/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nv.pair.data.ClusterState
import com.nv.pair.data.PairNode
import com.nv.pair.data.PairWorkload
import com.nv.pair.models.GatewayModel
import com.nv.pair.models.GatewayModelRepository
import com.nv.pair.models.ModelHubScreen
import com.nv.pair.mnn.BackendChoice
import com.nv.pair.mnn.EffectiveBackendSelection
import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.MnnCapabilitySnapshot
import com.nv.pair.runtime.GatewayTokenStore
import com.nv.pair.runtime.MnnLocalEngineStatus
import com.nv.pair.runtime.PairRuntimeController
import com.nv.pair.runtime.PairRuntimeState
import com.nv.pair.runtime.RuntimePhase
import com.nv.pair.ui.theme.PAIRTheme
import com.nv.pair.ui.state.projectConnectedNodes
import com.nv.pair.ui.state.projectLocalInference
import com.nv.pair.ui.state.projectWorkloadStatus
import com.nv.pair.ui.state.shouldConfirmNodeStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.Duration
import com.nv.pair.network.AndroidNetworkContext

class MainActivity : ComponentActivity() {
    private lateinit var runtimeController: PairRuntimeController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        runtimeController = PairRuntimeController(applicationContext)
        setContent {
            PAIRTheme {
                val scope = rememberCoroutineScope()
                val state by runtimeController.state.collectAsStateWithLifecycle()
                val nodes by runtimeController.nodes.collectAsStateWithLifecycle()
                val cluster by runtimeController.cluster.collectAsStateWithLifecycle()
                val workloads by runtimeController.workloads.collectAsStateWithLifecycle()
                val cloudProviderSettings by runtimeController.cloudProviderSettings.collectAsStateWithLifecycle()
                val backendChoice by runtimeController.backendChoice.collectAsStateWithLifecycle(initialValue = BackendChoice.Auto)
                val capabilities by runtimeController.mnnCapabilities.collectAsStateWithLifecycle()
                val effectiveBackend by runtimeController.effectiveBackendSelection.collectAsStateWithLifecycle()
                val mnnLocalEngine by runtimeController.mnnLocalEngine.collectAsStateWithLifecycle()
                var gatewayModels by remember { mutableStateOf<List<GatewayModel>?>(null) }
                var gatewayCheckCompleted by remember { mutableStateOf(false) }
                LaunchedEffect(state.phase) {
                    gatewayModels = null
                    gatewayCheckCompleted = false
                    if (state.phase == RuntimePhase.RUNNING) {
                        while (true) {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    GatewayModelRepository(accessToken = GatewayTokenStore(applicationContext).getOrCreate()).models()
                                }
                            }
                            gatewayModels = result.getOrNull()
                            gatewayCheckCompleted = true
                            delay(GATEWAY_MODEL_REFRESH_MILLIS)
                        }
                    }
                }
                PairAppScreen(
                    state = state,
                    nodes = nodes,
                    cluster = cluster,
                    workloads = workloads,
                    gatewayModels = gatewayModels,
                    gatewayCheckCompleted = gatewayCheckCompleted,
                    backendChoice = backendChoice,
                    capabilities = capabilities,
                    effectiveBackendSelection = effectiveBackend,
                    mnnLocalEngine = mnnLocalEngine,
                    cloudProviderSettings = cloudProviderSettings,
                    onStart = ::startRuntime,
                    onStop = runtimeController::stop,
                    onCreateCluster = runtimeController::createCluster,
                    onInvite = runtimeController::invite,
                    onRespond = runtimeController::respond,
                    onCancelInvite = runtimeController::cancelInvite,
                    onLeave = runtimeController::leaveCluster,
                    onRemove = runtimeController::removeMember,
                    onManualBackendChange = { backend -> scope.launch { runtimeController.setManualBackend(backend) } },
                    onResetBackendToAuto = { scope.launch { runtimeController.resetBackendToAuto() } },
                    onReprobeOpenCl = runtimeController::reprobeOpenCl,
                    onCloudEnabledChange = runtimeController::setCloudEnabled,
                    onCloudPolicyChange = runtimeController::setCloudPolicy,
                    onSaveCloudProvider = runtimeController::saveCloudProvider,
                    onRemoveCloudProvider = runtimeController::removeCloudProvider,
                    onTestCloudProvider = runtimeController::testCloudProvider,
                )
            }
        }
    }

    private fun startRuntime() {
        runtimeController.start()
    }
}

