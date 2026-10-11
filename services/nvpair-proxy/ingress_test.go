// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
)

// TestResolveCandidatesUnclusteredDropsRelayPeers is the core isolation
// assertion: an unclustered node (nil mesh) must not route inference to
// relay-discovered peers — even one advertising a cluster principal — because it
// holds no pin to reach them over mTLS. Only an explicit, user-added manual node
// survives, dialed plaintext.
func TestResolveCandidatesUnclusteredDropsRelayPeers(t *testing.T) {
	disc := NewDiscovery()
	disc.SetSubscribed([]Node{{
		ID: "peer-a", Host: "peer-a", Port: 11434,
		Addresses:   []string{"192.0.2.10"},
		IP:          "192.0.2.10",
		ClusterUUID: "cluster-uuid-a",
	}})
	disc.AddManual(Node{
		ID: "manual-x", Host: "manual-x", Port: 11434,
		Addresses: []string{"192.0.2.20"}, IP: "192.0.2.20",
	})
	p := testProxy(anyProfile(t), disc, 11435) // mesh nil => unclustered

	cands := p.soleFacade().resolveCandidates("")
	if len(cands) != 1 {
		t.Fatalf("unclustered candidate set = %+v, want exactly the manual node", cands)
	}
	if cands[0].id != "manual-x" || cands[0].peerUUID != "" || cands[0].url.Scheme != "http" {
		t.Fatalf("unclustered candidate = %+v, want plaintext manual-x with no peerUUID", cands[0])
	}
}

// TestHandlePlainRejectsNonLoopback proves the plaintext personality is
// loopback-only: a LAN caller is refused (closing the former open-relay), so
// peers cannot use the plaintext path at all.
func TestHandlePlainRejectsNonLoopback(t *testing.T) {
	p := testProxy(anyProfile(t), NewDiscovery(), 11435)
	req := httptest.NewRequest(http.MethodPost, "/api/generate", nil)
	req.RemoteAddr = "192.0.2.50:40000"
	rec := httptest.NewRecorder()

	p.soleFacade().handlePlain(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("non-loopback plaintext status = %d, want %d", rec.Code, http.StatusForbidden)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Errorf("Access-Control-Allow-Origin = %q, want no CORS header on the refusal", got)
	}
}

// Preflight is subject to the same ingress gate as ordinary requests.
func TestHandlePlainRejectsPreflightAtLoopbackGate(t *testing.T) {
	p := testProxy(anyProfile(t), NewDiscovery(), 11435)
	req := httptest.NewRequest(http.MethodOptions, "/api/generate", nil)
	req.RemoteAddr = "192.0.2.50:40000"
	rec := httptest.NewRecorder()

	p.soleFacade().handlePlain(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("preflight status = %d, want %d", rec.Code, http.StatusForbidden)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Errorf("Access-Control-Allow-Origin = %q, want no CORS header", got)
	}
}

// engine-manager marks its own identity probes so the compatibility facade can
// never be adopted as the engine itself. The rejection has to name the engine
// this proxy fronts, or the operator reading it is sent to the wrong process —
// which is why this runs per engine rather than asserting only the status.
func TestHandlePlainRejectsEngineIdentityProbe(t *testing.T) {
	forEachEngine(t, func(t *testing.T, tc engineCase) {
		p := testProxy(tc.profile, NewDiscovery(), tc.profile.StandalonePort)
		req := httptest.NewRequest(http.MethodGet, "/api/version", nil)
		req.RemoteAddr = "127.0.0.1:40000"
		req.Header.Set(engineIdentityProbeHeader, "1")
		rec := httptest.NewRecorder()

		p.soleFacade().handlePlain(rec, req)
		if rec.Code != http.StatusConflict {
			t.Fatalf("identity probe status = %d, want %d", rec.Code, http.StatusConflict)
		}
		if body := rec.Body.String(); !strings.Contains(body, tc.profile.DisplayName) {
			t.Fatalf("rejection does not name %s: %s", tc.profile.DisplayName, body)
		}
	})
}

