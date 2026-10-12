// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

// Package modelselection chooses a model and engine from runtime inventory.
// Node placement remains the responsibility of the engine facade scheduler.
package modelselection

import (
	"math"
)

const (
	AutoAlias          = "auto"
	AutoFastAlias      = "auto-fast"
	AutoBalancedAlias  = "auto-balanced"
	AutoBestAlias      = "auto-best"
	unknownSignalScore = 0.5
)

var enginePreference = []string{"mnn", "ollama", "lmstudio"}

type ModelDescriptor struct {
	LogicalID            string
	EngineModelID        string
	Family               string
	ParameterCount       int64
	Quantization         string
	ContextLength        int
	Capabilities         map[string]bool
	EstimatedMemoryBytes int64
	Compatible           bool
}

type RuntimeModel struct {
	Model                  ModelDescriptor
	NodeID                 string
	Engine                 string
	Available              bool
	Loaded                 bool
	TokensPerSecond        float64
	TimeToFirstTokenMillis int64
	MemoryPressure         float64
	AvailableMemoryBytes   int64
	NetworkCost            float64
	PendingRequests        int
	PendingKnown           bool
	InFlightReservations   int
	GPUPressure            int
	GPUPressureKnown       bool
	VRAMPressure           int
	VRAMPressureKnown      bool
}

type Requirements struct {
	Capabilities         map[string]bool
	MinimumContextLength int
	MaximumMemoryBytes   int64
}

type Selection struct {
	RequestedModel string
	PolicyAlias    string
	Model          ModelDescriptor
	Engine         string
	NodeID         string
	Score          float64
}

// AutoModelSelector scores available runtime models; catalog records never enter
// the candidate list. A facade still schedules the selected model onto a node.
type AutoModelSelector struct{}

func (AutoModelSelector) Select(requested string, requirements Requirements, inventory []RuntimeModel) *Selection {
	policy := requested
	if policy == AutoAlias {
		policy = AutoBalancedAlias
	}
	//只支持三种自动策略
	if policy != AutoFastAlias && policy != AutoBalancedAlias && policy != AutoBestAlias {
		return nil
	}

	var selected *Selection
	for _, candidate := range inventory {
		if !eligible(candidate, requirements) {
			continue
		}
		score := scoreCandidate(policy, candidate)
		if selected == nil || score > selected.Score ||
			(score == selected.Score && candidate.Model.EngineModelID < selected.Model.EngineModelID) ||
			(score == selected.Score && candidate.Model.EngineModelID == selected.Model.EngineModelID &&
				(engineRank(candidate.Engine) < engineRank(selected.Engine) ||
					(engineRank(candidate.Engine) == engineRank(selected.Engine) && candidate.NodeID < selected.NodeID))) {
			selected = &Selection{
				RequestedModel: requested,
				PolicyAlias:    policy,
				Model:          candidate.Model,
				Engine:         candidate.Engine,
				NodeID:         candidate.NodeID,
				Score:          score,
			}
		}
	}
	return selected
}

func eligible(candidate RuntimeModel, requirements Requirements) bool {
	if !candidate.Available || !candidate.Model.Compatible || candidate.Model.ContextLength < requirements.MinimumContextLength {
		return false
	}
	capabilities := effectiveCapabilities(candidate)
	for capability, required := range requirements.Capabilities {
		if required && !capabilities[capability] {
			return false
		}
	}
	if requirements.MaximumMemoryBytes > 0 &&
		(candidate.Model.EstimatedMemoryBytes <= 0 || candidate.Model.EstimatedMemoryBytes > requirements.MaximumMemoryBytes) {
		return false
	}
	if candidate.AvailableMemoryBytes > 0 && candidate.Model.EstimatedMemoryBytes > candidate.AvailableMemoryBytes {
		return false
	}
	return true
}

