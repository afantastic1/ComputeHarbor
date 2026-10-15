/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

interface ModelSourceAdapter {
    val kind: ModelSourceKind
    fun parseSearchResponse(response: String): List<ModelDescriptor>
    fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<ModelDescriptor>
    fun searchPage(query: String, page: Int, pageSize: Int = DEFAULT_SEARCH_LIMIT): ModelSearchPage
    fun inspect(descriptor: ModelDescriptor): ModelDescriptor
    fun fileUrl(repository: String, revision: String, path: String): URL
    fun accessToken(): String?

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 20
    }
}

fun interface ModelHubHttpTransport {
    fun get(url: URL, bearerToken: String?): String

    fun getResponse(url: URL, bearerToken: String?): ModelHubHttpResponse =
        ModelHubHttpResponse(body = get(url, bearerToken))

    fun getResponse(url: URL, bearerToken: String?, requestHeaders: Map<String, String>): ModelHubHttpResponse =
        getResponse(url, bearerToken)
}

data class ModelHubHttpResponse(
    val body: String,
    val headers: Map<String, String> = emptyMap(),
) {
    fun header(name: String): String? = headers.entries.firstOrNull { (key, _) ->
        key.equals(name, ignoreCase = true)
    }?.value
}

private object UrlConnectionModelHubTransport : ModelHubHttpTransport {
    override fun get(url: URL, bearerToken: String?): String = getResponse(url, bearerToken).body

    override fun getResponse(url: URL, bearerToken: String?): ModelHubHttpResponse =
        getResponse(url, bearerToken, emptyMap())

    override fun getResponse(
        url: URL,
        bearerToken: String?,
        requestHeaders: Map<String, String>,
    ): ModelHubHttpResponse {
        requireTrustedProviderUrl(url)
        val credentialOrigin = origin(url)
        var target = url
        repeat(MAX_PROVIDER_REDIRECTS + 1) { redirectCount ->
            requireTrustedProviderUrl(target)
            val opened = target.openConnection()
            if (opened !is HttpURLConnection) throw IOException("Model catalog endpoint must use HTTPS.")
            val connection = opened
            connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", requestHeaders["Accept"] ?: "application/json")
            requestHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (origin(target) == credentialOrigin) {
                bearerToken?.takeIf(String::isNotBlank)?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            }
            try {
                val status = connection.responseCode
                if (status in PROVIDER_REDIRECTS) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank() || redirectCount == MAX_PROVIDER_REDIRECTS) {
                        throw IOException("Model catalog returned an invalid or excessive redirect chain.")
                    }
                    target = URL(target, location)
                    return@repeat
                }
                if (status !in 200..299) {
                    throw ProviderError(
                        provider = providerIdFor(target),
                        operation = target.path.substringAfterLast('/').ifBlank { "request" },
                        category = providerErrorCategory(status),
                        httpStatus = status,
                        retryable = status == 408 || status == 429 || status >= 500,
                        requestId = connection.getHeaderField("X-Request-ID")?.takeIf(String::isNotBlank),
                    )
                }
                val body = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_PROVIDER_RESPONSE_BYTES) {
                            throw IOException("Model catalog response exceeds the configured size limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                val headers = connection.headerFields.entries.mapNotNull { (name, values) ->
                    name?.let { it to values.orEmpty().joinToString(", ") }
                }.toMap()
                return ModelHubHttpResponse(body, headers)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Model catalog returned an excessive redirect chain.")
    }

    private fun requireTrustedProviderUrl(url: URL) {
        if (url.protocol != "https" || !TRUSTED_PROVIDER_HOSTS.any { host ->
                url.host.equals(host, ignoreCase = true) || url.host.endsWith(".$host", ignoreCase = true)
            }
        ) throw IOException("Model catalog URL is outside the approved HTTPS provider hosts.")
    }

    private fun origin(url: URL): String = "${url.protocol.lowercase()}://${url.host.lowercase()}:${url.port.takeIf { it >= 0 } ?: url.defaultPort}"

