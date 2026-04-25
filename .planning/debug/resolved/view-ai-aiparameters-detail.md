---
slug: view-ai-aiparameters-detail
status: resolved
trigger: |
  <!-- DATA_START -->
  NoSuchViewException: View 'ai_AiParameters.detail' is not defined fix this
  <!-- DATA_END -->
created: 2026-04-22T14:38:50Z
updated: 2026-04-22T16:12:00Z
---

# Debug Session: view-ai-aiparameters-detail

## Symptoms

- **Expected:** Opening the AI Parameters detail view should navigate successfully.
- **Actual:** Navigation failed with `NoSuchViewException`.
- **Error:** `NoSuchViewException: View 'ai_AiParameters.detail' is not defined.`
- **Timeline:** Reported on 2026-04-22.
- **Reproduction:** Trigger Create/Edit from the Parameters list.

## Current Focus

hypothesis: confirmed.
test: inspect Parameters list/detail wiring, verify Jmix list action properties, and force explicit detail view class binding.
expecting: no action path should rely on `{entityName}.detail` auto-resolution for `AiParameters`.
next_action: resolved.

## Evidence

- timestamp: 2026-04-22T14:38:50Z — Session initialized from user report.
- timestamp: 2026-04-22T14:41:00Z — `ParametersDetailView` is registered as `@ViewController(id = "AiAgent_Parameters.detail")`.
- timestamp: 2026-04-22T14:43:00Z — Jmix 2.8 sources (`CreateAction`, `EditAction`) confirm list actions support `setViewClass()`/`setViewId()` and navigate via `DetailViewNavigator`.
- timestamp: 2026-04-22T14:46:00Z — Applied explicit action binding in `ParametersListView.onInit()`:
  - `createAction.setViewClass(ParametersDetailView.class);`
  - `editAction.setViewClass(ParametersDetailView.class);`
- timestamp: 2026-04-22T14:47:00Z — JetBrains file inspection on `ParametersListView.java` returned warnings only (no errors).
- timestamp: 2026-04-22T14:48:00Z — Focused verification passed: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.parameters.*"` (`BUILD SUCCESSFUL`).

## Eliminated

- hypothesis: the detail view class or descriptor is missing.
  reason: `ParametersDetailView` + `parameters-detail-view.xml` exist and are registered.
- hypothesis: `viewId` is invalid for `list_create`/`list_edit` in Jmix 2.8.
  reason: framework source exposes `setViewId()`/`setViewClass()` on both actions.

## Resolution

**Root cause:** Parameters list create/edit navigation still had a path that fell back to entity-default detail id resolution (`ai_AiParameters.detail`). The actual registered detail view id in this project is `AiAgent_Parameters.detail`, causing `NoSuchViewException`.
**Fix:** Bound both list actions to `ParametersDetailView.class` in `ParametersListView` so navigation no longer depends on entity-name-based id auto-resolution.
**Verification:** JetBrains inspection: no errors on modified Java file. Focused tests: `:ai-agent:ai-agent:test --tests "com.vn.agent.view.parameters.*"` passed.
**Files changed:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/parameters/ParametersListView.java`
