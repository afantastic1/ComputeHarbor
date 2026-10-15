/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nv.pair.data.ClusterInvite
import com.nv.pair.data.ClusterState
import com.nv.pair.data.PairNode
import com.nv.pair.data.PeerRelationshipStatus
import com.nv.pair.data.buildPeerViews

@Composable
internal fun ClusterManagement(
    cluster: ClusterState,
    discoveredNodes: List<PairNode>,
    relationshipStateLoaded: Boolean,
    enabled: Boolean,
    onCreate: (String) -> Unit,
    onInvite: (String) -> Unit,
    onRespond: (String, Boolean, String) -> Unit,
    onCancel: (String) -> Unit,
    onLeave: () -> Unit,
    onRemove: (String) -> Unit,
) {
    var clusterName by remember { mutableStateOf("") }
    var selectedPeerId by remember { mutableStateOf<String?>(null) }
    var selectedInviteId by remember { mutableStateOf<String?>(null) }
    val identity = cluster.identity
    val peerViews = buildPeerViews(discoveredNodes, cluster, relationshipStateLoaded)
    LaunchedEffect(cluster.invites) {
        if (selectedInviteId == null) {
            selectedInviteId = cluster.invites.lastOrNull { it.state == "pending" }?.inviteId
        }
    }

    Text("Cluster", style = MaterialTheme.typography.headlineMedium)
    Text("Manage trusted compute nodes", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This device", style = MaterialTheme.typography.titleLarge)
            if (identity == null) {
                Text("Start the node to load its cluster identity.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(identity.name, style = MaterialTheme.typography.titleMedium)
                Text(if (cluster.isClustered) "Cluster · ${identity.clusterFriendlyName.ifBlank { identity.clusterId }}" else "Not in a cluster")
                Text("Node ID · ${identity.nodeId}", style = MaterialTheme.typography.bodySmall)
                if (!cluster.isClustered) {
                    OutlinedTextField(
                        value = clusterName,
                        onValueChange = { clusterName = it },
                        label = { Text("Cluster name") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onCreate(clusterName.trim()); clusterName = "" },
                        enabled = enabled && clusterName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Create cluster") }
                }
            }
            if (cluster.busy) CircularProgressIndicator()
            cluster.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    SectionTitle("Trusted Nodes")
    val pairedPeers = peerViews.filter { it.status == PeerRelationshipStatus.PAIRED_ONLINE || it.status == PeerRelationshipStatus.PAIRED_OFFLINE }
    if (pairedPeers.isEmpty()) Text("No trusted nodes yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    pairedPeers.forEach { peer ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                Text(if (peer.status == PeerRelationshipStatus.PAIRED_ONLINE) "Connected" else "Offline")
                peer.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedButton(onClick = { onRemove(peer.nodeUuid ?: peer.nodeId) }, enabled = enabled) { Text("Remove node") }
            }
        }
    }
    SectionTitle("Nearby Devices")
    val nearbyPeers = peerViews.filterNot { it.status == PeerRelationshipStatus.PAIRED_ONLINE || it.status == PeerRelationshipStatus.PAIRED_OFFLINE }
    val actionablePeers = nearbyPeers.filterNot { it.status == PeerRelationshipStatus.INVITE_OUTBOUND || it.status == PeerRelationshipStatus.INVITE_INBOUND }
    if (!relationshipStateLoaded) Text("Start the node to check pairing status and discover nearby devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else if (actionablePeers.isEmpty()) Text("No nearby unpaired devices found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    actionablePeers.forEach { peer ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                peer.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                when (peer.status) {
                    PeerRelationshipStatus.DISCOVERED_UNPAIRED -> Button(
                        onClick = { selectedPeerId = peer.nodeId },
                        enabled = enabled && peer.canInvite,
                    ) { Text("Pair") }
                    PeerRelationshipStatus.IN_OTHER_CLUSTER -> Text("Already in another cluster")
                    PeerRelationshipStatus.INCONSISTENT -> Text(peer.reason ?: "Pairing needs attention", color = MaterialTheme.colorScheme.error)
                    PeerRelationshipStatus.UNKNOWN_IDENTITY -> Text("Device identity is not available yet")
                    PeerRelationshipStatus.INVITE_OUTBOUND, PeerRelationshipStatus.INVITE_INBOUND -> Text("Pairing in progress")
                    PeerRelationshipStatus.PAIRED_ONLINE, PeerRelationshipStatus.PAIRED_OFFLINE -> Unit
                }
            }
        }
    }

    SectionTitle("Pending Requests · ${cluster.invites.count { it.state == "pending" }}")
    val pendingInvites = cluster.invites.filter { it.state == "pending" }
    if (pendingInvites.isEmpty()) Text("No pending pairing requests.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    pendingInvites.forEach { invite ->
        val inbound = invite.pin == null
        val target = discoveredNodes.firstOrNull { it.id == invite.toNodeId || it.hostUuid == invite.toNodeId }
        val inviteName = if (inbound) invite.fromNodeName.ifBlank { invite.fromNodeId }
            else target?.name ?: invite.toNodeId
        Card(Modifier.fillMaxWidth().clickable { selectedInviteId = invite.inviteId }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(inviteName.ifBlank { "Pairing request" }, style = MaterialTheme.typography.titleMedium)
                Text(if (inbound) "Incoming request · tap to enter PIN" else "Outgoing request · tap to view PIN")
                TextButton(onClick = { selectedInviteId = invite.inviteId }) { Text("Open request") }
            }
        }
    }

    if (cluster.isClustered) {
        OutlinedButton(onClick = onLeave, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Leave cluster") }
    }

    val selectedPeer = discoveredNodes.firstOrNull { it.id == selectedPeerId }
    if (selectedPeer != null) {
        AlertDialog(
            onDismissRequest = { selectedPeerId = null },
            title = { Text("Pair with ${selectedPeer.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Review this device before sending a pairing request.")
                    Text("Device type - not reported")
                    Text("Node ID - ${selectedPeer.hostUuid.ifBlank { selectedPeer.id }}", style = MaterialTheme.typography.bodySmall)
                    Text("Trust - ${if (selectedPeer.trusted) "Already trusted" else "Not trusted"}")
                    Text("Address · ${selectedPeer.ipAddress}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("The existing PIN-based pairing protocol will be used.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { onInvite(selectedPeer.id); selectedPeerId = null }, enabled = enabled) { Text("Request pairing") }
            },
            dismissButton = { TextButton(onClick = { selectedPeerId = null }) { Text("Cancel") } },
        )
    }

    val selectedInvite = cluster.invites.firstOrNull { it.inviteId == selectedInviteId }
    selectedInvite?.let { invite ->
        val target = discoveredNodes.firstOrNull { it.id == invite.toNodeId || it.hostUuid == invite.toNodeId }
        val inviteName = if (invite.pin == null) invite.fromNodeName.ifBlank { invite.fromNodeId }
            else target?.name ?: invite.toNodeId
        key(invite.inviteId) {
            PairingInviteDialog(invite, inviteName, cluster.error, enabled, onRespond, onCancel) { selectedInviteId = null }
        }
    }
}

