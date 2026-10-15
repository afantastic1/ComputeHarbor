/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourceAdapterTest {
    @Test
    fun providerHttpStatusesMapToTypedCategoriesAndRetryability() {
        assertEquals(ProviderErrorCategory.AUTHENTICATION, providerErrorCategory(401))
        assertEquals(ProviderErrorCategory.NOT_FOUND, providerErrorCategory(404))
        assertEquals(ProviderErrorCategory.RATE_LIMITED, providerErrorCategory(429))
        assertEquals(ProviderErrorCategory.SERVER, providerErrorCategory(503))
        assertEquals(ProviderErrorCategory.INVALID_RESPONSE, providerErrorCategory(422))
    }

    @Test
    fun malformedProviderResponseMapsToTypedNonRetryableError() {
        val adapter = ModelScopeAdapter(
            "https://modelscope.test",
            transport = ModelHubHttpTransport { _, _ -> "{" },
        )

        val failure = captureProviderError { adapter.search("Qwen") }

        assertEquals(ProviderId.MODELSCOPE, failure.provider)
        assertEquals("search", failure.operation)
        assertEquals(ProviderErrorCategory.INVALID_RESPONSE, failure.category)
        assertTrue(!failure.retryable)
    }

    @Test
    fun providerConnectionFailureMapsToTypedRetryableNetworkError() {
        val adapter = HuggingFaceAdapter(
            "https://huggingface.test/api",
            transport = ModelHubHttpTransport { _, _ -> throw IOException("socket details are private") },
        )

        val failure = captureProviderError { adapter.search("Qwen") }

        assertEquals(ProviderId.HUGGING_FACE, failure.provider)
        assertEquals(ProviderErrorCategory.NETWORK, failure.category)
        assertTrue(failure.retryable)
        assertTrue(!failure.message.orEmpty().contains("socket details"))
    }

    private fun captureProviderError(action: () -> Unit): ProviderError = try {
        action()
        throw AssertionError("Expected a typed provider error.")
    } catch (failure: ProviderError) {
        failure
    }

    @Test
    fun hugFaceSearchResultsKeepRepositoryAndMnnArtifactMetadata() {
        val models = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"taobao-mnn/Qwen3-1.7B-MNN","pipeline_tag":"text-generation","tags":["mnn","qwen3"],"siblings":[{"rfilename":"config.json","size":120,"lfs":{"oid":"${"a".repeat(64)}"}},{"rfilename":"llm.mnn","size":2048,"lfs":{"oid":"${"b".repeat(64)}"}}]}]""",
        )

        assertEquals(1, models.size)
        assertEquals("taobao-mnn/Qwen3-1.7B-MNN", models.single().source.repository)
        assertEquals(ModelFormat.MNN, models.single().format)
        assertEquals(1_700_000_000L, models.single().parameterCount)
        assertTrue(models.single().files.any { it.path == "llm.mnn" && it.sha256 == "b".repeat(64) })
    }

    @Test
    fun hfFortyHexGitOidIsNotSha256() {
        val model = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"taobao-mnn/model","siblings":[{"rfilename":"config.json","oid":"${"e".repeat(40)}"}]}]""",
        ).single()

        assertNull(model.files.single().sha256)
    }

    @Test
    fun hfLfsSha256IsAccepted() {
        val model = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"taobao-mnn/model","siblings":[{"rfilename":"llm.mnn","lfs":{"oid":"sha256:${"a".repeat(64)}"}}]}]""",
        ).single()

        assertEquals("a".repeat(64), model.files.single().sha256)
    }

    @Test
    fun missingConfigHashAllowsLocalDigestOnlyWhenRevisionIsPinned() {
        val model = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"taobao-mnn/Qwen3-MNN","siblings":[{"rfilename":"config.json"},{"rfilename":"llm_config.json","lfs":{"oid":"${"d".repeat(64)}"}},{"rfilename":"llm.mnn","lfs":{"oid":"${"a".repeat(64)}"}},{"rfilename":"llm.mnn.weight","lfs":{"oid":"${"b".repeat(64)}"}},{"rfilename":"tokenizer.txt","lfs":{"oid":"${"c".repeat(64)}"}}]}]""",
        ).single().copy(requiredArtifactPaths = listOf("llm_config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt"))

        assertEquals(ModelInstallability.UNPINNED_SOURCE_REVISION, model.installability())
        assertEquals(ModelInstallability.LOCAL_DIGEST_ONLY, model.copy(source = model.source.copy(revision = "a".repeat(40))).installability())
    }

    @Test
    fun missingRequiredArtifactHashAllowsLocalDigestOnlyWhenRevisionIsPinned() {
        val model = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"taobao-mnn/Qwen3-MNN","siblings":[{"rfilename":"config.json","lfs":{"oid":"${"a".repeat(64)}"}},{"rfilename":"llm_config.json","lfs":{"oid":"${"d".repeat(64)}"}},{"rfilename":"llm.mnn","lfs":{"oid":"${"b".repeat(64)}"}},{"rfilename":"llm.mnn.weight"},{"rfilename":"tokenizer.txt","lfs":{"oid":"${"c".repeat(64)}"}}]}]""",
        ).single().copy(requiredArtifactPaths = listOf("llm_config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt"))

        assertEquals(ModelInstallability.UNPINNED_SOURCE_REVISION, model.installability())
        assertEquals(ModelInstallability.LOCAL_DIGEST_ONLY, model.copy(source = model.source.copy(revision = "a".repeat(40))).installability())
    }

    @Test
    fun incompleteMnnArtifactSetIsNotVerifiedInstallable() {
        val model = HuggingFaceAdapter().parseSearchResponse(
            """[{"id":"taobao-mnn/Qwen3-MNN","siblings":[{"rfilename":"config.json","lfs":{"oid":"${"a".repeat(64)}"}},{"rfilename":"llm.mnn","lfs":{"oid":"${"b".repeat(64)}"}}]}]""",
        ).single()

        assertEquals(ModelInstallability.UNPINNED_SOURCE_REVISION, model.installability())
        assertEquals(ModelInstallability.INCOMPLETE_ARTIFACT_SET, model.copy(source = model.source.copy(revision = "a".repeat(40))).installability())
    }

    @Test
    fun modelScopeSearchResultsMapTheirModelPathsToCatalogDescriptors() {
        val models = ModelScopeAdapter().parseSearchResponse(
            """{"Code":200,"Data":{"Models":[{"Name":"Qwen3 0.6B MNN","Path":"MNN/Qwen3-0.6B-MNN","Description":"MNN chat model","Tags":["qwen3","mnn"],"Files":[{"Name":"config.json","Size":120,"Sha256":"${"c".repeat(64)}"},{"Name":"llm.mnn","Size":2048,"Sha256":"${"d".repeat(64)}"}]}]}}""",
        )

        assertEquals(1, models.size)
        assertEquals("MNN/Qwen3-0.6B-MNN", models.single().source.repository)
        assertEquals(600_000_000L, models.single().parameterCount)
        assertEquals("MNN chat model", models.single().description)
        assertEquals(listOf("qwen3", "mnn"), models.single().tags)
        assertTrue(models.single().files.any { it.path == "llm.mnn" && it.sizeBytes == 2048L })
    }

    @Test
    fun searchRequestsUseOfficialOwnersAndDoNotInspectEachResult() {
        val requests = mutableListOf<String>()
        val transport = ModelHubHttpTransport { url, _ ->
            requests += url.toString()
            when {
                url.host == "modelscope.test" -> """{"data":{"models":[{"id":"MNN/model","name":"model"},{"id":"other/model"}]}}"""
                else -> """[{"id":"taobao-mnn/model"},{"id":"other/model"}]"""
            }
        }

        val modelScope = ModelScopeAdapter("https://modelscope.test", transport = transport).search("Qwen", 12)
        val huggingFace = HuggingFaceAdapter("https://huggingface.test/api", transport = transport).search("Qwen", 12)

        assertEquals(listOf("MNN/model"), modelScope.map { it.source.repository })
        assertEquals(listOf("taobao-mnn/model"), huggingFace.map { it.source.repository })
        assertEquals(2, requests.size)
        assertTrue(requests[0].startsWith("https://modelscope.test/openapi/v1/models?"))
        assertTrue(requests[0].contains("owner=MNN"))
        assertTrue(requests[0].contains("page_size=12"))
        assertTrue(requests[1].contains("author=taobao-mnn"))
        assertTrue(!requests[1].contains("full=true"))
    }

    @Test
    fun modelScopeSearchPageUsesSnakeCasePaginationAndReportsMoreResults() {
        val requests = mutableListOf<String>()
        val transport = ModelHubHttpTransport { url, _ ->
            requests += url.toString()
            """{"data":{"total_count":51,"models":[{"id":"MNN/model"}]}}"""
        }

        val page = ModelScopeAdapter("https://modelscope.test", transport = transport)
            .searchPage("Qwen", page = 2, pageSize = 25)

        assertEquals(2, page.providerPage.page)
        assertEquals(25, page.providerPage.pageSize)
        assertTrue(page.providerPage.hasMore)
        assertEquals("MNN/model", page.descriptors.single().source.repository)
        assertTrue(requests.single().contains("page_number=2&page_size=25"))
    }

    @Test
    fun modelScopeSearchPageRejectsPageSizesAboveTheProviderLimit() {
        val error = runCatching {
            ModelScopeAdapter("https://modelscope.test", transport = ModelHubHttpTransport { _, _ -> "{}" })
                .searchPage("Qwen", page = 1, pageSize = 51)
        }.exceptionOrNull()

        assertTrue("ModelScope page sizes above 50 must be rejected", error is IllegalArgumentException)
    }

    @Test
    fun huggingFaceSearchPageFollowsTheNextLinkHeader() {
        val requests = mutableListOf<String>()
        val transport = object : ModelHubHttpTransport {
            override fun get(url: java.net.URL, bearerToken: String?): String =
                error("The adapter should preserve pagination headers.")

            override fun getResponse(url: java.net.URL, bearerToken: String?): ModelHubHttpResponse {
                requests += url.toString()
                return if (url.toString().contains("cursor=page-2")) {
                    ModelHubHttpResponse("""[{"id":"taobao-mnn/page-two"}]""")
                } else {
                    ModelHubHttpResponse(
                        body = """[{"id":"taobao-mnn/page-one"}]""",
                        headers = mapOf("Link" to "<https://huggingface.test/api/models?cursor=page-2>; rel=\"next\""),
                    )
                }
            }
        }

        val page = HuggingFaceAdapter("https://huggingface.test/api", transport = transport)
            .searchPage("Qwen", page = 2, pageSize = 1)

        assertEquals("taobao-mnn/page-two", page.descriptors.single().source.repository)
        assertEquals(2, page.providerPage.page)
        assertTrue(!page.providerPage.hasMore)
        assertEquals(2, requests.size)
        assertTrue(requests.last().contains("cursor=page-2"))
    }

    @Test
    fun inspectPinsRevisionAndResolvesConfiguredNestedMtokArtifacts() {
        val revision = "a".repeat(40)
        val requests = mutableListOf<String>()
        val transport = ModelHubHttpTransport { url, _ ->
            requests += url.toString()
            if (url.host == "huggingface.test") {
                """{"sha":"$revision","siblings":[{"rfilename":".gitattributes"},{"rfilename":"config.json","size":20,"lfs":{"oid":"${"c".repeat(64)}"}},{"rfilename":"llm_config.json","lfs":{"oid":"${"e".repeat(64)}"}},{"rfilename":"weights/graph.mnn","lfs":{"oid":"${"a".repeat(64)}"}},{"rfilename":"weights/graph.mnn.weight","lfs":{"oid":"${"b".repeat(64)}"}},{"rfilename":"tokenizer.mtok","lfs":{"oid":"${"d".repeat(64)}"}}]}"""
            } else {
                """{"llm_config":"llm_config.json","llm_model":"weights/graph.mnn","llm_weight":"weights/graph.mnn.weight","tokenizer_file":"tokenizer.mtok"}"""
            }
        }
        val adapter = HuggingFaceAdapter("https://huggingface.test/api", transport = transport)
        val summary = adapter.parseSearchResponse("""[{"id":"taobao-mnn/model"}]""").single()

        val inspected = adapter.inspect(summary)

        assertEquals(revision, inspected.source.revision)
        assertEquals(ModelFormat.MNN, inspected.format)
        assertEquals(
            listOf("llm_config.json", "weights/graph.mnn", "weights/graph.mnn.weight", "tokenizer.mtok"),
            inspected.requiredArtifactPaths,
        )
        assertEquals(ModelInstallability.VERIFIED_INSTALLABLE, inspected.installability())
        assertTrue(requests[0].contains("?blobs=true"))
        assertTrue(requests[1].contains("/resolve/$revision/config.json"))
    }

    @Test
    fun inspectIncludesVisualArtifactsForVisualMnnModels() {
        val revision = "a".repeat(40)
        val transport = ModelHubHttpTransport { url, _ ->
            if (url.host == "huggingface.test") {
                """{"sha":"$revision","siblings":[{"rfilename":"config.json","lfs":{"oid":"${"a".repeat(64)}"}},{"rfilename":"llm_config.json","lfs":{"oid":"${"b".repeat(64)}"}},{"rfilename":"llm.mnn","lfs":{"oid":"${"c".repeat(64)}"}},{"rfilename":"llm.mnn.weight","lfs":{"oid":"${"d".repeat(64)}"}},{"rfilename":"tokenizer.txt","lfs":{"oid":"${"e".repeat(64)}"}},{"rfilename":"visual.mnn","lfs":{"oid":"${"f".repeat(64)}"}},{"rfilename":"visual.mnn.weight","lfs":{"oid":"${"0".repeat(64)}"}}]}"""
            } else if (url.path.endsWith("/config.json")) {
                """{"llm_model":"llm.mnn","llm_weight":"llm.mnn.weight","tokenizer_file":"tokenizer.txt"}"""
            } else {
                """{"is_visual":true}"""
            }
        }
        val adapter = HuggingFaceAdapter("https://huggingface.test/api", transport = transport)
        val summary = adapter.parseSearchResponse("""[{"id":"taobao-mnn/visual-model"}]""").single()

        val inspected = adapter.inspect(summary)

        assertEquals(
            listOf("llm_config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.txt", "visual.mnn", "visual.mnn.weight"),
            inspected.requiredArtifactPaths,
        )
        assertEquals(ModelInstallability.VERIFIED_INSTALLABLE, inspected.installability())
    }

    @Test
    fun modelScopeInspectionResolvesBranchFromGitRefAdvertisement() {
        val commit = "c".repeat(40)
        val requests = mutableListOf<String>()
        val gitHeaders = mutableMapOf<String, String>()
        val respond: (java.net.URL) -> String = { url ->
            requests += url.toString()
            when {
                url.path.endsWith("/revisions") ->
                    """{"Data":{"RevisionMap":{"Branches":[{"Revision":"master"}],"Tags":[]}}}"""
                url.path.endsWith("/info/refs") -> gitAdvertisement(commit, "master")
                url.path.endsWith("/repo/files") -> """{"Data":{"Files":[
                    {"Name":"config.json","Revision":"$commit","Size":20,"Sha256":"${"a".repeat(64)}"},
                    {"Name":"llm_config.json","Revision":"$commit","Size":15,"Sha256":"${"e".repeat(64)}"},
                    {"Name":"llm.mnn","Revision":"${"d".repeat(40)}","Size":30,"Sha256":"${"b".repeat(64)}"},
                    {"Name":"llm.mnn.weight","Revision":"${"e".repeat(40)}","Size":40,"Sha256":"${"c".repeat(64)}"},
                    {"Name":"tokenizer.txt","Revision":"${"f".repeat(40)}","Size":10,"Sha256":"${"d".repeat(64)}"}
                ]}}"""
                url.path.endsWith("/config.json") -> """{"llm_model":"llm.mnn"}"""
                url.path.endsWith("/llm_config.json") -> "{}"
                else -> error("Unexpected ModelScope URL: $url")
            }
        }
        val transport = object : ModelHubHttpTransport {
            override fun get(url: java.net.URL, bearerToken: String?): String = respond(url)

            override fun getResponse(
                url: java.net.URL,
                bearerToken: String?,
                requestHeaders: Map<String, String>,
            ): ModelHubHttpResponse {
                if (url.path.endsWith("/info/refs")) gitHeaders.putAll(requestHeaders)
                return ModelHubHttpResponse(respond(url))
            }
        }
        val descriptor = ModelScopeAdapter("https://modelscope.test", transport = transport)
            .parseSearchResponse("""{"Data":{"Models":[{"Path":"MNN/Qwen-MNN"}]}}""").single()

        val inspected = ModelScopeAdapter("https://modelscope.test", transport = transport).inspect(descriptor)

        assertEquals(commit, inspected.source.revision)
        assertEquals(ModelInstallability.VERIFIED_INSTALLABLE, inspected.installability())
        assertTrue(requests[1].contains(".git/info/refs?service=git-upload-pack"))
        assertEquals("application/x-git-upload-pack-advertisement", gitHeaders["Accept"])
        assertTrue(gitHeaders["User-Agent"].orEmpty().startsWith("git/"))
        assertTrue(requests.any { request -> request.contains("/resolve/$commit/config.json") })
        assertTrue(requests.last().contains("/resolve/$commit/llm_config.json"))
    }

    @Test
    fun modelScopeInspectionRejectsGitAdvertisementWithoutSelectedBranch() {
        val requests = mutableListOf<String>()
        val transport = ModelHubHttpTransport { url, _ ->
            requests += url.toString()
            when {
                url.path.endsWith("/revisions") ->
                    """{"Data":{"RevisionMap":{"Branches":[{"Revision":"master"}],"Tags":[]}}}"""
                url.path.endsWith("/info/refs") -> gitAdvertisement("e".repeat(40), "develop")
                else -> error("Unexpected ModelScope URL: $url")
            }
        }
        val adapter = ModelScopeAdapter("https://modelscope.test", transport = transport)
        val descriptor = adapter.parseSearchResponse("""{"Data":{"Models":[{"Path":"MNN/Qwen-MNN"}]}}""").single()

        val failure = try {
            adapter.inspect(descriptor)
            throw AssertionError("Expected inspection to reject an unknown branch.")
        } catch (error: ProviderError) {
            error
        }

        assertEquals("resolve-revision", failure.operation)
        assertEquals(2, requests.size)
    }

    @Test
    fun modelScopeInspectionRejectsBranchWhenCommitMetadataIsMissing() {
        val transport = ModelHubHttpTransport { _, _ ->
            """{"Data":{"RevisionMap":{"Branches":[{"Revision":"master"}],"Tags":[]}}}"""
        }
        val descriptor = ModelScopeAdapter("https://modelscope.test", transport = transport)
            .parseSearchResponse("""{"Data":{"Models":[{"Path":"MNN/Qwen-MNN"}]}}""").single()

        val failure = try {
            ModelScopeAdapter("https://modelscope.test", transport = transport).inspect(descriptor)
            throw AssertionError("Expected inspection to reject an unpinned revision.")
        } catch (error: ProviderError) {
            error
        }

        assertEquals(ProviderId.MODELSCOPE, failure.provider)
        assertEquals("resolve-revision", failure.operation)
        assertEquals(ProviderErrorCategory.INVALID_RESPONSE, failure.category)
        assertTrue(!failure.retryable)
    }
}

private fun gitAdvertisement(commit: String, branch: String): String {
    val capabilities = "$commit HEAD\u0000multi_ack side-band-64k object-format=sha1\n"
    val ref = "$commit refs/heads/$branch\n"
    return gitPacketLine("# service=git-upload-pack\n") + "0000" +
        gitPacketLine(capabilities) + gitPacketLine(ref) + "0000"
}

private fun gitPacketLine(payload: String): String =
    "%04x".format(payload.toByteArray(Charsets.UTF_8).size + 4) + payload
