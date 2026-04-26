---
phase: 05-rag-layer
fixed_at: 2026-04-20T14:15:00Z
review_path: .planning/phases/05-rag-layer/05-REVIEW.md
iteration: 1
findings_in_scope: 5
fixed: 5
skipped: 0
status: all_fixed
---
# Phase 05: Code Review Fix Report

**Fixed at:** 2026-04-20T14:15:00Z
**Source review:** .planning/phases/05-rag-layer/05-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 5 (1 critical + 4 warnings)
- Fixed: 5
- Skipped: 0

## Fixed Issues

### CR-01: Upload accepts arbitrary server-side resource URIs

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/config/AiAgentRagProperties.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentUploadServiceTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AsyncIngestionWorkerTest.java`
**Commit:** 58c8dd0
**Applied fix:** Added `AiAgentRagProperties.Upload` record with a `classpathAllowedPrefixes` allowlist (default `classpath:ai-kb/`) and required `fileStagingRoot` for `file:` URIs. `KnowledgeDocumentUploadService.upload()` now calls `validateSourceUri()` before persistence, rejecting `file:/etc/passwd`, `file:/tmp/application.properties`, `classpath:application.properties`, and any non-classpath/file scheme. Added four negative unit tests covering those cases.

### WR-01: Reingest clears the cancellation flag before the previous worker can observe it

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/CancellationRegistryTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AsyncIngestionWorkerTest.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentServiceTest.java`
**Commit:** e17a0e2
**Applied fix:** Replaced the single-boolean cancellation flag with a per-document monotonic generation. Worker captures `currentGeneration(id)` at entry and polls `isCancelled(id, generation)` at each batch boundary. `reingest()` now calls `bumpGeneration(id)` (instead of `cancel + clear`), which marks every older worker cancelled while leaving the newly-scheduled worker's captured generation unaffected. Added `bumpGeneration_marks_prior_generation_as_cancelled_but_not_new_generation` as race-proof coverage. Note: full integration proof with the real async executor is deferred to Phase-5 integration suite (generation semantics are unit-proven; an end-to-end concurrent reingest test would live in the `@Tag("rag-it")` tier).

### WR-02: The documented document-size guard is dead configuration

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AsyncIngestionWorkerTest.java`
**Commit:** 7af7376
**Applied fix:** Added `enforceMaxDocumentChars()` in `AsyncIngestionWorker` that reads `ragProperties.ingest().maxDocumentChars()` after Tika extraction and before splitting/embedding. Oversize documents throw `IllegalArgumentException` which the existing catch block converts into a `FAILED` status with a clear message (`"Document exceeds jmix.ai-agent.rag.ingest.max-document-chars=..."`). Atomic cleanup path ensures `vectorStore.add(...)` is skipped. Added unit test with a 1-char cap on `fixture-alpha.md` asserting `vectorStore.add` is never called and the failure message names the property.

### WR-03: Phase-5 integration coverage is excluded from normal verification

**Files modified:** `ai-agent/ai-agent/ai-agent.gradle`
**Commit:** 7090ad8
**Applied fix:** Added a `tasks.named('check')` hook that depends on `integrationTest` when `docker info` succeeds within 3 seconds. Added inline comment documenting the CI contract (CI must run `./gradlew check` on a Docker-capable host). Developer boxes without Docker get a lifecycle log notice and keep a green `check` while being pointed at `./gradlew integrationTest` as the required pre-merge step.

### WR-04: Shared RAG integration cleanup leaves order-dependent test state behind

**Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AbstractRagIntegrationTest.java`
**Commit:** 3751e60
**Applied fix:** Centralised hermetic cleanup in the base class's `@BeforeEach` and `@AfterEach`. `purgeAllVectorsAndDocuments()` now deletes every chunk regardless of `embeddingModel` slug (catches the `"other-model-not-current"` drift fixture) AND every `AiKnowledgeDocument` row. A fail-fast `assertEmptyStateOrFail()` assertion at test start catches residue from any crashed prior run, eliminating class-order dependence on the shared Testcontainers database. The legacy `deleteAllVectors()` method delegates to the new purge helper so existing subclass callers remain valid.

---

_Fixed: 2026-04-20T14:15:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