    private const val MAX_PROVIDER_REDIRECTS = 5
    private const val MAX_PROVIDER_RESPONSE_BYTES = 8 * 1024 * 1024
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private val PROVIDER_REDIRECTS = 300..399
    private val TRUSTED_PROVIDER_HOSTS = setOf("huggingface.co", "hf.co", "modelscope.cn")
}

private fun providerIdFor(url: URL): ProviderId =
    if (url.host.equals("modelscope.cn", ignoreCase = true) || url.host.endsWith(".modelscope.cn", ignoreCase = true)) {
        ProviderId.MODELSCOPE
    } else {
        ProviderId.HUGGING_FACE
    }

internal fun providerErrorCategory(status: Int): ProviderErrorCategory = when (status) {
    401, 403 -> ProviderErrorCategory.AUTHENTICATION
    404 -> ProviderErrorCategory.NOT_FOUND
    429 -> ProviderErrorCategory.RATE_LIMITED
    in 500..599 -> ProviderErrorCategory.SERVER
    else -> ProviderErrorCategory.INVALID_RESPONSE
}

private inline fun <T> providerCall(provider: ProviderId, operation: String, action: () -> T): T = try {
    action()
} catch (failure: ProviderError) {
    throw failure
} catch (_: JSONException) {
    throw ProviderError(
        provider = provider,
        operation = operation,
        category = ProviderErrorCategory.INVALID_RESPONSE,
        retryable = false,
        detail = "The provider returned an invalid response.",
    )
} catch (failure: IOException) {
    throw ProviderError(
        provider = provider,
        operation = operation,
        category = ProviderErrorCategory.NETWORK,
        retryable = failure !is InterruptedIOException,
        detail = if (failure is InterruptedIOException) "The provider request was cancelled." else "The provider request could not be completed.",
    )
}

class HuggingFaceAdapter(
    private val apiBase: String = HUGGING_FACE_API,
    private val bearerToken: String? = null,
    private val transport: ModelHubHttpTransport = UrlConnectionModelHubTransport,
) : ModelSourceAdapter {
    override val kind = ModelSourceKind.HUGGING_FACE
    override fun accessToken(): String? = bearerToken

    override fun search(query: String, limit: Int): List<ModelDescriptor> {
        require(limit in 1..MAX_SEARCH_LIMIT)
        return providerCall(ProviderId.HUGGING_FACE, "search") {
            val url = URL("$apiBase/models?search=${encode(query)}&author=$MNN_AUTHOR&limit=$limit")
            transport.get(url, bearerToken).let(::parseSearchResponse)
        }
    }

    override fun searchPage(query: String, page: Int, pageSize: Int): ModelSearchPage {
        require(page in 1..MAX_SEARCH_PAGE)
        require(pageSize in 1..MAX_SEARCH_LIMIT)
        return providerCall(ProviderId.HUGGING_FACE, "search") {
            val firstPageUrl = URL("$apiBase/models?search=${encode(query)}&author=$MNN_AUTHOR&limit=$pageSize")
            var response = transport.getResponse(firstPageUrl, bearerToken)
            var reachedPage = 1
            while (reachedPage < page) {
                val nextUrl = nextPageUrl(response.header("Link")) ?: break
                response = transport.getResponse(URL(nextUrl), bearerToken)
                reachedPage++
            }
            if (reachedPage != page) {
                modelSearchPage(emptyList(), page, pageSize, hasMore = false)
            } else {
                val models = parseSearchResponse(response.body)
                modelSearchPage(models, page, pageSize, hasMore = nextPageUrl(response.header("Link")) != null)
            }
        }
    }

    override fun fileUrl(repository: String, revision: String, path: String): URL = URL(
        "https://huggingface.co/${repository.split('/').joinToString("/") { encode(it) }}/resolve/${encode(revision)}/${encodePath(path)}",
    )

    override fun inspect(descriptor: ModelDescriptor): ModelDescriptor {
        require(descriptor.source.kind == kind) { "The model belongs to a different provider." }
        return providerCall(ProviderId.HUGGING_FACE, "inspect") {
            val repository = descriptor.source.repository.split('/').joinToString("/") { encode(it) }
            val detailsUrl = URL("$apiBase/models/$repository?blobs=true")
            val details = JSONObject(transport.get(detailsUrl, bearerToken))
            val revision = details.optString("sha").takeIf { value -> IMMUTABLE_COMMIT.matches(value) }
                ?: throw IOException("Hugging Face did not return an immutable repository revision.")
            val files = huggingFaceFiles(details.optJSONArray("siblings") ?: JSONArray())
            inspectMnnPackage(descriptor, revision, files) { path ->
                transport.get(fileUrl(descriptor.source.repository, revision, path), bearerToken)
            }
        }
    }

    override fun parseSearchResponse(response: String): List<ModelDescriptor> {
        val rows = JSONArray(response)
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let(::descriptor)
        }
    }

    private fun descriptor(row: JSONObject): ModelDescriptor? {
        val repository = row.optString("id").trim().takeIf { value ->
            value.substringBefore('/').equals(MNN_AUTHOR, ignoreCase = true) && value.contains('/')
        } ?: return null
        val tags = strings(row.optJSONArray("tags"))
        val files = row.optJSONArray("siblings")?.let(::huggingFaceFiles).orEmpty()
        return modelDescriptor(
            kind = kind,
            repository = repository,
            displayName = repository.substringAfterLast('/'),
            description = row.optString("pipeline_tag"),
            tags = tags,
            files = files,
        )
    }

    private fun huggingFaceFiles(rows: JSONArray): List<ModelFile> = (0 until rows.length()).mapNotNull { index ->
        val file = rows.optJSONObject(index) ?: return@mapNotNull null
        val path = file.optString("rfilename").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val size = file.optLong("size", -1L).takeIf { it >= 0L }
        val lfs = file.optJSONObject("lfs")
        val hash = (lfs?.optString("oid") ?: file.optString("oid")).removePrefix("sha256:").takeIf(::isSha256)
        ModelFile(path, size, hash)
    }

    companion object {
        private const val HUGGING_FACE_API = "https://huggingface.co/api"
        private const val MAX_SEARCH_LIMIT = 100
        private const val MAX_SEARCH_PAGE = 100
private const val MNN_AUTHOR = "taobao-mnn"
private val IMMUTABLE_COMMIT = Regex("(?i)(?:[0-9a-f]{40}|[0-9a-f]{64})")
    }
}

