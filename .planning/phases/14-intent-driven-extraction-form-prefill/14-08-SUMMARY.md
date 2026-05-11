---
phase: 14-intent-driven-extraction-form-prefill
plan: 08
subsystem: verification
tags: [verification, scanners, evals, security, uat]

requires:
  - phase: 14-01
    provides: AiExtractionDraft entity, security, and TTL foundation
  - phase: 14-02
    provides: IntentExtractor SPI and schema synthesis
  - phase: 14-03
    provides: ExtractionService and prepare_form_draft tool payload
  - phase: 14-04
    provides: named-intent chat routing and callback gating
  - phase: 14-05
    provides: draft apply/navigation lifecycle
  - phase: 14-06
    provides: intent picker and confirm-row UI
  - phase: 14-07
    provides: host Customer reference intent
provides:
  - TEST-15 scanner for tool-side navigation leaks
  - Draft raw-setValue and host/core Customer boundary scanners
  - Deterministic extraction eval fixtures for AI-SPEC failure modes
  - Manual UAT checklist for the full chat-to-form flow
  - Final targeted verification matrix and residual module-gate report
affects: [phase-14, scanners, eval-fixtures, uat, verification-gates]

tech-stack:
  added: []
  patterns:
    - Java NIO source scanners for AI/Jmix architectural invariants
    - Deterministic YAML eval fixtures loaded through EvalFixtures
    - Manual UAT artifact with message-key based visible expectations

key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNavigationLeakScannerTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/DraftSetValueBypassScannerTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/CoreCustomerImportScannerTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/ExtractionEvaluationContractTest.java
    - ai-agent/ai-agent/src/test/resources/eval/extraction-fixtures.yaml
    - .planning/phases/14-intent-driven-extraction-form-prefill/14-UAT-CHECKLIST.md
  modified: []

key-decisions:
  - "TEST-15 scans @Tool-bearing classes, ToolContributor implementations, ToolCallbackProvider surfaces, and explicitly includes ExtractionToolBridge."
  - "OpenFormWithDraftHandler is intentionally excluded from the navigation scanner because it is the UI-side navigation owner, not a tool surface."
  - "Draft raw EntityValues.setValue remains allowed only inside DraftLoader.setValueIfPermitted."
  - "Extraction eval coverage is deterministic and non-live; it encodes AI-SPEC critical failure modes without calling a model."
  - "Full module-gate failures are recorded as residual environment/infrastructure failures because Phase 14 targeted tests pass and the failures occur in pre-existing host context/integration surfaces."

patterns-established:
  - "Scanner tests should strip comments before matching navigation or host-entity tokens."
  - "Source-scan tests should resolve module/repository roots dynamically so they work from root and module Gradle working directories."
  - "UAT checklists should reference visible message keys so locale copy remains source-of-truth."

requirements-completed:
  - EXTRACT-01
  - EXTRACT-02
  - EXTRACT-03
  - EXTRACT-04
  - EXTRACT-05
  - EXTRACT-06
  - EXTRACT-07
  - EXTRACT-08
  - EXTRACT-09
  - EXTRACT-10
  - ENT-08
  - SPI-12
  - TEST-15
  - SEC-06

duration: 2h 29m
completed: 2026-05-08
---

# Phase 14 Plan 08: Final Verification Summary

**Cross-cutting scanners, eval fixtures, UAT checklist, and final Phase 14 verification report**

## Performance

- **Duration:** 2h 29m
- **Started:** 2026-05-08T05:24:38Z
- **Completed:** 2026-05-08T07:53:51Z
- **Tasks:** 5/5
- **Files modified:** 6

## Accomplishments

- Added `ToolNavigationLeakScannerTest` to enforce TEST-15: LLM-facing tool surfaces must not import `ViewNavigators`, reference `ViewNavigators`, or call `.navigate()`.
- Added `DraftSetValueBypassScannerTest` to pin raw prefill writes to `DraftLoader.setValueIfPermitted`.
- Added `CoreCustomerImportScannerTest` to enforce the host/core boundary and keep `StreamEventRenderer` navigation-free.
- Added `extraction-fixtures.yaml` plus `ExtractionEvaluationContractTest` covering happy extraction, schema discipline, source faithfulness, denied attributes, expired drafts, exposure denial, concurrent drafts, and stale/unknown attributes.
- Added `14-UAT-CHECKLIST.md` for the post-merge manual UI path using message keys for expected visible copy.
- Ran the targeted Phase 14 add-on and host test matrix, full add-on unit suite, full host suite, and module `check` gate.

## Task Commits

1. **Task 1: Implement TEST-15 navigation leak scanner** - `a0c3b9e` (test)
2. **Task 2: Add raw setValue and core Customer import scanners** - `4bef202` (test)
3. **Task 3: Add extraction evaluation fixture contract** - `1c7e301` (test)
4. **Task 4: Run full Phase 14 verification suite and fix regressions** - verification-only; no code commit
5. **Task 5: Post-merge manual UAT checklist for UI flow** - `46ff80b` (docs)

## Files Created/Modified

- `ToolNavigationLeakScannerTest.java` - Scans add-on and host tool surfaces for navigation leakage.
- `DraftSetValueBypassScannerTest.java` - Allows exactly one raw `EntityValues.setValue` call, inside `DraftLoader.setValueIfPermitted`.
- `CoreCustomerImportScannerTest.java` - Verifies no host `Customer` reference enters add-on main sources and `StreamEventRenderer` does not navigate.
- `ExtractionEvaluationContractTest.java` - Loads deterministic eval fixtures and asserts AI-SPEC failure-mode coverage plus safe audit summaries.
- `extraction-fixtures.yaml` - Encodes Phase 14 extraction scenarios and expected audit/result behavior.
- `14-UAT-CHECKLIST.md` - Manual UI checklist for intent selection, confirm row, form prefill, save/delete, expired draft, permission denial, and payload errors.

