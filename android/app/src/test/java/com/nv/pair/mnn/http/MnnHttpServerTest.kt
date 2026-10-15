/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.MnnChatMessage
import com.nv.pair.mnn.MnnChatRequest
import com.nv.pair.mnn.MnnChatRole
import com.nv.pair.mnn.MnnError
import com.nv.pair.mnn.MnnErrorCode
import com.nv.pair.mnn.MnnGenerationResult
import com.nv.pair.mnn.MnnFinishReason
import com.nv.pair.mnn.MnnInferenceService
import com.nv.pair.mnn.MnnHealthStatus
import com.nv.pair.mnn.MnnLoadedModel
import com.nv.pair.mnn.MnnModelDescriptor
import com.nv.pair.mnn.MnnResult
import com.nv.pair.mnn.MnnRuntimeMetrics
import com.nv.pair.mnn.MnnRuntimeStatus
import com.nv.pair.mnn.MnnEngineState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnHttpServerTest {
    @Test
    fun healthModelsAndChatUseLoopbackOpenAiContracts() {
        val service = FakeInferenceService()
        val server = MnnHttpServer(service, TEST_PORT)
        server.start()
        try {
            assertEquals("127.0.0.1", InetAddress.getByName("127.0.0.1").hostAddress)
            val health = http(server.localPort, "GET", "/healthz")
            val models = http(server.localPort, "GET", "/v1/models")
            val completion = http(
                server.localPort,
                "POST",
                "/v1/chat/completions",
                chatBody(stream = false),
            )

            assertTrue(health.startsWith("HTTP/1.1 200"))
            assertTrue(health.contains("\"status\":\"ok\""))
            assertTrue(health.contains("\"runtime_state\":\"unloaded\""))
            assertTrue(models.contains("\"id\":\"qwen3-0.6b\""))
            assertTrue(completion.startsWith("HTTP/1.1 200"))
            assertTrue(completion.contains("\"object\":\"chat.completion\""))
            assertTrue(completion.contains("\"content\":\"Hello world\""))
            assertTrue(completion.contains("\"finish_reason\":\"stop\""))
            assertEquals(listOf(MnnChatMessage(MnnChatRole.USER, "Say hello.")), service.lastRequest?.messages)
        } finally {
            server.close()
        }
    }

    @Test
    fun healthReturns503WhenNativeRuntimeIsUnavailable() {
        val server = MnnHttpServer(FakeInferenceService(available = false), TEST_PORT)
        server.start()
        try {
            val health = http(server.localPort, "GET", "/healthz")

            assertTrue(health.startsWith("HTTP/1.1 503"))
            assertTrue(health.contains("\"status\":\"unavailable\""))
            assertTrue(health.contains("\"runtime_state\":\"error\""))
            assertTrue(health.contains("\"error_code\":\"native_library_unavailable\""))
        } finally {
            server.close()
        }
    }

    @Test
    fun unsupportedOpenClLoadReturnsTyped422WithoutCpuSuccess() {
        val server = MnnHttpServer(FakeInferenceService(supportsOpenCl = false), TEST_PORT)
        server.start()
        try {
            val result = http(
                server.localPort,
                "POST",
                "/internal/models/load",
                """{"model":"qwen3-0.6b","backend":"opencl"}""",
            )

            assertTrue(result.startsWith("HTTP/1.1 422"))
            assertTrue(result.contains("\"code\":\"backend_unsupported\""))
        } finally {
            server.close()
        }
    }

    @Test
    fun mapsInferenceErrorsToConsistentOpenAiStatusTypeAndCode() {
        val cases = listOf(
            Triple(MnnErrorCode.ENGINE_BUSY, 409, "server_error"),
            Triple(MnnErrorCode.BACKEND_UNSUPPORTED, 422, "invalid_request_error"),
            Triple(MnnErrorCode.INVALID_REQUEST, 400, "invalid_request_error"),
            Triple(MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE, 503, "server_error"),
            Triple(MnnErrorCode.GENERATION_FAILED, 500, "server_error"),
        )

        cases.forEach { (errorCode, status, type) ->
            val server = MnnHttpServer(FakeInferenceService(generationError = errorCode), TEST_PORT)
            server.start()
            try {
                val response = http(server.localPort, "POST", "/v1/chat/completions", chatBody(stream = false))
                val expectedCode = when (errorCode) {
                    MnnErrorCode.ENGINE_BUSY -> "engine_busy"
                    MnnErrorCode.BACKEND_UNSUPPORTED -> "backend_unsupported"
                    MnnErrorCode.INVALID_REQUEST -> "invalid_request_error"
                    MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE -> "engine_unavailable"
                    else -> "server_error"
                }
                assertTrue("$errorCode returned unexpected status: $response", response.startsWith("HTTP/1.1 $status"))
                assertTrue("$errorCode returned unexpected type: $response", response.contains("\"type\":\"$type\""))
                assertTrue("$errorCode returned unexpected code: $response", response.contains("\"code\":\"$expectedCode\""))
            } finally {
                server.close()
            }
        }

        val missingModelServer = MnnHttpServer(FakeInferenceService(), TEST_PORT)
        missingModelServer.start()
        try {
            val response = http(
                missingModelServer.localPort,
                "POST",
                "/v1/chat/completions",
                """{"model":"missing-model","messages":[{"role":"user","content":"x"}]}""",
            )
            assertTrue(response.startsWith("HTTP/1.1 404"))
            assertTrue(response.contains("\"type\":\"invalid_request_error\""))
            assertTrue(response.contains("\"code\":\"model_not_found\""))
        } finally {
            missingModelServer.close()
        }
    }

    @Test
    fun streamsFirstTokenBeforeDoneAndAcceptsOpenAiToolSchemas() {
        val server = MnnHttpServer(FakeInferenceService(), TEST_PORT)
        server.start()
        try {
            val stream = http(server.localPort, "POST", "/v1/chat/completions", chatBody(stream = true))
            val firstEvent = stream.indexOf("data: {")
            val done = stream.indexOf("data: [DONE]")
            assertTrue(stream.contains("Content-Type: text/event-stream"))
            assertTrue(stream, firstEvent >= 0)
            assertTrue(done > firstEvent)
            assertTrue(stream.contains("\"content\":\"Hello\""))
            assertTrue(stream.contains("\"finish_reason\":\"stop\""))
            assertEquals(1, Regex("data: \\[DONE\\]").findAll(stream).count())

            val usageStream = http(
                server.localPort,
                "POST",
                "/v1/chat/completions",
                """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"x"}],"stream":true,"stream_options":{"include_usage":true}}""",
            )
            assertTrue(usageStream.contains("\"prompt_tokens\":0"))
            assertTrue(usageStream.contains("\"completion_tokens\":2"))

            val toolsRequest = http(
                server.localPort,
                "POST",
                "/v1/chat/completions",
                """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"x"}],"tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}],"tool_choice":"auto"}""",
            )
            assertTrue("tool schema response: $toolsRequest", toolsRequest.startsWith("HTTP/1.1 200"))
        } finally {
            server.close()
        }
    }

    @Test
    fun returnsCallerExecutableToolCallsForStreamingAndNonStreamingRequests() {
        val generatedCall = "<tool_call><function=lookup><parameter=key>value</parameter></function></tool_call>"
        val server = MnnHttpServer(FakeInferenceService(generatedChunks = listOf(generatedCall)), TEST_PORT)
        server.start()
        try {
            val body = """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"lookup"}],"tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object","properties":{"key":{"type":"string"}}}}}]}"""
            val completion = http(server.localPort, "POST", "/v1/chat/completions", body)
            assertTrue(completion.contains("\"finish_reason\":\"tool_calls\""))
            assertTrue(completion.contains("\"name\":\"lookup\""))
            assertTrue(completion.contains("\"arguments\":\"{\\\"key\\\":\\\"value\\\"}\""))

            val streamed = http(
                server.localPort,
                "POST",
                "/v1/chat/completions",
                body.dropLast(1) + ",\"stream\":true}",
            )
            assertTrue(streamed.contains("\"tool_calls\":"))
            assertTrue(streamed.contains("\"finish_reason\":\"tool_calls\""))
            assertTrue(streamed.contains("data: [DONE]"))
        } finally {
            server.close()
        }
    }

    @Test
    fun completionAndStreamExposeLengthFinishReasonFromInference() {
        val server = MnnHttpServer(
            FakeInferenceService(generationFinishReason = MnnFinishReason.LENGTH),
            TEST_PORT,
        )
        server.start()
        try {
            val completion = http(
                server.localPort,
                "POST",
                "/v1/chat/completions",
                """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"x"}]}""",
            )
            assertTrue(completion.contains("\"finish_reason\":\"length\""))

            val stream = http(
                server.localPort,
                "POST",
                "/v1/chat/completions",
                """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"x"}],"stream":true}""",
            )
            assertTrue(stream.contains("\"finish_reason\":\"length\""))
            assertEquals(1, Regex("data: \\[DONE\\]").findAll(stream).count())
        } finally {
            server.close()
        }
    }

    @Test
    fun loadedModelInventoryUsesTheEngineManagerManifestShape() {
        val service = FakeInferenceService()
        val server = MnnHttpServer(service, TEST_PORT)
        server.start()
        try {
            val unloaded = http(server.localPort, "GET", "/internal/models/loaded")
            assertTrue(unloaded.contains("\"models\":[]"))

            http(server.localPort, "POST", "/v1/chat/completions", chatBody(stream = false))
            val loaded = http(server.localPort, "GET", "/internal/models/loaded")
            assertTrue(loaded.contains("\"models\":[{\"id\":\"qwen3-0.6b\"}]"))
        } finally {
            server.close()
        }
    }

    @Test
    fun clientDisconnectDuringErrorResponseDoesNotKillServer() {
        val server = MnnHttpServer(FakeInferenceService(), TEST_PORT)
        server.start()
        try {
            Socket("127.0.0.1", server.localPort).use { client ->
                client.setSoLinger(true, 0)
                client.getOutputStream().write(requestBytes("GET", "/bad path", ""))
            }
            Thread.sleep(100)
            assertTrue(http(server.localPort, "GET", "/healthz").startsWith("HTTP/1.1 200"))
        } finally {
            server.close()
        }
    }

    @Test
    fun healthRemainsAvailableWhileClientsHoldRequestHeadersOpen() {
        val server = MnnHttpServer(FakeInferenceService(), TEST_PORT)
        server.start()
        val slowClients = List(4) {
            Socket("127.0.0.1", server.localPort).apply {
                getOutputStream().write("GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
        }
        try {
            val health = http(server.localPort, "GET", "/healthz")

            assertTrue("health request was not served: $health", health.startsWith("HTTP/1.1 200"))
        } finally {
            slowClients.forEach(Socket::close)
            server.close()
        }
    }

    @Test
    fun rejectsNewConnectionsWithServiceUnavailableWhenRequestCapacityIsFull() {
        val server = MnnHttpServer(FakeInferenceService(), TEST_PORT)
        server.start()
        val slowClients = List(24) {
            Socket("127.0.0.1", server.localPort).apply {
                getOutputStream().write("GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
        }
        try {
            val rejected = rawHttp(
                server.localPort,
                "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
            )

            assertTrue("server did not report bounded-pool overload: $rejected", rejected.startsWith("HTTP/1.1 503"))
            assertTrue(rejected.contains("\"code\":\"server_busy\""))
        } finally {
            slowClients.forEach(Socket::close)
            server.close()
        }
    }

    @Test
    fun malformedOrAmbiguousContentLengthIsRejected() {
        val server = MnnHttpServer(FakeInferenceService(), TEST_PORT)
        server.start()
        try {
            val requests = listOf(
                "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: nope\r\n\r\n{}",
                "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: +2\r\n\r\n{}",
                "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 2\r\nContent-Length: 0\r\n\r\n{}",
            )

            requests.forEach { request ->
                val response = rawHttp(server.localPort, request)
                assertTrue("malformed framing was accepted: $response", response.startsWith("HTTP/1.1 400"))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun overflowingContentLengthIsRejectedInsteadOfTreatedAsEmptyBody() {
        val server = MnnHttpServer(FakeInferenceService(), TEST_PORT)
        server.start()
        try {
            val response = rawHttp(
                server.localPort,
                "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 9223372036854775808\r\n\r\n",
            )

            assertTrue(response.startsWith("HTTP/1.1 400"))
            assertTrue("overflow was not reported as invalid framing: $response", response.contains("HTTP Content-Length is invalid."))
        } finally {
            server.close()
        }
    }

    @Test
    fun oversizedContentLengthReturnsPayloadTooLarge() {
        val server = MnnHttpServer(FakeInferenceService(), TEST_PORT)
        server.start()
        try {
            val response = rawHttp(
                server.localPort,
                "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 1048577\r\n\r\n",
            )

            assertTrue("oversized body returned unexpected response: $response", response.startsWith("HTTP/1.1 413"))
        } finally {
            server.close()
        }
    }

    @Test
    fun returnsConflictForSecondGenerationAndCancelsOnStreamDisconnect() {
        val service = FakeInferenceService(blockAfterFirstToken = true)
        val server = MnnHttpServer(service, TEST_PORT)
        server.start()
        val firstClient = Socket("127.0.0.1", server.localPort)
        firstClient.setSoLinger(true, 0)
        firstClient.getOutputStream().write(requestBytes("POST", "/v1/chat/completions", chatBody(stream = true)))
        val firstReader = BufferedReader(InputStreamReader(firstClient.getInputStream(), StandardCharsets.UTF_8))
        try {
            assertTrue(firstReader.readLine().orEmpty().contains("200"))
            while (firstReader.readLine()?.isEmpty() == false) Unit
            assertTrue(service.firstToken.await(2, TimeUnit.SECONDS))

            val second = http(server.localPort, "POST", "/v1/chat/completions", chatBody(stream = false))
            assertTrue("second generation response: $second", second.startsWith("HTTP/1.1 409"))
            assertTrue("second generation response: $second", second.contains("\"code\":\"engine_busy\""))

            firstClient.close()
            service.releaseGeneration.countDown()
            assertTrue(service.cancelled.await(3, TimeUnit.SECONDS))
        } finally {
            firstClient.close()
            service.releaseGeneration.countDown()
            server.close()
        }
    }

    @Test
    fun healthRemainsAvailableDuringActiveGeneration() {
        val service = FakeInferenceService(blockAfterFirstToken = true)
        val server = MnnHttpServer(service, TEST_PORT)
        server.start()
        val generationClient = Socket("127.0.0.1", server.localPort)
        generationClient.getOutputStream().write(requestBytes("POST", "/v1/chat/completions", chatBody(stream = true)))
        val generationReader = BufferedReader(InputStreamReader(generationClient.getInputStream(), StandardCharsets.UTF_8))
        try {
            assertTrue(generationReader.readLine().orEmpty().contains("200"))
            assertTrue("generation did not start", service.firstToken.await(2, TimeUnit.SECONDS))

            val health = http(server.localPort, "GET", "/healthz")

            assertTrue("health request was not served during generation: $health", health.startsWith("HTTP/1.1 200"))
        } finally {
            generationClient.close()
            service.releaseGeneration.countDown()
            server.close()
        }
    }

    @Test
    fun healthRemainsAvailableWhenAllInferenceWorkersAreOccupied() {
        val service = FakeInferenceService(blockedGenerationCount = 4)
        val server = MnnHttpServer(service, TEST_PORT)
        server.start()
        val generationClients = List(4) {
            Thread {
                runCatching {
                    http(server.localPort, "POST", "/v1/chat/completions", chatBody(stream = false))
                }
            }.apply { start() }
        }
        try {
            assertTrue("all inference workers did not start", service.generationsStarted.await(2, TimeUnit.SECONDS))

            val health = http(server.localPort, "GET", "/healthz")

            assertTrue("health request was not served while inference workers were occupied: $health", health.startsWith("HTTP/1.1 200"))
        } finally {
            service.releaseGeneration.countDown()
            generationClients.forEach { it.join(2_000) }
            server.close()
        }
    }

    @Test
    fun closeCanBeRetriedAfterAnInterruptedCleanupWaitAndPreventsRestart() {
        val service = FakeInferenceService(blockedGenerationCount = 1)
        val server = MnnHttpServer(service, TEST_PORT)
        server.start()
        val generationClient = Thread {
            runCatching {
                http(server.localPort, "POST", "/v1/chat/completions", chatBody(stream = false))
            }
        }.apply { start() }
        try {
            assertTrue("generation did not start", service.generationsStarted.await(2, TimeUnit.SECONDS))
            Thread.currentThread().interrupt()
            val firstCloseFailure = runCatching(server::close).exceptionOrNull()
            assertNotNull("an interrupted close must report incomplete cleanup", firstCloseFailure)
            assertTrue("close must preserve the interrupted status", Thread.currentThread().isInterrupted)

            service.releaseGeneration.countDown()
            generationClient.join(2_000)
            server.close()
            assertTrue("retry must restore the interrupted status", Thread.currentThread().isInterrupted)

            try {
                server.start()
                throw AssertionError("a closed HTTP server must not restart")
            } catch (_: IllegalStateException) {
                // close is terminal even if its first cleanup pass was interrupted.
            }
        } finally {
            Thread.interrupted()
            service.releaseGeneration.countDown()
            generationClient.join(2_000)
            server.close()
        }
    }

    private fun http(port: Int, method: String, path: String, body: String = ""): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(requestBytes(method, path, body))
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }

    private fun rawHttp(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }

    private fun requestBytes(method: String, path: String, body: String): ByteArray {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val request = "$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
        return request.toByteArray(StandardCharsets.US_ASCII) + bodyBytes
    }

    private fun chatBody(stream: Boolean): String =
        """{"model":"qwen3-0.6b","messages":[{"role":"user","content":"Say hello."}],"stream":$stream}"""

    private class FakeInferenceService(
        private val blockAfterFirstToken: Boolean = false,
        blockedGenerationCount: Int = 0,
        private val available: Boolean = true,
        private val supportsOpenCl: Boolean = true,
        private val generationError: MnnErrorCode? = null,
        private val generationFinishReason: MnnFinishReason = MnnFinishReason.STOP,
        private val generatedChunks: List<String> = listOf("Hello", " world"),
    ) : MnnInferenceService {
        private val model = MnnModelDescriptor("qwen3-0.6b", "/models/qwen3-0.6b/config.json")
        val firstToken = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val generationsStarted = CountDownLatch(blockedGenerationCount)
        private val remainingBlockedGenerations = AtomicInteger(blockedGenerationCount)
        @Volatile var lastRequest: MnnChatRequest? = null
            private set
        @Volatile private var loaded = false

        override fun listModels(): List<MnnModelDescriptor> = listOf(model)

        override fun status(): MnnRuntimeStatus = MnnRuntimeStatus(
            state = if (loaded) MnnEngineState.READY else MnnEngineState.UNLOADED,
            backend = if (loaded) MnnBackend.CPU else null,
            modelId = if (loaded) model.modelId else null,
        )

        override fun health(): MnnHealthStatus = MnnHealthStatus(
            available = available,
            state = if (available) status().state else MnnEngineState.ERROR,
            error = if (available) null else MnnError(MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE, "native unavailable"),
        )

        override fun ensureLoaded(modelId: String, backend: MnnBackend): MnnResult<MnnLoadedModel> {
            if (modelId != model.modelId) return MnnResult.failure(MnnErrorCode.MODEL_NOT_FOUND, "Model not found.")
            if (backend == MnnBackend.OPENCL && !supportsOpenCl) {
                return MnnResult.failure(MnnErrorCode.BACKEND_UNSUPPORTED, "Backend unavailable.")
            }
            loaded = true
            return MnnResult.success(MnnLoadedModel(model, backend))
        }

        override fun generate(
            requestId: Long,
            request: MnnChatRequest,
            onToken: (String) -> Unit,
        ): MnnResult<MnnGenerationResult> {
            if (blockAfterFirstToken && firstToken.count == 0L) {
                return MnnResult.failure(MnnErrorCode.ENGINE_BUSY, "An MNN generation is already active.")
            }
            lastRequest = request
            when (val loadResult = ensureLoaded(request.modelId, MnnBackend.CPU)) {
                is MnnResult.Failure -> return loadResult
                is MnnResult.Success -> Unit
            }
            generationError?.let { return MnnResult.failure(it, "Synthetic inference failure.") }
            if (remainingBlockedGenerations.getAndUpdate { count -> if (count > 0) count - 1 else 0 } > 0) {
                generationsStarted.countDown()
                releaseGeneration.await(30, TimeUnit.SECONDS)
            }
            generatedChunks.forEachIndexed { index, chunk ->
                onToken(chunk)
                if (index == 0) {
                    firstToken.countDown()
                    if (blockAfterFirstToken) releaseGeneration.await(5, TimeUnit.SECONDS)
                }
            }
            return MnnResult.success(MnnGenerationResult(generatedChunks.joinToString(""), MnnRuntimeMetrics(generatedTokens = generatedChunks.size), generationFinishReason))
        }

        override fun cancel(requestId: Long) {
            cancelled.countDown()
        }

        override fun unload(): MnnResult<Unit> {
            loaded = false
            return MnnResult.success(Unit)
        }
    }

    private companion object {
        const val TEST_PORT = 0
    }
}
