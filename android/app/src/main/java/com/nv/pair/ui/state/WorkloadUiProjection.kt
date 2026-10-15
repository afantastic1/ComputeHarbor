/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.ui.state

internal fun projectWorkloadStatus(state: String): String = when (state.lowercase()) {
    "queued", "pending" -> "Queued"
    "running", "in_progress" -> "Running"
    "completed", "succeeded", "success" -> "Completed"
    "failed", "error" -> "Failed"
    "cancelled", "canceled" -> "Cancelled"
    else -> "Unknown ($state)"
}
