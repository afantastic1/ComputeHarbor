<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# ComputeHarbor visual refresh design

## Purpose

Refresh the existing desktop interface into an independent ComputeHarbor product identity while preserving the application's behavior, information architecture, accessibility, and open-source attribution. The result should read as a focused distributed-compute control surface rather than an NVIDIA-branded application.

## Scope

This change covers the user-visible desktop shell and its shared visual foundation:

- application display name, browser metadata, and visible product copy;
- application logo, favicon, and packaged desktop icons;
- global color tokens, typography, surfaces, borders, shadows, and focus treatment;
- top bar, primary tabs, navigation, buttons, cards, status indicators, charts, scrollbars, overlays, loading states, and responsive presentation;
- semantic use of color for nodes, inference work, warnings, and failures.

It does not change service behavior, JSON-RPC contracts, routing, persisted data formats, command names, application identifiers, migration identifiers, or dependency package names. Internal compatibility identifiers may continue to contain upstream names where changing them would break upgrades or integration.

## Legal and attribution constraints

- Preserve SPDX headers, Apache-2.0 notices, copyright statements, LICENSE files, and required third-party attribution.
- Do not rewrite source ownership history or rename third-party packages merely to hide their provenance.
- Remove NVIDIA references only from user-visible branding and presentation where they are not legally required notices.
- Keep the existing licensed component library as an implementation dependency for this refresh. Override its visual tokens through a ComputeHarbor theme layer rather than copying or relicensing library code.

## Visual direction

The selected direction is **Deep-sea console**: a compact, dark operational interface inspired by harbor navigation instruments. It should feel calm, precise, and technical. It must avoid fluorescent NVIDIA green, pure-black expanses, purple AI gradients, excessive glass effects, and decorative animation.

### Palette

| Semantic role | Color | Usage |
| --- | --- | --- |
| Harbor cyan | `#25D0B1` | Logo, primary action, focus, online and idle states |
| Deep-sea blue | `#07131F` | Application background |
| Harbor blue | `#10283A` | Cards, sidebars, raised surfaces |
| Compute blue | `#4D9FFF` | Inference jobs, active data flow, informational charts |
| Beacon orange | `#F2A65A` | Busy nodes and warnings |
| Failure red | `#F16D78` | Failed and destructive states |
| Mist white | `#EAF2F7` | Primary text |
| Sea-fog gray | `#91A7B5` | Secondary text |
| Structural border | `#234052` | Dividers, card edges, inactive controls |

Harbor cyan is the only brand accent. Compute blue, beacon orange, and failure red are reserved for their operational meanings and must not become decorative accents.

### Surfaces and depth

- Use deep-sea blue as the continuous page theme.
- Use harbor blue and nearby tonal variants for grouping instead of many floating cards.
- Separate regions with thin cool-blue borders, spacing, and restrained inset highlights.
- Use a subtle radial ambient gradient behind the main shell. It must not reduce text contrast or resemble a marketing-page backdrop.
- Keep corner radii restrained and consistent: smaller controls inside slightly softer containers.
- Shadows carry a cool navy tint and remain subtle enough for a dense dashboard.

### Typography and data

- Use the operating-system font stack already available to Electron, with refined weights, line height, and tracking rather than introducing a network font dependency.
- Use sentence case in navigation and headings.
- Use tabular figures for metrics, ports, percentages, memory, and job data.
- Preserve compact density while increasing separation between major regions and clarifying heading hierarchy.

## Brand assets

Create an original code-authored ComputeHarbor mark based on two converging harbor walls around a central compute channel. The geometry should remain recognizable at 16 pixels and must not resemble NVIDIA's eye mark.

The mark will be delivered as a source SVG plus generated PNG/ICO/ICNS variants required by Electron. It will appear in the application title bar and replace the current favicon and packaged icons. Asset generation must retain a reproducible source and must not incorporate third-party trademarks.

## Component treatment

### Application shell and title bar

- Add the ComputeHarbor mark beside the product name.
- Give the title bar a lightly translucent deep-sea surface with a structural bottom edge.
- Preserve Electron drag and window-control regions, keyboard access, and minimum target sizes.

### Navigation and tabs

- Replace the green active rail with harbor cyan.
- Use a low-contrast harbor-blue active surface and mist-white active label.
- Keep the current responsive vertical-navigation-to-tabs behavior and all existing labels.
- Make focus state visible independently of hover and active state.

### Panels and cards

- Retain cards only where they communicate grouping or elevation.
- Use tonal surfaces with thin structural borders instead of generic black cards and heavy shadows.
- Preserve current content order, widths, and responsive behavior unless a local spacing adjustment is required to prevent crowding.

### Buttons and inputs

- Primary actions use harbor cyan with deep-sea text at WCAG AA contrast.
- Secondary actions use tonal harbor-blue surfaces; tertiary actions remain visually quiet.
- Destructive actions use failure red only at the decision point.
- Hover, pressed, disabled, and focus-visible states must remain distinct.
- Motion is limited to 150-220 ms state transitions and respects reduced-motion preferences.

### Operational status and visualization

- Online/idle: harbor cyan.
- Inference/data flow: compute blue.
- Busy/warning: beacon orange.
- Failure/offline error: failure red.
- Do not rely on color alone; retain labels, icons, or patterns that communicate state.
- Update chart constants and legends together so visual output and explanations remain consistent.

### Loading, empty, error, and overlay states

- Restyle existing loading indicators with harbor cyan and reduced-motion support.
- Preserve existing empty and error copy unless it contains user-visible NVIDIA branding.
- Use deep-sea overlay backdrops and maintain readable dialog separation.

## Implementation boundaries

- Prefer CSS custom properties and focused class overrides in the existing styling stack.
- Do not migrate frameworks or replace the component library during this refresh.
- Do not use type casts, dynamic imports beyond existing approved patterns, or renderer imports from Electron code.
- Preserve absolute-import conventions and existing state ownership.
- Avoid changing JSON-RPC payloads or service binaries.
- Keep user-authored working-tree changes intact and integrate around them.

## Verification

Run the following from `desktop/` after implementation:

1. `npm run lint`
2. `npm run typecheck`
3. `npm run test:unit`
4. `npm run dead-code:check`
5. `npm run service-contracts:check`

Also run the repository SPDX header check and inspect representative desktop widths for:

- initial loading and service-stopped states;
- overview with online, busy, and failed nodes;
- settings vertical navigation and narrow tab fallback;
- model and workload views;
- modal, dropdown, focus-visible, and disabled states;
- title bar controls and the packaged icon at small sizes.

## Acceptance criteria

- No user-visible NVIDIA name, logo, green brand treatment, or NVIDIA-specific product copy remains in the desktop interface.
- Required attribution, SPDX headers, licenses, dependency names, compatibility identifiers, and migration paths remain intact.
- ComputeHarbor uses the approved palette consistently and semantic status colors are not decorative.
- The desktop application retains its current behavior, navigation labels, information architecture, and responsive flows.
- Text and interactive states meet WCAG AA contrast expectations and keyboard focus remains clearly visible.
- All listed automated checks pass, with skipped tests reported as unverified rather than passing.
- The source logo and all packaged icon variants are consistent and clearly distinct from NVIDIA trademarks.
