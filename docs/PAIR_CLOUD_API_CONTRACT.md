<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# PAIR Cloud API Contract

This contract defines the first cloud-provider integration for PAIR. It extends
the existing local OpenAI-compatible Gateway at `127.0.0.1:14326`; it does not
add a service, local engine, or second inference entry point.

## Scope

The first release supports `GET /v1/models` and
`POST /v1/chat/completions` for configured text chat models. A configured model
may support streaming SSE, tools, JSON object output, or JSON Schema output only
when its declared capabilities say so. Embeddings, image generation, video, and
asynchronous media jobs are separate future APIs. Unsupported endpoints return
404 or a typed unsupported response; they are never forwarded as chat.

The Gateway keeps the existing local facade execution path. Cloud calls use a
separate OpenAI-compatible HTTPS transport and are never sent through local
node discovery, scheduler reservations, or local inference retries.

## Model identity and routing

The public model ID is stable and unique in the Gateway catalog. Suggested
forms are `local/<engine>/<model-id>` and
`cloud/<provider>/<configured-model-id>`, alongside `auto`, `auto-fast`,
`auto-balanced`, and `auto-best`. Existing bare local IDs remain transitional
aliases only while they resolve unambiguously. Duplicate public IDs are invalid;
duplicate upstream IDs across providers are allowed.

Only the internal execution target contains the provider ID and upstream model
ID. Responses and `/v1/models` expose public IDs, never credentials, credential
references, provider URLs, node addresses, or internal routing identifiers.
Model capability declarations are explicit; they are not inferred from names.
Explicit model selection and `auto*` selection apply the same capability,
authorization, enabled-state, and budget checks.

The initial routing policy is `local_only`. Cloud fallback requires an explicit
user setting and authorization. Cloud failures are returned as errors and never
silently trigger another paid target. The current local meanings of `auto*`
are not redefined as price policies.

## Provider configuration

Provider configuration is versioned JSON and contains no secret material:

```json
{
  "schema_version": 1,
  "providers": [{
    "id": "deepseek-primary",
    "protocol": "openai_chat_completions",
    "base_url": "https://api.deepseek.com",
    "auth_ref": "user-vault:deepseek-primary",
    "enabled": true,
    "models": [{
      "public_id": "cloud/deepseek/deepseek-chat",
      "upstream_id": "deepseek-chat",
      "capabilities": ["chat", "streaming", "tools", "json_object"]
    }]
  }]
}
```

`auth_ref` is an opaque reference resolved by the secret owner. API keys never
belong in ordinary configuration, model targets, logs, notifications, model
lists, workloads, or renderer state. Invalid configuration must not replace the
last valid runtime snapshot. Snapshot replacement is atomic, so an in-flight
request uses one consistent provider and model mapping for its lifetime.

The configured API base URL is normalized according to the provider protocol;
the client must not append `/v1` twice. Production endpoints require HTTPS and
must reject redirects and destinations resolving to loopback, private, link
local, or metadata addresses. Test-only loopback endpoints require an explicit
test option. Request data cannot select the URL, host, proxy, or auth headers.

## Request and transport behavior

The Gateway retains the original JSON request and rewrites only `model` to the
configured upstream ID. It preserves supported `tools`, `tool_choice`,
`response_format`, `stream_options`, and conversation tool-result fields.
Unsupported capabilities return HTTP 422 with a typed error; fields are never
silently discarded.

Cloud HTTP requests use the inbound request context, bounded concurrency, and
bounded non-streaming response/event sizes. SSE is relayed event by event.
Client disconnect cancels the upstream request and releases resources. An
upstream stream ending without its terminal event is reported as truncated; the
Gateway does not manufacture a successful completion or an extra `[DONE]`.
Successful response bodies and SSE events expose the requested public model ID,
while upstream requests receive only the configured upstream model ID.

