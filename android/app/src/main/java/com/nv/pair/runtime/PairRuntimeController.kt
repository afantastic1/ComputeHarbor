/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.nv.pair.models.ModelHubInstaller
import com.nv.pair.data.PairNode
import com.nv.pair.data.ClusterState
import com.nv.pair.data.EngineProxyStatus
import com.nv.pair.data.PairWorkload
import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.MnnSettingsRepository
import com.nv.pair.mnn.BackendChoice
import com.nv.pair.mnn.EffectiveBackendSelection
import com.nv.pair.mnn.MnnCapabilitySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.UUID
import org.json.JSONObject

class PairRuntimeController(context: Context) {
    private val applicationContext = context.applicationContext
    private val mnnSettings = MnnSettingsRepository(applicationContext)

    val state: StateFlow<PairRuntimeState> = PairRuntimeService.runtimeState
    val nodes: StateFlow<List<PairNode>> = PairRuntimeService.discoveredNodes
    val cluster: StateFlow<ClusterState> = PairRuntimeService.clusterState
    val proxies: StateFlow<List<EngineProxyStatus>> = PairRuntimeService.proxyStatuses
    val mnnLocalEngine: StateFlow<MnnLocalEngineStatus> = PairRuntimeService.mnnLocalEngine
    val backendChoice: Flow<BackendChoice> = mnnSettings.backendChoice
    val mnnCapabilities: StateFlow<MnnCapabilitySnapshot> = PairRuntimeService.mnnCapabilities
    val effectiveBackendSelection: StateFlow<EffectiveBackendSelection> = PairRuntimeService.effectiveBackendSelection
    val workloads: StateFlow<List<PairWorkload>> = PairRuntimeService.workloads
    val cloudProviderSettings = PairRuntimeService.cloudProviderSettings

    fun setCloudEnabled(enabled: Boolean) {
        applicationContext.startService(
            Intent(applicationContext, PairRuntimeService::class.java)
                .setAction(PairRuntimeService.ACTION_CLOUD_ENABLED_CHANGED)
                .putExtra(PairRuntimeService.EXTRA_CLOUD_ENABLED, enabled),
        )
    }

    fun setCloudPolicy(policy: String) {
        applicationContext.startService(
            Intent(applicationContext, PairRuntimeService::class.java)
                .setAction(PairRuntimeService.ACTION_CLOUD_POLICY_CHANGED)
                .putExtra(PairRuntimeService.EXTRA_CLOUD_POLICY, policy),
        )
    }

    fun saveCloudProvider(provider: JSONObject, credential: String) {
        applicationContext.startService(
            Intent(applicationContext, PairRuntimeService::class.java)
                .setAction(PairRuntimeService.ACTION_CLOUD_PROVIDER_SAVE)
                .putExtra(PairRuntimeService.EXTRA_CLOUD_PROVIDER_JSON, provider.toString())
                .putExtra(PairRuntimeService.EXTRA_CLOUD_PROVIDER_CREDENTIAL, credential),
        )
    }

    fun removeCloudProvider(providerId: String) {
        applicationContext.startService(
            Intent(applicationContext, PairRuntimeService::class.java)
                .setAction(PairRuntimeService.ACTION_CLOUD_PROVIDER_REMOVE)
                .putExtra(PairRuntimeService.EXTRA_CLOUD_PROVIDER_ID, providerId),
        )
    }

    fun testCloudProvider(providerId: String) {
        applicationContext.startService(
            Intent(applicationContext, PairRuntimeService::class.java)
                .setAction(PairRuntimeService.ACTION_CLOUD_PROVIDER_TEST)
                .putExtra(PairRuntimeService.EXTRA_CLOUD_PROVIDER_ID, providerId),
        )
    }

    suspend fun setPreferredMnnBackend(backend: MnnBackend) {
        setManualBackend(backend)
    }

