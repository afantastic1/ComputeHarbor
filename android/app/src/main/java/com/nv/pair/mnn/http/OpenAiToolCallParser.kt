/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class OpenAiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ParsedAssistantOutput(
    val content: String?,
    val toolCalls: List<OpenAiToolCall>,
)

object OpenAiToolCallParser {
    fun parse(text: String, toolsJson: String?): ParsedAssistantOutput {
        if (toolsJson.isNullOrBlank()) return ParsedAssistantOutput(text, emptyList())
        val blocks = Regex("<tool_call>(.*?)</tool_call>", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(text)
            .toList()
        if (blocks.isEmpty()) return ParsedAssistantOutput(text, emptyList())

        val availableTools = runCatching {
            val tools = JSONArray(toolsJson)
            (0 until tools.length()).mapNotNull { index ->
                tools.optJSONObject(index)?.optJSONObject("function")?.optString("name")?.takeIf(String::isNotBlank)
            }.toSet()
        }.getOrDefault(emptySet())
        if (availableTools.isEmpty()) return ParsedAssistantOutput(text, emptyList())

        val calls = blocks.mapNotNull { block -> parseFunctionBlock(block.groupValues[1], availableTools) }
        if (calls.size != blocks.size) return ParsedAssistantOutput(text, emptyList())
        val content = text.replace(Regex("<tool_call>.*?</tool_call>", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .trim()
            .ifBlank { null }
        return ParsedAssistantOutput(content, calls)
    }

    private fun parseFunctionBlock(block: String, availableTools: Set<String>): OpenAiToolCall? {
        val functionStart = block.indexOf("<function=")
        if (functionStart < 0) return null
        val nameStart = functionStart + "<function=".length
        val nameEnd = block.indexOf('>', nameStart)
        if (nameEnd <= nameStart) return null
        val name = block.substring(nameStart, nameEnd).trim()
        if (name !in availableTools) return null

        val arguments = JSONObject()
        var cursor = nameEnd + 1
        while (true) {
            val parameterStart = block.indexOf("<parameter=", cursor)
            if (parameterStart < 0) break
            val parameterNameStart = parameterStart + "<parameter=".length
            val parameterNameEnd = block.indexOf('>', parameterNameStart)
            if (parameterNameEnd <= parameterNameStart) return null
            val parameterName = block.substring(parameterNameStart, parameterNameEnd).trim()
            val valueEnd = block.indexOf("</parameter>", parameterNameEnd + 1)
            if (parameterName.isBlank() || valueEnd < 0 || arguments.has(parameterName)) return null
            val rawValue = block.substring(parameterNameEnd + 1, valueEnd).trim()
            val value = runCatching { JSONTokener(rawValue).nextValue() }.getOrElse { rawValue }
            arguments.put(parameterName, value)
            cursor = valueEnd + "</parameter>".length
        }
        return OpenAiToolCall("call_${UUID.randomUUID().toString().replace("-", "")}", name, arguments.toString())
    }
}
