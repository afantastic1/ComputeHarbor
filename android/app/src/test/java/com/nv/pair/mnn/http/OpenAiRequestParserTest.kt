/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import com.nv.pair.mnn.MnnChatRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestParserTest {
    @Test
    fun parsesStructuredMessagesAndSamplingOptions() {
        val request = OpenAiRequestParser.parse(
            JSONObject(
                """{"model":"qwen3-0.6b","messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"Hello."}],"stream":true,"max_tokens":48,"temperature":0.25,"top_p":0.8,"seed":7}""",
            ),
        )

        assertEquals("qwen3-0.6b", request.chat.modelId)
        assertEquals(listOf(MnnChatRole.SYSTEM, MnnChatRole.USER), request.chat.messages.map { it.role })
        assertEquals("Be brief.", request.chat.messages.first().content)
        assertEquals(48, request.chat.maxTokens)
        assertEquals(0.25f, request.chat.temperature)
        assertEquals(0.8f, request.chat.topP)
        assertEquals(7, request.chat.seed)
        assertTrue(request.stream)
    }

    @Test
    fun acceptsOpenAiStreamingUsageOptions() {
        val request = OpenAiRequestParser.parse(
            JSONObject(
                """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"Hello."}],"stream":true,"stream_options":{"include_usage":true}}""",
            ),
        )

        assertTrue(request.stream)
        assertTrue(request.includeUsage)
    }

    @Test
    fun parsesTextContentPartsUsedByOpenAiCompatibleClients() {
        val request = OpenAiRequestParser.parse(
            JSONObject(
                """{"model":"qwen3-0.6b","messages":[{"role":"user","content":[{"type":"text","text":"Attached notes"},{"type":"text","text":"Follow-up"}]}]}""",
            ),
        )

        assertEquals("Attached notes\nFollow-up", request.chat.messages.single().content)
    }

    @Test
    fun skipsEmptyAssistantMessagesFromConversationHistory() {
        val request = OpenAiRequestParser.parse(
            JSONObject(
                """{"model":"qwen3-0.6b","messages":[{"role":"assistant","content":""},{"role":"user","content":"Continue"}]}""",
            ),
        )

        assertEquals(1, request.chat.messages.size)
        assertEquals(MnnChatRole.USER, request.chat.messages.single().role)
        assertEquals("Continue", request.chat.messages.single().content)
    }

    @Test
    fun rejectsUnsupportedMultimodalContentWithItsFieldPath() {
        val failure = runCatching {
            OpenAiRequestParser.parse(
                JSONObject(
                    """{"model":"qwen3-0.6b","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}]}]}""",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is OpenAiRequestException)
        assertEquals("messages[0].content[0]", (failure as OpenAiRequestException).fieldPath)
    }

    @Test
    fun reportsOnlyTheJsonKindForInvalidContentDiagnostics() {
        val failure = runCatching {
            OpenAiRequestParser.parse(
                JSONObject("""{"model":"m","messages":[{"role":"assistant","content":null}]}"""),
            )
        }.exceptionOrNull()

        assertTrue(failure is OpenAiRequestException)
        val requestFailure = failure as OpenAiRequestException
        assertEquals("messages[0].content", requestFailure.fieldPath)
        assertEquals("null", requestFailure.fieldKind)
    }

    @Test
    fun defaultsToNonStreamingWithRuntimeSamplingDefaults() {
        val request = OpenAiRequestParser.parse(
            JSONObject("""{"model":"qwen3-0.6b","messages":[{"role":"user","content":"Hello."}]}"""),
        )

        assertFalse(request.stream)
        assertEquals(128, request.chat.maxTokens)
        assertEquals(0.7f, request.chat.temperature)
        assertEquals(0.9f, request.chat.topP)
        assertEquals(null, request.chat.seed)
    }

    @Test
    fun explicitlyRejectsUnsupportedCapabilities() {
        listOf(
            """{"model":"m","messages":[{"role":"user","content":"x"}],"logprobs":true}""",
            """{"model":"m","messages":[{"role":"user","content":[{"type":"image_url"}]}]}""",
        ).forEach { body ->
            val failure = runCatching { OpenAiRequestParser.parse(JSONObject(body)) }.exceptionOrNull()
            assertTrue("Expected an explicit unsupported request error", failure is OpenAiRequestException)
            val statusCode = when (failure) {
                is OpenAiRequestException -> failure.statusCode
                else -> error("Expected an HTTP request error")
            }
            assertEquals(400, statusCode)
        }
    }

    @Test
    fun parsesQwenToolCallIntoOpenAiResponseShapeOnlyForAdvertisedTools() {
        val tools = """[{"type":"function","function":{"name":"lookup"}}]"""
        val output = OpenAiToolCallParser.parse(
            "Thinking first. <tool_call><function=lookup><parameter=key>\"value\"</parameter></function></tool_call>",
            tools,
        )

        assertEquals("Thinking first.", output.content)
        assertEquals(1, output.toolCalls.size)
        assertEquals("lookup", output.toolCalls.single().name)
        assertEquals("{\"key\":\"value\"}", output.toolCalls.single().arguments)
        assertTrue(OpenAiToolCallParser.parse("<tool_call><function=other></function></tool_call>", tools).toolCalls.isEmpty())

        val completion = OpenAiResponseWriter.completion(
            "chatcmpl-test", "qwen3.5", output.content.orEmpty(), 10, 4,
            com.nv.pair.mnn.MnnFinishReason.STOP, output.toolCalls,
        )
        val choice = completion.getJSONArray("choices").getJSONObject(0)
        assertEquals("tool_calls", choice.getString("finish_reason"))
        assertEquals("Thinking first.", choice.getJSONObject("message").getString("content"))
        assertEquals("lookup", choice.getJSONObject("message").getJSONArray("tool_calls")
            .getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test
    fun parsesToolsAndAssistantToolCallHistoryForCallerSideExecution() {
        val request = OpenAiRequestParser.parse(
            JSONObject(
                """{"model":"qwen3.5","messages":[{"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\"key\":\"v\"}"}}]},{"role":"tool","tool_call_id":"call_1","name":"lookup","content":"result"},{"role":"user","content":"Continue"}],"tools":[{"type":"function","function":{"name":"lookup","description":"Lookup","parameters":{"type":"object","properties":{"key":{"type":"string"}}}}}],"tool_choice":"auto"}""",
            ),
        )

        assertEquals(3, request.chat.messages.size)
        assertEquals("json", request.chat.messages[0].nativeRole)
        assertTrue(request.chat.messages[0].nativeJson?.contains("\"tool_calls\"") == true)
        assertEquals("json", request.chat.messages[1].nativeRole)
        assertTrue(request.chat.messages[1].nativeJson?.contains("\"tool_call_id\"") == true)
        assertTrue(request.chat.toolsJson?.contains("\"lookup\"") == true)
        assertEquals("auto", request.chat.toolChoice)
    }

    @Test
    fun reportsTheUnsupportedFieldPathForRequestDiagnostics() {
        val failure = runCatching {
            OpenAiRequestParser.parse(
                JSONObject("""{"model":"m","messages":[{"role":"user","content":"x"}],"unknown_option":true}"""),
            )
        }.exceptionOrNull()

        assertTrue(failure is OpenAiRequestException)
        assertEquals("unknown_option", (failure as OpenAiRequestException).fieldPath)
    }

    @Test
    fun rejectsUnknownRolesAndMalformedSamplingOptions() {
        val roleFailure = runCatching {
            OpenAiRequestParser.parse(
                JSONObject("""{"model":"m","messages":[{"role":"tool","content":"x"}]}"""),
            )
        }.exceptionOrNull()
        val samplingFailure = runCatching {
            OpenAiRequestParser.parse(
                JSONObject("""{"model":"m","messages":[{"role":"user","content":"x"}],"top_p":0}"""),
            )
        }.exceptionOrNull()

        assertTrue(roleFailure is OpenAiRequestException)
        assertTrue(samplingFailure is OpenAiRequestException)
    }
}
