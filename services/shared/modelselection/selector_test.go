// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package modelselection

import (
	"math"
	"testing"
)

func TestAutoModelSelectorPolicies(t *testing.T) {
	selector := AutoModelSelector{}
	fast := runtimeModel("phone-small", "mnn", 1_700_000_000, true, 700, 12, nil)
	large := runtimeModel("pc-large", "ollama", 8_000_000_000, false, 90, 50, nil)
	vision := runtimeModel("vision-large", "lmstudio", 14_000_000_000, false, 500, 16, map[string]bool{"chat": true, "vision": true})

	t.Run("auto alias uses balanced policy", func(t *testing.T) {
		selection := selector.Select(AutoAlias, Requirements{}, []RuntimeModel{fast, large})
		if selection == nil {
			t.Fatal("auto selected no available model")
		}
		if selection.PolicyAlias != AutoBalancedAlias {
			t.Fatalf("auto policy = %q, want %q", selection.PolicyAlias, AutoBalancedAlias)
		}
	})

	t.Run("fast policy favors loaded model", func(t *testing.T) {
		selection := selector.Select(AutoFastAlias, Requirements{}, []RuntimeModel{fast, large})
		if selection == nil || selection.Model.EngineModelID != fast.Model.EngineModelID {
			t.Fatalf("fast selection = %+v, want loaded phone model", selection)
		}
	})

	t.Run("best policy filters capabilities before scoring size", func(t *testing.T) {
		selection := selector.Select(AutoBestAlias, Requirements{Capabilities: map[string]bool{"vision": true}}, []RuntimeModel{large, vision})
		if selection == nil || selection.Model.EngineModelID != vision.Model.EngineModelID {
			t.Fatalf("best selection = %+v, want capability-compatible vision model", selection)
		}
	})

	t.Run("catalog entries cannot add runtime candidates", func(t *testing.T) {
		selection := selector.Select(AutoBestAlias, Requirements{}, nil)
		if selection != nil {
			t.Fatalf("selection = %+v, want no model from an empty runtime inventory", selection)
		}
	})
}

func TestMNNIsExcludedForUnsupportedCapabilities(t *testing.T) {
	selector := AutoModelSelector{}
	for _, capability := range []string{"tools", "vision", "embeddings"} {
		t.Run(capability, func(t *testing.T) {
			candidate := runtimeModel("qwen-tool-vision-embed", "mnn", 1_700_000_000, false, 0, 0, map[string]bool{
				"chat": true, "tools": true, "vision": true, "embeddings": true,
			})
			selection := selector.Select(AutoBalancedAlias, Requirements{
				Capabilities: map[string]bool{capability: true},
			}, []RuntimeModel{candidate})
			if selection != nil {
				t.Fatalf("selection = %+v, MNN must not satisfy %s", selection, capability)
			}
		})
	}
}

func TestMNNCanBeSelectedForChat(t *testing.T) {
	candidate := runtimeModel("qwen-tool-0.6b", "mnn", 600_000_000, false, 0, 0, map[string]bool{
		"chat": true, "tools": true,
	})
	selection := (AutoModelSelector{}).Select(AutoBalancedAlias, Requirements{
		Capabilities: map[string]bool{"chat": true},
	}, []RuntimeModel{candidate})
	if selection == nil || selection.Engine != "mnn" {
		t.Fatalf("chat selection = %+v, want MNN", selection)
	}
}

func TestMNNCanBeSelectedForStreamingChat(t *testing.T) {
	candidate := runtimeModel("qwen-small", "mnn", 600_000_000, false, 0, 0, map[string]bool{
		"chat": true, "streaming": true,
	})
	selection := (AutoModelSelector{}).Select(AutoBalancedAlias, Requirements{
		Capabilities: map[string]bool{"chat": true, "streaming": true},
	}, []RuntimeModel{candidate})
	if selection == nil || selection.Engine != "mnn" {
		t.Fatalf("streaming chat selection = %+v, want MNN", selection)
	}
}

