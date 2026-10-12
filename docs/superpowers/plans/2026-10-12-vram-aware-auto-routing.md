<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# VRAM-aware Auto Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route `auto` to the configured Cloud fallback when every compatible local candidate has fresh GPU compute or VRAM pressure at the 85% saturation band.

**Architecture:** Carry independently valid VRAM utilization from node-info through compact discovery telemetry, smooth it in the scheduler, and publish it beside GPU pressure. The proxy uses the maximum known pressure for local ranking and model scoring while retaining both freshness flags for the Gateway's conservative saturation classification.

**Tech Stack:** Go 1.25, newline-delimited JSON-RPC 2.0, HTTP node-info telemetry, Electron service contract generator

**Spec:** `docs/superpowers/specs/2026-10-12-vram-aware-auto-routing-design.md`

## Global Constraints

- Preserve the meaning of existing GPU utilization and `gpuPressure` fields.
- Use 85% as the saturation threshold, the existing 0.35 EWMA, current hysteresis bands, and the ten-second freshness window.
- Keep routing and telemetry interpretation in Go services; add no renderer policy.
- Missing or stale signals remain unknown and cannot alone trigger paid Cloud fallback.
- Keep every changed file's two-line SPDX header and do not edit `services/versions.json`, `CHANGELOG.md`, or generated `desktop/docs/services-api.md` by hand.
- Preserve unrelated working-tree changes and stage exact files only when committing.

## Review Focus

- A GPU with a known total but no dynamic used-memory sample must remain VRAM-unknown; cover in node-info and scanner tests in Task 1.
- Used VRAM greater than total must clamp to 100 without integer overflow; cover in scanner tests in Task 1.
- A fresh saturated signal must win when the other signal is unknown, while a low known signal plus an unknown peer signal remains capacity-unknown; cover in Gateway table tests in Task 4.
- Telemetry becoming stale must make both pressure freshness flags false and emit a changed snapshot even when the effective neutral pressure remains 1; cover in scheduler tests in Task 3.
- Multiple compatible local candidates must use Cloud only when every candidate is saturated by at least one fresh signal; cover in Gateway table tests in Task 4.

---

### Task 1: Produce valid compact VRAM telemetry

**Files:**
- Modify: `services/nvpair-node-info/main.go`
- Modify: `services/nvpair-node-info/stats_test.go`
- Modify: `services/shared/noderec/noderec.go`
- Modify: `services/nvpair-node-scanner/daemon.go`
- Modify: `services/nvpair-node-scanner/telemetry_test.go`

**Interfaces:**
- Produces: `GPUInfo.VramUsedValid bool` as JSON `vram_used_valid` from node-info and shared discovery shapes.
- Produces: `noderec.NodeTelemetry.VRAMUtilizationPct uint32` as JSON `vramUtilizationPercent`.
- Produces: `noderec.NodeTelemetry.VRAMUtilizationKnown bool` as JSON `vramUtilizationKnown`.
- Produces: `noderec.MaxVRAMUtilization(gpus []GPUInfo) (uint32, bool)` for scanner and manual-node projections.

- [ ] **Step 1: Write failing node-info tests**

Add focused cases to `stats_test.go` proving a matched dynamic GPU row sets `vram_used_valid`, an unmatched GPU leaves it false, unified-memory reporting sets it when memory totals are available, and valid zero used memory remains distinguishable from a missing sample.

- [ ] **Step 2: Run the node-info tests and verify RED**

Run: `go test ./... -run 'TestBuildResponse|Test.*VRAM' -count=1` from `services/nvpair-node-info`.

Expected: FAIL because `GPUInfo.VramUsedValid` and its JSON field do not exist.

- [ ] **Step 3: Implement node-info sample validity**

Add `VramUsedValid bool \`json:"vram_used_valid,omitempty"\`` to node-info `GPUInfo`. Set it only when response assembly has a valid dynamic VRAM source: a matching GPU stats row for a discrete GPU, or the existing valid system-memory snapshot for a unified-memory GPU with a known total.

- [ ] **Step 4: Run node-info tests and verify GREEN**

Run: `go test ./... -count=1` from `services/nvpair-node-info`.

Expected: PASS.

- [ ] **Step 5: Write failing scanner telemetry tests**

