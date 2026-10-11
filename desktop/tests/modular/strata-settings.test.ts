// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseStrataProbeResult, parseStrataSettingsResult, strataSettingsParams } from '@/electron/service-bridge/strata'
import type { StrataSettings } from '@/shared/types/strata'

describe('Strata settings bridge', () => {
    it('parses loopback connection settings and discovered model IDs', () => {
        expect(
            parseStrataSettingsResult({
                settings: { enabled: true, endpoint: 'http://127.0.0.1:8080', modelId: 'qwen-local' },
                probe: { connected: true, modelIds: ['qwen-local'] }
            })
        ).toEqual({
            settings: {
                enabled: true,
                endpoint: 'http://127.0.0.1:8080',
                modelId: 'qwen-local',
                apiKeyEnv: ''
            },
            probe: { connected: true, modelIds: ['qwen-local'] }
        })
    })

    it('keeps failure categories typed', () => {
        expect(
            parseStrataProbeResult({ connected: false, errorCode: 'authentication_failed', modelIds: [] })
        ).toEqual({ connected: false, errorCode: 'authentication_failed', modelIds: [] })
        expect(() =>
            parseStrataProbeResult({ connected: false, errorCode: 'unknown', modelIds: [] })
        ).toThrow('invalid connection test response')
    })

    it('sends only the environment variable reference in settings', () => {
        const settings: StrataSettings = {
            enabled: true,
            endpoint: 'http://localhost:8080',
            modelId: '',
            apiKeyEnv: 'STRATA_API_KEY'
        }
        expect(strataSettingsParams(settings)).toEqual(settings)
    })
})
