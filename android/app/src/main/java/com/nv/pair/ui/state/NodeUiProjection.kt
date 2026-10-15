/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.ui.state

import com.nv.pair.data.ClusterState
import com.nv.pair.data.PairNode
import com.nv.pair.data.PairWorkload
import com.nv.pair.data.PeerRelationshipStatus
import com.nv.pair.data.buildPeerViews
import com.nv.pair.mnn.MnnEngineState
import com.nv.pair.runtime.MnnLocalEngineStatus

internal data class LocalInferenceUiState(
    val activityLabel: String,
    val modelLabel: String,
    val errorLabel: String?,
)

internal fun shouldConfirmNodeStop(runtimeRunning: Boolean, workloads: List<PairWorkload>): Boolean =
    runtimeRunning && workloads.any { it.state.equals("running", ignoreCase = true) }

internal fun projectLocalInference(
    status: MnnLocalEngineStatus,
    runtimeRunning: Boolean,
): LocalInferenceUiState {
    if (!runtimeRunning) return LocalInferenceUiState("Stopped", "Model status unavailable", null)
    if (status.engineState == MnnEngineState.ERROR) {
        return LocalInferenceUiState(
            "Error",
            status.modelId?.let { "Model $it" } ?: "Model load failed",
            status.errorCode?.let(::formatMnnError),
        )
    }
    if (!status.available) return LocalInferenceUiState("Unavailable", "Model status unavailable", status.errorCode?.let(::formatMnnError))
    val label = when (status.engineState) {
        MnnEngineState.UNLOADED -> "Idle"
        MnnEngineState.LOADING -> "Loading"
        MnnEngineState.READY -> "Ready"
        MnnEngineState.GENERATING -> "Generating"
        MnnEngineState.ERROR -> "Error"
        null -> "Available"
    }
    return LocalInferenceUiState(
        activityLabel = label,
        modelLabel = when (status.engineState) {
            MnnEngineState.UNLOADED -> "No model loaded"
            MnnEngineState.LOADING -> "Loading model"
            MnnEngineState.READY, MnnEngineState.GENERATING -> status.modelId?.let { "Loaded model · $it" } ?: "Loaded model · Unknown"
            MnnEngineState.ERROR -> status.modelId?.let { "Model · $it" } ?: "Model load failed"
            null -> "Model status unavailable"
        },
        errorLabel = status.errorCode?.let(::formatMnnError),
    )
}

private fun formatMnnError(errorCode: com.nv.pair.mnn.MnnErrorCode): String = when (errorCode) {
    com.nv.pair.mnn.MnnErrorCode.MODEL_LOAD_FAILED -> "Model could not be loaded (MODEL_LOAD_FAILED). See diagnostics."
    else -> "MNN engine error (${errorCode.name}). See diagnostics."
}

internal data class NodeInferenceLabel(val text: String, val active: Boolean)

internal data class ConnectedNodeUi(
    val identity: String,
    val name: String,
    val online: Boolean,
    val address: String?,
    val lastSeen: Long?,
    val inference: List<NodeInferenceLabel>,
)

internal fun projectConnectedNodes(
    nodes: List<PairNode>,
    cluster: ClusterState,
    workloads: List<PairWorkload>,
    relationshipStateLoaded: Boolean,
): List<ConnectedNodeUi> {
    if (!relationshipStateLoaded) return emptyList()
    val localUuid = cluster.identity?.nodeUuid
    return buildPeerViews(nodes, cluster, loaded = true)
        .asSequence()
        .filter { it.nodeUuid != null && it.nodeUuid != localUuid }
        .map { peer ->
            val node = nodes.firstOrNull { it.hostUuid == peer.nodeUuid }
            val inference = if (node == null) {
                emptyList()
            } else {
                val active = workloads.filter { workload ->
                    workload.state.equals("running", ignoreCase = true) &&
                        workload.scheduledOn == node.hostUuid
                }.map { workload ->
                    NodeInferenceLabel("${workload.engine} · ${workload.model}", active = true)
                }
                if (active.isNotEmpty()) active else node.loadedByEngine.flatMap { (engine, models) ->
                    models.map { model -> NodeInferenceLabel("$engine · $model · Loaded", active = false) }
                }
            }
            ConnectedNodeUi(
                identity = peer.nodeUuid.orEmpty(),
                name = peer.displayName,
                online = peer.status == PeerRelationshipStatus.PAIRED_ONLINE,
                address = peer.address,
                lastSeen = nodes.firstOrNull { it.hostUuid == peer.nodeUuid }?.lastSeen
                    ?.takeIf { it > 0 }?.times(1_000)
                    ?: cluster.members.firstOrNull { it.nodeUuid == peer.nodeUuid }?.lastSeen,
                inference = inference,
            )
        }
        .toList()
}
