<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# Android UI Baseline Audit

Audit date: 2026-10-14 (Asia/Shanghai)

## Baseline

- Repository HEAD: `487c4ef788e5a497bbb429757cf389aba3dd2fcd`
- The worktree already contained MNN/native/model implementation changes and related tests. Those changes were preserved.
- The Android app originally exposed Network, Cluster, and Models destinations. Settings was not a screen.
- The supplied reference image is for layout and palette only. Its sample identities, addresses, model names, capabilities, and pairing comparison are not runtime facts.
- The Android SDK is installed at `D:\_software\sdk` (API 36, NDK 27.2.12479018, CMake 3.22.1).

## State sources

| UI claim | Source | Meaning |
|---|---|---|
| Runtime phase, broker version, startup error | `PairRuntimeService.runtimeState` through `PairRuntimeController.state` | Authoritative broker lifecycle state. |
| Discovered nodes and trust | `PairRuntimeService.discoveredNodes` (`PairNode`) | Current discovery snapshot; stable identity is `hostUuid`. |
| Paired/offline relation and invites | `clusterState` projected through `buildPeerViews` | Repository-backed membership and invite state. PIN is scoped to `inviteId`. |
| Local MNN health and model activity | `mnnLocalEngine` plus MNN runtime status | Local engine state; proxy health does not imply inference activity. |
| MNN capability and effective selection | Capability snapshot, effective selection, and saved preference | Separates probe result from preference and fallback. |
| Proxy status | `RouterRepository` (`EngineProxyStatus`) | Gateway facade health, not a local engine inventory. |
| Installed local models | `ModelHubViewModel` / `ModelHubInstaller` | App-private MNN installations only. |
| Gateway/network models | `GatewayModelRepository` and `ModelInventoryRepository` | May include local, remote, automatic, and cloud inventory. |
| Workloads | `RouterRepository.workloads` (`PairWorkload`) | `running` plus matching `scheduledOn` UUID substantiates active inference. |
| Unified local API URL | Android gateway and `android/README.md` | `http://127.0.0.1:14326/v1`, loopback-only. MNN port `14325` is internal. |

## Implementation record

- Home presents Node Status, Local API, Local Inference, Connected Nodes, and Recent Jobs. It hides API credentials and only copies the endpoint. Stop is visibly destructive and has a 48dp minimum target. Stopping while a workload is running now requires confirmation.
- Local inference state is projected from MNN runtime state, including unloaded, loading, ready, generating, and errors. The runtime adapter retains existing state/model/error fields without changing RPC contracts.
- Connected nodes are projected by stable UUID. Active workload attribution requires `running` and an exact `scheduledOn` UUID match. Loaded engine models are labeled Loaded, never Running. Offline cards show available last-seen data; unknown device types are labeled unreported.
- Remote node cards open a detail dialog with the stable node ID, connectivity, reported address, last-seen time, and node-attributed inference/model state.
- Recent Jobs prioritize running workloads and show source, normalized status, target, duration, and creation age. `View all` opens the complete recent workload list.
- Known workload state values map to the plan's Queued / Running / Completed / Failed / Cancelled labels; unrecognized values remain explicit as Unknown instead of being inferred.
- Cluster pairing uses a review dialog and invite-specific dialogs. PIN entry is masked, six-digit only, and scoped by `inviteId`. Decline/cancel wait for repository state; dismissing a dialog does not cancel the invite.
- Models separates installed MNN models, Browse / Install, and network inventory. Installed MNN models show Tool Calling as Unsupported because the Android OpenAI-compatible parser rejects tool/function request fields; installed and loaded states are distinct.
- Settings exposes device/network/runtime identity, MNN preference and capability, model storage, loopback API details, and local Broker/MNN diagnostics. It does not reveal the token or list desktop-only Ollama/LM Studio facades as phone services.
- Home includes stopped and running CPU-only Compose previews. Pure state projection tests cover local MNN, UUID attribution, and stop confirmation logic.
- The app theme defaults to the supplied dark navy/teal palette, independent of the device's current light theme. System bar styling uses light icons on the dark edge-to-edge surfaces.

