// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { existsSync, readFileSync, statSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const desktopRoot = resolve(__dirname, '..', '..')
const sourceRoot = resolve(desktopRoot, 'resources', 'app-icon')
const outputRoot = resolve(desktopRoot, 'resources', 'icons')

function pngDimensions(path: string): { width: number; height: number } {
    const bytes = readFileSync(path)
    expect(bytes.subarray(0, 8)).toEqual(
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
    )
    return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) }
}

describe('ComputeHarbor application icons', () => {
    it('keeps an original self-contained vector source', () => {
        const sourcePath = resolve(sourceRoot, 'computeharbor-mark.svg')
        expect(existsSync(sourcePath)).toBe(true)
        const source = existsSync(sourcePath) ? readFileSync(sourcePath, 'utf8') : ''

        expect(source.toLowerCase()).toContain('#25d0b1')
        expect(source.toLowerCase()).toContain('#07131f')
        expect(source).not.toMatch(/<image|href=/i)
    })

    it('provides every required square source size', () => {
        for (const size of [16, 24, 32, 48, 64, 128, 256, 512, 1024]) {
            expect(pngDimensions(resolve(sourceRoot, `pair-${size}.png`))).toEqual({
                width: size,
                height: size
            })
        }
    })

    it('generates renderer and packaged outputs', () => {
        expect(pngDimensions(resolve(desktopRoot, 'src', 'ui', 'public', 'favicon.png'))).toEqual({
            width: 256,
            height: 256
        })
        expect(statSync(resolve(sourceRoot, 'pair.ico')).size).toBeGreaterThan(100)
        expect(statSync(resolve(outputRoot, 'logo.ico')).size).toBeGreaterThan(100)
        expect(statSync(resolve(outputRoot, 'logo.icns')).size).toBeGreaterThan(100)
    })
})
