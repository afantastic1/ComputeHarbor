/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface MnnInferenceService {
    fun listModels(): List<MnnModelDescriptor>

    fun status(): MnnRuntimeStatus

    fun health(): MnnHealthStatus

    fun ensureLoaded(modelId: String, backend: MnnBackend): MnnResult<MnnLoadedModel>

    fun generate(requestId: Long, request: MnnChatRequest, onToken: (String) -> Unit): MnnResult<MnnGenerationResult>

    fun cancel(requestId: Long)

    fun unload(): MnnResult<Unit>
}

class LocalMnnInferenceService(
    private val catalog: MnnModelCatalog,
    private val host: MnnEngineHost,
    private val backendSelection: MnnBackendSelection = MnnBackendSelection(),
) : MnnInferenceService, AutoCloseable {
    private val operationLock = ReentrantLock()
    private val generationLock = ReentrantLock(true)
    private val generationSlots = Semaphore(2, true)
    private val activeRequestId = AtomicReference<Long?>(null)

    override fun listModels(): List<MnnModelDescriptor> = catalog.listModels()

    override fun status(): MnnRuntimeStatus = host.getStatus()

    override fun health(): MnnHealthStatus = host.getHealth()

    fun preferredBackend(): MnnBackend = backendSelection.current()

    fun setPreferredBackend(backend: MnnBackend) {
        backendSelection.update(backend)
    }

    override fun ensureLoaded(modelId: String, backend: MnnBackend): MnnResult<MnnLoadedModel> {
        backendSelection.update(backend)
        return operationLock.withLock {
            if (activeRequestId.get() != null) {
                return MnnResult.failure(MnnErrorCode.ENGINE_BUSY, "An MNN generation is already active.")
            }
            ensureLoadedLocked(modelId, backend)
        }
    }

    override fun generate(
        requestId: Long,
        request: MnnChatRequest,
        onToken: (String) -> Unit,
    ): MnnResult<MnnGenerationResult> {
        val validation = request.validate()
        if (validation is MnnResult.Failure) return validation
        if (!generationSlots.tryAcquire()) {
            return MnnResult.failure(MnnErrorCode.ENGINE_BUSY, "An MNN generation is already active.")
        }
        return try {
            try {
                generationLock.lockInterruptibly()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return MnnResult.failure(MnnErrorCode.CANCELLED, "MNN generation was cancelled while waiting for the engine.")
            }
            try {
                activeRequestId.set(requestId)
                when (val loaded = operationLock.withLock {
                    ensureLoadedLocked(request.modelId, backendSelection.current())
                }) {
                    is MnnResult.Failure -> loaded
                    is MnnResult.Success -> host.generate(requestId, request, onToken)
                }
            } finally {
                activeRequestId.compareAndSet(requestId, null)
                generationLock.unlock()
            }
        } finally {
            generationSlots.release()
        }
    }

    override fun cancel(requestId: Long) {
        if (activeRequestId.get() == requestId) host.cancel(requestId)
    }

    override fun unload(): MnnResult<Unit> = operationLock.withLock {
        if (activeRequestId.get() != null) {
            return MnnResult.failure(MnnErrorCode.ENGINE_BUSY, "An MNN generation is already active.")
        }
        host.unloadModel()
    }

    fun deleteModel(modelId: String, deleteFiles: () -> Unit): MnnResult<Unit> = operationLock.withLock {
        if (activeRequestId.get() != null) {
            return MnnResult.failure(MnnErrorCode.ENGINE_BUSY, "An MNN generation is active; the model was not deleted.")
        }
        if (host.getLoadedModel()?.model?.modelId == modelId) {
            when (val unloaded = host.unloadModel()) {
                is MnnResult.Failure -> return unloaded
                is MnnResult.Success -> Unit
            }
            if (host.getLoadedModel()?.model?.modelId == modelId || host.getStatus().state != MnnEngineState.UNLOADED) {
                return MnnResult.failure(MnnErrorCode.INVALID_STATE, "The active MNN model could not be confirmed unloaded.")
            }
        }
        try {
            deleteFiles()
            MnnResult.success(Unit)
        } catch (failure: Exception) {
            MnnResult.failure(MnnErrorCode.INTERNAL_ERROR, failure.message ?: "The installed model could not be deleted.")
        }
    }

    override fun close() {
        activeRequestId.get()?.let(::cancel)
        host.close()
    }

    private fun ensureLoadedLocked(modelId: String, backend: MnnBackend): MnnResult<MnnLoadedModel> {
        val model = catalog.find(modelId)
            ?: return MnnResult.failure(MnnErrorCode.MODEL_NOT_FOUND, "The requested MNN model is not installed.")
        val loaded = host.getLoadedModel()
        if (
            loaded?.model?.modelId == modelId &&
            loaded.backend == backend &&
            host.getStatus().state == MnnEngineState.READY
        ) {
            return MnnResult.success(loaded)
        }
        return when (val result = host.loadModel(model, backend)) {
            is MnnResult.Success -> host.getLoadedModel()?.let { MnnResult.success(it) }
                ?: MnnResult.failure(MnnErrorCode.INVALID_STATE, "MNN did not report a loaded model.")
            is MnnResult.Failure -> result
        }
    }
}
