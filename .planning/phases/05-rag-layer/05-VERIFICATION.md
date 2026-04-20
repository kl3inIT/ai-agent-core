---
phase: 05-rag-layer
verified: 2026-04-20T00:00:00Z
status: human_needed
score: 4/4 success criteria verified (code-level); 2 flagged for live-Docker / live-LLM confirmation
re_verification:
  previous_status: none
  previous_score: n/a
  gaps_closed: []
  gaps_remaining: []
  regressions: []
human_verification:
  - test: "Run ./gradlew :ai-agent:ai-agent:integrationTest on a Docker-enabled host and confirm all @Tag('rag-it') tests pass (UploadToReadyIntegrationTest, RoleScopedRetrievalIntegrationTest, AtomicDeleteIntegrationTest, FailClosedPostureIntegrationTest, IngestionRetryAndFailureIntegrationTest)."
    expected: "All rag-it tests green against pgvector/pgvector:pg16 Testcontainer. No 'extension vector is not installed' errors."
    why_human: "Docker is not available in this verification environment; @EnabledIf('isDockerAvailable') skips these tests cleanly on ./gradlew test."
  - test: "End-to-end smoke: boot a sample Jmix app with ai-agent-starter, configure a real OpenAI-compatible embedding provider + pgvector DB, upload a real PDF via the upload service, verify document reaches READY and chunks appear in AI_AGENT_KB_VECTOR_STORE with the documented metadata shape."
    expected: "Document status transitions PENDING → PROCESSING → READY; vector rows carry source, documentId, embeddingModel, allowedRoles, role_* flags."
    why_human: "Requires live LLM embedding provider and a real PostgreSQL + pgvector instance; Phase 5 deliberately defers Flow UI wiring to Phase 7."
---

# Phase 5: RAG Layer Verification Report

**Phase Goal:** Knowledge base upload + pgvector storage + role-scoped retrieval. RAG authorization is a parallel channel to Jmix security, enforced at both ingest and retrieval.
**Verified:** 2026-04-20
**Status:** human_needed (code fully verified; Docker-gated integration tests + live-LLM smoke require human execution)
**Test run:** `./gradlew :ai-agent:ai-agent:test` → **BUILD SUCCESSFUL**

## ROADMAP Success Criteria

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Admin uploads PDF → document reaches READY; chunks visible in pgvector with correct metadata | ✓ VERIFIED (code) / ? HUMAN (live) | `KnowledgeDocumentUploadService.upload` (144 LOC) + `AsyncIngestionWorker.ingest` (229 LOC) → Tika → TokenTextSplitter → `vectorStore.add` → `markReady`. `UploadToReadyIntegrationTest` covers md/pdf/txt/html matrix (Docker-gated). |
| 2 | Unit: filter-expression builder produces correct expression from role set | ✓ VERIFIED | `RetrievalFilterBuilderTest` (6 cases: admin bypass → null, non-admin OR role flags + embeddingModel AND, empty-roles → __none__ sentinel, multi-role, null auth). Runs in default `./gradlew test`. |
| 3 | Integration: admin-tagged doc NOT retrieved by user with only AiAgentUserRole | ✓ VERIFIED (code) / ? HUMAN (live) | `RoleScopedRetrievalIntegrationTest` (4 cases incl. `user_sees_alpha_only`). Per-request `VectorStoreDocumentRetriever.FILTER_EXPRESSION` threaded in `DefaultChatServiceImpl` L113-114. Docker-gated. |
| 4 | Delete-document removes AiKnowledgeDocument row + vector chunks in one transaction | ✓ VERIFIED (code) / ? HUMAN (live) | `KnowledgeDocumentService.delete` annotated `@Transactional`; order: `cancellationRegistry.cancel` → `vectorStore.delete(docFilter)` → `dataManager.remove`. `AtomicDeleteIntegrationTest` asserts rollback via `@SpyBean`. Docker-gated. |

