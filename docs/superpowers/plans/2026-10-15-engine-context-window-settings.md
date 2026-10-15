<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# Engine Context Window Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an authoritative default context window setting to every supported engine settings panel, with exact Ollama, llama.cpp, and LM Studio runtime mappings on local and remote nodes.

**Architecture:** Extend the shared settings wire contract and engine manifests, then keep normalization, validation, persistence, capability probing, launch translation, and model-load translation inside `nvpair-engine-manager`. Electron only validates the additive wire fields, and React edits the normalized value through the existing preview/apply/push flow.

**Tech Stack:** Go 1.25, JSON-RPC 2.0, JSON Schema, Electron, React, TypeScript, Zustand, Vitest, NVIDIA Foundations React Core

**Spec:** `docs/superpowers/specs/2026-10-15-engine-context-window-settings-design.md`

## Global Constraints

- Modes are exactly `engine-default`, `auto`, and `fixed`; only `fixed` carries an integer `tokens` value.
- Fixed values are accepted from 1024 through 262144 inclusive; PAIR never clamps, substitutes, or falls back.
- Presets are 8192, 16384, 32768, and 65536; they are UI shortcuts for the same `fixed` wire value.
- Missing persisted context normalizes to Engine default; an omitted field in an old-client request preserves the currently saved setting.
- Ollama maps Auto/fixed to `OLLAMA_CONTEXT_LENGTH=0/N`; llama.cpp maps to `--ctx-size 0/N`; LM Studio maps to `lms load MODEL --auto/--context-length N`.
- Ollama and llama.cpp use engine-launch scope and the existing restart flow. LM Studio uses model-load scope, never restarts its server for this setting, and reports resident models through `modelReloadRequired`.
- LM Studio Auto is valid only when the owning node's exact `lms load --help` output advertises `--auto`; capability failure is an error, never a fallback.
- The target node applies its own saved setting for both local and remote model loads. Vendor arguments never cross the remote API.
- Renderer and Electron code must not parse engine arguments or implement vendor mapping.
- TypeScript changes use static imports and no casts, `any`, or `unknown` signatures. New files include the two-line SPDX header.
- Do not edit `desktop/docs/services-api.md`, `services/versions.json`, or `CHANGELOG.md` by hand.
- Do not log prompts, messages, response bodies, pairing PINs, credentials, model input, or key material.
- Android MNN and `pairMnnOpenCL` remain out of scope.

## Review Focus

- An old client omits `contextWindow` while changing a port: preserve the saved setting and its effective launch/load behavior.
- A user writes the managed flag or environment key inside `launchText`, including after `--` or with Windows environment-key casing: reject it as `errors.contextWindow` and preserve unrelated literal text.
- LM Studio's help text contains similar strings such as `--automatic` or prose mentioning auto: accept only the exact `--auto` option token.
- A remote load reaches a node whose saved value differs from the controller's local value: the target node's value must be the only one translated to vendor arguments.
- Persistence, restart, or load fails after a valid preview: keep the saved selection consistent with the existing settings transaction, do not retry with another mode, and do not expose request content in errors or logs.

---

### Task 1: Define the wire and manifest contracts

**Files:**
- Modify: `services/shared/enginesettings/settings.go`
- Modify: `services/nvpair-engine-manager/registry.go`
- Modify: `services/nvpair-engine-manager/manifest.schema.json`
- Modify: `services/nvpair-engine-manager/manifests/ollama.json`
- Modify: `services/nvpair-engine-manager/manifests/lmstudio.json`
- Modify: `services/nvpair-engine-manager/manifests/llamacpp.json`
- Test: `services/nvpair-engine-manager/registry_test.go`

**Interfaces:**
- Consumes: existing `enginesettings.Config`, `Snapshot`, and `Preview`; existing `Runtime` manifest merge.
- Produces: `enginesettings.ContextWindowSetting`, `enginesettings.Capabilities`, `Runtime.ContextControl`, and `Runtime.ContextWindow` for all later service tasks.

