// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type { BadgeColor } from '@/ui/types/types'

const HARBOR_CYAN = '#25D0B1'
const COMPUTE_BLUE = '#4D9FFF'
const BEACON_ORANGE = '#F2A65A'
const FAILURE_RED = '#F16D78'

// Color mapping for CSS. Values must stay six-digit hex: connection lines
// append a two-digit alpha.
export const WORKLOAD_COLOR_MAP: Record<BadgeColor, `#${string}`> = {
    yellow: BEACON_ORANGE,
    blue: COMPUTE_BLUE,
    green: HARBOR_CYAN,
    gray: '#91A7B5',
    red: FAILURE_RED,
    teal: HARBOR_CYAN,
    purple: '#7D8FFF'
} as const

/**
 * Chart color palette for metrics visualization
 */
export const CHART_COLORS = {
    CPU: COMPUTE_BLUE,
    MEMORY: HARBOR_CYAN
} as const

/**
 * Color palettes for cycling through multiple GPUs
 */
export const GPU_COLOR_PALETTE = [COMPUTE_BLUE, '#75B8FF', '#2E7FD2', '#86A7FF'] as const

export const VRAM_COLOR_PALETTE = [HARBOR_CYAN, '#67DBC6', '#159A85', '#56B6C8'] as const
