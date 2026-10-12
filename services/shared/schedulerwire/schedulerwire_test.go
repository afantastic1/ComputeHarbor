// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package schedulerwire

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestPriorityAcceptsLegacyNodesOnlyPayload(t *testing.T) {
	var got Priority
	if err := json.Unmarshal([]byte(`{"nodes":["a","b"]}`), &got); err != nil {
		t.Fatalf("unmarshal nodes-only priority: %v", err)
	}
	if !reflect.DeepEqual(got.Nodes, []string{"a", "b"}) {
		t.Fatalf("nodes = %v, want [a b]", got.Nodes)
	}
	if len(got.Ranks) != 0 {
		t.Fatalf("nodes-only ranks = %v, want empty", got.Ranks)
	}

	encoded, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("marshal nodes-only priority: %v", err)
	}
	if strings.Contains(string(encoded), `"ranks"`) {
		t.Fatalf("nodes-only encoding unexpectedly included ranks: %s", encoded)
	}
}

func TestEnginePriorityRoundTripsResourceAwareRanks(t *testing.T) {
	want := EnginePriority{
		Engine: "ollama",
		Nodes:  []string{"b", "a"},
		Ranks: []NodeRank{
			{ID: "b", Pending: 1, GPUPressure: 0, GPUPressureKnown: true, VRAMPressure: 2, VRAMPressureKnown: true, Rank: 0},
			{ID: "a", Pending: 4, GPUPressure: 3, GPUPressureKnown: true, VRAMPressure: 3, VRAMPressureKnown: true, Rank: 1},
		},
	}
	encoded, err := json.Marshal(want)
	if err != nil {
		t.Fatalf("marshal engine priority: %v", err)
	}

	var got EnginePriority
	if err := json.Unmarshal(encoded, &got); err != nil {
		t.Fatalf("unmarshal engine priority: %v", err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("round trip = %#v, want %#v", got, want)
	}
}

func TestNodeRankAcceptsOldAndFreshnessAwareJSON(t *testing.T) {
	tests := []struct {
		name     string
		payload  string
		known    bool
		pressure int
	}{
		{name: "old scheduler omits freshness", payload: `{"id":"pc-a","pending":2,"gpuPressure":1,"rank":0}`, pressure: 1},
		{name: "new scheduler marks freshness", payload: `{"id":"pc-a","pending":2,"gpuPressure":1,"gpuPressureKnown":true,"rank":0}`, known: true, pressure: 1},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			var got NodeRank
			if err := json.Unmarshal([]byte(test.payload), &got); err != nil {
				t.Fatalf("unmarshal NodeRank: %v", err)
			}
			if got.ID != "pc-a" || got.Pending != 2 || got.GPUPressure != test.pressure || got.GPUPressureKnown != test.known || got.Rank != 0 {
				t.Fatalf("NodeRank = %+v, want known=%v pressure=%d", got, test.known, test.pressure)
			}
		})
	}
}

func TestPriorityCopiesOwnTheirSlices(t *testing.T) {
	source := EnginePriority{
		Engine: "ollama",
		Nodes:  []string{"a"},
		Ranks:  []NodeRank{{ID: "a", Pending: 2}},
	}
	snapshot := source.Snapshot()
	clone := snapshot.Clone()

	source.Nodes[0] = "source-mutated"
	snapshot.Nodes[0] = "snapshot-mutated"
	source.Ranks[0].Pending = 9
	snapshot.Ranks[0].Pending = 8

	if clone.Nodes[0] != "a" || clone.Ranks[0].Pending != 2 {
		t.Fatalf("clone changed through an aliased slice: %#v", clone)
	}
}
