---
phase: 05-rag-layer
verified: 2026-04-20T22:15:58.6687800+07:00
status: verified
score: 4/4 success criteria verified with executed default and Docker-backed integration tests
re_verification:
  previous_status: closed_obsolete
  previous_score: 4/4 success criteria verified (code-level); 2 flagged for live-Docker / live-LLM confirmation
  gaps_closed:
    - "Executed `./gradlew :ai-agent:ai-agent:integrationTest` successfully on a Docker-enabled host."
    - "Synced REQUIREMENTS.md so delivered RAG-02 and SPI-07 are no longer shown as unchecked."
  gaps_remaining: []
  regressions: []
human_verification: []
---

# Phase 5: RAG Layer Verification Report

**Phase Goal:** Knowledge base upload + pgvector storage + role-scoped retrieval. RAG authorization is a parallel channel to Jmix security, enforced at both ingest and retrieval.
**Verified:** 2026-04-20T22:15:58.6687800+07:00
**Status:** verified
**Test runs:**
- `./gradlew :ai-agent:ai-agent:test` → **BUILD SUCCESSFUL**
- `./gradlew :ai-agent:ai-agent:integrationTest` → **BUILD SUCCESSFUL**

## ROADMAP Success Criteria

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Admin uploads PDF → document reaches READY; chunks visible in pgvector with correct metadata | ✓ VERIFIED | `UploadToReadyIntegrationTest` passed in `integrationTest`, covering the md/pdf/txt/html matrix against the Docker-backed pgvector store. |
| 2 | Unit: filter-expression builder produces correct expression from role set | ✓ VERIFIED | `RetrievalFilterBuilderTest` runs in the default `test` task and covers admin bypass, non-admin role overlap, empty-role fail-closed, multi-role, and null-auth cases. |
| 3 | Integration: admin-tagged doc NOT retrieved by user with only AiAgentUserRole | ✓ VERIFIED | `RoleScopedRetrievalIntegrationTest` passed in `integrationTest`, exercising the per-request `VectorStoreDocumentRetriever.FILTER_EXPRESSION` path against pgvector. |
| 4 | Delete-document removes AiKnowledgeDocument row + vector chunks in one transaction | ✓ VERIFIED | `AtomicDeleteIntegrationTest` passed in `integrationTest`, verifying the delete ordering and rollback-sensitive path with a spied `VectorStore`. |

## Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| T1 | File-upload ingestion wires entity → async worker → Tika → splitter → embed → pgvector | ✓ VERIFIED | `KnowledgeDocumentUploadService` + `AsyncIngestionWorker` are exercised by upload/service tests and the Docker-backed upload-to-ready suite. |
| T2 | Single shared `EmbeddingModel` bean is enforced | ✓ VERIFIED | `EmbeddingModelBeanCollisionTest` passed in the default `test` task. |
| T3 | Chunk metadata contract includes `source`, `documentId`, `embeddingModel`, `allowedRoles`, and `role_*` flags | ✓ VERIFIED | `AsyncIngestionWorkerTest` asserts the metadata shape directly. |
| T4 | Retrieval advisor uses per-request FILTER_EXPRESSION from current authentication | ✓ VERIFIED | `ChatServiceFilterParamContractTest` covers the service contract; `RoleScopedRetrievalIntegrationTest` proves the filter behavior against pgvector. |
| T5 | Fail-closed posture blocks empty-role / null-auth retrieval for non-admin callers | ✓ VERIFIED | `RetrievalFilterBuilderTest` plus `FailClosedPostureIntegrationTest` passed. |
| T6 | `CustomIngester` SPI ships with one sample implementation | ✓ VERIFIED | `IngesterManagerTest` covers SPI fan-out; `SampleIngesterDisabledByDefaultTest` verifies the sample ingester gate. |
| T7 | Delete-document removes vector chunks atomically with the entity-facing contract | ✓ VERIFIED | `AtomicDeleteIntegrationTest` passed. |
| T8 | Ingestion status transitions are persisted independently of worker failures | ✓ VERIFIED | `IngestionStatusWriterTest` covers the REQUIRES_NEW writer transitions; integration tests exercise READY / FAILED / CANCELLED flows. |

## Requirements Coverage

