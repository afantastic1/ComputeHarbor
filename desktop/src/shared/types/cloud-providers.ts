// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export interface CloudModelConfig {
    public_id: string
    upstream_id: string
    capabilities: string[]
}

export interface CloudProviderConfig {
    id: string
    protocol: 'openai_chat_completions'
    base_url: string
    auth_ref: string
    enabled: boolean
    models: CloudModelConfig[]
}

export interface CloudNodeAuthorization {
    nodeUuid: string
    certFingerprint: string
}

export interface CloudProvidersSettings {
    schema_version: 1
    config: { schema_version: 1; providers: CloudProviderConfig[] }
    cloudEnabled: boolean
    policy: 'local_only' | 'cloud_only' | 'prefer_local'
    policyMigrationRequired: boolean
    fallbackCloudModel: string
    allowPaidFallback: boolean
    monthlyBudgetUSD: number
    perRequestMaxEstimatedCostUSD: number
    authorizedNodes: CloudNodeAuthorization[]
}
