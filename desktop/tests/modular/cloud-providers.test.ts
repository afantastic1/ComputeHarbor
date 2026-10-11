// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import {
    cloudProvidersSettingsParams,
    parseCloudProvidersSettings
} from '@/electron/service-bridge/cloud-providers'

describe('cloud provider settings bridge', () => {
    it('parses public settings without carrying extra credential fields', () => {
        const settings = parseCloudProvidersSettings({
            schema_version: 1,
            config: {
                schema_version: 1,
                providers: [
                    {
                        id: 'provider-a',
                        protocol: 'openai_chat_completions',
                        base_url: 'https://api.example.com',
                        auth_ref: 'user-vault:provider-a',
                        enabled: true,
                        api_key: 'must-not-leak',
                        models: [
                            {
                                public_id: 'cloud/provider-a/chat',
                                upstream_id: 'chat-model',
                                capabilities: ['chat', 'streaming']
                            }
                        ]
                    }
                ]
            },
            cloudEnabled: true,
            policy: 'cloud_only',
            allowPaidFallback: false,
            monthlyBudgetUSD: 25,
            perRequestMaxEstimatedCostUSD: 2,
            authorizedNodes: []
        })

        expect(settings.config.providers[0].auth_ref).toBe('user-vault:provider-a')
        expect(Object.hasOwn(settings.config.providers[0], 'api_key')).toBe(false)
    })

    it('rejects a settings response with an unknown policy', () => {
        expect(() =>
            parseCloudProvidersSettings({
                schema_version: 1,
                config: { schema_version: 1, providers: [] },
                cloudEnabled: false,
                policy: 'automatic-paid-fallback',
                allowPaidFallback: false,
                monthlyBudgetUSD: 0,
                perRequestMaxEstimatedCostUSD: 0,
                authorizedNodes: []
            })
        ).toThrow('invalid response')
    })

    it('preserves explicit paired-node cloud authorizations through the broker payload', () => {
        const settings = parseCloudProvidersSettings({
            schema_version: 1,
            config: { schema_version: 1, providers: [] },
            cloudEnabled: true,
            policy: 'prefer_local',
            fallbackCloudModel: 'cloud/provider-a/chat',
            allowPaidFallback: false,
            monthlyBudgetUSD: 10,
            perRequestMaxEstimatedCostUSD: 1,
            authorizedNodes: [
                {
                    nodeUuid: '10000000-0000-0000-0000-000000000001',
                    certFingerprint: `sha256:${'a'.repeat(64)}`
                }
            ]
        })

        expect(cloudProvidersSettingsParams(settings).authorizedNodes).toEqual(
            settings.authorizedNodes
        )
        expect(cloudProvidersSettingsParams(settings).fallbackCloudModel).toBe(
            'cloud/provider-a/chat'
        )
    })

    it('safely migrates legacy prefer-cloud settings and missing fallback model', () => {
        const settings = parseCloudProvidersSettings({
            schema_version: 1,
            config: { schema_version: 1, providers: [] },
            cloudEnabled: true,
            policy: 'prefer_cloud',
            allowPaidFallback: true,
            monthlyBudgetUSD: 10,
            perRequestMaxEstimatedCostUSD: 1,
            authorizedNodes: []
        })

        expect(settings.policy).toBe('local_only')
        expect(settings.policyMigrationRequired).toBe(true)
        expect(settings.fallbackCloudModel).toBe('')
    })
})
