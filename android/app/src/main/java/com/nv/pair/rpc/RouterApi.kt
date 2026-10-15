/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import com.nv.pair.data.EngineProxyStatus
import com.nv.pair.data.PairWorkload
import com.nv.pair.data.RouterRepository
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class RouterApi(private val session: BrokerSession) {
    fun proxyStatus(engine: String): EngineProxyStatus = parseProxyStatus(
        engine,
        session.request("$engine-proxy:get-status"),
    )

    fun initializeWorkloads(repository: RouterRepository) {
        val subscription = session.request("workloads:subscribe")
        if (!subscription.optBoolean("subscribed")) throw IOException("broker rejected workload subscription")
        val generation = repository.workloadGeneration()
        repository.setWorkloadsIfUnchanged(generation, parseWorkloads(session.request("workloads:get-initial")))
    }

    fun cloudProviderSettings(): CloudProviderSettings = parseCloudProviderSettings(
        session.request("cloudproviders:get"),
    )

    fun updateCloudSettings(settings: CloudProviderSettings, enabled: Boolean, policy: String) {
        val params = settings.toJson(enabled, policy)
        val result = session.request("cloudproviders:save", params)
        if (!result.optBoolean("saved")) throw IOException("broker did not save cloud settings")
    }

    fun updateCloudProviders(settings: CloudProviderSettings, config: JSONObject) {
        val updated = settings.copy(config = config)
        val result = session.request("cloudproviders:save", updated.toJson(settings.cloudEnabled, settings.policy))
        if (!result.optBoolean("saved")) throw IOException("broker did not save cloud providers")
    }

    fun setCloudProviderCredential(authRef: String, credential: String) {
        if (authRef.isBlank()) throw IOException("provider credential reference is missing")
        session.request(
            "cloudproviders:credential:set",
            JSONObject().put("authRef", authRef).put("credential", credential),
        )
    }

    fun testCloudProvider(providerId: String): JSONObject {
        if (providerId.isBlank()) throw IOException("provider ID is missing")
        return session.request("cloudproviders:test", JSONObject().put("providerId", providerId))
    }
}

data class CloudProviderSettings(
    val config: JSONObject,
    val policy: String,
    val allowPaidFallback: Boolean,
    val monthlyBudgetUsd: Double,
    val perRequestMaxEstimatedCostUsd: Double,
    val cloudEnabled: Boolean,
    val authorizedNodes: List<CloudNodeAuthorization>,
    val providers: List<CloudProviderMetadata>,
    val providerStatusMessage: String? = null,
) {
    fun toJson(enabled: Boolean, routingPolicy: String): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("config", config)
        .put("cloudEnabled", enabled)
        .put("policy", routingPolicy)
        .put("allowPaidFallback", allowPaidFallback)
        .put("monthlyBudgetUSD", monthlyBudgetUsd)
        .put("perRequestMaxEstimatedCostUSD", perRequestMaxEstimatedCostUsd)
        .put("authorizedNodes", JSONArray().apply {
            authorizedNodes.forEach { authorization ->
                put(JSONObject()
                    .put("nodeUuid", authorization.nodeUuid)
                    .put("certFingerprint", authorization.certFingerprint))
            }
        })
}

data class CloudProviderMetadata(
    val id: String,
    val baseUrl: String,
    val authRef: String,
    val enabled: Boolean,
    val models: List<CloudModelMetadata>,
    val credentialConfigured: Boolean = false,
)

data class CloudModelMetadata(
    val publicId: String,
    val upstreamId: String,
    val capabilities: Set<String>,
)

data class CloudNodeAuthorization(val nodeUuid: String, val certFingerprint: String)

data class CloudProviderConfigUpdate(
    val config: JSONObject,
    val replacedAuthRef: String? = null,
)