class ModelScopeAdapter(
    private val endpointBase: String = MODELSCOPE_ENDPOINT,
    private val bearerToken: String? = null,
    private val transport: ModelHubHttpTransport = UrlConnectionModelHubTransport,
) : ModelSourceAdapter {
    override val kind = ModelSourceKind.MODELSCOPE
    override fun accessToken(): String? = bearerToken

    override fun search(query: String, limit: Int): List<ModelDescriptor> {
        require(limit in 1..MAX_SEARCH_LIMIT)
        return providerCall(ProviderId.MODELSCOPE, "search") {
            val url = URL("$endpointBase/openapi/v1/models?search=${encode(query)}&owner=$MNN_OWNER&page_size=$limit")
            transport.get(url, bearerToken).let(::parseSearchResponse)
        }
    }

    override fun searchPage(query: String, page: Int, pageSize: Int): ModelSearchPage {
        require(pageSize in 1..MAX_SEARCH_LIMIT)
        require(page in 1..(MAX_SEARCH_RESULTS / pageSize))
        return providerCall(ProviderId.MODELSCOPE, "search") {
            val url = URL(
                "$endpointBase/openapi/v1/models?search=${encode(query)}&owner=$MNN_OWNER&page_number=$page&page_size=$pageSize",
            )
            val response = JSONObject(transport.get(url, bearerToken))
            val data = response.optJSONObject("Data") ?: response.optJSONObject("data") ?: JSONObject()
            val models = parseSearchResponse(response.toString())
            val total = data.optInt("TotalCount", data.optInt("total_count", -1))
            val hasMore = if (total >= 0) page * pageSize < total else models.size >= pageSize
            modelSearchPage(models, page, pageSize, hasMore)
        }
    }

    override fun fileUrl(repository: String, revision: String, path: String): URL = URL(
        "https://modelscope.cn/models/${repository.split('/').joinToString("/") { encode(it) }}/resolve/${encode(revision)}/${encodePath(path)}",
    )

    override fun inspect(descriptor: ModelDescriptor): ModelDescriptor {
        require(descriptor.source.kind == kind) { "The model belongs to a different provider." }
        return providerCall(ProviderId.MODELSCOPE, "inspect") {
            val snapshot = resolveImmutableSnapshot(descriptor.source.repository, descriptor.source.revision)
            inspectMnnPackage(descriptor, snapshot.revision, snapshot.files) { path ->
                transport.get(fileUrl(descriptor.source.repository, snapshot.revision, path), bearerToken)
            }
        }
    }

    private fun resolveImmutableSnapshot(repository: String, revision: String): ModelScopeFileSnapshot {
        if (isImmutableRevision(revision)) {
            return ModelScopeFileSnapshot(revision, listFiles(repository, revision))
        }

        val repoPath = repository.split('/').joinToString("/") { encode(it) }
        val revisionsUrl = URL("$endpointBase/api/v1/models/$repoPath/revisions")
        val root = JSONObject(transport.get(revisionsUrl, bearerToken))
        val data = root.optJSONObject("Data") ?: root.optJSONObject("data")
            ?: throw invalidRevisionResponse("ModelScope revision response has no data object.")
        val revisionMap = data.optJSONObject("RevisionMap") ?: data.optJSONObject("revision_map")
            ?: throw invalidRevisionResponse("ModelScope revision response has no revision map.")
        val branches = revisionMap.optJSONArray("Branches") ?: revisionMap.optJSONArray("branches") ?: JSONArray()
        val tags = revisionMap.optJSONArray("Tags") ?: revisionMap.optJSONArray("tags") ?: JSONArray()
        val matchingRevision = (0 until branches.length()).asSequence().mapNotNull(branches::optJSONObject)
            .plus((0 until tags.length()).asSequence().mapNotNull(tags::optJSONObject))
            .firstOrNull { item ->
                item.optString("Revision").ifBlank { item.optString("revision") } == revision
            } ?: throw invalidRevisionResponse("ModelScope did not return the requested revision.")
        val commit = resolveGitRevision(repository, revision)
        return ModelScopeFileSnapshot(commit, listFiles(repository, commit))
    }

    private fun resolveGitRevision(repository: String, revision: String): String {
        val repoPath = repository.split('/').joinToString("/") { encode(it) }
        val url = URL("$endpointBase/$repoPath.git/info/refs?service=git-upload-pack")
        val response = transport.getResponse(url, bearerToken, GIT_ADVERTISEMENT_HEADERS).body
        val refs = parseGitAdvertisement(response)
        val commit = refs["refs/heads/$revision"]
            ?: refs["refs/tags/$revision^{}"]
            ?: refs["refs/tags/$revision"]
        return commit?.takeIf(::isImmutableRevision)
            ?: throw invalidRevisionResponse("ModelScope did not advertise an immutable commit for the requested revision.")
    }

    private fun parseGitAdvertisement(response: String): Map<String, String> {
        val refs = mutableMapOf<String, String>()
        var offset = 0
        while (offset < response.length) {
            if (offset + PACKET_LENGTH_WIDTH > response.length) {
                throw invalidRevisionResponse("ModelScope returned a truncated git reference advertisement.")
            }
            val packetLength = response.substring(offset, offset + PACKET_LENGTH_WIDTH).toIntOrNull(16)
                ?: throw invalidRevisionResponse("ModelScope returned an invalid git reference advertisement.")
            offset += PACKET_LENGTH_WIDTH
            if (packetLength == PACKET_RESPONSE_END) break
            if (packetLength == PACKET_FLUSH || packetLength == PACKET_DELIMITER) continue
            val payloadLength = packetLength - PACKET_LENGTH_WIDTH
            if (payloadLength <= 0 || offset + payloadLength > response.length) {
                throw invalidRevisionResponse("ModelScope returned a truncated git reference advertisement.")
            }
            val payload = response.substring(offset, offset + payloadLength)
            offset += payloadLength
            val fields = payload.substringBefore('\u0000').trim().split(WHITESPACE)
            if (fields.size >= MINIMUM_GIT_REF_FIELDS && isImmutableRevision(fields[0])) {
                refs[fields[1]] = fields[0]
            }
        }
        return refs
    }

    private fun invalidRevisionResponse(message: String): ProviderError = ProviderError(
        provider = ProviderId.MODELSCOPE,
        operation = "resolve-revision",
        category = ProviderErrorCategory.INVALID_RESPONSE,
        retryable = false,
        detail = message,
    )

    private fun listFiles(repository: String, revision: String): List<ModelFile> {
        val repoPath = repository.split('/').joinToString("/") { encode(it) }
        val url = URL("$endpointBase/api/v1/models/$repoPath/repo/files?Revision=${encode(revision)}&Recursive=true")
        return transport.get(url, bearerToken).let { response ->
            val root = JSONObject(response)
            val data = root.optJSONObject("Data") ?: root.optJSONObject("data") ?: JSONObject()
            val rows = data.optJSONArray("Files") ?: data.optJSONArray("files")
                ?: data.optJSONArray("FileList") ?: data.optJSONArray("file_list") ?: JSONArray()
            modelScopeFiles(rows)
        }
    }

    override fun parseSearchResponse(response: String): List<ModelDescriptor> {
        val root = JSONObject(response)
        val data = root.optJSONObject("Data") ?: root.optJSONObject("data") ?: JSONObject()
        val rows = data.optJSONArray("Models") ?: data.optJSONArray("models") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let(::descriptor)
        }
    }

    private fun descriptor(row: JSONObject): ModelDescriptor? {
        val repository = (row.optString("Path").ifBlank { row.optString("path") }.ifBlank { row.optString("id") })
            .trim().takeIf { value ->
                value.substringBefore('/').equals(MNN_OWNER, ignoreCase = true) && value.contains('/')
            } ?: return null
        val tags = strings(row.optJSONArray("Tags") ?: row.optJSONArray("tags"))
        val files = modelScopeFiles(row.optJSONArray("Files") ?: row.optJSONArray("files"))
        return modelDescriptor(
            kind = kind,
            repository = repository,
            displayName = row.optString("Name").ifBlank { row.optString("name") }.ifBlank { repository.substringAfterLast('/') },
            description = row.optString("Description").ifBlank { row.optString("description") },
            tags = tags,
            files = files,
        )
    }

    private fun modelScopeFiles(rows: JSONArray?): List<ModelFile> = rows?.let { files ->
        (0 until files.length()).mapNotNull { index ->
            val file = files.optJSONObject(index) ?: return@mapNotNull null
            val path = (file.optString("Name").ifBlank { file.optString("name") }
                .ifBlank { file.optString("Path").ifBlank { file.optString("path") } }
                .ifBlank { file.optString("FileName").ifBlank { file.optString("file_name") } })
                .trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val size = file.optLong("Size", file.optLong("size", -1L)).takeIf { it >= 0L }
            val hash = file.optString("Sha256").ifBlank { file.optString("sha256") }
                .removePrefix("sha256:").lowercase().takeIf(::isSha256)
            ModelFile(path, size, hash)
        }
    }.orEmpty()

    companion object {
        private const val MODELSCOPE_ENDPOINT = "https://modelscope.cn"
        // The ModelScope Git route serves its ref advertisement to Git clients.
        private const val GIT_USER_AGENT = "git/2.37.1"
        private const val PACKET_LENGTH_WIDTH = 4
        private const val PACKET_FLUSH = 0
        private const val PACKET_DELIMITER = 1
        private const val PACKET_RESPONSE_END = 2
        private const val MINIMUM_GIT_REF_FIELDS = 2
        private const val MAX_SEARCH_LIMIT = 50
        private const val MAX_SEARCH_RESULTS = 3_000
        private const val MNN_OWNER = "MNN"
        private val WHITESPACE = Regex("\\s+")
        private val GIT_ADVERTISEMENT_HEADERS = mapOf(
            "Accept" to "application/x-git-upload-pack-advertisement",
            "User-Agent" to GIT_USER_AGENT,
        )
    }

    private data class ModelScopeFileSnapshot(
        val revision: String,
        val files: List<ModelFile>,
    )
}

