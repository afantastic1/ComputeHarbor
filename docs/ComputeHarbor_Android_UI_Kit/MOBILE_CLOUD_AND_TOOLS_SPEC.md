<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# Android Cloud Providers and Tool Protocol Specification

**Status:** Approved for implementation (2026-10-14)  
**Scope:** Android OpenAI-compatible cloud provider setup and caller-executed tool calls across PAIR inference entry points.

## 1. User decisions

- Android provider setup covers **OpenAI-compatible providers only**.
- PAIR uses **standard tool-protocol pass-through**. The caller (for example, Operit) executes tools; PAIR does not execute arbitrary tools or run an internal tool loop.
- Tool protocol support is required across PAIR's advertised invocation paths, including Android's local MNN OpenAI-compatible endpoint. A route/model that cannot honor the request must return a clear, bounded error instead of silently dropping fields or prompting a caller retry loop.

## 2. Goals

1. Let a user add, edit, enable, disable, test, and remove an OpenAI Chat Completions-compatible provider from the Android Models experience.
2. Let the Android-hosted PAIR Gateway use those provider models, subject to the existing routing policy and model capability checks.
3. Keep provider credentials private to the Android device when the phone is the provider execution host. Credentials are encrypted at rest with an Android Keystore key, are never returned by settings reads, and are not copied to paired nodes.
4. Preserve the OpenAI tool protocol end to end: request `tools`/`tool_choice`, assistant `tool_calls` and `finish_reason`, and follow-up assistant/tool messages with call IDs. Preserve streaming tool-call deltas as well as non-streaming responses.
5. Keep tool execution with the caller. PAIR transports protocol data and routes only to a verified tool-capable model/runtime.
6. Keep Settings diagnostics truthful to Android-hosted services; do not list desktop-only engines as phone services.

## 3. Non-goals

- Supporting non-OpenAI provider protocols or implementing OAuth/provider catalog discovery.
- Executing tools, interpreting tool names, or granting PAIR access to caller tools.
- Changing cluster pairing, mTLS trust, or provider-key sharing. Existing paired-host cloud access remains a separate supported path and its credentials stay on that host.
- Claiming that every model can generate tool calls. Capability is determined by the execution runtime/model, not by the presence of a request field or a user-edited label.

## 4. Provider management and credential lifecycle

### Android UI

Add provider management under Models > Cloud resources. Each provider form contains a display/provider ID, HTTPS base URL, API key, enabled state, and one or more model entries (public ID, upstream model ID, and verified capability selections). Include a provider connection test and actionable validation/error states. Secret input is masked; a saved key is represented only by a “configured” state and cannot be read back.

The supported wire protocol for this scope is `openai_chat_completions`. The existing model IDs and broker registry schema remain canonical. Android does not invent a parallel provider schema.

### Storage and service flow

- Persist provider metadata using the existing broker `cloudproviders:get/save` contract.
- Persist credentials in an Android-specific encrypted store backed by an AES/GCM key held by Android Keystore. Store credentials by opaque `auth_ref`; do not place secrets in ordinary preferences, provider DTOs, logs, crash reports, backups, or UI state snapshots.
- When the local broker/proxy runtime becomes ready, restore the credential to the existing credential-set operation. Keep the key in memory only for the service lifetime and prune it when the provider is removed or its auth reference changes.
- Provider tests use the existing provider-test operation and report sanitized status/model metadata. Never log request/response bodies or authorization headers.
- Android can manage only credentials for providers executing on this phone. A remote paired host continues to own and use its own credential; no key crosses the pairing link.
- If the Android Keystore key becomes unavailable (for example, after device credential reset), mark the credential as needing re-entry and do not silently enable a provider without credentials.

## 5. Tool protocol behavior

### Gateway and cloud-compatible models

For an eligible request, PAIR forwards the caller's original supported OpenAI Chat Completions fields without rewriting tool definitions or messages. It relays provider responses unchanged, including JSON tool-call objects and SSE deltas. The next caller request may include the assistant tool-call message and the caller-produced `role: "tool"` result with `tool_call_id`; PAIR forwards that round as another ordinary request.