- [ ] **Step 1: Write failing manifest and wire-shape tests**

  Add focused tests asserting that all three bundled manifests decode and validate with these exact declarations: Ollama `launch/env/OLLAMA_CONTEXT_LENGTH`, llama.cpp `launch/flag/--ctx-size`, and LM Studio `model_load/flag/--auto/--context-length` plus probe `[{cli}, load, --help]`. Assert malformed scopes, bindings with both `env` and `flag`, missing fixed bindings, and unsupported probe placeholders are rejected.

- [ ] **Step 2: Run the focused tests and verify failure**

  Run: `go test ./... -run 'TestBundledContextControls|TestRejectsInvalidContextControl'`

  Working directory: `services/nvpair-engine-manager`

  Expected: FAIL because the context contract and manifest fields do not exist.

- [ ] **Step 3: Add the shared settings types**

  In `services/shared/enginesettings/settings.go`, define:

  ```go
  type ContextWindowSetting struct {
      Mode   string `json:"mode"`
      Tokens *int   `json:"tokens"`
  }

  type Capabilities struct {
      ContextWindow bool `json:"contextWindow"`
  }
  ```

  Add `ContextWindow *ContextWindowSetting `json:"contextWindow,omitempty"`` to `Config`, `Capabilities Capabilities `json:"capabilities"`` to `Snapshot`, and `ModelReloadRequired bool `json:"modelReloadRequired"`` to `Preview`. The pointer on `Config` is required to distinguish an old request from an explicit Engine default selection.

- [ ] **Step 4: Add the manifest types and schema**

  In `registry.go`, add `Runtime.ContextControl *ContextControl` and `Runtime.ContextWindow *enginesettings.ContextWindowSetting`. Define `ContextControl` with `Scope string`, `Auto ContextBinding`, `Fixed ContextBinding`, optional `Probe []string`, and optional `ModelLimit *ContextModelLimit`. Define `ContextBinding` with mutually exclusive `Env string` or `Flag string` plus `Value *string`: environment bindings require a value; a flag with nil value is presence-only (`--auto`); a flag with a value emits a separate value argument. `ContextModelLimit` names an action plus array, model-ID, and maximum-token field paths so model metadata remains declarative. Permit `{tokens}` only in context binding values and existing trusted placeholders in probe commands. Validate `scope` as exactly `launch` or `model_load`.

- [ ] **Step 5: Declare the three canonical mappings**

  Update the bundled manifests with the exact mappings from the spec. LM Studio also declares its `list_models` result paths for model ID and maximum context when the native response exposes them. Store the user selection only in override `runtime.context_window`; bundled manifests declare `runtime.context_control` and do not set a default selection.

- [ ] **Step 6: Run the focused tests**

  Run: `go test ./... -run 'TestBundledContextControls|TestRejectsInvalidContextControl'`

  Expected: PASS.

- [ ] **Step 7: Commit the contract**

  ```powershell
  git add services/shared/enginesettings/settings.go services/nvpair-engine-manager/registry.go services/nvpair-engine-manager/manifest.schema.json services/nvpair-engine-manager/manifests services/nvpair-engine-manager/registry_test.go
  git commit -s -m "feat: declare engine context controls"
  ```

### Task 2: Normalize, validate, preview, and persist settings

**Files:**
- Create: `services/nvpair-engine-manager/contextsettings.go`
- Modify: `services/nvpair-engine-manager/settings.go`
- Modify: `services/nvpair-engine-manager/setport.go`
- Test: `services/nvpair-engine-manager/contextsettings_test.go`
- Test: `services/nvpair-engine-manager/settings_test.go`
- Test: `services/nvpair-engine-manager/setport_test.go`

**Interfaces:**
- Consumes: Task 1's `ContextWindowSetting`, `Runtime.ContextControl`, and `Runtime.ContextWindow`.
- Produces: `normalizedContextWindow`, `resolveRequestedContextWindow`, `validateContextWindow`, and `runtimeConfigUpdate`; normalized snapshots and authoritative previews.

- [ ] **Step 1: Write failing normalization and validation tests**

  Add named cases for absent persisted value, all three valid modes, exact boundaries 1024 and 262144, and preset values. Add rejection cases for unknown/empty mode, tokens on Engine default or Auto, nil fixed tokens, 1023, 262145, and fractional JSON input. Decode structured JSON in tests rather than matching serialized substrings.

