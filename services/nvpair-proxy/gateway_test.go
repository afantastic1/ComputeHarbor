// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"nvpair-shared/modelselection"
	"nvpair-shared/schedulerwire"
)

func TestGatewayRequestCapabilitiesInspectStructuredImageParts(t *testing.T) {
	tests := []struct {
		name string
		body string
		want bool
	}{
		{name: "image content part", body: `{"messages":[{"content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,abc"}}]}]}`, want: true},
		{name: "ordinary text mentioning image", body: `{"messages":[{"content":"describe the image field in this JSON"}]}`},
		{name: "unrelated object field", body: `{"messages":[{"content":[{"type":"text","text":"image"}]}]}`},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got := requiredGatewayCapabilities([]byte(test.body))["vision"]
			if got != test.want {
				t.Fatalf("vision requirement = %v, want %v", got, test.want)
			}
		})
	}
}

func TestGatewayModelCapabilitiesDoNotInferFromNames(t *testing.T) {
	capabilities := modelCapabilities("ollama")
	if !capabilities["chat"] || !capabilities["streaming"] || capabilities["vision"] || capabilities["tools"] || capabilities["embeddings"] {
		t.Errorf("capabilities for unverified engine = %v, want chat/streaming only", capabilities)
	}
	strataCapabilities := modelCapabilities("strata")
	if !strataCapabilities["chat"] || !strataCapabilities["streaming"] || !strataCapabilities["tools"] || strataCapabilities["vision"] || strataCapabilities["json_schema"] {
		t.Errorf("capabilities for verified Strata model = %v, want chat/streaming/tools", strataCapabilities)
	}
}