private fun modelDescriptor(
    kind: ModelSourceKind,
    repository: String,
    displayName: String,
    description: String,
    tags: List<String>,
    files: List<ModelFile>,
): ModelDescriptor {
    val searchable = "$repository $displayName $description ${tags.joinToString(" ")}"
    val format = when {
        files.any { it.path.endsWith(".mnn", ignoreCase = true) } -> ModelFormat.MNN
        files.any { it.path.endsWith(".gguf", ignoreCase = true) } -> ModelFormat.GGUF
        files.any { it.path.endsWith(".onnx", ignoreCase = true) } -> ModelFormat.ONNX
        else -> ModelFormat.UNKNOWN
    }
    val parameters = PARAMETER_PATTERN.find(searchable)?.groupValues?.get(1)?.toDoubleOrNull()
        ?.let { (it * PARAMETERS_PER_BILLION).toLong() }
    val quantization = QUANTIZATION_PATTERN.find(searchable)?.value
    val context = CONTEXT_PATTERN.find(searchable)?.groupValues?.get(1)?.toIntOrNull()
    val memory = parameters?.let { (it * BYTES_PER_PARAMETER).toLong() }
    return ModelDescriptor(
        logicalId = "${kind.name.lowercase()}:$repository",
        engineModelId = repository,
        displayName = displayName,
        family = familyFor(searchable),
        parameterCount = parameters,
        quantization = quantization,
        contextLength = context,
        source = ModelSource(kind, repository),
        format = format,
        estimatedMemoryBytes = memory,
        compatibility = if (format == ModelFormat.MNN) ModelCompatibility.COMPATIBLE else ModelCompatibility.UNKNOWN,
        files = files,
        description = description,
        tags = tags,
    )
}