fun upsertCloudProviderConfig(config: JSONObject, provider: JSONObject): CloudProviderConfigUpdate {
    val providerId = provider.optString("id").takeIf(String::isNotBlank)
        ?: throw IOException("cloud provider ID is missing")
    val updatedConfig = JSONObject(config.toString())
    val current = updatedConfig.optJSONArray("providers")
        ?: throw IOException("cloud settings have no provider list")
    val updated = JSONArray()
    var replacedAuthRef: String? = null
    var replacedProvider = false
    for (index in 0 until current.length()) {
        val existing = current.optJSONObject(index)
            ?: throw IOException("cloud settings contain an invalid provider")
        if (existing.optString("id") == providerId) {
            replacedAuthRef = existing.optString("auth_ref").takeIf(String::isNotBlank)
            updated.put(JSONObject(provider.toString()))
            replacedProvider = true
        } else {
            updated.put(existing)
        }
    }
    if (!replacedProvider) updated.put(JSONObject(provider.toString()))
    updatedConfig.put("providers", updated)
    return CloudProviderConfigUpdate(updatedConfig, replacedAuthRef)
}

fun removeCloudProviderConfig(config: JSONObject, providerId: String): CloudProviderConfigUpdate {
    if (providerId.isBlank()) throw IOException("cloud provider ID is missing")
    val updatedConfig = JSONObject(config.toString())
    val current = updatedConfig.optJSONArray("providers")
        ?: throw IOException("cloud settings have no provider list")
    val updated = JSONArray()
    for (index in 0 until current.length()) {
        val existing = current.optJSONObject(index)
            ?: throw IOException("cloud settings contain an invalid provider")
        if (existing.optString("id") != providerId) updated.put(existing)
    }
    updatedConfig.put("providers", updated)
    return CloudProviderConfigUpdate(updatedConfig)
}

fun parseCloudProviderSettings(value: JSONObject): CloudProviderSettings {
    val config = value.optJSONObject("config") ?: throw IOException("cloud settings have no config")
    if (value.optInt("schema_version") != 1) throw IOException("unsupported cloud settings version")
    val policy = value.optString("policy")
    if (policy !in setOf("local_only", "cloud_only", "prefer_local", "prefer_cloud")) {
        throw IOException("cloud settings have an invalid policy")
    }
    val providersJson = config.optJSONArray("providers")
        ?: throw IOException("cloud settings have no provider list")
    val providers = List(providersJson.length()) { index ->
        val provider = providersJson.optJSONObject(index)
            ?: throw IOException("cloud settings contain an invalid provider")
        if (provider.has("credential") || provider.has("api_key") || provider.has("apiKey")) {
            throw IOException("cloud provider settings must not contain credentials")
        }
        if (provider.optString("protocol") != "openai_chat_completions") {
            throw IOException("cloud provider protocol is not supported on Android")
        }
        val modelsJson = provider.optJSONArray("models")
            ?: throw IOException("cloud provider has no model list")
        CloudProviderMetadata(
            id = provider.requiredCloudString("id"),
            baseUrl = provider.requiredCloudString("base_url"),
            authRef = provider.requiredCloudString("auth_ref"),
            enabled = provider.optBoolean("enabled"),
            models = List(modelsJson.length()) { modelIndex ->
                val model = modelsJson.optJSONObject(modelIndex)
                    ?: throw IOException("cloud provider contains an invalid model")
                val capabilitiesJson = model.optJSONArray("capabilities")
                    ?: throw IOException("cloud model has no capabilities")
                CloudModelMetadata(
                    publicId = model.requiredCloudString("public_id"),
                    upstreamId = model.requiredCloudString("upstream_id"),
                    capabilities = List(capabilitiesJson.length()) { capabilityIndex ->
                        capabilitiesJson.optString(capabilityIndex).takeIf(String::isNotBlank)
                            ?: throw IOException("cloud model contains an invalid capability")
                    }.toSet(),
                )
            },
        )
    }
    return CloudProviderSettings(
        config = config,
        policy = policy,
        allowPaidFallback = value.optBoolean("allowPaidFallback"),
        monthlyBudgetUsd = value.optDouble("monthlyBudgetUSD"),
        perRequestMaxEstimatedCostUsd = value.optDouble("perRequestMaxEstimatedCostUSD"),
        cloudEnabled = value.optBoolean("cloudEnabled"),
        authorizedNodes = value.optJSONArray("authorizedNodes")?.let { array ->
            List(array.length()) { index ->
                val authorization = array.optJSONObject(index)
                    ?: throw IOException("cloud settings contain an invalid node authorization")
                val nodeUuid = authorization.optString("nodeUuid")
                val certFingerprint = authorization.optString("certFingerprint")
                if (nodeUuid.isBlank() || certFingerprint.isBlank()) {
                    throw IOException("cloud settings contain an invalid node authorization")
                }
                CloudNodeAuthorization(nodeUuid, certFingerprint)
            }
        }.orEmpty(),
        providers = providers,
    )
}

