// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestValidateStrataSettingsRequiresLoopbackHTTP(t *testing.T) {
	test := func(name, endpoint string, wantValid bool) {
		t.Helper()
		t.Run(name, func(t *testing.T) {
			settings := defaultStrataSettings()
			settings.Endpoint = endpoint
			if got := validStrataSettings(settings); got != wantValid {
				t.Fatalf("validStrataSettings(%q) = %v, want %v", endpoint, got, wantValid)
			}
		})
	}
	test("IPv4 loopback", "http://127.0.0.1:8080", true)
	test("OpenAI API base path", "http://127.0.0.1:8080/v1", true)
	test("OpenAI API base path with slash", "http://127.0.0.1:8080/v1/", true)
	test("unexpected base path", "http://127.0.0.1:8080/api", false)
	test("localhost", "http://localhost:8080", true)
	test("LAN address", "http://192.168.1.4:8080", false)
	test("HTTPS", "https://127.0.0.1:8080", false)
	test("credentials", "http://user:secret@127.0.0.1:8080", false)
}

func TestValidateStrataSettingsExplainsInvalidCredentialReference(t *testing.T) {
	settings := defaultStrataSettings()
	settings.APIKeyEnv = "sk-test-secret"
	if err := validateStrataSettings(settings); err == nil || err.Error() != "API key must be an environment variable name, not a key value" {
		t.Fatalf("validateStrataSettings() error = %v, want environment-variable guidance", err)
	}
}

func TestProbeStrataAcceptsOpenAIAPIBasePath(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" {
			t.Errorf("request path = %q, want /v1/models", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"data":[{"id":"qwen-local"}]}`))
	}))
	defer server.Close()

	settings := defaultStrataSettings()
	settings.Endpoint = server.URL + "/v1"
	probe := probeStrata(settings)
	if !probe.Connected || len(probe.ModelIDs) != 1 || probe.ModelIDs[0] != "qwen-local" {
		t.Fatalf("probeStrata() = %+v, want connected with qwen-local", probe)
	}
}

func TestStrataTestRPCReturnsProbeOnly(t *testing.T) {
	settings := defaultStrataSettings()
	probe := strataProbeResult{Connected: true, ModelIDs: []string{"qwen-local"}}
	if got := strataRPCResult("strata:test", settings, probe); !reflect.DeepEqual(got, probe) {
		t.Fatalf("strata:test result = %#v, want probe-only result %+v", got, probe)
	}
	if got := strataRPCResult("strata:save", settings, probe); !reflect.DeepEqual(got, strataSettingsResult{Settings: settings, Probe: probe}) {
		t.Fatalf("strata:save result = %#v, want settings and probe", got)
	}
}

func TestStrataSettingsRoundTripStoresOnlyEnvironmentReference(t *testing.T) {
	configRoot := t.TempDir()
	t.Setenv("LOCALAPPDATA", configRoot)
	t.Setenv("STRATA_API_KEY", "secret-value")
	settings := strataSettings{
		Enabled: true, Endpoint: "http://127.0.0.1:8080", ModelID: "qwen-local", APIKeyEnv: "STRATA_API_KEY",
	}
	if err := saveStrataSettings(settings); err != nil {
		t.Fatalf("save Strata settings: %v", err)
	}
	got, err := loadStrataSettings()
	if err != nil {
		t.Fatalf("load Strata settings: %v", err)
	}
	if got != settings {
		t.Fatalf("settings round trip = %+v, want %+v", got, settings)
	}
	path := filepath.Join(configRoot, "Nvidia Corporation", "Personal AI Router", strataSettingsStoreName)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read Strata settings: %v", err)
	}
	if !strings.Contains(string(data), "STRATA_API_KEY") || strings.Contains(string(data), "secret-value") {
		t.Fatalf("persisted settings did not contain only the environment reference: %s", data)
	}
}