func TestGatewayModelDirectoryUnionsEngineInventoriesWithoutNodeDetails(t *testing.T) {
	ollama, _ := profileFor("ollama")
	lmstudio, _ := profileFor("lmstudio")
	mnn, _ := profileFor("mnn")
	proxy := NewProxy(NewCodec(rwNop{}))
	proxy.facades = map[string]*facade{
		"ollama":   newFacade(proxy, ollama, NewDiscovery(), 11435),
		"lmstudio": newFacade(proxy, lmstudio, NewDiscovery(), 1234),
		"mnn":      newFacade(proxy, mnn, NewDiscovery(), 14325),
	}
	proxy.facades["ollama"].discovery.AddManual(Node{ID: "pc-secret", Models: []string{"qwen3-8b", "shared"}})
	proxy.facades["lmstudio"].discovery.AddManual(Node{ID: "peer-secret", Models: []string{"shared", "llama"}})
	proxy.facades["mnn"].discovery.AddManual(Node{ID: "phone-secret", Models: []string{"qwen3-1.7b"}})

	models := proxy.gatewayModels()
	want := []string{
		"llama", "local/lmstudio/shared", "local/mnn/qwen3-1.7b",
		"local/ollama/qwen3-8b", "local/ollama/shared", "qwen3-1.7b", "qwen3-8b",
	}
	for _, id := range want {
		found := false
		for _, model := range models {
			if model.ID == id && model.OwnedBy == "pair" {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("model list = %+v, missing id=%q owned_by=pair", models, id)
		}
	}
}

func TestGatewayModelDirectoryAdvertisesAutoAliases(t *testing.T) {
	proxy := NewProxy(nil)
	models := proxy.gatewayModels()
	for _, alias := range []string{"auto", "auto-fast", "auto-balanced", "auto-best"} {
		found := false
		for _, model := range models {
			if model.ID == alias {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("model list does not advertise %q", alias)
		}
	}
}

func TestGatewayModelDirectoryAdvertisesLocalAndCloudAliases(t *testing.T) {
	proxy := NewProxy(nil)
	models := proxy.gatewayModels()
	for _, alias := range []string{"auto-local", "auto-cloud"} {
		found := false
		for _, model := range models {
			if model.ID == alias {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("model list does not advertise %q", alias)
		}
	}
}

func TestGatewayAutoPoliciesSelectOnlyAdvertisedModels(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	mnn, _ := profileFor("mnn")
	ollamaServer := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer ollamaServer.Close()
	mnnServer := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer mnnServer.Close()
	proxy.facades = map[string]*facade{
		"ollama": newFacade(proxy, ollama, NewDiscovery(), 11435),
		"mnn":    newFacade(proxy, mnn, NewDiscovery(), 14325),
	}
	proxy.facades["ollama"].discovery.AddManual(nodeForModel(t, "pc", ollamaServer.URL, "qwen3-8b"))
	mnnNode := nodeForModel(t, "phone", mnnServer.URL, "qwen3-1.7b")
	mnnNode.LoadedModels = []string{"qwen3-1.7b"}
	proxy.facades["mnn"].discovery.AddManual(mnnNode)

	if got := proxy.resolveGatewayModel(AUTO_FAST_ALIAS, nil); got == nil || got.Model.EngineModelID != "qwen3-1.7b" || got.Engine != "mnn" {
		t.Fatalf("fast selection = %+v, want loaded phone model", got)
	}
	if got := proxy.resolveGatewayModel(AUTO_BEST_ALIAS, nil); got == nil || got.Model.EngineModelID != "qwen3-8b" || got.Engine != "ollama" {
		t.Fatalf("best selection = %+v, want larger PC model", got)
	}
	if got := proxy.resolveGatewayModel(AUTO_ALIAS, nil); got == nil || got.Model.EngineModelID != "qwen3-1.7b" {
		t.Fatalf("default auto selection = %+v, want balanced alias selection", got)
	}
}

func TestGatewayInventoryRetainsEveryModelDeploymentAndFreshNodeResources(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	serverA := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer serverA.Close()
	serverB := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer serverB.Close()
	proxy.facades = map[string]*facade{
		"ollama": newFacade(proxy, ollama, NewDiscovery(), 11435),
	}
	proxy.facades["ollama"].discovery.AddManual(nodeForModel(t, "pc-a", serverA.URL, "model-8b"))
	proxy.facades["ollama"].discovery.AddManual(nodeForModel(t, "pc-b", serverB.URL, "model-8b"))
	proxy.SetPrioritySnapshot(schedulerwire.Priority{
		Generation: 1,
		Nodes:      []string{"pc-b", "pc-a"},
		Ranks: []schedulerwire.NodeRank{
			{ID: "pc-a", Pending: 2, GPUPressure: 3, GPUPressureKnown: true},
			{ID: "pc-b", Pending: 0, GPUPressure: 0, GPUPressureKnown: true},
		},
	})

	inventory := proxy.gatewayInventory()
	if len(inventory) != 2 {
		t.Fatalf("inventory has %d deployments, want 2: %+v", len(inventory), inventory)
	}
	byNode := make(map[string]modelselection.RuntimeModel, len(inventory))
	for _, candidate := range inventory {
		byNode[candidate.NodeID] = candidate
	}
	if byNode["pc-a"].PendingRequests != 2 || !byNode["pc-a"].PendingKnown || byNode["pc-a"].GPUPressure != 3 || !byNode["pc-a"].GPUPressureKnown ||
		byNode["pc-a"].MemoryPressure != 0 || byNode["pc-a"].AvailableMemoryBytes != 0 {
		t.Errorf("pc-a resource signals = %+v, want pending=2 GPU pressure=3 and unknown memory", byNode["pc-a"])
	}
	if byNode["pc-b"].PendingRequests != 0 || !byNode["pc-b"].PendingKnown || byNode["pc-b"].GPUPressure != 0 || !byNode["pc-b"].GPUPressureKnown {
		t.Errorf("pc-b resource signals = %+v, want known idle values", byNode["pc-b"])
	}
}

func TestGatewayInventoryExcludesUndialableDeployments(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	discovery := NewDiscovery()
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer server.Close()
	discovery.AddManual(Node{ID: "bad-node", Models: []string{"model-32b"}})
	discovery.AddManual(nodeForModel(t, "good-node", server.URL, "model-8b"))
	proxy.facades = map[string]*facade{"ollama": newFacade(proxy, ollama, discovery, 11435)}

	selection := proxy.resolveGatewayModel(AUTO_BALANCED_ALIAS, nil)
	if selection == nil || selection.Model.EngineModelID != "model-8b" {
		t.Fatalf("selection = %+v, want routable 8B model", selection)
	}
	for _, candidate := range proxy.gatewayInventory() {
		if candidate.Model.EngineModelID == "model-32b" {
			t.Fatalf("inventory includes undialable deployment: %+v", candidate)
		}
	}
}

func TestGatewayInventoryKeepsModelOnRoutableDuplicateHostID(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	discovery := NewDiscovery()
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer server.Close()
	discovery.AddManual(nodeForModel(t, "alias-a", server.URL, "model-32b"))
	discovery.AddManual(nodeForModel(t, "alias-b", server.URL, "model-8b"))
	proxy.facades = map[string]*facade{"ollama": newFacade(proxy, ollama, discovery, 11435)}

	f := proxy.facades["ollama"]
	if got := f.resolveCandidates("model-8b"); len(got) != 1 || got[0].id != "alias-b" {
		t.Fatalf("official candidates for 8B = %+v, want alias-b", got)
	}
	for _, candidate := range proxy.gatewayInventory() {
		if candidate.NodeID == "alias-b" && candidate.Model.EngineModelID == "model-8b" {
			return
		}
	}
	t.Fatal("Gateway inventory omitted model advertised on second ID for shared host")
}

func TestGatewayInventoryIgnoresSchedulerResourcesWhenBrokerReportsDown(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	discovery := NewDiscovery()
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer server.Close()
	discovery.AddManual(nodeForModel(t, "pc", server.URL, "model-8b"))
	proxy.facades = map[string]*facade{"ollama": newFacade(proxy, ollama, discovery, 11435)}
	proxy.SetPrioritySnapshot(schedulerwire.Priority{
		Generation: 1,
		Nodes:      []string{"pc"},
		Ranks:      []schedulerwire.NodeRank{{ID: "pc", Pending: 5, GPUPressure: 3, GPUPressureKnown: true}},
	})
	proxy.SetSchedulerHealth(false)

	inventory := proxy.gatewayInventory()
	if len(inventory) != 1 || inventory[0].PendingKnown || inventory[0].GPUPressureKnown || inventory[0].InFlightReservations != 0 {
		t.Fatalf("inventory = %+v, want unknown resources while scheduler is down", inventory)
	}
	proxy.priorityMu.RLock()
	defer proxy.priorityMu.RUnlock()
	if proxy.appliedPriorityGeneration != 1 || proxy.priorityPending["pc"] != 5 || proxy.priorityReservations["pc"] != 0 {
		t.Fatalf("scheduler health changed official proxy snapshot: generation=%d pending=%v reservations=%v", proxy.appliedPriorityGeneration, proxy.priorityPending, proxy.priorityReservations)
	}
}

func TestGatewayInventoryTreatsMissingPriorityRankAsUnknown(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer server.Close()
	proxy.facades = map[string]*facade{"ollama": newFacade(proxy, ollama, NewDiscovery(), 11435)}
	proxy.facades["ollama"].discovery.AddManual(nodeForModel(t, "pc", server.URL, "model-8b"))
	proxy.SetPrioritySnapshot(schedulerwire.Priority{Generation: 1, Nodes: []string{"pc"}})

	inventory := proxy.gatewayInventory()
	if len(inventory) != 1 || inventory[0].PendingKnown || inventory[0].GPUPressureKnown {
		t.Fatalf("inventory = %+v, want one deployment with unknown resources", inventory)
	}
}

func TestGatewayInventoryUsesSchedulerTelemetryFreshness(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer server.Close()
	proxy.facades = map[string]*facade{"ollama": newFacade(proxy, ollama, NewDiscovery(), 11435)}
	proxy.facades["ollama"].discovery.AddManual(nodeForModel(t, "pc", server.URL, "model-8b"))
	proxy.SetPrioritySnapshot(schedulerwire.Priority{
		Generation: 1,
		Nodes:      []string{"pc"},
		Ranks:      []schedulerwire.NodeRank{{ID: "pc", Pending: 0, GPUPressure: 1, GPUPressureKnown: false}},
	})

	inventory := proxy.gatewayInventory()
	if len(inventory) != 1 || !inventory[0].PendingKnown || inventory[0].GPUPressureKnown || inventory[0].GPUPressure != 1 {
		t.Fatalf("inventory = %+v, want current pending and scheduler-marked stale pressure unknown", inventory)
	}
}

func TestGatewayModelRankingTracksUpdatedPrioritySnapshots(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	discovery := NewDiscovery()
	serverA := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer serverA.Close()
	serverB := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer serverB.Close()
	pcA := nodeForModel(t, "pc-a", serverA.URL, "model-32b")
	pcA.Models = append(pcA.Models, "model-8b")
	discovery.AddManual(pcA)
	discovery.AddManual(nodeForModel(t, "pc-b", serverB.URL, "model-8b"))
	proxy.facades = map[string]*facade{"ollama": newFacade(proxy, ollama, discovery, 11435)}

	selectionFor := func() *modelselection.Selection {
		t.Helper()
		return (modelselection.AutoModelSelector{}).Select(AUTO_BALANCED_ALIAS, modelselection.Requirements{}, proxy.gatewayInventory())
	}
	proxy.SetPrioritySnapshot(schedulerwire.Priority{
		Generation: 1,
		Nodes:      []string{"pc-b", "pc-a"},
		Ranks: []schedulerwire.NodeRank{
			{ID: "pc-a", Pending: 4, GPUPressure: 3, GPUPressureKnown: true},
			{ID: "pc-b", Pending: 0, GPUPressure: 0, GPUPressureKnown: true},
		},
	})
	first := selectionFor()
	if first == nil || first.Model.EngineModelID != "model-8b" || first.NodeID != "pc-b" {
		t.Fatalf("selection from first snapshot = %+v, want 8B on pc-b", first)
	}

	proxy.SetPrioritySnapshot(schedulerwire.Priority{
		Generation: 2,
		Nodes:      []string{"pc-a", "pc-b"},
		Ranks: []schedulerwire.NodeRank{
			{ID: "pc-a", Pending: 0, GPUPressure: 0, GPUPressureKnown: true},
			{ID: "pc-b", Pending: 4, GPUPressure: 3, GPUPressureKnown: true},
		},
	})
	second := selectionFor()
	if second == nil || second.Model.EngineModelID != "model-32b" || second.NodeID != "pc-a" {
		t.Fatalf("selection from updated snapshot = %+v, want 32B on pc-a", second)
	}
}

func TestGatewayModelChoiceDoesNotPinTheRatedNode(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	discovery := NewDiscovery()
	serverA := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer serverA.Close()
	serverB := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer serverB.Close()
	discovery.AddManual(nodeForModel(t, "pc-a", serverA.URL, "qwen3-8b"))
	discovery.AddManual(nodeForModel(t, "pc-b", serverB.URL, "qwen3-8b"))
	f := newFacade(proxy, ollama, discovery, 11435)
	proxy.facades = map[string]*facade{"ollama": f}
	proxy.SetPrioritySnapshot(schedulerwire.Priority{
		Generation: 1,
		Nodes:      []string{"pc-b", "pc-a"},
		Ranks: []schedulerwire.NodeRank{
			{ID: "pc-a", Pending: 1, GPUPressure: 0, GPUPressureKnown: true},
			{ID: "pc-b", Pending: 0, GPUPressure: 0, GPUPressureKnown: true},
		},
	})

	route, dispatchErr := proxy.gatewayDispatcher.resolveLocalAuto(AUTO_BALANCED_ALIAS, gatewayRequestTraits{
		model: AUTO_BALANCED_ALIAS, capabilities: map[string]bool{"chat": true},
	}, nil)
	if dispatchErr != nil {
		t.Fatalf("resolve auto route: %+v", dispatchErr)
	}
	if route.facade != f || route.upstreamModel != "qwen3-8b" || route.remoteNode != nil {
		t.Fatalf("route = %+v, want model route without a pinned node", route)
	}
	bestDeployment := proxy.gatewayInventory()
	selected := (modelselection.AutoModelSelector{}).Select(AUTO_BALANCED_ALIAS, modelselection.Requirements{}, bestDeployment)
	if selected == nil || selected.NodeID != "pc-b" {
		t.Fatalf("rated deployment = %+v, want pc-b", selected)
	}

	// Simulate an overlapping request that has already reserved pc-b after the
	// Gateway scored it. Proxy scheduling must use the live reservation instead.
	firstReservations := []reservation{
		reservedOn(t, proxy, f, "pc-b"),
		reservedOn(t, proxy, f, "pc-b"),
	}
	candidates := f.resolveCandidates(route.upstreamModel)
	ordered, secondReservation := proxy.reserveCandidate(f, candidates)
	defer func() {
		for _, held := range firstReservations {
			proxy.releaseReservation(held)
		}
	}()
	defer proxy.releaseReservation(secondReservation)
	if len(ordered) == 0 || ordered[0].id != "pc-a" {
		t.Fatalf("Proxy order after pc-b reservation = %+v, want pc-a first", ordered)
	}
}

func reservedOn(t *testing.T, proxy *Proxy, f *facade, nodeID string) reservation {
	t.Helper()
	_, held := proxy.reserveCandidate(f, reservationCandidates(nodeID))
	if !held.held {
		t.Fatalf("no reservation was created for %s", nodeID)
	}
	return held
}

func TestGatewayBurstModelSelectionUsesInFlightProxyReservations(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	discovery := NewDiscovery()
	serverA := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer serverA.Close()
	serverB := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	defer serverB.Close()
	discovery.AddManual(nodeForModel(t, "pc-a", serverA.URL, "qwen3-8b"))
	discovery.AddManual(nodeForModel(t, "pc-b", serverB.URL, "llama3-8b"))
	f := newFacade(proxy, ollama, discovery, 11435)
	proxy.facades = map[string]*facade{"ollama": f}
	proxy.SetPrioritySnapshot(schedulerwire.Priority{
		Generation: 1,
		Nodes:      []string{"pc-a", "pc-b"},
		Ranks: []schedulerwire.NodeRank{
			{ID: "pc-a", Pending: 0, GPUPressure: 0, GPUPressureKnown: true},
			{ID: "pc-b", Pending: 0, GPUPressure: 0, GPUPressureKnown: true},
		},
	})

	traits := gatewayRequestTraits{model: AUTO_BALANCED_ALIAS, capabilities: map[string]bool{"chat": true}}
	type burstResult struct {
		model string
		err   error
	}
	start := make(chan struct{})
	finish := make(chan struct{})
	results := make(chan burstResult, 4)
	var workers sync.WaitGroup
	defer func() {
		close(finish)
		workers.Wait()
	}()
	selectedModels := make(map[string]int)
	for request := 0; request < 4; request++ {
		workers.Add(1)
		go func(requestID int) {
			defer workers.Done()
			<-start
			route, dispatchErr := proxy.gatewayDispatcher.resolveLocalAuto(AUTO_BALANCED_ALIAS, traits, nil)
			if dispatchErr != nil {
				results <- burstResult{err: fmt.Errorf("request %d resolve auto route: %+v", requestID, dispatchErr)}
				return
			}
			candidates := route.facade.resolveCandidates(route.upstreamModel)
			ordered, held := proxy.reserveCandidate(route.facade, candidates)
			if len(ordered) != 1 || !held.held {
				results <- burstResult{err: fmt.Errorf("request %d candidate order = %+v, reservation=%+v", requestID, ordered, held)}
				return
			}
			results <- burstResult{model: route.upstreamModel}
			<-finish // hold the Proxy reservation until the burst has selected every request
			proxy.releaseReservation(held)
		}(request)
		start <- struct{}{}
		result := <-results
		if result.err != nil {
			t.Fatal(result.err)
		}
		selectedModels[result.model]++
	}
	if selectedModels["qwen3-8b"] != 2 || selectedModels["llama3-8b"] != 2 {
		t.Fatalf("model distribution during overlapping burst = %v, want 2 requests per model", selectedModels)
	}
}

func TestGatewayRejectsAmbiguousBareModelID(t *testing.T) {
	proxy := NewProxy(NewCodec(rwNop{}))
	ollama, _ := profileFor("ollama")
	lmstudio, _ := profileFor("lmstudio")
	proxy.facades = map[string]*facade{
		"ollama":   newFacade(proxy, ollama, NewDiscovery(), 11435),
		"lmstudio": newFacade(proxy, lmstudio, NewDiscovery(), 1234),
	}
	proxy.facades["ollama"].discovery.AddManual(Node{ID: "pc", Models: []string{"shared"}})
	proxy.facades["lmstudio"].discovery.AddManual(Node{ID: "workstation", Models: []string{"shared"}})
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"shared","messages":[{"role":"user","content":"hello"}]}`,
	))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusConflict || !strings.Contains(response.Body.String(), "model_conflict") {
		t.Fatalf("ambiguous model response=%d %s", response.Code, response.Body.String())
	}
}

func TestGatewayAutoToolsRequestDoesNotRouteToMNN(t *testing.T) {
	var hits atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		hits.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()
	proxy := NewProxy(NewCodec(rwNop{}))
	mnn, _ := profileFor("mnn")
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "phone", upstream.URL, "qwen-tool-0.6b"))
	proxy.facades = map[string]*facade{"mnn": newFacade(proxy, mnn, discovery, 14325)}
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"auto-balanced","messages":[{"role":"user","content":"hello"}],"tools":[{"type":"function"}]}`,
	))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)

	if response.Code != http.StatusUnprocessableEntity {
		t.Fatalf("gateway status = %d body=%s, want no compatible model", response.Code, response.Body.String())
	}
	if hits.Load() != 0 {
		t.Fatalf("MNN upstream hits = %d, want 0", hits.Load())
	}
}

