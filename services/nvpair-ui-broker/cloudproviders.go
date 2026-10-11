// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"sync"

	"nvpair-shared/appdir"
)

const cloudProvidersStoreName = "cloud-providers.json"

var fallbackCloudModelPattern = regexp.MustCompile(`^cloud(?:/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}){2,}$`)

type cloudNodeAuthorization struct {
	NodeUUID        string `json:"nodeUuid"`
	CertFingerprint string `json:"certFingerprint"`
}

type cloudProvidersSettings struct {
	SchemaVersion                 int                      `json:"schema_version"`
	Config                        json.RawMessage          `json:"config"`
	CloudEnabled                  bool                     `json:"cloudEnabled"`
	Policy                        string                   `json:"policy"`
	PolicyMigrationRequired       bool                     `json:"policyMigrationRequired,omitempty"`
	FallbackCloudModel            string                   `json:"fallbackCloudModel"`
	AllowPaidFallback             bool                     `json:"allowPaidFallback"`
	MonthlyBudgetUSD              float64                  `json:"monthlyBudgetUSD"`
	PerRequestMaxEstimatedCostUSD float64                  `json:"perRequestMaxEstimatedCostUSD"`
	AuthorizedNodes               []cloudNodeAuthorization `json:"authorizedNodes"`
}

var cloudProvidersStoreMu sync.Mutex

func defaultCloudProvidersSettings() cloudProvidersSettings {
	return cloudProvidersSettings{
		SchemaVersion: 1, Config: json.RawMessage(`{"schema_version":1,"providers":[]}`), AuthorizedNodes: []cloudNodeAuthorization{},
		Policy: "local_only",
	}
}

func loadCloudProvidersSettings() (cloudProvidersSettings, error) {
	path, err := appdir.Path(cloudProvidersStoreName)
	if err != nil {
		return cloudProvidersSettings{}, fmt.Errorf("resolve cloud provider settings path")
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return defaultCloudProvidersSettings(), nil
	}
	if err != nil {
		return cloudProvidersSettings{}, fmt.Errorf("read cloud provider settings")
	}
	settings, err := parseCloudProvidersSettings(data)
	if err != nil || settings.SchemaVersion != 1 || len(settings.Config) == 0 {
		return cloudProvidersSettings{}, fmt.Errorf("cloud provider settings are invalid")
	}
	if settings.AuthorizedNodes == nil {
		settings.AuthorizedNodes = []cloudNodeAuthorization{}
	}
	settings.PolicyMigrationRequired = false
	if settings.Policy == "prefer_cloud" {
		settings.Policy = "local_only"
		settings.PolicyMigrationRequired = true
	}
	if !validCloudRoutingPolicy(settings.Policy) || !validFallbackCloudModel(settings.FallbackCloudModel) {
		return cloudProvidersSettings{}, fmt.Errorf("cloud provider settings are invalid")
	}
	return settings, nil
}

func validCloudProvidersSettings(settings cloudProvidersSettings) bool {
	if settings.PolicyMigrationRequired || !validCloudRoutingPolicy(settings.Policy) {
		return false
	}
	return validFallbackCloudModel(settings.FallbackCloudModel)
}

func validFallbackCloudModel(model string) bool {
	return model == "" || fallbackCloudModelPattern.MatchString(model)
}

func validCloudRoutingPolicy(policy string) bool {
	switch policy {
	case "local_only", "cloud_only", "prefer_local":
		return true
	default:
		return false
	}
}

func parseCloudProvidersSettings(data []byte) (cloudProvidersSettings, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var settings cloudProvidersSettings
	if err := decoder.Decode(&settings); err != nil {
		return cloudProvidersSettings{}, err
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return cloudProvidersSettings{}, fmt.Errorf("settings must contain one JSON value")
	}
	return settings, nil
}

