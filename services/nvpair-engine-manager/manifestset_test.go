// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"io/fs"
	"strings"
	"testing"

	"nvpair-shared/engines"
)

// nvpair-shared/engines states that an engine's Name must match its manifest
// basename, and argues that declaring the set once turns a mismatch into a
// build error. For the broker, proxy and scheduler it does, because they all
// read the table. This component embeds manifests/*.json and only resolves an
// engine by id in the settings relay, so adding an engine to the table without
// a manifest (or the reverse) still compiles.
//
// The failure that produces is the one the package comment describes — an
// engine that discovers and advertises but never resolves a model owner —
// which is invisible until inference is attempted. The existing manifest tests
// validate content, not the set, so this closes the last gap.
//
// External engines are exempt: they are user-managed, the manager never
// installs, starts, or stops them, so they carry no install/release manifest.
// The set check below therefore covers only engines the manager actually owns.
func TestBundledManifestSetMatchesEngineTable(t *testing.T) {
	entries, err := fs.ReadDir(bundledManifests, "manifests")
	if err != nil {
		t.Fatal(err)
	}

	have := map[string]bool{}
	for _, e := range entries {
		have[strings.TrimSuffix(e.Name(), ".json")] = true
	}

	for _, engine := range engines.All() {
		if engine.External {
			continue
		}
		if !have[engine.Name] {
			t.Errorf("no manifests/%s.json for engine %q", engine.Name, engine.Name)
		}
		delete(have, engine.Name)
	}
	for name := range have {
		t.Errorf("manifests/%s.json has no entry in nvpair-shared/engines", name)
	}
}