    suspend fun setManualBackend(backend: MnnBackend) {
        mnnSettings.setBackendChoice(BackendChoice.Manual(backend))
        if (PairRuntimeService.runtimeState.value.desiredRunning) {
            applicationContext.startService(
                Intent(applicationContext, PairRuntimeService::class.java)
                    .setAction(PairRuntimeService.ACTION_MNN_BACKEND_CHANGED),
            )
        }
    }

    suspend fun resetBackendToAuto() {
        mnnSettings.resetBackendToAuto()
        if (PairRuntimeService.runtimeState.value.desiredRunning) {
            applicationContext.startService(
                Intent(applicationContext, PairRuntimeService::class.java)
                    .setAction(PairRuntimeService.ACTION_MNN_BACKEND_CHANGED),
            )
        }
    }

    fun reprobeOpenCl() {
        applicationContext.startService(
            Intent(applicationContext, PairRuntimeService::class.java)
                .setAction(PairRuntimeService.ACTION_MNN_REPROBE_OPENCL),
        )
    }

    suspend fun deleteInstalledModel(modelId: String) {
        when (state.value.phase) {
            RuntimePhase.STOPPED -> withContext(Dispatchers.IO) {
                ModelHubInstaller(File(applicationContext.filesDir, "mnn/models")).deleteInstalledModel(modelId)
            }
            RuntimePhase.RUNNING -> {
                val requestId = UUID.randomUUID().toString()
                val result = coroutineScope {
                    val response = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        withTimeout(MODEL_DELETE_TIMEOUT_MILLIS) {
                            PairRuntimeService.modelDeletionResults.first { it.requestId == requestId }
                        }
                    }
                    applicationContext.startService(
                        Intent(applicationContext, PairRuntimeService::class.java)
                            .setAction(PairRuntimeService.ACTION_DELETE_MODEL)
                            .putExtra(PairRuntimeService.EXTRA_MODEL_DELETE_REQUEST_ID, requestId)
                            .putExtra(PairRuntimeService.EXTRA_MODEL_DELETE_ID, modelId),
                    )
                    response.await()
                }
                result.failureMessage?.let { throw IOException(it) }
            }
            else -> throw IOException("Wait for PAIR to finish changing runtime state before deleting a model.")
        }
    }

    fun start() {
        val intent = Intent(applicationContext, PairRuntimeService::class.java)
            .setAction(PairRuntimeService.ACTION_START)
        ContextCompat.startForegroundService(applicationContext, intent)
    }

    fun stop() {
        val intent = Intent(applicationContext, PairRuntimeService::class.java)
            .setAction(PairRuntimeService.ACTION_STOP)
        applicationContext.startService(intent)
    }

    fun createCluster(name: String) = clusterAction(PairRuntimeService.ACTION_CLUSTER_CREATE, name)

    fun invite(nodeId: String) = clusterAction(PairRuntimeService.ACTION_CLUSTER_INVITE, nodeId)

    fun respond(inviteId: String, accept: Boolean, pin: String = "") {
        clusterIntent(PairRuntimeService.ACTION_CLUSTER_RESPOND)
            .putExtra(PairRuntimeService.EXTRA_VALUE, inviteId)
            .putExtra(PairRuntimeService.EXTRA_SECONDARY, pin)
            .putExtra(PairRuntimeService.EXTRA_ACCEPT, accept)
            .also(applicationContext::startService)
    }

    fun cancelInvite(inviteId: String) = clusterAction(PairRuntimeService.ACTION_CLUSTER_CANCEL, inviteId)

    fun leaveCluster() = clusterAction(PairRuntimeService.ACTION_CLUSTER_LEAVE)

    fun removeMember(nodeId: String) = clusterAction(PairRuntimeService.ACTION_CLUSTER_REMOVE, nodeId)

    private fun clusterAction(action: String, value: String = "") {
        clusterIntent(action).putExtra(PairRuntimeService.EXTRA_VALUE, value).also(applicationContext::startService)
    }

    private fun clusterIntent(action: String) =
        Intent(applicationContext, PairRuntimeService::class.java).setAction(action)

    private companion object {
        const val MODEL_DELETE_TIMEOUT_MILLIS = 35_000L
    }
}
