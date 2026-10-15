/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.ui.state

import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.MnnEngineState
import com.nv.pair.mnn.MnnErrorCode
import com.nv.pair.runtime.MnnLocalEngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalInferenceProjectionTest {
    @Test
    fun healthyUnloadedRuntimeProjectsToIdleWithoutModel() {
        val result = projectLocalInference(
            MnnLocalEngineStatus(available = true, backend = MnnBackend.CPU, engineState = MnnEngineState.UNLOADED),
            runtimeRunning = true,
        )

        assertEquals("Idle", result.activityLabel)
        assertEquals("No model loaded", result.modelLabel)
        assertNull(result.errorLabel)
    }

    @Test
    fun loadedModelIsReadyButNotGenerating() {
        val result = projectLocalInference(
            MnnLocalEngineStatus(
                available = true,
                backend = MnnBackend.OPENCL,
                engineState = MnnEngineState.READY,
                modelId = "qwen-local",
            ),
            runtimeRunning = true,
        )

        assertEquals("Ready", result.activityLabel)
        assertEquals("Loaded model · qwen-local", result.modelLabel)
    }

    @Test
    fun loadingModelRemainsDistinctFromReady() {
        val result = projectLocalInference(
            MnnLocalEngineStatus(
                available = true,
                backend = MnnBackend.CPU,
                engineState = MnnEngineState.LOADING,
                modelId = "qwen-local",
            ),
            runtimeRunning = true,
        )

        assertEquals("Loading", result.activityLabel)
        assertEquals("Loading model", result.modelLabel)
    }

    @Test
    fun generatingModelIsReportedAsActiveLocalInference() {
        val result = projectLocalInference(
            MnnLocalEngineStatus(
                available = true,
                backend = MnnBackend.CPU,
                engineState = MnnEngineState.GENERATING,
                modelId = "qwen-local",
            ),
            runtimeRunning = true,
        )

        assertEquals("Generating", result.activityLabel)
        assertEquals("Loaded model · qwen-local", result.modelLabel)
    }

    @Test
    fun stoppedAndUnavailableRuntimeDoNotClaimLocalModelReadiness() {
        val stopped = projectLocalInference(
            MnnLocalEngineStatus(available = true, engineState = MnnEngineState.READY, modelId = "qwen-local"),
            runtimeRunning = false,
        )
        val unavailable = projectLocalInference(
            MnnLocalEngineStatus(available = false, errorCode = MnnErrorCode.INTERNAL_ERROR),
            runtimeRunning = true,
        )

        assertEquals("Stopped", stopped.activityLabel)
        assertEquals("Model status unavailable", stopped.modelLabel)
        assertEquals("Unavailable", unavailable.activityLabel)
        assertEquals("Model status unavailable", unavailable.modelLabel)
    }

    @Test
    fun modelLoadFailureRemainsAnEngineError() {
        val result = projectLocalInference(
            MnnLocalEngineStatus(
                available = true,
                backend = MnnBackend.CPU,
                engineState = MnnEngineState.ERROR,
                errorCode = MnnErrorCode.MODEL_LOAD_FAILED,
            ),
            runtimeRunning = true,
        )

        assertEquals("Error", result.activityLabel)
        assertEquals("Model could not be loaded (MODEL_LOAD_FAILED). See diagnostics.", result.errorLabel)
    }
}
