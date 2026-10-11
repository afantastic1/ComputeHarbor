// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"nvpair-shared/appdir"
)

const strataSettingsStoreName = "strata.json"

var (
	strataSettingsMu = sync.Mutex{}
	environmentName  = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)
)

type strataSettings struct {
	Enabled   bool   `json:"enabled"`
	Endpoint  string `json:"endpoint"`
	ModelID   string `json:"modelId,omitempty"`
	APIKeyEnv string `json:"apiKeyEnv,omitempty"`
}

type strataProbeResult struct {
	Connected bool     `json:"connected"`
	ErrorCode string   `json:"errorCode,omitempty"`
	ModelIDs  []string `json:"modelIds"`
}

type strataSettingsResult struct {
	Settings strataSettings    `json:"settings"`
	Probe    strataProbeResult `json:"probe"`
}

func strataRPCResult(method string, settings strataSettings, probe strataProbeResult) any {
	if method == "strata:test" {
		return probe
	}
	return strataSettingsResult{Settings: settings, Probe: probe}
}

func defaultStrataSettings() strataSettings {
	return strataSettings{Endpoint: "http://127.0.0.1:8080"}
}

func validStrataSettings(settings strataSettings) bool {
	return validateStrataSettings(settings) == nil
}

func validateStrataSettings(settings strataSettings) error {
	parsed, err := url.Parse(settings.Endpoint)
	if err != nil || parsed.Scheme != "http" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" || (parsed.Path != "" && parsed.Path != "/" && strings.TrimRight(parsed.Path, "/") != "/v1") {
		return fmt.Errorf("endpoint must be an HTTP loopback address, optionally ending in /v1")
	}
	port, err := strconv.Atoi(parsed.Port())
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("endpoint must include a valid port")
	}
	host := parsed.Hostname()
	ip := net.ParseIP(host)
	if host != "localhost" && (ip == nil || !ip.IsLoopback()) {
		return fmt.Errorf("endpoint must use localhost or a loopback IP address")
	}
	if settings.ModelID != "" && strings.TrimSpace(settings.ModelID) != settings.ModelID {
		return fmt.Errorf("model ID cannot start or end with whitespace")
	}
	if settings.APIKeyEnv != "" && !environmentName.MatchString(settings.APIKeyEnv) {
		return fmt.Errorf("API key must be an environment variable name, not a key value")
	}
	return nil
}

func loadStrataSettings() (strataSettings, error) {
	path, err := appdir.Path(strataSettingsStoreName)
	if err != nil {
		return strataSettings{}, fmt.Errorf("resolve Strata settings path")
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return defaultStrataSettings(), nil
	}
	if err != nil {
		return strataSettings{}, fmt.Errorf("read Strata settings")
	}
	var settings strataSettings
	if err := json.Unmarshal(data, &settings); err != nil || !validStrataSettings(settings) {
		return strataSettings{}, fmt.Errorf("Strata settings are invalid")
	}
	return settings, nil
}

func saveStrataSettings(settings strataSettings) error {
	if !validStrataSettings(settings) {
		return fmt.Errorf("Strata settings are invalid")
	}
	path, err := appdir.Path(strataSettingsStoreName)
	if err != nil {
		return fmt.Errorf("resolve Strata settings path")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return fmt.Errorf("create Strata settings directory")
	}
	data, err := json.Marshal(settings)
	if err != nil {
		return fmt.Errorf("encode Strata settings")
	}
	temp, err := os.CreateTemp(filepath.Dir(path), ".strata-*.tmp")
	if err != nil {
		return fmt.Errorf("create Strata settings file")
	}
	name := temp.Name()
	defer os.Remove(name)
	if err := temp.Chmod(0o600); err != nil {
		_ = temp.Close()
		return fmt.Errorf("secure Strata settings file")
	}
	if _, err := temp.Write(data); err != nil {
		_ = temp.Close()
		return fmt.Errorf("write Strata settings")
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return fmt.Errorf("sync Strata settings")
	}
	if err := temp.Close(); err != nil {
		return fmt.Errorf("close Strata settings")
	}
	if err := os.Rename(name, path); err != nil {
		return fmt.Errorf("save Strata settings")
	}
	return nil
}

func probeStrata(settings strataSettings) strataProbeResult {
	result := strataProbeResult{ModelIDs: []string{}}
	if !validStrataSettings(settings) {
		result.ErrorCode = "invalid_endpoint"
		return result
	}
	parsed, _ := url.Parse(settings.Endpoint)
	basePath := strings.TrimRight(parsed.Path, "/")
	if basePath != "/v1" {
		basePath = "/v1"
	}
	parsed.Path = basePath + "/models"
	request, err := http.NewRequest(http.MethodGet, parsed.String(), nil)
	if err != nil {
		result.ErrorCode = "invalid_endpoint"
		return result
	}
	if settings.APIKeyEnv != "" {
		if key, ok := os.LookupEnv(settings.APIKeyEnv); ok && key != "" {
			request.Header.Set("Authorization", "Bearer "+key)
		}
	}
	client := &http.Client{Timeout: 3 * time.Second}
	response, err := client.Do(request)
	if err != nil {
		result.ErrorCode = "not_running"
		return result
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
		result.ErrorCode = "authentication_failed"
		return result
	}
	if response.StatusCode != http.StatusOK {
		result.ErrorCode = "protocol_incompatible"
		return result
	}
	var body struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	if err := json.NewDecoder(response.Body).Decode(&body); err != nil {
		result.ErrorCode = "protocol_incompatible"
		return result
	}
	for _, model := range body.Data {
		if model.ID != "" {
			result.ModelIDs = append(result.ModelIDs, model.ID)
		}
	}
	if len(result.ModelIDs) == 0 {
		result.ErrorCode = "model_not_recognized"
		return result
	}
	if settings.ModelID != "" && !slices.Contains(result.ModelIDs, settings.ModelID) {
		result.ErrorCode = "model_not_recognized"
		return result
	}
	result.Connected = true
	return result
}

func (b *Broker) handleStrataRPC(msg *Message) {
	strataSettingsMu.Lock()
	defer strataSettingsMu.Unlock()
	switch msg.Method {
	case "strata:get":
		settings, err := loadStrataSettings()
		if err != nil {
			_ = b.codec.RespondError(msg.ID, -32000, err.Error())
			return
		}
		_ = b.codec.Respond(msg.ID, strataSettingsResult{Settings: settings, Probe: probeStrata(settings)})
	case "strata:test", "strata:save":
		var settings strataSettings
		if err := json.Unmarshal(msg.Params, &settings); err != nil {
			_ = b.codec.RespondError(msg.ID, -32602, "Strata settings payload is malformed")
			return
		}
		if err := validateStrataSettings(settings); err != nil {
			_ = b.codec.RespondError(msg.ID, -32602, err.Error())
			return
		}
		probe := probeStrata(settings)
		if msg.Method == "strata:save" {
			if settings.Enabled && !probe.Connected {
				_ = b.codec.RespondError(msg.ID, -32000, "connect to Strata and resolve its model before enabling it")
				return
			}
			if err := saveStrataSettings(settings); err != nil {
				_ = b.codec.RespondError(msg.ID, -32000, err.Error())
				return
			}
			if profile, ok := engineProxyProfileFor("strata"); ok {
				b.reconcileEngineAdvertisements(&http.Client{Timeout: 3 * time.Second}, []engineProxyProfile{profile})
			}
		}
		_ = b.codec.Respond(msg.ID, strataRPCResult(msg.Method, settings, probe))
	default:
		_ = b.codec.RespondError(msg.ID, -32601, "Strata method not found")
	}
}
