// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestGatewayConfigureAppliesValidatedSettingsAndRegistry(t *testing.T) {
	dispatcher := newGatewayDispatcher(NewProxy(NewCodec(rwNop{})))
	params := gatewayConfigureParams{
		Config: json.RawMessage(validCloudConfig), CloudEnabled: true,
		Policy: gatewayPolicyCloudOnly, FallbackCloudModel: "cloud/deepseek/deepseek-chat", MonthlyBudgetUSD: 20,
		PerRequestMaxEstimatedCostUSD: 2,
		AuthorizedNodes: []gatewayCloudNodeAuthorization{{
			NodeUUID: "10000000-0000-0000-0000-000000000001", CertFingerprint: cloudCertificateFingerprint([]byte("peer-cert")),
		}},
	}
	if err := dispatcher.configure(params); err != nil {
		t.Fatalf("configure: %v", err)
	}
	if got := len(dispatcher.registry.Snapshot().Models()); got != 1 {
		t.Fatalf("registered model count=%d, want 1", got)
	}
	settings := dispatcher.settings.Load()
	if settings.policy != gatewayPolicyCloudOnly || !settings.cloudEnabled || settings.fallbackCloudModel != "cloud/deepseek/deepseek-chat" || settings.monthlyBudgetUSD != 20 {
		t.Fatalf("settings=%+v", settings)
	}
	if !isAuthorizedCloudNode(settings, "10000000-0000-0000-0000-000000000001", []byte("peer-cert")) {
		t.Fatal("configured paired node was not authorized")
	}
}

func TestPeerCloudModelDirectoryExposesNoProviderEndpointOrCredentialReference(t *testing.T) {
	dispatcher := newGatewayDispatcher(NewProxy(NewCodec(rwNop{})))
	if err := dispatcher.registry.ReplaceJSON([]byte(validCloudConfig), cloudConfigOptions{}); err != nil {
		t.Fatalf("configure registry: %v", err)
	}
	dispatcher.settings.Store(&gatewayRoutingSettings{cloudEnabled: true, policy: gatewayPolicyCloudOnly})
	encoded, err := json.Marshal(dispatcher.peerCloudModelDirectory())
	if err != nil {
		t.Fatalf("marshal public directory: %v", err)
	}
	value := string(encoded)
	for _, secret := range []string{"api.deepseek.com", "user-vault:", "auth_ref", "base_url"} {
		if strings.Contains(value, secret) {
			t.Errorf("public Cloud directory contains private field %q: %s", secret, value)
		}
	}
	if !strings.Contains(value, "cloud/deepseek/deepseek-chat") {
		t.Fatalf("public model ID missing from directory: %s", value)
	}
}

func TestGatewayConfigureRejectsMalformedAuthorizedCloudNode(t *testing.T) {
	dispatcher := newGatewayDispatcher(NewProxy(NewCodec(rwNop{})))
	err := dispatcher.configure(gatewayConfigureParams{
		Config: json.RawMessage(validCloudConfig), AuthorizedNodes: []gatewayCloudNodeAuthorization{{
			NodeUUID: "not-a-node-uuid", CertFingerprint: cloudCertificateFingerprint([]byte("peer-cert")),
		}},
	})
	if err == nil {
		t.Fatal("configure accepted malformed authorized node identity")
	}
	if len(dispatcher.settings.Load().authorizedCloudNodes) != 0 {
		t.Fatal("invalid authorization replaced live settings")
	}
}

func TestGatewayConfigureRejectsInvalidPolicyWithoutReplacingRegistry(t *testing.T) {
	dispatcher := newGatewayDispatcher(NewProxy(NewCodec(rwNop{})))
	if err := dispatcher.configure(gatewayConfigureParams{Config: json.RawMessage(validCloudConfig), Policy: "unknown"}); err == nil {
		t.Fatal("configure accepted unsupported policy")
	}
	if got := len(dispatcher.registry.Snapshot().Models()); got != 0 {
		t.Fatalf("registry model count=%d after invalid configuration, want 0", got)
	}
}

func TestGatewayConfigureRejectsLegacyPolicyWithoutReplacingRegistry(t *testing.T) {
	dispatcher := newGatewayDispatcher(NewProxy(NewCodec(rwNop{})))
	err := dispatcher.configure(gatewayConfigureParams{
		Config: json.RawMessage(validCloudConfig), Policy: gatewayPolicy("prefer_cloud"),
	})
	if err == nil {
		t.Fatal("configure accepted removed prefer_cloud policy")
	}
	if got := len(dispatcher.registry.Snapshot().Models()); got != 0 {
		t.Fatalf("registry model count=%d after invalid configuration, want 0", got)
	}
}

func TestGatewayConfigureRejectsMalformedFallbackCloudModel(t *testing.T) {
	dispatcher := newGatewayDispatcher(NewProxy(NewCodec(rwNop{})))
	var params gatewayConfigureParams
	if err := json.Unmarshal([]byte(`{"config":`+validCloudConfig+`,"policy":"local_only","fallbackCloudModel":"https://attacker.example/model"}`), &params); err != nil {
		t.Fatalf("decode Gateway configuration: %v", err)
	}
	err := dispatcher.configure(params)
	if err == nil {
		t.Fatal("configure accepted a fallback model that is not a canonical cloud model ID")
	}
	if got := len(dispatcher.registry.Snapshot().Models()); got != 0 {
		t.Fatalf("registry model count=%d after invalid configuration, want 0", got)
	}
}

func TestGatewayCredentialCannotBeReadBackFromResponse(t *testing.T) {
	proxy := NewProxy(NewCodec(rwNop{}))
	if err := proxy.gatewayDispatcher.registry.ReplaceJSON([]byte(validCloudConfig), cloudConfigOptions{}); err != nil {
		t.Fatalf("configure registry: %v", err)
	}
	if err := proxy.setGatewayCredential(gatewayCredentialParams{AuthRef: "user-vault:deepseek-primary", Credential: "test-secret"}); err != nil {
		t.Fatalf("set credential: %v", err)
	}
	if strings.Contains("{\"credentialConfigured\":true}", "test-secret") {
		t.Fatal("credential response leaked secret")
	}
	if err := proxy.setGatewayCredential(gatewayCredentialParams{AuthRef: "unknown", Credential: "test-secret"}); err == nil {
		t.Fatal("accepted credential for unknown auth reference")
	}
	if err := proxy.setGatewayCredential(gatewayCredentialParams{AuthRef: "user-vault:deepseek-primary", Credential: "bad\nkey"}); err == nil {
		t.Fatal("accepted credential containing a newline")
	}
}
