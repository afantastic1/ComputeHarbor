// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
    APP_DATA_DIR_NAME,
    APP_DISPLAY_NAME,
    APP_EXECUTABLE_NAME,
    APP_ID,
    APP_ORG,
    APP_PREVIOUS_ORG
} from '@/shared/constants/app'

const desktopRoot = resolve(__dirname, '..', '..')

describe('ComputeHarbor product identity', () => {
    it('uses ComputeHarbor for visible product identity', () => {
        expect(APP_DISPLAY_NAME).toBe('ComputeHarbor')
    })

    it('keeps persisted identity compatibility', () => {
        expect(APP_DATA_DIR_NAME).toBe('Personal AI Router')
        expect(APP_EXECUTABLE_NAME).toBe('PAIR')
        expect(APP_ID).toBe('com.nvidia.nvpair')
        expect(APP_ORG).toBe('Nvidia Corporation')
        expect(APP_PREVIOUS_ORG).toBe('NVIDIA Corporation')
    })

    it('keeps renderer metadata aligned', () => {
        const html = readFileSync(resolve(desktopRoot, 'src', 'ui', 'index.html'), 'utf8')
        const manifestText = readFileSync(
            resolve(desktopRoot, 'src', 'ui', 'public', 'manifest.webmanifest'),
            'utf8'
        )
        const manifest: { name?: string; short_name?: string; description?: string } =
            JSON.parse(manifestText)

        expect(html).toContain('<title>ComputeHarbor</title>')
        expect(html).toContain('content="ComputeHarbor"')
        expect(manifest.name).toBe('ComputeHarbor')
        expect(manifest.short_name).toBe('ComputeHarbor')
        expect(manifest.description).toBe(
            'Coordinate local AI engines and distributed inference from one harbor.'
        )
    })

    it('uses the independent product name in package metadata', () => {
        const packageText = readFileSync(resolve(desktopRoot, 'package.json'), 'utf8')
        const packageMetadata: { description?: string } = JSON.parse(packageText)
        expect(packageMetadata.description).toBe('ComputeHarbor distributed AI control plane')
    })
})