## Observable Truths (from deliverables)

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| T1 | File-upload ingestion wires Flow UI → entity → async worker → Tika → splitter → embed → pgvector | ✓ VERIFIED | Full chain present in main sources. Flow UI itself is Phase 7. |
| T2 | Single shared EmbeddingModel bean enforced by test | ✓ VERIFIED | `EmbeddingModelBeanCollisionTest` asserts `getBeansOfType(EmbeddingModel.class).size() == 1`; passes in `./gradlew test`. |
| T3 | Chunk metadata contract: source, documentId, embeddingModel, allowedRoles (+role_* flags) | ✓ VERIFIED | `ChunkMetadata.java` defines constants; `AsyncIngestionWorker.enrich(...)` populates all five groups (asserted by `AsyncIngestionWorkerTest`). |
| T4 | RetrievalAugmentationAdvisor with per-request FILTER_EXPRESSION from CurrentAuthentication roles | ✓ VERIFIED | `RetrievalAugmentationAdvisorFactory` + `DefaultChatServiceImpl` L102-114; advisor slot HIGHEST_PRECEDENCE+250 asserted by `AdvisorOrderStructuralTest`. |
| T5 | Fail-closed: empty/missing allowedRoles filtered out for non-admin | ✓ VERIFIED | `RetrievalFilterBuilder` empty-roles branch emits `eq(documentId, "__none__")` sentinel; unit-tested. `FailClosedPostureIntegrationTest` (4 cases, Docker-gated). |
| T6 | CustomIngester SPI + one sample impl | ✓ VERIFIED | `spi/CustomIngester.java` interface + `IngesterManager` fan-out + `ClasspathMarkdownIngester` sample (disabled via `@ConditionalOnProperty havingValue="true"`). |
| T7 | Delete-document atomic removal of vector chunks | ✓ VERIFIED | See SC #4. |
| T8 | Ingestion status view backing queries | ✓ VERIFIED | `IngestionStatusWriter` with 5 REQUIRES_NEW methods (pending/processing/ready/failed/cancelled). Status enum extended with CANCELLED + en/vi messages. |

## Requirements Coverage

| Req | Description | Status | Evidence |
|-----|-------------|--------|----------|
| RAG-01 | Upload PDF/MD/TXT/HTML via Tika | ✓ SATISFIED | `AsyncIngestionWorker` uses `TikaDocumentReader`; format matrix in `UploadToReadyIntegrationTest` + fixtures alpha.md/beta.md/gamma.md/delta.pdf/epsilon.txt/zeta.html. |
| RAG-02 | Single shared EmbeddingModel bean | ✓ SATISFIED | `EmbeddingModelBeanCollisionTest` passes. (REQUIREMENTS.md checkbox is `[ ]` — stale; code contract is enforced.) |
| RAG-03 | Async ingestion with status PENDING/PROCESSING/READY/FAILED | ✓ SATISFIED | `@Async("aiAgentIngestExecutor")` + status writer; atomicity tested in `IngestionRetryAndFailureIntegrationTest`. |
| RAG-04 | Chunks in pgvector with source/documentId/embeddingModel/allowedRoles | ✓ SATISFIED | `AsyncIngestionWorker.enrich` + `ChunkMetadata`. |
| RAG-05 | Per-request FILTER_EXPRESSION from CurrentAuthentication | ✓ SATISFIED | `DefaultChatServiceImpl` L102-114 + `RetrievalFilterBuilder`; `ChatServiceFilterParamContractTest` is pure Mockito G-1 guard (runs in default test task). |
| RAG-06 | Untagged docs refused for non-admin (fail closed) | ✓ SATISFIED | `FailClosedPostureIntegrationTest`; `__none__` sentinel. |
| RAG-07 | CustomIngester SPI + one example | ✓ SATISFIED | `IngesterManager` + `ClasspathMarkdownIngester`; opt-in. |
| RAG-08 | Admin delete → chunks removed atomically | ✓ SATISFIED | `KnowledgeDocumentService.delete` single transaction; `AtomicDeleteIntegrationTest`. |
| SPI-07 | CustomIngester SPI implemented | ✓ SATISFIED | See RAG-07. (REQUIREMENTS.md checkbox stale.) |

**Note:** REQUIREMENTS.md shows `[ ]` for RAG-02 and SPI-07 despite code delivery. This is a **documentation lag** (checkboxes not flipped), not a missing deliverable. Flag for the REQUIREMENTS.md maintainer to flip.

## Key Link Verification