Tool requests require an execution target whose verified capability includes `tools`. Existing capability filtering and route selection remain authoritative. If no compatible target is available, return a stable OpenAI-style unsupported-capability error (HTTP 422 at the Gateway where consistent with current behavior) with a non-secret explanation. Do not retry the same incompatible request in a loop or strip `tools`/`tool_choice`.

Streaming responses must preserve tool-call chunk ordering, IDs, function names, argument fragments, and terminal finish reason. A stream that has begun is relayed as-is; PAIR must not synthesize a second completion after partial output.

### Android direct MNN endpoint

The current Android MNN request model and JNI interface represent only system/user/assistant text and explicitly reject tool fields. The Kotlin/JNI generation API also returns generated text rather than structured tool-call objects; the current native chat-template path does not pass tool definitions. This endpoint therefore does **not** meet the requirement today. For this implementation, direct MNN remains explicitly unsupported for tools and rejects the request once with a clear error. A future native structured tool API requires separate runtime/model feasibility work; merely accepting and discarding JSON fields is prohibited.

Direct MNN's unsupported result is an explicit capability outcome for this implementation. It must not advertise universal support or create retry loops.

### Other invocation paths

Inventory all PAIR-provided OpenAI-compatible chat entry points and classify each as (a) pass-through to a tool-capable target, (b) native tool-capable implementation, or (c) clear capability rejection. Do not claim tools support for a path that drops protocol fields. Engine-native non-OpenAI endpoints are outside this OpenAI protocol guarantee unless they already translate this protocol losslessly.

## 6. Security and privacy requirements

- Android UI is a client of the broker; it does not implement routing, provider HTTP execution, budget accounting, cluster trust, or tool execution.
- Provider registry responses contain no credential material.
- Credentials are sent only to the local broker/proxy credential operation over the app's existing local IPC boundary. Provider HTTPS connections retain normal certificate validation.
- No provider secret, prompt, tool arguments, or tool result content may be written to logs or diagnostics.
- Never forward a phone-owned credential to another node. Remote provider access uses the existing paired-host authorization path.
- Provider base URLs must be validated by the service-side provider configuration and outbound network policy; Android validation is for user feedback, not a security boundary.

## 7. Contract and implementation boundaries

Prefer existing broker RPC methods (`cloudproviders:get`, `cloudproviders:save`, provider test, credential set) and existing versioned provider DTOs. Add a new contract only if a documented Android-specific lifecycle action cannot be represented by the current methods. Any RPC change must be propagated through Go producer, broker relay, Android consumer, desktop bridge where applicable, tests, and generated service contracts.

Likely implementation areas include Android Models UI/state/API, Android Keystore credential storage and runtime restoration, Android MNN request/response/JNI/native integration, the Go Gateway only where capability/error/stream behavior is missing, and the corresponding unit/instrumentation/service tests and docs. The exact split is to be set in the implementation plan after this spec is approved.

## 8. Acceptance criteria

1. A user can configure and test an OpenAI-compatible provider on Android; metadata survives app/runtime restart, while a secret never appears in a settings read or diagnostic.
2. The Android-hosted Gateway can invoke an enabled configured provider using its upstream model ID and returns a sanitized failure when configuration, credentials, provider, or capability is unavailable.
3. Cloud tool requests and responses pass through correctly in non-streaming and SSE modes, including a caller-executed follow-up tool result round.
4. Direct Android MNN either passes a proven structured tool-call exchange in both modes, or returns one clear unsupported-capability error and reports the capability accurately. It never silently strips fields or enters a PAIR-generated tool loop.
5. Every advertised PAIR OpenAI-compatible invocation path has a documented and tested tool capability outcome.
6. Paired-node behavior continues to keep provider credentials on the execution host.
7. UI states are accessible, support loading/empty/error states, and show only engines/services actually present on Android.

## 9. Open implementation decision

The current MNN JNI/native generation surface does not expose a structured tool-call API. The implementation plan must first determine whether the bundled MNN runtime and target models can support constrained tool-call generation and structured streaming. If not, the user-facing contract must say that direct MNN is unsupported for tools while Gateway cloud/remote routes remain available. A blanket statement that “all models support tools” is not technically achievable through protocol forwarding alone.