Add shared helper tests and extend `telemetry_test.go` with cases asserting the highest valid per-GPU ratio is emitted, invalid GPU memory samples are ignored, zero totals are ignored, and `used > total` emits 100%. Assert both the value and `VRAMUtilizationKnown`.

- [ ] **Step 6: Run scanner tests and verify RED**

Run: `go test ./... -run 'TestRefresh.*Telemetry' -count=1` from `services/nvpair-node-scanner`.

Expected: FAIL because the shared GPU validity and compact VRAM telemetry fields do not exist.

- [ ] **Step 7: Implement compact VRAM telemetry**

Add the three shared fields in `noderec`, decode `vram_used_valid` through the scanner's existing `noderec.GPUInfo` alias, and implement `noderec.MaxVRAMUtilization` with clamped floating-point division so `used * 100` cannot overflow. Populate the new compact fields beside `GPUUtilizationPct`.

- [ ] **Step 8: Run shared and scanner tests**

Run: `go test ./... -count=1` from `services/shared`, then `services/nvpair-node-scanner`.

Expected: both PASS.

- [ ] **Step 9: Commit the telemetry producer change**

Stage only the Task 1 files and create a signed commit with message `feat: report VRAM routing telemetry`.

### Task 2: Preserve VRAM telemetry through manual nodes and the broker

**Files:**
- Modify: `services/nvpair-manual-nodes/manager.go`
- Modify: `services/nvpair-manual-nodes/manager_test.go`
- Modify: `services/nvpair-ui-broker/manualnodes.go`
- Modify: `services/nvpair-ui-broker/manualnodes_test.go`
- Modify: `services/nvpair-ui-broker/telemetry_test.go`
- Modify: `services/nvpair-ui-broker/discovery_test.go`

**Interfaces:**
- Consumes: `GPUInfo.VramUsedValid`, `noderec.NodeTelemetry.VRAMUtilizationPct`, and `VRAMUtilizationKnown` from Task 1.
- Produces: manual-node status and broker telemetry that preserve those fields unchanged.

- [ ] **Step 1: Write failing manual-node and broker tests**

Assert the manual-node worker decodes and detects changes to `vram_used_valid`; `manualNodeTelemetry` calculates the same maximum as the scanner; broker cache aging, source precedence, replay, and scanner notification handling preserve the VRAM fields.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `go test ./... -run 'Test.*(GPU|Telemetry|Manual)' -count=1` from `services/nvpair-manual-nodes` and `services/nvpair-ui-broker`.

Expected: FAIL because the manual status shape and projection do not carry VRAM validity or utilization.

- [ ] **Step 3: Implement the manual and broker path**

Add the validity field to the manual-node GPU shape and equality check. Call `noderec.MaxVRAMUtilization` from `manualNodeTelemetry`; leave the broker cache and relay transparent apart from preserving the expanded value.

- [ ] **Step 4: Run module tests and verify GREEN**

Run: `go test ./... -count=1` from `services/nvpair-manual-nodes` and `services/nvpair-ui-broker`.

Expected: both PASS.

- [ ] **Step 5: Commit the manual telemetry propagation**

Stage only the Task 2 files and create a signed commit with message `feat: relay manual-node VRAM telemetry`.

### Task 3: Smooth and publish VRAM pressure

**Files:**
- Modify: `services/nvpair-job-scheduler/manager.go`
- Modify: `services/nvpair-job-scheduler/telemetry.go`
- Modify: `services/nvpair-job-scheduler/telemetry_test.go`
- Modify: `services/nvpair-job-scheduler/schedule.go`
- Modify: `services/nvpair-job-scheduler/schedule_test.go`
- Modify: `services/shared/schedulerwire/schedulerwire.go`
- Modify: `services/shared/schedulerwire/schedulerwire_test.go`
- Modify: `services/nvpair-ui-broker/scheduler_test.go`
- Modify: `services/tests/scheduler_interop_test.go`

**Interfaces:**
- Consumes: expanded `noderec.NodeTelemetry` from Task 1.
- Produces: `schedulerwire.NodeRank.VRAMPressure int` as JSON `vramPressure` and `VRAMPressureKnown bool` as JSON `vramPressureKnown`.
- Produces: `effectiveResourcePressure(gpuPressure, gpuKnown, vramPressure, vramKnown) int`, returning neutral pressure 1 only when both signals are unknown.

- [ ] **Step 1: Write failing scheduler, wire, broker, and cross-process tests**