// TestHandleClusterIngressUnclusteredForbids proves the mTLS ingress fails
// closed on an unclustered node (nil mesh): with no cluster identity there are
// no pins, so every caller is rejected.
func TestHandleClusterIngressUnclusteredForbids(t *testing.T) {
	p := testProxy(anyProfile(t), NewDiscovery(), 11435)
	req := httptest.NewRequest(http.MethodPost, "/api/generate", nil) // no client cert
	rec := httptest.NewRecorder()

	p.soleFacade().handleClusterIngress(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("unclustered ingress status = %d, want %d", rec.Code, http.StatusForbidden)
	}
}

func TestIsLoopbackRemote(t *testing.T) {
	for _, c := range []struct {
		addr string
		want bool
	}{
		{"127.0.0.1:5000", true},
		{"[::1]:5000", true},
		{"192.168.1.10:5000", false},
		{"10.0.0.5:80", false},
		{"", false},
		{"garbage", false},
	} {
		if got := isLoopbackRemote(c.addr); got != c.want {
			t.Errorf("isLoopbackRemote(%q) = %v, want %v", c.addr, got, c.want)
		}
	}
}

func TestLocalReverseProxyUsesSharedPlainTransport(t *testing.T) {
	p := testProxy(anyProfile(t), NewDiscovery(), 11435)
	shared := p.plainHTTPTransport()
	target := &url.URL{Scheme: "http", Host: "127.0.0.1:1"}
	rp := p.soleFacade().newLocalReverseProxy(target)
	tr, ok := rp.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("Transport type = %T, want *http.Transport", rp.Transport)
	}
	if tr != shared {
		t.Fatal("ingress reverse proxy did not use the shared plain Transport")
	}
}

func TestStrataLocalIngressUsesConfiguredLoopbackAPIKey(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "Bearer configured-key" {
			t.Errorf("Authorization = %q, want configured Strata credential", got)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()
	parsed, err := url.Parse(upstream.URL)
	if err != nil {
		t.Fatalf("parse upstream URL: %v", err)
	}
	port, err := strconv.Atoi(parsed.Port())
	if err != nil {
		t.Fatalf("parse upstream port: %v", err)
	}
	profile, ok := profileFor("strata")
	if !ok {
		t.Fatal("Strata profile missing")
	}
	proxy := testProxy(profile, NewDiscovery(), profile.StandalonePort)
	facade := proxy.soleFacade()
	if err := facade.setLocalBackend(localBackend{
		Engine: "strata", Host: "127.0.0.1", Port: port, Healthy: true, APIKey: "configured-key",
	}); err != nil {
		t.Fatalf("set local Strata backend: %v", err)
	}
	req := httptest.NewRequest(http.MethodGet, "/v1/models", nil)
	rec := httptest.NewRecorder()
	target, ok := facade.localBackendTarget()
	if !ok {
		t.Fatal("configured Strata backend was not available")
	}
	facade.reverseProxyToLocal(rec, req, target)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("local ingress status = %d, want %d", rec.Code, http.StatusNoContent)
	}
}

func TestStrataModelListFanoutUsesConfiguredLoopbackAPIKey(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "Bearer configured-key" {
			t.Errorf("Authorization = %q, want configured Strata credential", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"object":"list","data":[{"id":"qwen-local","object":"model"}]}`))
	}))
	defer upstream.Close()
	target, err := url.Parse(upstream.URL)
	if err != nil {
		t.Fatalf("parse upstream URL: %v", err)
	}
	profile, ok := profileFor("strata")
	if !ok {
		t.Fatal("Strata profile missing")
	}
	proxy := testProxy(profile, NewDiscovery(), profile.StandalonePort)
	modelListRoute, ok := profile.routeFor(http.MethodGet, "/v1/models")
	if !ok {
		t.Fatal("Strata model-list route missing")
	}
	recorder := httptest.NewRecorder()
	status, err := proxy.soleFacade().serveModelList(
		recorder,
		httptest.NewRequest(http.MethodGet, "/v1/models", nil),
		modelListRoute,
		[]candidate{{id: "self", url: target, localAPIKey: "configured-key"}},
	)
	if err != nil {
		t.Fatalf("serve Strata model list: %v", err)
	}
	if status != http.StatusOK || recorder.Code != http.StatusOK {
		t.Fatalf("model-list status = %d / %d, want 200", status, recorder.Code)
	}
}
