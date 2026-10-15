<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# Engine context window settings

## Goal

Every managed local inference engine exposes a structured default context
window setting in its existing Engine settings panel. A user can preserve the
engine's current behavior, request the engine's automatic choice, select a
common fixed size, or enter a custom size. The engine manager owns validation,
persistence, engine-specific translation, remote synchronization, and user
visible failures.

The first release covers Ollama, LM Studio, and llama.cpp on local and remote
nodes. It fixes the current mismatch where a client can advertise a large
context budget while LM Studio has loaded the selected model with an 8192-token
context.

## Product behavior

Each editable Engine settings panel adds a **Default context window** field
with these choices:

- **Engine default** preserves the engine's existing configuration and passes
  no PAIR context override. This is the migration value for existing installs.
- **Auto** explicitly requests the engine's native automatic behavior.
- **8K**, **16K**, **32K**, and **64K** save fixed values of 8192, 16384, 32768,
  and 65536 tokens.
- **Custom** reveals a numeric input and saves its exact token value.

The field uses the existing `FormField`, `Select`, and `TextInput` components.
The custom input appears directly below the select, carries a concise helper
message, uses numeric input semantics, and reports server validation beside the
field. The controls join the existing ports and engine arguments in the same
settings section; this change does not add a new modal or page.

Changing an Ollama or llama.cpp context setting while the engine is running
uses the existing restart confirmation. Changing LM Studio's setting does not
restart the server. When LM Studio has loaded models, preview reports that a
model reload is required and the confirmation explains that the new setting
applies after ejecting and loading those models again. PAIR does not
automatically interrupt inference or reload resident models.

## Settings contract

The node-owned engine settings contract gains a structured context value:

```text
ContextWindowSetting {
    mode: "engine-default" | "auto" | "fixed"
    tokens: integer | null
}
```

`EngineSettingsConfig` and the Go `enginesettings.Config` carry the value as
`contextWindow`. `engine-default` and `auto` require `tokens` to be null.
`fixed` requires an integer from 1024 through 262144 inclusive. UI presets are
presentation only; the wire always carries the exact integer.

Settings snapshots add a `capabilities.contextWindow` boolean. A new service
sets it when the target engine declares a context control. A snapshot from an
older service lacks the capability and is normalized to false by the desktop
bridge. The UI then disables the field and directs the user to update that
device. This prevents a new desktop from sending a setting that an older peer
would silently discard.

Settings preview adds `modelReloadRequired`. It is true when a model-load scoped
setting changes while that engine reports one or more resident models. Preview
continues to own field errors and lifecycle effects: an invalid context value
appears under `errors.contextWindow`, `restart` covers engine process restarts,
and `modelReloadRequired` covers resident model reloads.

Missing context fields in persisted overrides and requests from old clients
normalize to `engine-default`. A request from an old client must preserve the
currently saved context value instead of resetting it. A new client always
sends the complete normalized settings object.

## Engine ownership and persistence

`nvpair-engine-manager` remains the source of truth. Renderer and Electron code
must not parse engine arguments, modify vendor configuration files, or decide
how a context selection maps to a runtime.

The engine manifest schema gains a context-control declaration. It identifies
whether the control applies to the engine launch or to model load, and declares
the environment variable or flag used for automatic and fixed values. The
selected value is persisted beside the existing runtime overrides in the
per-engine user override. Context settings stay independent from `launchText`,
so raw engine arguments cannot create a second UI-owned value for the same
control.

Preview rejects a `launchText` environment variable or flag that aliases the
managed context control. The error tells the user to use Default context window.
Unrelated arguments and environment assignments remain literal and round trip
unchanged.

The manifests declare these mappings:

| Engine | Scope | Engine default | Auto | Fixed |
| --- | --- | --- | --- | --- |
| Ollama | engine launch | omit override | `OLLAMA_CONTEXT_LENGTH=0` | `OLLAMA_CONTEXT_LENGTH=N` |
| llama.cpp | engine launch | omit override | `--ctx-size 0` | `--ctx-size N` |
| LM Studio | model load | `lms load MODEL` | `lms load MODEL --auto` | `lms load MODEL --context-length N` |

Ollama and llama.cpp context changes alter the effective launch and therefore
restart a running owned engine. LM Studio context changes only alter future
`load_model` actions, so its server remains running. The same mapping is used
for local and remote model loads because the remote request carries no derived
vendor arguments; the target node applies its own authoritative setting.

LM Studio Auto is available only when the installed `lms load --help` advertises
the exact `--auto` flag. The owning engine manager performs and caches that
capability probe. Preview rejects Auto with an update-or-fixed-value message
when the flag is unavailable. No fallback to Engine default is allowed.

## Data flow

1. The renderer reads the authoritative settings snapshot and renders the
   normalized context value and capability.
2. Editing the field marks the existing settings draft dirty.
3. `engines:preview-settings` travels through the service bridge and broker to
   the target node's engine manager.
