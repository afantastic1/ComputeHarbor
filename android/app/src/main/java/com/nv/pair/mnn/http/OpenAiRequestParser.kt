/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import com.nv.pair.mnn.MnnChatMessage
import com.nv.pair.mnn.MnnChatRequest
import com.nv.pair.mnn.MnnChatRole
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class OpenAiChatCompletionRequest(
    val chat: MnnChatRequest,
    val stream: Boolean,
    val includeUsage: Boolean,
)

class OpenAiRequestException(
    val statusCode: Int,
    val code: String,
    message: String,
    val fieldPath: String? = null,
    val fieldKind: String? = null,
) : Exception(message)

object OpenAiRequestParser {
    private val supportedFields = setOf("model", "messages", "stream", "stream_options", "max_tokens", "temperature", "top_p", "seed", "tools", "tool_choice")
    private val unsupportedFields = setOf(
        "functions", "function_call", "response_format", "logprobs", "top_logprobs",
        "audio", "modalities", "n", "stop", "presence_penalty", "frequency_penalty", "logit_bias",
    )

    fun parse(body: JSONObject): OpenAiChatCompletionRequest {
        body.keys().forEach { key ->
            if (key in unsupportedFields || key !in supportedFields) {
                throw badRequest("unsupported_parameter", "The '$key' parameter is not supported.", key)
            }
        }
        val modelId = requiredString(body, "model", "model")
        val messages = parseMessages(body.optJSONArray("messages"))
        val stream = optionalBoolean(body, "stream", false)
        val includeUsage = if (body.has("stream_options")) {
            val options = body.optJSONObject("stream_options")
                ?: throw badRequest("invalid_request_error", "'stream_options' must be an object.", "stream_options")
            options.keys().forEach { key ->
                if (key != "include_usage") {
                    throw badRequest("unsupported_parameter", "The 'stream_options.$key' parameter is not supported.", "stream_options.$key")
                }
            }
            optionalBoolean(options, "include_usage", false)
        } else {
            false
        }
        val maxTokens = optionalInteger(body, "max_tokens", DEFAULT_MAX_TOKENS)
        val temperature = optionalNumber(body, "temperature", DEFAULT_TEMPERATURE).toFloat()
        val topP = optionalNumber(body, "top_p", DEFAULT_TOP_P).toFloat()
        val seed = if (body.has("seed")) optionalInteger(body, "seed", 0) else null
        if (body.has("tools") && body.opt("tools") !is JSONArray) {
            throw badRequest("invalid_request_error", "'tools' must be an array.", "tools")
        }
        val tools = body.optJSONArray("tools")
        if (tools != null) validateTools(tools)
        val toolChoice = parseToolChoice(body.opt("tool_choice"), tools)
        val chat = MnnChatRequest(
            modelId, messages, maxTokens, temperature, topP, seed,
            tools?.toString(), toolChoice,
        )
        when (val validation = chat.validate()) {
            is com.nv.pair.mnn.MnnResult.Failure -> throw badRequest(
                "invalid_request_error",
                validation.error.message,
                "messages",
            )
            is com.nv.pair.mnn.MnnResult.Success -> Unit
        }
        return OpenAiChatCompletionRequest(chat, stream, includeUsage)
    }

    private fun parseMessages(messages: JSONArray?): List<MnnChatMessage> {
        if (messages == null || messages.length() == 0) {
            throw badRequest("invalid_request_error", "'messages' must be a non-empty array.", "messages")
        }
        return (0 until messages.length()).mapNotNull { index ->
            val message = try {
                messages.getJSONObject(index)
            } catch (_: JSONException) {
                throw badRequest("invalid_request_error", "Each message must be an object with role and content.", "messages[$index]")
            }
            val roleName = requiredString(message, "role", "messages[$index].role")
            val isToolHistory = roleName == "tool" || (roleName == "assistant" && message.has("tool_calls"))
            message.keys().forEach { key ->
                val allowedToolFields = if (roleName == "assistant") setOf("tool_calls") else setOf("tool_call_id", "name")
                if (key !in setOf("role", "content") && !(isToolHistory && key in allowedToolFields)) {
                    throw badRequest("unsupported_parameter", "The message '$key' field is not supported.", "messages[$index].$key")
                }
            }
            if (isToolHistory) {
                if (roleName == "assistant") {
                    val calls = message.optJSONArray("tool_calls")
                    if (calls == null || calls.length() == 0) {
                        throw badRequest("invalid_request_error", "Assistant tool_calls must be a non-empty array.", "messages[$index].tool_calls")
                    }
                    for (callIndex in 0 until calls.length()) {
                        val call = calls.optJSONObject(callIndex)
                        val function = call?.optJSONObject("function")
                        if (call == null || call.optString("id").isBlank() ||
                            call.optString("type") != "function" || function?.optString("name").isNullOrBlank() ||
                            function?.opt("arguments") !is String) {
                            throw badRequest("invalid_request_error", "Assistant tool call is malformed.", "messages[$index].tool_calls[$callIndex]")
                        }
                    }
                }
                if (roleName == "tool" && message.optString("tool_call_id").isBlank()) {
                    throw badRequest("invalid_request_error", "Tool messages require tool_call_id.", "messages[$index].tool_call_id")
                }
                return@mapNotNull MnnChatMessage(MnnChatRole.ASSISTANT, "", message.toString())
            }
            val role = MnnChatRole.entries.firstOrNull { it.wireName == roleName }
                ?: throw badRequest("invalid_request_error", "Message role '$roleName' is not supported.", "messages[$index].role")
            val content = parseMessageContent(message.opt("content"), index, role == MnnChatRole.ASSISTANT)
            if (role == MnnChatRole.ASSISTANT && content.isBlank()) null else MnnChatMessage(role, content)
        }
    }

