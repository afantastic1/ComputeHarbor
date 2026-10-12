// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
	"time"
)

func TestCloudOnlyGatewayStartsWithoutFacadesAndRestoresAfterRestart(t *testing.T) {
	portProbe, err := net.Listen("tcp", "127.0.0.1:14326")
	if err != nil {
		t.Skipf("cloud-only lifecycle test requires loopback port 14326: %v", err)
	}
	_ = portProbe.Close()

	temporaryRoot := t.TempDir()
	t.Setenv("LOCALAPPDATA", filepath.Join(temporaryRoot, "appdata"))
	settings := defaultCloudProvidersSettings()
	settings.Config = json.RawMessage(`{"schema_version":1,"providers":[{"id":"test-provider","protocol":"openai_chat_completions","base_url":"https://api.openai.com/v1","auth_ref":"test-vault:provider","enabled":true,"models":[{"public_id":"cloud/test/chat","upstream_id":"test-chat","capabilities":["chat","streaming"]}]}]}`)
	settings.CloudEnabled = true
	settings.Policy = "cloud_only"
	settings.MonthlyBudgetUSD = 5
	settings.PerRequestMaxEstimatedCostUSD = 1
	if err := saveCloudProvidersSettings(settings); err != nil {
		t.Fatalf("save isolated Cloud settings: %v", err)
	}

	proxyPath := buildProxyBinary(t, temporaryRoot)
	broker := NewBroker(NewCodec(rwDiscard{}), workerPaths{proxy: proxyPath})
	first, err := broker.spawnProxy()
	if err != nil {
		t.Fatalf("start Gateway without local facades: %v", err)
	}
	t.Cleanup(first.Stop)
	if broker.getProxy() != nil {
		t.Fatal("cloud-only Gateway unexpectedly published an Ollama facade handle")
	}
	if broker.getProxyProcess() != first {
		t.Fatal("cloud-only Gateway process handle was not published")
	}
	assertCloudOnlyGatewayModelAvailable(t)

	settings.Config = json.RawMessage(`{"schema_version":1,"providers":[{"id":"test-provider","protocol":"openai_chat_completions","base_url":"https://api.openai.com/v1","auth_ref":"test-vault:provider","enabled":true,"models":[{"public_id":"cloud/test/updated","upstream_id":"test-chat","capabilities":["chat","streaming"]}]}]}`)
	settingsParams, err := json.Marshal(settings)
	if err != nil {
		t.Fatalf("marshal updated Cloud settings: %v", err)
	}
	saveID := json.RawMessage("1")
	broker.handleCloudProvidersRPC(&Message{Method: "cloudproviders:save", ID: &saveID, Params: settingsParams})
	assertCloudOnlyGatewayModelAvailable(t, "cloud/test/updated")

	credentialParams, err := json.Marshal(map[string]string{
		"authRef":    "test-vault:provider",
		"credential": "synthetic-cloud-gateway-test-secret",
	})
	if err != nil {
		t.Fatalf("marshal provider credential request: %v", err)
	}
	credentialID := json.RawMessage("2")
	broker.handleCloudProvidersRPC(&Message{Method: "cloudproviders:credential:set", ID: &credentialID, Params: credentialParams})
	broker.cloudCredentialMu.RLock()
	credential := broker.cloudCredentials["test-vault:provider"]
	broker.cloudCredentialMu.RUnlock()
	if credential != "synthetic-cloud-gateway-test-secret" {
		t.Fatal("cloud-only Gateway did not accept and retain the provider credential")
	}
	first.Stop()

	blockedPort, err := net.Listen("tcp", "127.0.0.1:14326")
	if err != nil {
		t.Fatalf("reserve Gateway port for bind-failure case: %v", err)
	}
	failed, spawnErr := broker.spawnProxy()
	if spawnErr == nil {
		if failed != nil {
			failed.Stop()
		}
		_ = blockedPort.Close()
		t.Fatal("Proxy spawn succeeded while the only Gateway listener port was occupied")
	}
	_ = blockedPort.Close()

	second, err := broker.spawnProxy()
	if err != nil {
		t.Fatalf("restart Gateway without local facades: %v", err)
	}
	t.Cleanup(second.Stop)
	assertCloudOnlyGatewayModelAvailable(t, "cloud/test/updated")
}

func buildProxyBinary(t *testing.T, destination string) string {
	t.Helper()
	name := "nvpair-proxy"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	path := filepath.Join(destination, name)
	command := exec.Command("go", "build", "-o", path, ".")
	command.Dir = filepath.Join("..", "nvpair-proxy")
	if output, err := command.CombinedOutput(); err != nil {
		t.Fatalf("build nvpair-proxy for cloud-only lifecycle test: %v\n%s", err, output)
	}
	return path
}

func assertCloudOnlyGatewayModelAvailable(t *testing.T, expectedID ...string) {
	t.Helper()
	request, err := http.NewRequestWithContext(
		context.Background(), http.MethodGet, "http://127.0.0.1:14326/v1/models", nil,
	)
	if err != nil {
		t.Fatalf("create Gateway model request: %v", err)
	}
	request.Header.Set("Authorization", "Bearer broker-cloud-test-token-12345678901234567890")
	client := &http.Client{Timeout: 3 * time.Second}
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("request cloud-only Gateway model directory: %v", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("cloud-only model directory status=%d", response.StatusCode)
	}
	var directory struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	if err := json.NewDecoder(response.Body).Decode(&directory); err != nil {
		t.Fatalf("decode cloud-only model directory: %v", err)
	}
	for _, model := range directory.Data {
		want := "cloud/test/chat"
		if len(expectedID) > 0 {
			want = expectedID[0]
		}
		if model.ID == want {
			return
		}
	}
	t.Fatalf("cloud-only Gateway directory omitted configured Cloud model: %+v", directory.Data)
}
