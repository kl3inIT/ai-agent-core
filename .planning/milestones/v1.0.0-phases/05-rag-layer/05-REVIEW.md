---
phase: 05-rag-layer
reviewed: 2026-04-20T13:28:56.5143198Z
depth: standard
files_reviewed: 54
files_reviewed_list:
  - ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java
  - ai-agent/ai-agent/ai-agent.gradle
  - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocumentStatus.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/CancellationRegistry.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ClasspathMarkdownIngester.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/DocumentNotFoundException.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngesterManager.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngestionStatusWriter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/MdcPropagatingTaskDecorator.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/UnknownRoleCodeException.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactory.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/config/AiAgentEmbeddingProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/config/AiAgentRagProperties.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/module.properties
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AdvisorOrderStructuralTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/DualLayerParityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OrchestrationIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OwnershipOpacityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AbstractRagIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AsyncIngestionWorkerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AtomicDeleteIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/CancellationRegistryTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/FailClosedPostureIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngesterManagerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngestionRetryAndFailureIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngestionStatusWriterTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentServiceTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentUploadServiceTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RagItTestApp.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RagTestConfiguration.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RoleScopedRetrievalIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/SampleIngesterDisabledByDefaultTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/UploadToReadyIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/config/EmbeddingModelBeanCollisionTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/config/StubEmbeddingModelConfiguration.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/StubVectorStoreConfiguration.java
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-alpha.md
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-beta.md
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-delta.pdf
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-epsilon.txt
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-gamma.md
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-zeta.html
findings:
  critical: 1
  warning: 4
  info: 0
  total: 5
status: issues_found
---
# Phase 05: Code Review Report

## Critical Issues

### CR-01: Upload accepts arbitrary server-side resource URIs

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java:88-105`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java:171-184`  
**Issue:** `upload()` only checks `sourceUri` for null, then persists it verbatim. The worker later treats any `classpath:` or `file:` URI as trusted and resolves it directly through `ResourceLoader`. Any caller that can reach this service can therefore ingest arbitrary local files or packaged resources into the vector store, which is a server-side file/classpath read primitive and can expose secrets or internal configuration through retrieval. Existing tests only reject unknown schemes such as `ftp://`; there is no negative coverage for dangerous but allowed `file:`/`classpath:` inputs.  
**Fix:** Do not accept raw resolver URIs from callers. Replace `sourceUri` with a trusted upload handle, or validate `file:` paths against a configured staging root and `classpath:` against a narrow allowlist used only for built-in fixtures/ingesters. Add tests that reject values such as `file:/.../application.properties` and unexpected `classpath:` resources.

## Warnings

### WR-01: Reingest clears the cancellation flag before the previous worker can observe it

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java:105-117`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/KnowledgeDocumentServiceTest.java:153-163`  
**Issue:** `reingest()` calls `cancel(documentId)` and immediately `clear(documentId)` before deleting old chunks and scheduling the new ingest. If a prior `AsyncIngestionWorker` is still running, its next `isCancelled()` poll can see `false` and continue writing stale chunks after the purge, racing with the replacement ingest and reintroducing old or duplicate vectors. The current unit test locks in this order with mocks, so the race is not covered.  
**Fix:** Keep cancellation state generation-aware instead of using a single boolean flag. Typical fixes are a monotonically increasing ingest generation/token on the document, or leaving the old flag in place until the previous worker reaches a terminal state and starting the new worker with a fresh token. Add a concurrent integration test with the real async executor to prove stale chunks cannot reappear during reingest.

### WR-02: The documented document-size guard is dead configuration

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/config/AiAgentRagProperties.java:24-31`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java:127-131`, `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java:187-197`  
**Issue:** `AiAgentRagProperties` documents `ingest.maxDocumentChars` as a hard pre-embed guard, but the worker never reads `ragProperties.ingest()` or enforces any size ceiling before Tika parsing, splitting, or embedding. Large uploads therefore bypass the stated safety control and can still drive excessive memory usage, latency, or embedding cost. There is no test covering rejection of oversize input.  
**Fix:** Enforce the limit explicitly in `AsyncIngestionWorker`, preferably before embedding and after text extraction if byte size is not available. Fail the document with a clear message and skip `vectorStore.add(...)`. Add unit and integration coverage for an over-limit document.

### WR-03: Phase-5 integration coverage is excluded from normal verification

**File:** `ai-agent/ai-agent/ai-agent.gradle:76-107`  
**Issue:** The default `test` task explicitly excludes `@Tag("rag-it")`, and the separate `integrationTest` task is only registered, not attached to `check` or any module-local verification hook. That means the phase’s headline contracts (`upload -> READY`, role-scoped retrieval, atomic delete, failure cleanup) are not part of the normal `./gradlew test` path described in repo guidance, so regressions can merge without running the real pgvector suite.  
**Fix:** Make `integrationTest` a required verification step in CI and/or wire it into `check` when Docker is available. If the suite must stay opt-in locally, document the CI gate explicitly so these tests are still mandatory before merge.

### WR-04: Shared RAG integration cleanup leaves order-dependent test state behind

**File:** `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AbstractRagIntegrationTest.java:123-130`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RoleScopedRetrievalIntegrationTest.java:118-132`, `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/UploadToReadyIntegrationTest.java:79-82`  
**Issue:** The shared cleanup helper deletes only vectors whose `embeddingModel` equals the current model. `RoleScopedRetrievalIntegrationTest` deliberately inserts a synthetic chunk with `embeddingModel = "other-model-not-current"`, so that chunk survives cleanup and can leak into later tests. The shared base class also does not clear `AiKnowledgeDocument` rows, while `UploadToReadyIntegrationTest` asserts the table is empty after a rejected upload. With the shared Testcontainers database, that makes the suite depend on class order.  
**Fix:** Use test cleanup that removes all vectors regardless of model slug and clears `AiKnowledgeDocument` rows centrally in the base class. Add an assertion in the base cleanup path that both the vector store and document table are empty before each test begins.

## Summary

Static standard-depth review of the scoped Phase 05 files found one security-critical issue and four warning-level risks. The biggest product problems are the unrestricted resource-URI ingestion path and the reingest cancellation race; the biggest verification gaps are that the real pgvector suite is not on the default verification path and its shared cleanup is not hermetic.

Residual risk: I did not execute Gradle tasks during this review, so the report is based on code and test inspection rather than a live test run.

---

_Reviewed: 2026-04-20T13:28:56.5143198Z_  
_Reviewer: Claude (gsd-code-reviewer)_  
_Depth: standard_
