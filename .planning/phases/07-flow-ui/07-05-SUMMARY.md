---
phase: 07-flow-ui
plan: 05
subsystem: flow-ui/parameters
tags: [flowui, parameters, form, yaml, admin, ui-04]
requirements: [UI-04]
dependency_graph:
  requires: [07-01]
  provides: [AiAgent_Parameters.list, AiAgent_Parameters.detail]
  affects: [com.vn.agent.view.parameters]
tech_stack:
  added: []
  patterns:
    - "StandardListView + DataGrid ComponentRenderer parsing yamlBody per row (raw blob hidden)"
    - "StandardDetailView with Jmix tabSheet + Form tab + read-only YAML preview tab"
    - "Live @Tool registry discovery (AgentToolCallbacks.forCurrentUser + ApplicationContext fallback), fail-fast on empty — no hardcoded six-tool list"
    - "writeAsYaml round-trip re-runs Jakarta validation (D-13 per-field validation blocks save)"
key_files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersListView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-list-view.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-detail-view.xml
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - "Bind form fields to real AiParametersBody shape (model, temperature, topP, maxTokens, systemPrompt, enabledTools, ragTopK, ragSimilarityThreshold) — NOT the plan's assumed rateLimit/tokenBudget/iterationCap/outputScannerPatterns fields which actually live in AiAgentGuardProperties."
  - "Entity uses profileName + bodyYaml (not name + yamlBody as the plan text assumed); all code paths adapted Rule-1-style."
  - "JmixMultiSelectComboBox lives in io.jmix.flowui.component.multiselectcombobox (not .combobox) — Rule 1 bug fix against plan snippet."
  - "Tool discovery: AgentToolCallbacks.forCurrentUser() (path #1) is the canonical source — returns the exact set the backend honours. ApplicationContext @Tool-method scan kept as defensive fallback."
metrics:
  duration_min: ~35
  completed: 2026-04-21
commits:
  - "28fa69c — feat(07-05): ParametersListView + XML (parsed-YAML columns + Set active)"
  - "5d8b86b — feat(07-05): ParametersDetailView (Form + YAML preview tabs, registry-driven tools)"
---

# Phase 7 Plan 05: Parameters Views (UI-04) Summary

Admin CRUD for AiParameters profiles shipped as two Jmix Flow UI views: a list view with
parsed-YAML columns (Name / Model / Active-badge / Version) plus an immediate-commit Set
active action, and a detail view with a Form tab (all 8 AiParametersBody fields bound to a
live @Tool registry) plus a read-only YAML preview tab that regenerates on every field
valueChange via AiParametersBodyYamlMapper.writeAsYaml. The mapper's Jakarta validation
runs in onBeforeSave so invalid forms block persistence (D-13); the YAML preview recovers
gracefully with a localized hint when mid-edit validation fails. Both views are
admin-gated via the @ViewPolicy wired in 07-01.

## What was built

### Task 1 — ParametersListView (commit `28fa69c`, committed earlier in session)
- `parameters-list-view.xml`: DataGrid with Profile / Model / Active-badge / createdDate
  columns; top action bar with Create / Edit / Set active buttons.
- `ParametersListView.java`: TextRenderer on `model` column parses bodyYaml via
  `AiParametersBodyYamlMapper.readValue` (raw YAML never shown); ComponentRenderer on
  `status` column returns a themed Vaadin Badge (`success` / `contrast`);
  `setPartNameGenerator` highlights the active row; per-view `parsedCache` invalidated on
  CollectionLoader postLoad; Set active button commits immediately via
  `ParametersService.setActive` (D-14, no confirm).

### Task 2 — ParametersDetailView (commit `5d8b86b`, this session)
- `parameters-detail-view.xml`: profileName field bound to parametersDc; tabSheet with
  Form tab (model, temperature, topP, maxTokens, systemPrompt, enabledTools,
  ragTopK, ragSimilarityThreshold) + YAML tab with read-only `codeEditor`; action bar
  with Save / Set active / Close buttons.
- `ParametersDetailView.java`: at `onInit`, discovers tool names via
  `AgentToolCallbacks.forCurrentUser()` (primary) with an ApplicationContext `@Tool`
  reflection fallback, and throws `IllegalStateException` if both return empty — NO
  hardcoded six-tool list. At `onReady`, parses existing bodyYaml into the form fields
  (intersecting enabledTools with the live registry to drop stale names). At
  `onBeforeSave`, rebuilds an `AiParametersBody` from the form and calls `writeAsYaml`
  which re-runs Jakarta validation — invalid forms trigger `preventSave()` and a toast.
  Every form field's valueChange calls `refreshYamlPreview()` (D-12 live regeneration).
  Set active button commits immediately via `ParametersService.setActive` (D-14).

