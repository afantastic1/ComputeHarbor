// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import type { Workload } from '@/shared/types/workloads'
import { GPU_COLOR_PALETTE, WORKLOAD_COLOR_MAP } from '@/ui/constants/colors'
import WorkloadItemCard from '@/ui/components/Workloads/WorkloadItemCard'
import { workloadNodeLabel } from '@/ui/utils/workload-labels'

const STARTED_AT = 1_700_000_000_000

describe('workloadNodeLabel', () => {
    it('says Sent to for an in-flight job', () => {
        expect(workloadNodeLabel({ state: 'queued', startedAt: null })).toBe('Sent to')
    })

    it('says Running on for a running job', () => {
        expect(workloadNodeLabel({ state: 'running', startedAt: STARTED_AT })).toBe('Running on')
    })

    it('says Ran on for a completed job', () => {
        expect(workloadNodeLabel({ state: 'completed', startedAt: STARTED_AT })).toBe('Ran on')
    })

    // The proxy keeps scheduledOn on the terminal event, so a job that ends
    // before it starts still names the node it was sent to.
    it('says Sent to for a job that failed before it started', () => {
        expect(workloadNodeLabel({ state: 'failed', startedAt: null })).toBe('Sent to')
    })

    it('says Sent to for a job that was cancelled before it started', () => {
        expect(workloadNodeLabel({ state: 'cancelled', startedAt: null })).toBe('Sent to')
    })

    it('says Ran on for a job that failed after it started', () => {
        expect(workloadNodeLabel({ state: 'failed', startedAt: STARTED_AT })).toBe('Ran on')
    })

    it('says Ran on for a job that was cancelled after it started', () => {
        expect(workloadNodeLabel({ state: 'cancelled', startedAt: STARTED_AT })).toBe('Ran on')
    })
})

describe('WORKLOAD_COLOR_MAP', () => {
    // Connection lines append a two-digit alpha to these values.
    it('holds only six-digit hex colors', () => {
        for (const [name, color] of Object.entries(WORKLOAD_COLOR_MAP)) {
            expect(color, name).toMatch(/^#[0-9a-f]{6}$/i)
        }
    })

    it('maps operational states to the approved harbor palette', () => {
        expect(WORKLOAD_COLOR_MAP.yellow).toBe('#F2A65A')
        expect(WORKLOAD_COLOR_MAP.blue).toBe('#4D9FFF')
        expect(WORKLOAD_COLOR_MAP.green).toBe('#25D0B1')
        expect(WORKLOAD_COLOR_MAP.red).toBe('#F16D78')
        expect(GPU_COLOR_PALETTE).toContain('#4D9FFF')
    })
})

describe('Cloud workload card', () => {
    it('shows the public model, provider, reported usage, and estimated cost without an engine', () => {
        const workload: Workload = {
            id: 'cloud-job-1',
            model: 'auto',
            kind: 'cloud',
            providerId: 'deepseek',
            publicModelId: 'cloud/deepseek/chat',
            usage: { inputTokens: 10, outputTokens: 4 },
            costEstimate: 0.0002,
            state: 'completed',
            originatedFrom: 'requester-node',
            createdAt: STARTED_AT,
            startedAt: STARTED_AT,
            completedAt: STARTED_AT,
            error: null,
            requesterId: 'requester-node'
        }

        const markup = renderToStaticMarkup(createElement(WorkloadItemCard, { workload }))

        expect(markup).toContain('cloud/deepseek/chat')
        expect(markup).toContain('Cloud · deepseek')
        expect(markup).toContain('10 input · 4 output tokens')
        expect(markup).toContain('$0.0002')
    })

    it('does not show usage or estimated cost when the provider did not report them', () => {
        const workload: Workload = {
            id: 'cloud-job-2',
            model: 'auto',
            kind: 'cloud',
            providerId: 'deepseek',
            publicModelId: 'cloud/deepseek/chat',
            state: 'completed',
            originatedFrom: null,
            createdAt: STARTED_AT,
            startedAt: STARTED_AT,
            completedAt: STARTED_AT,
            error: null,
            requesterId: null
        }

        const markup = renderToStaticMarkup(createElement(WorkloadItemCard, { workload }))

        expect(markup).not.toContain('tokens')
        expect(markup).not.toContain('Estimated cost')
        expect(markup).not.toContain('$0')
    })
})

describe('Android MNN workload card', () => {
    it('identifies the reported engine without inventing a missing execution node', () => {
        const workload: Workload = {
            id: 'mnn-job-1',
            model: 'qwen3-1.7b',
            engine: 'mnn',
            state: 'completed',
            originatedFrom: 'android-node',
            scheduledOn: 'unreported-node',
            createdAt: STARTED_AT,
            startedAt: STARTED_AT,
            completedAt: STARTED_AT,
            error: null,
            requesterId: null
        }

        const markup = renderToStaticMarkup(createElement(WorkloadItemCard, { workload }))

        expect(markup).toContain('MNN engine')
        expect(markup).toContain('qwen3-1.7b')
        expect(markup).not.toContain('unreported-node')
    })
})
