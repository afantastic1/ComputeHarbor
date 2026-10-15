/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class CloudProviderSettingsTest {
    @Test
    fun togglePreservesProviderConfigAndBudgetSettings() {
        val settings = parseCloudProviderSettings(
            JSONObject(
                """{"schema_version":1,"config":{"schema_version":1,"providers":[]},"cloudEnabled":false,"policy":"prefer_local","allowPaidFallback":true,"monthlyBudgetUSD":25,"perRequestMaxEstimatedCostUSD":0.5,"authorizedNodes":[{"nodeUuid":"10000000-0000-0000-0000-000000000001","certFingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}""",
            ),
        )

        val saved = settings.toJson(enabled = true, routingPolicy = settings.policy)

        assertTrue(saved.optBoolean("cloudEnabled"))
        assertEquals(settings.config.toString(), saved.getJSONObject("config").toString())
        assertEquals("prefer_local", saved.optString("policy"))
        assertTrue(saved.optBoolean("allowPaidFallback"))
        assertEquals(25.0, saved.optDouble("monthlyBudgetUSD"), 0.0)
        assertEquals(0.5, saved.optDouble("perRequestMaxEstimatedCostUSD"), 0.0)
        assertEquals(
            "10000000-0000-0000-0000-000000000001",
            saved.getJSONArray("authorizedNodes").getJSONObject(0).getString("nodeUuid"),
        )
        assertFalse(saved.has("credential"))
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsUnsupportedSchema() {
        parseCloudProviderSettings(JSONObject("""{"schema_version":2,"config":{},"policy":"local_only"}"""))
    }

    @Test
    fun parsesOpenAiCompatibleProvidersWithoutCredentialFields() {
        val settings = parseCloudProviderSettings(
            JSONObject(
                """{"schema_version":1,"config":{"schema_version":1,"providers":[{"id":"work","protocol":"openai_chat_completions","base_url":"https://api.example.test/v1","auth_ref":"android:work","enabled":true,"models":[{"public_id":"cloud/work/chat","upstream_id":"chat-model","capabilities":["chat","streaming","tools"]}]}]},"cloudEnabled":true,"policy":"cloud_only","monthlyBudgetUSD":0,"perRequestMaxEstimatedCostUSD":0}""",
            ),
        )

        assertEquals("work", settings.providers.single().id)
        assertEquals("https://api.example.test/v1", settings.providers.single().baseUrl)
        assertEquals("android:work", settings.providers.single().authRef)
        assertEquals("cloud/work/chat", settings.providers.single().models.single().publicId)
        assertTrue(settings.providers.single().models.single().capabilities.contains("tools"))
        assertFalse(settings.toJson(true, "cloud_only").toString().contains("credential"))
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsNonOpenAiProviderProtocol() {
        parseCloudProviderSettings(
            JSONObject(
                """{"schema_version":1,"config":{"schema_version":1,"providers":[{"id":"work","protocol":"anthropic_messages","base_url":"https://api.example.test","auth_ref":"android:work","enabled":true,"models":[]}]},"policy":"local_only"}""",
            ),
        )
    }

    @Test
    fun replacingProviderPreservesOtherProvidersAndReturnsRotatedAuthReference() {
        val config = JSONObject(
            """{"schema_version":1,"providers":[{"id":"first","protocol":"openai_chat_completions","base_url":"https://one.test","auth_ref":"android:first","enabled":true,"models":[]},{"id":"second","protocol":"openai_chat_completions","base_url":"https://two.test","auth_ref":"android:second","enabled":true,"models":[]}] }""",
        )
        val replacement = JSONObject(
            """{"id":"first","protocol":"openai_chat_completions","base_url":"https://new.test","auth_ref":"android:first-new","enabled":true,"models":[]}""",
        )

        val update = upsertCloudProviderConfig(config, replacement)

        assertEquals("android:first", update.replacedAuthRef)
        assertEquals(2, update.config.getJSONArray("providers").length())
        assertEquals("https://new.test", update.config.getJSONArray("providers").getJSONObject(0).getString("base_url"))
        assertEquals("second", update.config.getJSONArray("providers").getJSONObject(1).getString("id"))
    }

    @Test
    fun removingProviderDoesNotMutateOriginalConfig() {
        val config = JSONObject(
            """{"schema_version":1,"providers":[{"id":"first"},{"id":"second"}]}""",
        )

        val update = removeCloudProviderConfig(config, "first")

        assertEquals(2, config.getJSONArray("providers").length())
        assertEquals("first", config.getJSONArray("providers").getJSONObject(0).getString("id"))
        assertEquals(1, update.config.getJSONArray("providers").length())
        assertEquals("second", update.config.getJSONArray("providers").getJSONObject(0).getString("id"))
    }
}
