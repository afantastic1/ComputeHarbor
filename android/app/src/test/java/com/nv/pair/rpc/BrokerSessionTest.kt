/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.rpc

import com.nv.pair.runtime.NativeBinaryRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerSessionTest {
    @Test
    fun startupCallbackFailureStopsTheAlreadyStartedBroker() {
        val (_, root) = session(emptyList())
        val child = FakeProcess()
        val runner = FakeRunner(child)
        val failingSession = BrokerSession(
            binaries = NativeBinaryRegistry(root),
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            onWaitingReady = { error("synthetic startup callback failure") },
        )

        try {
            val failure = runCatching { failingSession.startWithRunner(runner) }.exceptionOrNull()

            assertTrue("startup failure should remain visible", failure is IOException)
            assertEquals("started child must be stopped during rollback", 1, runner.stopCount.get())
            assertTrue("child must no longer be alive", !child.isAlive)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun brokerCommandIncludesConfiguredProxyEngines() {
        val (session, root) = session(listOf("ollama", "lmstudio", "mnn"))
        try {
            assertEquals("ollama,lmstudio,mnn", proxyEngines(session.commandArguments()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun brokerCommandIncludesNodeInfoServerPath() {
        val (session, root) = session(listOf("ollama", "lmstudio", "mnn"))
        try {
            val arguments = session.commandArguments()
            val nodeInfoFlag = arguments.indexOf("--node-info-path")

            assertTrue("broker command must set --node-info-path", nodeInfoFlag >= 0)
            assertEquals(root.resolve("libnvpair_node_info.so").absolutePath, arguments[nodeInfoFlag + 1])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun brokerCommandExcludesMnnWhenLocalMnnDidNotStart() {
        val (session, root) = session(listOf("ollama", "lmstudio"))
        try {
            assertEquals("ollama,lmstudio", proxyEngines(session.commandArguments()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupContinuesAfterFailureAndRetriesOnlyTheFailedAction() {
        val failedActionAttempts = AtomicInteger()
        val independentActionAttempts = AtomicInteger()
        val cleanup = RetryableCleanup(actionCount = 2)
        val actions = listOf<() -> Unit>(
            {
                if (failedActionAttempts.getAndIncrement() == 0) throw IOException("transient close failure")
            },
            { independentActionAttempts.incrementAndGet() },
        )

        val firstFailure = runCatching { cleanup.run(actions) }.exceptionOrNull()
        assertTrue(firstFailure is IOException)
        assertEquals(1, independentActionAttempts.get())

        cleanup.run(actions)
        cleanup.run(actions)

        assertEquals(2, failedActionAttempts.get())
        assertEquals("successful cleanup actions must not run twice", 1, independentActionAttempts.get())
    }

    private fun session(proxyEngines: List<String>): Pair<BrokerSession, java.io.File> {
        val root = Files.createTempDirectory("pair-broker-session").toFile()
        listOf(
            "libnvpair_ui_broker.so",
            "libnvpair_node_scanner.so",
            "libnvpair_node_settings.so",
            "libnvpair_cluster_manager.so",
            "libnvpair_proxy.so",
            "libnvpair_job_scheduler.so",
            "libnvpair_workload_manager.so",
            "libnvpair_errors.so",
            "libnvpair_engine_manager.so",
            "libnvpair_node_info.so",
        ).forEach { root.resolve(it).createNewFile() }
        return BrokerSession(
            binaries = NativeBinaryRegistry(root),
            filesDir = root.resolve("files"),
            cacheDir = root.resolve("cache"),
            proxyEngines = proxyEngines,
        ) to root
    }

    private fun proxyEngines(arguments: List<String>): String {
        val flagIndex = arguments.indexOf("--proxy-engines")
        return arguments[flagIndex + 1]
    }

    private class FakeRunner(private val process: FakeProcess) : BrokerProcessRunner {
        val stopCount = AtomicInteger()

        override fun start(): Process = process

        override fun stop(process: Process, timeoutMillis: Long): Int {
            stopCount.incrementAndGet()
            process.destroyForcibly()
            return process.exitValue()
        }
    }

    private class FakeProcess : Process() {
        private val exited = CountDownLatch(1)
        private val childOutput = ByteArrayOutputStream()
        @Volatile private var childExitCode: Int? = null

        override fun getOutputStream(): OutputStream = childOutput
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            exited.await()
            return exitValue()
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)
        override fun exitValue(): Int = childExitCode ?: throw IllegalThreadStateException("process is still running")
        override fun destroy() {
            destroyForcibly()
        }
        override fun destroyForcibly(): Process {
            childExitCode = 137
            exited.countDown()
            return this
        }
        override fun isAlive(): Boolean = childExitCode == null
    }
}
