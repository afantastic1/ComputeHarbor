// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"nvpair-shared/modelselection"
)

const gatewayAddress = "127.0.0.1:14326"

const (
	gatewayReadHeaderTimeout = 5 * time.Second
	gatewayIdleTimeout       = 60 * time.Second
	gatewayMaxHeaderBytes    = 32 << 10
)

var gatewayEnginePreference = []string{"mnn", "ollama", "lmstudio"}

const (
	AUTO_ALIAS          = modelselection.AutoAlias
	AUTO_FAST_ALIAS     = modelselection.AutoFastAlias
	AUTO_BALANCED_ALIAS = modelselection.AutoBalancedAlias
	AUTO_BEST_ALIAS     = modelselection.AutoBestAlias
)

var autoAliases = []string{AUTO_ALIAS, AUTO_FAST_ALIAS, AUTO_BALANCED_ALIAS, AUTO_BEST_ALIAS}
var gatewayParameterPattern = regexp.MustCompile(`(?i)([0-9]+(?:\.[0-9]+)?)\s*[- ]?b([^a-z]|$)`)

type gatewayModel struct {
	ID      string `json:"id"`
	Object  string `json:"object"`
	OwnedBy string `json:"owned_by"`
}

type gatewayServer struct {
	srv *http.Server
}

func (p *Proxy) gatewayModels() []gatewayModel {
	return p.gatewayDispatcher.modelDirectory()
}

// 收集所有可用的运行时模型
func (p *Proxy) gatewayInventory() []modelselection.RuntimeModel {
	priority := p.prioritySnapshot()
	candidates := make(map[string]modelselection.RuntimeModel)
	for _, f := range p.enabledFacades() {
		engine := f.profile.Name
		// Reuse the Proxy's complete routing admission rules once per facade.
		// This is a read-only candidate view: no reservation is taken and the
		// final request still goes through resolveCandidates/reserveCandidate.
		routable := make(map[string]struct{})
		for _, candidate := range f.resolveCandidateViews() {
			routable[candidate.id] = struct{}{}
		}
		for _, node := range f.discovery.Nodes() {
			if _, ok := routable[node.ID]; !ok {
				continue
			}
			for _, model := range node.Models {
				if strings.TrimSpace(model) == "" {
					continue
				}
				key := engine + "\x00" + model + "\x00" + node.ID
				candidate := modelselection.RuntimeModel{NodeID: node.ID, Engine: engine, Available: true}
				candidate.Model = modelselection.ModelDescriptor{
					LogicalID: engine + ":" + model, EngineModelID: model,
					Family: modelFamily(model), ParameterCount: modelParameterCount(model),
					Capabilities: modelCapabilities(), Compatible: true,
				}
				if candidate.Model.ParameterCount > 0 {
					candidate.Model.EstimatedMemoryBytes = candidate.Model.ParameterCount * 2
				}
				for _, loaded := range node.LoadedModels {
					if f.profile.normalizeModel(loaded) == f.profile.normalizeModel(model) {
						candidate.Loaded = true
						break
					}
				}
				if resource, ok := priority.nodes[node.ID]; ok && !priority.schedulerUnavailable {
					candidate.PendingRequests = resource.pending
					candidate.PendingKnown = resource.pendingKnown
					candidate.GPUPressure = resource.gpuPressure
					candidate.GPUPressureKnown = resource.gpuPressureKnown
					candidate.InFlightReservations = resource.reservations
				}
				candidates[key] = candidate
			}
		}
	}
	result := make([]modelselection.RuntimeModel, 0, len(candidates))
	for _, candidate := range candidates {
		result = append(result, candidate)
	}
	return result
}

//自动选择匹配的网关模型

func (p *Proxy) resolveGatewayModel(alias string, requestBody []byte) *modelselection.Selection {
	return (modelselection.AutoModelSelector{}).Select(alias, modelselection.Requirements{
		Capabilities: requiredGatewayCapabilities(requestBody),
	}, p.gatewayInventory())
}

func modelParameterCount(model string) int64 {
	match := gatewayParameterPattern.FindStringSubmatch(model)
	if len(match) < 2 {
		return 0
	}
	value, _ := strconv.ParseFloat(match[1], 64)
	return int64(value * 1_000_000_000)
}

func modelFamily(model string) string {
	if index := strings.IndexAny(model, "-_"); index > 0 {
		return strings.ToLower(model[:index])
	}
	return strings.ToLower(model)
}

