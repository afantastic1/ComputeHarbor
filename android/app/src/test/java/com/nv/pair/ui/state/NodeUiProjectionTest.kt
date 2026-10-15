/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.ui.state

import com.nv.pair.data.ClusterIdentity
import com.nv.pair.data.ClusterMember
import com.nv.pair.data.ClusterState
import com.nv.pair.data.PairNode
import com.nv.pair.data.PairWorkload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeUiProjectionTest {
    @Test
    fun loadedModelIsNotReportedAsActiveInference() {
        val node = PairNode(
            id = "desktop-node",
            hostUuid = "desktop-uuid",
            name = "Office PC",
            ipAddress = "192.0.2.10",
            trusted = true,
            modelsByEngine = mapOf("lmstudio" to listOf("model-a")),
            loadedByEngine = mapOf("lmstudio" to listOf("model-a")),
        )
        val result = projectConnectedNodes(
            nodes = listOf(node),
            cluster = pairedCluster(),
            workloads = emptyList(),
            relationshipStateLoaded = true,
        )

        assertEquals(1, result.size)
        assertEquals("desktop-uuid", result.single().identity)
        assertEquals("lmstudio · model-a · Loaded", result.single().inference.single().text)
        assertFalse(result.single().inference.single().active)
    }

    @Test
    fun runningWorkloadIsAttributedByStableNodeIdentity() {
        val node = PairNode(id = "desktop-node", hostUuid = "desktop-uuid", name = "Office PC", ipAddress = "192.0.2.10", trusted = true)
        val workload = PairWorkload(
            id = "job-1", model = "model-a", engine = "lmstudio", runId = "run-1",
            state = "running", originatedFrom = "phone-uuid", scheduledOn = "desktop-uuid",
            createdAt = 1, startedAt = 2, completedAt = null, error = null,
        )

        val result = projectConnectedNodes(listOf(node), pairedCluster(), listOf(workload), true)

        assertTrue(result.single().inference.single().active)
        assertEquals("lmstudio · model-a", result.single().inference.single().text)
    }

    @Test
    fun workloadScheduledToUnrelatedNodeIdIsNotAttributedByNameOrAlias() {
        val node = PairNode(id = "desktop-node", hostUuid = "desktop-uuid", name = "Office PC", ipAddress = "192.0.2.10", trusted = true)
        val workload = PairWorkload(
            id = "job-2", model = "model-a", engine = "lmstudio", runId = "run-2",
            state = "running", originatedFrom = "phone-uuid", scheduledOn = "desktop-node",
            createdAt = 1, startedAt = 2, completedAt = null, error = null,
        )

        val result = projectConnectedNodes(listOf(node), pairedCluster(), listOf(workload), true)

        assertTrue(result.single().inference.isEmpty())
    }

    @Test
    fun stoppingWithRunningWorkloadRequiresConfirmation() {
        val workload = PairWorkload(
            id = "job-running", model = "model-a", engine = "mnn", runId = "run-3",
            state = "running", originatedFrom = "phone-uuid", scheduledOn = "phone-uuid",
            createdAt = 1, startedAt = 2, completedAt = null, error = null,
        )

        assertTrue(shouldConfirmNodeStop(runtimeRunning = true, workloads = listOf(workload)))
    }

    @Test
    fun stoppingWithoutRunningWorkloadsDoesNotRequireConfirmation() {
        val queued = PairWorkload(
            id = "job-queued", model = "model-a", engine = "mnn", runId = "run-4",
            state = "queued", originatedFrom = "phone-uuid", scheduledOn = "phone-uuid",
            createdAt = 1, startedAt = null, completedAt = null, error = null,
        )

        assertFalse(shouldConfirmNodeStop(runtimeRunning = true, workloads = listOf(queued)))
    }

    @Test
    fun sameDisplayNameWithDifferentNodeIdsRemainsDistinct() {
        val first = PairNode(id = "pc-1", hostUuid = "uuid-1", name = "Desktop", ipAddress = "192.0.2.10", trusted = true)
        val second = PairNode(id = "pc-2", hostUuid = "uuid-2", name = "Desktop", ipAddress = "192.0.2.11", trusted = true)
        val cluster = pairedCluster().copy(members = listOf(
            ClusterMember(id = "pc-1", nodeUuid = "uuid-1", name = "Desktop", clusterId = "cluster-1"),
            ClusterMember(id = "pc-2", nodeUuid = "uuid-2", name = "Desktop", clusterId = "cluster-1"),
        ))

        val result = projectConnectedNodes(listOf(first, second), cluster, emptyList(), true)

        assertEquals(setOf("uuid-1", "uuid-2"), result.map(ConnectedNodeUi::identity).toSet())
    }

    private fun pairedCluster() = ClusterState(
        identity = ClusterIdentity(nodeUuid = "phone-uuid", nodeId = "phone", name = "Phone", clusterId = "cluster-1"),
        members = listOf(ClusterMember(id = "desktop-node", nodeUuid = "desktop-uuid", name = "Office PC", clusterId = "cluster-1")),
    )
}
