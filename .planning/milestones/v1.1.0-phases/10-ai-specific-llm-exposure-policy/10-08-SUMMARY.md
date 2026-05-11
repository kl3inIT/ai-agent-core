---
phase: 10
plan: 08
subsystem: rag-governance-ui
tags: [knowledge-base, kb-upload, kb-row-actions, source-entity, reingest, edit-permissions, llm-exposure-policy]
requirements:
  - EXP-05
dependency-graph:
  requires:
    - 10-01 (AiKnowledgeDocument.sourceEntityName column)
    - 10-03 (AiAgentAdminRole view/menu policies)
    - 10-05 (RetrievalFilterBuilder source_entity NOT IN clause + AsyncIngestionWorker.enrich mirroring)
    - 10-06 (MetaclassComboBoxHelper shared @Component)
    - 10-07 (KB-extension message keys shipped early in Group B)
  provides:
    - KnowledgeDocumentUploadService 4-arg overload that persists sourceEntityName BEFORE dataManager.save (Phase 10 D-07)
    - KnowledgeDocumentService.updatePermissionsAndReingest orchestration (Phase 10 D-08, Fix W3)
    - UpdatePermissionsResult typed result for partial-failure routing (T-10-08 accepted)
    - KnowledgeBaseView upload sourceEntity field + edit-permissions Dialog + sourceEntity column
  affects:
    - IngesterManager (existing 3-arg upload(...) call still compiles via convenience overload)
    - KnowledgeDocumentUploadServiceTest, IngesterManagerTest, IngestionRetryAndFailureIntegrationTest, UploadToReadyIntegrationTest, AbstractRagIntegrationTest (existing 3-arg callers unbroken)
tech-stack:
  added:
    - org.springframework.lang.Nullable on KnowledgeDocumentService.updatePermissionsAndReingest
    - com.vaadin.flow.component.dialog.Dialog + CheckboxGroup + ComboBox<MetaClass> in KnowledgeBaseView
  patterns:
    - convenience-overload-with-null (existing 3-arg upload delegates to 4-arg with null sourceEntityName)
    - service-orchestrated save+reingest with typed enum result so view does NOT contain dataManager.save / try/catch (CLAUDE.md no-business-logic-in-views)
    - @Supply renderer with em-dash placeholder for nullable display field (matches Phase 9 placeholder convention)
    - shared MetaclassComboBoxHelper reused across upload form + edit-permissions Dialog (Plan 10-06 single source of truth for @SystemLevel + AI-* exclusion)
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/UpdatePermissionsResult.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/knowledge/knowledge-base-view.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - sourceEntityName persisted BEFORE dataManager.save AND BEFORE asyncIngestionWorker.ingest enqueue (Phase 10 D-07 invariant)
  - updatePermissionsAndReingest returns UpdatePermissionsResult enum (not throws) so the view branches on a type instead of catching exceptions — keeps the view as pure UI routing
  - On reingest enqueue failure the save is NOT rolled back (T-10-08 accepted) — admin retries via the existing Reingest row action without losing the saved metadata
  - Convenience 3-arg upload overload delegates to 4-arg with null to keep IngesterManager + 4 existing test callers source-compatible (no test churn)
  - View dialog uses standard Vaadin Dialog + CheckboxGroup<String> for role codes (sourced from ResourceRoleRepository.getAllRoles) + ComboBox<MetaClass> via MetaclassComboBoxHelper — same widget the upload form wires
  - Reingest-required confirmation dialog shown via dialogs.createOptionDialog BEFORE the service call (UI-SPEC Surface 3 copywriting + Phase 10 D-08 admin-explicit-consent)
  - Em-dash placeholder for null sourceEntity column (matches Phase 9 baseline-context placeholder convention; em dash U+2014 preserved per memory feedback_jmix_action_column_renderer)
metrics:
  duration: ~25min
  tasks_completed: 2
  completed_date: 2026-04-27
  files_changed: 7
---

# Phase 10 Plan 08: KnowledgeBase RAG-Governance UI Extensions Summary

Closes the RAG governance loop in the Knowledge Base view: admins now (1) set
`sourceEntityName` at upload time so chunks land in pgvector with the
`source_entity` metadata key already populated, and (2) edit roles +
source-entity post-upload via a row-action dialog that triggers reingest
through a single typed service call.

## What Was Built

**Service layer (Task 1):**

- `KnowledgeDocumentUploadService.upload(String, String, Collection<String>, String)` —
  new 4-arg overload that calls `document.setSourceEntityName(sourceEntityName)` BEFORE
  `dataManager.save(document)` (line ordering verified: setter at line 159, save at line
  167). The existing 3-arg overload now delegates to the new one with `null`, so
  `IngesterManager` and 4 existing test callers stay green. Phase 10 D-07 invariant —
  `sourceEntityName` MUST be persisted before `AsyncIngestionWorker.ingest` reloads the
  row — is now enforced at the only entry point that could violate it.
- `KnowledgeDocumentService.updatePermissionsAndReingest(UUID, Collection<String>,
  String)` — orchestrates load → set fields → save → schedule reingest. Returns the new
  `UpdatePermissionsResult` record; never throws. On reingest enqueue failure the
  save is intentionally not rolled back (T-10-08 accepted) — the doc row is correct,
  the admin retries via the Reingest row action.
- `UpdatePermissionsResult` — new record + `Status` enum
  (`SAVED_AND_REINGESTING` / `SAVED_REINGEST_FAILED`) consumed by the view layer to
  route a localized notification.

**View layer (Task 2):**

