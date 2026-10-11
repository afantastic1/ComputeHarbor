// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// @vitest-environment jsdom

import { act, createElement } from 'react'
import { createRoot } from 'react-dom/client'
import { renderToStaticMarkup } from 'react-dom/server'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CloudProvidersSettingsCard from '@/ui/components/ServiceSettings/CloudProvidersSettings'

describe('Cloud providers settings section', () => {
    afterEach(() => vi.unstubAllGlobals())

    it('starts collapsed in a card with a clickable summary', () => {
        const markup = renderToStaticMarkup(createElement(CloudProvidersSettingsCard))

        expect(markup).toContain('<details class="pair-paper w-full p-4 cloud-providers-card"')
        expect(markup).not.toMatch(/<details[^>]*open/)
        expect(markup).toContain(
            '<summary class="pair-accordion-summary cloud-providers-card-summary">'
        )
        expect(markup).toContain('Cloud providers')
        expect(markup).toContain('Cloud routing is off by default')
    })

    it('explains that automatic Cloud routing uses one configured fallback model', () => {
        const markup = renderToStaticMarkup(createElement(CloudProvidersSettingsCard))

        expect(markup).toContain('Paid fallback model')
        expect(markup).toContain('Only this selected model is used for automatic Cloud routing')
    })

    it('keeps a deleted fallback selection visible as unavailable instead of choosing another model', async () => {
        vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true)
        vi.stubGlobal('window', {
            pairApi: {
                cloudProviders: {
                    getSettings: vi.fn().mockResolvedValue({
                        schema_version: 1,
                        config: { schema_version: 1, providers: [] },
                        cloudEnabled: true,
                        policy: 'prefer_local',
                        policyMigrationRequired: false,
                        fallbackCloudModel: 'cloud/deleted/model',
                        allowPaidFallback: true,
                        monthlyBudgetUSD: 10,
                        perRequestMaxEstimatedCostUSD: 1,
                        authorizedNodes: []
                    }),
                    onSettingsChanged: () => () => {}
                },
                cluster: {
                    getInitial: vi.fn().mockResolvedValue({
                        members: [],
                        identity: { nodeUuid: 'self' }
                    })
                },
                nodes: { onMembersChanged: () => () => {} }
            }
        })
        const container = document.createElement('div')
        const root = createRoot(container)
        try {
            await act(async () => {
                root.render(createElement(CloudProvidersSettingsCard))
                await Promise.resolve()
            })

            expect(container.textContent).toContain('Unavailable: cloud/deleted/model')
            expect(container.textContent).toContain('selected fallback model is unavailable')
        } finally {
            await act(async () => root.unmount())
        }
    })
})
