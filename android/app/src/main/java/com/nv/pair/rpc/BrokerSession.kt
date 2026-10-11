/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import com.nv.pair.runtime.NativeBinaryRegistry
import com.nv.pair.runtime.PairProcess
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

data class BrokerRuntimeInfo(val version: String, val uptimeMillis: Long)

internal interface BrokerProcessRunner {
    fun start(): Process
    fun stop(process: Process, timeoutMillis: Long): Int
}

class BrokerSession(
    private val binaries: NativeBinaryRegistry,
    private val filesDir: File,
    private val cacheDir: File,
    private val onLog: (String) -> Unit = {},
    private val onCrash: (Int) -> Unit = {},
    private val onWaitingReady: () -> Unit = {},
    private val onNotification: (RpcNotification) -> Unit = {},
    private val scannerBinaryOverride: File? = null,
    private val additionalEnvironment: Map<String, String> = emptyMap(),
    proxyEngines: List<String> = DEFAULT_PROXY_ENGINES,
) : AutoCloseable {
    private val proxyEngines = proxyEngines.toList()
    private val stopping = AtomicBoolean()
    private val cleanup = RetryableCleanup(actionCount = 4)
    private val ready = CompletableFuture<String>()
    private var process: Process? = null
    private var processRunner: BrokerProcessRunner? = null
    private var rpc: JsonRpcClient? = null
    private var stderrThread: Thread? = null
    private var monitorThread: Thread? = null

    @Synchronized
    fun start(): BrokerRuntimeInfo {
        val pairProcess = PairProcess(commandArguments(), runtimeEnvironment())
        return startWithRunner(object : BrokerProcessRunner {
            override fun start(): Process = pairProcess.start()
            override fun stop(process: Process, timeoutMillis: Long): Int = pairProcess.stop(process, timeoutMillis)
        })
    }

    @Synchronized
    internal fun startWithRunner(runner: BrokerProcessRunner): BrokerRuntimeInfo {
        check(process == null) { "broker session already started" }
        processRunner = runner
        val child = runner.start()
        process = child

        return try {
            finishStartup(child)
        } catch (failure: Exception) {
            val startupFailure = if (failure is IOException) failure else IOException("broker startup failed", failure)
            throw closeAfterStartupFailure(startupFailure)
        }
    }

    private fun finishStartup(child: Process): BrokerRuntimeInfo {
        val client = JsonRpcClient(
            child.inputStream,
            child.outputStream,
            onNotification = { notification ->
                handleNotification(notification)
                onNotification(notification)
            },
        )
        rpc = client
        client.start()
        startStderrReader(child)
        startExitMonitor(child, client)
        onWaitingReady()

        val readyVersion = try {
            ready.get(15, TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            throw IOException("broker did not emit app:ready within 15 seconds", timeout)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted while waiting for broker app:ready", interrupted)
        } catch (failure: ExecutionException) {
            throw IOException("broker exited before app:ready", failure.cause)
        }

        try {
            val ping = BrokerApi(client).ping()
            val reportedVersion = BrokerApi(client).version()
            if (ping.version != readyVersion || reportedVersion != readyVersion) {
                throw IOException("broker version mismatch across ready, ping, and version")
            }
            return BrokerRuntimeInfo(reportedVersion, ping.uptimeMillis)
        } catch (failure: Exception) {
            val startupFailure = if (failure is IOException) failure else IOException("broker RPC verification failed", failure)
            throw startupFailure
        }
    }

    private fun closeAfterStartupFailure(startupFailure: IOException): IOException {
        runCatching { close() }.onFailure(startupFailure::addSuppressed)
        return startupFailure
    }

    fun initializeDiscovery(repository: com.nv.pair.data.PairRepository) {
        val client = rpc ?: throw IOException("broker session has not started")
        BrokerApi(client).initializeDiscovery(repository)
    }

    fun request(
        method: String,
        params: JSONObject? = null,
        timeoutMillis: Long = 5_000,
    ): JSONObject {
        val client = rpc ?: throw IOException("broker session has not started")
        return client.request(method, params, timeoutMillis)
    }

    internal fun commandArguments(): List<String> {
        val broker = binaries.broker()
        val scanner = scannerBinaryOverride ?: binaries.scanner()
        return listOf(
            broker.absolutePath,
            "--scanner-path", scanner.absolutePath,
            "--node-info-path", binaries.nodeInfo().absolutePath,
            "--settings-path", binaries.settings().absolutePath,
            "--cluster-manager-path", binaries.clusterManager().absolutePath,
            "--proxy-path", binaries.proxy().absolutePath,
            "--proxy-engines", proxyEngines.joinToString(","),
            "--scheduler-path", binaries.scheduler().absolutePath,
            "--workload-manager-path", binaries.workloadManager().absolutePath,
            "--errors-path", binaries.errors().absolutePath,
            "--engine-manager-path", binaries.engineManager().absolutePath,
        )
    }

    private fun handleNotification(notification: RpcNotification) {
        if (notification.method != "app:ready") return
        val params = notification.paramsJson?.let(::JSONObject) ?: JSONObject()
        val version = params.optString("version")
        if (version.isBlank()) {
            ready.completeExceptionally(IOException("broker app:ready notification has no version"))
        } else {
            ready.complete(version)
        }
    }

    private fun runtimeEnvironment(): Map<String, String> {
        val config = File(filesDir, "pair-config")
        val cache = File(cacheDir, "pair-cache")
        val temp = File(cacheDir, "pair-tmp")
        listOf(filesDir, config, cache, temp).forEach { directory ->
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("could not create PAIR runtime directory: ${directory.absolutePath}")
            }
        }
        return mapOf(
            "HOME" to filesDir.absolutePath,
            "XDG_CONFIG_HOME" to config.absolutePath,
            "XDG_CACHE_HOME" to cache.absolutePath,
            "TMPDIR" to temp.absolutePath,
            "NVPAIR_LOG_LEVEL" to "info",
        ) + additionalEnvironment
    }

    private fun startStderrReader(child: Process) {
        stderrThread = Thread({
            child.errorStream.bufferedReader().useLines { lines ->
                lines.forEach(onLog)
            }
        }, "pair-broker-stderr").apply {
            isDaemon = true
            start()
        }
    }

    private fun startExitMonitor(child: Process, client: JsonRpcClient) {
        monitorThread = Thread({
            val exitCode = child.waitFor()
            if (!stopping.get()) {
                onCrash(exitCode)
                ready.completeExceptionally(IOException("broker exited before ready with code $exitCode"))
                client.close()
            }
        }, "pair-broker-exit-monitor").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    override fun close() {
        stopping.set(true)
        cleanup.run(
            listOf(
                {
                    val child = process
                    val runner = processRunner
                    if (child != null && runner != null && child.isAlive) {
                        try {
                            runner.stop(child, PROCESS_STOP_TIMEOUT_MILLIS)
                        } catch (stopFailure: Exception) {
                            if (child.isAlive) throw stopFailure
                        }
                    }
                },
                { rpc?.close() },
                { joinCleanupThread(stderrThread, STDERR_JOIN_TIMEOUT_MILLIS, "broker stderr reader") },
                { joinCleanupThread(monitorThread, MONITOR_JOIN_TIMEOUT_MILLIS, "broker exit monitor") },
            ),
        )
    }

    private fun joinCleanupThread(thread: Thread?, timeoutMillis: Long, description: String) {
        if (thread == null || thread === Thread.currentThread()) return
        thread.join(timeoutMillis)
        if (thread.isAlive) throw IOException("PAIR $description did not stop in time")
    }

    companion object {
        private const val PROCESS_STOP_TIMEOUT_MILLIS = 15_000L
        private const val STDERR_JOIN_TIMEOUT_MILLIS = 2_000L
        private const val MONITOR_JOIN_TIMEOUT_MILLIS = 2_000L
        private val DEFAULT_PROXY_ENGINES = listOf("ollama", "lmstudio", "mnn")

        fun proxyEnginesForLocalMnn(mnnAvailable: Boolean): List<String> =
            if (mnnAvailable) DEFAULT_PROXY_ENGINES else DEFAULT_PROXY_ENGINES.filterNot { it == "mnn" }
    }
}

internal class RetryableCleanup(private val actionCount: Int) {
    private val completedActions = BooleanArray(actionCount)

    @Synchronized
    fun run(actions: List<() -> Unit>) {
        require(actions.size == actionCount) { "Cleanup action count changed between attempts." }
        var failure: IOException? = null
        actions.forEachIndexed { index, action ->
            if (!completedActions[index]) {
                try {
                    action()
                    completedActions[index] = true
                } catch (actionFailure: Exception) {
                    val wrapped = IOException("PAIR cleanup action ${index + 1} failed.", actionFailure)
                    failure = failure?.apply { addSuppressed(wrapped) } ?: wrapped
                }
            }
        }
        failure?.let { throw it }
    }
}