- `knowledge-base-view.xml`:
  - New `<formLayout id="uploadMetaForm">` above the `<upload>` widget hosting the
    `<comboBox id="sourceEntityNameComboBox">` (clear-button visible, optional field).
  - `<actions>` block: new `editPermissions` action of type `list_itemTracking`
    (existing `reingest` + `delete` actions unchanged).
  - New `<column key="sourceEntity">` between `status` and `createdDate`.
  - New `<button id="editPermissionsBtn">` in `rowActionsBar`.
- `KnowledgeBaseView.java`:
  - `onInit` populates `sourceEntityNameComboBox` via `MetaclassComboBoxHelper`
    (Plan 10-06) using the same `messageTools.getEntityCaption(mc) + " (" + mc.getName() + ")"`
    label format as `AiExposureRuleDetailView` for cross-view consistency.
  - The existing upload handler now reads `sourceEntityNameComboBox.getValue()` and
    passes it into `uploadService.upload(uri, kind, roles, sourceEntityName)`.
  - `onEditPermissionsClick` builds a Vaadin `Dialog` containing a `CheckboxGroup<String>`
    seeded from `ResourceRoleRepository.getAllRoles()` (pre-selected from
    `doc.getAllowedRolesJson()`) and a fresh `ComboBox<MetaClass>` (pre-selected from
    `doc.getSourceEntityName()`). The Save button delegates to
    `confirmAndSavePermissions` which shows the UI-SPEC "Reingest required" confirmation
    via `dialogs.createOptionDialog`, then calls
    `documentService.updatePermissionsAndReingest(...)` exactly once and routes the
    typed result to a localized notification.
  - `@Supply(to = "documentsDataGrid.sourceEntity", subject = "renderer")` shows
    `caption (metaClass.getName())` for non-null values and an em-dash (U+2014) for
    null (D-06 legacy-doc display contract).

**Message bundles:** added 3 new keys in EN and VI:
`knowledgeBase.notification.reingestStarted`,
`knowledgeBase.dialog.editPermissions.save`,
`knowledgeBase.dialog.editPermissions.cancel`.
The bulk of UI-SPEC KB-extension keys (`editPermissions`, `column.sourceEntity`,
`confirm.editPermissions.reingest.*`, `error.editPermissionsReingest`) were already
shipped in Plan 10-07 Group B and are consumed verbatim here.

## Plan Tasks

| # | Task | Status | Commit |
|---|------|--------|--------|
| 1 | Extend KnowledgeDocumentUploadService + KnowledgeDocumentService + UpdatePermissionsResult | Done | 4a709ab |
| 2 | KnowledgeBaseView XML + Java extensions | Done | 55dd639 |

## Acceptance Criteria

- [x] `sourceEntityName` count in upload service ≥ 2 (actual: 6 — parameter + Javadoc + setter)
- [x] `setSourceEntityName` count in upload service ≥ 1 (actual: 1, line 159, BEFORE save at line 167)
- [x] `updatePermissionsAndReingest` count in service ≥ 1 (actual: 2 — Javadoc + method body)
- [x] `SAVED_AND_REINGESTING` and `SAVED_REINGEST_FAILED` each present once in the result enum
- [x] `dataManager.save` count in `KnowledgeBaseView.java` = 0 (no business logic in view)
- [x] `updatePermissionsAndReingest` count in `KnowledgeBaseView.java` = 1 (single call site)
- [x] `setSourceEntityName` count in view = 0 (view never mutates the entity)
- [x] `result.status()` switch present in view (`SAVED_AND_REINGESTING` / `SAVED_REINGEST_FAILED`)
- [x] Vaadin `Dialog` constructed in `onEditPermissionsClick`
- [x] `sourceEntityNameComboBox`, `editPermissions`, and `sourceEntity` column present in XML
- [x] `./gradlew :ai-agent:ai-agent:compileJava` clean
- [x] `./gradlew :ai-agent:ai-agent:test` no regressions (BUILD SUCCESSFUL in 2m 7s)

> Note on `getSourceEntityName` in view (3 occurrences): all are read-only display
> calls — pre-populating the dialog ComboBox from the row, the cell renderer, and
> docstring reference. The plan's "0" criterion targets *mutation*; no
> `setSourceEntityName` exists in the view, and no `dataManager.save` exists in the
> view. The CLAUDE.md "no business logic in views" rule is fully met.

## Deviations from Plan

### Rule 2 — auto-add missing functionality: 3 message keys not pre-shipped in 10-07

**Found during:** Task 2 dialog wiring.
**Issue:** The plan referenced `knowledgeBase.notification.reingestStarted`,
`knowledgeBase.dialog.editPermissions.save`, and `knowledgeBase.dialog.editPermissions.cancel`
in code, but Plan 10-07 only shipped the UI-SPEC error/confirm keys for KB; these
three were absent from both message bundles.
**Fix:** Added all three keys to `messages_en.properties` and `messages_vi.properties`
(Vietnamese translations: "Đã lưu quyền. Đã lên lịch nạp lại.", "Lưu", "Huỷ").
**Files modified:** messages_en.properties, messages_vi.properties.
**Commit:** 55dd639.

### Rule 1 — Convenience overload (not strictly a deviation, documented per scope rule)

The plan offered "Option A overload" or "Option B request DTO". Picked Option A
(plain overload) because the existing 3-arg call site count (1 production +
4 tests) is small and the codebase has no DTO precedent for this service. The
3-arg method now delegates to the 4-arg with null, keeping every existing caller
green without test churn.

## Self-Check: PASSED

**Files verified:**
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/rag/UpdatePermissionsResult.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java
- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/view/knowledge/knowledge-base-view.xml
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java
- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties

**Commits verified:**
- FOUND: 4a709ab (Task 1)
- FOUND: 55dd639 (Task 2)

**Build verified:**
- compileJava: BUILD SUCCESSFUL
- test: BUILD SUCCESSFUL (no regressions)
