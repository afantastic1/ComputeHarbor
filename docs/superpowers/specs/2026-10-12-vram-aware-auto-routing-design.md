<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# VRAM-aware automatic routing

## Goal

Automatic routing must treat a compatible local node as saturated when either
its GPU compute utilization or its VRAM utilization reaches the existing high
pressure band. With the `prefer_local` policy, enabled Cloud routing, an enabled
paid fallback, and a configured fallback model, `auto` then routes to Cloud when
every compatible local candidate is saturated.

The current behavior considers only GPU compute utilization. A model can occupy
nearly all VRAM while GPU compute utilization is temporarily low, so the local
capacity classifier incorrectly keeps sending new requests to that node.

## Decisions

- VRAM utilization is a separate signal. Existing GPU utilization fields keep
  their current meaning.
- A node's VRAM utilization is the highest `used / total` percentage among its
  GPUs with valid memory telemetry.
- GPU and VRAM use the existing pressure bands: 0 below 40%, 1 from 40% through
  69%, 2 from 70% through 84%, and 3 at 85% or above.
- Both signals use the existing 0.35 EWMA, pressure hysteresis, and ten-second
  freshness window.
- The effective resource pressure used for ranking and model scoring is the
  maximum known GPU or VRAM pressure. A missing signal remains unknown and does
  not erase a known signal.
- A candidate is saturated when either fresh, known signal has pressure 3. If
  neither signal proves saturation and at least one is unknown, capacity is
  unknown. A candidate is available only when every applicable signal is known
  and below pressure 3.
- Cloud routing still requires the existing policy, Cloud enablement, paid
  fallback consent, fallback model, provider, credentials, and budget checks.
- No renderer routing logic or new direct HTTP polling is added.

## Data flow

### Node information

`nvpair-node-info` already reports each GPU's total and used VRAM. It will also
report whether the used value came from a valid dynamic sample. This prevents a
missing sample, which currently decodes as zero, from being mistaken for known
idle VRAM.

For discrete GPUs, a matching dynamic GPU statistics row makes VRAM usage
known. For unified-memory devices, the existing system-memory snapshot makes
the value known when total and used memory are available.

### Compact discovery telemetry

`nvpair-node-scanner` and the manual-node projection calculate the highest
known per-GPU VRAM percentage. `noderec.NodeTelemetry` gains:

- `vramUtilizationPercent`
- `vramUtilizationKnown`

The existing `telemetryValid` and `msSince` fields remain the freshness source
for both GPU and VRAM observations because both values come from the same
node-info snapshot.

The broker's source-aware telemetry cache continues to relay the complete
`NodeTelemetry` value without interpreting it.

### Scheduler output

`nvpair-job-scheduler` maintains independent EWMA and pressure state for GPU
and VRAM. `schedulerwire.NodeRank` gains:

- `vramPressure`
- `vramPressureKnown`

The scheduler orders nodes by `pending + effectivePressure`, then effective
pressure, then stable node ID, where `effectivePressure` is the maximum known
GPU or VRAM pressure. A change to either pressure or freshness flag emits a new
priority snapshot.

### Proxy and Gateway

`nvpair-proxy` stores both pressure signals from `node/set-priority`. Candidate
selection and process-wide reservation scoring use their maximum known pressure.

The Gateway local-capacity classifier examines each compatible candidate. A
candidate is busy if GPU pressure or VRAM pressure is known at the maximum band.
It routes to the configured Cloud fallback only when every compatible candidate
is busy and all existing Cloud fallback gates pass.

## Invalid and missing data

- A GPU with zero total memory is excluded from the VRAM maximum.
- A GPU whose used-memory value is not backed by a dynamic sample is excluded.
- Used memory greater than total memory is clamped to 100%.
- An invalid or stale node telemetry sample makes both signals unknown.
- If one signal is known saturated, the candidate is saturated even when the
  other signal is unknown.
- If one signal is known below saturation and the other is unknown, the
  candidate remains capacity-unknown and stays local under `prefer_local`.

## Components and documentation

The implementation changes these contract participants:

- `nvpair-node-info`: memory sample validity
- `shared/noderec`: compact VRAM telemetry fields
- `nvpair-node-scanner`: maximum VRAM utilization calculation
- `nvpair-ui-broker`: manual-node projection and transparent relay tests
- `nvpair-job-scheduler`: VRAM EWMA, pressure, ranking, and output fields
- `shared/schedulerwire`: VRAM pressure fields
- `shared/modelselection`: effective resource pressure scoring
- `nvpair-proxy`: priority storage, candidate projection, and Cloud fallback
  classification

The corresponding README and service specification files must describe the new
wire fields and routing behavior. The generated desktop service API is updated
through `npm run service-contracts:write`, never by hand.

## Verification

Tests must prove:

- scanner telemetry reports the highest valid VRAM percentage and ignores
  unavailable memory samples;
- broker cache and manual-node projection preserve VRAM value and validity;
- scheduler maps 85% VRAM to pressure 3, applies freshness and hysteresis, and
  ranks by the maximum of GPU and VRAM pressure;
- shared wire JSON includes the new pressure fields;
- proxy snapshots store and clamp VRAM pressure;
- model selection uses effective resource pressure;
- `prefer_local` selects Cloud when VRAM alone saturates every compatible local
  candidate and keeps local routing when any compatible candidate is available
  or capacity is unknown;
- focused Go module tests, cross-process contract checks, generated service
  contract checks, and `git diff --check` pass.