| From | To | Via | Status |
|------|-----|-----|--------|
| `DefaultChatServiceImpl.ask` | `RetrievalAugmentationAdvisor` | `advisorSpec.param(FILTER_EXPRESSION, ragFilter)` | ✓ WIRED (L113-114) |
| `AIAutoConfiguration` | `PgVectorStore` on `AI_AGENT_KB_VECTOR_STORE` | `.vectorTableName(...).initializeSchema(false)` | ✓ WIRED (L85-89) |
| `AIConfiguration` | `aiAgentIngestExecutor` ThreadPool | `@Bean(name=...)` + `@EnableAsync` | ✓ WIRED |
| `AsyncIngestionWorker` | `aiAgentIngestExecutor` | `@Async("aiAgentIngestExecutor")` | ✓ WIRED (L105) |
| `KnowledgeDocumentService.delete` | `VectorStore` + `DataManager` | single `@Transactional` method; cancel → vectorStore.delete → remove | ✓ WIRED (L82/86/92) |
| `KnowledgeDocumentUploadService` | `RoleRepository.findRoleByCode` | pre-persist role validation, throws `UnknownRoleCodeException` | ✓ WIRED |
| `ClasspathMarkdownIngester` | `@ConditionalOnProperty jmix.ai-agent.rag.sample-ingester.enabled=true` | default off in module.properties | ✓ WIRED |

## Anti-Patterns / Risks

| Severity | Item | Impact |
|----------|------|--------|
| ℹ️ Info | `markReady(chunkCount)` does not persist chunkCount (no column in Phase 2 entity); value logged only. | Minor observability gap. Tracked as deferred (needs Liquibase). |
| ℹ️ Info | D-19 UUIDv5 synthetic ids deferred — re-running a CustomIngester may create duplicate `AiKnowledgeDocument` rows. Operator workaround: delete-then-reingest. Documented in `IngesterManager` Javadoc. | Operational, not functional. |
| ℹ️ Info | REQUIREMENTS.md has stale `[ ]` checkboxes for RAG-02 and SPI-07 despite delivery. | Documentation drift only. |
| ⚠️ Warning | Docker-gated integration tests (`@Tag('rag-it')`) cannot run in this verification environment. Their harness/assertions were reviewed at source level and are sound (Testcontainers + pgvector/pgvector:pg16 image, `RagItTestApp` avoids HSQL DataSource collision, `SyncTaskExecutor` override for deterministic assertions). | Real behaviour under pgvector requires human run on a Docker host. |
| ℹ️ Info | `@SpyBean` is deprecated in Spring Boot 3.4+; a migration sweep to `@MockitoSpyBean` is deferred across all Phase 4/5 spy tests. | Compile-green today; future upgrade work. |

## Test Execution

- `./gradlew :ai-agent:ai-agent:test` → **BUILD SUCCESSFUL** (4s, UP-TO-DATE caches include the Phase 5 default-task tests: `EmbeddingModelBeanCollisionTest`, `RetrievalFilterBuilderTest`, `IngestionStatusWriterTest`, `CancellationRegistryTest`, `AsyncIngestionWorkerTest`, `KnowledgeDocumentUploadServiceTest`, `KnowledgeDocumentServiceTest`, `IngesterManagerTest`, `ChatServiceFilterParamContractTest`, `SampleIngesterDisabledByDefaultTest`).
- Integration suite (`integrationTest` task / `@Tag('rag-it')`) — **not executed here** (Docker unavailable); gated via `@EnabledIf('isDockerAvailable')`, source-level review passes.

## Verdict

**Phase 5 RAG Layer is code-complete and passes all verifiable gates.**

- 4/4 ROADMAP success criteria delivered at code level.
- All 8 observable truths verified.
- All 9 requirements (RAG-01..08, SPI-07) satisfied — modulo stale checkboxes in REQUIREMENTS.md.
- Default test task green; ingestion, retrieval, delete, fail-closed, SPI fan-out, sample-ingester default-off all have wired, substantive implementations with non-trivial test coverage.

**Remaining human gates:** (1) run `./gradlew integrationTest` on a Docker host, (2) end-to-end smoke against a real embedding provider + pgvector, (3) flip the two stale checkboxes in REQUIREMENTS.md.

---

_Verified: 2026-04-20_
_Verifier: Claude (gsd-verifier)_