## Decisions Made

- Kept all final verification additions as test/docs artifacts; no new runtime feature code was introduced.
- Treated full host module failures as residual infrastructure because the failing classes are pre-existing Spring/Jmix context tests and the root error is `MetaClass not found for class com.vn.jmixapp.entity.User`, unrelated to the new CustomerDraft focused tests.
- Treated `:ai-agent:ai-agent:check` integration-test failure as residual environment memory pressure because the test workers crashed with native memory allocation errors and generated `hs_err_pid*.log`; targeted and full `:ai-agent:ai-agent:test` passed under the same constrained JVM setup.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Verification Environment] Finished plan inline after executor 429**
- **Found during:** Plan execution
- **Issue:** The `14-08` executor stopped with `429 Too Many Requests` after committing Tasks 1-2 and leaving Task 3 uncommitted.
- **Fix:** Closed the executor, preserved its commits, completed Tasks 3-5 inline, and reran the required tests.
- **Files modified:** Eval fixture/test and UAT checklist files.
- **Verification:** Targeted Phase 14 tests passed; summary records full residual gates.
- **Committed in:** `1c7e301`, `46ff80b`

**2. [Rule 3 - Verification Environment] Removed generated JVM crash logs**
- **Found during:** `:ai-agent:ai-agent:check :jmix-app:check --continue`
- **Issue:** Gradle test workers generated `hs_err_pid35220.log` and `hs_err_pid4320.log` after native-memory allocation failures.
- **Fix:** Removed generated crash logs from the worktree; documented the memory failure in this summary.
- **Files modified:** None committed.
- **Verification:** `git status` clean before summary creation.
- **Committed in:** N/A

**Total deviations:** 2 auto-fixed (execution interruption, verification environment)
**Impact on plan:** No runtime behavior changed. Scanner/eval/UAT deliverables were completed; full module gates have documented residual failures.

## Issues Encountered

- `:jmix-app:test` fails broadly in pre-existing Spring/Jmix context tests (`ChatServiceToolIntegrationTest`, `CustomerMutationToolIntegrationTest`, `UserTest`, `UserUiTest`) because `UserRepository` cannot initialize: `MetaClass not found for class com.vn.jmixapp.entity.User`.
- `:ai-agent:ai-agent:check :jmix-app:check --continue` also fails because `:ai-agent:ai-agent:integrationTest` test workers hit native memory allocation failures (`The paging file is too small for this operation to complete`) and `:jmix-app:test` repeats the `User` metaclass boot failure.
- These residual failures are not introduced by the Plan 14-08 test/docs changes. The Phase 14 targeted matrix, host `CustomerDraft*` tests, and full `:ai-agent:ai-agent:test` passed.

## Verification Results

- `./gradlew --no-daemon :ai-agent:ai-agent:test --tests "*ExtractionEvaluationContractTest"` with `GRADLE_OPTS=-Xmx1024m -XX:MaxMetaspaceSize=512m` - PASS
- `./gradlew --no-daemon :ai-agent:ai-agent:test --tests "*AiExtractionDraft*" --tests "*IntentRegistry*" --tests "*MetaClassDtoSynthesizer*" --tests "*ExtractionService*" --tests "*ExtractionToolBridge*" --tests "*IntentGating*" --tests "*DraftLoader*" --tests "*OpenFormWithDraft*" --tests "*IntentCardRow*" --tests "*ToolNavigationLeakScannerTest" --tests "*DraftSetValueBypassScannerTest" --tests "*CoreCustomerImportScannerTest" --tests "*ExtractionEvaluationContractTest" --tests "*LocaleParityTest"` - PASS
- `./gradlew --no-daemon :jmix-app:test --tests "*CustomerDraft*"` - PASS
- `./gradlew --no-daemon :ai-agent:ai-agent:test` - PASS
- `./gradlew --no-daemon :jmix-app:test` - FAIL, residual host Jmix context boot failure: `MetaClass not found for class com.vn.jmixapp.entity.User`
- `./gradlew --no-daemon :ai-agent:ai-agent:check :jmix-app:check --continue` - FAIL, residual memory failure in `:ai-agent:ai-agent:integrationTest` plus same host `User` metaclass boot failure
- JetBrains file-problem checks - PASS for scanner tests; `ExtractionEvaluationContractTest` has only Java 17-compatible `get(0)` warnings intentionally skipped.
- UAT checklist file existence check - PASS

## Known Stubs

None. Eval fixtures are deterministic non-live contract cases by design and do not call a model.

## Threat Flags

- Tool-side navigation leak: guarded by `ToolNavigationLeakScannerTest`.
- Raw prefill bypass: guarded by `DraftSetValueBypassScannerTest`.
- Host/core Customer coupling: guarded by `CoreCustomerImportScannerTest`.
- Raw PII audit leakage in eval summaries: guarded by `ExtractionEvaluationContractTest`.

## User Setup Required

Manual UI verification remains pending in `14-UAT-CHECKLIST.md`. The autonomous execution path does not block phase completion on a human UAT run.

## Next Phase Readiness

All Phase 14 plans now have summaries. Phase-level verification should account for the documented residual full-module gate failures while recognizing that Phase 14 targeted tests and the full add-on unit suite pass.

## Self-Check: PASSED

- Summary file exists.
- All planned scanner/eval/UAT files exist.
- Four task commits are present in git history.
- Targeted Phase 14 add-on tests, host `CustomerDraft*` tests, and full `:ai-agent:ai-agent:test` passed.
- Residual module-gate failures are documented with owner/reason.

---
*Phase: 14-intent-driven-extraction-form-prefill*
*Completed: 2026-05-08*
