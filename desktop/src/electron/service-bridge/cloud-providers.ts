// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type {
    CloudProvidersSettings,
    CloudProviderConfig,
    CloudModelConfig
} from '@/shared/types/cloud-providers'
import type { JsonObject, JsonValue } from './json-rpc-subprocess'

function objectValue(value: JsonValue | undefined): JsonObject | null {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null
    return value
}

function stringValue(value: JsonValue | undefined): string {
    return typeof value === 'string' ? value : ''
}

function numberValue(value: JsonValue | undefined): number {
    return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

function booleanValue(value: JsonValue | undefined): boolean {
    return typeof value === 'boolean' ? value : false
}

function isGatewayPolicy(value: string): value is CloudProvidersSettings['policy'] {
    return value === 'local_only' || value === 'cloud_only' || value === 'prefer_local'
}

function isFallbackCloudModel(value: string): boolean {
    return /^cloud(?:\/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}){2,}$/.test(value)
}

function parseProvider(value: JsonValue): CloudProviderConfig | null {
    const provider = objectValue(value)
    const id = stringValue(provider?.id)
    const baseUrl = stringValue(provider?.base_url)
    const authRef = stringValue(provider?.auth_ref)
    const modelsValue = provider?.models
    if (
        !provider ||
        !id ||
        !baseUrl ||
        !authRef ||
        provider.protocol !== 'openai_chat_completions' ||
        !Array.isArray(modelsValue)
    ) {
        return null
    }
    const models: CloudModelConfig[] = []
    for (const value of modelsValue) {
        const model = objectValue(value)
        const publicId = stringValue(model?.public_id)
        const upstreamId = stringValue(model?.upstream_id)
        const capabilities = model?.capabilities
        if (!model || !publicId || !upstreamId || !Array.isArray(capabilities)) {
            return null
        }
        const parsedCapabilities: string[] = []
        for (const capability of capabilities) {
            if (typeof capability !== 'string') return null
            parsedCapabilities.push(capability)
        }
        models.push({
            public_id: publicId,
            upstream_id: upstreamId,
            capabilities: parsedCapabilities
        })
    }
    return {
        id,
        protocol: 'openai_chat_completions',
        base_url: baseUrl,
        auth_ref: authRef,
        enabled: booleanValue(provider.enabled),
        models
    }
}

export function parseCloudProvidersSettings(value: JsonValue | undefined): CloudProvidersSettings {
    const settings = objectValue(value)
    const config = objectValue(settings?.config)
    const providersValue = config?.providers
    const storedPolicy = stringValue(settings?.policy)
    const policyMigrationRequired =
        booleanValue(settings?.policyMigrationRequired) || storedPolicy === 'prefer_cloud'
    const policy = storedPolicy === 'prefer_cloud' ? 'local_only' : storedPolicy
    const fallbackCloudModel = stringValue(settings?.fallbackCloudModel)
    const authorizedNodesValue = settings?.authorizedNodes
    if (
        settings?.schema_version !== 1 ||
        config?.schema_version !== 1 ||
        !Array.isArray(providersValue) ||
        (authorizedNodesValue !== undefined && !Array.isArray(authorizedNodesValue)) ||
        !isGatewayPolicy(policy) ||
        (fallbackCloudModel !== '' && !isFallbackCloudModel(fallbackCloudModel))
    ) {
        throw new Error('Cloud provider settings returned an invalid response')
    }
    const providers: CloudProviderConfig[] = []
    for (const value of providersValue) {
        const provider = parseProvider(value)
        if (!provider) throw new Error('Cloud provider settings returned an invalid response')
        providers.push(provider)
    }
    const authorizedNodes: CloudProvidersSettings['authorizedNodes'] = []
    if (Array.isArray(authorizedNodesValue)) {
        for (const value of authorizedNodesValue) {
            const authorization = objectValue(value)
            const nodeUuid = stringValue(authorization?.nodeUuid)
            const certFingerprint = stringValue(authorization?.certFingerprint)
            if (!nodeUuid || !/^sha256:[0-9a-f]{64}$/i.test(certFingerprint)) {
                throw new Error('Cloud provider settings returned an invalid response')
            }
            authorizedNodes.push({ nodeUuid, certFingerprint })
        }
    }
    return {
        schema_version: 1,
        config: { schema_version: 1, providers },
        cloudEnabled: booleanValue(settings.cloudEnabled),
        policy,
        policyMigrationRequired,
        fallbackCloudModel,
        allowPaidFallback: booleanValue(settings.allowPaidFallback),
        monthlyBudgetUSD: numberValue(settings.monthlyBudgetUSD),
        perRequestMaxEstimatedCostUSD: numberValue(settings.perRequestMaxEstimatedCostUSD),
        authorizedNodes
    }
}

export function cloudProvidersSettingsParams(settings: CloudProvidersSettings): JsonObject {
    const providers: JsonValue[] = settings.config.providers.map(provider => {
        const models: JsonValue[] = provider.models.map(model => ({
            public_id: model.public_id,
            upstream_id: model.upstream_id,
            capabilities: model.capabilities
        }))
        return {
            id: provider.id,
            protocol: provider.protocol,
            base_url: provider.base_url,
            auth_ref: provider.auth_ref,
            enabled: provider.enabled,
            models
        }
    })
    const config: JsonObject = { schema_version: 1, providers }
    return {
        schema_version: 1,
        config,
        cloudEnabled: settings.cloudEnabled,
        policy: settings.policy,
        policyMigrationRequired: settings.policyMigrationRequired,
        fallbackCloudModel: settings.fallbackCloudModel,
        allowPaidFallback: settings.allowPaidFallback,
        monthlyBudgetUSD: settings.monthlyBudgetUSD,
        perRequestMaxEstimatedCostUSD: settings.perRequestMaxEstimatedCostUSD,
        authorizedNodes: settings.authorizedNodes.map(({ nodeUuid, certFingerprint }) => ({
            nodeUuid,
            certFingerprint
        }))
    }
}
