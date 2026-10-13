// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const stylesRoot = resolve(__dirname, '..', '..', 'src', 'ui', 'styles')
const themePath = resolve(stylesRoot, 'computeharbor-theme.css')

function readTheme(): string {
    return existsSync(themePath) ? readFileSync(themePath, 'utf8') : ''
}

describe('ComputeHarbor theme contract', () => {
    it('defines the approved semantic palette after the vendor styles', () => {
        const indexCss = readFileSync(resolve(stylesRoot, 'index.css'), 'utf8')
        const themeCss = readTheme()

        expect(
            indexCss.indexOf("@import '../lib/kaizen-ui-foundations/components.css';")
        ).toBeLessThan(indexCss.indexOf("@import './computeharbor-theme.css';"))
        expect(themeCss).toContain('--ch-harbor-cyan: #25d0b1')
        expect(themeCss).toContain('--ch-deep-sea: #07131f')
        expect(themeCss).toContain('--ch-harbor-blue: #10283a')
        expect(themeCss).toContain('--ch-compute-blue: #4d9fff')
        expect(themeCss).toContain('--ch-beacon-orange: #f2a65a')
        expect(themeCss).toContain('--ch-failure-red: #f16d78')
        expect(themeCss).toContain('--ch-mist-white: #eaf2f7')
        expect(themeCss).toContain('--ch-sea-fog: #91a7b5')
        expect(themeCss).toContain('--ch-border: #234052')
        expect(indexCss).not.toMatch(/#76b900|#a5de15/i)
    })

    it('keeps keyboard focus visible', () => {
        const themeCss = readTheme()
        expect(themeCss).toContain(':focus-visible')
        expect(themeCss).toContain('outline: 2px solid var(--ch-harbor-cyan)')
    })

    it('stops continuous and entrance motion when reduced motion is requested', () => {
        const themeCss = readTheme()
        expect(themeCss).toContain('@media (prefers-reduced-motion: reduce)')
        expect(themeCss).toContain('.spinner-element')
        expect(themeCss).toContain('.pulsing')
        expect(themeCss).toContain('.workload-item-card')
        expect(themeCss).toContain('.ring-item')
        expect(themeCss).toContain('animation: none !important')
    })
})
