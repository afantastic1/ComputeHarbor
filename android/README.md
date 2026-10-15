<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# PAIR for Android

The Android app hosts the PAIR services and the optional MNN model runtime. Model weights are supplied locally for testing; they are not stored in this repository or packaged in the APK.

## Android UI

The Home screen puts runtime Start/Stop first, then shows the phone-only unified API and the local MNN runtime. The API card uses the loopback gateway at `http://127.0.0.1:14326/v1`; this address is intended for clients running on the phone. The MNN card reports runtime health only and does not claim that a model is loaded or generating. Service facade health is available under Settings diagnostics and is not inference activity.

Connected node activity is attributed using the stable discovered node UUID and workload `scheduledOn` field. A model in `loadedByEngine` is labeled Loaded; only a workload with state `running` is labeled Active Inference. Cluster pairing actions use invite-scoped dialogs and continue to call the existing invite/respond/cancel commands. Closing a dialog does not cancel a remote invite.

The Models screen separates installed on-device MNN models, catalog browsing/install, and models exposed through the PAIR network. Tool Calling is shown as Not verified where the current Android UI has no capability evidence. Settings only exposes values available from Android/runtime state; hardware resource values and LAN endpoints are not inferred from the design reference.

See [`ComputeHarbor_Android_UI_Kit/CURRENT_UI_AUDIT.md`](../docs/ComputeHarbor_Android_UI_Kit/CURRENT_UI_AUDIT.md) for the baseline SHA and the UI state-source audit. UI screenshots and device acceptance remain pending when an Android SDK/device is unavailable.

On Android/arm64, the broker packages engine-manager in hosted mode and the
MNN proxy facade listens on `:14324` while the app-owned engine remains on
loopback `:14325`. Discovery advertises service key `mn` only while both
endpoints pass their health checks; engine-manager's `em` endpoint supplies
`modelsByEngine.mnn` to PAIR discovery.

## MNN loopback engine

While `PairRuntimeService` is running, it owns an OpenAI-compatible MNN engine at `127.0.0.1:14325`. The listener is never exposed on Wi-Fi or another LAN interface. It provides:

```text
GET  /healthz
GET  /v1/models
POST /v1/chat/completions
GET  /internal/models/loaded
POST /internal/models/load
POST /internal/models/unload
```

Install each model as one directory below the app-private `files/mnn/models/` directory. The directory name is the exact model ID exposed by `/v1/models`. One model may be loaded and one generation may run at a time; a simultaneous generation returns `409 engine_busy`. Stopping PAIR first stops HTTP acceptance and requests cancellation of any active generation. HTTP workers and native runtime shutdown each have a 10-second bound, so full MNN container shutdown can take up to 20 seconds. If native inference does not return, PAIR continues stopping and logs that native resources may remain allocated until process exit. In that case, MNN may not unload cleanly, but the PAIR foreground service does not wait indefinitely for the optional engine. A native call that never returns cannot be safely force-killed in-process; isolating MNN in a separate process is future work.

The Models tab stores either automatic selection or a manual CPU/OpenCL
preference in DataStore. New installs use automatic selection: an OpenCL runtime
probe that succeeds selects OpenCL; every other result selects CPU. Existing
`mnn_preferred_backend` values migrate as manual preferences. Manual OpenCL stays
saved when unavailable, while the effective engine safely falls back to CPU.
Changing the effective engine applies to the next inference request and does
not interrupt an active generation.

The OpenCL probe initializes the MNN runtime without loading a model. An
`AVAILABLE` result only means that the runtime initialized; model compatibility
is determined when a model loads and generates. Probe results are not persisted
and are refreshed when the runtime starts or when the user requests a reprobe.
The default APK compiles and packages OpenCL support. Devices without a usable
OpenCL runtime continue to use CPU; build a CPU-only APK with
`-PpairMnnOpenCL=false`. OpenCL builds request optional access to the public
system library `libOpenCL.so`; this does not bypass Android vendor library
access rules.

The broker currently advertises the local MNN engine from overall runtime
health, not from model-level readiness or the effective OpenCL capability. Do
not treat a successful probe or an engine health response as proof that a model
can run. Tightening gateway advertisement for an unavailable effective engine
is a follow-up acceptance item; P0 intentionally leaves the Go broker contract
unchanged.

Chat completions accept structured `system`, `user`, and `assistant` messages. MNN applies the model's chat template. Both streamed SSE and non-streamed responses support `max_tokens`, `temperature`, `top_p`, and `seed`; the native runtime's terminal status maps to `finish_reason: stop` or `finish_reason: length`. Error responses keep HTTP status, OpenAI error type, and error code aligned. Unsupported OpenAI capabilities return an explicit HTTP 400 response.