- [ ] **Step 2: Write failing compatibility and persistence tests**

  Prove that an old request with `ContextWindow == nil` preserves an existing fixed value while changing ports, an explicit Engine default removes `runtime.context_window`, a fixed value survives a fresh registry/executor load, and unrelated override fields survive every update.

- [ ] **Step 3: Run focused tests and verify failure**

  Run: `go test ./... -run 'TestContextWindow|TestOldClientPreservesContextWindow|TestPersistContextWindow'`

  Working directory: `services/nvpair-engine-manager`

  Expected: FAIL because normalization and persistence do not exist.

- [ ] **Step 4: Implement the context setting helpers**

  In `contextsettings.go`, define constants `contextWindowMinTokens = 1024` and `contextWindowMaxTokens = 262144`, then implement:

  ```go
  func normalizedContextWindow(value *enginesettings.ContextWindowSetting) enginesettings.ContextWindowSetting
  func resolveRequestedContextWindow(current enginesettings.ContextWindowSetting, requested *enginesettings.ContextWindowSetting) enginesettings.ContextWindowSetting
  func validateContextWindow(value enginesettings.ContextWindowSetting) string
  ```

  Return the Engine default value as `{Mode: "engine-default", Tokens: nil}`. `resolveRequestedContextWindow` returns `current` only for nil old-client input.

- [ ] **Step 5: Extend the atomic override update**

  Replace positional `persistRuntimeConfig` arguments with:

  ```go
  type runtimeConfigUpdate struct {
      Port                int
      LaunchArgs          *[]string
      LaunchEnv           *[]string
      ContextWindow       *enginesettings.ContextWindowSetting
      SetContextWindow    bool
  }

  func (e *Executor) persistRuntimeConfig(engine string, update runtimeConfigUpdate) error
  ```

  When `SetContextWindow` is true, delete `runtime.context_window` for Engine default and write the complete normalized object otherwise. Preserve the existing atomic write, permissions, deep-merge, bundled-port cleanup, and rollback behavior.

- [ ] **Step 6: Integrate normalized snapshots and authoritative preview/apply**

  Snapshot returns `capabilities.contextWindow=true` exactly when `Runtime.ContextControl != nil`, and always returns a normalized settings value for capable engines. Preview validates under `errors.contextWindow`; configure resolves nil old-client input against the current value and repeats validation before persistence. Include the context value in change detection without yet adding vendor arguments; launch/model effects are completed in Tasks 3 and 4.

- [ ] **Step 7: Run the focused tests**

  Run: `go test ./... -run 'TestContextWindow|TestOldClientPreservesContextWindow|TestPersistContextWindow'`

  Expected: PASS.

- [ ] **Step 8: Commit settings ownership**

  ```powershell
  git add services/nvpair-engine-manager/contextsettings.go services/nvpair-engine-manager/contextsettings_test.go services/nvpair-engine-manager/settings.go services/nvpair-engine-manager/settings_test.go services/nvpair-engine-manager/setport.go services/nvpair-engine-manager/setport_test.go
  git commit -s -m "feat: persist context window settings"
  ```

### Task 3: Apply launch-scoped settings and reject raw aliases

**Files:**
- Create: `services/nvpair-engine-manager/contextlaunch.go`
- Modify: `services/nvpair-engine-manager/settings.go`
- Modify: `services/nvpair-engine-manager/lifecycle.go`
- Test: `services/nvpair-engine-manager/contextlaunch_test.go`
- Test: `services/nvpair-engine-manager/settings_test.go`

**Interfaces:**
- Consumes: Task 2's normalized setting and Task 1's launch-scoped `ContextControl`.
- Produces: `applyLaunchContextWindow` and `contextLaunchAliasError`; final restart calculation for Ollama and llama.cpp.

- [ ] **Step 1: Write failing mapping and alias tests**

  Assert exact effective launches: Ollama Engine default has no `OLLAMA_CONTEXT_LENGTH`, Auto has `OLLAMA_CONTEXT_LENGTH=0`, fixed has `OLLAMA_CONTEXT_LENGTH=32768`; llama.cpp Engine default has no `--ctx-size`, Auto has `--ctx-size 0`, fixed has `--ctx-size 32768`. Assert raw aliases are rejected even after `--`, repeated, or differently cased for an environment key, while unrelated arguments and environment assignments round trip byte-for-byte through normalized argument text.

