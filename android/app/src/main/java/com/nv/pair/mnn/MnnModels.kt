/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

data class MnnModelDescriptor(
    val modelId: String,
    val configPath: String,
    val displayName: String? = null
)

enum class MnnBackend(val preferenceValue: String) {
    CPU("cpu"),
    OPENCL("opencl");

    companion object {
        fun fromPreferenceValue(value: String?): MnnBackend =
            entries.firstOrNull { it.preferenceValue == value } ?: CPU
    }
}

enum class MnnEngineState {
    UNLOADED,
    LOADING,
    READY,
    GENERATING,
    ERROR
}

enum class MnnErrorCode {
    INVALID_MODEL_ID,
    MODEL_DIRECTORY_MISSING,
    MODEL_CONFIG_MISSING,
    MODEL_CONFIG_INVALID,
    MODEL_LOAD_FAILED,
    NATIVE_LIBRARY_UNAVAILABLE,
    BACKEND_UNSUPPORTED,
    INVALID_REQUEST,
    MODEL_NOT_FOUND,
    MODEL_NOT_LOADED,
    ENGINE_BUSY,
    INVALID_STATE,
    GENERATION_FAILED,
    CANCELLED,
    INTERNAL_ERROR
}

enum class MnnFinishReason(val wireName: String) {
    STOP("stop"),
    LENGTH("length")
}

data class MnnError(
    val code: MnnErrorCode,
    val message: String
)

sealed interface MnnResult<out T> {
    data class Success<T>(val value: T) : MnnResult<T>
    data class Failure(val error: MnnError) : MnnResult<Nothing>

    companion object {
        fun <T> success(value: T): MnnResult<T> = Success(value)

        fun failure(code: MnnErrorCode, message: String): MnnResult<Nothing> =
            Failure(MnnError(code, message))
    }
}

data class MnnLoadedModel(
    val model: MnnModelDescriptor,
    val backend: MnnBackend,
    val loadedAtEpochMillis: Long = System.currentTimeMillis()
)

data class MnnRuntimeStatus(
    val state: MnnEngineState,
    val backend: MnnBackend? = null,
    val modelId: String? = null,
    val error: MnnError? = null
)

data class MnnHealthStatus(
    val available: Boolean,
    val state: MnnEngineState,
    val error: MnnError? = null,
)

data class MnnRuntimeMetrics(
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val durationMillis: Long = 0,
    val tokensPerSecond: Double = 0.0,
    val peakMemoryBytes: Long? = null
)

enum class MnnChatRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant")
}

data class MnnChatMessage(
    val role: MnnChatRole,
    val content: String,
    val nativeJson: String? = null,
) {
    val nativeRole: String
        get() = if (nativeJson != null) "json" else role.wireName
}

data class MnnChatRequest(
    val modelId: String,
    val messages: List<MnnChatMessage>,
    val maxTokens: Int = 128,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val seed: Int? = null,
    val toolsJson: String? = null,
    val toolChoice: String? = null,
) {
    fun validate(): MnnResult<Unit> {
        if (!modelId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            return MnnResult.failure(MnnErrorCode.INVALID_MODEL_ID, "Model ID is invalid.")
        }
        if (messages.isEmpty() || messages.none { it.role == MnnChatRole.USER }) {
            return MnnResult.failure(MnnErrorCode.INVALID_REQUEST, "Chat messages must include a user message.")
        }
        if (messages.any { it.content.isBlank() && it.nativeJson == null }) {
            return MnnResult.failure(MnnErrorCode.INVALID_REQUEST, "Chat message content must not be empty.")
        }
        if (maxTokens !in 1..4096) {
            return MnnResult.failure(MnnErrorCode.INVALID_REQUEST, "maxTokens must be between 1 and 4096.")
        }
        if (!temperature.isFinite() || temperature < 0f || temperature > 2f) {
            return MnnResult.failure(MnnErrorCode.INVALID_REQUEST, "temperature must be between 0 and 2.")
        }
        if (!topP.isFinite() || topP <= 0f || topP > 1f) {
            return MnnResult.failure(MnnErrorCode.INVALID_REQUEST, "topP must be greater than 0 and at most 1.")
        }
        return MnnResult.success(Unit)
    }
}

data class MnnGenerationResult(
    val text: String,
    val metrics: MnnRuntimeMetrics,
    val finishReason: MnnFinishReason,
)