## PAIR local OpenAI API

When the PAIR runtime is running, the proxy process also hosts the unified
OpenAI-compatible endpoint at `http://127.0.0.1:14326/v1`. Configure a local
third-party chat client with that base URL and the placeholder key `pair-local`.
The key is not authentication: this listener is loopback-only. `GET /models`
lists the current union of discovered models, and `POST /chat/completions`
routes an explicit model through its engine facade and the existing PAIR node
scheduler. The Android Network screen shows the endpoint and provides a copy
action.

## Model Hub

The Models screen keeps searchable ModelScope and Hugging Face catalog entries
separate from models reported by PAIR runtime inventory. Catalog metadata can
describe installation candidates, but automatic selection only uses models
currently advertised by a runtime. Local MNN imports and verified catalog
downloads are installed under app-private `files/mnn/models/`; downloads resume
partial transfers and verify each artifact against trusted SHA-256 metadata
before atomically publishing the model directory. Catalog entries without
complete artifact metadata remain browsable, while Verified Install stays
disabled with a specific verification or artifact availability status. Local
imports validate the MNN files independently of remote checksum metadata.
Installed models can be removed from this screen.

The unified API advertises `auto`, `auto-fast`, `auto-balanced`, and `auto-best`
in addition to explicit model IDs. The Android Models screen reads those IDs
from the gateway model list. Go owns automatic model and engine selection,
including request capability filtering; the existing engine facade still
chooses the node and records workload attribution.

For the M11 device acceptance, use a third-party OpenAI-compatible chat app on
the phone, set Base URL to `http://127.0.0.1:14326/v1`, and use `pair-local` if
the app requires a key. Request a model available only on a PC (for example,
`qwen3-8b`) and verify the response streams while PAIR records the workload on
the PC engine. Then request an installed Android MNN model such as
`qwen3-1.7b` through the same URL and verify its workload is attributed to MNN.

## MNN device acceptance

Routine native-runtime acceptance uses a local MNN-format Qwen 0.6B model directory. It must contain `config.json`, `llm.mnn`, `llm.mnn.weight`, and either `tokenizer.mtok` or `tokenizer.txt`. Connect and authorize one ARM64 Android device with USB debugging or wireless ADB, then run from the repository's `android` directory:

```powershell
.\scripts\run-mnn-acceptance.ps1 -ModelDir D:\models\qwen3-0.6b-mnn
```

For the final Qwen3-1.7B device gate, pass its MNN fixture as `-ModelDir` and optionally supply the separate Qwen3-0.6B fixture for model-switch acceptance:

```powershell
.\scripts\run-mnn-acceptance.ps1 `
  -ModelDir D:\models\qwen3-1.7b-mnn `
  -ModelSwitchDir D:\models\qwen3-0.6b-mnn
```

If multiple devices are connected, select one explicitly with `-Serial <adb-serial>`. The runner builds and installs the debug app, copies the fixture through a temporary device staging directory into a unique app-private directory, then runs the instrumentation suite once with the CPU runtime and once with the OpenCL-enabled runtime. It removes its temporary staging and app-private fixture copies on exit. The test reports missing fixture files instead of skipping acceptance.

The instrumented tests cover CPU load, generation and streaming, cancellation, metrics, unload/reload, five repeated load/generate/unload cycles with post-unload PSS comparisons, and an OpenCL load/generation probe. OpenCL may return the typed `BACKEND_UNSUPPORTED` result; in that case the test still verifies that CPU remains usable. Prompts and generated text are not written to test logs.

The final planned device gate additionally uses the pinned Qwen3-1.7B MNN fixture. See `../docs/superpowers/plans/2026-10-05-pair-android-mnn-runtime.md` for its pinned snapshot and remaining acceptance requirements.

## Two-device pairing persistence acceptance

Connect and authorize one clean ARM64 Android test device. The acceptance runner starts an isolated PC broker, waits for Android to create a pairing invitation, accepts it on the PC, then verifies that the paired PC remains trusted after Android Stop/Start. Run it from `android`:

```powershell
.\scripts\run-pairing-persistence-acceptance.ps1 -PcAddress 192.168.1.20
```

Pass `-Serial <adb-serial>` when multiple Android devices are attached. The test requires an unpaired Android installation and ports 14321/14324 free on the PC. The runner removes only its unique temporary PC broker directory and log files. A successful script run is required to report this real-device acceptance as PASS; compilation alone does not count.

## Cluster shutdown

Using the runtime's explicit **Stop** action closes the local Broker without leaving its cluster. The node identity, cluster membership, and trust state persist, so starting PAIR again does not require pairing again. Use the explicit **Leave cluster** action to withdraw membership; this operation remains distinct from stopping the runtime.