func TestGatewayAutoLocalToolsRequestRoutesToVerifiedStrata(t *testing.T) {
	var upstreamHits atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstreamHits.Add(1)
		var payload map[string]json.RawMessage
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode Strata request: %v", err)
		}
		if len(payload["tools"]) == 0 {
			t.Error("tools field was not forwarded to Strata")
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"chatcmpl-strata-tool","choices":[{"message":{"role":"assistant","tool_calls":[{"id":"call-1","type":"function","function":{"name":"lookup_status","arguments":"{\"item\":\"alpha\"}"}}]},"finish_reason":"tool_calls"}]}`)
	}))
	defer upstream.Close()

	proxy := NewProxy(NewCodec(rwNop{}))
	profile, _ := profileFor("strata")
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "strata-node", upstream.URL, "qwen-local"))
	proxy.facades = map[string]*facade{"strata": newFacade(proxy, profile, discovery, 8082)}
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"auto-local","messages":[{"role":"user","content":"look up alpha"}],"tools":[{"type":"function","function":{"name":"lookup_status"}}],"tool_choice":"required"}`,
	))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"finish_reason":"tool_calls"`) {
		t.Fatalf("Gateway response = %d %s, want forwarded Strata tool call", response.Code, response.Body.String())
	}
	if got := upstreamHits.Load(); got != 1 {
		t.Fatalf("Strata upstream hits = %d, want 1", got)
	}
}

func TestGatewayAutoChatRequestCanRouteToMNNAndRewritesOnlyModel(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Errorf("read upstream request: %v", err)
		}
		var payload map[string]json.RawMessage
		if err := json.Unmarshal(body, &payload); err != nil {
			t.Errorf("decode upstream request: %v", err)
		}
		var payloadModel string
		if err := json.Unmarshal(payload["model"], &payloadModel); err != nil || payloadModel != "qwen3-1.7b" {
			t.Errorf("resolved model = %q, err = %v", payloadModel, err)
		}
		var stream bool
		if err := json.Unmarshal(payload["stream"], &stream); err != nil || !stream {
			t.Errorf("stream = %v, err = %v; rewriting the alias must preserve other request fields", stream, err)
		}
		if !strings.Contains(string(body), `"messages"`) {
			t.Errorf("rewritten body lost messages: %s", body)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"choices":[]}`)
	}))
	defer upstream.Close()
	proxy := NewProxy(NewCodec(rwNop{}))
	mnn, _ := profileFor("mnn")
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "phone", upstream.URL, "qwen3-1.7b"))
	proxy.facades = map[string]*facade{"mnn": newFacade(proxy, mnn, discovery, 14325)}
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(`{"model":"auto-fast","messages":[{"role":"user","content":"hello"}],"stream":true}`))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("gateway status = %d body=%s", response.Code, response.Body.String())
	}
}

