<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# ComputeHarbor Visual Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the desktop application's user-visible NVIDIA identity with the approved ComputeHarbor deep-sea console identity without changing runtime behavior or compatibility identifiers.

**Architecture:** Keep the existing React, Electron, Tailwind, and Foundations component structure. Add one ComputeHarbor theme override layer after the vendored Foundations CSS, centralize semantic colors in the existing color constants, and add an original reusable brand-mark component plus reproducibly generated application icons.

**Tech Stack:** Electron, React, TypeScript, Vitest, Tailwind CSS utilities, CSS custom properties, `@nvidia/foundations-react-core` as a retained licensed dependency, `@fiahfy/icns` for packaged icon generation.

**Spec:** `docs/superpowers/specs/2026-10-13-computeharbor-visual-refresh-design.md`

## Global Constraints

- Preserve SPDX headers, Apache-2.0 notices, copyright statements, LICENSE files, and third-party attribution.
- Preserve service behavior, JSON-RPC contracts, routing, persisted data formats, command names, application identifiers, migration identifiers, and dependency package names.
- Keep `APP_ID`, `APP_ORG`, `APP_PREVIOUS_ORG`, `APP_DATA_DIR_NAME`, and `APP_EXECUTABLE_NAME` unchanged as compatibility identifiers.
- Use exact semantic colors: harbor cyan `#25D0B1`, deep-sea blue `#07131F`, harbor blue `#10283A`, compute blue `#4D9FFF`, beacon orange `#F2A65A`, failure red `#F16D78`, mist white `#EAF2F7`, sea-fog gray `#91A7B5`, and structural border `#234052`.
- Harbor cyan is the sole brand accent; blue, orange, and red are reserved for operational state.
- Preserve existing navigation labels, information architecture, component behavior, and responsive vertical-navigation-to-tabs flow.
- Do not migrate frameworks, replace the component library, add a network font, or change service binaries.
- Respect reduced-motion preferences and maintain WCAG AA contrast and visible keyboard focus.
- Do not overwrite unrelated working-tree changes; re-read each dirty file and integrate at hunk level.
- Follow repository rules: no type casts, static imports only, absolute imports across directories, and edits through `apply_patch`.

## Review Focus

- A user upgrading from PAIR keeps the same data directory and application ID while every visible product title reads ComputeHarbor; pin this in Task 1 branding tests.
- A user with reduced motion sees no spinner, ring, or card entrance animation; pin this in Task 2 CSS contract tests.
- Online, running, busy, and failed states remain distinguishable by text or icon as well as color; pin semantic values and existing labels in Task 3 tests.
- Electron window drag, minimize, maximize, restore, and close controls remain operable after adding the logo; pin rendered markup and preserve the existing window-control tests in Task 4.
- Windows, macOS, Linux, tray, and renderer icons all derive from the same mark and contain every required size; pin dimensions and output inventory in Task 5.

---

### Task 1: Freeze the user-visible brand and compatibility boundary

**Files:**
- Create: `desktop/tests/modular/brand-identity.test.ts`
- Modify: `desktop/src/shared/constants/app.ts`
- Modify: `desktop/src/ui/index.html`
- Modify: `desktop/src/ui/public/manifest.webmanifest`
- Test: `desktop/tests/modular/brand-identity.test.ts`

**Interfaces:**
- Consumes: Existing `APP_DISPLAY_NAME` and compatibility constants from `@/shared/constants/app`.
- Produces: `APP_DISPLAY_NAME === 'ComputeHarbor'` for all visible Electron and renderer copy; unchanged internal compatibility constants.

- [ ] **Step 1: Write the failing identity boundary test**

Add tests named `uses ComputeHarbor for visible product identity`, `keeps persisted identity compatibility`, and `keeps renderer metadata aligned`. Assert the display name and HTML/manifest values are ComputeHarbor while `APP_DATA_DIR_NAME`, `APP_EXECUTABLE_NAME`, `APP_ID`, `APP_ORG`, and `APP_PREVIOUS_ORG` retain their current values.

- [ ] **Step 2: Run the focused test and verify the metadata assertions fail where current work is incomplete**

Run: `npm run test:unit -- tests/modular/brand-identity.test.ts`

Expected: FAIL until every visible identity assertion is implemented; compatibility assertions already pass.

- [ ] **Step 3: Complete the visible identity edits without changing compatibility constants**

Set the display name, document title, Apple web-app title, manifest name, manifest short name, and manifest description to ComputeHarbor-specific copy. Re-read and preserve the user's existing edits in all three dirty files.

