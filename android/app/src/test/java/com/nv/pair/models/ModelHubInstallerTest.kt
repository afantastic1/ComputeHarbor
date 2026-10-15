/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.net.InetAddress
import java.io.InterruptedIOException
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelHubInstallerTest {
    private companion object {
        const val INSTALLED_MODEL_ID = "Qwen3-0.6B-MNN-53d1cbe871c27025"
    }
    @Test
    fun storageIdsRemainDistinctForRepositoriesWithTheSameModelName() {
        val first = catalogDescriptor(mnnArtifacts())
        val second = first.copy(
            logicalId = "hugging_face:another-owner/Qwen3-0.6B-MNN",
            engineModelId = "another-owner/Qwen3-0.6B-MNN",
            source = first.source.copy(repository = "another-owner/Qwen3-0.6B-MNN"),
        )

        val firstInstalledId = ModelHubInstaller.installedModelIdFor(first)
        val secondInstalledId = ModelHubInstaller.installedModelIdFor(second)

        assertTrue(firstInstalledId != secondInstalledId)
        assertTrue(firstInstalledId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        assertTrue(secondInstalledId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
    }

    @Test
    fun installRecoversWhenOnlyTheInitialTaskManifestTemporaryFileRemains() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-manifest-recovery").toFile()
        val staging = root.resolve(".$INSTALLED_MODEL_ID.downloading")
        assertTrue(staging.mkdir())
        staging.resolve(".pair-download.json.tmp").writeText("incomplete")
        try {
            val installed = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(catalogDescriptor(artifacts), localHttpAdapter(server.port))

            assertEquals(INSTALLED_MODEL_ID, installed.engineModelId)
            assertTrue(root.resolve("$INSTALLED_MODEL_ID/.pair-model.json").isFile)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun installationResumesPartialArtifactsAfterInstallerReconstruction() {
        val artifacts = mnnArtifacts().toMutableMap()
        artifacts["llm.mnn.weight"] = ByteArray(256 * 1024) { index -> (index % 251).toByte() }
        val server = ArtifactServer(artifacts, expectedRequests = 6)
        val root = Files.createTempDirectory("pair-model-resume").toFile()
        val descriptor = catalogDescriptor(artifacts)
        var interruptedAtBytes = 0L
        try {
            val firstFailure = runCatching {
                ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                    .installMnnModel(descriptor, localHttpAdapter(server.port)) { progress ->
                        if (progress.artifactPath == "llm.mnn.weight" && progress.artifactBytesReceived > 0L) {
                            interruptedAtBytes = progress.artifactBytesReceived
                            throw InterruptedIOException("Simulated process interruption.")
                        }
                    }
            }.exceptionOrNull()

            assertTrue(firstFailure is InterruptedIOException)
            val staging = root.resolve(".$INSTALLED_MODEL_ID.downloading")
            val partialWeight = staging.resolve("llm.mnn.weight.part")
            assertTrue(staging.isDirectory)
            assertTrue(partialWeight.length() in 1 until artifacts.getValue("llm.mnn.weight").size.toLong())

            val installed = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(descriptor, localHttpAdapter(server.port))

            assertEquals(INSTALLED_MODEL_ID, installed.engineModelId)
            assertTrue(artifacts.getValue("llm.mnn.weight").contentEquals(root.resolve("$INSTALLED_MODEL_ID/llm.mnn.weight").readBytes()))
            assertEquals(
                "unexpected resume request sequence",
                listOf(null, null, null, null, "bytes=$interruptedAtBytes-", null),
                server.requestedRanges.toList(),
            )
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun successfulInstallPublishesAtomicallyUnderThePrivateModelRoot() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-hub").toFile()
        try {
            val descriptor = catalogDescriptor(artifacts)
            val installed = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(descriptor, localHttpAdapter(server.port))

            assertEquals(INSTALLED_MODEL_ID, installed.engineModelId)
            assertEquals(ModelSourceKind.LOCAL, installed.source.kind)
            assertTrue(root.resolve("$INSTALLED_MODEL_ID/config.json").isFile)
            assertTrue(root.resolve("$INSTALLED_MODEL_ID/llm.mnn.weight").isFile)
            assertTrue(root.resolve("$INSTALLED_MODEL_ID/.pair-model.json").isFile)
            assertTrue(root.resolve(".$INSTALLED_MODEL_ID.downloading").exists().not())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun successfulVisualModelInstallIncludesVisualArtifacts() {
        val artifacts = visualMnnArtifacts()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-visual-model-hub").toFile()
        val descriptor = catalogDescriptor(artifacts).copy(
            requiredArtifactPaths = listOf(
                "llm_config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt", "visual.mnn", "visual.mnn.weight",
            ),
        )
        try {
            ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(descriptor, localHttpAdapter(server.port))

            assertTrue(root.resolve("$INSTALLED_MODEL_ID/visual.mnn").isFile)
            assertTrue(root.resolve("$INSTALLED_MODEL_ID/visual.mnn.weight").isFile)
            assertEquals(7, server.requestedPaths.size)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun installReplacesAnEarlierIncompleteModelPackage() {
        val basicArtifacts = mnnArtifacts()
        val visualArtifacts = visualMnnArtifacts()
        val server = ArtifactServer(visualArtifacts, expectedRequests = 7)
        val root = Files.createTempDirectory("pair-incomplete-model-repair").toFile()
        val basicDescriptor = catalogDescriptor(basicArtifacts)
        val visualDescriptor = catalogDescriptor(visualArtifacts).copy(
            requiredArtifactPaths = listOf(
                "llm_config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt", "visual.mnn", "visual.mnn.weight",
            ),
        )
        try {
            val oldDirectory = root.resolve(INSTALLED_MODEL_ID)
            oldDirectory.mkdirs()
            basicArtifacts.forEach { (path, content) -> oldDirectory.resolve(path).writeBytes(content) }

            ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(visualDescriptor, localHttpAdapter(server.port))

            assertTrue(oldDirectory.resolve("visual.mnn").isFile)
            assertTrue(oldDirectory.resolve("visual.mnn.weight").isFile)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun existingLegacyModelDirectoryRemainsUsableWithoutAutomaticRename() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-legacy-id").toFile()
        val descriptor = catalogDescriptor(artifacts)
        val legacyModelId = descriptor.engineModelId.substringAfterLast('/')
        try {
            ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(descriptor, localHttpAdapter(server.port))
            Files.move(root.resolve(INSTALLED_MODEL_ID).toPath(), root.resolve(legacyModelId).toPath())

            val installed = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(descriptor, localHttpAdapter(server.port))

            assertEquals(legacyModelId, installed.engineModelId)
            assertTrue(root.resolve(legacyModelId).isDirectory)
            assertTrue(root.resolve(INSTALLED_MODEL_ID).exists().not())
            assertEquals("legacy recovery must not redownload artifacts", 5, server.requestedPaths.size)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun retryForAnAlreadyPublishedSourceCompletesIdempotently() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-hub-retry").toFile()
        val descriptor = catalogDescriptor(artifacts)
        try {
            val installer = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
            installer.installMnnModel(descriptor, localHttpAdapter(server.port))
            writeDownloadTask(root.resolve(INSTALLED_MODEL_ID), descriptor, "PUBLISHING")

            val retried = installer.installMnnModel(descriptor, localHttpAdapter(server.port))

            assertEquals(INSTALLED_MODEL_ID, retried.engineModelId)
            assertEquals(5, server.requestedPaths.size)
            assertTrue(root.resolve("$INSTALLED_MODEL_ID/.pair-model.json").isFile)
            assertTrue(root.resolve("$INSTALLED_MODEL_ID/.pair-download.json").exists().not())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun completedArtifactReceiptMustStillMatchProviderDeclaredSize() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts, expectedRequests = 7)
        val root = Files.createTempDirectory("pair-model-size-recheck").toFile()
        val descriptor = catalogDescriptor(artifacts)
        try {
            val installer = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
            val interrupted = runCatching {
                installer.installMnnModel(descriptor, localHttpAdapter(server.port)) { progress ->
                    if (progress.artifactPath == "tokenizer.txt" && progress.artifactBytesReceived == 0L) {
                        throw InterruptedIOException("Stop after the preceding artifact receipts are persisted.")
                    }
                }
            }.exceptionOrNull()
            assertTrue(interrupted is InterruptedIOException)

            val descriptorWithWrongWeightSize = descriptor.copy(
                files = descriptor.files.map { artifact ->
                    if (artifact.path == "llm.mnn.weight") artifact.copy(sizeBytes = artifact.sizeBytes!! - 1L) else artifact
                },
            )
            val failure = runCatching {
                installer.installMnnModel(descriptorWithWrongWeightSize, localHttpAdapter(server.port))
            }.exceptionOrNull()

            assertTrue("a receipt must not override the provider's expected size", failure is java.io.IOException)
            assertTrue(root.resolve(INSTALLED_MODEL_ID).exists().not())
            assertEquals(6, server.requestedPaths.size)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedStagingRecoversAndPublishesWithoutDownloadingAgain() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-verified-stage").toFile()
        val descriptor = catalogDescriptor(artifacts)
        val staging = root.resolve(".$INSTALLED_MODEL_ID.downloading")
        try {
            assertTrue(staging.mkdir())
            artifacts.forEach { (path, content) -> staging.resolve(path).writeBytes(content) }
            staging.resolve(".pair-model.json").writeText(provenanceManifest(descriptor, artifacts).toString(2))
            writeDownloadTask(staging, descriptor, "VERIFIED")

            val installed = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(descriptor, localHttpAdapter(server.port))

            assertEquals(INSTALLED_MODEL_ID, installed.engineModelId)
            assertTrue(root.resolve("$INSTALLED_MODEL_ID/llm.mnn.weight").isFile)
            assertTrue(root.resolve(".$INSTALLED_MODEL_ID.downloading").exists().not())
            assertTrue("verified recovery must not fetch artifacts", server.requestedPaths.isEmpty())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun completeLegacyStagingWithProvenanceRecoversMissingTaskManifest() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-legacy-stage").toFile()
        val descriptor = catalogDescriptor(artifacts)
        val staging = root.resolve(".$INSTALLED_MODEL_ID.downloading")
        try {
            assertTrue(staging.mkdir())
            artifacts.forEach { (path, content) -> staging.resolve(path).writeBytes(content) }
            staging.resolve(".pair-model.json").writeText(provenanceManifest(descriptor, artifacts).toString(2))

            val installed = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                .installMnnModel(descriptor, localHttpAdapter(server.port))

            assertEquals(INSTALLED_MODEL_ID, installed.engineModelId)
            assertTrue(root.resolve("$INSTALLED_MODEL_ID/.pair-model.json").isFile)
            assertTrue(server.requestedPaths.isEmpty())
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun failedInstallLeavesNoFinalModelDirectory() {
        val artifacts = mnnArtifacts().toMutableMap()
        artifacts["llm.mnn.weight"] = "tampered".toByteArray()
        val server = ArtifactServer(artifacts)
        val root = Files.createTempDirectory("pair-model-hub").toFile()
        try {
            val descriptor = catalogDescriptor(mnnArtifacts())
            val failure = runCatching {
                ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
                    .installMnnModel(descriptor, localHttpAdapter(server.port))
            }.exceptionOrNull()

            assertTrue(failure is java.io.IOException)
            assertTrue(root.resolve(INSTALLED_MODEL_ID).exists().not())
            assertTrue(root.resolve(".$INSTALLED_MODEL_ID.downloading").isDirectory)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun missingRequiredArtifactHashRequiresExplicitOptInAndRecordsLocalDigest() {
        val artifacts = mnnArtifacts()
        val server = ArtifactServer(artifacts, expectedRequests = 5)
        val root = Files.createTempDirectory("pair-model-hub").toFile()
        try {
            val descriptor = catalogDescriptor(artifacts).copy(
                files = catalogDescriptor(artifacts).files.map { file ->
                    if (file.path == "llm.mnn.weight") file.copy(sha256 = null) else file
                },
            )
            val installer = ModelHubInstaller(root, ResumableModelDownloader(allowLoopbackHttpForTests = true))
            val failure = runCatching { installer.installMnnModel(descriptor, localHttpAdapter(server.port)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(root.listFiles().orEmpty().isEmpty())

            installer.installMnnModel(descriptor, localHttpAdapter(server.port), allowUnverifiedSource = true)
            val manifest = org.json.JSONObject(root.resolve("$INSTALLED_MODEL_ID/.pair-model.json").readText())
            assertEquals("UNVERIFIED_SOURCE_DIGEST", manifest.getString("digestStatus"))
            assertEquals(5, server.requestedPaths.size)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    private fun catalogDescriptor(artifacts: Map<String, ByteArray>) = ModelDescriptor(
        logicalId = "hugging_face:owner/Qwen3-0.6B-MNN",
        engineModelId = "owner/Qwen3-0.6B-MNN",
        displayName = "Qwen3 0.6B MNN",
        family = "qwen",
        parameterCount = 600_000_000,
        quantization = null,
        contextLength = null,
        source = ModelSource(ModelSourceKind.HUGGING_FACE, "owner/Qwen3-0.6B-MNN", "a".repeat(40)),
        format = ModelFormat.MNN,
        estimatedMemoryBytes = 1_440_000_000,
        compatibility = ModelCompatibility.COMPATIBLE,
        files = artifacts.map { (path, bytes) -> ModelFile(path, bytes.size.toLong(), sha256(bytes)) },
        requiredArtifactPaths = listOf("llm_config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt"),
    )

    private fun localHttpAdapter(port: Int) = object : ModelSourceAdapter {
        override val kind = ModelSourceKind.HUGGING_FACE
        override fun parseSearchResponse(response: String): List<ModelDescriptor> = emptyList()
        override fun search(query: String, limit: Int): List<ModelDescriptor> = emptyList()
        override fun searchPage(query: String, page: Int, pageSize: Int): ModelSearchPage = ModelSearchPage(
            descriptors = emptyList(),
            providerPage = RemoteModelPage(emptyList(), page, pageSize, hasMore = false),
        )
        override fun inspect(descriptor: ModelDescriptor): ModelDescriptor = descriptor
        override fun fileUrl(repository: String, revision: String, path: String): URL = URL("http://127.0.0.1:$port/$path")
        override fun accessToken(): String? = null
    }

    private fun mnnArtifacts(): Map<String, ByteArray> = mapOf(
        "config.json" to """{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight","tokenizer_file":"tokenizer.txt"}""".toByteArray(),
        "llm_config.json" to "{}".toByteArray(),
        "llm.mnn" to "graph".toByteArray(),
        "llm.mnn.weight" to "weights".toByteArray(),
        "tokenizer.txt" to "tokens".toByteArray(),
    )

    private fun visualMnnArtifacts(): Map<String, ByteArray> = mnnArtifacts() + mapOf(
        "llm_config.json" to "{\"is_visual\":true}".toByteArray(),
        "visual.mnn" to "visual graph".toByteArray(),
        "visual.mnn.weight" to "visual weights".toByteArray(),
    )

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

    private fun writeDownloadTask(directory: File, descriptor: ModelDescriptor, phase: String) {
        val sourceHashes = org.json.JSONObject()
        (listOf("config.json") + descriptor.requiredArtifactPaths).forEach { path ->
            sourceHashes.put(path, descriptor.files.first { it.path == path }.sha256.orEmpty())
        }
        val completed = org.json.JSONArray()
        descriptor.files.forEach { artifact ->
            val contents = directory.resolve(artifact.path).readBytes()
            completed.put(org.json.JSONObject()
                .put("path", artifact.path)
                .put("sizeBytes", contents.size)
                .put("installedSha256", sha256(contents))
                .put("sourceSha256", artifact.sha256.orEmpty()))
        }
        val task = org.json.JSONObject()
            .put("schemaVersion", 2)
            .put("provider", descriptor.source.kind.name)
            .put("repository", descriptor.source.repository)
            .put("revision", descriptor.source.revision)
            .put("modelId", INSTALLED_MODEL_ID)
            .put("phase", phase)
            .put("requiredArtifactPaths", org.json.JSONArray(descriptor.requiredArtifactPaths))
            .put("sourceSha256ByPath", sourceHashes)
            .put("completedArtifacts", completed)
        directory.resolve(".pair-download.json").writeText(task.toString(2))
    }

    private fun provenanceManifest(
        descriptor: ModelDescriptor,
        artifacts: Map<String, ByteArray>,
    ): org.json.JSONObject {
        val files = org.json.JSONArray()
        descriptor.files.forEach { artifact ->
            val content = artifacts.getValue(artifact.path)
            files.put(org.json.JSONObject()
                .put("path", artifact.path)
                .put("sizeBytes", content.size)
                .put("sourceSha256", artifact.sha256)
                .put("installedSha256", sha256(content)))
        }
        return org.json.JSONObject()
            .put("schemaVersion", 1)
            .put("provider", descriptor.source.kind.name)
            .put("repository", descriptor.source.repository)
            .put("revision", descriptor.source.revision)
            .put("digestStatus", "VERIFIED_SOURCE_SHA256")
            .put("files", files)
    }

    private class ArtifactServer(
        private val artifacts: Map<String, ByteArray>,
        expectedRequests: Int = artifacts.size,
    ) : AutoCloseable {
        private val server = ServerSocket(0, expectedRequests, InetAddress.getByName("127.0.0.1"))
        val requestedPaths = Collections.synchronizedList(mutableListOf<String>())
        val requestedRanges = Collections.synchronizedList(mutableListOf<String?>())
        val port: Int = server.localPort
        private val worker = Thread {
            repeat(expectedRequests) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@Thread
                try {
                    socket.use(::serve)
                } catch (_: java.net.SocketException) {
                    // The simulated process interruption closes the download connection early.
                }
            }
        }.apply { start() }

        private fun serve(socket: Socket) {
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
            val path = reader.readLine()?.split(' ')?.getOrNull(1)?.removePrefix("/")
            var range: String? = null
            path?.let(requestedPaths::add)
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(':').trim()
            }
            requestedRanges += range
            val body = artifacts[path] ?: ByteArray(0)
            val offset = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
            val responseBody = body.drop(offset).toByteArray()
            val status = if (offset > 0) "206 Partial Content" else "200 OK"
            val contentRange = if (offset > 0) "Content-Range: bytes $offset-${body.lastIndex}/${body.size}\r\n" else ""
            val header = "HTTP/1.1 $status\r\nContent-Length: ${responseBody.size}\r\n${contentRange}Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(header.toByteArray(StandardCharsets.US_ASCII))
                write(responseBody)
                flush()
            }
        }

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }
}