Cloud POSTs are not automatically retried, including after 429, 5xx, timeout,
or uncertain response delivery. Provider errors are translated to static typed
messages so response bodies, secrets, and sensitive URL components are not
returned to callers.
PAIR waits up to two minutes for Provider response headers. A timeout before
the headers arrive is reported as `504 provider_timeout`.

## Credentials, authorization, and budgets

Desktop provider control uses the broker's typed `cloudproviders:get` and
`cloudproviders:save` methods for non-secret configuration, and a separate
one-shot `cloudproviders:credential:set` command for keys. Electron encrypts
keys with `safeStorage`; only process memory receives decrypted material.
`cloudproviders:test` is manual and performs only an authenticated `GET
/v1/models`, never an inference request. Saving settings never tests or calls a
provider. Provider removal prunes the runtime credential and its encrypted
vault entry.

The local Gateway listens only on loopback. Local clients do not need a second
Gateway credential in addition to the Provider key already stored by PAIR.
Enabling paid Cloud use requires explicit routing policy and budget settings;
mTLS pairing alone does not authorize a peer to spend Cloud budget. Cloud
routing defaults off. Requests rejected for disabled Cloud, a missing Provider
credential, insufficient budget, or unsupported capability make no upstream
request.

Cloud authorization requires positive monthly and per-request USD limits.
Before dispatch, PAIR durably reserves the configured per-request ceiling in
the local budget ledger. Until a model price table is configured, the ceiling
is charged conservatively because Provider usage alone does not establish a
cost; unknown cost is never recorded as zero.

Cross-node execution uses a terminal mTLS protocol. The host advertises only
public Cloud model IDs and capabilities; it never sends Provider URLs, auth
references, or keys to a caller. A paired caller must also be explicitly
authorized by UUID and its exact pinned certificate fingerprint. The caller
sends an explicit `cloud/...` model to the execution host; `auto` and local
models are not accepted on that path. The host owns the Provider call, budget
ledger, and workload, and records the caller UUID as `requesterId`. Pin removal
or re-pairing invalidates the grant, and transport failures do not trigger a
paid-request retry.

Cloud workload notifications use `kind: "cloud"`, `providerId`, and
`publicModelId`, and omit `engine` and `scheduledOn`. Aggregate token usage may
be included when returned by the provider. Prompt and response content,
credential references, and keys are never workload fields.

Cluster membership alone never transfers paid-use permission. Only the
separate certificate-bound grant enables terminal Cloud execution.

## Error mapping

| Condition | HTTP | Error type |
| --- | ---: | --- |
| Missing or invalid Gateway token | 401 | `unauthorized` |
| Cloud disabled or caller not authorized | 403 | `cloud_not_allowed` |
| Public model ID not registered | 404 | `model_not_found` |
| Requested capability unsupported | 422 | `unsupported_capability` |
| Budget or concurrency limit reached | 429 | `quota_exceeded` / `rate_limited` |
| Provider auth failure | 502 | `provider_auth_failed` |
| Provider rate limit | 429 | `provider_rate_limited` |
| Provider network failure or invalid response | 502 or 504 | `provider_error` / `provider_timeout` |

Network errors are typed and sanitized. Error messages and logs must not include
API keys, authorization headers, prompts, tool arguments, or response bodies.

## Baseline and staged delivery

Before implementation, the reviewed baseline is commit
`a6cc1a173f283f18b5bc51654d9d57966337f337` on the `feature/cloud-provider`
branch. Existing local Gateway behaviors are pinned by
`services/nvpair-proxy/gateway_test.go`; the existing cross-process model
routing coverage is in `services/tests/model_routing_interop_test.go`.
`docs/M12_5_TEST_REPORT.md` records earlier Android and desktop validation; it
is historical evidence, not a test run for this cloud change.

Cloud-only Broker lifecycle, provider registry, HTTPS/SSE behavior, auth,
budgeting, desktop configuration, Android presentation, and remote cloud
authorization are delivered in separate stages. No later-stage capability is
implied by landing the Gateway dispatcher.