func scoreCandidate(policy string, candidate RuntimeModel) float64 {
	parameters := float64(candidate.Model.ParameterCount) / 1_000_000_000
	loaded := 0.0
	if candidate.Loaded {
		loaded = 1
	}
	latency := unknownSignalScore
	if candidate.TimeToFirstTokenMillis > 0 {
		latency = 1 / (1 + float64(candidate.TimeToFirstTokenMillis)/500)
	}
	throughput := unknownSignalScore
	if candidate.TokensPerSecond > 0 {
		throughput = candidate.TokensPerSecond / (candidate.TokensPerSecond + 20)
	}
	size := 1 / (1 + parameters/2)
	quality := qualityScore(parameters, candidate.Model.ContextLength)
	pressure := unknownSignalScore
	if candidate.MemoryPressure > 0 {
		pressure = 1 - clamp(candidate.MemoryPressure)
	}
	pending := unknownSignalScore
	resourcePressure := unknownSignalScore
	if candidate.PendingKnown || candidate.InFlightReservations > 0 {
		pendingCount := max(candidate.InFlightReservations, 0)
		if candidate.PendingKnown {
			pendingCount += max(candidate.PendingRequests, 0)
		}
		pending = 1 / (1 + float64(pendingCount))
	}
	if candidate.GPUPressureKnown {
		resourcePressure = 1 - clamp(float64(candidate.GPUPressure)/3)
	}
	network := unknownSignalScore
	if candidate.NetworkCost > 0 {
		network = 1 - clamp(candidate.NetworkCost)
	}

	switch policy {
	case AutoFastAlias:
		return fastWeights.loaded*loaded + fastWeights.latency*latency + fastWeights.throughput*throughput + fastWeights.size*size +
			fastWeights.pending*pending + fastWeights.gpuPressure*resourcePressure
	case AutoBestAlias:
		capability := float64(capabilityCount(effectiveCapabilities(candidate))) / 4
		return bestWeights.quality*quality + bestWeights.context*contextScore(candidate.Model.ContextLength) + bestWeights.capability*capability +
			bestWeights.pending*pending + bestWeights.gpuPressure*resourcePressure
	default:
		return balancedWeights.quality*quality + balancedWeights.latency*latency + balancedWeights.loaded*loaded +
			balancedWeights.network*network + balancedWeights.pressure*pressure + balancedWeights.pending*pending +
			balancedWeights.gpuPressure*resourcePressure
	}
}

func effectiveCapabilities(candidate RuntimeModel) map[string]bool {
	effective := make(map[string]bool, len(candidate.Model.Capabilities))
	for capability, modelSupports := range candidate.Model.Capabilities {
		effective[capability] = modelSupports && engineProtocolSupports(candidate.Engine, capability)
	}
	return effective
}

func engineProtocolSupports(engine, capability string) bool {
	if engine != "mnn" {
		return true
	}
	// The Android MNN chat facade supports streaming but rejects tools, vision,
	// and embeddings.
	return capability == "chat" || capability == "streaming"
}

func qualityScore(parameters float64, contextLength int) float64 {
	parameterQuality := unknownSignalScore
	if parameters > 0 {
		parameterQuality = math.Log1p(parameters) / math.Log1p(100)
	}
	context := unknownSignalScore
	if contextLength > 0 {
		context = contextScore(contextLength)
	}
	return clamp(qualityWeights.parameters*parameterQuality + qualityWeights.context*context)
}

func contextScore(contextLength int) float64 {
	if contextLength <= 0 {
		return unknownSignalScore
	}
	return float64(contextLength) / (float64(contextLength) + 8192)
}

func capabilityCount(capabilities map[string]bool) int {
	count := 0
	for _, capability := range capabilities {
		if capability {
			count++
		}
	}
	return count
}

func clamp(value float64) float64 { return math.Max(0, math.Min(value, 1)) }

func engineRank(engine string) int {
	for index, preferred := range enginePreference {
		if engine == preferred {
			return index
		}
	}
	return len(enginePreference)
}

type scoreWeights struct {
	quality, latency, loaded, network, pressure, throughput, size, context, capability, parameters float64
	pending, gpuPressure                                                                           float64
}

var (
	fastWeights     = scoreWeights{loaded: 0.25, latency: 0.20, throughput: 0.10, size: 0.05, pending: 0.20, gpuPressure: 0.20}
	balancedWeights = scoreWeights{quality: 0.25, latency: 0.15, loaded: 0.10, network: 0.10, pressure: 0.05, pending: 0.20, gpuPressure: 0.15}
	bestWeights     = scoreWeights{quality: 0.62, context: 0.23, capability: 0.10, pending: 0.03, gpuPressure: 0.02}
	qualityWeights  = scoreWeights{parameters: 0.8, context: 0.2}
)