4. The engine manager validates the structured setting, rejects aliases in raw
   launch text, calculates restart and model-reload effects, and returns the
   normalized preview.
5. After the existing confirmation step, `engines:apply-settings` persists the
   complete normalized setting.
6. Launch-scoped settings are applied through the existing stop, persist,
   rebind, and resume transaction. Model-load scoped settings are persisted
   without stopping the engine server.
7. The authoritative `engine:settings-changed` push replaces the renderer
   draft. Commands do not synthesize replacement state in the renderer.
8. A later local or remote model load reads the saved setting on the owning
   node and constructs the declared native action.

## Validation and failure behavior

Validation is authoritative in the engine manager and is repeated at apply.
The UI may constrain numeric entry for convenience but does not replace service
validation.

- Unknown modes, non-null tokens for non-fixed modes, missing fixed tokens,
  fractional values, and values outside 1024 through 262144 fail preview.
- A fixed value larger than a particular model supports fails that model load
  with the model ID, requested size, and engine-reported maximum when available.
- Memory allocation failures retain the saved setting, leave the model
  unloaded, and suggest a smaller value.
- Unsupported Auto fails preview on the owning node.
- Persist, restart, and model-load failures use the existing settings snapshot
  and `errors:update` paths. They do not log prompts, messages, response bodies,
  credentials, or model input.
- PAIR never silently clamps, substitutes, or falls back to another context
  mode. If an engine itself reports a different effective value, that value is
  treated as an engine result rather than relabeled as the requested value.

## Compatibility

The change is additive on the JSON wire. New Go code normalizes absent fields;
new desktop code treats an absent capability as unsupported. Old desktop
clients can continue editing ports and launch text without resetting a saved
context value because partial old requests preserve that field on the owner.

Remote settings follow the existing pinned peer settings relay. The target node
performs validation and translation because it owns the engine installation,
version, model inventory, and hardware. Browser-access and trust boundaries do
not change.

No compatibility branch is added to model loading. A node either advertises
the structured capability and applies it canonically, or the field is
unavailable until that node is updated.

## Verification

Go tests must prove:

- missing fields normalize to Engine default without changing existing launch
  behavior;
- old-client requests preserve a previously saved context setting;
- every mode and boundary value validates as specified;
- context aliases in `launchText` are rejected while unrelated literals remain;
- the three manifests produce the exact environment variables and arguments in
  the mapping table;
- Ollama and llama.cpp request a restart only when their effective setting
  changes;
- LM Studio persists without restarting and reports a required model reload
  when models are resident;
- LM Studio Auto is rejected when the owner does not expose `--auto`;
- saved settings survive an engine-manager restart;
- local and remote loads use the target node's saved value;
- load errors do not fall back or leak request content.

Desktop tests must prove:

- presets and Custom produce the canonical structured payload;
- Custom validation and service field errors render beside the control;
- changing the field participates in dirty, reload, preview, Apply, and push
  replacement behavior;
- restart and model-reload confirmations use the correct copy;
- an older remote node disables the field and receives no context value;
- pending actions and existing model controls remain driven by service state.

Acceptance testing uses the real engine surfaces:

- LM Studio `/api/v1/models` and `lms ps` show the selected context after the
  model is reloaded;
- Ollama starts with the selected `OLLAMA_CONTEXT_LENGTH` and reports the loaded
  context;
- llama.cpp starts with the selected `--ctx-size` and its loaded model reports
  that context;
- an OpenCode request larger than 8192 tokens and smaller than the selected
  fixed context succeeds through the PAIR Local endpoint.

Run the affected Go module tests, desktop unit tests, `npm run typecheck`,
`npm run dead-code:check`, and `npm run service-contracts:check`. Regenerate
`desktop/docs/services-api.md` through `npm run service-contracts:write`; never
edit it by hand.

## Documentation and release intent

Update the engine-manager spec and README, desktop service and parity docs, and
the user-facing engine settings documentation. The pull request declares an
`nvpair-engine-manager` service bump and describes the user-visible context
window setting in its `pair-release-intent:v1` block. Do not edit
`services/versions.json` or `CHANGELOG.md` manually.

## Out of scope

- Per-request context overrides in the OpenAI-compatible proxy API.
- Per-model saved context profiles.
- Automatic truncation or summarization of oversized client conversations.
- Automatic interruption, unload, or reload of resident models when a setting
  changes.
- GPU offload, batch size, parallelism, KV cache placement, or other model-load
  controls.
- Android MNN runtime context configuration and `pairMnnOpenCL`.

## References

- LM Studio model loading: https://lmstudio.ai/docs/cli/local-models/load
- LM Studio REST load configuration: https://lmstudio.ai/docs/developer/rest/load
- Ollama context environment: https://github.com/ollama/ollama/blob/main/envconfig/config.go
- llama.cpp server context options: https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