private fun ModelDescriptor.toRemoteSummary(): RemoteModelSummary = RemoteModelSummary(
    provider = when (source.kind) {
        ModelSourceKind.MODELSCOPE -> ProviderId.MODELSCOPE
        ModelSourceKind.HUGGING_FACE -> ProviderId.HUGGING_FACE
        else -> error("Local models do not have a remote provider summary.")
    },
    repository = source.repository,
    displayName = displayName,
    description = description,
    tags = tags,
)

private fun modelSearchPage(
    descriptors: List<ModelDescriptor>,
    page: Int,
    pageSize: Int,
    hasMore: Boolean,
): ModelSearchPage = ModelSearchPage(
    descriptors = descriptors,
    providerPage = RemoteModelPage(
        models = descriptors.map(ModelDescriptor::toRemoteSummary),
        page = page,
        pageSize = pageSize,
        hasMore = hasMore,
    ),
)

private fun inspectMnnPackage(
    descriptor: ModelDescriptor,
    revision: String,
    files: List<ModelFile>,
    fetchText: (String) -> String,
): ModelDescriptor {
    require(files.map(ModelFile::path).distinct().size == files.size) {
        "The provider returned duplicate artifact paths."
    }
    require(files.all { isSafeArtifactPath(it.path) }) {
        "The provider returned an unsafe artifact path."
    }
    val configFile = files.singleOrNull { it.path == "config.json" }
        ?: return descriptor.copy(
            source = descriptor.source.copy(revision = revision),
            files = files,
            compatibility = ModelCompatibility.INCOMPATIBLE,
        )
    val configText = fetchText(configFile.path)
    require(configText.toByteArray(Charsets.UTF_8).size <= MAX_MODEL_CONFIG_BYTES) {
        "The model config exceeds the 1 MiB inspection limit."
    }
    val config = JSONObject(configText)
    val modelPath = config.optString("llm_model").takeIf(String::isNotBlank)
        ?: return descriptor.copy(source = descriptor.source.copy(revision = revision), files = files, compatibility = ModelCompatibility.INCOMPATIBLE)
    val llmConfigPath = config.optString("llm_config").ifBlank { "llm_config.json" }
    require(isSafeArtifactPath(llmConfigPath)) { "The model config contains an unsafe artifact path." }
    val required = mutableListOf(
        llmConfigPath,
        modelPath,
        config.optString("llm_weight").ifBlank { "llm.mnn.weight" },
        config.optString("tokenizer_file").ifBlank { "tokenizer.txt" },
    )
    val filesByPath = files.associateBy(ModelFile::path)
    val llmConfig = filesByPath[llmConfigPath]?.let { JSONObject(fetchText(llmConfigPath)) }
    if (config.optBoolean("is_visual", llmConfig?.optBoolean("is_visual", false) ?: false)) {
        required += config.optString("visual_model")
            .ifBlank { llmConfig?.optString("visual_model").orEmpty() }
            .ifBlank { "visual.mnn" }
        required += config.optString("vision_weight")
            .ifBlank { llmConfig?.optString("vision_weight").orEmpty() }
            .ifBlank { "visual.mnn.weight" }
    }
    val requiredPaths = required.distinct()
    requiredPaths.forEach { path -> require(isSafeArtifactPath(path)) { "The model config contains an unsafe artifact path." } }
    val allPresent = requiredPaths.all(filesByPath::containsKey)
    val format = if (modelPath.endsWith(".mnn", ignoreCase = true)) ModelFormat.MNN else ModelFormat.UNKNOWN
    return descriptor.copy(
        source = descriptor.source.copy(revision = revision),
        files = files,
        format = format,
        compatibility = if (allPresent && format == ModelFormat.MNN) ModelCompatibility.COMPATIBLE else ModelCompatibility.INCOMPATIBLE,
        requiredArtifactPaths = requiredPaths,
    )
}

