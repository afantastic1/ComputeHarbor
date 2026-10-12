<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0

This README has been substantially rewritten for the independent ComputeHarbor project.
See "Upstream, attribution, and license" below.
-->

# ComputeHarbor

**One API for your local AI compute, distributed devices, and optional cloud models.**

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Stage: Early development](https://img.shields.io/badge/Stage-Early%20development-orange.svg)](#project-status)

**English** | [简体中文](README.zh-CN.md)

ComputeHarbor is an open-source, **local-first inference router** for heterogeneous AI devices. It discovers and connects compatible machines, schedules independent inference requests across available models and nodes, and exposes an OpenAI-compatible interface for applications and AI agents. An opt-in cloud provider path can handle requests when configured routing policies permit it.

Use the GPUs and devices you already own. Keep local work local when possible, and decide explicitly when a request may use a paid cloud model.

> **Early development:** This repository has not yet published a ComputeHarbor-branded stable release. Some interfaces, configuration formats, and platform integrations remain experimental. Do not confuse this source tree with NVIDIA's official releases.

## Why ComputeHarbor?

- **One local API:** point compatible tools at a single model catalog and chat-completions endpoint instead of configuring every inference engine separately.
- **Multiple devices and engines:** discover or manually add nodes; route a request to an eligible node with the requested model.
- **Workload-aware scheduling:** take model availability, current work, and available resource telemetry into account rather than blindly round-robin requests.
- **Local-first with controlled cloud access:** configure an OpenAI-compatible provider, choose a routing policy, and explicitly authorize potential paid fallback.
- **Agent-friendly routing:** use explicit model IDs or the `auto`, `auto-fast`, `auto-balanced`, and `auto-best` aliases.
- **External Strata engine:** connect to a separately running [Strata](https://github.com/Niko1221/Strata) instance on a PC, detect its advertised models, and include it in local routing without managing its process or weights.
- **Experimental Android node:** run an MNN-backed local model on supported ARM64 Android devices and participate in the same routing system.

ComputeHarbor is **not** a model-training framework, hosted commercial API reseller, GPU-memory pooling system, or tensor-parallel inference engine. A request is dispatched to one execution target; multiple devices do not combine their VRAM to run a single oversized model.

## Architecture

```text
AI agents / applications / OpenAI-compatible clients
                       |
                       v
           ComputeHarbor local API
          127.0.0.1:14326/v1
                       |
             Model & policy routing
                 /           \
                v             v
        Local inference    Optional cloud
        and paired nodes    API provider
         /     |     \       (opt-in,
        v      v      v      budgeted)
     Ollama  LM Studio  llama.cpp
       |             |           \
   Strata (external) |   Android MNN (experimental)
```

Each local node runs an inference engine appropriate to its hardware. The router selects among eligible **model/node deployments**. Cloud providers are separate execution targets, not virtual LAN nodes.

## Project status

The table describes code present in this repository, **not** a claim of production readiness on every device.

| Area | Status | Notes |
| --- | --- | --- |
| Windows, Linux, macOS desktop and Go services | Available in source | Electron desktop, discovery, pairing, and engine management inherited from the upstream architecture |
| Ollama, LM Studio, llama.cpp | Available in source | Local engine facades and model-aware request routing; runtime support depends on the device and engine |
| Strata (externally managed local engine) | Experimental integration | Loopback connection settings, model probe, proxy facade and routing code exist; independent live Strata and multi-node acceptance remain to be verified |
| Unified OpenAI-compatible gateway | Available in source | Local `/v1/models` and `/v1/chat/completions` on loopback |
| Automatic model selection | Available in source | `auto`, `auto-fast`, `auto-balanced`, `auto-best`; request-specific `auto-local` and `auto-cloud` |
| Configurable cloud providers and local-first fallback | Experimental | Explicit opt-in, selected fallback model, authorization and budget controls; end-to-end deployments still need validation |
| Android ARM64 + MNN | Experimental | CPU runtime, model management, LAN pairing and routing; OpenCL is optional and device-dependent |
| Production public API gateway, accounts and billing | Not included | Requires a separately secured gateway, authentication, metering, rate limits and operations |
| Cross-device model sharding / pooled VRAM | Not supported | A single inference request is not split across machines |
| Image/video generation and distributed media workflows | Planned | Not part of the current text chat API |

Some Android device acceptance tests are documented in [Android README](android/README.md) and [test reports](docs/M12_5_TEST_REPORT.md). Those results do not guarantee compatibility with other phones, models, or drivers.

## Quick start (desktop, from source)

**Prerequisites:** Git, Node.js **25.5+**, Go **1.25+**, npm and `jq` on `PATH`. Refer to the [build guide](docs/building.mdx) for platform-specific details. Some older documents and internal binary names still use `PAIR`/`nvpair` during the branding transition.

```bash
git clone https://github.com/afantastic1/ComputeHarbor.git
cd ComputeHarbor/desktop
npm install
npm start
```

`npm start` builds the Go services from the adjacent `services/` directory and starts the Electron desktop application. From the UI, start or configure a supported inference engine, make a model available, and pair trusted machines if you want to route between devices.

This is a **source development workflow**, not an officially signed ComputeHarbor installer. Do not download NVIDIA-branded installers and assume they contain ComputeHarbor's additions.

### Call the unified local API

When the runtime and gateway are running, inspect the advertised models:

```bash
curl http://127.0.0.1:14326/v1/models
```

Then send a chat request to an available explicit model ID or an automatic alias:

```bash
curl http://127.0.0.1:14326/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "auto",
    "messages": [{"role": "user", "content": "Hello from ComputeHarbor!"}],
    "stream": false
  }'
```

The response depends on an eligible, running model and on your configured policy. Authentication may be required, especially when cloud execution is enabled; use the credential configured for your gateway and add an `Authorization: Bearer ...` header where required. A placeholder key accepted by a loopback-only development endpoint is **not** public-API authentication.

Currently documented gateway operations cover **text chat and model discovery**. Do not assume arbitrary OpenAI endpoints (such as embeddings, image, audio or video APIs) are supported at this unified endpoint.

### Optional cloud providers

The desktop includes cloud provider configuration and a choice among `local_only`, `prefer_local`, and `cloud_only` policies. Configure an allowed provider, its model and credentials, a fallback model if needed, and budget/authorization limits before enabling paid requests. Automatic fallback is deliberately constrained: it is **not** a general-purpose cheapest-model optimizer or unlimited cloud failover mechanism.

Budget reservations are safeguards, **not** a substitute for provider invoices or exact per-token billing. Check provider terms before operating any paid or resale service.

## Strata (external local inference engine, experimental)

[Strata](https://github.com/Niko1221/Strata) is integrated as an **externally managed, local inference engine**, not as a cloud provider or a second ComputeHarbor physical node. This is useful when a PC runs a large model through Strata's own runtime: ComputeHarbor can treat its advertised models as candidates for **request-level** routing alongside Ollama, LM Studio and llama.cpp. It does **not** merge GPU memory across ComputeHarbor nodes or claim any specific model-size capability on your hardware.

**Current source integration:** shared engine identity, a dedicated proxy facade, Broker model discovery/advertisement, a desktop **Strata (External)** settings card, and unit tests for connection validation and routing. **Limitations:** an end-to-end test with a real Strata process and cross-device workload is still required before declaring production support. ComputeHarbor does not install, launch, update, stop or manage Strata models for you.

1. Install and run Strata **separately on the same PC** following its [upstream documentation](https://github.com/Niko1221/Strata). Keep its OpenAI-compatible service bound to local loopback.
2. Check that Strata actually exposes a compatible model inventory: `curl http://127.0.0.1:8080/v1/models` (change the port to match your Strata configuration).
3. In ComputeHarbor desktop settings, find **Strata (External)**. Enter the local endpoint (root URL or `/v1`), optionally select the expected model ID, and provide an **environment variable name** if authentication is required (never paste an API key into that field).
4. Use **Test connection**, enable the integration, and **Save Strata settings**. Then inspect ComputeHarbor's `/v1/models` list and test an explicit Strata-backed model through its unified local gateway.

A successful `GET /v1/models` probe checks connectivity and model identity; it does not prove generation, tool-calling or streaming quality. Verify your actual Strata model and driver setup before enabling automatic routing. For design/acceptance boundaries, see the [Strata integration plan](docs/PAIR_Strata_Integration_Plan%20(1).md).

## Android (experimental)

The `android/` module contains an ARM64 Android application and optional local MNN inference runtime. Model weights are **not** bundled. For the device setup, verified Model Hub limitations, Android API endpoints and acceptance commands, see [android/README.md](android/README.md).

From a configured Android build environment:

```bash
cd android
./gradlew :app:assembleDebug
```

On Windows use `gradlew.bat :app:assembleDebug`. The default MNN build targets CPU; OpenCL is an explicit, device-dependent build option. An APK build passing is not proof that native inference works on a particular device.

## Repository layout

```text
desktop/      Electron + React application
services/     Go broker, discovery, scheduler, engine managers and proxies
android/      Android UI, hosted services and MNN runtime
docs/         Architecture, build instructions, contracts and engineering notes
scripts/      Development and validation tooling
```

For development, start with [the developer guide](docs/developing.mdx), [building from source](docs/building.mdx), [backend services](services/readme.md), and [Android runtime notes](android/README.md). Some imported documents, images and internal identifiers still refer to the original upstream project; these are being updated separately.

## Roadmap

This is a direction, not a promise or shipping schedule.

- [ ] Complete ComputeHarbor branding, documentation and independent release pipeline.
- [ ] Validate cloud-provider fallback, permissions, budgets and error handling across realistic multi-node workloads.
- [ ] Expand repeatable Android MNN device coverage and acceleration compatibility.
- [ ] Complete real-Strata protocol, streamed chat and multi-node integration acceptance; validate model capabilities before broad auto-routing.
- [ ] Improve capability-aware routing, observability and successful-request cost measurements.
- [ ] Explore managed high-memory inference clusters as a single schedulable execution target.
- [ ] Add further inference backends and media workflows where appropriate.

For proposed changes and bugs, use [ComputeHarbor issues](https://github.com/afantastic1/ComputeHarbor/issues). Contributions should include relevant tests and documentation; please review the existing development rules before editing runtime contracts.

## Security and deployment

ComputeHarbor is currently **LAN/local-first** software. The unified HTTP gateway binds to **loopback**. Do **not** expose it to the public internet with port forwarding or a raw reverse proxy. A public service would require a separate hardened ingress with authentication, TLS, authorization, tenant isolation, quotas, metering, abuse controls, and operational monitoring.

Only pair trusted devices, treat model endpoints and cloud credentials as sensitive, and review outbound traffic from external providers or tools. For possible vulnerabilities, avoid public disclosure of exploit details; use GitHub private vulnerability reporting if enabled, or request a private contact channel from the maintainer. The imported [SECURITY.md](SECURITY.md) still includes NVIDIA-specific reporting language and should **not** be treated as ComputeHarbor's security response policy.

## Upstream, attribution, and license

ComputeHarbor is an **independent derivative** of [NVIDIA Personal AI Router](https://github.com/NVIDIA/Personal-AI-Router), with additional code and a separate product direction. **It is not affiliated with, sponsored by, or endorsed by NVIDIA.**

The original project is licensed under [Apache License 2.0](LICENSE). Original copyright and license notices are retained where applicable; see [third-party notices](THIRD_PARTY_NOTICES.md) for other bundled components. Inference engines, model weights, SDKs and cloud services can carry separate licenses or terms. Branding changes do not remove those obligations.

NVIDIA and NVIDIA-related names remain the property of their respective owners. References here identify the upstream work and do not imply official support.
