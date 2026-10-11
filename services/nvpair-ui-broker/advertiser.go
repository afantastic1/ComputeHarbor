// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"time"

	"nvpair-shared/httpcon"
	"nvpair-shared/noderec"
)

// An engine's stock port is its FacadePort in the engine table, used ONLY as a
// fallback when engine-manager cannot report the real one. Never hardcode the
// advertise or health port: the product-default proxy takes Ollama's 11434, so
// a fixed 11434 would advertise the proxy as Ollama and make the proxy — and
// peers — self-forward into a loop. The real port is resolved per poll via
// localEnginePort.
var (
	defaultOllamaPort   = ollamaProxyProfile.FacadePort
	defaultLMStudioPort = lmstudioProxyProfile.FacadePort
)

const (
	// engineManagerHTTPPort is the fixed LAN port the broker tells
	// nvpair-engine-manager to serve its HTTP surface (/v1/models) on, and the port
	// it registers as the em service so peers' daemons can fetch this node's
	// model list. Fixed like node-info's :14318 (next free in the 143xx range);
	// the broker knows it, so no dynamic port handshake is needed.
	engineManagerHTTPPort = 14322

	// engineControlPort is the fixed LAN port the broker tells
	// nvpair-engine-manager to serve its cluster-scoped mTLS remote-control surface
	// (the ec service: remote install/pull/start/stop + engine status) on. Unlike
	// em (plain, model list) it's pin-based mTLS and only binds when this node is
	// clustered. Next free after em in the 143xx range.
	engineControlPort = 14323

	// autoAdvertiseInterval is how often the broker polls a local engine to
	// decide whether to register it with (or unregister it from) the discovery
	// daemon (a 5s cadence).
	autoAdvertiseInterval = 5 * time.Second
)

// runHostedEngineAdvertisements starts hosted engines immediately because
// their availability does not depend on Ollama or LM Studio port ownership.
func (b *Broker) runHostedEngineAdvertisements(ctx context.Context) {
	b.runEngineAdvertisements(ctx, engineProxyProfilesForOwnership(hostedEngine))
}

// runNonHostedEngineAdvertisements keeps port-gated engine reconciliation in
// its existing lifecycle while hosted engines continue on their independent
// loop.
func (b *Broker) runNonHostedEngineAdvertisements(ctx context.Context) {
	b.runEngineAdvertisements(ctx, engineProxyProfilesExceptOwnership(hostedEngine))
}

func engineProxyProfilesForOwnership(ownership engineOwnership) []engineProxyProfile {
	profiles := make([]engineProxyProfile, 0, len(engineProxyProfiles))
	for _, profile := range engineProxyProfiles {
		if profile.Ownership == ownership {
			profiles = append(profiles, profile)
		}
	}
	return profiles
}

func engineProxyProfilesExceptOwnership(ownership engineOwnership) []engineProxyProfile {
	profiles := make([]engineProxyProfile, 0, len(engineProxyProfiles))
	for _, profile := range engineProxyProfiles {
		if profile.Ownership != ownership {
			profiles = append(profiles, profile)
		}
	}
	return profiles
}

func (b *Broker) runEngineAdvertisements(ctx context.Context, profiles []engineProxyProfile) {
	client := &http.Client{Timeout: 2 * time.Second}
	ticker := time.NewTicker(autoAdvertiseInterval)
	defer ticker.Stop()

	b.reconcileEngineAdvertisements(client, profiles)

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			b.reconcileEngineAdvertisements(client, profiles)
		}
	}
}

