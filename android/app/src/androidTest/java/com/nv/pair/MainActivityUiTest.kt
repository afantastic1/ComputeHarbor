/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nv.pair

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nv.pair.runtime.PairRuntimeController
import com.nv.pair.runtime.RuntimePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()


    @Test
    fun largeFontKeepsBrandOnOneLine() {
        val brand = composeRule.onNodeWithText("ComputeHarbor").fetchSemanticsNode().boundsInRoot

        assertTrue("ComputeHarbor should stay on one line at large font; height=${brand.height}", brand.height < 120f)
    }

    @Test
    fun homeShowsUnifiedGatewayPortWithoutExposingInternalMnnPort() {
        composeRule.onNodeWithText("http://127.0.0.1:14326/v1").assertIsDisplayed()
        composeRule.onAllNodesWithText("14325", substring = true).assertCountEquals(0)
    }

    @Test
    fun settingsDoesNotClaimUnprobedOpenClCapability() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Inference").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CPU").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("OpenCL").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Not checked · CPU fallback").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Not checked").assertCountEquals(2)
        composeRule.onAllNodesWithText("Probe available", substring = true).assertCountEquals(0)
    }

    @Test
    fun settingsDoesNotListDesktopEngineFacadesAsPhoneServices() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Service Diagnostics").performScrollTo().assertIsDisplayed()

        composeRule.onAllNodesWithText("ollama", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("lmstudio", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("MNN health").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backgroundForegroundAndActivityRecreationDoNotChangeStoppedRuntime() {
        val runtime = PairRuntimeController(composeRule.activity.applicationContext).state.value
        assertEquals(RuntimePhase.STOPPED, runtime.phase)
        assertEquals(false, runtime.desiredRunning)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        val afterForeground = PairRuntimeController(composeRule.activity.applicationContext).state.value
        assertEquals(RuntimePhase.STOPPED, afterForeground.phase)
        assertEquals(false, afterForeground.desiredRunning)

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        val afterRecreation = PairRuntimeController(composeRule.activity.applicationContext).state.value
        assertEquals(RuntimePhase.STOPPED, afterRecreation.phase)
        assertEquals(false, afterRecreation.desiredRunning)
    }

    @Test
    fun openingModelsAfterScrollingHomeShowsItsTop() {
        val header = composeRule.onNodeWithText("ComputeHarbor")
        val initialHeaderTop = header.fetchSemanticsNode().boundsInRoot.top
        composeRule.onNode(hasScrollAction()).performTouchInput {
            repeat(6) { swipeUp() }
        }
        val scrolledHeaderTop = header.fetchSemanticsNode().boundsInRoot.top
        assertTrue("Home must actually scroll before switching pages: $initialHeaderTop -> $scrolledHeaderTop", scrolledHeaderTop < initialHeaderTop)

        composeRule.onNodeWithText("Models").performClick()

        val modelsHeaderTop = header.fetchSemanticsNode().boundsInRoot.top
        assertEquals(initialHeaderTop, modelsHeaderTop, 1f)
        header.assertIsDisplayed()
        composeRule.onNodeWithText("On-device Models - MNN").assertIsDisplayed()
    }

    @Test
    fun navigationRemainsAvailableAfterStartingRuntime() {
        val runtime = PairRuntimeController(composeRule.activity.applicationContext)
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.revokeRuntimePermission(
                composeRule.activity.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            composeRule.onNodeWithText("Start node").performClick()
            composeRule.onNodeWithText("Models").performClick()
            composeRule.onNodeWithText("On-device Models - MNN").assertIsDisplayed()
            composeRule.onNodeWithText("Cluster").performClick()
            composeRule.onNodeWithText("No trusted nodes yet.").assertIsDisplayed()
            composeRule.onNodeWithText("Home").performClick()
            composeRule.onNodeWithText("Node Status").assertIsDisplayed()
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun localModelIdInputHasRoomAtNarrowWidthAndLargeFont() {
        composeRule.onNodeWithText("Models").performClick()
        composeRule.onNodeWithTag("destination_scroll")
            .performScrollToNode(hasTestTag("local_model_id_input"))

        val inputWidth = composeRule.onNodeWithTag("local_model_id_input")
            .fetchSemanticsNode().boundsInRoot.width
        assertTrue("Local model ID input is too narrow: ${inputWidth}px", inputWidth > 500f)
    }

    @Test
    fun viewAllJobsOpensCompleteJobsDestination() {
        composeRule.onNodeWithText("View all").performScrollTo()
        composeRule.onNodeWithText("View all").performClick()

        composeRule.onAllNodesWithText("Recent Jobs").assertCountEquals(2)
        composeRule.onNodeWithText("No requests have been routed yet.").assertIsDisplayed()
    }

}
