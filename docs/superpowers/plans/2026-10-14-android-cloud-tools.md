<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# Android Cloud Providers and Tool Protocol Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Android management for OpenAI-compatible cloud providers and ensure each PAIR OpenAI-compatible invocation path either faithfully supports caller-executed tool calls or reports that tools are unsupported.

**Architecture:** Android Models UI remains a client of the local broker. Provider metadata uses the current broker registry contract, while Android Keystore encrypts the phone-owned credential and restores it into the existing broker/proxy memory path. Go Gateway preserves standard tool protocol; Android MNN needs structured Kotlin/JNI/native support proven against a compatible runtime/model before it can advertise tools.

**Tech Stack:** Kotlin, Jetpack Compose, Android Keystore AES/GCM, JSON-RPC, Go broker/proxy, JNI/C++, MNN, Gradle and Go tests.

**Spec:** `docs/ComputeHarbor_Android_UI_Kit/MOBILE_CLOUD_AND_TOOLS_SPEC.md`

## Global Constraints

- Services are the runtime source of truth; Android does not implement routing, provider HTTP execution, budget accounting, cluster trust, or tool execution.
- Phone-owned provider credentials remain on the phone, encrypted at rest, absent from settings reads and logs, and are never copied to paired nodes.
- PAIR transports tool protocol; the caller executes tools.
- Every tool-capable request preserves `tools`, `tool_choice`, assistant `tool_calls`, tool result IDs/content, finish reasons, and streaming deltas.
- A path that cannot honor tools returns one clear capability error; it does not drop fields or retry in a loop.
- Keep strict typing, absolute cross-directory imports where applicable, static imports, and two-line SPDX headers.
- Any JSON-RPC change must update producer, broker relay, consumers, desktop bridge where applicable, tests, documentation, and generated service contracts.

## Review Focus

- Missing/invalid Keystore key or unreadable ciphertext: provider is marked as needing credential re-entry; no plaintext fallback. Test encrypted store recovery and reset behavior.
- Provider removed or auth reference changed: stale in-memory credential is pruned. Test credential set, replacement, removal, and restart restoration.
- Tool schema, assistant tool call, tool result, or SSE arguments are malformed/fragmented: preserve valid wire content and return a bounded protocol error for invalid input. Test both whole JSON and fragmented streaming.
- No tool-capable target exists: one stable unsupported-capability response with no repeated dispatch. Test local-only, cloud-only, and mixed inventory.
- MNN model/runtime cannot generate structured tool calls: report tools unavailable and reject once. Test capability reporting and error response on the shipped runtime/model.

---

## File Map

Expected areas (confirm exact class/test names before editing):

- `android/app/src/main/java/com/nv/pair/rpc/RouterApi.kt` — typed provider registry and existing RPC calls.
- `android/app/src/main/java/com/nv/pair/runtime/PairRuntimeService.kt` — provider mutations, status refresh, and credential restoration on runtime readiness.
- `android/app/src/main/java/com/nv/pair/runtime/` — new encrypted provider credential store, following `GatewayTokenStore.kt` without sharing token keys.
- `android/app/src/main/java/com/nv/pair/models/ModelHubScreen.kt` and focused provider UI components — provider list/editor/test states.
- `android/app/src/androidTest/java/com/nv/pair/models/` and `android/app/src/test/java/com/nv/pair/` — UI, RPC, and credential lifecycle coverage.
- `android/app/src/main/java/com/nv/pair/mnn/MnnModels.kt`, `NativeMnn.kt`, `mnn/http/OpenAiRequestParser.kt`, `MnnHttpServer.kt`, and `mnn/http/` response/SSE code — structured MNN tool request/result protocol.
- `android/app/src/main/cpp/NativeMnn.cpp`, `CMakeLists.txt`, and any MNN integration glue required by the verified runtime API — native structured tool generation/callback handling.
- `services/nvpair-proxy/gateway_dispatcher.go`, gateway capability code, and nearby Go tests — only if the existing Gateway path audit finds a missing behavior.
- `services/nvpair-ui-broker/cloudproviders.go` and broker tests — only if Android lifecycle needs an RPC not already exposed.
- `docs/ComputeHarbor_Android_UI_Kit/ANDROID_UI_IMPLEMENTATION_PLAN.md`, Android/service docs, and generated desktop service contracts — update only for implemented behavior; regenerate contracts with the prescribed command.

## Task 1: Android provider DTO and credential storage

