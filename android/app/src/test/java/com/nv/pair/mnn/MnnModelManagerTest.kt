/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnModelManagerTest {
    @Test
    fun rejectsModelDirectoryWithoutConfig() {
        val modelDirectory = Files.createTempDirectory("pair-mnn-model").toFile()

        val result = MnnModelManager().resolve(modelDirectory, "qwen-test")

        assertTrue(result is MnnResult.Failure)
        val errorCode = when (result) {
            is MnnResult.Failure -> result.error.code
            is MnnResult.Success -> error("Expected missing model config to fail")
        }
        assertEquals(MnnErrorCode.MODEL_CONFIG_MISSING, errorCode)
        modelDirectory.deleteRecursively()
    }

    @Test
    fun resolvesValidatedConfigToLogicalModelDescriptor() {
        val modelDirectory = Files.createTempDirectory("pair-mnn-model").toFile()
        modelDirectory.resolve("config.json").writeText(
            "{\"llm_model\":\"llm.mnn\",\"llm_weight\":\"llm.mnn.weight\",\"tokenizer_file\":\"tokenizer.txt\"}"
        )
        modelDirectory.resolve("llm_config.json").writeText("{}")
        modelDirectory.resolve("llm.mnn").writeText("synthetic model file")
        modelDirectory.resolve("llm.mnn.weight").writeText("synthetic model weights")
        modelDirectory.resolve("tokenizer.txt").writeText("synthetic tokenizer")

        val result = MnnModelManager().resolve(modelDirectory, "qwen-test", "Qwen test")

        assertTrue(result is MnnResult.Success)
        val descriptor = when (result) {
            is MnnResult.Success -> result.value
            is MnnResult.Failure -> error("Expected valid model config to resolve")
        }
        assertEquals("qwen-test", descriptor.modelId)
        assertEquals(modelDirectory.resolve("config.json").absolutePath, descriptor.configPath)
        assertEquals("Qwen test", descriptor.displayName)
        modelDirectory.deleteRecursively()
    }

    @Test
    fun rejectsModelWhenConfiguredWeightFileIsMissing() {
        val modelDirectory = Files.createTempDirectory("pair-mnn-model").toFile()
        modelDirectory.resolve("config.json").writeText(
            "{\"llm_model\":\"llm.mnn\",\"llm_weight\":\"llm.mnn.weight\",\"tokenizer_file\":\"tokenizer.txt\"}"
        )
        modelDirectory.resolve("llm_config.json").writeText("{}")
        modelDirectory.resolve("llm.mnn").writeText("synthetic model file")
        modelDirectory.resolve("tokenizer.txt").writeText("synthetic tokenizer")

        val result = MnnModelManager().resolve(modelDirectory, "qwen-test")

        assertTrue(result is MnnResult.Failure)
        val error = when (result) {
            is MnnResult.Failure -> result.error
            is MnnResult.Success -> error("Expected incomplete model to fail")
        }
        assertEquals(MnnErrorCode.MODEL_CONFIG_INVALID, error.code)
        assertTrue(error.message.contains("llm.mnn.weight"))
        modelDirectory.deleteRecursively()
    }

    @Test
    fun rejectsModelWhenConfiguredTokenizerFileIsMissing() {
        val modelDirectory = Files.createTempDirectory("pair-mnn-model").toFile()
        modelDirectory.resolve("config.json").writeText(
            "{\"llm_model\":\"llm.mnn\",\"llm_weight\":\"llm.mnn.weight\",\"tokenizer_file\":\"tokenizer.txt\"}"
        )
        modelDirectory.resolve("llm_config.json").writeText("{}")
        modelDirectory.resolve("llm.mnn").writeText("synthetic model file")
        modelDirectory.resolve("llm.mnn.weight").writeText("synthetic model weights")

        val result = MnnModelManager().resolve(modelDirectory, "qwen-test")

        assertTrue(result is MnnResult.Failure)
        val error = when (result) {
            is MnnResult.Failure -> result.error
            is MnnResult.Success -> error("Expected incomplete model to fail")
        }
        assertEquals(MnnErrorCode.MODEL_CONFIG_INVALID, error.code)
        assertTrue(error.message.contains("tokenizer.txt"))
        modelDirectory.deleteRecursively()
    }

    @Test
    fun rejectsModelWhenLlmConfigFileIsMissing() {
        val modelDirectory = Files.createTempDirectory("pair-mnn-model").toFile()
        modelDirectory.resolve("config.json").writeText(
            "{\"llm_model\":\"llm.mnn\",\"llm_weight\":\"llm.mnn.weight\",\"tokenizer_file\":\"tokenizer.txt\"}"
        )
        modelDirectory.resolve("llm.mnn").writeText("synthetic model file")
        modelDirectory.resolve("llm.mnn.weight").writeText("synthetic model weights")
        modelDirectory.resolve("tokenizer.txt").writeText("synthetic tokenizer")

        val result = MnnModelManager().resolve(modelDirectory, "qwen-test")

        assertTrue(result is MnnResult.Failure)
        val error = when (result) {
            is MnnResult.Failure -> result.error
            is MnnResult.Success -> error("Expected incomplete model to fail")
        }
        assertEquals(MnnErrorCode.MODEL_CONFIG_INVALID, error.code)
        assertTrue(error.message.contains("llm_config.json"))
        modelDirectory.deleteRecursively()
    }

    @Test
    fun rejectsVisualModelWhenVisualArtifactsAreMissing() {
        val modelDirectory = Files.createTempDirectory("pair-mnn-model").toFile()
        modelDirectory.resolve("config.json").writeText(
            "{\"llm_model\":\"llm.mnn\",\"llm_weight\":\"llm.mnn.weight\",\"tokenizer_file\":\"tokenizer.txt\"}"
        )
        modelDirectory.resolve("llm_config.json").writeText("{\"is_visual\":true}")
        modelDirectory.resolve("llm.mnn").writeText("synthetic model file")
        modelDirectory.resolve("llm.mnn.weight").writeText("synthetic model weights")
        modelDirectory.resolve("tokenizer.txt").writeText("synthetic tokenizer")

        val result = MnnModelManager().resolve(modelDirectory, "qwen-visual-test")

        assertTrue(result is MnnResult.Failure)
        val error = when (result) {
            is MnnResult.Failure -> result.error
            is MnnResult.Success -> error("Expected incomplete visual model to fail")
        }
        assertEquals(MnnErrorCode.MODEL_CONFIG_INVALID, error.code)
        assertTrue(error.message.contains("visual.mnn"))
        modelDirectory.deleteRecursively()
    }
}