// reconcileEngineAdvertisement checks one local engine and brings its discovery
// registration into line with the backend and facade health gates. The
// advertised endpoint is the facade port; the engine's real (loopback) port is
// a private detail handed only to the local proxy via node/set-local-backend.
//
//   - engine healthy + proxy up -> register {service, facade port} + set-local-backend{enginePort, healthy}
//   - otherwise                  -> unregister service + clear the proxy's local backend
//
// The model list is not carried here — it lives on engine-manager's em
// /v1/models endpoint (registered separately), which peers fetch during
// enrichment.
func (b *Broker) reconcileEngineAdvertisements(client *http.Client, profiles []engineProxyProfile) {
	for _, profile := range profiles {
		b.reconcileEngineAdvertisement(profile, client)
	}
}

func (b *Broker) reconcileEngineAdvertisement(profile engineProxyProfile, client *http.Client) {
	b.engineConfigMu.Lock()
	defer b.engineConfigMu.Unlock()
	if profile.Name == ollamaProxyProfile.Name && b.ollamaFacadeIsPendingBackend() {
		b.unregisterService(profile.DiscoveryService)
		b.setProxyLocalBackend(b.engineProxyHandle(profile), profile.Name, 0, false)
		return
	}
	if client == nil {
		client = &http.Client{Timeout: 2 * time.Second}
	}
	fallbackPort := profile.FacadePort
	if profile.Ownership == hostedEngine {
		fallbackPort = profile.EnginePortBase
	}
	enginePort, probe := b.localEnginePort(profile.Name, fallbackPort)
	facadePort := b.engineProxyListenPort(profile)
	if facadePort > 0 && enginePort == facadePort {
		if confirmed := int(b.engineProxy(profile).backendPort.Load()); confirmed > 0 && confirmed != facadePort {
			enginePort, probe = confirmed, true
		} else {
			enginePort, probe = 0, false
		}
	}
	backendHealthy := probe && enginePort > 0 && enginePort != facadePort
	if backendHealthy {
		if profile.External {
			settings, err := loadStrataSettings()
			backendHealthy = err == nil && probeStrata(settings).Connected
		} else {
			backendHealthy = checkEngineHealth(profile, client, enginePort)
		}
	}
	facadeReady := facadePort > 0
	if shouldAdvertiseEngine(profile, backendHealthy, facadeReady, enginePort, facadePort) {
		b.registerService(noderec.RegisterParams{Service: profile.DiscoveryService, Port: facadePort})
	} else {
		b.unregisterService(profile.DiscoveryService)
	}
	b.setProxyLocalBackend(b.engineProxyHandle(profile), profile.Name, enginePort, backendHealthy)
}

func shouldAdvertiseEngine(profile engineProxyProfile, backendHealthy, facadeReady bool, backendPort, facadePort int) bool {
	return backendHealthy && facadeReady && backendPort > 0 && facadePort > 0 && backendPort != facadePort && profile.DiscoveryService != ""
}

func (b *Broker) ollamaFacadeIsPendingBackend() bool {
	if b.managedOllamaBackend.Load() != 0 || b.ollamaMoveInFlight.Load() {
		return true
	}
	if int(b.ollamaState().backendPort.Load()) != managedOllamaFacadePort {
		return false
	}
	// Recovery flips managed mode off before it live-rebinds the proxy away
	// from :11434. Keep probes gated through that interval (and indefinitely if
	// the rebind fails) so the proxy can never be adopted as Ollama.
	return b.ollamaState().managedFacade.Load() || b.proxyListenPort() == managedOllamaFacadePort
}

// runAutoAdvertiseLMStudio is the LM Studio sibling of runAutoAdvertise: it
// polls the local LM Studio server and reconciles this node's lm service
// registration against it, so an LM Studio host appears on the cluster the same
// way an Ollama host does. Kept parallel to the Ollama path rather than folded
// into it: the two are a deliberate temporary pair, to be unified when the
// proxies are.
func (b *Broker) runAutoAdvertiseLMStudio(ctx context.Context) {
	client := &http.Client{Timeout: 2 * time.Second}
	ticker := time.NewTicker(autoAdvertiseInterval)
	defer ticker.Stop()

	b.reconcileAdvertiseLMStudio(client)

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			b.reconcileAdvertiseLMStudio(client)
		}
	}
}

