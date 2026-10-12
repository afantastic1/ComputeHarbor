// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"nvpair-ui-broker/relay"
)

// TestLocalEnginePortFallback: with no engine-manager supervised, the broker
// falls back to the engine's stock port rather than failing — so a broker
// running without engine-manager still advertises at the sensible default.
func TestLocalEnginePortFallback(t *testing.T) {
	b := &Broker{} // no engine-manager worker
	if got, ok := b.localEnginePort("ollama", defaultOllamaPort); !ok || got != defaultOllamaPort {
		t.Errorf("no engine-manager: localEnginePort = (%d, %v), want (%d, true)", got, ok, defaultOllamaPort)
	}
	if got, ok := b.localEnginePort("lmstudio", defaultLMStudioPort); !ok || got != defaultLMStudioPort {
		t.Errorf("no engine-manager: localEnginePort = (%d, %v), want (%d, true)", got, ok, defaultLMStudioPort)
	}
}

func TestRunningEnginePort(t *testing.T) {
	for _, tc := range []struct {
		name string
		raw  string
		port int
		ok   bool
	}{
		{name: "running", raw: `{"running":true,"port":1235}`, port: 1235, ok: true},
		{name: "stopped is authoritative", raw: `{"running":false,"port":1235}`},
		{name: "running without a port", raw: `{"running":true,"port":0}`},
		{name: "malformed response", raw: `{`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			port, ok := runningEnginePort([]byte(tc.raw))
			if port != tc.port || ok != tc.ok {
				t.Fatalf("runningEnginePort = (%d, %v), want (%d, %v)", port, ok, tc.port, tc.ok)
			}
		})
	}
}