Add scheduler tests for first-sample 85% VRAM pressure 3, EWMA and downward hysteresis, invalid/stale freshness, GPU/VRAM independence, effective-pressure maximum, ranking, and emissions caused by either VRAM pressure or freshness changes. Add shared-wire and broker relay assertions that JSON round trips, priority caching, and forwarding retain `vramPressure` and `vramPressureKnown`. Extend `scheduler_interop_test.go` with fresh VRAM-only saturation, unknown VRAM, and stale VRAM cases for both engine notifications.

- [ ] **Step 2: Run focused tests and verify RED**

Run `go test ./... -run 'Test.*(Telemetry|Pressure|Rank|Emit|Priority|Scheduler)' -count=1` from `services/shared`, `services/nvpair-job-scheduler`, and `services/nvpair-ui-broker`. Run the focused scheduler telemetry test from `services/tests` against the current staged binaries.

Expected: FAIL because scheduler state, wire ranks, broker relay assertions, and current binaries contain only GPU pressure.

- [ ] **Step 3: Implement pressure state, wire fields, and effective ranking**

Refactor the pressure calculation only as needed to apply the same EWMA, bands, hysteresis, and freshness rules to GPU and VRAM. Add the `schedulerwire.NodeRank` fields, preserve them through broker caching and forwarding, and sort by `pending + effectiveResourcePressure`, then effective pressure, then node ID.

- [ ] **Step 4: Run unit suites and verify GREEN**

Run `go test ./... -count=1` from `services/shared`, `services/nvpair-job-scheduler`, and `services/nvpair-ui-broker`.

Expected: all PASS.

- [ ] **Step 5: Rebuild the affected scheduler binary**

Use the repository's Windows service build path to refresh `services/build/bin/nvpair-job-scheduler.exe` and any broker binary required by `scheduler_interop_test.go`.

- [ ] **Step 6: Run the cross-process test and verify GREEN**

Run: `go test ./... -run 'TestScheduler.*Telemetry' -count=1` from `services/tests`.

Expected: PASS with the expanded real-process JSON-RPC path.

- [ ] **Step 7: Commit the scheduler contract change**

Stage only the Task 3 files and create a signed commit with message `feat: schedule with VRAM pressure`.

### Task 4: Apply VRAM pressure to proxy selection and Cloud fallback

**Files:**
- Modify: `services/shared/modelselection/selector.go`
- Modify: `services/shared/modelselection/selector_test.go`
- Modify: `services/nvpair-proxy/proxy.go`
- Modify: `services/nvpair-proxy/gateway.go`
- Modify: `services/nvpair-proxy/gateway_dispatcher.go`
- Modify: `services/nvpair-proxy/gateway_test.go`
- Modify: `services/nvpair-proxy/priority_test.go`
- Modify: `services/nvpair-proxy/reservation_test.go`

**Interfaces:**
- Consumes: expanded `schedulerwire.NodeRank` from Task 3.
- Produces: `modelselection.RuntimeModel.VRAMPressure` and `VRAMPressureKnown`.
- Produces: proxy priority snapshots containing independent GPU and VRAM pressure plus `effectiveResourcePressure` for selection and reservations.

- [ ] **Step 1: Write failing model-selection tests**

Add cases proving VRAM pressure influences auto model scoring through the maximum known resource pressure, GPU behavior remains unchanged, and both unknown signals retain the existing neutral score.

- [ ] **Step 2: Run shared tests and verify RED**

Run: `go test ./... -run 'TestAuto.*Pressure|TestUnknownSignals' -count=1` from `services/shared`.

Expected: FAIL because `RuntimeModel` lacks VRAM pressure.

- [ ] **Step 3: Implement effective model-selection pressure**

Add the VRAM fields and replace the score's GPU-only input with the maximum known GPU/VRAM pressure. Do not use the existing memory-capacity eligibility signal as a substitute.

- [ ] **Step 4: Write failing proxy state and reservation tests**

Assert `node/set-priority` clamps, stores, snapshots, and applies VRAM pressure; reservation load uses maximum resource pressure rather than adding GPU and VRAM pressure together; Gateway inventory exposes both values.

- [ ] **Step 5: Run proxy focused tests and verify RED**

Run: `go test ./... -run 'Test.*(Priority|Reservation|GatewayInventory)' -count=1` from `services/nvpair-proxy`.

Expected: FAIL because proxy priority state is GPU-only.