func TestSameInputProducesSameSelectionRepeatedly(t *testing.T) {
	first := runtimeModel("same-model", "ollama", 1_000_000_000, false, 500, 20, nil)
	second := runtimeModel("same-model", "ollama", 1_000_000_000, false, 500, 20, nil)
	first.NodeID = "node-z"
	second.NodeID = "node-a"
	inventory := []RuntimeModel{first, second}
	selector := AutoModelSelector{}
	for attempt := 0; attempt < 20; attempt++ {
		selection := selector.Select(AutoBalancedAlias, Requirements{}, inventory)
		if selection == nil || selection.NodeID != "node-a" {
			t.Fatalf("attempt %d selection = %+v, want stable node-a tie break", attempt, selection)
		}
	}
}

func TestLoadedCandidatePreferenceRemainsDeterministic(t *testing.T) {
	loaded := runtimeModel("same-model", "ollama", 1_000_000_000, true, 500, 20, nil)
	unloaded := runtimeModel("same-model", "ollama", 1_000_000_000, false, 500, 20, nil)
	loaded.NodeID = "node-z"
	unloaded.NodeID = "node-a"
	selection := (AutoModelSelector{}).Select(AutoBalancedAlias, Requirements{}, []RuntimeModel{unloaded, loaded})
	if selection == nil || selection.NodeID != "node-z" {
		t.Fatalf("selection = %+v, want loaded candidate on node-z", selection)
	}
}

func TestAutoBalancedPrefersLargerModelOnLessLoadedDeployment(t *testing.T) {
	large := runtimeModel("model-32b", "ollama", 32_000_000_000, true, 300, 30, nil)
	large.NodeID = "pc-a"
	large.PendingRequests = 4
	large.GPUPressure = 3
	large.PendingKnown = true
	large.GPUPressureKnown = true

	mediumBusy := runtimeModel("model-8b", "ollama", 8_000_000_000, true, 300, 30, nil)
	mediumBusy.NodeID = "pc-a"
	mediumBusy.PendingRequests = 4
	mediumBusy.GPUPressure = 3
	mediumBusy.PendingKnown = true
	mediumBusy.GPUPressureKnown = true
	mediumIdle := mediumBusy
	mediumIdle.NodeID = "pc-b"
	mediumIdle.PendingRequests = 0
	mediumIdle.GPUPressure = 0

	small := runtimeModel("model-4b", "ollama", 4_000_000_000, true, 300, 30, nil)
	small.NodeID = "android"
	small.PendingRequests = 0
	small.GPUPressure = 0
	small.PendingKnown = true
	small.GPUPressureKnown = true

	selection := (AutoModelSelector{}).Select(AutoBalancedAlias, Requirements{}, []RuntimeModel{large, mediumBusy, mediumIdle, small})
	if selection == nil || selection.Model.EngineModelID != "model-8b" || selection.NodeID != "pc-b" {
		t.Fatalf("selection = %+v, want 8B model using its low-load pc-b deployment", selection)
	}
}

func TestAutoBestKeepsQualityAheadOfResourcePressure(t *testing.T) {
	large := runtimeModel("model-32b", "ollama", 32_000_000_000, false, 500, 20, nil)
	large.PendingRequests = 8
	large.GPUPressure = 3
	large.PendingKnown = true
	large.GPUPressureKnown = true
	small := runtimeModel("model-8b", "ollama", 8_000_000_000, false, 500, 20, nil)
	small.PendingRequests = 0
	small.GPUPressure = 0
	small.PendingKnown = true
	small.GPUPressureKnown = true

	selection := (AutoModelSelector{}).Select(AutoBestAlias, Requirements{}, []RuntimeModel{small, large})
	if selection == nil || selection.Model.EngineModelID != large.Model.EngineModelID {
		t.Fatalf("selection = %+v, want quality-led 32B model", selection)
	}
}