func modelCapabilities() map[string]bool {
	// Discovery currently provides model IDs only. Do not infer optional
	// capabilities from names. Local facades expose basic chat and SSE.
	// 没有可靠能力信息，就不宣称模型支持Tool Calling
	return map[string]bool{"chat": true, "streaming": true}
}

func requiredGatewayCapabilities(body []byte) map[string]bool {
	traits, err := parseGatewayRequestTraits(body) //gateway_dispatcher
	if err != nil {
		return nil
	}
	return traits.capabilities
}

func enginePreference(engine string) int {
	for index, preferred := range gatewayEnginePreference {
		if engine == preferred {
			return index
		}
	}
	return len(gatewayEnginePreference)
}

func (p *Proxy) resolveGatewayFacade(model string) *facade {
	var candidates []*facade
	var loaded []*facade
	for _, f := range p.enabledFacades() {
		servesModel := false
		loadsModel := false
		for _, node := range f.discovery.Nodes() {
			if !nodeAdvertisesModel(f.profile, node, model) {
				continue
			}
			servesModel = true
			for _, loadedModel := range node.LoadedModels {
				if f.profile.normalizeModel(loadedModel) == f.profile.normalizeModel(model) {
					loadsModel = true
					break
				}
			}
		}
		if servesModel {
			candidates = append(candidates, f)
		}
		if loadsModel {
			loaded = append(loaded, f)
		}
	}
	if len(loaded) > 0 {
		candidates = loaded
	}
	if len(candidates) == 1 {
		return candidates[0]
	}
	for _, engine := range gatewayEnginePreference {
		for _, f := range candidates {
			if f.profile.Name == engine {
				return f
			}
		}
	}
	return nil
}

func (p *Proxy) enableGateway(port int) (int, error) {
	if port != 14326 {
		return 0, fmt.Errorf("gateway port must be %d", 14326)
	}
	p.gatewayMu.Lock()
	defer p.gatewayMu.Unlock()
	if p.gateway != nil {
		return port, nil
	}
	listener, err := net.Listen("tcp", gatewayAddress)
	if err != nil {
		return 0, err
	}
	server := &gatewayServer{srv: &http.Server{
		Handler:           http.HandlerFunc(p.serveGateway),
		ReadHeaderTimeout: gatewayReadHeaderTimeout,
		IdleTimeout:       gatewayIdleTimeout,
		MaxHeaderBytes:    gatewayMaxHeaderBytes,
	}}
	p.gateway = server
	go func() { _ = server.srv.Serve(listener) }()
	return port, nil
}

func (p *Proxy) serveGateway(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet && r.URL.Path == "/healthz" {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"status":"ok"}`)
		return
	}
	if r.Method == http.MethodGet && r.URL.Path == "/v1/models" {
		if !p.gatewayDispatcher.authorizeModelDirectory(r.Header.Get("Authorization")) {
			writeGatewayError(w, http.StatusUnauthorized, "a valid Gateway bearer token is required", "unauthorized")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{"object": "list", "data": p.gatewayModels()})
		return
	}
	if r.Method != http.MethodPost || r.URL.Path != "/v1/chat/completions" {
		http.NotFound(w, r)
		return
	}
	body, model, bodyErr := bufferBodyAndModel(w, r)
	if bodyErr != nil {
		writeRequestBodyError(w, bodyErr)
		return
	}
	if model == "" {
		writeGatewayError(w, http.StatusBadRequest, "model is required", "invalid_request_error")
		return
	}
	p.gatewayDispatcher.dispatch(w, r, body, model)
}

func isGatewayAutoAlias(model string) bool {
	for _, alias := range autoAliases {
		if model == alias {
			return true
		}
	}
	return false
}

func rewriteGatewayModel(body []byte, model string) ([]byte, error) {
	var request map[string]json.RawMessage
	if err := json.Unmarshal(body, &request); err != nil {
		return nil, fmt.Errorf("decode gateway request: %w", err)
	}
	if request == nil {
		return nil, fmt.Errorf("gateway request must be a JSON object")
	}
	encodedModel, err := json.Marshal(model)
	if err != nil {
		return nil, fmt.Errorf("encode resolved gateway model: %w", err)
	}
	request["model"] = encodedModel
	return json.Marshal(request)
}

func writeGatewayError(w http.ResponseWriter, status int, message, kind string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"error": map[string]string{"message": message, "type": kind, "code": kind}})
}

func (p *Proxy) stopGateway() {
	p.gatewayMu.Lock()
	server := p.gateway
	p.gateway = nil
	p.gatewayMu.Unlock()
	if server != nil {
		_ = server.srv.Close()
	}
}