- [ ] **Step 2: Write failing lifecycle-effect tests**

  For running engines, assert a changed launch-scoped value sets `restart=true`, an identical value does not, and Engine default removes a prior override. Assert stopped engines persist the value without requesting a restart.

- [ ] **Step 3: Run focused tests and verify failure**

  Run: `go test ./... -run 'TestLaunchContextWindow|TestContextWindowAlias|TestContextWindowRestart'`

  Working directory: `services/nvpair-engine-manager`

  Expected: FAIL because launch translation is not connected.

- [ ] **Step 4: Implement launch translation and alias detection**

  In `contextlaunch.go`, implement:

  ```go
  func applyLaunchContextWindow(rt Runtime, value enginesettings.ContextWindowSetting, command launchCommand) (launchCommand, error)
  func contextLaunchAliasError(tokens []string, control *ContextControl) string
  ```

  Apply only `scope=launch`; Engine default removes PAIR's managed entry, Auto resolves the declared binding with `0`, and fixed resolves `{tokens}`. Detect aliases across the complete token list, including tokens after `--`, before literal launch values are accepted.

- [ ] **Step 5: Integrate with preview and engine start**

  Preview uses the translated effective command for restart comparison and returns alias failures under `errors.contextWindow` with guidance to use Default context window. Engine start applies the same helper after manifest defaults and saved literal overrides, so preview and runtime cannot diverge.

- [ ] **Step 6: Run the focused tests**

  Run: `go test ./... -run 'TestLaunchContextWindow|TestContextWindowAlias|TestContextWindowRestart'`

  Expected: PASS.

- [ ] **Step 7: Commit launch mapping**

  ```powershell
  git add services/nvpair-engine-manager/contextlaunch.go services/nvpair-engine-manager/contextlaunch_test.go services/nvpair-engine-manager/settings.go services/nvpair-engine-manager/settings_test.go services/nvpair-engine-manager/lifecycle.go
  git commit -s -m "feat: apply launch context settings"
  ```

### Task 4: Apply model-load settings and probe LM Studio Auto

**Files:**
- Create: `services/nvpair-engine-manager/contextload.go`
- Modify: `services/nvpair-engine-manager/executor.go`
- Modify: `services/nvpair-engine-manager/actions.go`
- Modify: `services/nvpair-engine-manager/modelops.go`
- Modify: `services/nvpair-engine-manager/settings.go`
- Test: `services/nvpair-engine-manager/contextload_test.go`
- Create: `services/nvpair-engine-manager/modelops_test.go`
- Create: `services/nvpair-engine-manager/remote_context_test.go`

**Interfaces:**
- Consumes: Task 2's saved normalized setting and Task 1's model-load control/probe declaration.
- Produces: `modelLoadContextArgs`, `supportsContextAuto`, and `dispatchActionWithOptions`; final `modelReloadRequired` behavior.

- [ ] **Step 1: Write failing LM Studio argument and probe tests**

  Assert exact argv for Engine default, Auto, and fixed 32768. Assert Auto accepts a help line whose option token is exactly `--auto`, rejects `--automatic` and prose-only mentions, caches one probe per resolved CLI path, and returns an update-or-fixed-value error without running `lms load` when unsupported.

- [ ] **Step 2: Write failing preview and failure tests**

  Assert LM Studio context changes never request a server restart; `modelReloadRequired` is true only when the value changed and `loaded_models` returns at least one resident model. When declared model metadata reports a smaller maximum, assert load fails before the CLI call with model ID, requested size, and maximum. Assert a CLI allocation/load failure retains the saved setting, leaves the model unloaded, suggests a smaller value, does not retry without context arguments, does not rewrite the saved request from any engine-reported effective value, and does not include model input or response bodies in logs.

- [ ] **Step 3: Write the remote-authority regression test**

  Exercise both existing entry paths: local `engine:action{action:"load_model"}` and remote `/v1/models/load`, with different controller and target settings. Assert the remote payload still contains only engine/model operation fields and both paths execute the owning target's saved `--context-length` value.