**Files:**
- Modify: `android/app/src/main/java/com/nv/pair/rpc/RouterApi.kt`
- Create: `android/app/src/main/java/com/nv/pair/runtime/CloudProviderCredentialStore.kt`
- Test: focused `RouterApi` parser/RPC unit tests and `CloudProviderCredentialStore` unit tests

**Interfaces:**
- Consumes: current versioned broker provider registry and RPC names (`cloudproviders:get/save`, `cloudproviders:credential:set`, `cloudproviders:test`).
- Produces: typed provider metadata with no credential field; credential store methods `put(authRef: String, credential: String)`, `get(authRef: String): String?`, `remove(authRef: String)`, and `clearAll()`.

- [x] Write failing parser tests for OpenAI-compatible provider/model metadata, unknown protocol rejection, and proof that serialized settings contain no credential.
- [x] Run the focused Android JVM tests and confirm the new cases fail.
- [x] Implement typed metadata parsing and RPC wrappers around the existing wire methods; keep wire names and schema unchanged.
- [x] Write failing credential-store tests for encrypted persistence across instances, replacement, removal, corrupt ciphertext, and unavailable Keystore key.
- [x] Implement AES/GCM storage using a dedicated non-exportable Android Keystore key; delete unusable ciphertext and surface “re-entry required” without plaintext recovery.
- [x] Run focused unit tests; verify saved preference values are ciphertext and no credential appears in parser output.

## Task 2: Android provider lifecycle and Models UI

**Files:**
- Modify: `android/app/src/main/java/com/nv/pair/runtime/PairRuntimeService.kt`
- Modify: `android/app/src/main/java/com/nv/pair/runtime/PairRuntimeController.kt` if state/actions need exposure
- Modify: `android/app/src/main/java/com/nv/pair/MainActivity.kt` only for the minimal state/action wiring
- Modify/create: `android/app/src/main/java/com/nv/pair/models/` provider UI components
- Test: provider management Compose instrumentation tests and runtime/RPC unit tests

**Interfaces:**
- Consumes: Task 1 provider DTO, credential store, and typed broker operations.
- Produces: UI actions to save provider metadata, set/replace/remove its credential, test the provider, and refresh secret-free provider state.

- [ ] Add failing runtime tests for save→credential set, credential replacement, provider deletion pruning, and runtime restart restoration order.
- [ ] Add failing Compose tests for empty/list/editor/loading/test-success/test-failure states, masked key input, and no secret rendered after save.
- [ ] Run focused tests and confirm failures.
- [x] Implement lifecycle orchestration: persist metadata, keep credential local, set it through broker RPC, restore only after broker/proxy readiness, and prune removed/rotated auth references.
- [x] Add OpenAI-compatible provider editor to Models > Cloud resources, with HTTPS URL and required-field validation, model IDs/capabilities, enabled state, masked credential entry, test action, and accessible result/error labels.
- [x] Verify RPC saves never include a credential and the UI never reads one back; run focused Android unit and instrumentation tests.

## Task 3: Audit and close Gateway tool protocol gaps

**Files:**
- Inspect/modify: `services/nvpair-proxy/gateway_dispatcher.go`, related capability selector files, and `services/nvpair-proxy/gateway_test.go` / `cloud_client_test.go`
- Modify broker/service-contract surfaces only if a required missing RPC is confirmed.
- Update applicable Go README/spec and generated desktop service API through the contract command when a contract changes.

**Interfaces:**
- Consumes: existing Gateway capability routing and cloud request forwarding.
- Produces: a documented matrix of every PAIR OpenAI-compatible entry path, with tested pass-through or explicit unsupported behavior.

- [x] Add/identify tests for request `tools`, non-streaming assistant `tool_calls`, follow-up `role: tool` with `tool_call_id`, and SSE chunked tool arguments/finish reason.
- [x] Add/identify tests where no target advertises tools and assert a single 422 capability response with no upstream request or retry loop.
- [x] Run focused Go tests from `services/nvpair-proxy` and record which required cases already pass.
- [x] Implement only missing pass-through/error behavior; do not parse and reconstruct valid provider bodies unnecessarily.
- [x] Run the proxy package tests; no RPC contract changed, so generated desktop service contracts were not touched.
- [x] Document invocation paths and their capability outcomes; preserve existing remote-host credential ownership.

## Task 4: Determine MNN structured tool support before changing JNI

**Files:**
- Inspect: `android/app/src/main/cpp/CMakeLists.txt`, `NativeMnn.cpp`, Android MNN dependency/API headers, and shipped test model/runtime fixtures.
- Create/modify: a focused native integration probe or instrumentation test only where needed to establish runtime capability.

