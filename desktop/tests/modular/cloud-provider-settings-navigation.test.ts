// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/ui/api/bootstrap', () => ({ isElectron: true }))

import ServiceSettings from '@/ui/components/ServiceSettings/ServiceSettings'

describe('Service settings', () => {
    it('does not show a Cloud providers entry', () => {
        const markup = renderToStaticMarkup(createElement(ServiceSettings))

        expect(markup).not.toContain('Cloud providers')
    })
})
