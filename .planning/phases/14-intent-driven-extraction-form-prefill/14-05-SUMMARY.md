---
phase: 14-intent-driven-extraction-form-prefill
plan: 05
subsystem: draft-navigation
tags: [draft-loader, jmix-flow-ui, access-manager, audit, lifecycle]
requires:
  - phase: 14-01
    provides: AiExtractionDraft entity, row-level ownership, TTL cleanup
  - phase: 14-03
    provides: prepare_form_draft payload and extraction draft persistence
  - phase: 14-04
    provides: named-intent chat run context and tool gating
provides:
  - Permission-gated DraftLoader for applying persisted payloadJson to tracked detail-view entities
  - UI-side OpenFormWithDraftHandler as the sole ViewNavigators owner for draft confirmation
  - Save-time draft confirmation/deletion and close-without-save TTL retention
  - Focused regression coverage for prefill, permission denial, expiry, and lifecycle behavior
affects: [chat-confirm-rendering, phase-14-ui-plan, test-15-navigation-scanner]
tech-stack:
  added: []
  patterns:
    - Secured DataManager draft reload on each confirm click
    - ComponentUtil listener registration for StandardDetailView save/close lifecycle
    - Audit rows with counts and bounded attribute names only
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftLoader.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftApplyResult.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftNotFoundException.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/DraftLoaderTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandlerTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/intent/SaveDeletesDraftTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/intent/TestMutationDetailView.java
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
key-decisions:
  - "DraftLoader is UI-free and applies payload fields only after EntityAttributeContext.canModify passes."
  - "OpenFormWithDraftHandler reloads the draft by draftId on every open call and is the only chat-intent class that imports ViewNavigators."
  - "Draft rows are marked confirmed and removed only from the StandardDetailView.AfterSaveEvent path; close events only remove listener registrations."
  - "The handler checks create permission in addition to UiShowViewContext before opening a new detail view."
patterns-established:
  - "Draft apply audit uses eventName extraction.draft_applied with draft id, counts, and capped denied attribute names only."
  - "Jmix protected detail-view lifecycle hooks are consumed through ComponentUtil.addListener from the UI-side handler."
requirements-completed: [EXTRACT-07, EXTRACT-08, EXTRACT-09, EXTRACT-10]
duration: 23min
completed: 2026-05-08
---

# Phase 14 Plan 05: Draft Apply and Navigation Lifecycle Summary

**Controller-side draft confirmation now reloads persisted drafts, checks Jmix permissions, opens the primary detail view, applies allowed fields, audits counts, and deletes drafts only after Save.**

## Performance

- **Duration:** 23 min
- **Started:** 2026-05-08T03:50:53Z
- **Completed:** 2026-05-08T04:13:38Z
- **Tasks:** 3
- **Files modified:** 10

## Accomplishments

- Added `DraftLoader.apply(...)` to load `AiExtractionDraft` through secured `DataManager`, parse `payloadJson`, coerce scalar and to-one UUID values, and apply only attributes that pass `EntityAttributeContext.canModify()`.
- Added `OpenFormWithDraftHandler` as the UI-side navigation owner using `UiShowViewContext`, `ViewRegistry.getDetailViewInfo(...)`, `ViewNavigators.detailView(...).newEntity().withViewClass(...).withAfterNavigationHandler(...)`, and `ComponentUtil.addListener(...)`.
- Added save/close lifecycle behavior: successful `AfterSaveEvent` marks the draft confirmed and deletes it; `AfterCloseEvent` without save only unregisters listeners and leaves the row for TTL cleanup.
- Added focused tests for permitted/denied/unknown/relationship prefill, expired draft handling, permission denial, save deletion, and close retention.

## Task Commits

1. **Task 1: Implement DraftLoader permission-gated prefill** - `58c9650` (`feat`)
2. **Task 2: Implement OpenFormWithDraftHandler navigation owner** - `5917cd9` (`feat`)
3. **Task 3: Add draft apply/navigation tests** - `b3358da` (`test`)

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftLoader.java` - Applies draft payloads through per-attribute permission gates and writes `extraction.draft_applied` audit rows.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftApplyResult.java` - Immutable apply-count result with bounded denied attribute names.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftNotFoundException.java` - Safe missing-draft exception for expired/saved drafts.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java` - Session-scoped controller-side handler for permission-checked detail-view navigation and draft lifecycle deletion.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` - Added draft-expired and permission-denied intent messages.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` - Added Vietnamese parity for new intent messages.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/DraftLoaderTest.java` - Unit coverage for field application, denied/skipped counts, audit safety, and to-one UUID coercion.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandlerTest.java` - Unit coverage for expired draft and permission-denied navigation outcomes.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/intent/SaveDeletesDraftTest.java` - Unit coverage for save-time deletion and close-without-save retention.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/intent/TestMutationDetailView.java` - Test detail view used to exercise the after-navigation lifecycle path.

## Verification Results