private enum class Destination(val label: String) { HOME("Home"), CLUSTER("Cluster"), MODELS("Models"), JOBS("Recent Jobs"), SETTINGS("Settings") }

@Composable
private fun PairAppScreen(
    state: PairRuntimeState,
    nodes: List<PairNode>,
    cluster: ClusterState,
    workloads: List<PairWorkload>,
    gatewayModels: List<GatewayModel>?,
    gatewayCheckCompleted: Boolean,
    backendChoice: BackendChoice,
    capabilities: MnnCapabilitySnapshot,
    effectiveBackendSelection: EffectiveBackendSelection,
    mnnLocalEngine: MnnLocalEngineStatus,
    cloudProviderSettings: com.nv.pair.rpc.CloudProviderSettings?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCreateCluster: (String) -> Unit,
    onInvite: (String) -> Unit,
    onRespond: (String, Boolean, String) -> Unit,
    onCancelInvite: (String) -> Unit,
    onLeave: () -> Unit,
    onRemove: (String) -> Unit,
    onManualBackendChange: (MnnBackend) -> Unit,
    onResetBackendToAuto: () -> Unit,
    onReprobeOpenCl: () -> Unit,
    onCloudEnabledChange: (Boolean) -> Unit,
    onCloudPolicyChange: (String) -> Unit,
    onSaveCloudProvider: (org.json.JSONObject, String) -> Unit,
    onRemoveCloudProvider: (String) -> Unit,
    onTestCloudProvider: (String) -> Unit,
) {
    var destination by remember { mutableStateOf(Destination.HOME) }
    val destinationScrollState = remember(destination) { ScrollState(0) }
    val compactHeader = LocalConfiguration.current.screenWidthDp <= 360 || LocalDensity.current.fontScale >= 1.3f
    val transitioning = state.phase in setOf(RuntimePhase.STARTING, RuntimePhase.WAITING_READY, RuntimePhase.STOPPING)
    val context = LocalContext.current
    BackHandler(destination != Destination.HOME) { destination = Destination.HOME }

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(Destination.HOME, Destination.CLUSTER, Destination.MODELS).forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {},
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).testTag("destination_scroll")
                .verticalScroll(destinationScrollState)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val headerAction = if (destination != Destination.SETTINGS) "Settings" else "Done"
            val headerSubtitle = if (destination == Destination.HOME) "Mobile Compute Node" else destination.label
            if (compactHeader) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "ComputeHarbor",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(headerSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = { destination = if (destination == Destination.SETTINGS) Destination.HOME else Destination.SETTINGS }) {
                            Text(headerAction)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ComputeHarbor", style = MaterialTheme.typography.headlineSmall)
                        Text(headerSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { destination = if (destination == Destination.SETTINGS) Destination.HOME else Destination.SETTINGS }) {
                        Text(headerAction)
                    }
                }
            }

            when (destination) {
                Destination.HOME -> HomeScreen(
                    state, nodes, cluster, workloads, gatewayModels, gatewayCheckCompleted, mnnLocalEngine,
                    onStart, onStop,
                    onViewAllJobs = { destination = Destination.JOBS },
                    onCopyEndpoint = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("PAIR Local API", GATEWAY_ENDPOINT))
                    },
                )
                Destination.CLUSTER -> ClusterManagement(
                    cluster = cluster,
                    discoveredNodes = nodes,
                    relationshipStateLoaded = state.phase == RuntimePhase.RUNNING,
                    enabled = state.phase == RuntimePhase.RUNNING && !cluster.busy,
                    onCreate = onCreateCluster,
                    onInvite = onInvite,
                    onRespond = onRespond,
                    onCancel = onCancelInvite,
                    onLeave = onLeave,
                    onRemove = onRemove,
                )
                Destination.MODELS -> ModelHubScreen(
                    nodes = nodes,
                    gatewayModels = gatewayModels,
                    cloudProviderSettings = cloudProviderSettings,
                    onCloudEnabledChange = onCloudEnabledChange,
                    onCloudPolicyChange = onCloudPolicyChange,
                    onSaveCloudProvider = onSaveCloudProvider,
                    onRemoveCloudProvider = onRemoveCloudProvider,
                    onTestCloudProvider = onTestCloudProvider,
                    backendChoice = backendChoice,
                    capabilities = capabilities,
                    effectiveBackendSelection = effectiveBackendSelection,
                    localEngineStatus = mnnLocalEngine,
                    serviceRunning = state.phase == RuntimePhase.RUNNING,
                    onManualBackendChange = onManualBackendChange,
                    onResetBackendToAuto = onResetBackendToAuto,
                    onReprobeOpenCl = onReprobeOpenCl,
                )
                Destination.JOBS -> JobsScreen(workloads)
                Destination.SETTINGS -> SettingsScreen(
                    state, cluster, backendChoice, capabilities, effectiveBackendSelection, mnnLocalEngine,
                    onCopyNodeId = {
                        val nodeId = cluster.identity?.nodeUuid
                        if (!nodeId.isNullOrBlank()) {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("ComputeHarbor node ID", nodeId))
                        }
                    },
                    onCopyEndpoint = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("PAIR Local API", GATEWAY_ENDPOINT))
                    },
                )
            }
            if (transitioning && destination == Destination.HOME) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text(" ${phaseLabel(state.phase)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HomeScreen(
    state: PairRuntimeState,
    nodes: List<PairNode>,
    cluster: ClusterState,
    workloads: List<PairWorkload>,
    gatewayModels: List<GatewayModel>?,
    gatewayCheckCompleted: Boolean,
    localEngine: MnnLocalEngineStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onViewAllJobs: () -> Unit,
    onCopyEndpoint: () -> Unit,
) {
    var stopConfirmationVisible by remember { mutableStateOf(false) }
    var selectedNode by remember { mutableStateOf<com.nv.pair.ui.state.ConnectedNodeUi?>(null) }
    val running = state.phase == RuntimePhase.RUNNING
    val transitioning = state.phase in setOf(RuntimePhase.STARTING, RuntimePhase.WAITING_READY, RuntimePhase.STOPPING)
    val localInference = remember(localEngine, running) { projectLocalInference(localEngine, running) }
    val shouldConfirmStop = shouldConfirmNodeStop(running || state.desiredRunning, workloads)
    val activeWorkloadCount = workloads.count { it.state.equals("running", ignoreCase = true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Node Status", style = MaterialTheme.typography.titleLarge)
            Text(phaseLabel(state.phase), style = MaterialTheme.typography.headlineSmall,
                color = if (state.phase == RuntimePhase.STARTUP_FAILED || state.phase == RuntimePhase.CRASHED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.version?.let { Text("Broker · $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(
                onClick = {
                    if (running || state.desiredRunning) {
                        if (shouldConfirmStop) stopConfirmationVisible = true else onStop()
                    } else onStart()
                },
                enabled = !transitioning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = if (running || state.desiredRunning) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ) else ButtonDefaults.buttonColors(),
            ) { Text(if (running || state.desiredRunning) "Stop node" else if (state.phase == RuntimePhase.RESTART_BACKOFF) "Stop reconnecting" else "Start node") }
        }
    }
    if (stopConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { stopConfirmationVisible = false },
            title = { Text("Stop node while inference is active?") },
            text = {
                Text("Stopping now will interrupt $activeWorkloadCount running ${if (activeWorkloadCount == 1) "request" else "requests"}. The node will stop accepting new work.")
            },
            confirmButton = {
                TextButton(
                    onClick = { stopConfirmationVisible = false; onStop() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop node") }
            },
            dismissButton = { TextButton(onClick = { stopConfirmationVisible = false }) { Text("Keep running") } },
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Local API", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        !running -> "Unavailable"
                        gatewayModels != null -> "Available"
                        gatewayCheckCompleted -> "Unavailable"
                        else -> "Checking"
                    },
                    color = if (running && gatewayModels != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("OpenAI-compatible gateway", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(GATEWAY_ENDPOINT, style = MaterialTheme.typography.bodyMedium)
            Text("Loopback only · available to apps on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Models · ${gatewayModels?.size?.toString() ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCopyEndpoint, enabled = running) { Text("Copy endpoint") }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Local Inference", style = MaterialTheme.typography.titleLarge)
            Text("MNN · ${localInference.activityLabel}")
            Text(localInference.modelLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            localEngine.backend?.let { Text("Effective engine · ${it.name}") }
            localInference.errorLabel?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    val connectedNodes = remember(nodes, cluster, workloads, running) {
        projectConnectedNodes(nodes, cluster, workloads, running)
    }
    val localIdentity = cluster.identity
    SectionTitle("Connected Nodes - ${connectedNodes.size + if (localIdentity != null) 1 else 0}")
    if (localIdentity != null) {
        val localJobs = workloads.filter {
            it.state.equals("running", ignoreCase = true) && it.scheduledOn == localIdentity.nodeUuid
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("This device · ${localIdentity.name}", style = MaterialTheme.typography.titleMedium)
                Text(if (running) "Online" else "Offline",
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Local device - Android", style = MaterialTheme.typography.bodySmall)
                Text("127.0.0.1", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (localJobs.isEmpty()) Text("MNN · ${if (localEngine.available) "Available" else "Unavailable"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else localJobs.forEach { Text("Active Inference · ${it.engine} · ${it.model}") }
            }
        }
    }
    selectedNode?.let { node ->
        AlertDialog(
            onDismissRequest = { selectedNode = null },
            title = { Text(node.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Remote device - type not reported")
                    Text("Node ID: ${node.identity}")
                    Text(if (node.online) "Online" else "Offline")
                    node.address?.let { Text("Address: $it") }
                    node.lastSeen?.let { Text("Last seen ${formatLastSeen(it)}") }
                    Text("Active Inference")
                    if (node.inference.isEmpty()) Text("No active inference")
                    else node.inference.forEach { inference -> Text("${if (inference.active) "Running" else "Loaded"} - ${inference.text}") }
                }
            },
            confirmButton = { TextButton(onClick = { selectedNode = null }) { Text("Close") } },
        )
    }
    if (connectedNodes.isEmpty()) {
        Text(if (running) "No paired nodes are currently discovered." else "Start the node to discover cluster members.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    connectedNodes.forEach { node ->
        Card(Modifier.fillMaxWidth().clickable { selectedNode = node }) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(node.name, style = MaterialTheme.typography.titleMedium)
                Text(if (node.online) "Online" else "Offline",
                    color = if (node.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Remote device - type not reported", style = MaterialTheme.typography.bodySmall)
                node.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!node.online) node.lastSeen?.let { Text("Last seen ${formatLastSeen(it)}", style = MaterialTheme.typography.bodySmall) }
                when {
                    node.inference.isEmpty() -> Text("No active inference", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> node.inference.forEach { inference ->
                        Text("${if (inference.active) "Active Inference" else "Loaded"} · ${inference.text}")
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Recent Jobs")
        TextButton(onClick = onViewAllJobs) { Text("View all") }
    }
    val recentWorkloads = remember(workloads) {
        workloads.sortedWith(
            compareBy<PairWorkload> { if (it.state.equals("running", ignoreCase = true)) 0 else 1 }
                .thenByDescending { it.createdAt },
        ).take(5)
    }
    if (recentWorkloads.isEmpty()) Text("No requests have been routed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else recentWorkloads.forEach { WorkloadCard(it) }
}

@Preview(name = "Home - stopped", showBackground = true)
@Composable
private fun HomeScreenStoppedPreview() {
    PAIRTheme {
        HomeScreen(
            state = PairRuntimeState(RuntimePhase.STOPPED, desiredRunning = false),
            nodes = emptyList(),
            cluster = ClusterState(),
            workloads = emptyList(),
            gatewayModels = null,
            gatewayCheckCompleted = false,
            localEngine = MnnLocalEngineStatus(available = false),
            onStart = {}, onStop = {}, onViewAllJobs = {}, onCopyEndpoint = {},
        )
    }
}

@Preview(name = "Home - running CPU only", showBackground = true)
@Composable
private fun HomeScreenRunningPreview() {
    PAIRTheme {
        HomeScreen(
            state = PairRuntimeState(RuntimePhase.RUNNING, desiredRunning = true, version = "preview"),
            nodes = emptyList(),
            cluster = ClusterState(),
            workloads = emptyList(),
            gatewayModels = emptyList(),
            gatewayCheckCompleted = true,
            localEngine = MnnLocalEngineStatus(available = true, engineState = com.nv.pair.mnn.MnnEngineState.UNLOADED),
            onStart = {}, onStop = {}, onViewAllJobs = {}, onCopyEndpoint = {},
        )
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun WorkloadCard(workload: PairWorkload) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(workload.model.ifBlank { "Inference request" }, style = MaterialTheme.typography.titleMedium)
            val source = if (workload.kind == "cloud") "Cloud ${workload.providerId.orEmpty()}" else workload.engine
            Text("Source - $source")
            Text("Status - ${projectWorkloadStatus(workload.state)}")
            if (workload.scheduledOn.isNotBlank()) Text("Target node - ${workload.scheduledOn}", style = MaterialTheme.typography.bodySmall)
            workloadDuration(workload)?.let { Text("Duration - $it", style = MaterialTheme.typography.bodySmall) }
            if (workload.createdAt > 0) Text("Created ${formatLastSeen(workload.createdAt)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun JobsScreen(workloads: List<PairWorkload>) {
    SectionTitle("Recent Jobs")
    val sorted = remember(workloads) {
        workloads.sortedWith(
            compareBy<PairWorkload> { if (it.state.equals("running", ignoreCase = true)) 0 else 1 }
                .thenByDescending { it.createdAt },
        )
    }
    if (sorted.isEmpty()) Text("No requests have been routed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else sorted.forEach { WorkloadCard(it) }
}

private fun workloadDuration(workload: PairWorkload): String? {
    val start = workload.startedAt
        ?: workload.createdAt.takeIf { workload.state.equals("running", ignoreCase = true) }
        ?: return null
    val end = workload.completedAt
        ?: System.currentTimeMillis().takeIf { workload.state.equals("running", ignoreCase = true) }
        ?: return null
    val seconds = ((end - start).coerceAtLeast(0) / 1_000)
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}

private fun formatLastSeen(epochMillis: Long): String {
    val elapsed = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now()).seconds.coerceAtLeast(0)
    return when {
        elapsed < 60 -> "${elapsed}s ago"
        elapsed < 3_600 -> "${elapsed / 60}m ago"
        elapsed < 86_400 -> "${elapsed / 3_600}h ago"
        else -> "${elapsed / 86_400}d ago"
    }
}

@Composable
private fun SettingsScreen(
    state: PairRuntimeState,
    cluster: ClusterState,
    backendChoice: BackendChoice,
    capabilities: MnnCapabilitySnapshot,
    effectiveBackend: EffectiveBackendSelection,
    localEngine: MnnLocalEngineStatus,
    onCopyNodeId: () -> Unit,
    onCopyEndpoint: () -> Unit,
) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "Unavailable"
    }
    val wifiInterface = remember(context) { runCatching { AndroidNetworkContext(context).wifiInterface() }.getOrNull() }
    var modelStorageBytes by remember(context) { mutableStateOf<Long?>(null) }
    LaunchedEffect(context) {
        modelStorageBytes = withContext(Dispatchers.IO) {
            val modelRoot = File(context.filesDir, "mnn/models")
            if (!modelRoot.exists()) 0L else modelRoot.walkTopDown().filter(File::isFile).sumOf(File::length)
        }
    }
    SettingGroup("Node Information") {
        SettingRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        SettingRow("Node ID", cluster.identity?.nodeUuid?.let(::shortIdentity) ?: "Unavailable")
        if (cluster.identity?.nodeUuid != null) OutlinedButton(onClick = onCopyNodeId) { Text("Copy node ID") }
        SettingRow("Wi-Fi", if (wifiInterface == null) "Not connected" else "Connected")
        SettingRow("Local IP", wifiInterface?.ipv4Address ?: "Unavailable")
        SettingRow("App version", appVersion)
        SettingRow("Broker version", state.version ?: "Unavailable")
        SettingRow("Runtime", phaseLabel(state.phase))
    }
    SettingGroup("Inference") {
        SettingRow("MNN engine", if (state.phase == RuntimePhase.RUNNING) "Hosted by this device" else "Stopped")
        SettingRow("Engine selection", if (backendChoice == BackendChoice.Auto) "Automatic" else "Manual")
        SettingRow(
            "Effective engine",
            when {
                effectiveBackend.resolving && state.phase != RuntimePhase.RUNNING -> "Not checked · CPU fallback"
                effectiveBackend.resolving -> "Checking"
                else -> effectiveBackend.effectiveBackend.name
            },
        )
        SettingRow("CPU", backendCapabilityLabel(capabilities.cpu))
        SettingRow("OpenCL", openClSettingsLabel(capabilities))
        SettingRow("Model directory usage", modelStorageBytes?.let(::formatStorageBytes) ?: "Checking")
        SettingRow("CPU threads", "Managed by MNN · not user-configurable")
    }
    SettingGroup("API / Connectivity") {
        SettingRow("Unified endpoint", GATEWAY_ENDPOINT)
        SettingRow("Listening scope", "Phone only · loopback")
        SettingRow("API key", "Stored on device · hidden")
        OutlinedButton(onClick = onCopyEndpoint, enabled = state.phase == RuntimePhase.RUNNING) { Text("Copy endpoint") }
    }
    SettingGroup("Service Diagnostics") {
        SettingRow("Broker", phaseLabel(state.phase))
        state.error?.let { SettingRow("Broker detail", redactPrivatePath(it)) }
        SettingRow("MNN health", if (localEngine.available) "Available" else "Unavailable")
        SettingRow("MNN state", localEngine.engineState?.name ?: "Not reported")
        localEngine.modelId?.let { SettingRow("Loaded model", it) }
        localEngine.errorCode?.let { SettingRow("MNN error", it.name) }
    }
    SettingGroup("About") {
        SettingRow("ComputeHarbor", "Android compute node")
        SettingRow("License", "Apache-2.0")
    }
}

@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun openClSettingsLabel(capabilities: MnnCapabilitySnapshot): String = when (capabilities.openCl.state) {
    com.nv.pair.mnn.ProbeState.UNKNOWN -> "Not checked"
    com.nv.pair.mnn.ProbeState.CHECKING -> "Checking"
    com.nv.pair.mnn.ProbeState.AVAILABLE -> "Probe available · model compatibility not verified"
    com.nv.pair.mnn.ProbeState.ERROR -> "Probe failed"
    com.nv.pair.mnn.ProbeState.UNAVAILABLE -> when (capabilities.openCl.reason) {
        com.nv.pair.mnn.ProbeReason.NOT_COMPILED -> "Not compiled into this app"
        else -> "Unavailable"
    }
}

private fun backendCapabilityLabel(capability: com.nv.pair.mnn.BackendCapability): String = when (capability.state) {
    com.nv.pair.mnn.ProbeState.UNKNOWN -> "Not checked"
    com.nv.pair.mnn.ProbeState.CHECKING -> "Checking"
    com.nv.pair.mnn.ProbeState.AVAILABLE -> "Available"
    com.nv.pair.mnn.ProbeState.UNAVAILABLE -> "Unavailable"
    com.nv.pair.mnn.ProbeState.ERROR -> "Probe failed"
}

private fun shortIdentity(value: String): String = if (value.length <= 18) value else "${value.take(8)}…${value.takeLast(6)}"

private fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.0f MiB".format(bytes / (1024.0 * 1024.0))
    else -> "%.0f KiB".format(bytes / 1024.0)
}

private fun redactPrivatePath(message: String): String = message
    .replace(Regex("/data/user/\\d+/[^\\s,;]+"), "<app-private-path>")
    .replace(Regex("/data/data/[^\\s,;]+"), "<app-private-path>")
    .replace(Regex("/storage/emulated/\\d+/[^\\s,;]+"), "<device-path>")

private fun phaseLabel(phase: RuntimePhase): String = when (phase) {
    RuntimePhase.STOPPED -> "Stopped"
    RuntimePhase.STARTING -> "Starting"
    RuntimePhase.WAITING_READY -> "Waiting for services"
    RuntimePhase.RUNNING -> "Running"
    RuntimePhase.STOPPING -> "Stopping"
    RuntimePhase.STARTUP_FAILED -> "Startup failed"
    RuntimePhase.CRASHED -> "Stopped unexpectedly"
    RuntimePhase.RESTART_BACKOFF -> "Reconnecting"
}

private const val GATEWAY_ENDPOINT = "http://127.0.0.1:14326/v1"
private const val GATEWAY_MODEL_REFRESH_MILLIS = 5_000L