func saveCloudProvidersSettings(settings cloudProvidersSettings) error {
	path, err := appdir.Path(cloudProvidersStoreName)
	if err != nil {
		return fmt.Errorf("resolve cloud provider settings path")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return fmt.Errorf("create cloud provider settings directory")
	}
	data, err := json.Marshal(settings)
	if err != nil {
		return fmt.Errorf("encode cloud provider settings")
	}
	temp, err := os.CreateTemp(filepath.Dir(path), ".cloud-providers-*.tmp")
	if err != nil {
		return fmt.Errorf("create cloud provider settings file")
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		_ = temp.Close()
		return fmt.Errorf("protect cloud provider settings file")
	}
	if _, err := temp.Write(data); err != nil {
		_ = temp.Close()
		return fmt.Errorf("write cloud provider settings")
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return fmt.Errorf("sync cloud provider settings")
	}
	if err := temp.Close(); err != nil {
		return fmt.Errorf("close cloud provider settings")
	}
	if err := os.Rename(tempName, path); err != nil {
		return fmt.Errorf("replace cloud provider settings")
	}
	return nil
}

func (b *Broker) handleCloudProvidersRPC(msg *Message) {
	if msg.Method == "cloudproviders:get" {
		cloudProvidersStoreMu.Lock()
		settings, err := loadCloudProvidersSettings()
		cloudProvidersStoreMu.Unlock()
		if err != nil {
			b.codec.RespondError(msg.ID, -32000, err.Error())
			return
		}
		_ = b.codec.Respond(msg.ID, settings)
		return
	}
	if msg.Method == "cloudproviders:save" {
		settings, err := parseCloudProvidersSettings(msg.Params)
		if err != nil || settings.SchemaVersion != 1 || len(settings.Config) == 0 || !validCloudProvidersSettings(settings) {
			b.codec.RespondError(msg.ID, -32602, "invalid cloud provider settings")
			return
		}
		cloudProvidersStoreMu.Lock()
		previous, loadErr := loadCloudProvidersSettings()
		if loadErr != nil {
			cloudProvidersStoreMu.Unlock()
			b.codec.RespondError(msg.ID, -32000, loadErr.Error())
			return
		}
		if err := saveCloudProvidersSettings(settings); err != nil {
			cloudProvidersStoreMu.Unlock()
			b.codec.RespondError(msg.ID, -32000, err.Error())
			return
		}
		params, _ := json.Marshal(gatewaySettingsRPCParams(settings))
		proxy := b.getProxyProcess()
		if proxy == nil {
			_ = saveCloudProvidersSettings(previous)
			cloudProvidersStoreMu.Unlock()
			b.codec.RespondError(msg.ID, -32000, "proxy is not available")
			return
		}
		_, rpcErr, callErr := proxy.Call(context.Background(), "gateway/configure", params)
		if callErr != nil || rpcErr != nil {
			_ = saveCloudProvidersSettings(previous)
			cloudProvidersStoreMu.Unlock()
			if rpcErr != nil {
				b.codec.RespondError(msg.ID, rpcErr.Code, "proxy rejected cloud provider settings")
			} else {
				b.codec.RespondError(msg.ID, -32000, "proxy did not accept cloud provider settings")
			}
			return
		}
		b.pruneCloudCredentials(settings.Config)
		cloudProvidersStoreMu.Unlock()
		_ = b.codec.Respond(msg.ID, map[string]bool{"saved": true})
		if err := b.codec.Notify("cloudproviders:changed", settings); err != nil {
			slog.Debug("cloud provider settings push failed", "err", err)
		}
		return
	}
	if msg.Method == "cloudproviders:test" {
		var params struct {
			ProviderID string `json:"providerId"`
		}
		if err := json.Unmarshal(msg.Params, &params); err != nil || params.ProviderID == "" {
			b.codec.RespondError(msg.ID, -32602, "invalid provider test request")
			return
		}
		proxy := b.getProxyProcess()
		if proxy == nil {
			b.codec.RespondError(msg.ID, -32000, "proxy is not available")
			return
		}
		proxyParams, _ := json.Marshal(params)
		result, rpcErr, callErr := proxy.Call(context.Background(), "gateway/provider/test", proxyParams)
		if rpcErr != nil || callErr != nil {
			b.codec.RespondError(msg.ID, -32000, "provider connection test failed")
			return
		}
		_ = b.codec.Respond(msg.ID, result)
		return
	}

	var params struct {
		AuthRef    string `json:"authRef"`
		Credential string `json:"credential"`
	}
	if err := json.Unmarshal(msg.Params, &params); err != nil || params.AuthRef == "" {
		b.codec.RespondError(msg.ID, -32602, "invalid provider credential request")
		return
	}
	proxy := b.getProxyProcess()
	if proxy == nil {
		b.codec.RespondError(msg.ID, -32000, "proxy is not available")
		return
	}
	proxyParams, _ := json.Marshal(map[string]string{"authRef": params.AuthRef, "credential": params.Credential})
	result, rpcErr, callErr := proxy.Call(context.Background(), "gateway/credential/set", proxyParams)
	if rpcErr != nil {
		b.codec.RespondError(msg.ID, rpcErr.Code, "proxy rejected provider credential")
		return
	}
	if callErr != nil {
		b.codec.RespondError(msg.ID, -32000, "proxy did not accept provider credential")
		return
	}
	b.rememberCloudCredential(params.AuthRef, params.Credential)
	_ = b.codec.Respond(msg.ID, result)
}