- `powershell -Command "(Select-String -Path 'ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftLoader.java' -Pattern 'EntityAttributeContext','canModify','setValueIfPermitted','extraction.draft_applied','EntityValues.setValue').Count -ge 5"` - PASS
- `powershell -Command '$hits = Get-ChildItem -Path "ai-agent/ai-agent/src/main/java/com/vn/agent/extraction" -Recurse -Filter "*.java" | Select-String -Pattern "EntityValues\.setValue"; if((($hits | Where-Object { $_.Path -notlike "*DraftLoader.java" }).Count) -ne 0){ throw "EntityValues.setValue outside DraftLoader" }'` - PASS
- `powershell -Command '$f="ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java"; foreach($p in @("ViewNavigators","UiShowViewContext","AccessManager","withAfterNavigationHandler","ComponentUtil.addListener","AfterSaveEvent","AfterCloseEvent","UiComponentUtils|getView")){ if(-not (Select-String -Path $f -Pattern $p -Quiet)){ throw "missing $p" } }'` - PASS
- `./gradlew :ai-agent:ai-agent:compileJava` - PASS
- `./gradlew :ai-agent:ai-agent:test --tests "*DraftLoaderTest" --tests "*OpenFormWithDraftHandlerTest" --tests "*SaveDeletesDraftTest"` - PASS
- `./gradlew :ai-agent:ai-agent:test --tests "*Draft*"` - PASS
- Source grep: `OpenFormWithDraftHandler` is the only class under `view/chat/intent` importing `ViewNavigators` - PASS
- Source grep: `EntityValues.setValue` occurs in the extraction package only inside `DraftLoader.setValueIfPermitted(...)` - PASS
- JetBrains MCP file-problem checks ran on touched Java files. Main source files reported no errors. Remaining test warnings were triaged as unit-test-only entity construction to avoid the known Spring context blocker, Java-17-compatible list indexing, and style-only field-local suggestions.

## Decisions Made

- `DraftLoader` skips unknown, denied, invalid, and non-settable attributes and counts them as denied/skipped rather than surfacing raw values or aborting the whole form prefill.
- To-one draft payload values are resolved from UUID strings through secured `DataManager`; target read exposure and Jmix read permission are checked before loading.
- `OpenFormWithDraftHandler` reloads the `AiExtractionDraft` row on every click/open call, preserving D-11 re-click behavior while the row exists.
- Draft deletion is intentionally restricted to `StandardDetailView.AfterSaveEvent`; close events only remove listener registrations.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Added entity create permission check before opening new detail views**
- **Found during:** Task 2
- **Issue:** The plan required `UiShowViewContext` before navigation but did not explicitly check entity create permission before opening a new detail view.
- **Fix:** Added a `CrudEntityContext` create-permission check in `OpenFormWithDraftHandler` and reuse the localized permission-denied notification when it fails.
- **Files modified:** `OpenFormWithDraftHandler.java`
- **Verification:** `./gradlew :ai-agent:ai-agent:compileJava`; focused handler tests; plan-level `*Draft*` tests.
- **Committed in:** `5917cd9`

**2. [Rule 2 - Missing Critical] Added missing localized notification keys**
- **Found during:** Task 2
- **Issue:** The handler needed user-facing draft-expired and permission-denied notifications, but the bundles only had unknown-intent/configuration-error keys from Plan 14-04.
- **Fix:** Added `chatView.intent.draftExpired` and `chatView.intent.permissionDenied` to both `messages_en.properties` and `messages_vi.properties`.
- **Files modified:** `messages_en.properties`, `messages_vi.properties`
- **Verification:** Locale key presence check; compile; focused tests.
- **Committed in:** `5917cd9`

---

**Total deviations:** 2 auto-fixed (2 missing critical)
**Impact on plan:** Both fixes are within the planned controller-side security and i18n surface; no chat rendering or selector UI was added.

## Known Stubs

None. Stub scan found no TODO/FIXME/placeholder text or empty runtime data sources in created/modified files. Null checks in source are defensive control flow, not UI/data stubs.

## Issues Encountered

- The first run of one PowerShell acceptance command expanded variables in the outer shell; rerunning with single-quoted command content passed.
- Close-event lifecycle testing initially fired Jmix internal close listeners that require a real Vaadin session. The test was narrowed to invoke only the handler-registered close listener via `ComponentUtil.getListeners(...)`, preserving the lifecycle contract without broad Spring/UI context boot.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Ready for Plan 14-06. The UI renderer can now delegate confirm-button clicks to `OpenFormWithDraftHandler` without exposing navigation to LLM/tool classes. Draft apply, permission denial, expired row behavior, save deletion, and close retention are covered.

## Self-Check: PASSED

- Summary file exists at `.planning/phases/14-intent-driven-extraction-form-prefill/14-05-SUMMARY.md`.
- Created implementation files exist: `DraftLoader.java`, `DraftApplyResult.java`, `DraftNotFoundException.java`, `OpenFormWithDraftHandler.java`.
- Created test files exist: `DraftLoaderTest.java`, `OpenFormWithDraftHandlerTest.java`, `SaveDeletesDraftTest.java`, `TestMutationDetailView.java`.
- Task commits found: `58c9650`, `5917cd9`, `b3358da`.
- No tracked-file deletions were introduced by task commits.

---
*Phase: 14-intent-driven-extraction-form-prefill*
*Completed: 2026-05-08*