func TestHostedMNNAdvertisementRequiresHealthyBackendAndReadyFacade(t *testing.T) {
	profile, ok := engineProxyProfileFor("mnn")
	if !ok {
		t.Fatal("MNN proxy profile missing")
	}
	for _, tc := range []struct {
		name           string
		backendHealthy bool
		facadeReady    bool
		backendPort    int
		facadePort     int
		want           bool
	}{
		{name: "both ready", backendHealthy: true, facadeReady: true, backendPort: 14325, facadePort: 14324, want: true},
		{name: "backend down", facadeReady: true, backendPort: 14325, facadePort: 14324},
		{name: "facade down", backendHealthy: true, backendPort: 14325, facadePort: 14324},
		{name: "self forwarding", backendHealthy: true, facadeReady: true, backendPort: 14324, facadePort: 14324},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := shouldAdvertiseEngine(profile, tc.backendHealthy, tc.facadeReady, tc.backendPort, tc.facadePort); got != tc.want {
				t.Fatalf("shouldAdvertiseEngine = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestPostPortGateAdvertisementProfilesExcludeHostedEngines(t *testing.T) {
	profiles := engineProxyProfilesExceptOwnership(hostedEngine)
	wantCount := 0
	for _, profile := range engineProxyProfiles {
		if profile.Ownership != hostedEngine {
			wantCount++
		}
	}
	if len(profiles) != wantCount {
		t.Fatalf("post-gate profiles count = %d, want %d", len(profiles), wantCount)
	}
	for _, profile := range profiles {
		if profile.Ownership == hostedEngine {
			t.Fatalf("hosted engine %q remained in the post-gate advertisement loop", profile.Name)
		}
	}
}

func TestLMStudioFallbackNeverAdvertisesItsProxy(t *testing.T) {
	proxyClient, proxyServer := net.Pipe()
	defer proxyClient.Close()
	defer proxyServer.Close()
	proxy := &proxyProcess{
		peer:        NewPeer(NewCodec(proxyClient)),
		facadeState: readyFacade(lmstudioProxyProfile.Name, defaultLMStudioPort),
	}
	go proxy.peer.Serve(nil, nil)

	localBackend := make(chan proxyLocalBackend, 1)
	go func() {
		codec := NewCodec(proxyServer)
		msg, err := codec.Read()
		if err != nil {
			return
		}
		var got proxyLocalBackend
		if json.Unmarshal(msg.Params, &got) == nil {
			localBackend <- got
		}
		_ = codec.Respond(msg.ID, map[string]bool{"ok": true})
	}()

	b := &Broker{regCache: relay.NewRegistrationCache()}
	b.setLMStudioProxy(proxy)

	// A nil client is intentional: collision detection must short-circuit before
	// any health request can mistake the proxy for LM Studio.
	b.reconcileEngineAdvertisement(lmstudioProxyProfile, nil)
	if got := b.regCache.Snapshot(); len(got) != 0 {
		t.Fatalf("LM Studio proxy was advertised as an engine: %+v", got)
	}
	select {
	case got := <-localBackend:
		if got.Port != 0 || got.Healthy {
			t.Fatalf("proxy listener was retained as the local backend: %+v", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("LM Studio proxy did not receive a cleared local backend")
	}
}

// TestLMStudioFallbackDoesNotOverwriteKnownBackend: when engine-manager is
// unavailable, localEnginePort hands back the stock-port fallback (1234, the
// facade port). The advertiser must not promote that guess into the confirmed
// backend cache, or a later proxy-ready reconcile mistakes the compatibility
// proxy on :1234 for the backend and disables managed mode.
func TestLMStudioFallbackDoesNotOverwriteKnownBackend(t *testing.T) {
	b := &Broker{regCache: relay.NewRegistrationCache()}
	b.lmstudioState().backendPort.Store(managedLMStudioBackendStart)

	// No engine-manager and no proxy: the fallback path that used to poison
	// the cache with defaultLMStudioPort.
	b.reconcileEngineAdvertisement(lmstudioProxyProfile, nil)

	if got := int(b.lmstudioState().backendPort.Load()); got != managedLMStudioBackendStart {
		t.Fatalf("backend cache = %d, want %d (fallback must not overwrite the confirmed backend)", got, managedLMStudioBackendStart)
	}
}

func TestEngineAdvertisementProfilesAfterPortGatesContainEachEngineOnce(t *testing.T) {
	counts := make(map[string]int)
	for _, profile := range engineAdvertisementProfilesAfterPortGates() {
		counts[profile.Name]++
	}

	for _, engine := range []string{"ollama", "lmstudio", "llamacpp", "strata"} {
		if counts[engine] != 1 {
			t.Errorf("%s advertisement profile count = %d, want 1", engine, counts[engine])
		}
	}
	if counts["mnn"] != 0 {
		t.Errorf("hosted MNN advertisement profile count = %d, want 0", counts["mnn"])
	}
}

func TestStrataAdvertiserTracksConfiguredEndpointHealth(t *testing.T) {
	isolateOllamaHostTestConfig(t)
	backend := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" {
			t.Errorf("request path = %q, want /v1/models", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		if _, err := w.Write([]byte(`{"data":[{"id":"qwen-local"}]}`)); err != nil {
			t.Errorf("write model inventory: %v", err)
		}
	}))
	t.Cleanup(backend.Close)
	_, portText, err := net.SplitHostPort(backend.Listener.Addr().String())
	if err != nil {
		t.Fatalf("parse Strata test address: %v", err)
	}
	backendPort, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatalf("parse Strata test port: %v", err)
	}
	settings := strataSettings{Enabled: true, Endpoint: backend.URL}
	if err := saveStrataSettings(settings); err != nil {
		t.Fatalf("save Strata settings: %v", err)
	}

	profile, ok := engineProxyProfileFor("strata")
	if !ok {
		t.Fatal("Strata proxy profile missing")
	}
	b := &Broker{regCache: relay.NewRegistrationCache()}
	updates := attachAdvertiserProxy(t, b, profile, profile.FacadePort)

	b.reconcileEngineAdvertisement(profile, backend.Client())
	registrations := b.regCache.Snapshot()
	if len(registrations) != 1 || registrations[0].Service != profile.DiscoveryService || registrations[0].Port != profile.FacadePort {
		t.Fatalf("healthy Strata registration = %+v, want %s on %d", registrations, profile.DiscoveryService, profile.FacadePort)
	}
	if got := <-updates; !got.Healthy || got.Port != backendPort || got.Engine != profile.Name {
		t.Fatalf("healthy Strata local backend = %+v", got)
	}

	backend.Close()
	b.reconcileEngineAdvertisement(profile, backend.Client())
	if got := b.regCache.Snapshot(); len(got) != 0 {
		t.Fatalf("stopped Strata remained advertised: %+v", got)
	}
	if got := <-updates; got.Healthy || got.Port != backendPort {
		t.Fatalf("stopped Strata local backend = %+v", got)
	}
}

func attachAdvertiserProxy(
	t *testing.T,
	b *Broker,
	profile engineProxyProfile,
	port int,
) <-chan proxyLocalBackend {
	t.Helper()
	proxyClient, proxyServer := net.Pipe()
	t.Cleanup(func() {
		_ = proxyClient.Close()
		_ = proxyServer.Close()
	})
	proxy := &proxyProcess{
		peer:        NewPeer(NewCodec(proxyClient)),
		facadeState: readyFacade(profile.Name, port),
	}
	go proxy.peer.Serve(nil, nil)
	b.setEngineProxyHandle(profile, proxy)

	updates := make(chan proxyLocalBackend, 4)
	go func() {
		codec := NewCodec(proxyServer)
		for {
			msg, err := codec.Read()
			if err != nil {
				return
			}
			var update proxyLocalBackend
			if json.Unmarshal(msg.Params, &update) == nil {
				updates <- update
			}
			_ = codec.Respond(msg.ID, map[string]bool{"ok": true})
		}
	}()
	return updates
}

// TestProxyListenPortNoProxy: with no proxy supervised, proxyListenPort is 0,
// so the self-forward collision check (port == proxy port) never falsely trips.
func TestProxyListenPortNoProxy(t *testing.T) {
	b := &Broker{} // no proxy worker
	if got := b.proxyListenPort(); got != 0 {
		t.Errorf("no proxy: proxyListenPort = %d, want 0", got)
	}
}

func TestOllamaFacadeIsPendingBackend(t *testing.T) {
	b := &Broker{}
	b.ollamaState().managedFacade.Store(true)
	b.ollamaState().backendPort.Store(managedOllamaFacadePort)
	if !b.ollamaFacadeIsPendingBackend() {
		t.Fatal("managed facade must block liveness probes while the backend still points at 11434")
	}
	b.ollamaState().backendPort.Store(11435)
	if b.ollamaFacadeIsPendingBackend() {
		t.Fatal("liveness probes should resume after the backend moves off 11434")
	}
	b.managedOllamaBackend.Store(11436)
	if !b.ollamaFacadeIsPendingBackend() {
		t.Fatal("a pending 11435 to 11436 move must keep liveness probes gated")
	}
	b.managedOllamaBackend.Store(0)
	b.ollamaMoveInFlight.Store(true)
	if !b.ollamaFacadeIsPendingBackend() {
		t.Fatal("an in-flight backend move must keep liveness probes gated")
	}
	b.ollamaMoveInFlight.Store(false)

	b.ollamaState().managedFacade.Store(false)
	b.ollamaState().backendPort.Store(managedOllamaFacadePort)
	b.setProxy(&proxyProcess{
		facadeState: readyFacade(ollamaProxyProfile.Name, managedOllamaFacadePort),
	})
	if !b.ollamaFacadeIsPendingBackend() {
		t.Fatal("recovery must keep probes blocked until the proxy vacates 11434")
	}
}
