// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import CloudProvidersSettingsCard from '@/ui/components/ServiceSettings/CloudProvidersSettings'

describe('Cloud providers settings section', () => {
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
})