    private fun validateTools(tools: JSONArray) {
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index)
                ?: throw badRequest("invalid_request_error", "Each tool must be an object.", "tools[$index]")
            val function = tool.optJSONObject("function")
            if (tool.optString("type") != "function" || function == null || function.optString("name").isBlank()) {
                throw badRequest("invalid_request_error", "Tools must use the function tool format.", "tools[$index]")
            }
            val parameters = function.opt("parameters")
            if (parameters != null && parameters !is JSONObject) {
                throw badRequest("invalid_request_error", "Function parameters must be an object.", "tools[$index].function.parameters")
            }
        }
    }

    private fun parseToolChoice(value: Any?, tools: JSONArray?): String? {
        if (value == null) return null
        if (tools == null && value == "none") return "none"
        if (tools == null) throw badRequest("invalid_request_error", "tool_choice requires tools.", "tool_choice")
        if (tools.length() == 0 && value != "none") {
            throw badRequest("invalid_request_error", "tool_choice requires at least one function tool.", "tool_choice")
        }
        if (value is String && value in setOf("auto", "none", "required")) return value
        if (value is JSONObject && value.optString("type") == "function" &&
            value.optJSONObject("function")?.optString("name").orEmpty().isNotBlank()) {
            val functionName = value.getJSONObject("function").getString("name")
            val offered = (0 until tools.length()).any { index ->
                tools.optJSONObject(index)?.optJSONObject("function")?.optString("name") == functionName
            }
            if (offered) return "function:$functionName"
        }
        throw badRequest("invalid_request_error", "tool_choice must be auto, none, required, or a function choice.", "tool_choice")
    }

    private fun parseMessageContent(value: Any?, messageIndex: Int, allowEmpty: Boolean): String {
        val fieldPath = "messages[$messageIndex].content"
        return when (value) {
            is String -> {
                if (value.isBlank() && !allowEmpty) {
                    throw badRequest("invalid_request_error", "'content' must be a non-empty string.", fieldPath)
                }
                value
            }
            is JSONArray -> parseTextContentParts(value, fieldPath, allowEmpty)
            else -> throw badRequest(
                "invalid_request_error",
                "'content' must be text or an array of text parts.",
                fieldPath,
                jsonKind(value),
            )
        }
    }

    private fun jsonKind(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        is String -> "string"
        is Number -> "number"
        is Boolean -> "boolean"
        else -> "other"
    }

    private fun parseTextContentParts(parts: JSONArray, fieldPath: String, allowEmpty: Boolean): String {
        val textParts = (0 until parts.length()).map { index ->
            val partPath = "$fieldPath[$index]"
            val part = parts.optJSONObject(index)
                ?: throw badRequest("invalid_request_error", "Each content part must be an object.", partPath)
            if (part.optString("type") != "text") {
                throw badRequest("unsupported_parameter", "Only text content parts are supported.", partPath)
            }
            val text = part.opt("text")
            if (text !is String) {
                throw badRequest("invalid_request_error", "Text content parts must contain a string 'text' field.", "$partPath.text")
            }
            text
        }.filter(String::isNotBlank)
        if (textParts.isEmpty() && !allowEmpty) {
            throw badRequest("invalid_request_error", "'content' must contain non-empty text.", fieldPath)
        }
        return textParts.joinToString("\n")
    }

    private fun requiredString(body: JSONObject, field: String, fieldPath: String): String {
        val value = body.opt(field)
        if (value !is String || value.isBlank()) {
            throw badRequest("invalid_request_error", "'$field' must be a non-empty string.", fieldPath)
        }
        return value
    }

    private fun optionalBoolean(body: JSONObject, field: String, default: Boolean): Boolean {
        if (!body.has(field)) return default
        return when (val value = body.opt(field)) {
            is Boolean -> value
            else -> throw badRequest("invalid_request_error", "'$field' must be a boolean.", field)
        }
    }

    private fun optionalInteger(body: JSONObject, field: String, default: Int): Int {
        if (!body.has(field)) return default
        val number = when (val value = body.opt(field)) {
            is Number -> value
            else -> throw badRequest("invalid_request_error", "'$field' must be an integer.", field)
        }
        val value = number.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw badRequest("invalid_request_error", "'$field' must be an integer.", field)
        }
        return value.toInt()
    }

    private fun optionalNumber(body: JSONObject, field: String, default: Double): Double {
        if (!body.has(field)) return default
        val number = when (val value = body.opt(field)) {
            is Number -> value
            else -> throw badRequest("invalid_request_error", "'$field' must be a number.", field)
        }
        val value = number.toDouble()
        if (!value.isFinite()) throw badRequest("invalid_request_error", "'$field' must be finite.", field)
        return value
    }

    private fun badRequest(
        code: String,
        message: String,
        fieldPath: String? = null,
        fieldKind: String? = null,
    ) = OpenAiRequestException(400, code, message, fieldPath, fieldKind)

    private const val DEFAULT_MAX_TOKENS = 128
    private const val DEFAULT_TEMPERATURE = 0.7
    private const val DEFAULT_TOP_P = 0.9
}