func TestGatewayModelListUsesOpenAIShapeAndHidesRoutingDetails(t *testing.T) {
	ollama, _ := profileFor("ollama")
	discovery := NewDiscovery()
	node := nodeForModel(t, "private-node-id", "http://192.0.2.44:11434", "qwen3-8b")
	discovery.AddManual(node)
	p := newTestProxy(ollama, NewCodec(rwNop{}), discovery, 11435)
	response := httptest.NewRecorder()
	p.serveGateway(response, httptest.NewRequest(http.MethodGet, "http://127.0.0.1:14326/v1/models", nil))
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	var payload struct {
		Data   []gatewayModel `json:"data"`
		Object string         `json:"object"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if payload.Object != "list" {
		t.Fatalf("model list = %+v", payload)
	}
	foundModel := false
	foundAliases := map[string]bool{}
	for _, model := range payload.Data {
		if model.ID == "qwen3-8b" && model.OwnedBy == "pair" {
			foundModel = true
		}
		if isGatewayAutoAlias(model.ID) {
			foundAliases[model.ID] = true
		}
	}
	if !foundModel || len(foundAliases) != len(autoAliases) {
		t.Fatalf("model list missing explicit model or auto aliases: %+v", payload.Data)
	}
	for _, private := range []string{"private-node-id", "192.0.2.44", "11434"} {
		if strings.Contains(response.Body.String(), private) {
			t.Errorf("model list disclosed %q: %s", private, response.Body.String())
		}
	}
}

func TestGatewayResolvesDuplicateModelByCentralEnginePreference(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	lmstudio, _ := profileFor("lmstudio")
	mnn, _ := profileFor("mnn")
	proxy.facades = map[string]*facade{
		"lmstudio": newFacade(proxy, lmstudio, NewDiscovery(), 1234),
		"ollama":   newFacade(proxy, ollama, NewDiscovery(), 11435),
		"mnn":      newFacade(proxy, mnn, NewDiscovery(), 14325),
	}
	for _, engine := range []string{"lmstudio", "ollama", "mnn"} {
		proxy.facades[engine].discovery.AddManual(Node{ID: engine, Models: []string{"shared"}})
	}
	got := proxy.resolveGatewayFacade("shared")
	if got == nil || got.profile.Name != "mnn" {
		t.Fatalf("resolved facade = %v, want mnn by configured default preference", got)
	}
}

func TestGatewayPrefersEngineWithLoadedModelBeforeEnginePreference(t *testing.T) {
	proxy := NewProxy(nil)
	ollama, _ := profileFor("ollama")
	lmstudio, _ := profileFor("lmstudio")
	mnn, _ := profileFor("mnn")
	proxy.facades = map[string]*facade{
		"lmstudio": newFacade(proxy, lmstudio, NewDiscovery(), 1234),
		"ollama":   newFacade(proxy, ollama, NewDiscovery(), 11435),
		"mnn":      newFacade(proxy, mnn, NewDiscovery(), 14325),
	}
	for _, engine := range []string{"lmstudio", "ollama", "mnn"} {
		loaded := []string(nil)
		if engine == "lmstudio" {
			loaded = []string{"shared"}
		}
		proxy.facades[engine].discovery.AddManual(Node{ID: engine, Models: []string{"shared"}, LoadedModels: loaded})
	}
	if got := proxy.resolveGatewayFacade("shared"); got == nil || got.profile.Name != "lmstudio" {
		t.Fatalf("resolved facade = %v, want loaded lmstudio model", got)
	}
}

func TestGatewayChatRoutesExplicitModelThroughEngineFacade(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/chat/completions" {
			t.Errorf("path = %q", r.URL.Path)
		}
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), `"model":"qwen3-8b"`) {
			t.Errorf("body = %s", body)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"choices":[{"message":{"content":"routed"}}]}`)
	}))
	defer upstream.Close()
	ollama, _ := profileFor("ollama")
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "pc-private", upstream.URL, "qwen3-8b"))
	p := newTestProxy(ollama, NewCodec(rwNop{}), discovery, 11435)
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(`{"model":"qwen3-8b","messages":[]}`))
	request.Header.Set("Authorization", "Bearer arbitrary-placeholder-or-client-key")
	response := httptest.NewRecorder()
	p.serveGateway(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "routed") {
		t.Fatalf("gateway response = %d %s", response.Code, response.Body.String())
	}
}

func TestGatewayMissingModelReturnsOpenAIInvalidRequestError(t *testing.T) {
	p := NewProxy(NewCodec(rwNop{}))
	response := httptest.NewRecorder()
	p.serveGateway(response, httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(`{"messages":[]}`)))
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	if response.Header().Get("Content-Type") != "application/json" {
		t.Errorf("content type = %q, want application/json", response.Header().Get("Content-Type"))
	}
	if !strings.Contains(response.Body.String(), "invalid_request_error") {
		t.Errorf("body = %s", response.Body.String())
	}
}

func TestGatewayRejectsOversizedRequestBody(t *testing.T) {
	request := httptest.NewRequest(
		http.MethodPost,
		"http://127.0.0.1:14326/v1/chat/completions",
		strings.NewReader(strings.Repeat("x", (64<<20)+1)),
	)
	response := httptest.NewRecorder()
	NewProxy(NewCodec(rwNop{})).serveGateway(response, request)

	if response.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("gateway status = %d, want %d", response.Code, http.StatusRequestEntityTooLarge)
	}
}

func TestExplicitGatewayModelsRespectExclusivePolicy(t *testing.T) {
	proxy := NewProxy(nil)
	body := []byte(`{"model":"x","messages":[]}`)
	tests := []struct {
		name   string
		policy gatewayPolicy
		model  string
	}{
		{name: "local only rejects explicit cloud", policy: gatewayPolicyLocalOnly, model: "cloud/provider/model"},
		{name: "cloud only rejects explicit local", policy: gatewayPolicyCloudOnly, model: "local/ollama/model"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
				policy: test.policy, cloudEnabled: true,
			})
			_, dispatchErr := proxy.gatewayDispatcher.resolve(test.model, body)
			if dispatchErr == nil || dispatchErr.status != http.StatusForbidden || dispatchErr.kind != "cloud_not_allowed" {
				t.Fatalf("dispatch error=%+v, want cloud_not_allowed", dispatchErr)
			}
		})
	}
}

func TestAutoCloudUsesOnlyConfiguredFallbackModel(t *testing.T) {
	proxy := newGatewayProxyWithTwoCloudModels(t)
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyPreferLocal,
		fallbackCloudModel: "cloud/provider/selected",
	})
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-key", nil }

	route, dispatchErr := proxy.gatewayDispatcher.resolve("auto-cloud", []byte(`{"messages":[]}`))
	if dispatchErr != nil {
		t.Fatalf("resolve auto-cloud: %+v", dispatchErr)
	}
	if got := route.cloudTarget.PublicID; got != "cloud/provider/selected" {
		t.Fatalf("selected cloud model=%q, want configured fallback", got)
	}
}

func TestCloudOnlyAutoUsesConfiguredFallbackModel(t *testing.T) {
	proxy := newGatewayProxyWithTwoCloudModels(t)
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyCloudOnly,
		fallbackCloudModel: "cloud/provider/selected",
	})
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-key", nil }

	route, dispatchErr := proxy.gatewayDispatcher.resolve("auto", []byte(`{"messages":[]}`))
	if dispatchErr != nil {
		t.Fatalf("resolve auto in cloud-only mode: %+v", dispatchErr)
	}
	if got := route.cloudTarget.PublicID; got != "cloud/provider/selected" {
		t.Fatalf("selected cloud model=%q, want configured fallback", got)
	}
}

func TestAutoCloudRejectsLocalOnlyPolicy(t *testing.T) {
	proxy := newGatewayProxyWithTwoCloudModels(t)
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyLocalOnly,
		fallbackCloudModel: "cloud/provider/selected",
	})

	_, dispatchErr := proxy.gatewayDispatcher.resolve("auto-cloud", []byte(`{"messages":[]}`))
	if dispatchErr == nil || dispatchErr.status != http.StatusForbidden || dispatchErr.kind != "cloud_not_allowed" {
		t.Fatalf("dispatch error=%+v, want cloud_not_allowed", dispatchErr)
	}
}