func TestAutoFastUsesFreshResourcesToChooseAmongModelDeployments(t *testing.T) {
	busy := runtimeModel("same-model", "ollama", 8_000_000_000, true, 300, 30, nil)
	busy.NodeID = "pc-a"
	busy.PendingRequests = 3
	busy.GPUPressure = 3
	busy.PendingKnown = true
	busy.GPUPressureKnown = true
	idle := busy
	idle.NodeID = "pc-b"
	idle.PendingRequests = 0
	idle.GPUPressure = 0

	selection := (AutoModelSelector{}).Select(AutoFastAlias, Requirements{}, []RuntimeModel{busy, idle})
	if selection == nil || selection.NodeID != "pc-b" {
		t.Fatalf("selection = %+v, want same model's idle pc-b deployment", selection)
	}
}

func TestAutoFastUsesHighestKnownGPUOrVRAMPressure(t *testing.T) {
	busy := runtimeModel("same-model", "ollama", 8_000_000_000, true, 300, 30, nil)
	busy.NodeID = "pc-a"
	busy.GPUPressure = 0
	busy.GPUPressureKnown = true
	busy.VRAMPressure = 3
	busy.VRAMPressureKnown = true
	idle := busy
	idle.NodeID = "pc-b"
	idle.VRAMPressure = 0

	selection := (AutoModelSelector{}).Select(AutoFastAlias, Requirements{}, []RuntimeModel{busy, idle})
	if selection == nil || selection.NodeID != "pc-b" {
		t.Fatalf("selection = %+v, want same model's VRAM-idle pc-b deployment", selection)
	}
}

func TestAutoBalancedAccountsForCurrentProxyReservations(t *testing.T) {
	reserved := runtimeModel("model-8b", "ollama", 8_000_000_000, true, 300, 30, nil)
	reserved.NodeID = "pc-a"
	reserved.PendingRequests = 0
	reserved.GPUPressure = 0
	reserved.GPUPressureKnown = true
	reserved.InFlightReservations = 3
	idle := reserved
	idle.NodeID = "pc-b"
	idle.InFlightReservations = 0

	selection := (AutoModelSelector{}).Select(AutoBalancedAlias, Requirements{}, []RuntimeModel{reserved, idle})
	if selection == nil || selection.NodeID != "pc-b" {
		t.Fatalf("selection = %+v, want unreserved pc-b deployment", selection)
	}
}

func TestGPUPressureDoesNotActAsMemoryPressureOrCapacity(t *testing.T) {
	candidate := runtimeModel("gpu-busy", "ollama", 8_000_000_000, false, 0, 0, nil)
	candidate.PendingKnown = true
	candidate.GPUPressureKnown = true
	candidate.GPUPressure = 3
	candidate.MemoryPressure = 0
	candidate.AvailableMemoryBytes = candidate.Model.EstimatedMemoryBytes
	if !eligible(candidate, Requirements{MaximumMemoryBytes: candidate.Model.EstimatedMemoryBytes}) {
		t.Fatal("GPU pressure incorrectly disqualified a model with sufficient reported memory")
	}
}

func TestUnknownSignalsUseNeutralScore(t *testing.T) {
	candidate := runtimeModel("unknown", "ollama", 0, false, 0, 0, nil)
	candidate.Model.ContextLength = 0
	if score := scoreCandidate(AutoBalancedAlias, candidate); math.Abs(score-0.45) > 1e-12 {
		t.Fatalf("score for unknown signals = %v, want neutral score 0.45", score)
	}
}

func runtimeModel(id, engine string, parameters int64, loaded bool, ttfbMillis int64, tokensPerSecond float64, capabilities map[string]bool) RuntimeModel {
	if capabilities == nil {
		capabilities = map[string]bool{"chat": true}
	}
	return RuntimeModel{
		Model: ModelDescriptor{
			LogicalID:            engine + ":" + id,
			EngineModelID:        id,
			ParameterCount:       parameters,
			ContextLength:        8192,
			Capabilities:         capabilities,
			EstimatedMemoryBytes: parameters * 2,
			Compatible:           true,
		},
		Engine:                 engine,
		NodeID:                 "node-" + id,
		Available:              true,
		Loaded:                 loaded,
		TimeToFirstTokenMillis: ttfbMillis,
		TokensPerSecond:        tokensPerSecond,
		AvailableMemoryBytes:   parameters * 4,
	}
}
