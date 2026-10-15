/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.ui.state

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkloadUiProjectionTest {
    @Test
    fun mapsKnownWorkloadStatesWithoutHidingUnknownValues() {
        assertEquals("Queued", projectWorkloadStatus("pending"))
        assertEquals("Running", projectWorkloadStatus("RUNNING"))
        assertEquals("Completed", projectWorkloadStatus("succeeded"))
        assertEquals("Failed", projectWorkloadStatus("error"))
        assertEquals("Cancelled", projectWorkloadStatus("canceled"))
        assertEquals("Unknown (paused_by_policy)", projectWorkloadStatus("paused_by_policy"))
    }
}
