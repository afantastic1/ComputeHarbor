// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export interface StrataSettings {
    enabled: boolean
    endpoint: string
    modelId: string
    apiKeyEnv: string
}

export type StrataConnectionError =
    | 'invalid_endpoint'
    | 'not_running'
    | 'authentication_failed'
    | 'model_not_recognized'
    | 'protocol_incompatible'

export interface StrataProbeResult {
    connected: boolean
    errorCode?: StrataConnectionError
    modelIds: string[]
}

export interface StrataSettingsResult {
    settings: StrataSettings
    probe: StrataProbeResult
}
