---
phase: 10-ai-specific-llm-exposure-policy
fixed_at: 2026-04-28T04:55:44Z
review_path: .planning/phases/10-ai-specific-llm-exposure-policy/10-REVIEW.md
iteration: 3
findings_in_scope: 3
fixed: 3
skipped: 0
status: all_fixed
---

# Phase 10: Code Review Fix Report

**Fixed at:** 2026-04-28T04:55:44Z
**Source review:** .planning/phases/10-ai-specific-llm-exposure-policy/10-REVIEW.md
**Iteration:** 3

**Summary:**
- Findings in scope: 3 (1 Critical + 2 Warning)
- Fixed: 3
- Skipped: 0

## Fixed Issues

### CR-01: BLOCKER - Structured Filters Can Still Traverse Denylisted Relationship Targets

**Status:** fixed
**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/filter/StructuredFilterConditionMapper.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/filter/StructuredFilterConditionMapperTest.java`
**Commit:** 8acdf76

**Applied fix:** `StructuredFilterConditionMapper` now uses `LlmExposurePolicy` for every structured-filter path hop. Denied attributes and denied relationship targets surface as `unknown_attribute` without naming the hidden entity. Added a regression test for a visible root path (`customer.name`) whose relationship target entity is denylisted.

### WR-01: WARNING - Reingest Scheduling Failure Cannot Roll Back The Metadata Edit

**Status:** fixed
**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentServiceTest.java`
**Commit:** 7285c74

**Applied fix:** The after-commit callback now catches async scheduling failures and marks the document `FAILED` through `IngestionStatusWriter`, so operators see the failed reingest state after a committed metadata update. The method Javadoc now distinguishes pre-commit rollback failures from post-commit scheduling failures. Added a regression test for the after-commit failure path.

### WR-02: WARNING - Vector Store Filter Parse Errors May Not Be Visible Inline

**Status:** fixed
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/vectorstore/VectorStoreDebugView.java`
**Commit:** 54d0f38

**Applied fix:** The vector debug filter field now clears invalid state before loading, sets `invalid=true` when filter parsing fails, and clears invalid state when the filter is cleared. Parse errors now surface through Vaadin/Jmix field validation feedback instead of only setting an error message.

## Previous Auto Iterations

- Iteration 1 fixed 8 findings (4 Critical + 4 Warning); backup report: `10-REVIEW-FIX.iter2.md`.
- Iteration 2 fixed 7 findings (5 Critical + 2 Warning); backup report: `10-REVIEW-FIX.iter3.md`.
- Iteration 3 fixed the final 3 findings from the latest review. The `--auto` cap is 3 fix passes, so no fourth re-review was run after these commits.

## Skipped Issues

None - all in-scope findings from iteration 3 were fixed.

## Verification

- Focused mapper regression: `.\gradlew.bat :ai-agent:ai-agent:test --tests com.vn.agent.filter.StructuredFilterConditionMapperTest` - BUILD SUCCESSFUL.
- Focused service regression: `.\gradlew.bat :ai-agent:ai-agent:test --tests com.vn.agent.rag.KnowledgeDocumentServiceTest` - BUILD SUCCESSFUL.
- Combined focused final-pass tests: `.\gradlew.bat :ai-agent:ai-agent:test --tests com.vn.agent.filter.StructuredFilterConditionMapperTest --tests com.vn.agent.rag.KnowledgeDocumentServiceTest` - BUILD SUCCESSFUL.
- Final module verification: `.\gradlew.bat :ai-agent:ai-agent:test` - BUILD SUCCESSFUL.
- JetBrains MCP file-problem checks:
  - `StructuredFilterConditionMapper.java` - no problems.
  - `KnowledgeDocumentService.java` - no problems.
  - `VectorStoreDebugView.java` - only non-blocking warnings (defensive null checks and field injection in a Jmix view).
  - Touched tests reported non-blocking/pre-existing test-shape warnings.

---

_Fixed: 2026-04-28T04:55:44Z_
_Fixer: codex inline final pass after isolated fixer worktree collision_
_Iteration: 3_