func (b *Broker) rememberCloudCredential(authRef, credential string) {
	b.cloudCredentialMu.Lock()
	defer b.cloudCredentialMu.Unlock()
	if b.cloudCredentials == nil {
		b.cloudCredentials = make(map[string]string)
	}
	if credential == "" {
		delete(b.cloudCredentials, authRef)
		return
	}
	b.cloudCredentials[authRef] = credential
}

func (b *Broker) pruneCloudCredentials(config json.RawMessage) {
	var document struct {
		Providers []struct {
			AuthRef string `json:"auth_ref"`
		} `json:"providers"`
	}
	if json.Unmarshal(config, &document) != nil {
		return
	}
	allowed := make(map[string]struct{}, len(document.Providers))
	for _, provider := range document.Providers {
		allowed[provider.AuthRef] = struct{}{}
	}
	b.cloudCredentialMu.Lock()
	for authRef := range b.cloudCredentials {
		if _, ok := allowed[authRef]; !ok {
			delete(b.cloudCredentials, authRef)
		}
	}
	b.cloudCredentialMu.Unlock()
}

func (b *Broker) restoreCloudCredentials(proxy *proxyProcess) {
	b.cloudCredentialMu.RLock()
	credentials := make(map[string]string, len(b.cloudCredentials))
	for authRef, credential := range b.cloudCredentials {
		credentials[authRef] = credential
	}
	b.cloudCredentialMu.RUnlock()
	for authRef, credential := range credentials {
		params, _ := json.Marshal(map[string]string{"authRef": authRef, "credential": credential})
		_, rpcErr, err := proxy.Call(context.Background(), "gateway/credential/set", params)
		if err != nil || rpcErr != nil {
			slog.Warn("could not restore cloud credential into proxy", "authRef", authRef)
		}
	}
}

func gatewaySettingsRPCParams(settings cloudProvidersSettings) map[string]any {
	return map[string]any{
		"config": settings.Config, "cloudEnabled": settings.CloudEnabled,
		"policy": settings.Policy, "allowPaidFallback": settings.AllowPaidFallback,
		"fallbackCloudModel":            settings.FallbackCloudModel,
		"monthlyBudgetUSD":              settings.MonthlyBudgetUSD,
		"perRequestMaxEstimatedCostUSD": settings.PerRequestMaxEstimatedCostUSD,
		"authorizedNodes":               settings.AuthorizedNodes,
	}
}