- [ ] **Step 4: Run focused tests and verify failure**

  Run: `go test ./... -run 'TestLMStudioContext|TestModelReloadRequired|TestRemoteModelLoadUsesTargetContext'`

  Working directory: `services/nvpair-engine-manager`

  Expected: FAIL because model-load translation and probing do not exist.

- [ ] **Step 5: Add an internal action-options seam**

  Define:

  ```go
  type actionOptions struct { CommandSuffix []string }
  func (e *Executor) dispatchActionWithOptions(ctx context.Context, st *engineState, engine, action string, act Action, params json.RawMessage, options actionOptions) (json.RawMessage, error)
  ```

  Keep non-load public `Action` behavior unchanged by passing empty options. For `action == "load_model"`, `Action` derives options from the target engine state before dispatch, so the desktop's local `engine:action` route and the remote control endpoint share one implementation. Append `CommandSuffix` only after manifest-authored command placeholders resolve.

- [ ] **Step 6: Implement model-load translation and cached probing**

  In `contextload.go`, implement:

  ```go
  func modelLoadContextArgs(control *ContextControl, value enginesettings.ContextWindowSetting) ([]string, error)
  func (e *Executor) supportsContextAuto(ctx context.Context, st *engineState) (bool, error)
  func (e *Executor) reportedModelContextLimit(ctx context.Context, st *engineState, model string) (int, bool, error)
  ```

  Add a mutex-protected cache to `Executor`, keyed by resolved CLI path plus its file identity, so an upgraded CLI is reprobed. Run the manifest probe through the same bounded command runner as other CLI actions and tokenize help lines to match exact `--auto`. Use the manifest-declared result paths to read an available per-model maximum before dispatch. `Action` reads the target state under its normal synchronization and passes only the derived suffix internally; `ModelLoad` continues to delegate to `Action`.

- [ ] **Step 7: Add model-reload preview and authoritative apply checks**

  For `scope=model_load`, preview probes Auto and queries the engine's declared `loaded_models` action. Set `modelReloadRequired` only for an effective setting change with a nonempty resident set. Repeat the Auto probe during apply so a CLI replacement between preview and apply cannot save an unsupported mode.

- [ ] **Step 8: Run the focused tests**

  Run: `go test ./... -run 'TestLMStudioContext|TestModelReloadRequired|TestRemoteModelLoadUsesTargetContext'`

  Expected: PASS.

- [ ] **Step 9: Commit model-load mapping**

  ```powershell
  git add services/nvpair-engine-manager/contextload.go services/nvpair-engine-manager/contextload_test.go services/nvpair-engine-manager/executor.go services/nvpair-engine-manager/actions.go services/nvpair-engine-manager/modelops.go services/nvpair-engine-manager/modelops_test.go services/nvpair-engine-manager/settings.go services/nvpair-engine-manager/remote_context_test.go
  git commit -s -m "feat: apply model load context settings"
  ```

### Task 5: Parse the additive desktop contract

**Files:**
- Modify: `desktop/src/shared/types/engine-settings.ts`
- Modify: `desktop/src/electron/service-bridge/engine-settings.ts`
- Modify: `desktop/tests/modular/engine-settings-bridge.test.ts`
- Modify: `desktop/tests/modular/engine-settings-state.test.ts`

**Interfaces:**
- Consumes: Task 1's JSON field names and Task 4's preview result.
- Produces: `ContextWindowSetting`, `EngineSettingsConfig`, `EngineSettingsRequestConfig`, `EngineSettingsCapabilities`, normalized bridge snapshots, and typed `modelReloadRequired` for the UI.

- [ ] **Step 1: Write failing bridge tests**

  Add structured fixtures proving all modes parse, malformed modes/tokens reject, missing `contextWindow` normalizes to Engine default, missing `capabilities.contextWindow` normalizes to false, and missing `modelReloadRequired` normalizes to false. Prove an older remote snapshot and preview remain usable for ports/text while context is unsupported.

- [ ] **Step 2: Update state fixtures for complete normalized snapshots**

  Add context and capability values to the shared `EngineSettingsSnapshot` fixtures, then assert a newer pushed snapshot replaces the saved context selection under the existing revision/epoch ordering rules.

