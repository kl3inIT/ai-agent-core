---
phase: 10-ai-specific-llm-exposure-policy
fixed_at: 2026-04-28T04:02:16Z
review_path: .planning/phases/10-ai-specific-llm-exposure-policy/10-REVIEW.md
iteration: 1
findings_in_scope: 8
fixed: 8
skipped: 0
status: all_fixed
---

# Phase 10: Code Review Fix Report

**Fixed at:** 2026-04-28T04:02:16Z
**Source review:** .planning/phases/10-ai-specific-llm-exposure-policy/10-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 8 (4 Critical + 4 Warning)
- Fixed: 8
- Skipped: 0

## Fixed Issues

### CR-01: BLOCKER - Denylisted Entities Leak Through Relationship Metadata

**Status:** fixed: requires human verification
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java`
**Commit:** 813ee3f
**Applied fix:** Filtered `describe_entity` relationship attributes whose target entity is not LLM-readable, and changed hidden relationship targets in `get_related_records` to return `unknown_attribute` without naming the denied target.

### CR-02: BLOCKER - Permission Edits Commit While Stale Chunks Remain Retrievable

**Status:** fixed: requires human verification
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/UpdatePermissionsResult.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentServiceTest.java`
**Commit:** d9c480e
**Applied fix:** Removed the partial-success catch path so reingest failures propagate and roll back metadata edits. Updated the UI/result type to handle failures through the existing exception path, and added a regression test for reingest failure propagation.

### CR-03: BLOCKER - Classpath Upload Allowlist Can Be Bypassed With `..`

**Status:** fixed: requires human verification
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentUploadServiceTest.java`
**Commit:** b7bf949
**Applied fix:** Added classpath URI normalization and path-traversal rejection before allowlist matching for explicit `classpath:` locations and bare filenames. Added tests for both traversal forms.

### CR-04: BLOCKER - Exposure Rule List Descriptor Has Invalid GenericFilter Configuration

**Status:** fixed
**Files modified:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/exposure/ai-exposure-rule-list-view.xml`
**Commit:** a201d57
**Applied fix:** Added the required `id="default"` to the design-time `genericFilter` configuration.

### WR-01: WARNING - Root Bundle Messages Are Looked Up With Package-Scoped Keys

**Status:** fixed
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/AiExposureRuleListView.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/view/vectorstore/VectorStoreDebugView.java`
**Commit:** a343787
**Applied fix:** Replaced class-scoped `messages.getMessage(getClass(), key)` calls with root-bundle `messages.getMessage(key)` calls in the exposure-rule and vector-store admin views.

### WR-02: WARNING - Post-Ingest Permission Edits Bypass Role Validation

**Status:** fixed: requires human verification
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentRoleValidator.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/UnknownRoleCodeException.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentServiceTest.java`
**Commit:** b110107
**Applied fix:** Extracted shared fail-closed role-code validation and applied it to both upload and post-ingest permission edits. Null, blank, stale, and unknown role codes now fail before save/reingest. Added service tests for unknown and blank role rejection.

### WR-03: WARNING - Vector Store Debug View Is Not Actually Paginated

**Status:** fixed: requires human verification
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/vectorstore/VectorStoreDebugView.java`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/vectorstore/vector-store-debug-view.xml`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties`
**Commit:** 32dbdfc
**Applied fix:** Added a localized `Load more` action that grows the vector search `topK` window by 100 rows per click and disables itself when fewer rows than the loaded limit are returned.

### WR-04: WARNING - `KnowledgeDocumentUploadService` Keeps an Unused Dependency

**Status:** fixed
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentUploadServiceTest.java`
**Commit:** a14a290
**Applied fix:** Removed the unused `AiAgentEmbeddingProperties` constructor dependency and field from the upload service and its test setup. Updated stale service commentary that claimed upload consumed embedding model state.

## Skipped Issues

None - all in-scope findings were fixed.

## Verification

- Tier 1 re-read was performed for each modified source section before commit.
- Focused checks passed:
  - `./gradlew :ai-agent:ai-agent:compileJava`
  - `./gradlew :ai-agent:ai-agent:processResources`
  - `./gradlew :ai-agent:ai-agent:test --tests com.vn.agent.rag.KnowledgeDocumentServiceTest`
  - `./gradlew :ai-agent:ai-agent:test --tests com.vn.agent.rag.KnowledgeDocumentUploadServiceTest`
  - `./gradlew :ai-agent:ai-agent:test --tests com.vn.agent.rag.KnowledgeDocumentServiceTest --tests com.vn.agent.rag.KnowledgeDocumentUploadServiceTest`
- Final combined verification passed: `./gradlew :ai-agent:ai-agent:test` - BUILD SUCCESSFUL.
- JetBrains MCP file-problem check could not be applied to the isolated temp worktree; the MCP returned only the already-open projects (`D:/DTH/ai-agent-core`, `D:/study-materials-summer-2026/EXE202/zero-mail`) instead of attaching to `C:/Users/admin/AppData/Local/Temp/sv-10-reviewfix-hlzug5`.

---

_Fixed: 2026-04-28T04:02:16Z_
_Fixer: the agent (gsd-code-fixer)_
_Iteration: 1_
