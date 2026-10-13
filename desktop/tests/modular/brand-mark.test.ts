// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { BrandMark } from '@/ui/components/BrandMark'

describe('BrandMark', () => {
    it('exposes its title when the mark carries meaning', () => {
        const markup = renderToStaticMarkup(
            createElement(BrandMark, { size: 20, title: 'ComputeHarbor' })
        )

        expect(markup).toMatch(/<title[^>]*>ComputeHarbor<\/title>/)
        expect(markup).toContain('role="img"')
        expect(markup).toContain('currentColor')
        expect(markup).not.toMatch(/nvidia/i)
    })

    it('is decorative when the product name is adjacent', () => {
        const markup = renderToStaticMarkup(createElement(BrandMark, { size: 16 }))

        expect(markup).toContain('aria-hidden="true"')
        expect(markup).not.toContain('<title>')
        expect(markup).toContain('width="16"')
        expect(markup).toContain('height="16"')
    })
})