func TestAutoLocalRejectsCloudOnlyPolicy(t *testing.T) {
	proxy := newGatewayProxyWithTwoCloudModels(t)
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyCloudOnly,
		fallbackCloudModel: "cloud/provider/selected",
	})

	_, dispatchErr := proxy.gatewayDispatcher.resolve(AUTO_LOCAL_ALIAS, []byte(`{"messages":[]}`))
	if dispatchErr == nil || dispatchErr.status != http.StatusForbidden || dispatchErr.kind != "cloud_not_allowed" {
		t.Fatalf("dispatch error=%+v, want cloud_not_allowed", dispatchErr)
	}
}

func TestAutoCloudRequiresConfiguredFallbackModel(t *testing.T) {
	proxy := newGatewayProxyWithTwoCloudModels(t)
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyCloudOnly,
	})
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-key", nil }

	_, dispatchErr := proxy.gatewayDispatcher.resolve("auto-cloud", []byte(`{"messages":[]}`))
	if dispatchErr == nil || dispatchErr.status != http.StatusNotFound || dispatchErr.kind != "model_not_found" {
		t.Fatalf("dispatch error=%+v, want model_not_found for missing fallback", dispatchErr)
	}
}

func TestGatewayCloudAuthorizationCoversOnlyAliasesThatCanCallCloud(t *testing.T) {
	tests := []struct {
		name              string
		policy            gatewayPolicy
		allowPaidFallback bool
		model             string
		wantAuthorization bool
	}{
		{name: "explicit auto-cloud remains authorized when paid fallback switch is off", policy: gatewayPolicyPreferLocal, model: AUTO_CLOUD_ALIAS, wantAuthorization: true},
		{name: "automatic prefer-local route is authorized when paid fallback is enabled", policy: gatewayPolicyPreferLocal, allowPaidFallback: true, model: AUTO_ALIAS, wantAuthorization: true},
		{name: "automatic prefer-local route needs no cloud auth when fallback is off", policy: gatewayPolicyPreferLocal, model: AUTO_ALIAS},
		{name: "forced local alias needs no cloud authorization", policy: gatewayPolicyPreferLocal, model: AUTO_LOCAL_ALIAS},
		{name: "local-only auto-cloud is rejected before cloud authorization", policy: gatewayPolicyLocalOnly, model: AUTO_CLOUD_ALIAS},
		{name: "cloud-only automatic route requires authorization", policy: gatewayPolicyCloudOnly, model: AUTO_ALIAS, wantAuthorization: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			proxy := NewProxy(nil)
			proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
				cloudEnabled: true, policy: test.policy, allowPaidFallback: test.allowPaidFallback,
			})
			if got := proxy.gatewayDispatcher.requiresCloudAuthorization(test.model); got != test.wantAuthorization {
				t.Fatalf("authorization required=%v, want %v", got, test.wantAuthorization)
			}
		})
	}
}

func TestPreferLocalAutoUsesCloudOnlyForNoCapacityOrEntirelyBusyLocalInventory(t *testing.T) {
	const model = "qwen3-8b"
	tests := []struct {
		name              string
		deployments       []string
		ranks             []schedulerwire.NodeRank
		allowPaidFallback bool
		wantCloud         bool
		reserveOnNode     bool
	}{
		{
			name:              "all compatible deployments are busy",
			deployments:       []string{"pc-a"},
			ranks:             []schedulerwire.NodeRank{{ID: "pc-a", GPUPressure: schedulerwire.MaxGPUPressure, GPUPressureKnown: true}},
			allowPaidFallback: true, wantCloud: true,
		},
		{
			name:              "unknown telemetry keeps the request local",
			deployments:       []string{"pc-a"},
			ranks:             []schedulerwire.NodeRank{{ID: "pc-a", GPUPressure: schedulerwire.MaxGPUPressure, GPUPressureKnown: false}},
			allowPaidFallback: true,
		},
		{
			name:              "pending requests and reservations do not imply GPU saturation",
			deployments:       []string{"pc-a"},
			ranks:             []schedulerwire.NodeRank{{ID: "pc-a", Pending: 100, GPUPressure: 0, GPUPressureKnown: true}},
			allowPaidFallback: true, reserveOnNode: true,
		},
		{
			name:        "one idle deployment keeps the request local",
			deployments: []string{"pc-a", "pc-b"},
			ranks: []schedulerwire.NodeRank{
				{ID: "pc-a", GPUPressure: schedulerwire.MaxGPUPressure, GPUPressureKnown: true},
				{ID: "pc-b", GPUPressure: 0, GPUPressureKnown: true},
			},
			allowPaidFallback: true,
		},
		{
			name:              "no local deployment uses configured cloud when allowed",
			allowPaidFallback: true,
			wantCloud:         true,
		},
		{
			name:        "busy local deployment stays local when paid fallback is disabled",
			deployments: []string{"pc-a"},
			ranks:       []schedulerwire.NodeRank{{ID: "pc-a", GPUPressure: schedulerwire.MaxGPUPressure, GPUPressureKnown: true}},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			proxy := newGatewayProxyWithTwoCloudModels(t)
			ollama, ok := profileFor("ollama")
			if !ok {
				t.Fatal("load Ollama profile")
			}
			discovery := NewDiscovery()
			for _, nodeID := range test.deployments {
				server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
				t.Cleanup(server.Close)
				discovery.AddManual(nodeForModel(t, nodeID, server.URL, model))
			}
			proxy.facades = map[string]*facade{"ollama": newFacade(proxy, ollama, discovery, 11435)}
			proxy.SetPrioritySnapshot(schedulerwire.Priority{
				Generation: 1,
				Nodes:      test.deployments,
				Ranks:      test.ranks,
			})
			if test.reserveOnNode {
				held := reservedOn(t, proxy, proxy.facades["ollama"], test.deployments[0])
				defer proxy.releaseReservation(held)
			}
			proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
				cloudEnabled: true, policy: gatewayPolicyPreferLocal,
				allowPaidFallback: test.allowPaidFallback, fallbackCloudModel: "cloud/provider/selected",
			})
			proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-key", nil }

			route, dispatchErr := proxy.gatewayDispatcher.resolve(AUTO_ALIAS, []byte(`{"messages":[]}`))
			if dispatchErr != nil {
				t.Fatalf("resolve auto: %+v", dispatchErr)
			}
			if test.wantCloud {
				if got := route.cloudTarget.PublicID; got != "cloud/provider/selected" {
					t.Fatalf("selected cloud model=%q, want configured fallback", got)
				}
				return
			}
			if route.facade == nil || route.cloudTarget.PublicID != "" {
				t.Fatalf("route=%+v, want local engine route", route)
			}
		})
	}
}

func TestPreferLocalAutoKeepsAndroidMNNCandidateLocalWhenGPUPressureIsUnknown(t *testing.T) {
	proxy := newGatewayProxyWithTwoCloudModels(t)
	mnn, ok := profileFor("mnn")
	if !ok {
		t.Fatal("load MNN profile")
	}
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	t.Cleanup(server.Close)
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "android", server.URL, "qwen3-1.7b"))
	proxy.facades = map[string]*facade{"mnn": newFacade(proxy, mnn, discovery, 14325)}
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyPreferLocal, allowPaidFallback: true,
		fallbackCloudModel: "cloud/provider/selected",
	})
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-key", nil }

	route, dispatchErr := proxy.gatewayDispatcher.resolve(AUTO_ALIAS, []byte(`{"messages":[]}`))
	if dispatchErr != nil {
		t.Fatalf("resolve auto route: %+v", dispatchErr)
	}
	if route.facade != proxy.facades["mnn"] || route.cloudTarget.PublicID != "" {
		t.Fatalf("route=%+v, want local MNN execution while GPU pressure is unknown", route)
	}
}