- [ ] **Step 3: Run focused tests and verify failure**

  Run: `npm run test:unit -- --run tests/modular/engine-settings-bridge.test.ts tests/modular/engine-settings-state.test.ts`

  Working directory: `desktop`

  Expected: FAIL because the TypeScript contract lacks the fields.

- [ ] **Step 4: Add strict shared types and parser helpers**

  Define `ContextWindowMode`, `ContextWindowSetting`, and `EngineSettingsCapabilities`. `EngineSettingsConfig` has a required normalized `contextWindow`; `EngineSettingsRequestConfig` carries the same ports/text with optional `contextWindow` for unsupported old peers, and `EngineSettingsRequest.settings` uses that request type. Add `capabilities` to snapshots and `modelReloadRequired` to preview. In the bridge, implement `contextWindow(value)` and `capabilities(value)` with explicit property checks and no casts. Normalize only absent fields; reject malformed present values.

- [ ] **Step 5: Run focused tests and typecheck**

  Run: `npm run test:unit -- --run tests/modular/engine-settings-bridge.test.ts tests/modular/engine-settings-state.test.ts`

  Run: `npm run typecheck`

  Expected: both PASS.

- [ ] **Step 6: Commit the desktop contract**

  ```powershell
  git add desktop/src/shared/types/engine-settings.ts desktop/src/electron/service-bridge/engine-settings.ts desktop/tests/modular/engine-settings-bridge.test.ts desktop/tests/modular/engine-settings-state.test.ts
  git commit -s -m "feat: bridge context window settings"
  ```

### Task 6: Add the context control to every engine settings panel

**Files:**
- Create: `desktop/src/ui/components/BackendRow/ContextWindowField.tsx`
- Modify: `desktop/src/ui/components/BackendRow/EngineSettingsSection.tsx`
- Create: `desktop/tests/modular/engine-context-window-settings.test.tsx`

**Interfaces:**
- Consumes: Task 5's normalized `ContextWindowSetting`, capability, errors, restart, and model-reload effect.
- Produces: a controlled `ContextWindowField` and complete settings requests from `EngineSettingsSection`.

- [ ] **Step 1: Write failing component tests for canonical payloads**

  Render the real settings section with typed API/store fakes. Select Engine default, Auto, each preset, and Custom 12288; assert preview receives the complete canonical object and Apply uses the normalized preview result. Assert Custom reveals a numeric input and presets hide it.

- [ ] **Step 2: Write failing validation, compatibility, and lifecycle tests**

  Assert empty/fractional/out-of-range Custom values block preview and render beside the field; server `errors.contextWindow` renders beside it; an absent capability disables only this field and displays the update-device message; no request from that old node contains a changed context value. Assert restart confirmation remains `Restart engine?`, model-load-only confirmation explains eject/load without offering an automatic reload, and a pushed snapshot replaces the draft. Confirm pending model controls remain service-state driven.

- [ ] **Step 3: Run the focused UI test and verify failure**

  Run: `npm run test:unit -- --run tests/modular/engine-context-window-settings.test.tsx`

  Working directory: `desktop`

  Expected: FAIL because the field does not exist.

- [ ] **Step 4: Implement the focused field component**

  Export `ContextWindowDraft` with `choice: 'engine-default' | 'auto' | '8192' | '16384' | '32768' | '65536' | 'custom'` and `customTokens: string`. `ContextWindowField` accepts that draft, `disabled: boolean`, `unsupported: boolean`, `error: string`, and `onChange(value: ContextWindowDraft): void`. Use existing `FormField`, `Select`, and `TextInput`; labels are exactly Engine default, Auto, 8K, 16K, 32K, 64K, and Custom. Use numeric input semantics, min 1024, max 262144, and concise helper/error text.

- [ ] **Step 5: Integrate draft, preview, Apply, and confirmation state**

  Extend `Draft` with `ContextWindowDraft`; include it in dirty comparison and convert it to the canonical setting only after local integer/range validation. Keep `fieldErrors.contextWindow` separate from the existing operation dialog so service validation renders beside the control. Store both the request and preview effects in confirmation state so restart and model-reload copy are selected from authoritative preview booleans. For a capable peer, always send the complete normalized context object. For an unsupported peer, omit `contextWindow` from the request while still allowing ports/text edits, and prevent context field edits.

