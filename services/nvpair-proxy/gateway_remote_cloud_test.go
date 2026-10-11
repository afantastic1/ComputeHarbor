// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"nvpair-shared/clustertrust"
	"nvpair-shared/clustertrusttest"
)

func TestPairedCloudGatewayRequiresExplicitHostAuthorization(t *testing.T) {
	aDir, bDir := t.TempDir(), t.TempDir()
	clustertrusttest.Join(t, aDir, "cluster", "a")
	clustertrusttest.Join(t, bDir, "cluster", "b")
	pinClusterPeer(t, aDir, bDir, "b")
	pinClusterPeer(t, bDir, aDir, "a")
	var upstreamHits atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		upstreamHits.Add(1)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"chatcmpl-1","model":"deepseek-chat","choices":[]}`)
	}))
	defer upstream.Close()

	profile := anyProfile(t)
	host := testProxy(profile, NewDiscovery(), profile.StandalonePort)
	host.mesh = clustertrust.Open(bDir)
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", upstream.URL, 1)
	if err := host.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("configure Provider: %v", err)
	}
	host.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	budget, err := newCloudBudgetLedger(filepath.Join(t.TempDir(), "budget.json"))
	if err != nil {
		t.Fatalf("create Cloud budget ledger: %v", err)
	}
	host.gatewayDispatcher.budget = budget
	host.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "host-provider-key", nil }
	host.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyLocalOnly,
		monthlyBudgetUSD: 5, perRequestMaxEstimatedCostUSD: 1,
	})
	server := httptest.NewUnstartedServer(http.HandlerFunc(host.soleFacade().handleClusterIngress))
	server.TLS = host.mesh.ServerTLSConfig()
	server.StartTLS()
	defer server.Close()

	caller := testProxy(profile, NewDiscovery(), profile.StandalonePort+1)
	caller.mesh = clustertrust.Open(aDir)
	peer := nodeFor(t, "host", server.URL)
	peer.ClusterUUID = "b"
	caller.soleFacade().discovery.SetSubscribed([]Node{peer})
	caller.gatewayDispatcher.setClientToken("local-client-token-12345678901234567890")
	caller.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{cloudEnabled: true, policy: gatewayPolicyPreferLocal})
	call := func() *httptest.ResponseRecorder {
		request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
			`{"model":"cloud/deepseek/deepseek-chat","messages":[]}`,
		))
		request.Header.Set("Authorization", "Bearer local-client-token-12345678901234567890")
		response := httptest.NewRecorder()
		caller.serveGateway(response, request)
		return response
	}

	unapproved := call()
	if unapproved.Code != http.StatusForbidden || !strings.Contains(unapproved.Body.String(), "cloud_not_allowed") {
		t.Fatalf("unapproved paired node response=%d %s", unapproved.Code, unapproved.Body.String())
	}
	if got := upstreamHits.Load(); got != 0 {
		t.Fatalf("unapproved paired node reached Provider %d times", got)
	}

	callerCert := clusterCertificateDER(t, filepath.Join(aDir, "node.crt"))
	host.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyLocalOnly,
		monthlyBudgetUSD: 5, perRequestMaxEstimatedCostUSD: 1,
		authorizedCloudNodes: map[string]string{"a": cloudCertificateFingerprint([]byte("previous-pairing-certificate"))},
	})
	stalePairing := call()
	if stalePairing.Code != http.StatusForbidden {
		t.Fatalf("re-paired node with stale certificate authorization response=%d %s", stalePairing.Code, stalePairing.Body.String())
	}
	if got := upstreamHits.Load(); got != 0 {
		t.Fatalf("stale paired identity reached Provider %d times", got)
	}

	host.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyLocalOnly,
		monthlyBudgetUSD: 5, perRequestMaxEstimatedCostUSD: 1,
		authorizedCloudNodes: map[string]string{"a": cloudCertificateFingerprint(callerCert)},
	})
	approved := call()
	if approved.Code != http.StatusOK {
		t.Fatalf("approved paired node response=%d %s", approved.Code, approved.Body.String())
	}
	if got := upstreamHits.Load(); got != 1 {
		t.Fatalf("approved paired node reached Provider %d times, want one", got)
	}
	server.Close()
	offline := call()
	if offline.Code != http.StatusBadGateway {
		t.Fatalf("offline Cloud host response=%d %s, want a Gateway failure", offline.Code, offline.Body.String())
	}
	if got := upstreamHits.Load(); got != 1 {
		t.Fatalf("offline Cloud host reached Provider %d times, want one total", got)
	}
}

func TestPairedCloudAuthorizationRevocationCancelsActiveProviderRequest(t *testing.T) {
	aDir, bDir := t.TempDir(), t.TempDir()
	clustertrusttest.Join(t, aDir, "cluster", "a")
	clustertrusttest.Join(t, bDir, "cluster", "b")
	pinClusterPeer(t, aDir, bDir, "b")
	pinClusterPeer(t, bDir, aDir, "a")
	providerStarted := make(chan struct{})
	providerCanceled := make(chan struct{})
	var upstreamHits atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstreamHits.Add(1)
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		close(providerStarted)
		<-r.Context().Done()
		close(providerCanceled)
	}))
	defer upstream.Close()

	profile := anyProfile(t)
	host := testProxy(profile, NewDiscovery(), profile.StandalonePort)
	host.mesh = clustertrust.Open(bDir)
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", upstream.URL, 1)
	if err := host.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("configure Provider: %v", err)
	}
	host.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	budget, err := newCloudBudgetLedger(filepath.Join(t.TempDir(), "budget.json"))
	if err != nil {
		t.Fatalf("create Cloud budget ledger: %v", err)
	}
	host.gatewayDispatcher.budget = budget
	host.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "host-provider-key", nil }
	host.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyLocalOnly,
		monthlyBudgetUSD: 5, perRequestMaxEstimatedCostUSD: 1,
		authorizedCloudNodes: map[string]string{"a": cloudCertificateFingerprint(clusterCertificateDER(t, filepath.Join(aDir, "node.crt")))},
	})
	hostServer := httptest.NewUnstartedServer(http.HandlerFunc(host.soleFacade().handleClusterIngress))
	hostServer.TLS = host.mesh.ServerTLSConfig()
	hostServer.StartTLS()
	defer hostServer.Close()

	caller := testProxy(profile, NewDiscovery(), profile.StandalonePort+1)
	caller.mesh = clustertrust.Open(aDir)
	peer := nodeFor(t, "host", hostServer.URL)
	peer.ClusterUUID = "b"
	caller.soleFacade().discovery.SetSubscribed([]Node{peer})
	caller.gatewayDispatcher.setClientToken("local-client-token-12345678901234567890")
	caller.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{cloudEnabled: true, policy: gatewayPolicyPreferLocal})
	requestContext, cancelRequest := context.WithCancel(context.Background())
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[],"stream":true}`,
	)).WithContext(requestContext)
	request.Header.Set("Authorization", "Bearer local-client-token-12345678901234567890")
	response := httptest.NewRecorder()
	requestDone := make(chan struct{})
	go func() {
		caller.serveGateway(response, request)
		close(requestDone)
	}()
	select {
	case <-providerStarted:
	case <-time.After(5 * time.Second):
		cancelRequest()
		t.Fatal("authorized remote request did not reach the Provider")
	}
	if err := os.Remove(filepath.Join(bDir, "trusted", "a.json")); err != nil {
		cancelRequest()
		t.Fatalf("revoke caller pin: %v", err)
	}
	select {
	case <-providerCanceled:
	case <-time.After(3 * time.Second):
		cancelRequest()
		t.Fatal("removing the paired-node pin did not cancel the Provider request")
	}
	cancelRequest()
	select {
	case <-requestDone:
	case <-time.After(3 * time.Second):
		t.Fatal("caller request did not finish after authorization revocation")
	}
	if got := upstreamHits.Load(); got != 1 {
		t.Fatalf("revoked remote request reached Provider %d times, want one", got)
	}
}

func clusterCertificateDER(t *testing.T, path string) []byte {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read cluster certificate: %v", err)
	}
	block, _ := pem.Decode(data)
	if block == nil {
		t.Fatal("cluster certificate has no PEM block")
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatalf("parse cluster certificate: %v", err)
	}
	return certificate.Raw
}

func pinClusterPeer(t *testing.T, receiverDir, peerDir, peerUUID string) {
	t.Helper()
	certificate, err := os.ReadFile(filepath.Join(peerDir, "node.crt"))
	if err != nil {
		t.Fatalf("read peer certificate: %v", err)
	}
	if err := os.MkdirAll(filepath.Join(receiverDir, "trusted"), 0700); err != nil {
		t.Fatalf("create peer trust directory: %v", err)
	}
	data, err := json.Marshal(map[string]string{"nodeUuid": peerUUID, "certPem": string(certificate)})
	if err != nil {
		t.Fatalf("encode peer pin: %v", err)
	}
	if err := os.WriteFile(filepath.Join(receiverDir, "trusted", peerUUID+".json"), data, 0600); err != nil {
		t.Fatalf("write peer pin: %v", err)
	}
}