func TestGatewayBusyLocalInventoryCallsOnlyConfiguredCloudModel(t *testing.T) {
	var cloudHits atomic.Int32
	cloudServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cloudHits.Add(1)
		var payload struct {
			Model string `json:"model"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode Cloud request: %v", err)
		}
		if payload.Model != "selected-upstream" {
			t.Errorf("Cloud upstream model=%q, want selected-upstream", payload.Model)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"chatcmpl-1","model":"selected-upstream","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2}}`)
	}))
	t.Cleanup(cloudServer.Close)

	var localHits atomic.Int32
	localServer := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			localHits.Add(1)
		}
	}))
	t.Cleanup(localServer.Close)

	transport := &workloadRecordingTransport{}
	proxy := NewProxy(NewCodec(transport))
	config := strings.Replace(
		`{"schema_version":1,"providers":[{"id":"provider","protocol":"openai_chat_completions","base_url":"https://api.example.com","auth_ref":"user-vault:provider","enabled":true,"models":[{"public_id":"cloud/provider/first","upstream_id":"first-upstream","capabilities":["chat","streaming"]},{"public_id":"cloud/provider/selected","upstream_id":"selected-upstream","capabilities":["chat","streaming"]}]}]}`,
		"https://api.example.com", cloudServer.URL, 1,
	)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("configure Cloud Registry: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-key", nil }
	proxy.gatewayDispatcher.setClientToken("local-client-token-12345678901234567890")
	budget, err := newCloudBudgetLedger(filepath.Join(t.TempDir(), "budget.json"))
	if err != nil {
		t.Fatalf("create Cloud budget ledger: %v", err)
	}
	proxy.gatewayDispatcher.budget = budget
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyPreferLocal, allowPaidFallback: true,
		fallbackCloudModel: "cloud/provider/selected",
		monthlyBudgetUSD:   5, perRequestMaxEstimatedCostUSD: 1,
	})

	ollama, ok := profileFor("ollama")
	if !ok {
		t.Fatal("load Ollama profile")
	}
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "pc", localServer.URL, "qwen3-8b"))
	proxy.facades = map[string]*facade{"ollama": newFacade(proxy, ollama, discovery, 11435)}
	proxy.SetPrioritySnapshot(schedulerwire.Priority{
		Generation: 1,
		Nodes:      []string{"pc"},
		Ranks:      []schedulerwire.NodeRank{{ID: "pc", GPUPressure: schedulerwire.MaxGPUPressure, GPUPressureKnown: true}},
	})

	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"auto-best","messages":[{"role":"user","content":"hello"}]}`,
	))
	request.Header.Set("Authorization", "Bearer local-client-token-12345678901234567890")
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("Gateway response=%d %s", response.Code, response.Body.String())
	}
	if got := cloudHits.Load(); got != 1 {
		t.Fatalf("Cloud requests=%d, want one configured fallback request", got)
	}
	if got := localHits.Load(); got != 0 {
		t.Fatalf("local requests=%d, want no local dispatch after saturated selection", got)
	}

	transport.mu.Lock()
	lines := bytes.Split(bytes.TrimSpace(transport.writes.Bytes()), []byte{'\n'})
	transport.mu.Unlock()
	var methods []string
	for _, line := range lines {
		var notification struct {
			Method string `json:"method"`
		}
		if err := json.Unmarshal(line, &notification); err != nil {
			t.Fatalf("decode Cloud workload notification: %v", err)
		}
		methods = append(methods, notification.Method)
	}
	if want := []string{workloadSubmittedMethod, workloadStartedMethod, workloadCompletedMethod}; !reflect.DeepEqual(methods, want) {
		t.Fatalf("Cloud workload lifecycle=%v, want %v", methods, want)
	}
}

func newGatewayProxyWithTwoCloudModels(t *testing.T) *Proxy {
	t.Helper()
	proxy := NewProxy(nil)
	config := `{"schema_version":1,"providers":[{"id":"provider","protocol":"openai_chat_completions","base_url":"https://api.example.com","auth_ref":"user-vault:provider","enabled":true,"models":[{"public_id":"cloud/provider/first","upstream_id":"first","capabilities":["chat","streaming"]},{"public_id":"cloud/provider/selected","upstream_id":"selected","capabilities":["chat","streaming"]}]}]}`
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{}); err != nil {
		t.Fatalf("load cloud provider configuration: %v", err)
	}
	return proxy
}

func TestCloudFallbackBudgetPreflightFailureUsesLocalRouteWithoutCallingProvider(t *testing.T) {
	var localHits atomic.Int32
	localServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		localHits.Add(1)
		var payload struct {
			Model string `json:"model"`
		}
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode local request: %v", err)
		}
		if payload.Model != "qwen3-8b" {
			t.Errorf("local model=%q, want qwen3-8b", payload.Model)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"message":{"content":"local"}}`)
	}))
	t.Cleanup(localServer.Close)

	proxy := newGatewayProxyWithTwoCloudModels(t)
	proxy.codec = NewCodec(rwNop{})
	ollama, ok := profileFor("ollama")
	if !ok {
		t.Fatal("load Ollama profile")
	}
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "pc", localServer.URL, "qwen3-8b"))
	proxy.facades = map[string]*facade{"ollama": newFacade(proxy, ollama, discovery, 11435)}
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyPreferLocal,
		fallbackCloudModel: "cloud/provider/selected",
	})
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-key", nil }

	traits := gatewayRequestTraits{model: AUTO_ALIAS, capabilities: map[string]bool{"chat": true}}
	localRoute, dispatchErr := proxy.gatewayDispatcher.resolveLocalAuto(AUTO_BALANCED_ALIAS, traits, nil)
	if dispatchErr != nil {
		t.Fatalf("resolve local route: %+v", dispatchErr)
	}
	cloudRoute, dispatchErr := proxy.gatewayDispatcher.resolveConfiguredCloud(proxy.gatewayDispatcher.settings.Load(), traits)
	if dispatchErr != nil {
		t.Fatalf("resolve configured Cloud route: %+v", dispatchErr)
	}
	cloudRoute.localFallback = &localRoute

	body := []byte(`{"model":"auto","messages":[]}`)
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(string(body)))
	response := httptest.NewRecorder()
	proxy.gatewayDispatcher.dispatchCloud(response, request, body, cloudRoute)
	if response.Code != http.StatusOK || localHits.Load() != 1 {
		t.Fatalf("response=%d local hits=%d body=%s, want one local response", response.Code, localHits.Load(), response.Body.String())
	}
}

func TestCloudProviderFailureAfterRequestStartDoesNotRetryLocally(t *testing.T) {
	var cloudHits atomic.Int32
	cloudServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		cloudHits.Add(1)
		w.WriteHeader(http.StatusInternalServerError)
	}))
	t.Cleanup(cloudServer.Close)

	var localHits atomic.Int32
	localServer := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		localHits.Add(1)
	}))
	t.Cleanup(localServer.Close)

	proxy := NewProxy(NewCodec(rwNop{}))
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", cloudServer.URL, 1)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("configure Cloud Registry: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-key", nil }
	budget, err := newCloudBudgetLedger(filepath.Join(t.TempDir(), "budget.json"))
	if err != nil {
		t.Fatalf("create Cloud budget ledger: %v", err)
	}
	proxy.gatewayDispatcher.budget = budget
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyPreferLocal,
		monthlyBudgetUSD: 5, perRequestMaxEstimatedCostUSD: 1,
	})

	ollama, ok := profileFor("ollama")
	if !ok {
		t.Fatal("load Ollama profile")
	}
	discovery := NewDiscovery()
	discovery.AddManual(nodeForModel(t, "pc", localServer.URL, "qwen3-8b"))
	localFacade := newFacade(proxy, ollama, discovery, 11435)
	target, ok := proxy.gatewayDispatcher.registry.Snapshot().Resolve("cloud/deepseek/deepseek-chat")
	if !ok {
		t.Fatal("resolve configured Cloud model")
	}
	provider, ok := proxy.gatewayDispatcher.registry.Snapshot().provider(target.ProviderID)
	if !ok {
		t.Fatal("resolve configured Cloud provider")
	}
	route := gatewayRoute{
		cloudTarget: target, provider: provider, requestModel: AUTO_ALIAS,
		localFallback: &gatewayRoute{facade: localFacade, upstreamModel: "qwen3-8b"},
	}
	body := []byte(`{"model":"auto","messages":[]}`)
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(string(body)))
	response := httptest.NewRecorder()
	proxy.gatewayDispatcher.dispatchCloud(response, request, body, route)
	if response.Code != http.StatusBadGateway {
		t.Fatalf("Gateway response=%d %s, want Provider failure", response.Code, response.Body.String())
	}
	if got := cloudHits.Load(); got != 1 {
		t.Fatalf("Cloud requests=%d, want one request", got)
	}
	if got := localHits.Load(); got != 0 {
		t.Fatalf("local requests=%d, want no replay after Provider request started", got)
	}
}