## Verification

- `ANDROID_HOME=D:\_software\sdk ./gradlew :app:compileDebugKotlin`: passed.
- `ANDROID_HOME=D:\_software\sdk ./gradlew :app:testDebugUnitTest --tests 'com.nv.pair.ui.state.*'`: passed.
- `ANDROID_HOME=D:\_software\sdk ./gradlew :app:assembleDebug`: passed.
- After adding remote node details, `:app:testDebugUnitTest --tests 'com.nv.pair.ui.state.*' :app:assembleDebug`: passed (45 actionable tasks).
- After adding the full Recent Jobs destination and aligning Cluster section order to the plan, the same targeted tests and Debug build passed again (45 actionable tasks).
- Added workload status projection coverage for all five plan states and an unknown-state case; the UI projection tests and Debug build passed (45 actionable tasks).
- Full JVM suite rerun on the current worktree: 196 tests ran; 194 passed and 2 failed in the pre-existing MNN implementation (`MnnInferenceServiceTest.rejectsConcurrentGenerationAndAcceptsNextRequestAfterCancellation` at `MnnInferenceServiceTest.kt:254` and `MnnHttpServerTest.closeCanBeRetriedAfterAnInterruptedCleanupWaitAndPreventsRestart` at `MnnHttpServerTest.kt:435`). These runtime implementation failures are outside this Android UI task; the UI work did not modify those files.
- The device connected over ADB is a Xiaomi 22127RK46C (1080x2400 override, density 420). The current installed APK was backed up, the same-debug-signed build APK was installed for visual inspection, and the original APK was restored afterward. Home was captured after correcting both system-light rendering and the Material card surface colors: [`evidence/home.png`](evidence/home.png). The screenshot verifies the dark navy background, blue card surfaces, teal primary action, first-screen Start action, loopback API labeling, local MNN attribution, and Recent Jobs entry. Xiaomi rejects `adb shell input tap` with `INJECT_EVENTS` SecurityException, so the other destinations and pairing dialogs could not be navigated for capture. No Start, pairing, or inference action was invoked. After capture, the installed APK SHA-256 matched the pre-test backup.
- Installed Android API 36.1 Google APIs x86_64 system image and created the `ComputeHarborUi` Pixel 6 emulator. Real pair/inference actions were not invoked.
- `:app:assembleDebug :app:assembleDebugAndroidTest` passes. Direct emulator instrumentation passes in separate class invocations: 9 Cluster tests, 7 MainActivity tests, and 9 Models tests (25 total). Coverage includes nearby-device review and explicit invite confirmation, cancel-without-invite, rapid double-tap deduplication, invite-ID scoping, six-digit PIN entry, invitation switching without PIN carry-over, repository-driven pairing success, incorrect-PIN retry feedback, pending-invite dialog recovery after screen recreation without resending the invite, stopped-cluster guidance, background/foreground transitions and Activity recreation preserving the stopped runtime state, Settings showing unprobed OpenCL as not checked and CPU fallback without claiming a probe is in progress, theme color, page scroll reset, Home-to-Jobs navigation, large-font header, narrow import-field width, correct display of gateway port 14326 without exposing internal MNN port 14325, remote PC model attribution exclusively in network inventory, OpenCL availability/fallback, automatic model aliases, cloud inventory labeling, and installed-model Loaded / not-loaded / load-error labels derived from the matching local MNN runtime model ID and state. Tool Calling is labeled Unsupported for installed MNN models.
- Added focused UUID relationship regression tests: same-name peers with distinct UUIDs remain separately invitable, while duplicate discovery records with the same UUID fail closed. `:app:testDebugUnitTest --tests com.nv.pair.data.PeerRelationshipResolverTest --tests com.nv.pair.ui.state.NodeUiProjectionTest` passes.
- Extended Local Inference projection coverage for Loading, Generating, Stopped, and Unavailable; stopped/unavailable states do not claim model readiness. The focused Local Inference and node projection test invocation passes; the full-suite run above includes these three added tests.
- The first earlier instrumentation process was force-stopped during investigation; that process reported a crash because it was intentionally stopped. Subsequent emulator runs completed normally, including all 8 Cluster UI tests.
- Reproduced the local model ID field shrinking to 71px at 320dp width / 1.5 font scale. Updated `ModelHubScreen` to stack the input and import button at narrow widths or large font. The MainActivity instrumentation run used 320dp width / 1.5 font scale and verifies the field exceeds 500px. Cluster UI tests and the MainActivity suite were run separately because one combined invocation did not return within the command's observation window.
- Captured and visually reviewed emulator Home, Cluster, Models, Settings, Home's Recent Jobs empty state, and lower Models/Settings views at normal size. The MainActivity tests separately verify opening the complete Jobs destination. Screenshots are stored in `evidence/`.
- Captured the latest installed debug APK's Home, Cluster, Models, and Settings screens directly from the emulator after page navigation. `evidence/emulator-current-cluster.png` shows truthful stopped/no-peer guidance; `emulator-current-models.png` shows no installed MNN models, runtime unavailable, and OpenCL not checked; `emulator-current-settings.png` shows the effective engine as “Not checked · CPU fallback”. No service, pairing, or inference action was invoked for these captures.
- After the user connected a Xiaomi 22127RK46C over USB, ADB confirmed the phone was authorized. The verified debug APK was installed because `com.nv.pair` was not present. Real-device Home, Cluster, and Models screenshots are `evidence/physical-xiaomi-home.png`, `evidence/physical-xiaomi-cluster.png`, and `evidence/physical-xiaomi-models.png`. The Models screen truthfully reports no installed MNN models, runtime unavailable, OpenCL not checked, and CPU fallback while stopped. These pages make no synthetic pairing or capability claims. ADB screenshot/pull works, but `adb shell input tap` is rejected by the OEM with `INJECT_EVENTS`, so further page navigation requires the user to switch pages manually. No runtime start, pairing, or inference was invoked.
- Settings initially exposed the generic broker proxy inventory (`ollama`, `lmstudio`, `mnn`) as local engine facades, despite the Android plan requiring local MNN to be distinct from desktop engine services. Removed that facade inventory from Settings while retaining Broker and MNN health diagnostics. The focused instrumentation regression test failed before the fix on `ollama` and passes afterward. The updated debug APK was installed on the Xiaomi and `evidence/physical-xiaomi-settings.png` confirms Service Diagnostics now shows Broker Stopped, MNN Unavailable, and MNN state Not reported, with no Ollama or LM Studio entries. The API section reports the loopback-only unified endpoint and keeps the API key hidden.
- Started the node from Home on the x86_64 emulator. The UI showed its real retry/backoff state, but the ARM64 `nvpair-ui-broker` crashed under the emulator's ARM translation layer, so this environment cannot verify a healthy running screenshot. Stopped the node afterward and confirmed Home returned to Stopped; no pair or inference request was made. The retry-state screenshot is `evidence/emulator-home-reconnecting.png`.
- At high font scale, narrow Home remains readable with a one-line brand and stacked header actions. The physical Xiaomi continues to reject injected taps, so live two-device pairing and pairing failure paths remain unverified. UI instrumentation validates invite dialog behavior without contacting a peer.
- `git diff --check` passes. SPDX scan reports 22 existing/unmodified files missing headers, including supplied plans.

## Remaining evidence gaps

Live pairing failure paths and two-device acceptance still require a running Windows PAIR peer and user-visible pairing interaction. The emulator and physical Xiaomi screenshots show the stopped/no-peer runtime state; they do not stand in for pairing acceptance. Physical Xiaomi injected taps remain blocked by OEM policy, so the user must switch to Models and Settings for further hardware screenshots. The full JVM suite also remains red for the two unrelated MNN tests listed above.
