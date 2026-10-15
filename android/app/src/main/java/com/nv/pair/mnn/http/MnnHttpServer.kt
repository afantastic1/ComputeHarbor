/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.mnn.http

import android.util.Log
import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.MnnErrorCode
import com.nv.pair.mnn.MnnInferenceService
import com.nv.pair.mnn.MnnResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONException
import org.json.JSONObject

class MnnHttpServer(
    private val inference: MnnInferenceService,
    private val port: Int = DEFAULT_PORT,
) : AutoCloseable {
    private val requestWorkers = boundedExecutor(HTTP_REQUEST_WORKER_COUNT, HTTP_BACKLOG, "pair-mnn-http")
    private val inferenceWorkers = boundedExecutor(INFERENCE_WORKER_COUNT, HTTP_BACKLOG, "pair-mnn-inference-http")
    private val healthWorkers = boundedExecutor(1, HEALTH_QUEUE_CAPACITY, "pair-mnn-health-http")
    private val nextRequestId = AtomicLong()
    private val activeRequests = ConcurrentHashMap.newKeySet<Long>()
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var closeRequested = false

    val localPort: Int
        get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start() {
        check(!closeRequested) { "MNN HTTP server is closed." }
        check(serverSocket == null) { "MNN HTTP server is already started." }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(LOOPBACK, port), HTTP_BACKLOG)
        serverSocket = socket
        acceptThread = Thread({ acceptConnections(socket) }, "pair-mnn-http-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    override fun close() {
        closeRequested = true
        val failures = mutableListOf<Throwable>()
        var interrupted = Thread.interrupted()
        val socket = serverSocket
        serverSocket = null
        if (socket != null) {
            try {
                socket.close()
            } catch (failure: IOException) {
                failures.add(failure)
            }
        }
        clientSockets.toList().forEach(::closeClient)
        activeRequests.toList().forEach(inference::cancel)
        // Native MNN prefill cannot always be interrupted immediately.  Do not
        // make a user-requested PAIR stop wait for an in-flight generation.
        listOf(requestWorkers, inferenceWorkers, healthWorkers).forEach { it.shutdownNow() }
        val currentAcceptThread = acceptThread
        if (currentAcceptThread != null) {
            try {
                currentAcceptThread.join(SHUTDOWN_TIMEOUT_MILLIS)
            } catch (failure: InterruptedException) {
                interrupted = true
                failures.add(failure)
            }
            if (currentAcceptThread.isAlive) {
                failures.add(IllegalStateException("MNN HTTP accept thread did not terminate."))
            } else {
                acceptThread = null
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (failures.isNotEmpty()) {
            throw IllegalStateException("MNN HTTP server cleanup is incomplete; call close() again to retry.", failures.first()).apply {
                failures.drop(1).forEach(::addSuppressed)
            }
        }
    }

    private fun acceptConnections(listener: ServerSocket) {
        while (serverSocket === listener) {
            try {
                val client = listener.accept()
                clientSockets.add(client)
                if (serverSocket !== listener) {
                    closeClient(client)
                    return
                }
                try {
                    requestWorkers.execute { handleClient(client) }
                } catch (_: RejectedExecutionException) {
                    rejectClient(client)
                }
            } catch (_: SocketException) {
                if (serverSocket === listener) return
            }
        }
    }

    private fun handleClient(socket: Socket) {
        var ownershipTransferred = false
        try {
            socket.soTimeout = REQUEST_TIMEOUT_MILLIS
            val request = readRequest(socket.getInputStream())
            ownershipTransferred = route(request, socket)
        } catch (failure: HttpProtocolException) {
            runCatching {
                writeJson(socket, failure.statusCode, OpenAiResponseWriter.error("invalid_request_error", failure.code, failure.message.orEmpty()))
            }
        } catch (failure: OpenAiRequestException) {
            logRequestRejection(failure)
            runCatching {
                writeJson(socket, failure.statusCode, OpenAiResponseWriter.error("invalid_request_error", failure.code, failure.message.orEmpty()))
            }
        } catch (failure: IOException) {
            // A closed client is expected during streaming cancellation.
        } catch (failure: Exception) {
            logInternalFailure(failure)
            runCatching {
                writeJson(socket, HTTP_INTERNAL_SERVER_ERROR, OpenAiResponseWriter.error("server_error", "server_error", "MNN HTTP request failed."))
            }
        } finally {
            if (!ownershipTransferred) closeClient(socket)
        }
    }

    private fun route(request: MnnHttpRequest, socket: Socket): Boolean {
        val path = request.target.substringBefore('?')
        return when {
            request.method == "GET" && path == "/healthz" -> {
                dispatch(socket, healthWorkers) {
                    val health = inference.health()
                    writeJson(
                        socket,
                        if (health.available) HTTP_OK else HTTP_SERVICE_UNAVAILABLE,
                        OpenAiResponseWriter.health(health, inference.status().modelId != null),
                    )
                }
            }
            request.method == "GET" && path == "/v1/models" -> {
                writeJson(socket, HTTP_OK, OpenAiResponseWriter.models(inference.listModels()))
                false
            }
            request.method == "GET" && path == "/internal/models/loaded" -> {
                writeJson(socket, HTTP_OK, OpenAiResponseWriter.loaded(inference.status()))
                false
            }
            request.method == "POST" && path == "/internal/models/load" -> {
                loadModel(request, socket)
                false
            }
            request.method == "POST" && path == "/internal/models/unload" -> {
                unloadModel(socket)
                false
            }
            request.method == "POST" && path == "/v1/chat/completions" -> dispatch(socket, inferenceWorkers) {
                chatCompletion(request, socket)
            }
            path in KNOWN_PATHS -> {
                writeJson(
                    socket,
                    HTTP_METHOD_NOT_ALLOWED,
                    OpenAiResponseWriter.error("invalid_request_error", "method_not_allowed", "The HTTP method is not supported for this path."),
                )
                false
            }
            else -> {
                writeJson(socket, HTTP_NOT_FOUND, OpenAiResponseWriter.error("invalid_request_error", "not_found", "The requested path was not found."))
                false
            }
        }
    }

    private fun dispatch(socket: Socket, executor: ExecutorService, action: () -> Unit): Boolean = try {
        executor.execute {
            try {
                if (!socket.isClosed) action()
            } catch (failure: OpenAiRequestException) {
                logRequestRejection(failure)
                runCatching {
                    writeJson(socket, failure.statusCode, OpenAiResponseWriter.error("invalid_request_error", failure.code, failure.message.orEmpty()))
                }
            } catch (failure: IOException) {
                // A closed client is expected during streaming cancellation.
            } catch (failure: Exception) {
                logInternalFailure(failure)
                runCatching {
                    writeJson(socket, HTTP_INTERNAL_SERVER_ERROR, OpenAiResponseWriter.error("server_error", "server_error", "MNN HTTP request failed."))
                }
            } finally {
                closeClient(socket)
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        rejectClient(socket)
        true
    }

    private fun rejectClient(socket: Socket) {
        runCatching {
            writeJson(
                socket,
                HTTP_SERVICE_UNAVAILABLE,
                OpenAiResponseWriter.error("server_error", "server_busy", "The MNN HTTP server is at capacity."),
            )
        }
        closeClient(socket)
    }

    private fun logRequestRejection(failure: OpenAiRequestException) {
        val field = failure.fieldPath?.let { " field=$it" }.orEmpty()
        val kind = failure.fieldKind?.let { " kind=$it" }.orEmpty()
        runCatching { Log.w(LOG_TAG, "Rejected OpenAI request code=${failure.code}$field$kind") }
    }

    private fun logInferenceFailure(code: MnnErrorCode) {
        runCatching { Log.w(LOG_TAG, "MNN inference failed code=${code.name}") }
    }

    private fun logInternalFailure(failure: Exception) {
        runCatching { Log.e(LOG_TAG, "MNN HTTP request failed type=${failure.javaClass.simpleName}") }
    }

    private fun closeClient(socket: Socket) {
        clientSockets.remove(socket)
        runCatching { socket.close() }
    }

    private fun loadModel(request: MnnHttpRequest, socket: Socket) {
        val body = parseJson(request.body)
        body.keys().forEach { key ->
            if (key != "model" && key != "backend") {
                throw HttpProtocolException(HTTP_BAD_REQUEST, "unsupported_parameter", "The '$key' parameter is not supported.")
            }
        }
        val modelId = when (val value = body.opt("model")) {
            is String -> value
            else -> throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "'model' must be a string.")
        }
        val backendName = body.optString("backend", "cpu")
        val backend = when (backendName) {
            "cpu" -> MnnBackend.CPU
            "opencl" -> MnnBackend.OPENCL
            else -> throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "'backend' must be 'cpu' or 'opencl'.")
        }
        when (val result = inference.ensureLoaded(modelId, backend)) {
            is MnnResult.Success -> writeJson(socket, HTTP_OK, OpenAiResponseWriter.loaded(result.value))
            is MnnResult.Failure -> writeInferenceError(socket, result.error.code, result.error.message)
        }
    }

    private fun unloadModel(socket: Socket) {
        when (val result = inference.unload()) {
            is MnnResult.Success -> writeJson(socket, HTTP_OK, JSONObject().put("unloaded", true))
            is MnnResult.Failure -> writeInferenceError(socket, result.error.code, result.error.message)
        }
    }

    private fun chatCompletion(request: MnnHttpRequest, socket: Socket) {
        val parsed = OpenAiRequestParser.parse(parseJson(request.body))
        val requestId = nextRequestId.incrementAndGet()
        val completionId = "chatcmpl-$requestId"
        val effectiveToolsJson = if (parsed.chat.toolChoice == "none") null else parsed.chat.toolsJson
        val hasTools = !effectiveToolsJson.isNullOrBlank()
        activeRequests.add(requestId)
        try {
            if (parsed.stream) {
                val stream = SseStream(socket.getOutputStream(), completionId, parsed.chat.modelId)
                val generatedText = StringBuilder()
                when (val result = inference.generate(requestId, parsed.chat) { token ->
                    try {
                        if (hasTools) generatedText.append(token) else stream.token(token)
                    } catch (failure: IOException) {
                        inference.cancel(requestId)
                        throw failure
                    }
                }) {
                    is MnnResult.Success -> {
                        val parsedOutput = OpenAiToolCallParser.parse(generatedText.toString(), effectiveToolsJson)
                        if (hasTools && parsedOutput.content != null) stream.token(parsedOutput.content)
                        if (parsedOutput.toolCalls.isNotEmpty()) stream.toolCalls(parsedOutput.toolCalls)
                        stream.finish(
                            result.value.finishReason,
                            result.value.metrics,
                            parsed.includeUsage,
                            if (parsedOutput.toolCalls.isNotEmpty()) "tool_calls" else null,
                        )
                    }
                    is MnnResult.Failure -> {
                        logInferenceFailure(result.error.code)
                        val error = OpenAiResponseWriter.error(errorType(result.error.code), errorCode(result.error.code), result.error.message)
                        if (stream.started) stream.error(error) else writeJson(socket, httpStatus(result.error.code), error)
                    }
                }
            } else {
                val text = StringBuilder()
                when (val result = inference.generate(requestId, parsed.chat) { token -> text.append(token) }) {
                    is MnnResult.Success -> {
                        val output = OpenAiToolCallParser.parse(text.toString(), effectiveToolsJson)
                        writeJson(
                            socket,
                            HTTP_OK,
                            OpenAiResponseWriter.completion(
                                completionId,
                                parsed.chat.modelId,
                                output.content.orEmpty(),
                                result.value.metrics.promptTokens,
                                result.value.metrics.generatedTokens,
                                result.value.finishReason,
                                output.toolCalls,
                            ),
                        )
                    }
                    is MnnResult.Failure -> {
                        logInferenceFailure(result.error.code)
                        writeJson(
                            socket,
                            httpStatus(result.error.code),
                            OpenAiResponseWriter.error(errorType(result.error.code), errorCode(result.error.code), result.error.message),
                        )
                    }
                }
            }
        } finally {
            activeRequests.remove(requestId)
        }
    }

    private fun parseJson(body: ByteArray): JSONObject = try {
        JSONObject(String(body, StandardCharsets.UTF_8))
    } catch (_: JSONException) {
        throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "Request body must be a JSON object.")
    }

    private fun readRequest(input: InputStream): MnnHttpRequest {
        val requestLine = readLine(input, MAX_HEADER_LINE_BYTES)
            ?: throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP request line is missing.")
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts[2] !in SUPPORTED_HTTP_VERSIONS) {
            throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP request line is invalid.")
        }
        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_LINE_BYTES)
                ?: throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP headers are incomplete.")
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) {
                throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP headers are too large.")
            }
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP header is invalid.")
            val rawName = line.substring(0, separator)
            if (rawName != rawName.trim()) {
                throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP header is invalid.")
            }
            val name = rawName.lowercase()
            val value = line.substring(separator + 1).trim()
            if (name.isEmpty() || name.any { it !in 'a'..'z' && it !in '0'..'9' && it !in HEADER_NAME_PUNCTUATION }) {
                throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP header is invalid.")
            }
            if (name == "content-length" && headers.containsKey(name)) {
                throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP Content-Length is ambiguous.")
            }
            headers[name] = value
        }
        if (headers["transfer-encoding"] != null) {
            throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "Transfer-encoded request bodies are not supported.")
        }
        val contentLengthValue = headers["content-length"]
        if (contentLengthValue != null && !CONTENT_LENGTH_PATTERN.matches(contentLengthValue)) {
            throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP Content-Length is invalid.")
        }
        val contentLengthLong = contentLengthValue?.toLongOrNull()
            ?: if (contentLengthValue == null) 0L else {
                throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP Content-Length is invalid.")
            }
        if (contentLengthLong > MAX_BODY_BYTES) {
            throw HttpProtocolException(HTTP_CONTENT_TOO_LARGE, "invalid_request_error", "HTTP request body is too large.")
        }
        val contentLength = contentLengthLong.toInt()
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP request body is incomplete.")
            offset += count
        }
        return MnnHttpRequest(parts[0].uppercase(), parts[1], headers, body)
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val line = ByteArrayOutputStream()
        while (line.size() <= maxBytes) {
            val next = input.read()
            if (next < 0) return if (line.size() == 0) null else line.toString(StandardCharsets.UTF_8.name())
            if (next == '\n'.code) {
                val bytes = line.toByteArray()
                val size = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, size, StandardCharsets.UTF_8)
            }
            line.write(next)
        }
        throw HttpProtocolException(HTTP_BAD_REQUEST, "invalid_request_error", "HTTP line is too long.")
    }

    private fun writeInferenceError(socket: Socket, code: MnnErrorCode, message: String) =
        writeJson(socket, httpStatus(code), OpenAiResponseWriter.error(errorType(code), errorCode(code), message))

    private fun writeJson(socket: Socket, statusCode: Int, json: JSONObject) {
        val body = OpenAiResponseWriter.bytes(json)
        val headers = "HTTP/1.1 $statusCode ${reasonPhrase(statusCode)}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().write(headers.toByteArray(StandardCharsets.US_ASCII))
        socket.getOutputStream().write(body)
        socket.getOutputStream().flush()
    }

    private fun httpStatus(code: MnnErrorCode): Int = when (code) {
        MnnErrorCode.MODEL_NOT_FOUND -> HTTP_NOT_FOUND
        MnnErrorCode.ENGINE_BUSY -> HTTP_CONFLICT
        MnnErrorCode.BACKEND_UNSUPPORTED -> HTTP_UNPROCESSABLE_ENTITY
        MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE -> HTTP_SERVICE_UNAVAILABLE
        MnnErrorCode.INVALID_MODEL_ID, MnnErrorCode.MODEL_NOT_LOADED, MnnErrorCode.INVALID_REQUEST -> HTTP_BAD_REQUEST
        else -> HTTP_INTERNAL_SERVER_ERROR
    }

    private fun errorCode(code: MnnErrorCode): String = when (code) {
        MnnErrorCode.MODEL_NOT_FOUND -> "model_not_found"
        MnnErrorCode.ENGINE_BUSY -> "engine_busy"
        MnnErrorCode.BACKEND_UNSUPPORTED -> "backend_unsupported"
        MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE -> "engine_unavailable"
        MnnErrorCode.INVALID_MODEL_ID, MnnErrorCode.MODEL_NOT_LOADED, MnnErrorCode.INVALID_REQUEST -> "invalid_request_error"
        else -> "server_error"
    }

    private fun errorType(code: MnnErrorCode): String = when (code) {
        MnnErrorCode.INVALID_MODEL_ID,
        MnnErrorCode.BACKEND_UNSUPPORTED,
        MnnErrorCode.INVALID_REQUEST,
        MnnErrorCode.MODEL_NOT_FOUND,
        MnnErrorCode.MODEL_NOT_LOADED -> "invalid_request_error"
        else -> "server_error"
    }

    private fun reasonPhrase(statusCode: Int): String = when (statusCode) {
        HTTP_OK -> "OK"
        HTTP_BAD_REQUEST -> "Bad Request"
        HTTP_NOT_FOUND -> "Not Found"
        HTTP_METHOD_NOT_ALLOWED -> "Method Not Allowed"
        HTTP_CONFLICT -> "Conflict"
        HTTP_CONTENT_TOO_LARGE -> "Content Too Large"
        HTTP_UNPROCESSABLE_ENTITY -> "Unprocessable Entity"
        HTTP_SERVICE_UNAVAILABLE -> "Service Unavailable"
        else -> "Internal Server Error"
    }

    private class HttpProtocolException(
        val statusCode: Int,
        val code: String,
        override val message: String,
    ) : Exception(message)

    private fun boundedExecutor(workerCount: Int, queueCapacity: Int, threadName: String): ExecutorService = ThreadPoolExecutor(
        workerCount,
        workerCount,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        ThreadFactory { task -> Thread(task, threadName).apply { isDaemon = true } },
    )

    companion object {
        private const val LOG_TAG = "PAIR-MNN-HTTP"
        const val DEFAULT_PORT = 14325
        private const val HTTP_REQUEST_WORKER_COUNT = 8
        // One active generation and one waiting request are admitted by the
        // inference service. Extra retries still receive ENGINE_BUSY promptly.
        private const val INFERENCE_WORKER_COUNT = 4
        private const val HEALTH_QUEUE_CAPACITY = 4
        private const val HTTP_BACKLOG = 16
        private const val REQUEST_TIMEOUT_MILLIS = 30_000
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
    private const val SHUTDOWN_TIMEOUT_MILLIS = 1_000L
        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private const val HTTP_CONFLICT = 409
        private const val HTTP_CONTENT_TOO_LARGE = 413
        private const val HTTP_UNPROCESSABLE_ENTITY = 422
        private const val HTTP_INTERNAL_SERVER_ERROR = 500
        private const val HTTP_SERVICE_UNAVAILABLE = 503
        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private val SUPPORTED_HTTP_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")
        private val CONTENT_LENGTH_PATTERN = Regex("[0-9]+")
        private val HEADER_NAME_PUNCTUATION = setOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~')
        private val KNOWN_PATHS = setOf(
            "/healthz", "/v1/models", "/internal/models/loaded", "/internal/models/load",
            "/internal/models/unload", "/v1/chat/completions",
        )
    }
}