// reconcileAdvertiseLMStudio brings this node's lm registration into line with
// the local LM Studio server, mirroring reconcileAdvertise: it advertises the
// promoted proxy port (never the engine) and hands the engine's loopback port to
// the LM Studio proxy via node/set-local-backend.
func (b *Broker) reconcileAdvertiseLMStudio(client *http.Client) {
	b.engineConfigMu.Lock()
	defer b.engineConfigMu.Unlock()
	enginePort, probe := b.localEnginePort("lmstudio", defaultLMStudioPort)
	proxyPort := b.lmstudioProxyListenPort()
	if proxyPort != 0 && enginePort == proxyPort {
		// engine-manager may be temporarily unavailable after managed setup.
		// Prefer the last confirmed backend, but never hand the proxy its own
		// listener as a local destination.
		if cached := int(b.lmstudioState().backendPort.Load()); cached > 0 && cached != proxyPort {
			enginePort = cached
		} else {
			enginePort = 0
			probe = false
		}
	}
	// enginePort may be a stock-port fallback: localEnginePort returns one when
	// engine-manager is unavailable, and it is indistinguishable from a real
	// status here. Use it to advertise this tick only; never write it to
	// lmstudioBackendPort. That cache's authoritative owners are the managed
	// facade setup and live engine:status. Promoting the fallback poisons the
	// cache while the proxy and engine restart together (as on the first invite),
	// which later makes the compatibility proxy on the facade port look like the
	// backend and wrongly disables managed mode.
	up := probe && proxyPort != 0 && enginePort != proxyPort && checkEngineHealth(lmstudioProxyProfile, client, enginePort)
	if up {
		b.registerService(noderec.RegisterParams{Service: noderec.ServiceLMStudio, Port: proxyPort})
		b.setProxyLocalBackend(b.getLMStudioProxy(), "lmstudio", enginePort, true)
	} else {
		b.unregisterService(noderec.ServiceLMStudio)
		b.setProxyLocalBackend(b.getLMStudioProxy(), "lmstudio", enginePort, false)
	}
}

// runAutoAdvertiseEngine reconciles an engine using the configured port
// recorded in its runtime profile, without compatibility-port reconciliation.
func (b *Broker) runAutoAdvertiseEngine(ctx context.Context, profile engineProxyProfile) {
	client := &http.Client{Timeout: 2 * time.Second}
	ticker := time.NewTicker(autoAdvertiseInterval)
	defer ticker.Stop()

	b.reconcileAdvertiseEngine(profile, client)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			b.reconcileAdvertiseEngine(profile, client)
		}
	}
}

func (b *Broker) reconcileAdvertiseEngine(profile engineProxyProfile, client *http.Client) {
	b.engineConfigMu.Lock()
	defer b.engineConfigMu.Unlock()

	enginePort := int(b.engineProxy(profile).backendPort.Load())
	proxyPort := b.engineProxyListenPort(profile)
	up := enginePort > 0 &&
		proxyPort > 0 &&
		enginePort != proxyPort &&
		checkEngineHealth(profile, client, enginePort)
	if up {
		b.registerService(noderec.RegisterParams{Service: profile.DiscoveryService, Port: proxyPort})
		b.setProxyLocalBackend(b.engineProxyHandle(profile), profile.Name, enginePort, true)
		return
	}
	b.unregisterService(profile.DiscoveryService)
	b.setProxyLocalBackend(b.engineProxyHandle(profile), profile.Name, enginePort, false)
}

// proxyLocalBackend is the node/set-local-backend payload: the loopback engine
// the proxy's cluster mTLS ingress forwards to, and the proxy's own self
// candidate on the local routing path.
type proxyLocalBackend struct {
	Engine  string `json:"engine"`
	Host    string `json:"host"`
	Port    int    `json:"port"`
	Healthy bool   `json:"healthy"`
	APIKey  string `json:"apiKey,omitempty"`
}

