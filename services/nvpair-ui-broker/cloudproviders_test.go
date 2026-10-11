// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestCloudProvidersSettingsRoundTripStoresOnlyPublicConfiguration(t *testing.T) {
	t.Setenv("LOCALAPPDATA", t.TempDir())
	settings := cloudProvidersSettings{
		SchemaVersion: 1,
		Config:        json.RawMessage(`{"schema_version":1,"providers":[{"id":"p","auth_ref":"user-vault:p"}]}`),
		CloudEnabled: true, Policy: "cloud_only", FallbackCloudModel: "cloud/p/model", MonthlyBudgetUSD: 10,
		PerRequestMaxEstimatedCostUSD: 1,
		AuthorizedNodes: []cloudNodeAuthorization{{
			NodeUUID: "10000000-0000-0000-0000-000000000001", CertFingerprint: "sha256:" + strings.Repeat("a", 64),
		}},
	}
	if err := saveCloudProvidersSettings(settings); err != nil {
		t.Fatalf("save settings: %v", err)
	}
	got, err := loadCloudProvidersSettings()
	if err != nil {
		t.Fatalf("load settings: %v", err)
	}
	if got.CloudEnabled != settings.CloudEnabled || got.Policy != settings.Policy || got.FallbackCloudModel != settings.FallbackCloudModel || string(got.Config) != string(settings.Config) ||
		!reflect.DeepEqual(got.AuthorizedNodes, settings.AuthorizedNodes) {
		t.Fatalf("round trip=%+v, want %+v", got, settings)
	}
	path := filepath.Join(os.Getenv("LOCALAPPDATA"), "Nvidia Corporation", "Personal AI Router", cloudProvidersStoreName)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read persisted config: %v", err)
	}
	if strings.Contains(string(data), "credential") || strings.Contains(string(data), "api_key") {
		t.Fatalf("persisted cloud config contains a secret field: %s", data)
	}
}

func TestCloudProvidersSettingsRejectsUnsupportedSchema(t *testing.T) {
	t.Setenv("LOCALAPPDATA", t.TempDir())
	if err := saveCloudProvidersSettings(cloudProvidersSettings{SchemaVersion: 2, Config: json.RawMessage(`{}`)}); err != nil {
		t.Fatalf("seed future settings: %v", err)
	}
	if _, err := loadCloudProvidersSettings(); err == nil {
		t.Fatal("load accepted unsupported schema version")
	}
}

func TestCloudProvidersSettingsRejectsUnknownSecretFields(t *testing.T) {
	_, err := parseCloudProvidersSettings([]byte(`{"schema_version":1,"config":{"schema_version":1,"providers":[]},"cloudEnabled":false,"policy":"local_only","allowPaidFallback":false,"monthlyBudgetUSD":0,"perRequestMaxEstimatedCostUSD":0,"api_key":"secret"}`))
	if err == nil {
		t.Fatal("accepted an unknown api_key field")
	}
}

func TestGatewaySettingsRPCParamsCarriesFallbackCloudModel(t *testing.T) {
	settings := cloudProvidersSettings{FallbackCloudModel: "cloud/provider/model"}
	params := gatewaySettingsRPCParams(settings)
	encoded, err := json.Marshal(params)
	if err != nil {
		t.Fatalf("encode Gateway settings: %v", err)
	}
	var got map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &got); err != nil {
		t.Fatalf("decode Gateway settings: %v", err)
	}
	var modelID string
	if err := json.Unmarshal(got["fallbackCloudModel"], &modelID); err != nil {
		t.Fatalf("decode fallback model: %v", err)
	}
	if modelID != settings.FallbackCloudModel {
		t.Fatalf("fallback model=%q, want %q", modelID, settings.FallbackCloudModel)
	}
}

func TestCloudProvidersSettingsRequiresConfirmationForLegacyPreferCloud(t *testing.T) {
	t.Setenv("LOCALAPPDATA", t.TempDir())
	legacy := cloudProvidersSettings{
		SchemaVersion: 1,
		Config:        json.RawMessage(`{"schema_version":1,"providers":[]}`),
		CloudEnabled:  true,
		Policy:        "prefer_cloud",
	}
	if err := saveCloudProvidersSettings(legacy); err != nil {
		t.Fatalf("save legacy settings: %v", err)
	}

	got, err := loadCloudProvidersSettings()
	if err != nil {
		t.Fatalf("load legacy settings: %v", err)
	}
	if got.Policy != "local_only" {
		t.Fatalf("loaded policy=%q, want local_only until confirmation", got.Policy)
	}
	encoded, err := json.Marshal(got)
	if err != nil {
		t.Fatalf("encode loaded settings: %v", err)
	}
	var response map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &response); err != nil {
		t.Fatalf("decode loaded settings response: %v", err)
	}
	var migrationRequired bool
	if err := json.Unmarshal(response["policyMigrationRequired"], &migrationRequired); err != nil {
		t.Fatalf("decode migration confirmation flag: %v", err)
	}
	if !migrationRequired {
		t.Fatal("loaded legacy policy does not require explicit confirmation")
	}

	path := filepath.Join(os.Getenv("LOCALAPPDATA"), "Nvidia Corporation", "Personal AI Router", cloudProvidersStoreName)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read persisted legacy settings: %v", err)
	}
	var persisted cloudProvidersSettings
	if err := json.Unmarshal(data, &persisted); err != nil {
		t.Fatalf("decode persisted legacy settings: %v", err)
	}
	if persisted.Policy != "prefer_cloud" {
		t.Fatalf("persisted policy=%q, want original policy until user confirms", persisted.Policy)
	}
}

func TestCloudProvidersSettingsRejectsInvalidStoredPolicy(t *testing.T) {
	t.Setenv("LOCALAPPDATA", t.TempDir())
	if err := saveCloudProvidersSettings(cloudProvidersSettings{
		SchemaVersion: 1, Config: json.RawMessage(`{"schema_version":1,"providers":[]}`), Policy: "automatic",
	}); err != nil {
		t.Fatalf("save invalid settings fixture: %v", err)
	}
	if _, err := loadCloudProvidersSettings(); err == nil {
		t.Fatal("load accepted an unsupported stored policy")
	}
}

func TestCloudProvidersSettingsRejectsMalformedStoredFallbackModel(t *testing.T) {
	t.Setenv("LOCALAPPDATA", t.TempDir())
	if err := saveCloudProvidersSettings(cloudProvidersSettings{
		SchemaVersion: 1, Config: json.RawMessage(`{"schema_version":1,"providers":[]}`),
		Policy: "prefer_local", FallbackCloudModel: "cloud/not a valid id",
	}); err != nil {
		t.Fatalf("save invalid settings fixture: %v", err)
	}
	if _, err := loadCloudProvidersSettings(); err == nil {
		t.Fatal("load accepted a malformed stored fallback model ID")
	}
}