- [ ] **Step 6: Run focused tests and desktop checks**

  Run: `npm run test:unit -- --run tests/modular/engine-context-window-settings.test.tsx tests/modular/engine-settings-state.test.ts`

  Run: `npm run typecheck`

  Run: `npm run dead-code:check`

  Expected: all PASS.

- [ ] **Step 7: Commit the settings UI**

  ```powershell
  git add desktop/src/ui/components/BackendRow/ContextWindowField.tsx desktop/src/ui/components/BackendRow/EngineSettingsSection.tsx desktop/tests/modular/engine-context-window-settings.test.tsx
  git commit -s -m "feat: edit engine context windows"
  ```

### Task 7: Update contracts, documentation, and complete verification

**Files:**
- Modify: `services/nvpair-engine-manager/spec.md`
- Modify: `services/nvpair-engine-manager/README.md`
- Modify: `docs/engine-settings.mdx`
- Modify: `desktop/docs/services-backend.md`
- Modify: `desktop/docs/services-parity.md`
- Generate: `desktop/docs/services-api.md`
- Modify if generated tooling requires it: `desktop/docs/service-contract-exceptions.json`

**Interfaces:**
- Consumes: all implemented service and desktop behavior.
- Produces: reviewed contract documentation, generated API reference, verification evidence, and PR release-intent text.

- [ ] **Step 1: Update source documentation**

  Document the normalized wire fields, validation bounds, capabilities, preview lifecycle flags, old-client preservation rule, manifest ownership, exact mappings, LM Studio manual reload behavior, and remote target ownership. State Engine default versus Auto clearly and keep Android MNN out of the feature description.

- [ ] **Step 2: Regenerate and check the service contract**

  Run: `npm run service-contracts:write`

  Run: `npm run service-contracts:check`

  Working directory: `desktop`

  Expected: generated API docs include `contextWindow`, `capabilities.contextWindow`, and `modelReloadRequired`; check PASS.

- [ ] **Step 3: Run Go verification**

  Run: `gofmt -w shared/enginesettings/settings.go (Get-ChildItem nvpair-engine-manager -Filter *.go | ForEach-Object FullName)`

  Working directory: `services`

  Run: `go test ./...`

  Working directory: `services/nvpair-engine-manager`

  Expected: PASS with no skipped context-setting test. Record unrelated or environment-dependent skips separately.

- [ ] **Step 4: Run desktop verification**

  Run: `npm run lint`

  Run: `npm run typecheck`

  Run: `npm run test:unit`

  Run: `npm run dead-code:check`

  Run: `npm run service-contracts:check`

  Working directory: `desktop`

  Expected: all PASS.

- [ ] **Step 5: Run repository policy verification**

  Run: `node scripts/spdx-headers.mjs`

  Working directory: repository root

  Expected: PASS without modifying unrelated files.

- [ ] **Step 6: Perform opt-in real-engine acceptance checks**

  On controlled machines only: verify LM Studio through `/api/v1/models` and `lms ps` after manual eject/load; inspect Ollama's applied environment and loaded context; inspect llama.cpp's `--ctx-size` and loaded model; then send an OpenCode request above 8192 and below the selected fixed value through PAIR Local. Record engine versions, selected values, and outcomes without prompts or response bodies.

- [ ] **Step 7: Review the final diff against the spec**

  Check every spec verification bullet, confirm no TypeScript cast/dynamic import was introduced, confirm vendor mapping exists only in Go/manifests, confirm no silent fallback path exists, and confirm `services/versions.json` and `CHANGELOG.md` are untouched.

- [ ] **Step 8: Commit documentation and generated contracts**

  ```powershell
  git add services/nvpair-engine-manager/spec.md services/nvpair-engine-manager/README.md docs/engine-settings.mdx desktop/docs/services-backend.md desktop/docs/services-parity.md desktop/docs/services-api.md desktop/docs/service-contract-exceptions.json
  git commit -s -m "docs: document engine context settings"
  ```

- [ ] **Step 9: Prepare the pull request release intent**

  In the PR description's `pair-release-intent:v1` block, declare an `nvpair-engine-manager` service bump and describe the user-visible Default context window setting, LM Studio manual model reload requirement, and the compatibility behavior. Do not edit generated version or changelog files.