func TestGatewayRoutesAuthorizedCloudModelAndMapsPublicModelID(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstreamHits.Add(1)
		if r.URL.Path != "/v1/chat/completions" {
			t.Errorf("upstream path=%q", r.URL.Path)
		}
		if got := r.Header.Get("Authorization"); got != "Bearer provider-test-key" {
			t.Errorf("upstream authorization=%q", got)
		}
		var payload map[string]json.RawMessage
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode upstream request: %v", err)
		}
		var model string
		if err := json.Unmarshal(payload["model"], &model); err != nil || model != "deepseek-chat" {
			t.Errorf("upstream model=%q err=%v", model, err)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"chatcmpl-1","model":"deepseek-chat","choices":[],"provider_extra":{"kept":true}}`)
	}))
	defer server.Close()

	proxy := NewProxy(NewCodec(rwNop{}))
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load provider config: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyCloudOnly,
		monthlyBudgetUSD: 5, perRequestMaxEstimatedCostUSD: 1,
	})
	budget, err := newCloudBudgetLedger(filepath.Join(t.TempDir(), "budget.json"))
	if err != nil {
		t.Fatalf("create Cloud budget ledger: %v", err)
	}
	proxy.gatewayDispatcher.budget = budget
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-test-key", nil }
	proxy.gatewayDispatcher.setClientToken("local-client-token-12345678901234567890")
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[{"role":"user","content":"hello"}]}`,
	))
	request.Header.Set("Authorization", "Bearer local-client-token-12345678901234567890")
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("gateway status=%d body=%s", response.Code, response.Body.String())
	}
	var payload struct {
		Model         string          `json:"model"`
		ProviderExtra json.RawMessage `json:"provider_extra"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode Gateway response: %v", err)
	}
	if payload.Model != "cloud/deepseek/deepseek-chat" || string(payload.ProviderExtra) != `{"kept":true}` {
		t.Fatalf("public response model=%q provider_extra=%s", payload.Model, payload.ProviderExtra)
	}
	if got := upstreamHits.Load(); got != 1 {
		t.Fatalf("cloud upstream hit count=%d, want 1", got)
	}
}

func TestGatewaySupportsTwoTurnAgentToolCall(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		call := upstreamHits.Add(1)
		var payload map[string]json.RawMessage
		if err := json.NewDecoder(r.Body).Decode(&payload); err != nil {
			t.Errorf("decode Agent request: %v", err)
		}
		var model string
		if err := json.Unmarshal(payload["model"], &model); err != nil || model != "deepseek-chat" {
			t.Errorf("upstream model=%q err=%v", model, err)
		}
		var messages []map[string]json.RawMessage
		if err := json.Unmarshal(payload["messages"], &messages); err != nil {
			t.Errorf("decode Agent messages: %v", err)
		}
		if call == 1 {
			if len(payload["tools"]) == 0 || len(payload["tool_choice"]) == 0 {
				t.Errorf("first Agent turn lost tools/tool_choice: %s", payload)
			}
			_, _ = io.WriteString(w, `{"id":"chatcmpl-tools","model":"deepseek-chat","choices":[{"message":{"role":"assistant","tool_calls":[{"id":"call_weather","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Seattle\"}"}}],"content":null},"finish_reason":"tool_calls"}]}`)
			return
		}
		if len(messages) != 3 || len(messages[2]["tool_call_id"]) == 0 {
			t.Errorf("second Agent turn lost tool reply: messages=%s", payload["messages"])
		}
		if len(payload["response_format"]) == 0 {
			t.Errorf("second Agent turn lost structured output request")
		}
		_, _ = io.WriteString(w, `{"id":"chatcmpl-final","model":"deepseek-chat","choices":[{"message":{"role":"assistant","content":"Clear in Seattle."},"finish_reason":"stop"}]}`)
	}))
	defer server.Close()

	proxy := NewProxy(NewCodec(rwNop{}))
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load Provider config: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyCloudOnly,
		monthlyBudgetUSD: 5, perRequestMaxEstimatedCostUSD: 1,
	})
	budget, err := newCloudBudgetLedger(filepath.Join(t.TempDir(), "budget.json"))
	if err != nil {
		t.Fatalf("create Cloud budget ledger: %v", err)
	}
	proxy.gatewayDispatcher.budget = budget
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-test-key", nil }
	proxy.gatewayDispatcher.setClientToken("local-client-token-12345678901234567890")

	first := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[{"role":"user","content":"What is the weather in Seattle?"}],"tools":[{"type":"function","function":{"name":"get_weather","parameters":{"type":"object"}}}],"tool_choice":"auto"}`,
	))
	first.Header.Set("Authorization", "Bearer local-client-token-12345678901234567890")
	firstResponse := httptest.NewRecorder()
	proxy.serveGateway(firstResponse, first)
	if firstResponse.Code != http.StatusOK || !strings.Contains(firstResponse.Body.String(), `"tool_calls"`) {
		t.Fatalf("first Agent turn status=%d body=%s", firstResponse.Code, firstResponse.Body.String())
	}

	second := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[{"role":"user","content":"What is the weather in Seattle?"},{"role":"assistant","tool_calls":[{"id":"call_weather","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Seattle\"}"}}]},{"role":"tool","tool_call_id":"call_weather","content":"Sunny, 18 C"}],"tools":[{"type":"function","function":{"name":"get_weather","parameters":{"type":"object"}}}],"tool_choice":"auto","response_format":{"type":"json_object"}}`,
	))
	second.Header.Set("Authorization", "Bearer local-client-token-12345678901234567890")
	secondResponse := httptest.NewRecorder()
	proxy.serveGateway(secondResponse, second)
	if secondResponse.Code != http.StatusOK || !strings.Contains(secondResponse.Body.String(), "Clear in Seattle") {
		t.Fatalf("second Agent turn status=%d body=%s", secondResponse.Code, secondResponse.Body.String())
	}
	if got := upstreamHits.Load(); got != 2 {
		t.Fatalf("upstream Agent turn count=%d, want 2", got)
	}
}

func TestGatewayPaidCloudFallbackRequiresOptInAndBudget(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstreamHits.Add(1)
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"chatcmpl-fallback","model":"deepseek-chat","choices":[]}`)
	}))
	defer server.Close()
	proxy := NewProxy(NewCodec(rwNop{}))
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load Provider config: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.setClientToken("local-client-token-12345678901234567890")
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-test-key", nil }
	budget, err := newCloudBudgetLedger(filepath.Join(t.TempDir(), "budget.json"))
	if err != nil {
		t.Fatalf("create Cloud budget ledger: %v", err)
	}
	proxy.gatewayDispatcher.budget = budget
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyPreferLocal,
		fallbackCloudModel: "cloud/deepseek/deepseek-chat",
		monthlyBudgetUSD:   2, perRequestMaxEstimatedCostUSD: 0.5,
	})
	request := func() *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
			`{"model":"auto","messages":[{"role":"user","content":"hello"}]}`,
		))
		req.Header.Set("Authorization", "Bearer local-client-token-12345678901234567890")
		response := httptest.NewRecorder()
		proxy.serveGateway(response, req)
		return response
	}

	withoutOptIn := request()
	if withoutOptIn.Code != http.StatusNotFound {
		t.Fatalf("prefer-local without paid fallback response=%d %s, want local unavailable", withoutOptIn.Code, withoutOptIn.Body.String())
	}
	if got := upstreamHits.Load(); got != 0 {
		t.Fatalf("paid fallback without opt-in reached Provider %d times", got)
	}

	settings := *proxy.gatewayDispatcher.settings.Load()
	settings.allowPaidFallback = true
	proxy.gatewayDispatcher.settings.Store(&settings)
	withOptIn := request()
	if withOptIn.Code != http.StatusOK {
		t.Fatalf("budgeted paid fallback response=%d %s", withOptIn.Code, withOptIn.Body.String())
	}
	if got := upstreamHits.Load(); got != 1 {
		t.Fatalf("budgeted paid fallback Provider requests=%d, want 1", got)
	}

	settings.monthlyBudgetUSD = 0.25
	settings.perRequestMaxEstimatedCostUSD = 0.25
	proxy.gatewayDispatcher.settings.Store(&settings)
	exhausted := request()
	if exhausted.Code != http.StatusTooManyRequests || !strings.Contains(exhausted.Body.String(), "quota_exceeded") {
		t.Fatalf("exhausted paid fallback response=%d %s", exhausted.Code, exhausted.Body.String())
	}
	if got := upstreamHits.Load(); got != 1 {
		t.Fatalf("over-budget fallback reached Provider %d times, want one total call", got)
	}
}

