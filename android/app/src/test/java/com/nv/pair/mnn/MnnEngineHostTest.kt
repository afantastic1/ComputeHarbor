/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnEngineHostTest {
    private companion object {
        const val SYNTHETIC_NATIVE_ERROR_LOAD = 4
    }

    @Test
    fun nativeCloseFailureRemainsVisibleAndCanBeRetried() {
        val cleanup = FakeNativeCleanup(failUnloadAttempts = 1)
        val runtime = NativeMnn(42L, null, cleanup)

        val firstFailure = runCatching { runtime.close() }.exceptionOrNull()

        assertTrue("native close failure must be reported", firstFailure is IllegalStateException)
        assertEquals(MnnEngineState.ERROR, runtime.getStatus().state)
        assertEquals(0, cleanup.destroyCount.get())

        runtime.close()

        assertEquals(MnnEngineState.UNLOADED, runtime.getStatus().state)
        assertEquals(1, cleanup.destroyCount.get())
    }

    @Test
    fun closeCancelsActiveGeneration() {
        val runtime = FakeMnnRuntime(blockGeneration = true)
        val host = MnnEngineHost(runtime)
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        val generationFinished = CountDownLatch(1)
        Thread {
            host.generate(30, chatRequest()) { }
            generationFinished.countDown()
        }.start()
        assertTrue(runtime.generationStarted.await(2, TimeUnit.SECONDS))

        host.close()

        assertEquals(1, runtime.cancelCount.get())
        assertTrue(generationFinished.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun closeReturnsWithinBoundWhenRuntimeIgnoresCancel() {
        val runtime = FakeMnnRuntime(blockGeneration = true, ignoreCancellation = true)
        val warnings = mutableListOf<String>()
        val host = MnnEngineHost(runtime, shutdownTimeoutMillis = 50, logWarning = warnings::add)
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        Thread { host.generate(31, chatRequest()) { } }.start()
        assertTrue(runtime.generationStarted.await(2, TimeUnit.SECONDS))
        val startedAt = System.nanoTime()

        host.close()

        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("close took ${elapsedMillis}ms", elapsedMillis < 1_000)
        assertTrue(warnings.single().contains("continues in the background"))
        runtime.releaseGeneration.countDown()
    }

    @Test
    fun interruptedCloseRemainsUnavailableAndCanBeRetried() {
        val runtime = FakeMnnRuntime(blockGeneration = true, ignoreCancellation = true)
        val host = MnnEngineHost(runtime, shutdownTimeoutMillis = 5_000, logWarning = {})
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        val generationFinished = CountDownLatch(1)
        Thread {
            host.generate(33, chatRequest()) { }
            generationFinished.countDown()
        }.start()
        assertTrue(runtime.generationStarted.await(2, TimeUnit.SECONDS))

        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeThread = Thread {
            closeStarted.countDown()
            host.close()
            closeFinished.countDown()
        }
        closeThread.start()
        assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
        closeThread.interrupt()

        assertTrue("interrupted close must return promptly", closeFinished.await(2, TimeUnit.SECONDS))
        assertFalse("host must stay unavailable until close succeeds", host.getHealth().available)

        runtime.releaseGeneration.countDown()
        assertTrue(generationFinished.await(2, TimeUnit.SECONDS))
        assertEquals(MnnEngineState.UNLOADED, host.getStatus().state)
    }

    @Test
    fun pairStopContinuesWhenMnnCloseTimesOut() {
        val runtime = FakeMnnRuntime(blockGeneration = true, ignoreCancellation = true)
        val host = MnnEngineHost(runtime, shutdownTimeoutMillis = 50, logWarning = {})
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        Thread { host.generate(32, chatRequest()) { } }.start()
        assertTrue(runtime.generationStarted.await(2, TimeUnit.SECONDS))
        val stopContinued = CountDownLatch(1)

        Thread {
            try {
                host.close()
            } finally {
                stopContinued.countDown()
            }
        }.start()

        assertTrue("PAIR stop remained blocked on MNN close", stopContinued.await(1, TimeUnit.SECONDS))
        runtime.releaseGeneration.countDown()
    }

    @Test
    fun normalCloseStillUnloadsAndDestroysRuntime() {
        val runtime = FakeMnnRuntime()
        val host = MnnEngineHost(runtime)
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)

        host.close()

        assertEquals(1, runtime.closeCount.get())
        assertEquals(1, runtime.unloadCount.get())
    }

    @Test
    fun failedRuntimeCloseKeepsErrorStateAndCanBeRetried() {
        val runtime = FakeMnnRuntime(closeFailures = 1)
        val host = MnnEngineHost(runtime)
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)

        val firstFailure = runCatching { host.close() }.exceptionOrNull()

        assertEquals(MnnEngineState.ERROR, host.getStatus().state)
        assertTrue("runtime close failure must be reported", firstFailure is IllegalStateException)
        assertEquals("runtime close failure must remain visible", 1, runtime.closeCount.get())
        assertFalse("host must reject new work while cleanup is unresolved", host.getHealth().available)
        assertTrue("model load must not be admitted during close retry", host.loadModel(model(), MnnBackend.CPU) is MnnResult.Failure)
        assertEquals("rejected work must not reach the runtime", 1, runtime.loadCount.get())

        host.close()

        assertEquals(MnnEngineState.UNLOADED, host.getStatus().state)
        assertEquals(2, runtime.closeCount.get())
    }

    @Test
    fun initialNativeUnavailableIsPreservedByHost() {
        val runtime = NativeMnn.create { throw UnsatisfiedLinkError("synthetic unavailable library") }
        val host = MnnEngineHost(runtime)

        assertEquals(MnnEngineState.ERROR, host.getStatus().state)
        assertEquals(MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE, host.getStatus().error?.code)
        assertFalse(host.getHealth().available)
        assertEquals(MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE, host.getHealth().error?.code)
        host.close()
    }

    @Test
    fun successfulLoadReachesReady() {
        val host = MnnEngineHost(FakeMnnRuntime())

        val result = host.loadModel(model(), MnnBackend.CPU)

        assertTrue(result is MnnResult.Success)
        assertEquals(MnnEngineState.READY, host.getStatus().state)
        host.close()
    }

    @Test
    fun openClProbeRunsWithoutChangingEngineHealthOrStatus() {
        val runtime = FakeMnnRuntime()
        val host = MnnEngineHost(runtime)
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        val before = host.getStatus()

        val result = host.probeOpenCl()

        assertEquals(MnnResult.Success(BackendCapabilityResult.Available), result)
        assertEquals(before, host.getStatus())
        assertTrue(host.getHealth().available)
        host.close()
    }

    @Test
    fun failedLoadReachesErrorAndLeavesNoLoadedModel() {
        val host = MnnEngineHost(FakeMnnRuntime(loadError = MnnErrorCode.MODEL_LOAD_FAILED))

        val result = host.loadModel(model(), MnnBackend.CPU)

        val errorCode = when (result) {
            is MnnResult.Failure -> result.error.code
            is MnnResult.Success -> error("Expected the runtime load to fail")
        }
        assertEquals(MnnErrorCode.MODEL_LOAD_FAILED, errorCode)
        assertEquals(MnnEngineState.ERROR, host.getStatus().state)
        assertEquals(null, host.getLoadedModel())
        host.close()
    }

    @Test
    fun runtimeLoadExceptionBecomesTypedFailureAndErrorState() {
        val host = MnnEngineHost(FakeMnnRuntime(throwOnLoad = true))

        val result = host.loadModel(model(), MnnBackend.CPU)

        val errorCode = when (result) {
            is MnnResult.Failure -> result.error.code
            is MnnResult.Success -> error("Expected runtime load exception to be translated")
        }
        assertEquals(MnnErrorCode.MODEL_LOAD_FAILED, errorCode)
        assertEquals(MnnEngineState.ERROR, host.getStatus().state)
        assertEquals(null, host.getLoadedModel())
        host.close()
    }

    @Test
    fun repeatedLoadFailuresLeaveNoModelAndLaterLoadWorks() {
        val runtime = FakeMnnRuntime(failLoadAttempts = 2)
        val host = MnnEngineHost(runtime)

        repeat(2) {
            assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Failure)
            assertEquals(null, host.getLoadedModel())
        }
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        assertTrue(host.generate(15, chatRequest()) { } is MnnResult.Success)

        assertEquals(MnnEngineState.READY, host.getStatus().state)
        host.close()
    }

    @Test
    fun failedUnloadSynchronizesLoadedModelWithRuntime() {
        val runtime = FakeMnnRuntime(unloadFailureDropsModel = true)
        val host = MnnEngineHost(runtime)
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)

        assertTrue(host.unloadModel() is MnnResult.Failure)

        assertEquals(MnnEngineState.ERROR, host.getStatus().state)
        assertEquals(null, host.getLoadedModel())
        host.close()
    }

    @Test
    fun generationStreamsTokensAndReturnsMetrics() {
        val host = MnnEngineHost(FakeMnnRuntime())
        host.loadModel(model(), MnnBackend.CPU)
        val tokens = mutableListOf<String>()

        val result = host.generate(7, chatRequest()) { tokens.add(it) }

        assertEquals(listOf("synthetic", " response"), tokens)
        assertTrue(result is MnnResult.Success)
        val generatedTokens = when (result) {
            is MnnResult.Success -> result.value.metrics.generatedTokens
            is MnnResult.Failure -> error("Expected generation to complete")
        }
        assertEquals(2, generatedTokens)
        assertEquals(MnnEngineState.READY, host.getStatus().state)
        host.close()
    }

    @Test
    fun cancellationReturnsGenerationToReadyState() {
        val runtime = FakeMnnRuntime(blockGeneration = true)
        val host = MnnEngineHost(runtime)
        host.loadModel(model(), MnnBackend.CPU)
        val generationFinished = CountDownLatch(1)
        val generationResult = arrayOfNulls<MnnResult<MnnGenerationResult>>(1)

        Thread {
            generationResult[0] = host.generate(9, chatRequest()) { }
            generationFinished.countDown()
        }.start()

        assertTrue(runtime.generationStarted.await(2, TimeUnit.SECONDS))
        host.cancel(9)

        assertTrue(generationFinished.await(2, TimeUnit.SECONDS))
        val errorCode = when (val result = generationResult[0]) {
            is MnnResult.Failure -> result.error.code
            is MnnResult.Success -> error("Expected cancellation to stop generation")
            null -> error("Expected generation to return a result")
        }
        assertEquals(MnnErrorCode.CANCELLED, errorCode)
        assertEquals(MnnEngineState.READY, host.getStatus().state)
        assertTrue(host.generate(10, chatRequest()) { } is MnnResult.Success)
        host.close()
    }

    @Test
    fun generationFailureCanBeFollowedByReloadAndAnotherGeneration() {
        val runtime = FakeMnnRuntime(failingRequestId = 16)
        val host = MnnEngineHost(runtime)
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)

        assertTrue(host.generate(16, chatRequest()) { } is MnnResult.Failure)
        assertEquals(MnnEngineState.ERROR, host.getStatus().state)
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        assertTrue(host.generate(17, chatRequest()) { } is MnnResult.Success)

        assertEquals(MnnEngineState.READY, host.getStatus().state)
        host.close()
    }

    @Test
    fun metricsReadFailureDuringGenerationCleanupDoesNotLeaveHostGenerating() {
        val runtime = FakeMnnRuntime(failingRequestId = 18)
        val host = MnnEngineHost(runtime)
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        runtime.throwOnNextMetricsRead.set(true)

        assertTrue(host.generate(18, chatRequest()) { } is MnnResult.Failure)

        assertEquals(MnnEngineState.ERROR, host.getStatus().state)
        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        assertTrue(host.generate(19, chatRequest()) { } is MnnResult.Success)
        host.close()
    }

    @Test
    fun unloadWaitsForGenerationBeforeReleasingRuntime() {
        val runtime = FakeMnnRuntime(blockGeneration = true)
        val host = MnnEngineHost(runtime)
        host.loadModel(model(), MnnBackend.CPU)
        val generationFinished = CountDownLatch(1)
        val unloadFinished = CountDownLatch(1)

        Thread {
            host.generate(11, chatRequest()) { }
            generationFinished.countDown()
        }.start()
        assertTrue(runtime.generationStarted.await(2, TimeUnit.SECONDS))
        Thread {
            host.unloadModel()
            unloadFinished.countDown()
        }.start()

        assertFalse(unloadFinished.await(100, TimeUnit.MILLISECONDS))
        runtime.releaseGeneration.countDown()
        assertTrue(generationFinished.await(2, TimeUnit.SECONDS))
        assertTrue(unloadFinished.await(2, TimeUnit.SECONDS))
        assertEquals(1, runtime.unloadCount.get())
        assertEquals(MnnEngineState.UNLOADED, host.getStatus().state)
        host.close()
    }

    @Test
    fun unloadAndReloadAllowsAnotherGeneration() {
        val runtime = FakeMnnRuntime()
        val host = MnnEngineHost(runtime)

        assertTrue(host.loadModel(model(), MnnBackend.CPU) is MnnResult.Success)
        assertTrue(host.unloadModel() is MnnResult.Success)
        assertTrue(host.loadModel(model("qwen-reloaded"), MnnBackend.CPU) is MnnResult.Success)
        assertTrue(host.generate(13, chatRequest("qwen-reloaded")) { } is MnnResult.Success)

        assertEquals(2, runtime.loadCount.get())
        host.close()
    }

    @Test
    fun unsupportedOpenClDoesNotDisableCpu() {
        val host = MnnEngineHost(FakeMnnRuntime(supportedBackends = setOf(MnnBackend.CPU)))

        val openClResult = host.loadModel(model(), MnnBackend.OPENCL)
        val cpuResult = host.loadModel(model(), MnnBackend.CPU)

        val errorCode = when (openClResult) {
            is MnnResult.Failure -> openClResult.error.code
            is MnnResult.Success -> error("Expected OpenCL backend to be unsupported")
        }
        assertEquals(MnnErrorCode.BACKEND_UNSUPPORTED, errorCode)
        assertTrue(cpuResult is MnnResult.Success)
        assertEquals(MnnEngineState.READY, host.getStatus().state)
        assertTrue(host.getHealth().available)
        host.close()
    }

    @Test
    fun recoverableModelLoadFailureKeepsRuntimeAvailable() {
        val host = MnnEngineHost(FakeMnnRuntime(loadError = MnnErrorCode.MODEL_LOAD_FAILED))

        host.loadModel(model(), MnnBackend.CPU)

        assertEquals(MnnEngineState.ERROR, host.getStatus().state)
        assertTrue(host.getHealth().available)
        host.close()
    }

    private fun model(modelId: String = "qwen-test"): MnnModelDescriptor {
        val config = Files.createTempFile("pair-mnn-config", ".json").toFile()
        config.writeText("{}")
        return MnnModelDescriptor(modelId, config.absolutePath, modelId)
    }

    private fun chatRequest(modelId: String = "qwen-test"): MnnChatRequest = MnnChatRequest(
        modelId = modelId,
        messages = listOf(MnnChatMessage(MnnChatRole.USER, "synthetic prompt")),
    )

    private class FakeMnnRuntime(
        private val loadError: MnnErrorCode? = null,
        private val throwOnLoad: Boolean = false,
        private val blockGeneration: Boolean = false,
        private val ignoreCancellation: Boolean = false,
        private val supportedBackends: Set<MnnBackend> = setOf(MnnBackend.CPU, MnnBackend.OPENCL),
        failLoadAttempts: Int = 0,
        private val failingRequestId: Long? = null,
        private val unloadFailureDropsModel: Boolean = false,
        closeFailures: Int = 0,
    ) : MnnRuntime {
        val generationStarted = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val loadCount = AtomicInteger()
        val unloadCount = AtomicInteger()
        val cancelCount = AtomicInteger()
        val closeCount = AtomicInteger()
        private val closeFailuresRemaining = AtomicInteger(closeFailures)
        private val loadFailuresRemaining = AtomicInteger(failLoadAttempts)
        val throwOnNextMetricsRead = java.util.concurrent.atomic.AtomicBoolean(false)
        private var loadedModel: MnnLoadedModel? = null
        private var activeRequestId: Long? = null
        @Volatile private var cancelledRequestId: Long? = null

        override fun loadModel(model: MnnModelDescriptor, backend: MnnBackend): MnnResult<Unit> {
            loadCount.incrementAndGet()
            if (throwOnLoad) {
                error("Synthetic runtime failure")
            }
            if (loadFailuresRemaining.getAndUpdate { remaining -> if (remaining > 0) remaining - 1 else remaining } > 0) {
                return MnnResult.failure(MnnErrorCode.MODEL_LOAD_FAILED, "Synthetic model load failure")
            }
            if (backend !in supportedBackends) {
                return MnnResult.failure(MnnErrorCode.BACKEND_UNSUPPORTED, "Backend unavailable")
            }
            if (loadError != null) {
                return MnnResult.failure(loadError, "Model load failed")
            }
            loadedModel = MnnLoadedModel(model, backend)
            return MnnResult.success(Unit)
        }

        override fun generate(
            requestId: Long,
            request: MnnChatRequest,
            onToken: (String) -> Unit
        ): MnnResult<MnnGenerationResult> {
            activeRequestId = requestId
            generationStarted.countDown()
            if (blockGeneration) {
                if (ignoreCancellation) {
                    while (releaseGeneration.count != 0L) {
                        try {
                            releaseGeneration.await()
                        } catch (_: InterruptedException) {
                            // Simulate a native call that ignores both cancellation and interruption.
                        }
                    }
                } else {
                    releaseGeneration.await(2, TimeUnit.SECONDS)
                }
            } else {
                onToken("synthetic")
                onToken(" response")
            }
            activeRequestId = null
            if (cancelledRequestId == requestId) {
                return MnnResult.failure(MnnErrorCode.CANCELLED, "Generation cancelled")
            }
            if (failingRequestId == requestId) {
                return MnnResult.failure(MnnErrorCode.GENERATION_FAILED, "Synthetic generation failure")
            }
            return MnnResult.success(
                MnnGenerationResult(
                    text = "synthetic response",
                    metrics = MnnRuntimeMetrics(generatedTokens = 2),
                    finishReason = MnnFinishReason.STOP,
                )
            )
        }

        override fun cancel(requestId: Long) {
            cancelCount.incrementAndGet()
            if (activeRequestId == requestId && !ignoreCancellation) {
                cancelledRequestId = requestId
                releaseGeneration.countDown()
            }
        }

        override fun unloadModel(): MnnResult<Unit> {
            unloadCount.incrementAndGet()
            if (!unloadFailureDropsModel) {
                loadedModel = null
            }
            if (unloadFailureDropsModel) {
                loadedModel = null
                return MnnResult.failure(MnnErrorCode.MODEL_LOAD_FAILED, "Synthetic unload failure")
            }
            return MnnResult.success(Unit)
        }

        override fun getStatus(): MnnRuntimeStatus = MnnRuntimeStatus(
            state = if (loadedModel == null) MnnEngineState.UNLOADED else MnnEngineState.READY,
            backend = loadedModel?.backend
        )

        override fun getLoadedModel(): MnnLoadedModel? = loadedModel

        override fun getMetrics(): MnnRuntimeMetrics {
            if (throwOnNextMetricsRead.compareAndSet(true, false)) {
                error("Synthetic metrics read failure")
            }
            return MnnRuntimeMetrics()
        }

        override fun probeOpenCl(): BackendCapabilityResult = BackendCapabilityResult.Available

        override fun close() {
            closeCount.incrementAndGet()
            if (closeFailuresRemaining.getAndUpdate { remaining -> if (remaining > 0) remaining - 1 else remaining } > 0) {
                error("Synthetic runtime close failure")
            }
            unloadModel()
        }
    }

    private class FakeNativeCleanup(failUnloadAttempts: Int) : NativeSessionCleanup {
        private val failuresRemaining = AtomicInteger(failUnloadAttempts)
        val destroyCount = AtomicInteger()

        override fun unloadModel(handle: Long): Int =
            if (failuresRemaining.getAndUpdate { remaining -> if (remaining > 0) remaining - 1 else remaining } > 0) {
                SYNTHETIC_NATIVE_ERROR_LOAD
            } else {
                0
            }

        override fun destroySession(handle: Long) {
            destroyCount.incrementAndGet()
        }
    }
}
