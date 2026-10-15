/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nv.pair.data.ClusterInvite
import com.nv.pair.data.ClusterIdentity
import com.nv.pair.data.ClusterState
import com.nv.pair.data.PairNode
import com.nv.pair.ui.theme.PAIRTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClusterManagementUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stoppedClusterShowsStartGuidanceInsteadOfLoadingForever() {
        composeRule.setContent {
            MaterialTheme {
                ClusterManagement(
                    cluster = ClusterState(),
                    discoveredNodes = emptyList(),
                    relationshipStateLoaded = false,
                    enabled = false,
                    onCreate = {},
                    onInvite = {},
                    onRespond = { _, _, _ -> },
                    onCancel = {},
                    onLeave = {},
                    onRemove = {},
                )
            }
        }

        composeRule.onNodeWithText("Start the node to check pairing status and discover nearby devices.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Checking pairing status…").assertCountEquals(0)
    }

    @Test
    fun selectedNavigationSurfaceUsesHarborTealTint() {
        var selectedContainer = Color.Unspecified
        composeRule.setContent {
            PAIRTheme {
                selectedContainer = MaterialTheme.colorScheme.secondaryContainer
            }
        }

        composeRule.runOnIdle {
            assertEquals(Color(0xFF16463F), selectedContainer)
        }
    }

    @Test
    fun inboundInviteRequiresSixDigitsAndRespondsToItsInviteId() {
        var response: Triple<String, Boolean, String>? = null
        composeRule.setContent {
            MaterialTheme {
                ClusterManagement(
                    cluster = ClusterState(invites = listOf(inboundInvite("invite-in-1"))),
                    discoveredNodes = emptyList(),
                    relationshipStateLoaded = true,
                    enabled = true,
                    onCreate = {},
                    onInvite = {},
                    onRespond = { inviteId, accept, pin -> response = Triple(inviteId, accept, pin) },
                    onCancel = {},
                    onLeave = {},
                    onRemove = {},
                )
            }
        }

        composeRule.onNodeWithText("Pairing request").assertIsDisplayed()
        composeRule.onNodeWithText("Accept").assertIsNotEnabled()
        composeRule.onNodeWithText("6-digit PIN").performTextInput("12345")
        composeRule.onNodeWithText("Accept").assertIsNotEnabled()
        composeRule.onNodeWithText("6-digit PIN").performTextInput("6")
        composeRule.onNodeWithText("Accept").assertIsEnabled().performClick()

        assertEquals(Triple("invite-in-1", true, "123456"), response)
    }

    @Test
    fun outboundInviteCancellationUsesTheSelectedInviteId() {
        var canceledInviteId: String? = null
        composeRule.setContent {
            MaterialTheme {
                ClusterManagement(
                    cluster = ClusterState(invites = listOf(
                        ClusterInvite(
                            inviteId = "invite-out-2",
                            fromNodeId = "phone",
                            toNodeId = "desktop",
                            pin = "654321",
                            state = "pending",
                        ),
                    )),
                    discoveredNodes = emptyList(),
                    relationshipStateLoaded = true,
                    enabled = true,
                    onCreate = {},
                    onInvite = {},
                    onRespond = { _, _, _ -> },
                    onCancel = { canceledInviteId = it },
                    onLeave = {},
                    onRemove = {},
                )
            }
        }

        composeRule.onNodeWithText("Waiting for confirmation").assertIsDisplayed()
        composeRule.onNodeWithText("654321").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel invite").performClick()

        assertEquals("invite-out-2", canceledInviteId)
    }

    @Test
    fun switchingBetweenInvitesDoesNotCarryPinAcrossInviteIds() {
        var response: Triple<String, Boolean, String>? = null
        var cluster by mutableStateOf(ClusterState(invites = listOf(
            inboundInvite("invite-in-a", "Desktop A"),
            inboundInvite("invite-in-b", "Desktop B"),
        )))
        composeRule.setContent {
            MaterialTheme {
                ClusterManagement(
                    cluster = cluster,
                    discoveredNodes = emptyList(),
                    relationshipStateLoaded = true,
                    enabled = true,
                    onCreate = {},
                    onInvite = {},
                    onRespond = { inviteId, accept, pin ->
                        response = Triple(inviteId, accept, pin)
                        if (!accept) {
                            cluster = cluster.copy(invites = cluster.invites.map { invite ->
                                if (invite.inviteId == inviteId) invite.copy(state = "declined") else invite
                            })
                        }
                    },
                    onCancel = {},
                    onLeave = {},
                    onRemove = {},
                )
            }
        }

        composeRule.onNodeWithText("6-digit PIN").performTextInput("123456")
        composeRule.onNodeWithText("Decline").performClick()
        composeRule.onNodeWithText("Pairing declined").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("Open request").performClick()
        composeRule.onNodeWithText("Pairing request").assertIsDisplayed()
        composeRule.onNodeWithText("6-digit PIN").performTextInput("222222")
        composeRule.onNodeWithText("Accept").performClick()

        assertEquals(Triple("invite-in-a", true, "222222"), response)
    }

    @Test
    fun acceptedInviteShowsRepositoryBackedSuccessState() {
        var cluster by androidx.compose.runtime.mutableStateOf(
            ClusterState(invites = listOf(inboundInvite("invite-success"))),
        )
        composeRule.setContent {
            MaterialTheme {
                ClusterManagement(
                    cluster = cluster,
                    discoveredNodes = emptyList(),
                    relationshipStateLoaded = true,
                    enabled = true,
                    onCreate = {},
                    onInvite = {},
                    onRespond = { inviteId, _, _ ->
                        cluster = cluster.copy(invites = cluster.invites.map { invite ->
                            if (invite.inviteId == inviteId) invite.copy(state = "paired") else invite
                        })
                    },
                    onCancel = {},
                    onLeave = {},
                    onRemove = {},
                )
            }
        }

        composeRule.onNodeWithText("6-digit PIN").performTextInput("123456")
        composeRule.onNodeWithText("Accept").performClick()

        composeRule.onNodeWithText("Pairing successful").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
    }

    @Test
    fun incorrectPinStateExplainsRetryWithoutClaimingSuccess() {
        composeRule.setContent {
            MaterialTheme {
                ClusterManagement(
                    cluster = ClusterState(
                        invites = listOf(inboundInvite("invite-failed").copy(reason = "incorrect-pin")),
                    ),
                    discoveredNodes = emptyList(),
                    relationshipStateLoaded = true,
                    enabled = true,
                    onCreate = {},
                    onInvite = {},
                    onRespond = { _, _, _ ->},
                    onCancel = {},
                    onLeave = {},
                    onRemove = {},
                )
            }
        }

        composeRule.onNodeWithText("That PIN was not accepted. Check it and try again.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Pairing successful").assertCountEquals(0)
    }

    @Test
    fun nearbyPairReviewRequiresExplicitRequestAndCancelDoesNotInvite() {
        val peer = PairNode(
            id = "discovery-id-17",
            name = "Studio desktop",
            hostUuid = "node-uuid-stable-17",
            ipAddress = "192.168.1.42",
            port = 14325,
        )
        val invitations = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                ClusterManagement(
                    cluster = ClusterState(identity = ClusterIdentity(
                        nodeUuid = "phone-uuid",
                        nodeId = "phone-id",
                        name = "Phone",
                    )),
                    discoveredNodes = listOf(peer),
                    relationshipStateLoaded = true,
                    enabled = true,
                    onCreate = {},
                    onInvite = invitations::add,
                    onRespond = { _, _, _ -> },
                    onCancel = {},
                    onLeave = {},
                    onRemove = {},
                )
            }
        }

        composeRule.onNodeWithText("Pair").performClick()
        composeRule.onNodeWithText("Pair with Studio desktop").assertIsDisplayed()
        composeRule.onNodeWithText("Node ID - node-uuid-stable-17").assertIsDisplayed()
        composeRule.onAllNodesWithText("192.168.1.42", substring = true).assertCountEquals(2)
        assertEquals(emptyList<String>(), invitations)

        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), invitations)
        composeRule.onNodeWithText("Pair").performClick()
        composeRule.onNodeWithText("Request pairing").performTouchInput { doubleClick() }

        assertEquals(listOf("discovery-id-17"), invitations)
        composeRule.onAllNodesWithText("Pair with Studio desktop").assertCountEquals(0)
    }

    @Test
    fun pendingInviteDialogRecoversAfterScreenRecreationWithoutSendingAgain() {
        val peer = PairNode(
            id = "desktop-discovery-id",
            name = "Studio desktop",
            hostUuid = "desktop-stable-uuid",
            ipAddress = "192.168.1.42",
        )
        var invitationCount = 0
        var generation by mutableStateOf(0)
        var cluster by mutableStateOf(ClusterState(identity = ClusterIdentity(
            nodeUuid = "phone-stable-uuid",
            nodeId = "phone-id",
            name = "Phone",
        )))
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.runtime.key(generation) {
                    ClusterManagement(
                        cluster = cluster,
                        discoveredNodes = listOf(peer),
                        relationshipStateLoaded = true,
                        enabled = true,
                        onCreate = {},
                        onInvite = { nodeId ->
                            invitationCount += 1
                            cluster = cluster.copy(invites = listOf(ClusterInvite(
                                inviteId = "invite-pending-1",
                                fromNodeId = "phone-id",
                                fromNodeUuid = "phone-stable-uuid",
                                toNodeId = nodeId,
                                pin = "314159",
                                state = "pending",
                            )))
                        },
                        onRespond = { _, _, _ -> },
                        onCancel = {},
                        onLeave = {},
                        onRemove = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Pair").performClick()
        composeRule.onNodeWithText("Request pairing").performClick()
        composeRule.onNodeWithText("Waiting for confirmation").assertIsDisplayed()
        composeRule.runOnIdle { generation += 1 }

        composeRule.onNodeWithText("Waiting for confirmation").assertIsDisplayed()
        composeRule.onNodeWithText("314159").assertIsDisplayed()
        assertEquals(1, invitationCount)
    }

    private fun inboundInvite(inviteId: String, name: String = "Test desktop") = ClusterInvite(
        inviteId = inviteId,
        fromNodeId = "desktop",
        fromNodeName = name,
        toNodeId = "phone",
        state = "pending",
    )
}