**Interfaces:**
- Consumes: current MNN generation interface (role/content arrays and token callback).
- Produces: a go/no-go finding for structured tool-call generation and streaming with the bundled MNN version and target model; exact native API signature for Task 5 if supported.

- [x] Inspect whether the bundled MNN chat-template/runtime API accepts tool schemas and returns structured tool calls without relying on free-form text parsing; current source surface does not.
- [x] A compatible on-device model fixture was unavailable; source inspection of Kotlin/JNI/native integration established no structured tool API. Record this evidence and limitation instead of inferring runtime support.
- [x] Keep MNN `tools` capability false and test the one-shot explicit rejection in Task 6; skip Task 5.
- [ ] If support is real, specify the concrete native request/result representation and proceed to Task 5. Do not infer support from model naming or parser acceptance.

## Task 5: MNN structured tool request and response support (conditional)

**Files:**
- Modify: `android/app/src/main/java/com/nv/pair/mnn/MnnModels.kt`, `NativeMnn.kt`, parser/server and OpenAI response/SSE implementation under `mnn/http/`
- Modify: `android/app/src/main/cpp/NativeMnn.cpp` and CMake/native glue only as required by Task 4's verified API
- Test: MNN parser/response JVM tests, HTTP server tests, and device instrumentation tests with the compatible model

**Interfaces:**
- Consumes: Task 4's verified runtime API and Task 3's OpenAI wire behavior.
- Produces: structured tool-call request/response types; message representation supports assistant calls and tool result IDs; streaming emits correct OpenAI tool-call deltas.

- [ ] Write failing tests for tool schema parsing, assistant tool call plus tool-result round trip, malformed IDs/arguments, non-streaming `finish_reason: tool_calls`, and chunked SSE arguments.
- [ ] Run focused parser/HTTP tests and confirm failures.
- [ ] Extend Kotlin request/result types and JNI boundary with explicit structured tool fields; keep caller-side execution outside PAIR.
- [ ] Integrate only the verified MNN API; do not derive calls by guessing from arbitrary generated prose.
- [ ] Run focused JVM tests and instrumented device tests proving one structured tool call and a caller-result follow-up with streaming and non-streaming modes.
- [ ] Advertise `tools` for MNN only when the loaded runtime/model proves that capability; otherwise use Task 6 behavior.

## Task 6: MNN capability reporting and bounded rejection

**Files:**
- Modify: Android MNN OpenAI capability/model listing and `mnn/http/OpenAiRequestParser.kt` / server error mapping
- Test: parser, endpoint, and capability listing tests

**Interfaces:**
- Consumes: Task 4 go/no-go; Task 5 structured support when available.
- Produces: consistent advertised capability and one OpenAI-style error for unsupported tools.

- [x] Add tests that unsupported MNN returns one documented 400 for `tools` and `tool_choice`, while ordinary chat still works; parser coverage plus endpoint test checks rejection.
- [x] Run focused tests and confirm the new parser case failed before implementation.
- [x] Implement capability-aware rejection without dropping fields; endpoint handles once and does not retry.
- [x] Run Android unit tests for the unsupported runtime outcome available in the build.

## Task 7: Documentation, full verification, and release handoff

**Files:**
- Modify: `docs/ComputeHarbor_Android_UI_Kit/ANDROID_UI_IMPLEMENTATION_PLAN.md`, service/Android docs touched by changed behavior, and tests only where gaps remain.
- Generated: `desktop/docs/services-api.md` only via `npm run service-contracts:write` if RPC contracts changed; never hand-edit.

- [x] Update the Android implementation plan and provider/tool capability documentation to reflect implemented behavior and MNN limitations.
- [x] Run focused Android unit tests, Compose instrumentation tests, Keystore instrumentation tests, and focused Go tests. No RPC contracts changed. The Android full suite still has two unrelated failures in pre-existing dirty MNN lifecycle tests.
- [x] Run `git diff --check` and inspect changed provider code for secret serialization/logging. The repository-wide SPDX scan reports pre-existing omissions; the new plan doc now has its SPDX header.
- [ ] Provide release-intent details for changed service binaries in the PR description; do not hand-edit `services/versions.json` or `CHANGELOG.md`.
- [ ] Do not commit until the user chooses the commit workflow; if committing, use `git commit -s` and a focused commit per independently reviewable subsystem.

