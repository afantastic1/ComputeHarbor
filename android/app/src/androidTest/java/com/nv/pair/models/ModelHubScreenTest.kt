/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nv.pair.mnn.MnnBackend
import com.nv.pair.mnn.BackendChoice
import com.nv.pair.mnn.EffectiveBackendSelection
import com.nv.pair.mnn.MnnCapabilitySnapshot
import com.nv.pair.mnn.BackendCapability
import com.nv.pair.mnn.ProbeState
import com.nv.pair.runtime.MnnLocalEngineStatus
import com.nv.pair.data.PairNode
import com.nv.pair.mnn.MnnEngineState
import com.nv.pair.rpc.parseCloudProviderSettings
import org.json.JSONObject
import com.nv.pair.models.ModelCompatibility
import com.nv.pair.models.ModelDescriptor
import com.nv.pair.models.ModelFormat
import com.nv.pair.models.ModelSource
import com.nv.pair.models.ModelSourceKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelHubScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun automaticAliasesReflectGatewayModelList() {
        composeRule.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ModelHubScreen(
                    nodes = emptyList(),
                    gatewayModels = listOf(
                        GatewayModel("qwen3-1.7b", GatewayModelKind.LOCAL),
                        GatewayModel("auto", GatewayModelKind.AUTOMATIC),
                        GatewayModel("auto-balanced", GatewayModelKind.AUTOMATIC),
                        GatewayModel("auto-new-policy", GatewayModelKind.AUTOMATIC),
                    ),
                    backendChoice = BackendChoice.Auto,
                    capabilities = MnnCapabilitySnapshot(),
                    effectiveBackendSelection = EffectiveBackendSelection(BackendChoice.Auto, MnnBackend.CPU, resolving = true),
                    localEngineStatus = MnnLocalEngineStatus(available = false),
                    serviceRunning = false,
                    onManualBackendChange = {},
                    onResetBackendToAuto = {},
                    onReprobeOpenCl = {},
                    cloudProviderSettings = null,
                    onCloudEnabledChange = {},
                    onCloudPolicyChange = {},
                )
            }
        }

        composeRule.onNodeWithText("auto").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("auto-balanced").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("auto-new-policy").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun selectingOpenClUpdatesPreferredBackend() {
        var selectedBackend: MnnBackend? = null
        composeRule.setContent {
            ModelHubScreen(
                nodes = emptyList(),
                gatewayModels = listOf(
                    GatewayModel("auto", GatewayModelKind.AUTOMATIC),
                    GatewayModel("auto-balanced", GatewayModelKind.AUTOMATIC),
                ),
                backendChoice = BackendChoice.Manual(MnnBackend.CPU),
                capabilities = MnnCapabilitySnapshot(openCl = BackendCapability(MnnBackend.OPENCL, ProbeState.AVAILABLE)),
                effectiveBackendSelection = EffectiveBackendSelection(BackendChoice.Manual(MnnBackend.CPU), MnnBackend.CPU),
                localEngineStatus = MnnLocalEngineStatus(available = true),
                serviceRunning = true,
                onManualBackendChange = { selectedBackend = it },
                onResetBackendToAuto = {},
                onReprobeOpenCl = {},
                cloudProviderSettings = null,
                onCloudEnabledChange = {},
                onCloudPolicyChange = {},
            )
        }

        composeRule.onNodeWithText("OpenCL").performClick()

        assertEquals(MnnBackend.OPENCL, selectedBackend)
    }

    @Test
    fun unavailableManualOpenClShowsSafeCpuFallback() {
        composeRule.setContent {
            ModelHubScreen(
                nodes = emptyList(),
                gatewayModels = null,
                backendChoice = BackendChoice.Manual(MnnBackend.OPENCL),
                capabilities = MnnCapabilitySnapshot(
                    openCl = BackendCapability(MnnBackend.OPENCL, ProbeState.UNAVAILABLE),
                ),
                effectiveBackendSelection = EffectiveBackendSelection(
                    BackendChoice.Manual(MnnBackend.OPENCL), MnnBackend.CPU, unavailableManualChoice = true,
                ),
                localEngineStatus = MnnLocalEngineStatus(
                    available = true,
                    backend = MnnBackend.CPU,
                ),
                serviceRunning = true,
                onManualBackendChange = {},
                onResetBackendToAuto = {},
                onReprobeOpenCl = {},
                cloudProviderSettings = null,
                onCloudEnabledChange = {},
                onCloudPolicyChange = {},
            )
        }

        composeRule.onNodeWithText(
            "Manual OpenCL preference is saved. OpenCL is currently unavailable, so CPU is the safe fallback.",
        ).assertIsDisplayed()
    }

    @Test
    fun stoppedRuntimeDoesNotDescribeOpenClProbeAsInProgress() {
        composeRule.setContent {
            androidx.compose.material3.MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ModelHubScreen(
                        nodes = emptyList(),
                        gatewayModels = null,
                        backendChoice = BackendChoice.Auto,
                        capabilities = MnnCapabilitySnapshot(),
                        effectiveBackendSelection = EffectiveBackendSelection(
                            BackendChoice.Auto, MnnBackend.CPU, resolving = true,
                        ),
                        localEngineStatus = MnnLocalEngineStatus(available = false),
                        serviceRunning = false,
                        onManualBackendChange = {},
                        onResetBackendToAuto = {},
                        onReprobeOpenCl = {},
                        cloudProviderSettings = null,
                        onCloudEnabledChange = {},
                        onCloudPolicyChange = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Effective engine: CPU fallback (OpenCL not checked)")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cloudCardListsGatewayCloudModelsAndHostOnlyNotice() {
        composeRule.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ModelHubScreen(
                    nodes = emptyList(),
                    gatewayModels = listOf(GatewayModel("cloud/work/chat", GatewayModelKind.CLOUD)),
                    backendChoice = BackendChoice.Auto,
                    capabilities = MnnCapabilitySnapshot(),
                    effectiveBackendSelection = EffectiveBackendSelection(BackendChoice.Auto, MnnBackend.CPU, resolving = true),
                    localEngineStatus = MnnLocalEngineStatus(available = false),
                    serviceRunning = false,
                    onManualBackendChange = {},
                    onResetBackendToAuto = {},
                    onReprobeOpenCl = {},
                    cloudProviderSettings = null,
                    onCloudEnabledChange = {},
                    onCloudPolicyChange = {},
                )
            }
        }

        composeRule.onNodeWithText("Cloud resources").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("cloud/work/chat").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "This device's provider keys stay here and are never sent to paired nodes. A remote host keeps its own Provider key and must authorize this device.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun providerEditorSubmitsMetadataAndKeepsCredentialOutOfProviderJson() {
        var submittedProvider: JSONObject? = null
        var submittedCredential = ""
        val settings = parseCloudProviderSettings(
            JSONObject("""{"schema_version":1,"config":{"schema_version":1,"providers":[]},"cloudEnabled":false,"policy":"local_only","monthlyBudgetUSD":0,"perRequestMaxEstimatedCostUSD":0}"""),
        )
        composeRule.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ModelHubScreen(
                    nodes = emptyList(),
                    gatewayModels = null,
                    backendChoice = BackendChoice.Auto,
                    capabilities = MnnCapabilitySnapshot(),
                    effectiveBackendSelection = EffectiveBackendSelection(BackendChoice.Auto, MnnBackend.CPU),
                    localEngineStatus = MnnLocalEngineStatus(available = false),
                    serviceRunning = true,
                    onManualBackendChange = {},
                    onResetBackendToAuto = {},
                    onReprobeOpenCl = {},
                    cloudProviderSettings = settings,
                    onCloudEnabledChange = {},
                    onCloudPolicyChange = {},
                    onSaveCloudProvider = { provider, credential ->
                        submittedProvider = provider
                        submittedCredential = credential
                    },
                )
            }
        }

        composeRule.onNodeWithText("Add OpenAI-compatible provider").performScrollTo().performClick()
        composeRule.onNodeWithText("Provider ID").performTextInput("sample")
        composeRule.onNodeWithText("OpenAI-compatible base URL").performTextReplacement("https://api.example.test/v1")
        composeRule.onNodeWithText("PAIR model ID (cloud/provider/model)").performTextInput("cloud/sample/chat")
        composeRule.onNodeWithText("Provider model ID").performTextInput("chat-model")
        composeRule.onNodeWithText("API key").performTextInput("secret-value")
        composeRule.onNodeWithText("Save").performClick()

        assertEquals("sample", submittedProvider?.optString("id"))
        assertEquals("openai_chat_completions", submittedProvider?.optString("protocol"))
        assertEquals("secret-value", submittedCredential)
        assertEquals(false, submittedProvider?.has("credential"))
        assertEquals(false, submittedProvider?.toString()?.contains("secret-value"))
    }

    @Test
    fun remotePcModelStaysInNetworkInventoryAndNotOnDeviceMnn() {
        composeRule.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ModelHubScreen(
                    nodes = listOf(PairNode(
                        id = "pc-node-id",
                        name = "Studio PC",
                        hostUuid = "pc-stable-uuid",
                        ipAddress = "192.0.2.40",
                        modelsByEngine = mapOf("lmstudio" to listOf("pc-qwen")),
                    )),
                    gatewayModels = null,
                    backendChoice = BackendChoice.Auto,
                    capabilities = MnnCapabilitySnapshot(),
                    effectiveBackendSelection = EffectiveBackendSelection(BackendChoice.Auto, MnnBackend.CPU, resolving = true),
                    localEngineStatus = MnnLocalEngineStatus(available = false),
                    serviceRunning = false,
                    onManualBackendChange = {},
                    onResetBackendToAuto = {},
                    onReprobeOpenCl = {},
                    cloudProviderSettings = null,
                    onCloudEnabledChange = {},
                    onCloudPolicyChange = {},
                )
            }
        }

        composeRule.onNodeWithText("No MNN models are installed.").assertIsDisplayed()
        composeRule.onNodeWithText("Available via Network").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("pc-qwen · lmstudio · pc-stable-uuid · Installed")
            .performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("pc-qwen", substring = true).assertCountEquals(1)
    }

    @Test
    fun installedModelShowsLoadedOnlyWhenMnnReportsItLoaded() {
        composeRule.setContent {
            androidx.compose.material3.MaterialTheme {
                OnDeviceMnnModelsSection(
                    installedModels = listOf(installedModel()),
                    localEngineStatus = MnnLocalEngineStatus(
                        available = true,
                        engineState = MnnEngineState.READY,
                        modelId = "qwen3-0.6b",
                    ),
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("Loaded on this device").assertIsDisplayed()
        composeRule.onNodeWithText("Tool Calling · Unsupported").assertIsDisplayed()
    }

    @Test
    fun installedModelWithoutMatchingRuntimeModelIsNotReportedLoaded() {
        composeRule.setContent {
            androidx.compose.material3.MaterialTheme {
                OnDeviceMnnModelsSection(
                    installedModels = listOf(installedModel()),
                    localEngineStatus = MnnLocalEngineStatus(
                        available = true,
                        engineState = MnnEngineState.READY,
                        modelId = "different-model",
                    ),
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("Installed - not loaded").assertIsDisplayed()
        composeRule.onAllNodesWithText("Loaded on this device").assertCountEquals(0)
    }

    @Test
    fun modelLoadErrorShowsFailureStateWithoutCallingItReady() {
        composeRule.setContent {
            androidx.compose.material3.MaterialTheme {
                OnDeviceMnnModelsSection(
                    installedModels = listOf(installedModel()),
                    localEngineStatus = MnnLocalEngineStatus(
                        available = true,
                        engineState = MnnEngineState.ERROR,
                        modelId = "qwen3-0.6b",
                    ),
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("Load failed - see diagnostics").assertIsDisplayed()
        composeRule.onAllNodesWithText("Loaded on this device").assertCountEquals(0)
    }

    private fun installedModel() = ModelDescriptor(
        logicalId = "local:qwen3-0.6b",
        engineModelId = "qwen3-0.6b",
        displayName = "Qwen 3 0.6B",
        family = "qwen",
        parameterCount = 600_000_000,
        quantization = null,
        contextLength = null,
        source = ModelSource(ModelSourceKind.LOCAL, "fixture"),
        format = ModelFormat.MNN,
        estimatedMemoryBytes = 1024,
        compatibility = ModelCompatibility.COMPATIBLE,
    )
}