func TestPairedCloudIngressRejectsUnauthorizedCallerBeforeUpstream(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		upstreamHits.Add(1)
	}))
	defer server.Close()
	proxy := NewProxy(NewCodec(rwNop{}))
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load provider config: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyCloudOnly,
		monthlyBudgetUSD: 5, perRequestMaxEstimatedCostUSD: 1,
	})
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-test-key", nil }
	request := httptest.NewRequest(http.MethodPost, "https://host/v1/pair/cloud/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[]}`,
	))
	response := httptest.NewRecorder()
	proxy.gatewayDispatcher.serveAuthorizedCloudPeer(response, request, "10000000-0000-0000-0000-000000000001", []byte("caller-cert"))
	if response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), "cloud_not_allowed") {
		t.Fatalf("unauthorized paired Cloud response=%d %s", response.Code, response.Body.String())
	}
	if got := upstreamHits.Load(); got != 0 {
		t.Fatalf("unauthorized paired caller reached provider %d times", got)
	}
}

func TestPairedCloudIngressUsesHostCredentialAndBudget(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstreamHits.Add(1)
		if got := r.Header.Get("Authorization"); got != "Bearer provider-test-key" {
			t.Errorf("provider authorization=%q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"chatcmpl-1","model":"deepseek-chat","choices":[]}`)
	}))
	defer server.Close()
	proxy := NewProxy(NewCodec(rwNop{}))
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load provider config: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{
		cloudEnabled: true, policy: gatewayPolicyCloudOnly,
		monthlyBudgetUSD: 5, perRequestMaxEstimatedCostUSD: 1,
		authorizedCloudNodes: map[string]string{"10000000-0000-0000-0000-000000000001": cloudCertificateFingerprint([]byte("caller-cert"))},
	})
	budget, err := newCloudBudgetLedger(filepath.Join(t.TempDir(), "budget.json"))
	if err != nil {
		t.Fatalf("create Cloud budget ledger: %v", err)
	}
	proxy.gatewayDispatcher.budget = budget
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-test-key", nil }
	request := httptest.NewRequest(http.MethodPost, "https://host/v1/pair/cloud/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[]}`,
	))
	response := httptest.NewRecorder()
	proxy.gatewayDispatcher.serveAuthorizedCloudPeer(response, request, "10000000-0000-0000-0000-000000000001", []byte("caller-cert"))
	if response.Code != http.StatusOK {
		t.Fatalf("authorized paired Cloud response=%d %s", response.Code, response.Body.String())
	}
	if got := upstreamHits.Load(); got != 1 {
		t.Fatalf("authorized paired caller reached provider %d times, want one", got)
	}
}

func TestGatewayCloudRoutingIsDisabledByDefault(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		upstreamHits.Add(1)
	}))
	defer server.Close()
	proxy := NewProxy(nil)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load provider config: %v", err)
	}
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[]}`,
	))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), "cloud_not_allowed") {
		t.Fatalf("disabled Cloud response=%d %s", response.Code, response.Body.String())
	}
	if got := upstreamHits.Load(); got != 0 {
		t.Fatalf("disabled cloud route reached upstream %d times", got)
	}
}

func TestGatewayRejectsCloudRequestWithoutClientToken(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		upstreamHits.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	proxy := NewProxy(nil)
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load provider config: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{cloudEnabled: true, policy: gatewayPolicyCloudOnly})
	proxy.gatewayDispatcher.setCredentialResolver(func(string) (string, error) { return "provider-test-key", nil })
	proxy.gatewayDispatcher.setClientToken("local-client-token-12345678901234567890")
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[]}`,
	))
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusUnauthorized || !strings.Contains(response.Body.String(), "unauthorized") {
		t.Fatalf("unauthorized cloud response=%d %s", response.Code, response.Body.String())
	}
	if got := upstreamHits.Load(); got != 0 {
		t.Fatalf("unauthorized cloud route reached upstream %d times", got)
	}
}

func TestGatewayRejectsWrongClientTokenBeforeUpstream(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		upstreamHits.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	proxy := NewProxy(nil)
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load provider config: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{cloudEnabled: true, policy: gatewayPolicyCloudOnly})
	proxy.gatewayDispatcher.setCredentialResolver(func(string) (string, error) { return "provider-test-key", nil })
	proxy.gatewayDispatcher.setClientToken("local-client-token-12345678901234567890")

	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[]}`,
	))
	request.Header.Set("Authorization", "Bearer wrong-client-token-12345678901234567890")
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("wrong-token cloud response status=%d body=%s", response.Code, response.Body.String())
	}
	if got := upstreamHits.Load(); got != 0 {
		t.Fatalf("wrong-token cloud request reached upstream %d times", got)
	}
}

func TestGatewayRejectsCloudRequestWithoutBudgetBeforeUpstream(t *testing.T) {
	var upstreamHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		upstreamHits.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()
	proxy := NewProxy(NewCodec(rwNop{}))
	config := strings.Replace(validCloudConfig, "https://api.deepseek.com", server.URL, 1)
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(config), cloudConfigOptions{allowLoopbackURL: true}); err != nil {
		t.Fatalf("load provider config: %v", err)
	}
	proxy.gatewayDispatcher.client = newCloudHTTPClient(cloudHTTPOptions{allowLoopback: true})
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{cloudEnabled: true, policy: gatewayPolicyCloudOnly})
	proxy.gatewayDispatcher.resolveCredential = func(string) (string, error) { return "provider-test-key", nil }
	proxy.gatewayDispatcher.setClientToken("local-client-token-12345678901234567890")
	request := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:14326/v1/chat/completions", strings.NewReader(
		`{"model":"cloud/deepseek/deepseek-chat","messages":[]}`,
	))
	request.Header.Set("Authorization", "Bearer local-client-token-12345678901234567890")
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusForbidden || !strings.Contains(response.Body.String(), "cloud_not_allowed") {
		t.Fatalf("zero budget response=%d %s", response.Code, response.Body.String())
	}
	if got := upstreamHits.Load(); got != 0 {
		t.Fatalf("zero-budget Cloud route reached upstream %d times", got)
	}
}

func TestGatewayHealthDoesNotRevealCloudConfigurationOrRequireToken(t *testing.T) {
	proxy := NewProxy(nil)
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{cloudEnabled: true, policy: gatewayPolicyCloudOnly})
	response := httptest.NewRecorder()
	proxy.serveGateway(response, httptest.NewRequest(http.MethodGet, "http://127.0.0.1:14326/healthz", nil))
	if response.Code != http.StatusOK || response.Body.String() != "{\"status\":\"ok\"}" {
		t.Fatalf("health response=%d %q", response.Code, response.Body.String())
	}
}

func TestGatewayModelDirectoryRequiresTokenWhenCloudIsEnabled(t *testing.T) {
	proxy := NewProxy(nil)
	proxy.gatewayDispatcher.settings.Store(&gatewayRoutingSettings{cloudEnabled: true, policy: gatewayPolicyLocalOnly})
	request := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:14326/v1/models", nil)
	response := httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated model directory status=%d body=%s", response.Code, response.Body.String())
	}
	proxy.gatewayDispatcher.setClientToken("local-client-token-12345678901234567890")
	request = httptest.NewRequest(http.MethodGet, "http://127.0.0.1:14326/v1/models", nil)
	request.Header.Set("Authorization", "Bearer local-client-token-12345678901234567890")
	response = httptest.NewRecorder()
	proxy.serveGateway(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("authorized model directory status=%d body=%s", response.Code, response.Body.String())
	}
}