// setProxyLocalBackend hands the proxy its local (loopback) engine endpoint, or
// clears it (healthy=false) when the engine is down / unresolved. Best-effort
// and idempotent — re-sent on every reconcile so a freshly (re)spawned proxy
// re-learns its backend within one poll interval. A nil proxy is a no-op.
func (b *Broker) setProxyLocalBackend(p *proxyProcess, engine string, port int, healthy bool) {
	if p == nil {
		return
	}
	apiKey := ""
	if engine == "strata" {
		if settings, err := loadStrataSettings(); err == nil && settings.APIKeyEnv != "" {
			apiKey, _ = os.LookupEnv(settings.APIKeyEnv)
		}
	}
	b.callProxyManual(p, engine, "node/set-local-backend", proxyLocalBackend{
		Engine:  engine,
		Host:    "127.0.0.1",
		Port:    port,
		Healthy: healthy,
		APIKey:  apiKey,
	}, "local-backend")
}

// localEnginePort asks engine-manager for the port the named engine is actually
// serving on. The bool says whether there is a port worth probing. A valid
// running:false response is authoritative and returns false; only an unavailable
// manager/RPC retains the legacy stock-port fallback.
func (b *Broker) localEnginePort(engine string, fallback int) (int, bool) {
	if engine == "strata" {
		settings, err := loadStrataSettings()
		if err != nil || !settings.Enabled {
			return 0, false
		}
		parsed, err := url.Parse(settings.Endpoint)
		if err != nil {
			return 0, false
		}
		port, err := strconv.Atoi(parsed.Port())
		return port, err == nil && port > 0
	}
	em := b.getEngineMgr()
	if em == nil {
		return fallback, true
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	params, _ := json.Marshal(map[string]string{"engine": engine})
	result, rpcErr, err := em.Call(ctx, "engine:status", params)
	if err != nil || rpcErr != nil {
		return fallback, true
	}
	port, running := runningEnginePort(result)
	if running {
		if profile, ok := engineProxyProfileFor(engine); ok {
			b.engineProxy(profile).backendPort.Store(int32(port))
		}
	}
	return port, running
}

func runningEnginePort(result json.RawMessage) (int, bool) {
	var st struct {
		Running bool `json:"running"`
		Port    int  `json:"port"`
	}
	if json.Unmarshal(result, &st) != nil || !st.Running || st.Port <= 0 {
		return 0, false
	}
	return st.Port, true
}

// engineProxyListenPort returns an engine proxy's current listen port, or 0 if
// it is not supervised or has not reported ready. Used to refuse advertising an
// engine at its own proxy's port, which would be a self-forward loop, and to
// keep the compatibility fallback from mistaking a proxy that moved onto the
// facade port for the engine itself.
func (b *Broker) engineProxyListenPort(profile engineProxyProfile) int {
	if p := b.engineProxyHandle(profile); p != nil {
		if ready, port := p.Status(profile.Name); ready {
			return port
		}
	}
	return 0
}

func (b *Broker) proxyListenPort() int {
	return b.engineProxyListenPort(ollamaProxyProfile)
}

func (b *Broker) lmstudioProxyListenPort() int {
	return b.engineProxyListenPort(lmstudioProxyProfile)
}

// checkEngineHealth reports whether a local engine is answering on the given
// port, by probing the path its own liveness convention uses. The port is
// resolved per poll (see localEnginePort) rather than hardcoded, so the proxy
// is never mistaken for the engine it fronts.
func checkEngineHealth(profile engineProxyProfile, client *http.Client, port int) bool {
	resp, err := client.Get(fmt.Sprintf("http://localhost:%d%s", port, profile.HealthProbePath))
	if err != nil {
		return false
	}
	httpcon.DrainAndClose(resp.Body)
	return resp.StatusCode == http.StatusOK
}
