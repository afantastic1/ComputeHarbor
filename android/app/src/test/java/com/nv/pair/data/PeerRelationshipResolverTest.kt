/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerRelationshipResolverTest {
    @Test
    fun projectsConfirmedMembersAsPairedAndKeepsOfflineMembersVisible() {
        val state = clusteredState(members = listOf(
            ClusterMember(id = "pc", nodeUuid = "pc-uuid", name = "PC", clusterId = "cluster-a"),
            ClusterMember(id = "laptop", nodeUuid = "laptop-uuid", name = "Laptop", clusterId = "cluster-a"),
        ))
        val discovered = listOf(PairNode(
            id = "pc",
            name = "PC",
            hostUuid = "pc-uuid",
            ipAddress = "192.0.2.5",
            trusted = true,
            clustered = true,
            clusterId = "cluster-a",
        ))

        val peers = buildPeerViews(discovered, state, loaded = true)

        assertEquals(2, peers.size)
        assertEquals(PeerRelationshipStatus.PAIRED_ONLINE, peers.first().status)
        assertFalse(peers.first().canInvite)
        assertEquals("laptop-uuid", peers.last().nodeUuid)
        assertEquals(PeerRelationshipStatus.PAIRED_OFFLINE, peers.last().status)
    }

    @Test
    fun neverOffersPairForUnverifiedOrDifferentClusterIdentities() {
        val state = clusteredState()
        val discovered = listOf(
            PairNode(id = "unknown", name = "Unknown", ipAddress = "192.0.2.6"),
            PairNode(
                id = "foreign",
                name = "Foreign",
                hostUuid = "foreign-uuid",
                ipAddress = "192.0.2.7",
                clustered = true,
                clusterId = "cluster-b",
            ),
        )

        val peers = buildPeerViews(discovered, state, loaded = true)

        assertEquals(PeerRelationshipStatus.UNKNOWN_IDENTITY, peers[0].status)
        assertFalse(peers[0].canInvite)
        assertEquals(PeerRelationshipStatus.IN_OTHER_CLUSTER, peers[1].status)
        assertFalse(peers[1].canInvite)
    }

    @Test
    fun allowsInviteOnlyForIdentifiedUnclusteredPeerInAnExistingCluster() {
        val peers = buildPeerViews(
            listOf(PairNode(
                id = "pc",
                name = "PC",
                hostUuid = "pc-uuid",
                ipAddress = "192.0.2.5",
            )),
            clusteredState(),
            loaded = true,
        )

        assertTrue(peers.single().canInvite)
        assertEquals(PeerRelationshipStatus.DISCOVERED_UNPAIRED, peers.single().status)
    }

    @Test
    fun sameDisplayNameWithDifferentStableUuidsRemainsTwoInviteCandidates() {
        val peers = buildPeerViews(
            listOf(
                PairNode(id = "desktop-a", name = "Studio", hostUuid = "uuid-a", ipAddress = "192.0.2.8"),
                PairNode(id = "desktop-b", name = "Studio", hostUuid = "uuid-b", ipAddress = "192.0.2.9"),
            ),
            clusteredState(),
            loaded = true,
        )

        assertEquals(2, peers.size)
        assertEquals(setOf("uuid-a", "uuid-b"), peers.mapNotNull { it.nodeUuid }.toSet())
        assertTrue(peers.all { it.status == PeerRelationshipStatus.DISCOVERED_UNPAIRED && it.canInvite })
    }

    @Test
    fun duplicateStableUuidIsShownAsInconsistentAndCannotBeInvited() {
        val peers = buildPeerViews(
            listOf(
                PairNode(id = "desktop-a", name = "Studio", hostUuid = "duplicate-uuid", ipAddress = "192.0.2.8"),
                PairNode(id = "desktop-b", name = "Other label", hostUuid = "duplicate-uuid", ipAddress = "192.0.2.9"),
            ),
            clusteredState(),
            loaded = true,
        )

        assertEquals(1, peers.size)
        assertEquals(PeerRelationshipStatus.INCONSISTENT, peers.single().status)
        assertFalse(peers.single().canInvite)
    }

    @Test
    fun inviteDecisionRejectsExistingMemberAndMissingStableIdentity() {
        val state = clusteredState(members = listOf(
            ClusterMember(id = "pc", nodeUuid = "pc-uuid", clusterId = "cluster-a"),
        ))
        val member = PairNode(
            id = "pc",
            name = "PC",
            hostUuid = "pc-uuid",
            ipAddress = "192.0.2.5",
            trusted = true,
            clustered = true,
            clusterId = "cluster-a",
        )
        val unknown = PairNode(id = "unknown", name = "Unknown", ipAddress = "192.0.2.6")

        assertEquals(InviteDecision.NotAllowed("This device is already paired."), checkInviteDecision(member, state))
        assertEquals(
            InviteDecision.NotAllowed("The discovered device has no stable identity."),
            checkInviteDecision(unknown, state),
        )
    }

    private fun clusteredState(members: List<ClusterMember> = emptyList()) = ClusterState(
        identity = ClusterIdentity(
            nodeUuid = "phone-uuid",
            nodeId = "PHONE",
            name = "Phone",
            clusterId = "cluster-a",
        ),
        members = members,
    )
}