- [ ] **Step 4: Run the focused test**

Run: `npm run test:unit -- tests/modular/brand-identity.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit the branding boundary**

```bash
git add desktop/tests/modular/brand-identity.test.ts desktop/src/shared/constants/app.ts desktop/src/ui/index.html desktop/src/ui/public/manifest.webmanifest
git commit -s -m "feat: establish ComputeHarbor product identity"
```

### Task 2: Add the deep-sea theme contract and global interaction polish

**Files:**
- Create: `desktop/src/ui/styles/computeharbor-theme.css`
- Create: `desktop/tests/modular/computeharbor-theme.test.ts`
- Modify: `desktop/src/ui/styles/index.css`
- Modify: `desktop/src/ui/styles/sheen.css`
- Modify: `desktop/src/ui/components/NodeActive/NodeActive.css`
- Test: `desktop/tests/modular/computeharbor-theme.test.ts`

**Interfaces:**
- Consumes: Foundations CSS variables and existing `pair-*`/`nv-*` class hooks.
- Produces: A post-vendor theme layer defining `--ch-*` semantic tokens and mapping Foundations brand, text, surface, border, interaction, focus, and danger variables onto them.

- [ ] **Step 1: Write the failing CSS contract tests**

Read the theme source as text and assert all nine approved hex colors are present, `index.css` imports the theme after vendored Foundations CSS, focus-visible styling exists, and a `prefers-reduced-motion: reduce` block disables `.spinner-element`, `.pulsing`, `.workload-item-card`, and `.ring-item` animation.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `npm run test:unit -- tests/modular/computeharbor-theme.test.ts`

Expected: FAIL because the theme file and reduced-motion contract do not exist.

- [ ] **Step 3: Implement the theme token layer**

Define the exact palette as `--ch-harbor-cyan`, `--ch-deep-sea`, `--ch-harbor-blue`, `--ch-compute-blue`, `--ch-beacon-orange`, `--ch-failure-red`, `--ch-mist-white`, `--ch-sea-fog`, and `--ch-border`. Override Foundations variables at `html`/`:root`, style global focus-visible state, selection, scrollbars, overlays, inputs, buttons, tabs, vertical navigation, panels, and spinners with 150-220 ms transitions.

- [ ] **Step 4: Rework existing shell surfaces without changing layout behavior**

Replace pure-black and neutral-gray presentation in `index.css` and `sheen.css` with deep-sea and harbor tonal surfaces, cool tinted shadows, restrained borders, and one ambient radial background. Preserve all existing selectors and the user's pending accordion additions.

- [ ] **Step 5: Add reduced-motion handling**

Disable decorative and continuous motion under `prefers-reduced-motion: reduce`; preserve immediate state changes and non-motion feedback.

- [ ] **Step 6: Run the theme test and style checks**

Run: `npm run test:unit -- tests/modular/computeharbor-theme.test.ts`

Expected: PASS.

Run: `npm run lint`

Expected: PASS.

- [ ] **Step 7: Commit the theme foundation**

```bash
git add desktop/src/ui/styles/computeharbor-theme.css desktop/src/ui/styles/index.css desktop/src/ui/styles/sheen.css desktop/src/ui/components/NodeActive/NodeActive.css desktop/tests/modular/computeharbor-theme.test.ts
git commit -s -m "feat: add ComputeHarbor deep-sea theme"
```

### Task 3: Align charts, workloads, and node states with semantic colors

**Files:**
- Modify: `desktop/src/ui/constants/colors.ts`
- Modify: `desktop/src/ui/utils/colors.ts`
- Modify: `desktop/src/ui/components/TrayApp/TrayNodeRow.tsx`
- Modify: `desktop/src/ui/components/BackendRow/BackendFooter.tsx`
- Modify: `desktop/src/ui/components/LocalBadge.tsx`
- Modify: `desktop/tests/modular/workload-card-labels.test.ts`
- Test: `desktop/tests/modular/workload-card-labels.test.ts`

**Interfaces:**
- Consumes: Existing `BadgeColor`, workload state, chart palette, and status label APIs.
- Produces: Six-digit semantic color values safe for connection-line alpha suffixes; unchanged `getWorkloadStateColor` and `getWorkloadColorBar` signatures.

- [ ] **Step 1: Change tests to assert the approved semantic mapping**

Assert running/inference is `#4D9FFF`, queued/busy is `#F2A65A`, failed is `#F16D78`, online/completed is `#25D0B1`, and every `WORKLOAD_COLOR_MAP` value remains a six-digit hex string. Retain assertions that rendered workload cards include their textual state and node information.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `npm run test:unit -- tests/modular/workload-card-labels.test.ts`

