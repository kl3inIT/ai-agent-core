---
phase: 10-ai-specific-llm-exposure-policy
fixed_at: 2026-04-28T04:37:44Z
review_path: .planning/phases/10-ai-specific-llm-exposure-policy/10-REVIEW.md
iteration: 2
findings_in_scope: 7
fixed: 7
skipped: 0
status: all_fixed
---

# Phase 10: Code Review Fix Report

**Fixed at:** 2026-04-28T04:37:44Z
**Source review:** .planning/phases/10-ai-specific-llm-exposure-policy/10-REVIEW.md
**Iteration:** 2

**Summary:**
- Findings in scope: 7 (5 Critical + 2 Warning)
- Fixed: 7
- Skipped: 0

## Fixed Issues

### CR-01: BLOCKER - Admin RAG Bypass Skips The Exposure Denylist

**Status:** fixed: requires human verification
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderDenylistTest.java`
**Commit:** b47d798
**Applied fix:** Admin bypass now bypasses role-overlap checks only. When exposure denylist rules exist, admin retrieval still receives a model-pinned `(source_entity IS NULL OR source_entity NIN denied)` filter. Updated the denylist regression test accordingly.

### CR-02: BLOCKER - Row-Level Role Authorities Can Satisfy Resource-Role Retrieval Checks

**Status:** fixed: requires human verification
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderDenylistTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderTest.java`
**Commit:** 6e9efad
**Applied fix:** Retrieval role extraction now trusts only Jmix resource-role authorities, drops row-level authorities, filters blank mappings, and includes tests proving row-level admin/user collisions do not trigger admin bypass or role flags.

### CR-03: BLOCKER - Role Metadata Keys Are Collision-Prone

**Status:** fixed: requires human verification
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/security/RagRoleFilterNegativeTest.java`
**Commit:** a8bd04d
**Applied fix:** Role metadata keys now use UTF-8 hex encoding (`role_<hex>`) instead of lossy character replacement, with tests proving `sales-admin` and `sales_admin` no longer collide. Existing chunks using the old key scheme need reingest.

### CR-04: BLOCKER - Permission Update Reingest Uses A Nested New Transaction On The Same Row

**Status:** fixed: requires human verification
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentServiceTest.java`
**Commit:** 9905468
**Applied fix:** `updatePermissionsAndReingest()` no longer self-invokes `reingest()`. It now validates roles, purges old chunks, resets metadata/status on the document in one transaction, saves once, and schedules async ingest after commit without calling the `REQUIRES_NEW` status writer.

### CR-05: BLOCKER - Exposure Rule List Descriptor Still Has An Invalid GenericFilter Configuration

**Status:** fixed
**Files modified:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/exposure/ai-exposure-rule-list-view.xml`
**Commit:** 1411cc5
**Applied fix:** Renamed the design-time `genericFilter` configuration id from Java keyword `default` to valid identifier `defaultConfiguration`; the visible configuration name remains `default`.

### WR-01: WARNING - Edit-Permissions Error Message Now Lies About Rollback

**Status:** fixed
**Files modified:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties`
**Commit:** 7fb33f4
**Applied fix:** Updated both locale bundles so edit-permissions/reingest failure messages say permissions were not saved, and note the recovery path if chunks were already purged.

### WR-02: WARNING - Upload Validation Trims The URI But Persists The Untrimmed Value

**Status:** fixed: requires human verification
**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentUploadServiceTest.java`
**Commit:** 471bc48
**Applied fix:** Source URI validation now returns the normalized trimmed URI, and upload persists that exact value. Added a regression test for whitespace-padded classpath URIs.

## Skipped Issues

None - all in-scope findings were fixed.

## Verification

- Tier 1 re-read was performed for each modified section before commit.
- Focused tests passed for each fix area, including RAG filter tests, `KnowledgeDocumentServiceTest`, `KnowledgeDocumentUploadServiceTest`, and `LocaleParityTest`.
- Final module verification passed after all commits: `./gradlew :ai-agent:ai-agent:test` - BUILD SUCCESSFUL.
- JetBrains MCP file-problem checks ran on touched Java/XML files from the main project after source sync. The CR-05 XML descriptor reports no problems. A real touched-test Javadoc error was fixed in follow-up commit `5f5471f` (`test(10): clean denylist filter test inspection`). Remaining JetBrains findings are warnings judged non-blocking/style or pre-existing test-pattern noise.

---

_Fixed: 2026-04-28T04:37:44Z_
_Fixer: the agent (gsd-code-fixer)_
_Iteration: 2_