private fun JSONObject.requiredCloudString(key: String): String = optString(key)
    .takeIf(String::isNotBlank) ?: throw IOException("cloud provider metadata is missing $key")

fun parseProxyStatus(engine: String, value: JSONObject): EngineProxyStatus {
    val ready = value.optBoolean("ready")
    val port = if (ready) value.optInt("port") else 0
    if (ready && port !in 1..65535) throw IOException("$engine proxy reported an invalid bound port")
    return EngineProxyStatus(engine, ready, port)
}

fun parseWorkloads(result: JSONObject): List<PairWorkload> {
    val array = result.optJSONArray("workloads")
        ?: throw IOException("workload snapshot has no workloads array")
    return array.toWorkloads()
}

fun parseWorkloadUpsert(paramsJson: String?): PairWorkload {
    val params = parseNotificationObject(paramsJson, "workload upsert")
    return params.optJSONObject("workloadInfo")?.toPairWorkload()
        ?: throw IOException("workload upsert has no workloadInfo object")
}

data class WorkloadRemoval(val workloadId: String, val origin: String)

fun parseWorkloadRemoval(paramsJson: String?): WorkloadRemoval {
    val params = parseNotificationObject(paramsJson, "workload removal")
    return WorkloadRemoval(
        workloadId = params.requiredRouterString("workloadId"),
        origin = params.optString("originatedFrom"),
    )
}

private fun parseNotificationObject(paramsJson: String?, label: String): JSONObject = try {
    JSONObject(paramsJson ?: throw IOException("$label has no params"))
} catch (failure: JSONException) {
    throw IOException("$label params are invalid", failure)
}

private fun JSONArray.toWorkloads(): List<PairWorkload> = List(length()) { index ->
    val value = optJSONObject(index) ?: throw IOException("workload at index $index is not an object")
    value.toPairWorkload()
}

private fun JSONObject.toPairWorkload(): PairWorkload = PairWorkload(
    id = requiredRouterString("id"),
    model = optString("model"),
    engine = optString("engine"),
    runId = optString("runId"),
    state = requiredRouterString("state"),
    originatedFrom = optString("originatedFrom"),
    scheduledOn = optString("scheduledOn"),
    createdAt = optLong("createdAt"),
    startedAt = if (isNull("startedAt")) null else optLong("startedAt"),
    completedAt = if (isNull("completedAt")) null else optLong("completedAt"),
    error = if (isNull("error")) null else optString("error"),
    kind = optString("kind", "local"),
    providerId = if (isNull("providerId")) null else optString("providerId"),
    publicModelId = if (isNull("publicModelId")) null else optString("publicModelId"),
    inputTokens = valueOptJSONObject("usage")?.optLong("inputTokens"),
    outputTokens = valueOptJSONObject("usage")?.optLong("outputTokens"),
    costEstimate = if (isNull("costEstimate")) null else optDouble("costEstimate"),
    requesterId = if (isNull("requesterId")) null else optString("requesterId"),
)

private fun JSONObject.valueOptJSONObject(key: String): JSONObject? =
    if (isNull(key)) null else optJSONObject(key)

private fun JSONObject.requiredRouterString(key: String): String {
    val value = optString(key)
    if (value.isBlank()) throw IOException("router response has no $key")
    return value
}