## Deviations from plan

### Rule 1 — Real AiParametersBody shape differs from plan text
The plan text assumed the body had `rateLimitPerMinute`, `tokenBudgetPerConversation`,
`iterationCap`, `outputScannerPatterns` as fields. In reality those knobs live in
`AiAgentGuardProperties` (host application config) — `AiParametersBody` is
`(model, temperature, topP, maxTokens, systemPrompt, enabledTools, ragTopK, ragSimilarityThreshold)`.
The form binds the 8 real fields exactly; the i18n keys the plan pre-seeded for
rateLimit/tokenBudget/iterationCap/outputScannerPatterns are left in the bundles
unused — not removed here to avoid churn in messages.properties ordering (a cleanup pass
is a candidate for a later housekeeping quick-task).

### Rule 1 — Entity field names differ from plan text
`AiParameters.profileName` + `AiParameters.bodyYaml` (not `name` + `yamlBody` as the plan
text assumed). Adapted: `profileNameField` binds via `property="profileName"` in XML; the
detail view reads/writes `getBodyYaml()` / `setBodyYaml()`.

### Rule 1 — JmixMultiSelectComboBox import package
Plan's note said `io.jmix.flowui.component.combobox.JmixMultiSelectComboBox` — actual
package in Jmix 2.8 is `io.jmix.flowui.component.multiselectcombobox.JmixMultiSelectComboBox`.
First compile failure surfaced it; fixed before commit.

### Rule 1 — Jmix codeblock component name
Plan said "codeBlock" but Jmix 2.8 Flow UI ships `<codeEditor>` / `io.jmix.flowui.component.codeeditor.CodeEditor` for monospaced preview. Used that.

## Tool-discovery path observed

Path #1 (dedicated registry) — `AgentToolCallbacks.forCurrentUser()` from Phase 3 is the
canonical per-request assembly and returns exactly the set the chat pipeline honours.
Path #2 (ApplicationContext `@Tool` reflection scan) is kept as a defensive fallback so
if `CurrentAuthentication` is unavailable during early view init we still find tools.
Path #3 (Spring AI `ToolCallbackProvider`) not wired — AgentToolCallbacks is the wrapper
the host already uses, so no third path needed.

Empty-registry fail-fast: `IllegalStateException("No @Tool beans registered — …")` with
i18n key `parametersDetail.toolRegistry.empty` for the user-visible localized tail.

## Version column binding

`createdDate` — `AiParameters` has no dedicated `version`/`revision` field other than the
JPA `@Version` optimistic-lock counter (which is not user-meaningful). `createdDate`
doubles as the natural version stamp.

## Verification

| Check | Result |
|-------|--------|
| `./gradlew :ai-agent:ai-agent:compileJava` | ✅ green (5s) |
| `./gradlew :ai-agent:ai-agent:processResources` | ✅ green |
| `./gradlew :ai-agent:ai-agent:compileTestJava` | ✅ green (up-to-date) |
| `grep -c "AiAgent_Parameters.detail" ParametersDetailView.java` | 1 |
| `grep -c "writeAsYaml" ParametersDetailView.java` | 4 (build + preview + 2 comment refs) |
| `grep -c "addValueChangeListener" ParametersDetailView.java` | 9 |
| `grep -cE '"list_entities"' ParametersDetailView.java` | 0 — no hardcoded fallback |
| `grep -cE 'IllegalStateException.*Tool\|No @Tool beans' ParametersDetailView.java` | 2 |
| `grep -cE 'getBeansWithAnnotation\|discoverToolNames' ParametersDetailView.java` | 2 |
| Distinct form `id="*Field"` in XML | 10 (8 body fields + profileName + yamlPreview) |

## Known follow-ups (deferred, not blockers for UI-04)

- Pre-existing Phase-7 i18n keys for rateLimit/tokenBudget/iterationCap/outputScannerPatterns
  are now dead (body shape differs from plan assumption). Cleanup candidate for a
  housekeeping quick-task, not an execution-time fix.
- `ParametersDetailYamlPreviewTest` skeleton (from 07-07a) is `@Disabled` — fills in under 07-07b.
- Runtime UI verification (Playwright) deferred to 07-07b / human gate; this plan ships
  static compile + grep contract only, matching the plan's `<verify><automated>` clause.

## Self-Check: PASSED

Verified:
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersDetailView.java` → FOUND
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/parameters/parameters-detail-view.xml` → FOUND
- Commit `28fa69c` (Task 1) → FOUND in git log
- Commit `5d8b86b` (Task 2) → FOUND in git log
- compileJava green
