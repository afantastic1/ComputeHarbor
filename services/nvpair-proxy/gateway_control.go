// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

func parseAuthorizedCloudNodes(nodes []gatewayCloudNodeAuthorization) (map[string]string, error) {
	allowed := make(map[string]string, len(nodes))
	for _, node := range nodes {
		nodeUUID := strings.ToLower(node.NodeUUID)
		if !validCloudNodeUUID(node.NodeUUID) || !validCloudCertificateFingerprint(node.CertFingerprint) {
			return nil, fmt.Errorf("authorized cloud node identity is invalid")
		}
		if _, duplicate := allowed[nodeUUID]; duplicate {
			return nil, fmt.Errorf("duplicate authorized cloud node identity")
		}
		allowed[nodeUUID] = strings.ToLower(node.CertFingerprint)
	}
	return allowed, nil
}

func validCloudNodeUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for index, character := range value {
		if index == 8 || index == 13 || index == 18 || index == 23 {
			if character != '-' {
				return false
			}
			continue
		}
		if !(character >= '0' && character <= '9') && !(character >= 'a' && character <= 'f') && !(character >= 'A' && character <= 'F') {
			return false
		}
	}
	return true
}

func validCloudCertificateFingerprint(value string) bool {
	if len(value) != len("sha256:")+sha256.Size*2 || !strings.EqualFold(value[:len("sha256:")], "sha256:") {
		return false
	}
	_, err := hex.DecodeString(value[len("sha256:"):])
	return err == nil
}

func cloudCertificateFingerprint(der []byte) string {
	digest := sha256.Sum256(der)
	return "sha256:" + hex.EncodeToString(digest[:])
}

type gatewayConfigureParams struct {
	Config                        json.RawMessage                 `json:"config"`
	CloudEnabled                  bool                            `json:"cloudEnabled"`
	Policy                        gatewayPolicy                   `json:"policy"`
	FallbackCloudModel            string                          `json:"fallbackCloudModel"`
	AllowPaidFallback             bool                            `json:"allowPaidFallback"`
	MonthlyBudgetUSD              float64                         `json:"monthlyBudgetUSD"`
	PerRequestMaxEstimatedCostUSD float64                         `json:"perRequestMaxEstimatedCostUSD"`
	AuthorizedNodes               []gatewayCloudNodeAuthorization `json:"authorizedNodes"`
}

type gatewayCloudNodeAuthorization struct {
	NodeUUID        string `json:"nodeUuid"`
	CertFingerprint string `json:"certFingerprint"`
}

func (d *gatewayDispatcher) configure(params gatewayConfigureParams) error {
	d.cloudControlMu.Lock()
	defer d.cloudControlMu.Unlock()
	if len(params.Config) == 0 {
		return fmt.Errorf("cloud provider config is required")
	}
	if params.Policy == "" {
		params.Policy = gatewayPolicyLocalOnly
	}
	switch params.Policy {
	case gatewayPolicyLocalOnly, gatewayPolicyCloudOnly, gatewayPolicyPreferLocal:
	default:
		return fmt.Errorf("unsupported cloud routing policy")
	}
	if params.FallbackCloudModel != "" && !validCloudPublicID(params.FallbackCloudModel) {
		return fmt.Errorf("fallback cloud model ID is invalid")
	}
	if params.MonthlyBudgetUSD < 0 || params.PerRequestMaxEstimatedCostUSD < 0 {
		return fmt.Errorf("cloud budget limits cannot be negative")
	}
	authorizedNodes, err := parseAuthorizedCloudNodes(params.AuthorizedNodes)
	if err != nil {
		return err
	}
	snapshot, err := parseCloudConfig(params.Config, cloudConfigOptions{})
	if err != nil {
		return err
	}
	d.registry.snapshot.Store(snapshot)
	d.pruneProviderCredentials(snapshot)
	d.settings.Store(&gatewayRoutingSettings{
		policy: params.Policy, cloudEnabled: params.CloudEnabled,
		fallbackCloudModel:            params.FallbackCloudModel,
		allowPaidFallback:             params.AllowPaidFallback,
		monthlyBudgetUSD:              params.MonthlyBudgetUSD,
		perRequestMaxEstimatedCostUSD: params.PerRequestMaxEstimatedCostUSD,
		authorizedCloudNodes:          authorizedNodes,
	})
	return nil
}

func (d *gatewayDispatcher) testProvider(providerID string) error {
	provider, ok := d.registry.Snapshot().providerByID(providerID)
	if !ok {
		return fmt.Errorf("provider is not configured")
	}
	d.credentialMu.RLock()
	resolver := d.resolveCredential
	d.credentialMu.RUnlock()
	if resolver == nil {
		return fmt.Errorf("provider credential is not configured")
	}
	credential, err := resolver(provider.AuthRef)
	if err != nil || credential == "" {
		return fmt.Errorf("provider credential is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	return d.client.TestProvider(ctx, provider, credential)
}

type gatewayCredentialParams struct {
	AuthRef    string `json:"authRef"`
	Credential string `json:"credential"`
}

func (p *Proxy) setGatewayCredential(params gatewayCredentialParams) error {
	p.gatewayDispatcher.cloudControlMu.Lock()
	defer p.gatewayDispatcher.cloudControlMu.Unlock()
	if params.AuthRef == "" {
		return fmt.Errorf("authRef is required")
	}
	if len(params.Credential) > 16<<10 || strings.ContainsAny(params.Credential, "\r\n") {
		return fmt.Errorf("provider credential is invalid")
	}
	if !p.gatewayDispatcher.registry.Snapshot().providerByAuthRef(params.AuthRef) {
		return fmt.Errorf("credential reference is not configured")
	}
	p.gatewayDispatcher.setProviderCredential(params.AuthRef, params.Credential)
	return nil
}

type gatewayTestProviderParams struct {
	ProviderID string `json:"providerId"`
}