Expected: FAIL on the old yellow, green, blue, and red values.

- [ ] **Step 3: Implement semantic constants and component fallbacks**

Update `WORKLOAD_COLOR_MAP`, `CHART_COLORS`, GPU/VRAM palettes, hard-coded tray status colors, footer status styling, and local badge fallbacks. Remove NVIDIA-specific color comments while preserving SPDX attribution.

- [ ] **Step 4: Run the focused tests**

Run: `npm run test:unit -- tests/modular/workload-card-labels.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit semantic colors**

```bash
git add desktop/src/ui/constants/colors.ts desktop/src/ui/utils/colors.ts desktop/src/ui/components/TrayApp/TrayNodeRow.tsx desktop/src/ui/components/BackendRow/BackendFooter.tsx desktop/src/ui/components/LocalBadge.tsx desktop/tests/modular/workload-card-labels.test.ts
git commit -s -m "feat: apply semantic harbor status colors"
```

### Task 4: Add the ComputeHarbor mark to the application shell

**Files:**
- Create: `desktop/src/ui/components/BrandMark.tsx`
- Create: `desktop/tests/modular/brand-mark.test.ts`
- Modify: `desktop/src/ui/components/MainApp/MainApp.tsx`
- Modify: `desktop/src/ui/components/AppBar/AppBar.tsx`
- Modify: `desktop/src/ui/components/AppBar/AppBar.css`
- Test: `desktop/tests/modular/brand-mark.test.ts`

**Interfaces:**
- Consumes: `AppBar.slotTitleLeft`, `APP_DISPLAY_NAME`, and the existing React icon conventions.
- Produces: `BrandMark({ size?: number, title?: string })`, an inline SVG with a stable `viewBox`, current-color geometry, optional accessible title, and no dependency on renderer/Electron APIs.

- [ ] **Step 1: Write the failing server-render test**

Render `BrandMark` to static markup. Assert it has the harbor mark's stable accessible label when `title` is supplied, is hidden from assistive technology when omitted, uses `currentColor`, and contains no NVIDIA wording. Render the main title composition and assert the product name remains present.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `npm run test:unit -- tests/modular/brand-mark.test.ts`

Expected: FAIL because `BrandMark` does not exist.

- [ ] **Step 3: Implement the original harbor-channel SVG mark**

Build the mark from simple SVG geometry representing two harbor walls and a central compute channel. Keep it legible at 16 px, use current color, and follow the repository's local icon accessibility pattern.

- [ ] **Step 4: Integrate and style the title bar**

Pass `BrandMark` through `AppBar.slotTitleLeft`; add stable title-bar classes for the mark and wordmark; preserve drag/no-drag behavior, existing window callbacks, and control target sizes. Replace `capitalize` on the product title so `ComputeHarbor` casing remains exact.

- [ ] **Step 5: Run focused and existing window tests**

Run: `npm run test:unit -- tests/modular/brand-mark.test.ts tests/modular/window-reveal-stall.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit the shell mark**

```bash
git add desktop/src/ui/components/BrandMark.tsx desktop/src/ui/components/MainApp/MainApp.tsx desktop/src/ui/components/AppBar/AppBar.tsx desktop/src/ui/components/AppBar/AppBar.css desktop/tests/modular/brand-mark.test.ts
git commit -s -m "feat: add ComputeHarbor shell branding"
```

### Task 5: Generate cross-platform application icons from the original mark

**Files:**
- Create: `desktop/resources/app-icon/computeharbor-mark.svg`
- Modify: `desktop/resources/app-icon/pair-16.png`
- Modify: `desktop/resources/app-icon/pair-24.png`
- Modify: `desktop/resources/app-icon/pair-32.png`
- Modify: `desktop/resources/app-icon/pair-48.png`
- Modify: `desktop/resources/app-icon/pair-64.png`
- Modify: `desktop/resources/app-icon/pair-128.png`
- Modify: `desktop/resources/app-icon/pair-256.png`
- Modify: `desktop/resources/app-icon/pair-512.png`
- Modify: `desktop/resources/app-icon/pair-1024.png`
- Modify: `desktop/resources/app-icon/pair.ico`
- Modify: `desktop/scripts/build/generate-app-icons.ts`
- Create: `desktop/tests/modular/app-icons.test.ts`
- Regenerate: `desktop/resources/icons/logo.png`
- Regenerate: `desktop/resources/icons/logo.ico`
- Regenerate: `desktop/resources/icons/logo.icns`
- Regenerate: `desktop/resources/icons/linux/*.png`
- Regenerate: `desktop/src/ui/public/favicon.png`
- Test: `desktop/tests/modular/app-icons.test.ts`