| Req | Description | Status | Evidence |
|-----|-------------|--------|----------|
| RAG-01 | Upload PDF/MD/TXT/HTML via Tika | ✓ SATISFIED | `UploadToReadyIntegrationTest` passed the format matrix. |
| RAG-02 | Single shared `EmbeddingModel` bean | ✓ SATISFIED | `EmbeddingModelBeanCollisionTest` passed and REQUIREMENTS.md is now synced. |
| RAG-03 | Async ingestion with status PENDING/PROCESSING/READY/FAILED | ✓ SATISFIED | `AsyncIngestionWorkerTest`, `IngestionStatusWriterTest`, and integration tests cover the lifecycle. |
| RAG-04 | Chunks in pgvector with source/documentId/embeddingModel/allowedRoles | ✓ SATISFIED | Upload/integration tests and `AsyncIngestionWorkerTest` verify the metadata contract. |
| RAG-05 | Per-request FILTER_EXPRESSION from CurrentAuthentication | ✓ SATISFIED | `RetrievalFilterBuilderTest`, `ChatServiceFilterParamContractTest`, and `RoleScopedRetrievalIntegrationTest` passed. |
| RAG-06 | Untagged docs refused for non-admin (fail closed) | ✓ SATISFIED | `FailClosedPostureIntegrationTest` passed. |
| RAG-07 | `CustomIngester` SPI + one example | ✓ SATISFIED | `IngesterManager`, `ClasspathMarkdownIngester`, and their tests are present and passing. |
| RAG-08 | Admin delete → chunks removed atomically | ✓ SATISFIED | `AtomicDeleteIntegrationTest` passed. |
| SPI-07 | `CustomIngester` SPI implemented | ✓ SATISFIED | REQUIREMENTS.md is now synced with the shipped SPI implementation. |

## Key Link Verification

| From | To | Via | Status |
|------|-----|-----|--------|
| `DefaultChatServiceImpl.ask` | `RetrievalAugmentationAdvisor` | `advisorSpec.param(FILTER_EXPRESSION, ragFilter)` | ✓ WIRED |
| `AIAutoConfiguration` | `PgVectorStore` on `AI_AGENT_KB_VECTOR_STORE` | `.vectorTableName(...).initializeSchema(false)` | ✓ WIRED |
| `AIConfiguration` | `aiAgentIngestExecutor` ThreadPool | `@Bean(name=...)` + `@EnableAsync` | ✓ WIRED |
| `AsyncIngestionWorker` | `aiAgentIngestExecutor` | `@Async("aiAgentIngestExecutor")` | ✓ WIRED |
| `KnowledgeDocumentService.delete` | `VectorStore` + `DataManager` | single `@Transactional` method; cancel → vectorStore.delete → remove | ✓ WIRED |
| `KnowledgeDocumentUploadService` | role validation | pre-persist validation, typed rejection on unknown role codes | ✓ WIRED |
| `ClasspathMarkdownIngester` | opt-in sample gate | `@ConditionalOnProperty jmix.ai-agent.rag.sample-ingester.enabled=true` | ✓ WIRED |

## Residual Risks

| Severity | Item | Impact |
|----------|------|--------|
| ℹ️ Info | `markReady(chunkCount)` does not persist `chunkCount` because the Phase 2 entity has no column for it. | Minor observability gap; requires a Liquibase follow-up if the admin UI needs it. |
| ℹ️ Info | UUIDv5 synthetic ids for repeat ingester runs remain deferred. | Re-running a `CustomIngester` can create duplicate document rows; operator workaround is delete-then-reingest. |
| ℹ️ Info | `@SpyBean` is deprecated in newer Spring Boot lines. | Current suite is green; a migration sweep to `@MockitoSpyBean` can happen later. |
| ℹ️ Info | A real embedding-provider smoke test is still valuable before release. | Extra confidence only; no longer blocking Phase 5 verification now that the Docker-backed suite passed. |

## Test Execution

- `./gradlew :ai-agent:ai-agent:test` → **BUILD SUCCESSFUL**. Default Phase 5 tests covering bean collision, filter builder, ingestion worker, status writer, upload/delete services, SPI fan-out, and sample-ingester gating are green.
- `./gradlew :ai-agent:ai-agent:integrationTest` → **BUILD SUCCESSFUL**. Docker-backed pgvector tests covering upload-to-ready, role-scoped retrieval, fail-closed posture, delete atomicity, reingest scheduling, and ingestion failure cleanup are green.

## Verdict

**Phase 5 RAG Layer is verified.**

- 4/4 ROADMAP success criteria were executed and verified.
- All 8 observable truths are verified.
- REQUIREMENTS.md is now aligned for `RAG-02` and `SPI-07`.
- Remaining optional follow-up: run one release-candidate smoke against a real embedding provider.

---

_Verified: 2026-04-20T22:15:58.6687800+07:00_
_Verifier: Claude (gsd-verifier)_
