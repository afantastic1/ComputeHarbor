/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn

import java.io.File
import org.json.JSONException
import org.json.JSONObject

class MnnModelManager {
    fun resolve(
        modelDirectory: File,
        modelId: String,
        displayName: String? = null
    ): MnnResult<MnnModelDescriptor> {
        if (!modelId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            return MnnResult.failure(
                MnnErrorCode.INVALID_MODEL_ID,
                "Model ID must be 1–128 safe identifier characters."
            )
        }
        if (!modelDirectory.isDirectory) {
            return MnnResult.failure(
                MnnErrorCode.MODEL_DIRECTORY_MISSING,
                "Model directory does not exist: ${modelDirectory.absolutePath}"
            )
        }

        val configFile = File(modelDirectory, CONFIG_FILE_NAME)
        if (!configFile.isFile) {
            return MnnResult.failure(
                MnnErrorCode.MODEL_CONFIG_MISSING,
                "Model config is missing: ${configFile.absolutePath}"
            )
        }
        if (!configFile.canRead()) {
            return MnnResult.failure(
                MnnErrorCode.MODEL_CONFIG_INVALID,
                "Model config is not readable: ${configFile.absolutePath}"
            )
        }

        try {
            val config = JSONObject(configFile.readText())
            if (config.length() == 0 || config.optString("llm_model").isBlank()) {
                return MnnResult.failure(
                    MnnErrorCode.MODEL_CONFIG_INVALID,
                    "Model config must be an MNN LLM config with a non-empty llm_model field."
                )
            }
            val artifacts = listOf(
                ConfigArtifact("llm_config", "llm_config.json", "LLM config"),
                ConfigArtifact("llm_model", "llm.mnn", "model graph"),
                ConfigArtifact("llm_weight", "llm.mnn.weight", "model weights"),
                ConfigArtifact("tokenizer_file", "tokenizer.txt", "tokenizer"),
            )
            for (artifact in artifacts) {
                val error = validateArtifact(config, modelDirectory, artifact)
                if (error != null) {
                    return MnnResult.Failure(error)
                }
            }
            val llmConfigFile = File(modelDirectory, config.optString("llm_config", "llm_config.json"))
            val llmConfig = JSONObject(llmConfigFile.readText())
            if (config.optBoolean("is_visual", llmConfig.optBoolean("is_visual", false))) {
                val visualConfig = JSONObject(llmConfig.toString())
                listOf("visual_model", "vision_weight").forEach { key ->
                    if (config.has(key)) visualConfig.put(key, config.get(key))
                }
                val visualArtifacts = listOf(
                    ConfigArtifact("visual_model", "visual.mnn", "visual model"),
                    ConfigArtifact("vision_weight", "visual.mnn.weight", "visual model weights"),
                )
                for (artifact in visualArtifacts) {
                    val error = validateArtifact(visualConfig, modelDirectory, artifact)
                    if (error != null) {
                        return MnnResult.Failure(error)
                    }
                }
            }
        } catch (_: JSONException) {
            return MnnResult.failure(
                MnnErrorCode.MODEL_CONFIG_INVALID,
                "Model config is not valid JSON."
            )
        } catch (_: java.io.IOException) {
            return MnnResult.failure(
                MnnErrorCode.MODEL_CONFIG_INVALID,
                "Model config could not be read."
            )
        }

        return MnnResult.success(
            MnnModelDescriptor(
                modelId = modelId,
                configPath = configFile.absolutePath,
                displayName = displayName?.takeIf(String::isNotBlank)
            )
        )
    }

    companion object {
        private const val CONFIG_FILE_NAME = "config.json"
    }

    private data class ConfigArtifact(
        val configKey: String,
        val defaultFilename: String,
        val label: String,
    )

    private fun validateArtifact(
        config: JSONObject,
        modelDirectory: File,
        artifact: ConfigArtifact,
    ): MnnError? {
        val filename = config.optString(artifact.configKey, artifact.defaultFilename)
        if (filename.isBlank()) {
            return MnnError(
                MnnErrorCode.MODEL_CONFIG_INVALID,
                "The ${artifact.label} filename in config.json must not be empty."
            )
        }
        val directoryPrefix = modelDirectory.canonicalPath + File.separator
        val file = File(modelDirectory, filename)
        if (!file.canonicalPath.startsWith(directoryPrefix)) {
            return MnnError(
                MnnErrorCode.MODEL_CONFIG_INVALID,
                "The ${artifact.label} declared by config.json must stay inside the model directory."
            )
        }
        if (!file.isFile) {
            return MnnError(
                MnnErrorCode.MODEL_CONFIG_INVALID,
                "The ${artifact.label} declared by config.json is missing: ${file.name}"
            )
        }
        return null
    }
}
