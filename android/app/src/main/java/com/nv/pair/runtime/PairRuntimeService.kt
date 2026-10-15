/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nv.pair.MainActivity
import com.nv.pair.data.PairRepository
import com.nv.pair.data.ClusterRepository
import com.nv.pair.data.ClusterInvite
import com.nv.pair.data.InviteDecision
import com.nv.pair.data.checkInviteDecision
import com.nv.pair.data.RouterRepository
import com.nv.pair.data.EngineProxyStatus
import com.nv.pair.data.UiPreferencesRepository
import com.nv.pair.mnn.MnnRuntimeContainer
import com.nv.pair.mnn.MnnErrorCode
import com.nv.pair.mnn.MnnSettingsRepository
import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.MnnEngineState
import com.nv.pair.mnn.BackendChoice
import com.nv.pair.mnn.BackendCapability
import com.nv.pair.mnn.EffectiveBackendSelection
import com.nv.pair.mnn.MnnCapabilitySnapshot
import com.nv.pair.mnn.ProbeReason
import com.nv.pair.mnn.ProbeState
import com.nv.pair.mnn.BackendCapabilityResult
import com.nv.pair.mnn.resolveBackendChoice
import com.nv.pair.models.ModelHubInstaller
import com.nv.pair.network.MulticastLockManager
import com.nv.pair.network.AndroidNetworkContext
import com.nv.pair.rpc.BrokerSession
import com.nv.pair.rpc.BrokerApi
import com.nv.pair.rpc.parseNodesChanged
import com.nv.pair.rpc.ClusterApi
import com.nv.pair.rpc.parseClusterInvite
import com.nv.pair.rpc.RouterApi
import com.nv.pair.rpc.parseWorkloadRemoval
import com.nv.pair.rpc.parseWorkloadUpsert
import com.nv.pair.rpc.parseClusterMembersChanged
import com.nv.pair.rpc.removeCloudProviderConfig
import com.nv.pair.rpc.upsertCloudProviderConfig
import com.nv.pair.runtime.RuntimePhase.CRASHED
import com.nv.pair.runtime.RuntimePhase.RESTART_BACKOFF
import com.nv.pair.runtime.RuntimePhase.RUNNING
import com.nv.pair.runtime.RuntimePhase.STARTING
import com.nv.pair.runtime.RuntimePhase.STARTUP_FAILED
import com.nv.pair.runtime.RuntimePhase.STOPPED
import com.nv.pair.runtime.RuntimePhase.STOPPING
import com.nv.pair.runtime.RuntimePhase.WAITING_READY
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PairRuntimeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandMutex = Mutex()
    private val modelLifecycleMutex = Mutex()
    private val backendSelectionMutex = Mutex()
    private val pendingInviteTargets = ConcurrentHashMap.newKeySet<String>()
    private lateinit var preferences: UiPreferencesRepository
    private lateinit var mnnSettings: MnnSettingsRepository
    private lateinit var multicastLock: MulticastLockManager
    private var runtimeJob: Job? = null
    @Volatile private var mnnRuntimeContainer: MnnRuntimeContainer? = null
    private var openClProbeJob: Job? = null
    private var openClProbeGeneration = 0L
    private val activeSession = AtomicReference<BrokerSession?>()
    @Volatile
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        preferences = UiPreferencesRepository(applicationContext)
        mnnSettings = MnnSettingsRepository(applicationContext)
        serviceScope.launch {
            mnnSettings.backendChoice.collect { reconcileBackendSelection() }
        }
        multicastLock = MulticastLockManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> serviceScope.launch { commandMutex.withLock { stopRuntime(startId) } }
            ACTION_MNN_BACKEND_CHANGED -> serviceScope.launch { reconcileBackendSelection() }
            ACTION_MNN_REPROBE_OPENCL -> startOpenClProbe()
            ACTION_DELETE_MODEL -> serviceScope.launch { deleteInstalledModel(intent) }
            ACTION_CLOUD_ENABLED_CHANGED -> serviceScope.launch { updateCloudEnabled(intent.getBooleanExtra(EXTRA_CLOUD_ENABLED, false)) }
            ACTION_CLOUD_POLICY_CHANGED -> serviceScope.launch { updateCloudPolicy(intent.getStringExtra(EXTRA_CLOUD_POLICY).orEmpty()) }
            ACTION_CLOUD_PROVIDER_SAVE -> serviceScope.launch {
                updateCloudProvider(
                    intent.getStringExtra(EXTRA_CLOUD_PROVIDER_JSON).orEmpty(),
                    intent.getStringExtra(EXTRA_CLOUD_PROVIDER_CREDENTIAL).orEmpty(),
                )
            }
            ACTION_CLOUD_PROVIDER_REMOVE -> serviceScope.launch {
                removeCloudProvider(intent.getStringExtra(EXTRA_CLOUD_PROVIDER_ID).orEmpty())
            }
            ACTION_CLOUD_PROVIDER_TEST -> serviceScope.launch {
                testCloudProvider(intent.getStringExtra(EXTRA_CLOUD_PROVIDER_ID).orEmpty())
            }
            ACTION_CLUSTER_CREATE, ACTION_CLUSTER_INVITE, ACTION_CLUSTER_RESPOND,
            ACTION_CLUSTER_CANCEL, ACTION_CLUSTER_LEAVE, ACTION_CLUSTER_REMOVE ->
                serviceScope.launch { performClusterAction(intent.action.orEmpty(), intent) }
            ACTION_START -> {
                promoteToForeground()
                serviceScope.launch {
                    commandMutex.withLock {
                        preferences.setDesiredRuntimeRunning(true)
                        setDesiredRunning(true)
                        startRuntimeIfNeeded()
                    }
                }
            }
            null -> {
                promoteToForeground()
                serviceScope.launch {
                    commandMutex.withLock {
                        if (preferences.readDesiredRuntimeRunning()) {
                            setDesiredRunning(true)
                            startRuntimeIfNeeded()
                        } else {
                            finishStoppedService(startId)
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        invalidateOpenClProbe()
        runCatching { multicastLock.release() }
            .onFailure { android.util.Log.w(TAG, "multicast lock release failed during service destruction") }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRuntimeIfNeeded() {
        if (runtimeJob?.isActive == true) return
        runtimeJob = serviceScope.launch { runBrokerLoop() }
    }

    private suspend fun runBrokerLoop() {
        val restartPolicy = BrokerRestartPolicy()
        try {
            multicastLock.acquire()
            if (mnnRuntimeContainer != null) closeMnnRuntime()
            val mnnAvailable = startOptionalMnnRuntime()
            while (currentCoroutineContext().isActive && runtimeState.value.desiredRunning) {
                val previousSession = activeSession.get()
                if (previousSession != null && !closeBrokerSession(previousSession)) {
                    preferences.setDesiredRuntimeRunning(false)
                    setDesiredRunning(false)
                    setPhase(STARTUP_FAILED, error = "PAIR broker cleanup failed; restart is blocked until cleanup succeeds")
                    break
                }
                setPhase(STARTING)
                val crash = CompletableDeferred<Int>()
                val session = BrokerSession(
                    binaries = NativeBinaryRegistry(File(applicationInfo.nativeLibraryDir)),
                    filesDir = filesDir,
                    cacheDir = cacheDir,
                    onLog = { line -> android.util.Log.i(TAG, line) },
                    onCrash = { code -> crash.complete(code) },
                    onWaitingReady = { setPhaseIf(STARTING, WAITING_READY) },
                    additionalEnvironment = mdnsEnvironment() + mapOf(
                        "PAIR_GATEWAY_CLIENT_TOKEN" to GatewayTokenStore(applicationContext).getOrCreate(),
                    ),
                    proxyEngines = BrokerSession.proxyEnginesForLocalMnn(mnnAvailable),
                    onNotification = { notification ->
                        if (notification.method.startsWith("cluster:invite-")) {
                            android.util.Log.i(TAG, "cluster invite notification received method=${notification.method}")
                        }
                        if (notification.method == "discovery:nodes-changed") {
                            val applied = runCatching {
                                pairRepository.applyNodesChanged(parseNodesChanged(notification.paramsJson))
                            }.onFailure { android.util.Log.w(TAG, "invalid discovery snapshot") }
                            if (applied.isSuccess) refreshNotification()
                        } else if (notification.method == "cluster:identity-changed") {
                            runCatching {
                                val params = notification.paramsJson?.let { org.json.JSONObject(it) }
                                    ?: org.json.JSONObject()
                                clusterRepository.applyClusterIdentityChanged(
                                    params.optString("clusterId"), params.optString("clusterFriendlyName"),
                                )
                            }.onFailure { android.util.Log.w(TAG, "invalid cluster identity notification") }
                        } else if (notification.method == "nodes:changed") {
                            runCatching { clusterRepository.applyMembersChanged(parseClusterMembersChanged(notification.paramsJson)) }
                        } else if (notification.method.startsWith("cluster:invite-")) {
                            val inbound = notification.method == "cluster:invite-received"
                            runCatching {
                                val payload = notification.paramsJson?.let { org.json.JSONObject(it) }
                                    ?: throw IllegalArgumentException("missing invite payload")
                                val invite = parseClusterInvite(payload, inbound)
                                if (invite.state == "canceled" || invite.state == "expired" ||
                                    invite.state == "declined" || invite.state == "failed" || invite.state == "paired") {
                                    clusterRepository.applyInvite(invite.copy(pin = null))
                                    val session = activeSession.get()
                                    if (session != null) serviceScope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) { ClusterApi(session).refresh(clusterRepository) }
                                        }.onFailure {
                                            android.util.Log.w(TAG, "cluster snapshot refresh failed after invite update")
                                        }
                                    }
                                } else {
                                    clusterRepository.applyInvite(invite)
                                }
                            }.onFailure { android.util.Log.w(TAG, "invalid cluster invite notification") }
                        } else if (notification.method == "workloads:upsert") {
                            runCatching { routerRepository.upsertWorkload(parseWorkloadUpsert(notification.paramsJson)) }
                                .onFailure { android.util.Log.w(TAG, "invalid workload update") }
                        } else if (notification.method == "workloads:remove") {
                            runCatching {
                                val removed = parseWorkloadRemoval(notification.paramsJson)
                                routerRepository.removeWorkload(removed.workloadId, removed.origin)
                            }.onFailure { android.util.Log.w(TAG, "invalid workload removal") }
                        }
                    },
                )
                activeSession.set(session)
                val startedAt = SystemClock.elapsedRealtime()
                try {
                    val info = withContext(Dispatchers.IO) { session.start() }
                    if (!currentCoroutineContext().isActive || !runtimeState.value.desiredRunning) {
                        throw CancellationException("PAIR runtime is stopping")
                    }
                    withContext(Dispatchers.IO) { session.initializeDiscovery(pairRepository) }
                    clusterRepository.clearPendingInvites()
                    runCatching {
                        withContext(Dispatchers.IO) { ClusterApi(session).initialize(clusterRepository) }
                    }.onSuccess {
                        clusterRepository.setError(null)
                    }.onFailure {
                        clusterRepository.setError("Cluster manager is unavailable; cluster features are disabled.")
                    }
                    withContext(Dispatchers.IO) { RouterApi(session).initializeWorkloads(routerRepository) }
                    _cloudProviderSettings.value = withContext(Dispatchers.IO) {
                        val api = RouterApi(session)
                        val settings = api.cloudProviderSettings()
                        val credentialStore = CloudProviderCredentialStore(applicationContext)
                        var configChanged = false
                        val config = org.json.JSONObject(settings.config.toString())
                        val persistedProviders = config.optJSONArray("providers") ?: org.json.JSONArray()
                        val restoredProviders = org.json.JSONArray()
                        settings.providers.forEach { provider ->
                            val credential = credentialStore.get(provider.authRef)
                            if (credential != null) {
                                api.setCloudProviderCredential(provider.authRef, credential)
                            } else if (provider.enabled) {
                                configChanged = true
                            }
                            val rawProvider = (0 until persistedProviders.length())
                                .mapNotNull(persistedProviders::optJSONObject)
                                .firstOrNull { it.optString("id") == provider.id }
                            if (rawProvider != null) {
                                if (credential == null && provider.enabled) rawProvider.put("enabled", false)
                                restoredProviders.put(rawProvider)
                            }
                        }
                        if (configChanged) {
                            config.put("providers", restoredProviders)
                            api.updateCloudProviders(settings, config)
                        }
                        settings.copy(
                            config = config,
                            providers = settings.providers.map { provider ->
                                provider.copy(credentialConfigured = credentialStore.get(provider.authRef) != null,
                                    enabled = provider.enabled && credentialStore.get(provider.authRef) != null)
                            },
                        )
                    }
                    setPhase(RUNNING, version = info.version, uptimeMillis = info.uptimeMillis)
                    val proxyMonitor = serviceScope.launch { monitorProxyStatus(session) }
                    try {
                        val exitCode = crash.await()
                        val runtimeMillis = SystemClock.elapsedRealtime() - startedAt
                        restartPolicy.recordRuntime(runtimeMillis)
                        setPhase(CRASHED, error = "PAIR broker exited with code $exitCode")
                    } finally {
                        proxyMonitor.cancelAndJoin()
                        routerRepository.resetProxyStatuses()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    setPhase(STARTUP_FAILED, error = failure.message ?: "PAIR broker failed to start")
                } finally {
                    val sessionClosed = closeBrokerSession(session)
                    if (!sessionClosed) {
                        preferences.setDesiredRuntimeRunning(false)
                        setDesiredRunning(false)
                        setPhase(STARTUP_FAILED, error = "PAIR broker cleanup failed; runtime was stopped")
                    }
                }

                if (!runtimeState.value.desiredRunning) break
                val nextDelay = restartPolicy.nextDelayMillis()
                if (nextDelay == null) {
                    if (runtimeState.value.phase != STARTUP_FAILED) {
                        setPhase(STARTUP_FAILED, error = runtimeState.value.error ?: "PAIR broker restart limit reached")
                    }
                    preferences.setDesiredRuntimeRunning(false)
                    setDesiredRunning(false)
                    break
                }
                setPhase(RESTART_BACKOFF, error = "PAIR will retry in ${nextDelay / 1_000} seconds")
                delay(nextDelay)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (runtimeState.value.phase != STARTUP_FAILED) {
                setPhase(STARTUP_FAILED, error = failure.message ?: "PAIR runtime failed")
            }
            preferences.setDesiredRuntimeRunning(false)
            setDesiredRunning(false)
        } finally {
            try {
                runCatching { routerRepository.resetProxyStatuses() }
                    .onFailure { android.util.Log.w(TAG, "could not reset proxy status during shutdown") }
                val container = mnnRuntimeContainer
                if (container != null) {
                    try {
                        withContext(NonCancellable + Dispatchers.IO) { container.close() }
                        if (mnnRuntimeContainer === container) mnnRuntimeContainer = null
                    } catch (failure: Exception) {
                        android.util.Log.w(TAG, "local MNN runtime cleanup failed; retained for retry", failure)
                    }
                }
            } finally {
                _mnnLocalEngine.value = MnnLocalEngineStatus(available = false)
                multicastLock.release()
            }
        }
    }

    private suspend fun closeBrokerSession(session: BrokerSession): Boolean {
        runCatching { routerRepository.resetProxyStatuses() }
            .onFailure { android.util.Log.w(TAG, "could not reset proxy status during broker shutdown") }
        try {
            var failure: Exception? = null
            repeat(BROKER_CLEANUP_ATTEMPTS) { attempt ->
                try {
                    withContext(NonCancellable + Dispatchers.IO) { session.close() }
                    activeSession.compareAndSet(session, null)
                    return true
                } catch (cleanupFailure: Exception) {
                    failure = cleanupFailure
                    android.util.Log.w(TAG, "broker cleanup attempt ${attempt + 1} failed")
                }
            }
            failure?.let { android.util.Log.e(TAG, "broker cleanup did not complete after retries", it) }
            return false
        } finally {
            runCatching { clusterRepository.clearPendingInvites() }
                .onFailure { android.util.Log.w(TAG, "could not clear transient invitations during shutdown") }
        }
    }

    private suspend fun startOptionalMnnRuntime(): Boolean {
        invalidateOpenClProbe()
        _mnnCapabilities.value = MnnCapabilitySnapshot()
        _mnnLocalEngine.value = MnnLocalEngineStatus(available = false)
        if (mnnRuntimeContainer != null) {
            closeMnnRuntime()
        }
        var candidate: MnnRuntimeContainer? = null
        try {
            val container = withContext(Dispatchers.IO) {
                MnnRuntimeContainer(
                    File(filesDir, MNN_MODELS_DIRECTORY),
                    preferredBackend = MnnBackend.CPU,
                )
            }
            candidate = container
            mnnRuntimeContainer = container
            withContext(Dispatchers.IO) { container.start() }
            val health = withContext(Dispatchers.IO) { container.health() }
            if (!health.available) {
                closeUnstartedMnnContainer(container)
                val errorCode = health.error?.code ?: MnnErrorCode.NATIVE_LIBRARY_UNAVAILABLE
                _mnnLocalEngine.value = MnnLocalEngineStatus(
                    available = false,
                    backend = _preferredMnnBackend.value,
                    errorCode = errorCode,
                    engineState = health.state,
                )
                android.util.Log.w(TAG, "local MNN unavailable code=${errorCode.name.lowercase()}")
                return false
            }
            mnnRuntimeContainer = container
            val runtimeStatus = withContext(Dispatchers.IO) { container.runtimeStatus() }
            _mnnLocalEngine.value = MnnLocalEngineStatus(
                available = true,
                backend = MnnBackend.CPU,
                errorCode = runtimeStatus.error?.code,
                engineState = runtimeStatus.state,
                modelId = runtimeStatus.modelId,
            )
            _mnnCapabilities.value = MnnCapabilitySnapshot(
                cpu = BackendCapability(MnnBackend.CPU, ProbeState.AVAILABLE, checkedAtEpochMillis = System.currentTimeMillis()),
                openCl = BackendCapability(MnnBackend.OPENCL, ProbeState.CHECKING),
            )
            reconcileBackendSelection()
            startOpenClProbe()
            return true
        } catch (cancelled: CancellationException) {
            candidate?.let { closeUnstartedMnnContainer(it) }
            throw cancelled
        } catch (_: Exception) {
            candidate?.let { closeUnstartedMnnContainer(it) }
            val errorCode = MnnErrorCode.INTERNAL_ERROR
            _mnnLocalEngine.value = MnnLocalEngineStatus(
                available = false,
                backend = _preferredMnnBackend.value,
                errorCode = errorCode,
                engineState = MnnEngineState.ERROR,
            )
            android.util.Log.w(TAG, "local MNN startup failed code=${errorCode.name.lowercase()}")
            return false
        }
    }

    private suspend fun reconcileBackendSelection() {
        backendSelectionMutex.withLock {
            val container = mnnRuntimeContainer
            val selectedChoice = withContext(Dispatchers.IO) { mnnSettings.readBackendChoice() }
            val resolved = resolveBackendChoice(
                selectedChoice,
                _mnnCapabilities.value.openCl.state,
                _mnnLocalEngine.value.available,
            )
            if (container != null && mnnRuntimeContainer === container) {
                withContext(Dispatchers.IO) { container.setPreferredBackend(resolved.effectiveBackend) }
            }
            _backendChoice.value = selectedChoice
            _effectiveBackendSelection.value = resolved
            _preferredMnnBackend.value = resolved.effectiveBackend
            if (_mnnLocalEngine.value.available) {
                _mnnLocalEngine.value = _mnnLocalEngine.value.copy(backend = resolved.effectiveBackend)
            }
        }
    }

    private fun startOpenClProbe() {
        val container = mnnRuntimeContainer ?: return
        if (openClProbeJob?.isActive == true) return
        val generation = ++openClProbeGeneration
        _mnnCapabilities.value = _mnnCapabilities.value.copy(
            openCl = BackendCapability(MnnBackend.OPENCL, ProbeState.CHECKING),
        )
        openClProbeJob = serviceScope.launch {
            val result = withContext(Dispatchers.IO) { container.probeOpenCl() }
            if (mnnRuntimeContainer !== container || generation != openClProbeGeneration) return@launch
            val capability = when (result) {
                is com.nv.pair.mnn.MnnResult.Success -> when (val probe = result.value) {
                    BackendCapabilityResult.Available -> BackendCapability(
                        MnnBackend.OPENCL, ProbeState.AVAILABLE, checkedAtEpochMillis = System.currentTimeMillis(),
                    )
                    is BackendCapabilityResult.Unavailable -> BackendCapability(
                        MnnBackend.OPENCL, ProbeState.UNAVAILABLE, probe.reason, System.currentTimeMillis(),
                    )
                    is BackendCapabilityResult.Error -> BackendCapability(
                        MnnBackend.OPENCL, ProbeState.ERROR, probe.reason, System.currentTimeMillis(),
                    )
                }
                is com.nv.pair.mnn.MnnResult.Failure -> BackendCapability(
                    MnnBackend.OPENCL, ProbeState.ERROR,
                    if (result.error.code == MnnErrorCode.INVALID_STATE) ProbeReason.RUNTIME_STOPPED else ProbeReason.PROBE_FAILED,
                    System.currentTimeMillis(),
                )
            }
            _mnnCapabilities.value = _mnnCapabilities.value.copy(openCl = capability)
            reconcileBackendSelection()
        }
    }

    private fun invalidateOpenClProbe() {
        openClProbeGeneration += 1
        openClProbeJob?.cancel()
        openClProbeJob = null
        _mnnCapabilities.value = _mnnCapabilities.value.copy(
            openCl = BackendCapability(MnnBackend.OPENCL, ProbeState.UNKNOWN, ProbeReason.RUNTIME_STOPPED),
        )
        _mnnLocalEngine.value = MnnLocalEngineStatus(available = false, backend = MnnBackend.CPU)
        _preferredMnnBackend.value = MnnBackend.CPU
        _effectiveBackendSelection.value = resolveBackendChoice(
            _backendChoice.value,
            ProbeState.UNKNOWN,
            engineAvailable = false,
        )
    }

    private suspend fun closeUnstartedMnnContainer(container: MnnRuntimeContainer) {
        closeMnnContainer(container)
    }

    private suspend fun stopRuntime(startId: Int) {
        preferences.setDesiredRuntimeRunning(false)
        setDesiredRunning(false)
        if (runtimeState.value.phase != STOPPED) setPhase(STOPPING)
        try {
            closeMnnRuntime()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            android.util.Log.w(TAG, "local MNN runtime cleanup failed during Stop", failure)
        }
        try {
            runtimeJob?.cancelAndJoin()
        } finally {
            runtimeJob = null
            multicastLock.release()
        }
        val lingeringSession = activeSession.get()
        val brokerClosed = lingeringSession == null || closeBrokerSession(lingeringSession)
        if (!brokerClosed) {
            setPhase(STARTUP_FAILED, error = "PAIR broker cleanup failed; Stop can be retried")
            return
        }
        try {
            closeMnnRuntime()
        } catch (failure: Exception) {
            setPhase(STARTUP_FAILED, error = "Local MNN cleanup failed; Stop can be retried")
            return
        }
        if (runtimeState.value.phase == STOPPING) setPhase(STOPPED)
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelfResult(startId)
    }

    private suspend fun closeMnnRuntime() {
        invalidateOpenClProbe()
        modelLifecycleMutex.withLock {
            val container = mnnRuntimeContainer
            if (container != null) {
                closeMnnContainer(container)
            }
        }
    }

    private suspend fun closeMnnContainer(container: MnnRuntimeContainer) {
        withContext(NonCancellable + Dispatchers.IO) { container.close() }
        if (mnnRuntimeContainer === container) mnnRuntimeContainer = null
    }

    private fun finishStoppedService(startId: Int) {
        if (runtimeState.value.phase != STOPPED) setPhase(STOPPED, desiredRunning = false)
        multicastLock.release()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelfResult(startId)
    }

    private fun setDesiredRunning(desired: Boolean) {
        synchronized(stateLock) {
            _runtimeState.value = _runtimeState.value.copy(desiredRunning = desired)
        }
        refreshNotification()
    }

    private fun mdnsEnvironment(): Map<String, String> {
        val wifi = AndroidNetworkContext(applicationContext).wifiInterface() ?: return emptyMap()
        return mapOf(
            "NVPAIR_MDNS_INTERFACE_INDEX" to wifi.index.toString(),
            "NVPAIR_MDNS_INTERFACE_NAME" to wifi.name,
            "NVPAIR_MDNS_IPV4" to wifi.ipv4Address,
        )
    }

    private suspend fun monitorProxyStatus(session: BrokerSession) {
        while (currentCoroutineContext().isActive && activeSession.get() === session && runtimeState.value.phase == RUNNING) {
            for (engine in RouterRepository.ROUTER_ENGINES) {
                val status = try {
                    withContext(Dispatchers.IO) { RouterApi(session).proxyStatus(engine) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    EngineProxyStatus(engine, false, 0)
                }
                routerRepository.setProxyStatus(status)
            }
            mnnRuntimeContainer?.let { container ->
                val previousHealth = _mnnLocalEngine.value.available
                val (health, status) = withContext(Dispatchers.IO) {
                    container.health() to container.runtimeStatus()
                }
                _mnnLocalEngine.value = MnnLocalEngineStatus(
                    available = health.available,
                    backend = status.backend ?: container.preferredBackend(),
                    errorCode = status.error?.code ?: health.error?.code,
                    engineState = status.state,
                    modelId = status.modelId,
                )
                if (previousHealth != health.available) reconcileBackendSelection()
            }
            delay(2_000)
        }
    }

    private suspend fun updateCloudEnabled(enabled: Boolean) {
        val session = activeSession.get() ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                val api = RouterApi(session)
                val current = api.cloudProviderSettings()
                api.updateCloudSettings(current, enabled, current.policy)
                api.cloudProviderSettings()
            }
        }.onSuccess { _cloudProviderSettings.value = it }
            .onFailure { android.util.Log.w(TAG, "cloud setting update failed") }
    }

    private suspend fun updateCloudPolicy(policy: String) {
        if (policy !in setOf("local_only", "cloud_only", "prefer_local", "prefer_cloud")) return
        val session = activeSession.get() ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                val api = RouterApi(session)
                val current = api.cloudProviderSettings()
                api.updateCloudSettings(current, current.cloudEnabled, policy)
                api.cloudProviderSettings()
            }
        }.onSuccess { _cloudProviderSettings.value = it }
            .onFailure { android.util.Log.w(TAG, "cloud routing policy update failed") }
    }

    private suspend fun updateCloudProvider(providerJson: String, credential: String) {
        val session = activeSession.get() ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                val provider = org.json.JSONObject(providerJson)
                val authRef = provider.optString("auth_ref")
                val providerId = provider.optString("id")
                require(authRef.isNotBlank() && providerId.isNotBlank())
                val api = RouterApi(session)
                val settings = api.cloudProviderSettings()
                val store = CloudProviderCredentialStore(applicationContext)
                if (credential.isNotEmpty()) {
                    store.put(authRef, credential)
                    api.setCloudProviderCredential(authRef, credential)
                }
                val update = upsertCloudProviderConfig(settings.config, provider)
                val config = update.config
                api.updateCloudProviders(settings, config)
                if (update.replacedAuthRef != null && update.replacedAuthRef != authRef) {
                    store.remove(update.replacedAuthRef)
                }
                settings.copy(config = config, providers = parseCloudProvidersForDisplay(config, store))
            }
        }.onSuccess { _cloudProviderSettings.value = it.copy(providerStatusMessage = "Provider saved.") }
            .onFailure {
                _cloudProviderSettings.value = _cloudProviderSettings.value?.copy(
                    providerStatusMessage = "Provider could not be saved. Check the provider fields and API key.",
                )
                android.util.Log.w(TAG, "cloud provider save failed")
            }
    }

    private suspend fun removeCloudProvider(providerId: String) {
        val session = activeSession.get() ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                val api = RouterApi(session)
                val settings = api.cloudProviderSettings()
                val removed = settings.providers.firstOrNull { it.id == providerId }
                    ?: return@withContext settings
                val config = removeCloudProviderConfig(settings.config, providerId).config
                api.updateCloudProviders(settings, config)
                CloudProviderCredentialStore(applicationContext).remove(removed.authRef)
                settings.copy(config = config, providers = settings.providers.filterNot { it.id == providerId })
            }
        }.onSuccess { _cloudProviderSettings.value = it.copy(providerStatusMessage = "Provider removed.") }
            .onFailure {
                _cloudProviderSettings.value = _cloudProviderSettings.value?.copy(
                    providerStatusMessage = "Provider could not be removed. Try again.",
                )
                android.util.Log.w(TAG, "cloud provider removal failed")
            }
    }

    private suspend fun testCloudProvider(providerId: String) {
        val session = activeSession.get() ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                RouterApi(session).testCloudProvider(providerId)
                _cloudProviderSettings.value?.copy(providerStatusMessage = "Connection test succeeded.")
            }
        }.onSuccess { updated ->
            if (updated != null) _cloudProviderSettings.value = updated
        }.onFailure {
            _cloudProviderSettings.value = _cloudProviderSettings.value?.copy(
                providerStatusMessage = "Connection test failed. Check the URL and API key.",
            )
        }
    }

    private fun parseCloudProvidersForDisplay(
        config: org.json.JSONObject,
        store: CloudProviderCredentialStore,
    ): List<com.nv.pair.rpc.CloudProviderMetadata> {
        val root = org.json.JSONObject()
            .put("schema_version", 1)
            .put("config", config)
            .put("policy", "local_only")
        return com.nv.pair.rpc.parseCloudProviderSettings(root).providers.map { provider ->
            provider.copy(credentialConfigured = store.get(provider.authRef) != null)
        }
    }

    private fun performClusterAction(action: String, intent: Intent) {
        val session = activeSession.get() ?: run {
            clusterRepository.setError("Start PAIR before managing cluster membership.")
            return
        }
        val api = ClusterApi(session)
        val text = intent.getStringExtra(EXTRA_VALUE).orEmpty()
        clusterRepository.setBusy(true)
        clusterRepository.setError(null)
        serviceScope.launch {
            var inviteToWatch: String? = null
            try {
                withContext(Dispatchers.IO) {
                    when (action) {
                        ACTION_CLUSTER_CREATE -> api.create(text)
                        ACTION_CLUSTER_INVITE -> {
                            val node = pairRepository.nodes.value.firstOrNull { it.id == text }
                                ?: throw IllegalArgumentException("Discovered node is no longer available.")
                            val decision = checkInviteDecision(node, clusterRepository.state.value)
                            if (decision is InviteDecision.NotAllowed) {
                                throw IllegalStateException(decision.reason)
                            }
                            if (!pendingInviteTargets.add(node.hostUuid)) {
                                throw IllegalStateException("Pairing is already in progress for this device.")
                            }
                            val invite = try {
                                api.invite(node.ipAddress, CLUSTER_MANAGER_PORT, node.hostUuid)
                                    .also(clusterRepository::applyInvite)
                            } finally {
                                pendingInviteTargets.remove(node.hostUuid)
                            }
                            if (invite.state == "pending") inviteToWatch = invite.inviteId
                        }
                        ACTION_CLUSTER_RESPOND -> {
                            val inviteId = intent.getStringExtra(EXTRA_VALUE).orEmpty()
                            val pin = intent.getStringExtra(EXTRA_SECONDARY).orEmpty()
                            val accepted = intent.getBooleanExtra(EXTRA_ACCEPT, false)
                            clusterRepository.applyInvite(api.respond(inviteId, accepted, pin).copy(pin = null))
                        }
                        ACTION_CLUSTER_CANCEL -> {
                            api.cancel(text)
                            clusterRepository.removeInvite(text)
                        }
                        ACTION_CLUSTER_LEAVE -> api.leave()
                        ACTION_CLUSTER_REMOVE -> api.remove(text)
                    }
                    if (action == ACTION_CLUSTER_RESPOND || action == ACTION_CLUSTER_CANCEL ||
                        action == ACTION_CLUSTER_LEAVE || action == ACTION_CLUSTER_REMOVE) {
                        api.refresh(clusterRepository)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                clusterRepository.setError(failure.message ?: "Cluster operation failed.")
            } finally {
                clusterRepository.setBusy(false)
            }
            inviteToWatch?.let { inviteId ->
                serviceScope.launch {
                    while (isActive && runtimeState.value.phase == RUNNING) {
                        delay(2_000)
                        val updated = try {
                            withContext(Dispatchers.IO) { api.inviteStatus(inviteId) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            clusterRepository.applyInvite(ClusterInvite(
                                inviteId = inviteId,
                                fromNodeId = "",
                                state = "failed",
                            ))
                            break
                        }
                        clusterRepository.applyInvite(updated.copy(pin = if (updated.state == "pending") updated.pin else null))
                        if (updated.state != "pending") break
                    }
                }
            }
        }
    }

    private suspend fun deleteInstalledModel(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_MODEL_DELETE_REQUEST_ID).orEmpty()
        val modelId = intent.getStringExtra(EXTRA_MODEL_DELETE_ID).orEmpty()
        val result = try {
            require(requestId.isNotBlank()) { "Model deletion request ID is missing." }
            require(runtimeState.value.phase == RUNNING) { "PAIR runtime changed state; retry model deletion." }
            val container = mnnRuntimeContainer
            modelLifecycleMutex.withLock {
                require(runtimeState.value.phase == RUNNING && mnnRuntimeContainer === container) {
                    "PAIR runtime changed state; retry model deletion."
                }
                val deletion = withContext(Dispatchers.IO) {
                    val deleteFiles = {
                        ModelHubInstaller(File(filesDir, MNN_MODELS_DIRECTORY)).deleteInstalledModel(modelId)
                    }
                    container?.deleteModel(modelId, deleteFiles) ?: run {
                        deleteFiles()
                        com.nv.pair.mnn.MnnResult.success(Unit)
                    }
                }
                when (deletion) {
                    is com.nv.pair.mnn.MnnResult.Success -> ModelDeletionResult(requestId, modelId, null)
                    is com.nv.pair.mnn.MnnResult.Failure -> ModelDeletionResult(requestId, modelId, deletion.error.message)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ModelDeletionResult(requestId, modelId, failure.message ?: "Model deletion failed.")
        }
        modelDeletionResults.emit(result)
    }

    private fun setPhaseIf(expected: RuntimePhase, next: RuntimePhase) {
        val changed = synchronized(stateLock) {
            if (_runtimeState.value.phase != expected) {
                false
            } else {
                _runtimeState.value = _runtimeState.value.transitionTo(next)
                true
            }
        }
        if (changed) refreshNotification()
    }

    private fun setPhase(
        phase: RuntimePhase,
        desiredRunning: Boolean = runtimeState.value.desiredRunning,
        version: String? = runtimeState.value.version,
        uptimeMillis: Long? = runtimeState.value.uptimeMillis,
        error: String? = null,
    ) {
        synchronized(stateLock) {
            _runtimeState.value = _runtimeState.value.transitionTo(
                next = phase,
                desiredRunning = desiredRunning,
                version = version,
                uptimeMillis = uptimeMillis,
                error = error,
            )
        }
        refreshNotification()
    }

    private fun promoteToForeground() {
        if (foreground) return
        startForeground(NOTIFICATION_ID, buildNotification())
        foreground = true
    }

    private fun refreshNotification() {
        if (foreground) {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val open = PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, PairRuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val state = runtimeState.value
        val content = when (state.phase) {
            RUNNING -> "Nodes: ${pairRepository.nodes.value.size} | Status: Connected"
            STARTING, WAITING_READY, RESTART_BACKOFF -> "Status: Starting"
            STOPPING -> "Status: Stopping"
            STARTUP_FAILED, CRASHED -> "Status: Needs attention"
            STOPPED -> "Status: Stopped"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (state.phase == RUNNING) "PAIR is running" else "PAIR runtime")
            .setContentText(content)
            .setContentIntent(open)
            .setOngoing(state.desiredRunning)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PAIR runtime",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.nv.pair.action.START_RUNTIME"
        const val ACTION_STOP = "com.nv.pair.action.STOP_RUNTIME"
        const val ACTION_MNN_BACKEND_CHANGED = "com.nv.pair.action.MNN_BACKEND_CHANGED"
        const val ACTION_MNN_REPROBE_OPENCL = "com.nv.pair.action.MNN_REPROBE_OPENCL"
        const val ACTION_DELETE_MODEL = "com.nv.pair.action.DELETE_MODEL"
        const val ACTION_CLOUD_ENABLED_CHANGED = "com.nv.pair.action.CLOUD_ENABLED_CHANGED"
        const val EXTRA_CLOUD_ENABLED = "com.nv.pair.extra.CLOUD_ENABLED"
        const val ACTION_CLOUD_POLICY_CHANGED = "com.nv.pair.action.CLOUD_POLICY_CHANGED"
        const val EXTRA_CLOUD_POLICY = "com.nv.pair.extra.CLOUD_POLICY"
        const val ACTION_CLOUD_PROVIDER_SAVE = "com.nv.pair.action.CLOUD_PROVIDER_SAVE"
        const val ACTION_CLOUD_PROVIDER_REMOVE = "com.nv.pair.action.CLOUD_PROVIDER_REMOVE"
        const val ACTION_CLOUD_PROVIDER_TEST = "com.nv.pair.action.CLOUD_PROVIDER_TEST"
        const val EXTRA_CLOUD_PROVIDER_JSON = "com.nv.pair.extra.CLOUD_PROVIDER_JSON"
        const val EXTRA_CLOUD_PROVIDER_CREDENTIAL = "com.nv.pair.extra.CLOUD_PROVIDER_CREDENTIAL"
        const val EXTRA_CLOUD_PROVIDER_ID = "com.nv.pair.extra.CLOUD_PROVIDER_ID"
        const val ACTION_CLUSTER_CREATE = "com.nv.pair.action.CLUSTER_CREATE"
        const val ACTION_CLUSTER_INVITE = "com.nv.pair.action.CLUSTER_INVITE"
        const val ACTION_CLUSTER_RESPOND = "com.nv.pair.action.CLUSTER_RESPOND"
        const val ACTION_CLUSTER_CANCEL = "com.nv.pair.action.CLUSTER_CANCEL"
        const val ACTION_CLUSTER_LEAVE = "com.nv.pair.action.CLUSTER_LEAVE"
        const val ACTION_CLUSTER_REMOVE = "com.nv.pair.action.CLUSTER_REMOVE"
        const val EXTRA_VALUE = "com.nv.pair.extra.VALUE"
        const val EXTRA_SECONDARY = "com.nv.pair.extra.SECONDARY"
        const val EXTRA_ACCEPT = "com.nv.pair.extra.ACCEPT"
        const val EXTRA_MODEL_DELETE_REQUEST_ID = "com.nv.pair.extra.MODEL_DELETE_REQUEST_ID"
        const val EXTRA_MODEL_DELETE_ID = "com.nv.pair.extra.MODEL_DELETE_ID"

        private const val TAG = "PAIR-Runtime"
        private const val CHANNEL_ID = "pair-runtime"
        private const val NOTIFICATION_ID = 36
        private const val OPEN_REQUEST_CODE = 1
        private const val STOP_REQUEST_CODE = 2
        private const val CLUSTER_MANAGER_PORT = 14321
        private const val MNN_MODELS_DIRECTORY = "mnn/models"
        private const val BROKER_CLEANUP_ATTEMPTS = 2
        private val stateLock = Any()
        private val _runtimeState = MutableStateFlow(PairRuntimeState.stopped())
        private val pairRepository = PairRepository()
        private val clusterRepository = ClusterRepository()
        private val routerRepository = RouterRepository()
        private val _cloudProviderSettings = MutableStateFlow<com.nv.pair.rpc.CloudProviderSettings?>(null)

        val runtimeState: StateFlow<PairRuntimeState> = _runtimeState.asStateFlow()
        val discoveredNodes = pairRepository.nodes
        val clusterState = clusterRepository.state
        val proxyStatuses = routerRepository.proxies
        private val _mnnLocalEngine = MutableStateFlow(MnnLocalEngineStatus(available = false))
        val mnnLocalEngine: StateFlow<MnnLocalEngineStatus> = _mnnLocalEngine.asStateFlow()
        private val _preferredMnnBackend = MutableStateFlow(MnnBackend.CPU)
        private val _backendChoice = MutableStateFlow<BackendChoice>(BackendChoice.Auto)
        val backendChoice: StateFlow<BackendChoice> = _backendChoice.asStateFlow()
        private val _mnnCapabilities = MutableStateFlow(MnnCapabilitySnapshot())
        val mnnCapabilities: StateFlow<MnnCapabilitySnapshot> = _mnnCapabilities.asStateFlow()
        private val _effectiveBackendSelection = MutableStateFlow(
            EffectiveBackendSelection(BackendChoice.Auto, MnnBackend.CPU, resolving = true),
        )
        val effectiveBackendSelection: StateFlow<EffectiveBackendSelection> = _effectiveBackendSelection.asStateFlow()
        val workloads = routerRepository.workloads
        val cloudProviderSettings: StateFlow<com.nv.pair.rpc.CloudProviderSettings?> = _cloudProviderSettings.asStateFlow()
        val modelDeletionResults = MutableSharedFlow<ModelDeletionResult>(extraBufferCapacity = 8)
    }
}

data class ModelDeletionResult(val requestId: String, val modelId: String, val failureMessage: String?)
