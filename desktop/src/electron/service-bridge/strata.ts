// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type { StrataSettings, StrataSettingsResult } from '@/shared/types/strata'
import type { JsonObject, JsonValue } from './json-rpc-subprocess'

function objectValue(value: JsonValue | undefined): JsonObject | null {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null
    return value
}

function stringValue(value: JsonValue | undefined): string {
    return typeof value === 'string' ? value : ''
}

function isStrataConnectionError(value: string): value is NonNullable<StrataSettingsResult['probe']['errorCode']> {
    return (
        value === 'invalid_endpoint' ||
        value === 'not_running' ||
        value === 'authentication_failed' ||
        value === 'model_not_recognized' ||
        value === 'protocol_incompatible'
    )
}

function parseSettings(value: JsonValue | undefined): StrataSettings | null {
    const settings = objectValue(value)
    if (!settings || typeof settings.enabled !== 'boolean' || !stringValue(settings.endpoint)) {
        return null
    }
    return {
        enabled: settings.enabled,
        endpoint: stringValue(settings.endpoint),
        modelId: stringValue(settings.modelId),
        apiKeyEnv: stringValue(settings.apiKeyEnv)
    }
}

function parseProbe(value: JsonValue | undefined): StrataSettingsResult['probe'] | null {
    const probe = objectValue(value)
    if (!probe || typeof probe.connected !== 'boolean' || !Array.isArray(probe.modelIds)) {
        return null
    }
    const modelIds: string[] = []
    for (const modelId of probe.modelIds) {
        if (typeof modelId !== 'string') return null
        modelIds.push(modelId)
    }
    const errorCode = stringValue(probe.errorCode)
    if (errorCode && !isStrataConnectionError(errorCode)) {
        return null
    }
    return {
        connected: probe.connected,
        ...(isStrataConnectionError(errorCode) ? { errorCode } : {}),
        modelIds
    }
}

export function parseStrataProbeResult(value: JsonValue | undefined): StrataSettingsResult['probe'] {
    const probe = parseProbe(value)
    if (!probe) throw new Error('Strata returned an invalid connection test response')
    return probe
}

export function parseStrataSettingsResult(value: JsonValue | undefined): StrataSettingsResult {
    const result = objectValue(value)
    const settings = parseSettings(result?.settings)
    const probe = parseProbe(result?.probe)
    if (!settings || !probe) throw new Error('Strata returned an invalid settings response')
    return { settings, probe }
}

export function strataSettingsParams(settings: StrataSettings): JsonObject {
    return {
        enabled: settings.enabled,
        endpoint: settings.endpoint,
        modelId: settings.modelId,
        apiKeyEnv: settings.apiKeyEnv
    }
}