private fun isSafeArtifactPath(path: String): Boolean = path.isNotBlank() &&
    '\\' !in path && '\u0000' !in path && ':' !in path && !path.startsWith('/') &&
    path.split('/').all { segment -> segment.isNotBlank() && segment != "." && segment != ".." && SAFE_SEGMENT.matches(segment) }

private fun familyFor(value: String): String = FAMILY_PATTERN.find(value)?.value?.lowercase() ?: "unknown"

private fun strings(values: JSONArray?): List<String> = values?.let { array ->
    (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
}.orEmpty()

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun encodePath(value: String): String = value.split('/').joinToString("/") { encode(it) }

private fun nextPageUrl(linkHeader: String?): String? = linkHeader?.let { header ->
    NEXT_LINK.find(header)?.groupValues?.get(1)
}

private fun isSha256(value: String): Boolean = value.matches(Regex("(?i)[0-9a-f]{64}"))
private fun isImmutableRevision(value: String): Boolean = value.matches(Regex("(?i)(?:[0-9a-f]{40}|[0-9a-f]{64})"))

private const val NETWORK_TIMEOUT_MILLIS = 15_000
private const val MAX_MODEL_CONFIG_BYTES = 1024 * 1024
private const val PARAMETERS_PER_BILLION = 1_000_000_000.0
private const val BYTES_PER_PARAMETER = 2.4
private val PARAMETER_PATTERN = Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*[- ]?B(?:\\b|$)")
private val QUANTIZATION_PATTERN = Regex("(?i)(?:Q[234568]_[A-Z0-9_]+|INT[248])")
private val CONTEXT_PATTERN = Regex("(?i)(?:context|ctx)[-_ ]?([0-9]{3,7})")
private val FAMILY_PATTERN = Regex("(?i)qwen|llama|gemma|mistral|deepseek|phi|smollm|internlm|glm")
private val SAFE_SEGMENT = Regex("(?:[A-Za-z0-9][A-Za-z0-9._-]{0,255}|\\.[A-Za-z0-9][A-Za-z0-9._-]{0,254})")
private val NEXT_LINK = Regex("(?i)<([^>]+)>\\s*;[^,]*\\brel\\s*=\\s*\"?next\"?")
