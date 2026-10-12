<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# Automatic local and Cloud routing under load

## Goal

Automatic routing should use any compatible local deployment that has no
inference work in progress. Cloud fallback is considered only when every
compatible local deployment already has pending or in-flight inference work.
VRAM is useful telemetry, but it must not decide node order or whether a local
deployment is busy: loaded model weights naturally consume VRAM.

## Decisions

- Pending work is the scheduler's node-wide `queued` or `running` workload count.
- Process-wide proxy reservations count dispatches not yet reflected in the
  scheduler snapshot as in-flight work.
- Cloud fallback is eligible only when every compatible, routable local
  deployment has pending work or an in-flight reservation. A zero or unknown
  pending count keeps routing local.
- GPU and VRAM utilization do not determine Cloud fallback eligibility.
- Node sorting uses pending work, GPU pressure, and local reservations. VRAM
  pressure is independently measured and reported, but does not affect ranking.
- Cloud fallback continues to require the configured `prefer_local` policy,
  enabled Cloud routing, paid fallback consent, a configured model, valid
  credentials, and available budget.
- Renderer routing logic and direct renderer polling are out of scope.

## Data and scheduling

Node telemetry continues to carry VRAM utilization and validity. The scheduler
continues to smooth GPU and VRAM signals independently and includes both
pressures and freshness in `schedule:priority`. Ordering uses
`pending + gpuPressure`, then GPU pressure, then stable node ID. The proxy's
concurrent dispatch score uses `pending + gpuPressure + localReservations`.

The Gateway's compatible candidate set is the deployment set used for the Cloud
decision. Any candidate with a known positive pending count or an active local
reservation is busy. Cloud is considered only when all candidates are busy.
Unknown work does not prove that a deployment is busy.

## Verification

Tests should prove that:

- high VRAM pressure alone does not reorder nodes or dispatch targets;
- GPU pressure, pending workloads, and in-flight reservations still affect
  ordering as specified;
- VRAM pressure remains present in telemetry snapshots;
- Cloud fallback is selected only when every compatible deployment has pending
  or in-flight inference work;
- any idle or unknown-work compatible deployment keeps routing local.