**Interfaces:**
- Consumes: The same geometry and palette as `BrandMark`; existing `npm run generate:icons` entry point.
- Produces: Valid square PNG sources at 16/24/32/48/64/128/256/512/1024, one multi-size ICO, one ICNS, Linux hicolor outputs, renderer favicon, and tray/window PNG.

- [ ] **Step 1: Write the failing icon inventory test**

Assert the SVG exists and contains harbor cyan plus deep-sea blue, every required PNG exists with matching IHDR dimensions, ICO and ICNS outputs are non-empty, favicon is 256x256, and the source SVG contains no external image reference.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `npm run test:unit -- tests/modular/app-icons.test.ts`

Expected: FAIL because the new source SVG and icon content contract are absent.

- [ ] **Step 3: Create the source mark and source raster set**

Create a square deep-sea icon with the harbor-channel mark in harbor cyan. Render each size from the same source with optical simplification at 16-32 px so the channel remains open. Generate the multi-resolution ICO from those rasters using the existing installed tooling or a small deterministic extension to `generate-app-icons.ts`; do not add a runtime dependency.

- [ ] **Step 4: Regenerate shipped outputs**

Run: `npm run generate:icons`

Expected: The command reports all desktop, Linux, and favicon outputs generated.

- [ ] **Step 5: Run the icon test**

Run: `npm run test:unit -- tests/modular/app-icons.test.ts`

Expected: PASS.

- [ ] **Step 6: Visually inspect source and generated icons at 16, 32, 128, and 512 px**

Expected: The central channel stays open, the silhouette is distinct from NVIDIA's eye mark, edges are crisp, and no platform output uses the old logo.

- [ ] **Step 7: Commit the icon system**

```bash
git add desktop/resources/app-icon desktop/resources/icons desktop/src/ui/public/favicon.png desktop/scripts/build/generate-app-icons.ts desktop/tests/modular/app-icons.test.ts
git commit -s -m "feat: replace application icons with ComputeHarbor mark"
```

### Task 6: Audit user-visible branding and complete verification

**Files:**
- Modify only if audit finds a visible leak: files under `desktop/src/ui/` or renderer-facing copy under `desktop/src/electron/`
- Test: all desktop test suites and repository checks listed below

**Interfaces:**
- Consumes: Outputs from Tasks 1-5.
- Produces: A verified desktop tree with no user-visible NVIDIA branding and no change to required legal or compatibility strings.

- [ ] **Step 1: Audit remaining brand strings and old accent literals**

Run: `rg -n -i "NVIDIA|#76b900|#a5de15" desktop/src desktop/resources desktop/package.json`

Classify every hit as required attribution/dependency/compatibility content or an unwanted visible presentation. Fix only the latter and add a regression assertion to the owning test.

- [ ] **Step 2: Run all desktop checks**

Run from `desktop/`:

```bash
npm run lint
npm run typecheck
npm run test:unit
npm run dead-code:check
npm run service-contracts:check
```

Expected: All commands exit 0. Report any skipped test as unverified.

- [ ] **Step 3: Run repository-level attribution and build-script checks**

Run from the repository root:

```bash
node scripts/spdx-headers.mjs
cd desktop
npm run build-scripts:verify
```

Expected: Both commands exit 0 and no SPDX header was removed.

- [ ] **Step 4: Perform visual acceptance at representative widths**

Inspect loading, stopped-service, overview, node states, workloads, settings wide/narrow navigation, model views, modal/dropdown overlays, focus-visible, disabled controls, title-bar controls, and tray window. Confirm one deep-sea theme, semantic state colors, readable contrast, intact responsive behavior, and no old logo.

- [ ] **Step 5: Review the complete diff for scope and user changes**

Run: `git diff --check` and `git status --short`.

Expected: No whitespace errors; unrelated pre-existing files remain untouched; all refresh changes are intentional.

- [ ] **Step 6: Commit any audit-only fixes**

```bash
git add <audit-fix-files-and-owning-tests>
git commit -s -m "fix: complete ComputeHarbor visual identity audit"
```

Skip this commit when the audit requires no additional edits.