@Composable
private fun PairingInviteDialog(
    invite: ClusterInvite,
    inviteName: String,
    operationError: String?,
    enabled: Boolean,
    onRespond: (String, Boolean, String) -> Unit,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember(invite.inviteId) { mutableStateOf("") }
    var responding by remember(invite.inviteId) { mutableStateOf(false) }
    var canceling by remember(invite.inviteId) { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (enabled) {
            responding = false
            canceling = false
        }
    }
    val inbound = invite.pin == null
    val finished = invite.state != "pending"
    val paired = invite.state == "paired" || invite.state == "accepted"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (paired) "Pairing successful" else if (finished) "Pairing ${invite.state}" else if (inbound) "Pairing request" else "Waiting for confirmation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(inviteName.ifBlank { "Unknown device" })
                when {
                    paired -> Text("${inviteName.ifBlank { "This device" }} has completed pairing. Trusted membership is shown from the cluster snapshot.")
                    finished -> Text("Request status · ${invite.state}${invite.reason.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}")
                    inbound -> {
                        Text("Enter the six-digit PIN shown by the inviting device.")
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                            label = { Text("6-digit PIN") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (invite.reason == "incorrect-pin") Text("That PIN was not accepted. Check it and try again.", color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        Text("Share this PIN with the other device to complete pairing.")
                        Text(invite.pin.orEmpty(), style = MaterialTheme.typography.headlineLarge)
                        Text("This invite can expire. Request a new pairing if it no longer completes.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                finished -> TextButton(onClick = onDismiss) { Text("Done") }
                inbound -> Button(
                    onClick = {
                        if (!responding) {
                            responding = true
                            onRespond(invite.inviteId, true, pin)
                            pin = ""
                        }
                    },
                    enabled = enabled && !responding && pin.length == 6,
                ) { Text(if (responding) "Verifying…" else "Accept") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            when {
                !finished && inbound -> TextButton(
                    onClick = { if (!responding) { responding = true; onRespond(invite.inviteId, false, "") } },
                    enabled = enabled && !responding,
                ) { Text(if (responding) "Declining…" else "Decline") }
                !finished -> TextButton(
                    onClick = { if (!canceling) { canceling = true; onCancel(invite.inviteId) } },
                    enabled = enabled && !canceling,
                ) { Text(if (canceling) "Cancelling…" else "Cancel invite") }
            }
        },
    )
}