- [ ] **Step 6: Implement proxy pressure propagation**

Add the VRAM maps and snapshot fields, clamp them to `schedulerwire.MaxGPUPressure`, use maximum effective pressure in reservation load, and project both signals into Gateway runtime candidates.

- [ ] **Step 7: Write failing Gateway capacity cases**

Extend `TestPreferLocalAutoUsesCloudOnlyForNoCapacityOrEntirelyBusyLocalInventory` to cover VRAM-only saturation, saturated GPU with unknown VRAM, saturated VRAM with unknown GPU, low GPU with unknown VRAM, and a mixed inventory containing one available candidate.

- [ ] **Step 8: Run Gateway tests and verify RED**

Run: `go test ./... -run 'TestPreferLocalAuto' -count=1` from `services/nvpair-proxy`.

Expected: the VRAM-only saturation cases remain local before implementation.

- [ ] **Step 9: Implement conservative dual-signal classification**

For each compatible candidate, mark it saturated when either known signal is at maximum; available only when both applicable signals are known below maximum; otherwise unknown. Preserve the existing rule that Cloud fallback requires every compatible candidate to be saturated.

- [ ] **Step 10: Run full shared and proxy suites**

Run: `go test ./... -count=1` from `services/shared` and `services/nvpair-proxy`.

Expected: both PASS.

- [ ] **Step 11: Commit the proxy routing change**

Stage only the Task 4 files and create a signed commit with message `feat: route auto requests using VRAM pressure`. If a listed file already contains unrelated uncommitted work, stage only this task's hunks and verify the staged diff before committing.

### Task 5: Update contracts, documentation, and verify the complete change

**Files:**
- Modify: `services/nvpair-node-info/README.md`
- Modify: `services/nvpair-node-scanner/README.md`
- Modify: `services/nvpair-job-scheduler/README.md`
- Modify: `services/nvpair-job-scheduler/spec.md`
- Modify: `services/nvpair-ui-broker/README.md`
- Modify: `services/nvpair-proxy/README.md`
- Modify: `services/nvpair-proxy/spec.md`
- Modify: `.cursor/rules/proxy-inference-routing.mdc`
- Modify: `desktop/docs/service-contract-exceptions.json`
- Modify: `desktop/docs/architecture.md`
- Modify: `desktop/docs/services-backend.md`
- Modify: `desktop/docs/services-parity.md`
- Generate if changed: `desktop/docs/services-api.md`

**Interfaces:**
- Documents: `vram_used_valid`, compact VRAM utilization, scheduler VRAM pressure, effective pressure ranking, and Gateway fallback behavior.

- [ ] **Step 1: Update hand-written service and architecture documentation**

Describe the exact field names, validity rules, pressure thresholds, ranking formula, and Cloud fallback conditions. Preserve unrelated existing edits in proxy documentation.

- [ ] **Step 2: Run service contract generation and inspect output**

Run: `npm run service-contracts:write` from `desktop`.

Expected: generated output matches the current Go RPC surface; retain `services-api.md` changes only if the generator produces them.

- [ ] **Step 3: Build the modular binaries**

Run: `npm run build:modular-binaries -- --force` from `desktop`.

Expected: all changed Go services compile and staged binaries are refreshed.

- [ ] **Step 4: Run cross-process tests**

Run: `go test ./... -count=1` from `services/tests`.

Expected: PASS; report any intentional skip as untested.

- [ ] **Step 5: Run every affected module suite**

Run `go test ./... -count=1` from `services/shared`, `services/nvpair-node-info`, `services/nvpair-node-scanner`, `services/nvpair-manual-nodes`, `services/nvpair-ui-broker`, `services/nvpair-job-scheduler`, and `services/nvpair-proxy`.

Expected: all PASS.

- [ ] **Step 6: Run repository contract and formatting checks**

Run from `desktop`: `npm run service-contracts:check`.

Run from repository root: `node scripts/spdx-headers.mjs` and `git diff --check`.

Expected: all commands exit 0.

- [ ] **Step 7: Review the final diff**

Confirm the change contains no prompt or response logging, no renderer routing policy, no manual edits to version or changelog files, and no accidental inclusion or loss of pre-existing working-tree changes.

- [ ] **Step 8: Commit documentation updates**

Stage only documentation and generated artifacts changed by Task 5, inspect the staged diff, and create a signed commit with message `docs: describe VRAM-aware routing`.

