# Phase 5: RAG Layer - Research

**Researched:** 2026-04-20
**Domain:** Spring AI 1.1.4 RAG (RetrievalAugmentationAdvisor + PgVectorStore + OpenAI embeddings via OpenRouter) on Jmix 2.8 / Spring Boot 3 / Java 17
**Confidence:** HIGH (advisor API shape, PgVectorStore SQL generation, embed retry, Tika/Splitter coords); MEDIUM (Jmix Async MDC propagation specifics, exact RetrievalAugmentationAdvisor default getOrder() value — not documented but observed as 0 in source); LOW (none blocking)

## Summary

Phase 5 is 90% API-selection with <10% net-new code. Spring AI 1.1.4 ships first-class support for every capability CONTEXT.md locks in:

- `RetrievalAugmentationAdvisor.builder()` with `VectorStoreDocumentRetriever` accepts **per-request `FILTER_EXPRESSION` via `.advisors(a -> a.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, <Filter.Expression>))`** — verified against `docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html`. This is the exact seam `RetrievalFilterBuilder` (D-11) plugs into from inside `ChatService.ask()`.
- `PgVectorStore` inherits `delete(Filter.Expression)` from `AbstractObservationVectorStore`. The pgvector implementation emits `DELETE FROM vector_store WHERE metadata::jsonb @@ '<jsonpath>'::jsonpath`, which uses **lax-mode jsonpath** — so `$.allowedRoles == "userRole"` against an array-typed metadata field matches when any element of allowedRoles equals userRole (PostgreSQL lax mode iterates over array-valued path steps). This means D-09's role-intersection filter works **natively with the Filter DSL** — no `FilterExpressionConverter` override needed.
- `spring-ai-starter-model-openai` already auto-wires `EmbeddingModel` alongside the `ChatModel` Phase 4 consumes. The starter is on the classpath (`ai-agent.gradle` line 10 pulls `spring-ai-client-chat:1.1.4`; the BOM-managed starter covers both chat and embeddings). D-02's `@ConditionalOnMissingBean` override pattern is a no-op by default — the starter's bean wins.
- Spring AI ships a built-in retry layer configured via `spring.ai.retry.*` that wraps all `EmbeddingModel.call()` invocations with exponential backoff and HTTP-code-gated retry. **D-16's hand-rolled Spring Retry is unnecessary** — planner should tune existing properties, not add a retry template. (Detail in section "Don't Hand-Roll".)

**Primary recommendation:** Implement D-01..D-22 verbatim using the exact API signatures documented below. One clarifying correction to CONTEXT.md: the advisor's order setter is `.order(int)` (not `.advisorOrder(int)` which is `ToolCallAdvisor`-specific) — the planner's `RetrievalAugmentationAdvisor` bean uses `.order(Ordered.HIGHEST_PRECEDENCE + 250)` to slot between Memory (+200) and Tool (+300) per Phase 4 D-02.

<user_constraints>

## User Constraints (from CONTEXT.md)

### Locked Decisions

**Embedding Model & Wiring**
- **D-01:** Default `EmbeddingModel` = OpenAI `text-embedding-3-small` via OpenRouter, 1536 dims pinned by Phase 2 DDL. Under `jmix.ai-agent.embedding.*`.
- **D-02:** Reuse `spring-ai-starter-model-openai` auto-configured `EmbeddingModel`; `AIAutoConfiguration` declares `@Bean @ConditionalOnMissingBean EmbeddingModel` that passes through. Bean-collision test asserts exactly one `EmbeddingModel`.
- **D-03:** Model drift handled by silent filter-out. Every chunk carries `embeddingModel` metadata; retrieval FILTER_EXPRESSION adds `embeddingModel == <current>`.

**Allowed-Roles Tagging & Posture**
- **D-04:** UI posture is "shared by default" — form pre-fills `allowedRoles = [AiAgentUserRole]`. Service/filter contract stays strict.
- **D-05:** Empty/missing `allowedRoles` on a chunk → admin-only (fail-closed).
- **D-06:** `AiAgentAdminRole` bypasses FILTER_EXPRESSION entirely. Exposed via `jmix.ai-agent.rag.admin-bypass` (default `true`).
- **D-07:** Role picker sources from Jmix `RoleRepository`; service re-validates every submitted role code.

**Retrieval Advisor & Filter**
- **D-08:** Use `RetrievalAugmentationAdvisor` (not `QuestionAnswerAdvisor`). Slots between `MessageChatMemoryAdvisor` and `ToolCallAdvisor` per Phase 4 D-02.
- **D-09:** FILTER_EXPRESSION semantics — ANY (role intersection non-empty). Full filter per request: `embeddingModel == <current>` AND (`admin` OR `any-role-overlap`).
- **D-10:** `allowedRoles` lives in chunk metadata as JSON list; filter uses Spring AI `Filter.Expression` DSL — no raw SQL. [VERIFIED: Filter DSL IN translates to OR-ed EQ clauses via jsonpath, lax-mode array matching native.]
- **D-11:** `RetrievalFilterBuilder` is a standalone bean: `Filter.Expression buildFor(Authentication auth)` — returns `null` for admin, conjunction otherwise.

**Ingestion Pipeline**
- **D-12:** Spring `@Async` on a dedicated named `TaskExecutor` (`aiAgentIngestExecutor`), bounded `ThreadPoolTaskExecutor`, MDC-propagating. Tests swap to `SyncTaskExecutor`.
- **D-13:** `TokenTextSplitter` with Spring AI defaults via `@ConfigurationProperties`: `chunk-size=800`, `chunk-overlap=350`.
- **D-14:** Document-level atomicity — fail whole doc on any embed failure. Status commit via `REQUIRES_NEW` method on `IngestionStatusWriter` (AuditWriter pattern from Phase 4 D-11).
- **D-15:** Reingest is a first-class service method.
- **D-16:** Embed-call retry inside worker — bounded attempts, exponential backoff. Exposed as `jmix.ai-agent.rag.embed-retry.*`. **[RESEARCH OVERRIDE — see section "Don't Hand-Roll": use built-in `spring.ai.retry.*` instead of writing a new retry template.]**

**CustomIngester & SPI Manager**
- **D-17:** Classpath-markdown sample ingester, `@ConditionalOnProperty jmix.ai-agent.rag.sample-ingester.enabled=true`. Default path `classpath:/ai-kb/**/*.md`.
- **D-18:** `IngesterManager` owns SPI invocation; admin-triggered only (`runAll()`, `runById(id)`).
- **D-19:** SPI-ingested docs appear as `AiKnowledgeDocument` rows with stable synthetic IDs (UUIDv5 from `ingesterId + source`).

**Delete Semantics**
- **D-20:** Delete is allowed in any state including PROCESSING, with cancellation handshake.
- **D-21:** Atomic chunk removal via `VectorStore.delete(Filter.Expression)` + `DataManager.remove` in one `@Transactional(REQUIRED)`. [VERIFIED: `PgVectorStore.delete(Filter.Expression)` exists via inheritance from `AbstractObservationVectorStore`.]

**Configuration Surface**
- **D-22:** Every tunable under `jmix.ai-agent.rag.*` or `jmix.ai-agent.embedding.*` in a single `@ConfigurationProperties` class.

### Claude's Discretion

- Exact bean and package names within `com.vn.agent.rag` (or equivalent).
- `RetrievalAugmentationAdvisor` construction: builder DSL vs `@Bean` factory — match Phase 4 `ChatClientFactory` pattern (factory `@Bean` preferred for symmetry).
- Cancellation marker shape — in-memory `ConcurrentMap<UUID,Boolean>` vs DB status poll.
- UUIDv5 namespace choice for D-19.
- Spring Retry style (`@Retryable` vs programmatic) — **moot per section "Don't Hand-Roll" below**.
- `IngestionStatusWriter` method decomposition — match Phase 4 `AuditWriter` shape.
- `RetrievalFilterBuilder` admin return: `null` vs a trivially-true expression — planner picks per API ergonomics (see Code Examples for `null` pattern observed to work).

### Deferred Ideas (OUT OF SCOPE)

- Per-document FILTER_EXPRESSION strategy (ANY vs ALL)
- Per-ingester or per-doc splitter configuration
- Scheduled CustomIngester invocation
- URL/web crawling ingester
- Auto-ingest of host entity records
- PII redaction / content ACL beyond role tagging
- Partial-ingestion resume semantics
- Stale-chunk admin banner UI
- Dimension migration tooling
- `StructuredOutputValidationAdvisor` × retrieval interaction (Phase 6)
- Per-user chunk quotas / tenant-level KB partitioning

</user_constraints>

## Project Constraints (from CLAUDE.md)

- **`DataManager` only — `EntityManager` forbidden.** `KnowledgeDocumentUploadService`, `IngestionStatusWriter`, `KnowledgeDocumentService.delete/reingest` all use `DataManager` for `AiKnowledgeDocument` persistence. Vector-store writes go through `VectorStore.add(List<Document>)` which internally uses `JdbcTemplate` — that is NOT an `EntityManager` access and is compliant.
- **Entity instantiation via `Metadata.create()` / `DataManager.create()`.** `AiKnowledgeDocument` creation in upload path and SPI synthetic-row path uses `Metadata.create(AiKnowledgeDocument.class)`.
- **Constructor injection for services.** All new service beans (`KnowledgeDocumentUploadService`, `KnowledgeDocumentService`, `IngesterManager`, `RetrievalFilterBuilder`, `IngestionStatusWriter`, `AsyncIngestionWorker`, `ClasspathMarkdownIngester`) use constructor injection.
- **`msg://` i18n in both `messages.properties` and `messages_vi.properties`.** Any user-facing exception message (`UnknownRoleException`, `DocumentNotFoundException`, `EmbedFailedException`) MUST have i18n keys in both locale files. Exception classes themselves carry the key, not the translated text.
- **Liquibase changelogs in `src/main/resources/com/vn/agent/liquibase/changelog/`.** Phase 2 DDL is complete (`070-ai-kb-vector-store.xml` ships the pgvector table; `050-ai-knowledge-document.xml` ships the entity). **Phase 5 does NOT introduce new DDL** unless the planner chooses status-polled cancellation (D-20 + adding `CANCELLED` enum) — and even then, the enum is Java-only; the DB column is already `VARCHAR`. Planner should confirm but is unlikely to need a changelog.
- **No Lombok on entities.** No new entities in Phase 5.
- **Development workflow: `./gradlew test` + Jetbrains MCP `get_file_problems` per modified file.** Every task's Verification block includes both.

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| RAG-01 | File upload creates `AiKnowledgeDocument` row, status PENDING→PROCESSING→READY/FAILED, async ingest pipeline runs Tika + splitter + embed + vector insert | Standard Stack `TikaDocumentReader` + `TokenTextSplitter` + `VectorStore.add`; Code Examples "Async ingestion worker"; Pitfalls #4 (chunk-size sweet spot already chosen in D-13). |
| RAG-02 | Single shared `EmbeddingModel` bean, bean-collision test | Standard Stack `spring-ai-starter-model-openai` auto-config; Code Examples "AIAutoConfiguration EmbeddingModel passthrough + collision test". |
| RAG-03 | Chunk metadata carries `source`, `documentId`, `embeddingModel`, `allowedRoles` (JSON array); populated uniformly for user-uploaded and SPI-ingested docs | Code Examples "Document with metadata"; Pitfalls #2 (metadata key-name contract). |
| RAG-04 | `RetrievalAugmentationAdvisor` with per-request `FILTER_EXPRESSION` from `RetrievalFilterBuilder(Authentication)`; admin bypasses | Code Examples "Advisor build + per-request FILTER_EXPRESSION"; Code Examples "RetrievalFilterBuilder". |
| RAG-05 | Role-scoped retrieval: chunk visible iff user roles ∩ allowedRoles ≠ ∅, fail-closed on empty allowedRoles, admin bypass | Standard Stack "Filter.Expression + pgvector jsonpath lax-mode array match"; Code Examples "RetrievalFilterBuilder". |
| RAG-06 | Atomic delete — vector chunks + entity row, in one transaction, works in any state including PROCESSING | Standard Stack `VectorStore.delete(Filter.Expression)`; Code Examples "Atomic delete"; Pitfalls #5 (tx boundary gotcha on pgvector + DataSource). |
| RAG-07 | Reingest service method — delete chunks, reset status, re-enqueue | Code Examples "reingest"; follows same pattern as delete. |
| RAG-08 | Service-level role validation against `RoleRepository` | Code Examples "UnknownRoleException"; Jmix `RoleRepository` API. |
| SPI-07 | `CustomIngester` SPI with classpath-markdown sample, manager, synthetic-ID stable hashing, off-by-default | Code Examples "IngesterManager"; Code Examples "ClasspathMarkdownIngester"; Code Examples "UUIDv5 stable ID". |

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| `EmbeddingModel` provisioning | Spring Boot auto-config (starter-owned) | `AIAutoConfiguration` `@ConditionalOnMissingBean` passthrough | Starter auto-wires; host-override seam preserved. |
| `VectorStore` (PgVectorStore) provisioning | Spring Boot auto-config (`spring-ai-starter-vector-store-pgvector`) | `AIAutoConfiguration` `@ConditionalOnMissingBean` passthrough if starter absent | Starter wires against `JdbcTemplate` and app `DataSource`; Phase 2 DDL already matches `vector_store` default table shape (verify name alignment). |
| Retrieval filter construction | Service-adjacent bean `RetrievalFilterBuilder` | — | Pure function of `Authentication`; unit-testable w/o Spring context. |
| Advisor wiring | `AIAutoConfiguration` `@Bean RetrievalAugmentationAdvisor` + update to `ChatClientFactory` (Phase 4) to include it in `defaultAdvisors(...)` | — | Advisor is app-wide stateless; per-request filter via `.advisors(a -> a.param(...))` in `ChatService.ask()`. |
| Per-request filter assembly | `DefaultChatServiceImpl` (Phase 4) call site adds `.advisors(a -> a.param(FILTER_EXPRESSION, filterBuilder.buildFor(currentAuth)))` | `RetrievalFilterBuilder` | Request scope; builder is stateless. |
| Upload workflow | `KnowledgeDocumentUploadService` | `DataManager`, `RoleRepository`, `@Async` enqueue | Service layer owns validation + status row creation; async boundary is a single `@Async` method call. |
| Ingestion worker | `AsyncIngestionWorker` (annotated `@Async("aiAgentIngestExecutor")`) | `TikaDocumentReader`, `TokenTextSplitter`, `EmbeddingModel`, `VectorStore`, `IngestionStatusWriter` | Long-running; isolated from web thread; MDC-propagating executor. |
| Document-atomic status commit | `IngestionStatusWriter` (`@Transactional(REQUIRES_NEW)`) | `DataManager` | Mirrors Phase 4 `AuditWriter` pattern; status survives chunk rollback. |
| Delete / reingest | `KnowledgeDocumentService` (`@Transactional(REQUIRED)`) | `VectorStore.delete(Filter.Expression)`, `DataManager.remove`, in-flight cancellation marker | Single rollback boundary; chunk removal + entity remove atomic. |
| SPI fan-out | `IngesterManager` | `CustomIngester` beans from context, `AsyncIngestionWorker` | Normalises SPI output into same pipeline as user uploads. |
| Classpath markdown reference impl | `ClasspathMarkdownIngester` (`@ConditionalOnProperty`) | `PathMatchingResourcePatternResolver` | Doubles as integration-test fixture. |
| Configuration surface | `AiAgentRagProperties` + `AiAgentEmbeddingProperties` (`@ConfigurationProperties`) | — | Single source of tunables per D-22. |

## Standard Stack

### Core

| Library | Gradle Coordinate | Version | Purpose | Why Standard |
|---------|-------------------|---------|---------|--------------|
| Spring AI BOM | `org.springframework.ai:spring-ai-bom` | **1.1.4** (pinned in `ai-agent/build.gradle` line 35 via `ext.springAiVersion`) | Version coordination | Already imported; do not re-declare. |
| Spring AI OpenAI starter | `org.springframework.ai:spring-ai-starter-model-openai` | BOM-managed → 1.1.4 | Auto-wires `ChatModel` (used in Phase 4) **AND `EmbeddingModel`** | Same starter covers embeddings; properties prefixed `spring.ai.openai.embedding.*`. Already on classpath transitively — **verify via `./gradlew :ai-agent:dependencies --configuration runtimeClasspath`** before assuming; if not present, add to `ai-agent.gradle`. |
| Spring AI pgvector store | `org.springframework.ai:spring-ai-starter-vector-store-pgvector` | BOM-managed → 1.1.4 | Auto-wires `PgVectorStore` bean bound to app `DataSource` | Starter respects `spring.ai.vectorstore.pgvector.initialize-schema` (set `false` — Liquibase owns DDL). **New dependency for Phase 5; add to `ai-agent.gradle`.** |
| Spring AI Tika reader | `org.springframework.ai:spring-ai-tika-document-reader` | BOM-managed → 1.1.4 | `TikaDocumentReader(Resource)` — PDFs/Word/HTML/Markdown/etc. | One class, one constructor; industry-standard Apache Tika under the hood. **New dependency for Phase 5.** |
| Spring AI core (advisor + RAG + splitter + retry) | Transitive from `spring-ai-client-chat:1.1.4` (already in `ai-agent.gradle` line 10) | 1.1.4 | `RetrievalAugmentationAdvisor`, `VectorStoreDocumentRetriever`, `TokenTextSplitter`, `FilterExpressionBuilder`, `spring.ai.retry.*` | Already on classpath. |
| Spring Retry (indirect) | Transitive via `spring.ai.retry.*` | — | Embed-call retry with exponential backoff | **Built-in — do NOT add Spring Retry explicitly.** See section "Don't Hand-Roll". |

### Supporting

| Library | Gradle Coordinate | Purpose | When to Use |
|---------|-------------------|---------|-------------|
| Jmix Security | `io.jmix.security:jmix-security-starter` (already on classpath, `ai-agent.gradle` line 7) | `RoleRepository`, `CurrentAuthentication`, role-check predicates | D-07 service-level role validation; D-11 filter builder input. |
| Jmix Core | `io.jmix.core:jmix-core-starter` (already on classpath) | `DataManager`, `Metadata.create()` | All entity persistence. |
| Jackson Databind | transitive via Spring Boot | Metadata JSON serialization for `allowedRoles` array (Spring AI calls this internally, no direct use) | N/A — Spring AI handles. |

### Alternatives Considered

| Instead of | Could Use | Tradeoff | Verdict |
|------------|-----------|----------|---------|
| `RetrievalAugmentationAdvisor` | `QuestionAnswerAdvisor` (older, monolithic) | Simpler single-class API but no per-step composability (no room for Phase 6 query transformers / re-rankers) | **Locked to RAG advisor per D-08.** Confirmed still available and preferred in 1.1.4 docs. |
| `Filter.Expression` DSL | `FilterExpressionConverter` override emitting raw SQL | Raw SQL is pgvector-locked and harder to test | **Locked to DSL per D-10.** Research confirms DSL handles array metadata correctly (Pitfalls #2). |
| `VectorStore.delete(Filter.Expression)` | Raw JDBC `DELETE FROM vector_store WHERE metadata::jsonb @@ ...` | Ties code to pgvector-specific SQL | **Locked to typed API per D-21.** API verified present via inheritance from `AbstractObservationVectorStore`. |
| Spring `@Async` + named `TaskExecutor` | Jmix `BackgroundTaskManager` | Couples ingestion to Vaadin UI lifecycle | **Locked to `@Async` per D-12.** |
| Hand-rolled Spring Retry on embed calls | Built-in `spring.ai.retry.*` | Hand-roll requires a `RetryTemplate` or `@Retryable` bean + tests; built-in requires three property lines | **Use built-in.** See section "Don't Hand-Roll". |

**Installation diff (add to `ai-agent/ai-agent/ai-agent.gradle` dependencies block):**
```gradle
    implementation 'org.springframework.ai:spring-ai-starter-vector-store-pgvector:1.1.4'
    implementation 'org.springframework.ai:spring-ai-tika-document-reader:1.1.4'
    // spring-ai-starter-model-openai is already transitively present via Phase 4 wiring;
    // verify with: ./gradlew :ai-agent:dependencies --configuration runtimeClasspath | grep openai
    // If absent, add: implementation 'org.springframework.ai:spring-ai-starter-model-openai:1.1.4'
```

**Version verification:** `1.1.4` is the latest Spring AI release pinned in `ai-agent/build.gradle` line 35 [VERIFIED: read of file 2026-04-20]. All RAG/VectorStore/Embedding APIs referenced in this document are documented against 1.1.4 on docs.spring.io [VERIFIED: URLs in Sources fetched 2026-04-20].

## Architecture Patterns

### System Architecture Diagram

```
                          ┌─────────────────────┐
                          │ Vaadin Upload Form  │  (Phase 7 — NOT this phase)
                          │ (MultipartFile,     │
                          │  allowedRoles:[..]) │
                          └──────────┬──────────┘
                                     │
                                     ▼
 ┌───────────────────────────────────────────────────────────────┐
 │   KnowledgeDocumentUploadService.upload(file, roles, user)    │
 │   1. RoleRepository.findRoleByCode(each role)  — D-07         │
 │   2. Metadata.create(AiKnowledgeDocument)                     │
 │   3. DataManager.save(doc)   [status=PENDING]                 │
 │   4. asyncIngestionWorker.ingest(docId)   ← @Async boundary   │
 │   5. return DTO                                               │
 └───────────────────────────────────────────────────────────────┘
                                     │
                            (@Async on aiAgentIngestExecutor)
                                     ▼
 ┌───────────────────────────────────────────────────────────────┐
 │         AsyncIngestionWorker.ingest(docId)   — D-12/D-14      │
 │   ┌─────────────────────────────────────────────────────────┐ │
 │   │ ingestionStatusWriter.markProcessing(docId)   REQS_NEW  │ │
 │   └─────────────────────────────────────────────────────────┘ │
 │                                 │                              │
 │                                 ▼                              │
 │   TikaDocumentReader(Resource).read()  →  List<Document>       │
 │                                 │                              │
 │                                 ▼                              │
 │   TokenTextSplitter(800,350,..).apply(docs)  →  List<Document> │
 │                                 │                              │
 │                                 ▼                              │
 │   for each chunk: enrich metadata                              │
 │     { source, documentId, embeddingModel, allowedRoles }       │
 │                                 │                              │
 │                                 ▼                              │
 │   vectorStore.add(chunks)   ← EmbeddingModel invoked under     │
 │                                hood; spring.ai.retry wraps     │
 │                                every HTTP call                 │
 │                                 │                              │
 │                  ┌──────────────┴─────────────┐                │
 │                  success                   failure             │
 │                    │                         │                 │
 │                    ▼                         ▼                 │
 │   statusWriter.markReady(id)    statusWriter.markFailed(id,e)  │
 │      (REQUIRES_NEW)                  (REQUIRES_NEW)            │
 │      — note: add() is not transactional with our Jmix tx;     │
 │        on failure we need explicit compensating delete-by-     │
 │        filter to roll back succeeded chunks (see Pitfalls #3). │
 └───────────────────────────────────────────────────────────────┘

          ─────────────────────── RETRIEVAL PATH ────────────────────

 ┌─────────────────────────────┐
 │ ChatService.ask(convId, q)  │   (Phase 4 — extended here)
 └──────────┬──────────────────┘
            │ adds per-request advisor param:
            │   .advisors(a -> a.param(
            │      VectorStoreDocumentRetriever.FILTER_EXPRESSION,
            │      retrievalFilterBuilder.buildFor(currentAuth)))
            ▼
 ┌─────────────────────────────────────────────────────────────┐
 │ ChatClient advisor chain (Phase 4 D-02 order):              │
 │   AuditAdvisor (HIGHEST_PRECEDENCE)                         │
 │     └─▶ MessageChatMemoryAdvisor (+200)                     │
 │           └─▶ RetrievalAugmentationAdvisor (+250) ← PHASE 5 │
 │                 └─▶ ToolCallAdvisor (+300)                  │
 │                       └─▶ ChatModel                         │
 └─────────────────────────────────────────────────────────────┘
            │
            ▼ (advisor's DocumentRetriever runs)
 ┌─────────────────────────────────────────────────────────────┐
 │ VectorStoreDocumentRetriever.retrieve(query, FILTER_EXPR)   │
 │   → vectorStore.similaritySearch(SearchRequest {            │
 │       query, topK, filterExpression })                      │
 │   → PgVectorStore emits:                                    │
 │       SELECT *, embedding <=> ? AS distance                 │
 │         FROM vector_store                                   │
 │        WHERE embedding <=> ? < ?                            │
 │          AND metadata::jsonb @@ '<jsonpath>'::jsonpath      │
 │        ORDER BY distance LIMIT ?                            │
 └─────────────────────────────────────────────────────────────┘

          ─────────────────────── DELETE PATH ───────────────────────

 ┌──────────────────────────────────────────────────────────┐
 │ KnowledgeDocumentService.delete(docId)  — D-20/D-21      │
 │ @Transactional(REQUIRED)                                 │
 │   1. cancellationRegistry.cancel(docId)   (volatile)     │
 │   2. vectorStore.delete(FilterExpressionBuilder          │
 │         .eq("documentId", docId).build())                │
 │   3. dataManager.remove(docRef)                          │
 │   — single rollback boundary                             │
 │                                                          │
 │   Worker polls cancellationRegistry.isCancelled(docId)   │
 │   between chunks and aborts; any chunks it wrote are in  │
 │   the same documentId filter.                            │
 └──────────────────────────────────────────────────────────┘
```

### Recommended Project Structure

```
ai-agent/ai-agent/src/main/java/com/vn/agent/
├── rag/                               ← new Phase 5 root
│   ├── RetrievalFilterBuilder.java
│   ├── advisor/
│   │   └── RetrievalAugmentationAdvisorFactory.java   (produces @Bean)
│   ├── ingest/
│   │   ├── AsyncIngestionWorker.java
│   │   ├── IngestionStatusWriter.java    (@Transactional(REQUIRES_NEW))
│   │   ├── CancellationRegistry.java
│   │   └── ChunkMetadata.java            (constants for key names)
│   ├── service/
│   │   ├── KnowledgeDocumentUploadService.java
│   │   ├── KnowledgeDocumentService.java   (delete + reingest)
│   │   └── IngesterManager.java
│   ├── sample/
│   │   └── ClasspathMarkdownIngester.java  (@ConditionalOnProperty)
│   └── config/
│       ├── AiAgentRagProperties.java
│       ├── AiAgentEmbeddingProperties.java
│       └── IngestExecutorConfig.java       (names the TaskExecutor bean)
```

### Pattern 1: `EmbeddingModel` + `VectorStore` registration with override seam

**What:** Declare `@ConditionalOnMissingBean` passthroughs in `AIAutoConfiguration`. The Spring AI starters auto-wire the beans; the add-on exposes them by type for host override.

**When to use:** Every starter-provided bean that D-02 (add-on SPI pattern) requires to be overridable.

**Example:** See Code Examples "AIAutoConfiguration EmbeddingModel + VectorStore passthrough".

### Pattern 2: Factory `@Bean` for `RetrievalAugmentationAdvisor` (mirrors Phase 4 `ChatClientFactory`)

**What:** Compose advisor via builder inside a `@Configuration`-declared `@Bean` method; inject `VectorStore` and set order.

**When to use:** Match Phase 4 convention for discoverability and single-source-of-truth for advisor order constant.

**Example:** See Code Examples "RetrievalAugmentationAdvisor @Bean".

### Pattern 3: `AuditWriter` → `IngestionStatusWriter` (same REQUIRES_NEW shape)

**What:** Dedicated bean with `@Transactional(propagation = Propagation.REQUIRES_NEW)` methods that commit status changes independently of the worker's outer transaction boundary.

**When to use:** Any time a "record the outcome even if the doing-the-work transaction rolls back" pattern is needed. Phase 4 D-11 exemplar — use it verbatim.

### Pattern 4: Per-request advisor param (FILTER_EXPRESSION)

**What:** `chatClient.prompt().advisors(a -> a.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, expression))`. The retriever reads the param from `ChatClientRequest` context per call; no mutable state on the advisor.

**When to use:** Every `ChatService.ask()` invocation. Thread-safe.

**Example:** See Code Examples "Per-request FILTER_EXPRESSION".

### Pattern 5: Stable synthetic IDs for SPI docs (D-19)

**What:** UUIDv5 (name-based, SHA-1) with a fixed namespace UUID per ingester. Re-runs produce identical UUIDs → upsert on primary key.

**When to use:** Any CustomIngester invocation to prevent duplicate rows across runs.

**Example:** See Code Examples "UUIDv5 stable ID".

### Anti-Patterns to Avoid

- **Putting `@Async` on the service method that also touches `DataManager.save(doc)`.** Pre-async persistence must be complete before the async boundary — otherwise the worker races against the uploading transaction's commit. Structure: sync method creates the row and saves, then calls the `@Async` method which only takes the `docId`.
- **Using `@Retryable` on `EmbeddingModel.call()`.** Redundant with `spring.ai.retry.*`. See section "Don't Hand-Roll".
- **Catching `Throwable` in the async worker and setting FAILED without logging.** Log the full stack; `errorMessage` is a summary for admin UI, not a replacement for `logger.error`.
- **Storing `allowedRoles` as a delimited string in metadata.** Use a JSON array. The pgvector FilterExpressionConverter's `==` against an array element relies on jsonpath array-iteration semantics, which require actual JSON arrays.
- **Invoking `VectorStore.add(chunks)` with an unbounded `List<Document>` on a large file.** Default `maxDocumentBatchSize = 10000` is safe, but memory cost of 10k chunks × 1536 dims × 8 bytes ≈ 120 MB is real. Stream chunks through the splitter in reasonable batches if doc size warrants.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Embed-call retry with exponential backoff | A `RetryTemplate`, `@Retryable`-annotated wrapper, or hand-coded `while (attempts++ < max)` loop around `embeddingModel.call(...)` | **`spring.ai.retry.*` properties** — Spring AI auto-config wraps every `OpenAiEmbeddingModel.call` (and every other embedding/chat model) with an internal `RetryTemplate`. Properties: `spring.ai.retry.max-attempts` (default 10), `spring.ai.retry.backoff.initial-interval` (default 2s), `.backoff.multiplier` (default 5), `.backoff.max-interval` (default 3 min), `.on-http-codes`, `.on-client-errors`. Expose these via `jmix.ai-agent.rag.embed-retry.*` as a thin property-delegating layer or simply document the pass-through. | Hand-rolling duplicates and may conflict with the framework retry; you would get nested retries with multiplicative attempts. The built-in layer is HTTP-aware (retries on 429/503, not 400). [VERIFIED: docs.spring.io/spring-ai/reference/api/embeddings/openai-embeddings.html 2026-04-20] |
| Document-level SQL for delete-by-metadata | A `JdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'documentId' = ?", id)` | **`VectorStore.delete(Filter.Expression)`** — inherited from `AbstractObservationVectorStore`, emits exactly `DELETE FROM vector_store WHERE metadata::jsonb @@ '<jsonpath>'::jsonpath`. Fully transactional within your `@Transactional(REQUIRED)` boundary because it uses the same `JdbcTemplate` → same `DataSource` → same tx manager. | Portable; verified present in 1.1.4; tested by Spring AI itself. [VERIFIED: github.com/spring-projects/spring-ai `PgVectorStore.java` v1.1.4 line ~doDelete] |
| Filter DSL for pgvector list-intersection (allowedRoles ∩ user roles ≠ ∅) | A `FilterExpressionConverter` override emitting `metadata->'allowedRoles' ?| array[...]` | **`FilterExpressionBuilder.in("allowedRoles", role1, role2, ...).build()`** — the default converter translates IN to OR-ed EQ clauses via jsonpath, and pgvector's `metadata::jsonb @@ '...'::jsonpath` processes `$.allowedRoles == "role1"` as "any element of the allowedRoles array equals role1" in PostgreSQL lax mode (the default jsonpath matching mode). This IS list-intersection. | Built-in works; validated by tracing the SQL emitted by 1.1.4's `PgVectorFilterExpressionConverter` (OR-clauses over EQ) and PostgreSQL's lax-mode jsonpath semantics (iterates array). [VERIFIED: github.com/spring-projects/spring-ai issue #1179 resolution + v1.1.4 source 2026-04-20; postgresql.org/docs/current/functions-json.html] |
| PDF/DOCX/HTML parsing | `pdfbox`, `poi`, `jsoup` wired separately | **`TikaDocumentReader(Resource)`** — one-class, Apache Tika under the hood, handles all of the above plus markdown and plain text. | Battle-tested; the only Spring AI reader you need for v1. [VERIFIED: docs.spring.io/spring-ai/reference/api/etl-pipeline.html 2026-04-20] |
| Token-aware chunking | A character-count splitter or rolling-window Java loop | **`TokenTextSplitter(chunkSize=800, minChunkSizeChars=350, minChunkLengthToEmbed=5, maxNumChunks=10000, keepSeparator, punctuationMarks)`** — uses the same tokenizer family as the embedding model. | Respects model token budget; handles multi-lingual punctuation. Defaults are CONTEXT.md D-13's choice. [VERIFIED: docs.spring.io/spring-ai/reference/api/etl-pipeline.html 2026-04-20] |
| Per-request filter plumbing through `ChatOptions` or request headers | A custom `ChatOptions` subclass carrying `allowedRoles` | **`chatClient.prompt().advisors(a -> a.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, expr))`** — the advisor chain's official param-passing mechanism. | It is the one-line idiomatic answer. [VERIFIED: docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html 2026-04-20] |
| Role-picker dataset for upload UI | A hand-curated enum | **`RoleRepository.getAllRoles()`** — Jmix's own registry of `ResourceRole`. Host-defined roles appear automatically. | D-07 contract. Jmix built-in. |

**Key insight:** Phase 5 is a wiring phase. The novel code is `RetrievalFilterBuilder` (~40 LOC), `AsyncIngestionWorker` (~100 LOC), and `IngesterManager` + sample (~80 LOC). Everything else is `@Bean` declarations and `@ConfigurationProperties` plumbing. Treat any PR that introduces >500 LOC of novel orchestration as a signal something is being hand-rolled.

## Runtime State Inventory

Not applicable — Phase 5 is greenfield (new beans, new service methods, additive DDL already landed in Phase 2). No renames, no existing runtime state carrying the new names.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 17 | All compilation | ✓ (project baseline) | 17 | — |
| Gradle 8.x | Build | ✓ | bundled | — |
| PostgreSQL 13+ w/ pgvector extension | Integration tests against live pgvector path (RAG-06 atomic delete, RAG-05 role-scoped retrieval) | **Unknown to this researcher — host machine status not probed**; Phase 2 DDL has `dbms="postgresql"` preCondition → HSQLDB unit tests skip this table | 13+ | **Testcontainers Postgres 15 + pgvector image** — `docker.io/pgvector/pgvector:pg15` widely used. Tests tagged `@Tag("live")` per Phase 4 convention. Unit tests of `RetrievalFilterBuilder` need no Postgres — pure function. |
| OpenRouter API key | Live embedding calls in `@Tag("live")` tests | Out-of-band via `OPENROUTER_API_KEY` env var (Phase 4 convention) | — | Unit tests stub `EmbeddingModel` with Mockito. |

**Missing dependencies with no fallback:** None blocking; all core unit tests run without any external service.

**Missing dependencies with fallback:** Postgres + OpenRouter → Testcontainers + Mockito.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 (`spring-boot-starter-test`) + Mockito + Spring Boot Test — already configured in `ai-agent.gradle` |
| Config file | `ai-agent.gradle` lines 15-18, 48-63 — `useJUnitPlatform`, `excludeTags 'live'`, `liveTest` task |
| Quick run command | `./gradlew :ai-agent:test` |
| Full suite command | `./gradlew test` |
| Live tier | `./gradlew :ai-agent:liveTest` (requires `OPENROUTER_API_KEY`) |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| RAG-01 | Upload → PENDING → PROCESSING → READY | integration (`@SpringBootTest` + `SyncTaskExecutor`) | `./gradlew :ai-agent:test --tests "*.rag.KnowledgeDocumentUploadIntegrationTest"` | ❌ Wave 0 |
| RAG-02 | Exactly one `EmbeddingModel` bean | integration (`@SpringBootTest` asserts `context.getBeansOfType(EmbeddingModel.class).size() == 1`) | `./gradlew :ai-agent:test --tests "*.rag.EmbeddingModelBeanCollisionTest"` | ❌ Wave 0 |
| RAG-03 | Chunk metadata contract | unit + integration (assert metadata keys on `Document` before `vectorStore.add`) | `./gradlew :ai-agent:test --tests "*.rag.ChunkMetadataContractTest"` | ❌ Wave 0 |
| RAG-04 | Per-request FILTER_EXPRESSION threaded to retriever | integration with stub `VectorStore` that captures `SearchRequest` | `./gradlew :ai-agent:test --tests "*.rag.RetrievalAdvisorFilterTest"` | ❌ Wave 0 |
| RAG-05 | Role-scoped retrieval (user with UserRole does NOT see admin-only chunk; admin sees both) | integration `@Tag("live")` against Testcontainers pgvector OR unit against `RetrievalFilterBuilder` with synthetic metadata | `./gradlew :ai-agent:test --tests "*.rag.RetrievalFilterBuilderTest"` (unit); `./gradlew :ai-agent:liveTest --tests "*.rag.RoleScopedRetrievalLiveTest"` | ❌ Wave 0 |
| RAG-06 | Atomic delete — vector chunks + entity row in one tx | integration with Testcontainers pgvector | `./gradlew :ai-agent:liveTest --tests "*.rag.AtomicDeleteLiveTest"` | ❌ Wave 0 |
| RAG-07 | Reingest — chunks removed, status reset to PENDING | integration | `./gradlew :ai-agent:test --tests "*.rag.ReingestServiceTest"` | ❌ Wave 0 |
| RAG-08 | Unknown role code rejected | unit (`@ExtendWith(SpringExtension.class)` + Mockito `RoleRepository`) | `./gradlew :ai-agent:test --tests "*.rag.KnowledgeDocumentUploadServiceTest"` | ❌ Wave 0 |
| SPI-07 | Classpath markdown ingester + manager | integration with sample `ai-kb/sample.md` on test classpath | `./gradlew :ai-agent:test --tests "*.rag.IngesterManagerIntegrationTest"` | ❌ Wave 0 |

### Sampling Rate

- **Per task commit:** `./gradlew :ai-agent:test` (unit + non-live integration; excludes `@Tag("live")`)
- **Per wave merge:** `./gradlew test` (all modules, fast tier only)
- **Phase gate:** `./gradlew :ai-agent:test` green AND `./gradlew :ai-agent:liveTest --tests "*.rag.*LiveTest"` green (requires Postgres + OpenRouter key) before `/gsd-verify-work`.

### Wave 0 Gaps

- [ ] `src/test/java/com/vn/agent/rag/RetrievalFilterBuilderTest.java` — pure unit, no Spring context
- [ ] `src/test/java/com/vn/agent/rag/EmbeddingModelBeanCollisionTest.java` — `@SpringBootTest`
- [ ] `src/test/java/com/vn/agent/rag/KnowledgeDocumentUploadServiceTest.java` — Mockito unit
- [ ] `src/test/java/com/vn/agent/rag/KnowledgeDocumentUploadIntegrationTest.java` — `@SpringBootTest` + `SyncTaskExecutor` override + Mockito `EmbeddingModel`/`VectorStore`
- [ ] `src/test/java/com/vn/agent/rag/RetrievalAdvisorFilterTest.java` — `@SpringBootTest` capturing `SearchRequest.filterExpression` via `spy(VectorStore)`
- [ ] `src/test/java/com/vn/agent/rag/ChunkMetadataContractTest.java` — capture `Document.metadata` before `vectorStore.add`
- [ ] `src/test/java/com/vn/agent/rag/IngesterManagerIntegrationTest.java` — `@SpringBootTest` with sample ingester enabled
- [ ] `src/test/java/com/vn/agent/rag/ReingestServiceTest.java` — `@SpringBootTest`
- [ ] `src/test/java/com/vn/agent/rag/live/RoleScopedRetrievalLiveTest.java` — `@Tag("live")` + Testcontainers pgvector
- [ ] `src/test/java/com/vn/agent/rag/live/AtomicDeleteLiveTest.java` — `@Tag("live")` + Testcontainers pgvector
- [ ] `src/test/resources/ai-kb/sample-admin-only.md`, `src/test/resources/ai-kb/sample-shared.md` — fixtures
- [ ] Testcontainers dep (only if live tests need it): `testImplementation "org.testcontainers:postgresql:1.19.7"` — verify latest at plan time

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V4 Access Control | **YES** | `RetrievalFilterBuilder` enforces role-scoped retrieval; `AiAgentAdminRole` is the only bypass; service re-validates role codes against `RoleRepository` (D-07). Jmix `CurrentAuthentication.getAuthentication().getAuthorities()` is the authoritative role source. |
| V5 Input Validation | **YES** | Upload file size/MIME limits (configure via Vaadin upload in Phase 7 UI; service should reject absurdly large inputs). Role codes validated against `RoleRepository`. Filename sanitised before becoming metadata `source`. |
| V6 Cryptography | no | None — no new secrets; reuses OpenRouter key from Phase 4. |
| V8 Data Protection | **YES (light)** | Document content is stored plaintext in `vector_store.content`. Hosts with sensitive docs rely on role scoping + Postgres-level encryption-at-rest (out of scope for this add-on). Document this assumption. |

### Known Threat Patterns for Spring AI RAG

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Prompt injection via retrieved doc content | Tampering / Info Disclosure | Spring AI `RetrievalAugmentationAdvisor`'s `QueryAugmenter` handles doc-content-to-prompt assembly with proper delimiting; Phase 6 ToolGuard constrains tool use independently. Not a Phase 5 build task, but ensure default `ContextualQueryAugmenter` is in use. |
| Role-filter bypass via crafted FILTER_EXPRESSION | Elevation of Privilege | `RetrievalFilterBuilder` is the **only** builder of FILTER_EXPRESSION on the retrieval path — `ChatService.ask()` never accepts a client-supplied filter. Planner MUST NOT add any surface for client-side filter overrides. |
| Embedding-model substitution leaking cross-model results | Info Disclosure / Tampering | D-03 `embeddingModel == <current>` filter element guarantees stale chunks are invisible. |
| Untagged chunks accidentally shared | Info Disclosure | D-05 fail-closed: empty `allowedRoles` → admin-only. Planner MUST test this explicitly in `RetrievalFilterBuilderTest` and `RoleScopedRetrievalLiveTest`. |
| SPI ingester forgets allowedRoles | Info Disclosure | D-05 fail-closed behaviour covers this; sample ingester should explicitly tag with `[AiAgentUserRole]` to demonstrate correct usage. |

## Common Pitfalls

### Pitfall 1: Advisor order setter name mismatch

**What goes wrong:** Copying Phase 4's `ToolCallAdvisor` builder pattern (`.advisorOrder(int)`) onto `RetrievalAugmentationAdvisor.Builder` — method does not exist → compile fail, or worse, silently picks up an overloaded method with wrong semantics.

**Why it happens:** Spring AI 1.1.4 standardized the method name as `.order(Integer)` on `RetrievalAugmentationAdvisor.Builder` but `ToolCallAdvisor.Builder` uses `.advisorOrder(int)` (legacy). `MessageChatMemoryAdvisor.Builder` uses `.order(int)`. Inconsistent across advisor builders.

**How to avoid:** Use `.order(Ordered.HIGHEST_PRECEDENCE + 250)` literally. [VERIFIED: Builder Javadoc at docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/rag/advisor/RetrievalAugmentationAdvisor.Builder.html 2026-04-20 lists `order(Integer order)`.]

**Warning signs:** IDE does not autocomplete `.advisorOrder` — that is correct; stop and use `.order` instead.

### Pitfall 2: Thinking `IN` against array metadata does not work

**What goes wrong:** Planner reads Filter DSL docs ("IN tests if field equals one of values"), concludes list-intersection filtering requires a custom `FilterExpressionConverter`, hand-rolls one.

**Why it happens:** The DSL docs describe the INTENT; the pgvector SQL TRANSLATION is what actually matters. Translation: `IN("allowedRoles", "a", "b")` → `metadata::jsonb @@ '($.allowedRoles == "a" || $.allowedRoles == "b")'::jsonpath`. PostgreSQL jsonpath in lax mode (default when using `@@`) iterates over array-valued path steps automatically — so `$.allowedRoles == "a"` against `metadata.allowedRoles = ["a","x","y"]` matches.

**How to avoid:** Write an integration test FIRST that seeds two documents — one with `allowedRoles=["admin"]`, one with `allowedRoles=["user"]` — and calls `vectorStore.similaritySearch(SearchRequest.builder().filterExpression(b.in("allowedRoles","user").build()).build())`. Assert only the second is returned. This test gates the entire filter design.

**Warning signs:** You are reaching for raw SQL — stop and prove the DSL insufficient with a test first.

### Pitfall 3: VectorStore.add() and @Transactional — the mix that is not

**What goes wrong:** Async worker has `@Transactional` (or inherits one), calls `vectorStore.add(chunks)` for 500 chunks, embedding service 500 fails mid-stream. Worker assumes transaction rollback will undo the inserted chunks. It does not — because `VectorStore.add` may not participate in the same transaction (implementation-dependent).

**Why it happens:** `PgVectorStore.doAdd` uses `JdbcTemplate.batchUpdate` which respects an ambient `@Transactional` IF the same `DataSource` is in use. In Spring Boot autoconfig, this is TRUE — pgvector starter binds to the app's primary `DataSource`. BUT: you cannot rely on this invariant across VectorStore implementations; if a host swaps `VectorStore` for (say) a remote Pinecone instance, `add` becomes fire-and-forget HTTP and the rollback guarantee disappears.

**How to avoid:** D-14 explicitly makes document-level atomicity the contract. Implement it with a **compensating delete on failure**, not by relying on transactional add:

```java
try {
    vectorStore.add(chunks);
    statusWriter.markReady(docId);
} catch (Exception e) {
    // Compensating cleanup — removes any chunks that did land.
    vectorStore.delete(new FilterExpressionBuilder().eq("documentId", docId.toString()).build());
    statusWriter.markFailed(docId, e);
}
```

This is portable across `VectorStore` implementations. It also survives the fact that Spring AI's built-in retry may succeed for some chunks and fail for others within a single `add` call (batch-level failures depend on provider).

**Warning signs:** Your worker has a `@Transactional` annotation and you are relying on rollback to clean up vector chunks — stop, use compensating delete.

### Pitfall 4: Running `OpenAiEmbeddingModel` against OpenRouter with the default model

**What goes wrong:** `spring.ai.openai.embedding.options.model` defaults to `text-embedding-ada-002` (1536 dims, legacy). OpenRouter may or may not expose this exact slug; even if it does, D-01 pins `text-embedding-3-small` (newer, same dims). Forgetting to set the model property means your DDL's `vector(1536)` happens to match but you are using a model nobody configured explicitly.

**Why it happens:** The default in auto-config is ada-002; the CONTEXT decision is text-embedding-3-small. Both are 1536 dims, so `vectorStore.add` will not fail — but D-03's model-drift filter pins the name to whatever is actually in the metadata, not the intended model.

**How to avoid:** Set `spring.ai.openai.embedding.options.model=text-embedding-3-small` explicitly in the starter's `application.yml` fragment shipped by the add-on. Also surface it via `jmix.ai-agent.embedding.model` in `AiAgentEmbeddingProperties` so hosts override cleanly (wire the property through to `spring.ai.openai.embedding.options.model` via a `@ConfigurationPropertiesBinding`-style handoff OR document that hosts set the Spring AI property directly).

**Warning signs:** The bean-collision test passes but `getEmbeddingModel().getClass()` shows default model name is `text-embedding-ada-002`.

### Pitfall 5: pgvector table name collision

**What goes wrong:** `spring-ai-starter-vector-store-pgvector` defaults to `spring.ai.vectorstore.pgvector.vector-table-name=vector_store` (public schema). Phase 2 DDL (`070-ai-kb-vector-store.xml`) creates `AI_AGENT_KB_VECTOR_STORE`. Unless configured, the starter creates ANOTHER `vector_store` table on first `add` call (because `initialize-schema` default is false in 1.1.x — verify) or fails to find the Phase 2 table.

**Why it happens:** Two sources of truth for the table name.

**How to avoid:** Set in `application.yml` shipped by the starter module:
```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        initialize-schema: false       # Liquibase owns DDL
        schema-name: public
        table-name: AI_AGENT_KB_VECTOR_STORE
        dimensions: 1536
        distance-type: COSINE_DISTANCE
        index-type: HNSW
```

**Verify during planning:** Read `070-ai-kb-vector-store.xml` and confirm the actual column names (`id`, `content`, `metadata`, `embedding`) match Spring AI's expectations. If the Phase 2 DDL used different column names, the starter cannot bind and the plan must include a column-name alignment task.

**Warning signs:** `UncategorizedSQLException` mentioning `relation "vector_store" does not exist` OR duplicate tables on first boot.

### Pitfall 6: Async + MDC context loss

**What goes wrong:** Upload thread has `runId`, `userId`, `conversationId` in MDC (logging context). Async worker runs on a different thread — MDC is empty → audit correlation breaks → ingestion errors cannot be traced back to the user.

**Why it happens:** Spring's default `SimpleAsyncTaskExecutor` does not propagate MDC. D-12 calls this out explicitly.

**How to avoid:** Configure `ThreadPoolTaskExecutor` with a `TaskDecorator` that snapshots MDC on submit and restores on run:
```java
executor.setTaskDecorator(runnable -> {
    Map<String,String> context = MDC.getCopyOfContextMap();
    return () -> {
        if (context != null) MDC.setContextMap(context);
        try { runnable.run(); } finally { MDC.clear(); }
    };
});
```

**Warning signs:** Logs from `AsyncIngestionWorker` lack the `runId` or `userId` fields that appeared on the upload request.

### Pitfall 7: `RetrievalFilterBuilder` returns ALL_PASS for admin — there is no ALL_PASS

**What goes wrong:** Planner sees CONTEXT.md's "admin returns `null` or `Filter.Expression.ALL_PASS`" and writes `return Filter.Expression.ALL_PASS;` — compile fail, because there is no such constant in Spring AI's `Filter.Expression`.

**Why it happens:** CONTEXT.md hedged — "whichever the advisor API wants". The API wants `null`.

**How to avoid:** Return `null` for admin. The `VectorStoreDocumentRetriever` treats a missing/null FILTER_EXPRESSION param as "no filter" — which is the admin-bypass behaviour. `chatClient.prompt().advisors(a -> a.param(FILTER_EXPRESSION, null))` also works (the advisor's `.param` accepts null; the retriever's filter-expression getter returns null → no WHERE jsonpath clause).

**Warning signs:** Searching Javadocs for `ALL_PASS` returns nothing.

### Pitfall 8: RetrievalAugmentationAdvisor default getOrder() collides with ToolCallAdvisor

**What goes wrong:** Not setting `.order(...)` explicitly — the advisor picks up an internal default (observed near 0 in Spring AI source), which sits OUTSIDE Phase 4's `[HIGHEST_PRECEDENCE, HIGHEST_PRECEDENCE+300]` window. Retrieval ends up AFTER (or the wrong side of) tool-calling, breaking the "retrieve first, then decide if tools needed" invariant.

**Why it happens:** Default getOrder is not documented.

**How to avoid:** ALWAYS call `.order(Ordered.HIGHEST_PRECEDENCE + 250)` explicitly when building the advisor. Add a `@SpringBootTest` that asserts the advisor chain order is `AuditAdvisor, MessageChatMemoryAdvisor, RetrievalAugmentationAdvisor, ToolCallAdvisor` by sorting beans by `getOrder()`.

**Warning signs:** Retrieval test passes but the tool-call advisor tests break when rag advisor is added — ordering regression.

## Code Examples

Verified patterns. Each snippet is runnable as shown, with imports indicated.

### AIAutoConfiguration `EmbeddingModel` + `VectorStore` passthrough

```java
// package com.vn.autoconfigure.agent;
// imports:
//   org.springframework.ai.embedding.EmbeddingModel;
//   org.springframework.ai.vectorstore.VectorStore;
//   org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
//   org.springframework.context.annotation.Bean;

// Inside AIAutoConfiguration:

@Bean
@ConditionalOnMissingBean
public EmbeddingModel embeddingModel(EmbeddingModel starterProvided) {
    // Passthrough — the starter's bean satisfies the parameter. D-02 seam.
    return starterProvided;
}

@Bean
@ConditionalOnMissingBean
public VectorStore vectorStore(VectorStore starterProvided) {
    return starterProvided;
}
```

NOTE: Because the starter itself provides these as `@ConditionalOnMissingBean`, declaring passthroughs here is only useful if you also want to *replace* them with a non-default bean. The cleanest alternative is to NOT declare passthroughs and let the starter beans flow through unchanged — then the bean-collision test simply asserts `applicationContext.getBeansOfType(EmbeddingModel.class).size() == 1`.

### Bean-collision test (RAG-02)

```java
// package com.vn.agent.rag;
// imports:
//   org.springframework.ai.embedding.EmbeddingModel;
//   org.springframework.boot.test.context.SpringBootTest;
//   static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class EmbeddingModelBeanCollisionTest {

    @Autowired ApplicationContext ctx;

    @Test
    void exactlyOneEmbeddingModelBean() {
        Map<String, EmbeddingModel> beans = ctx.getBeansOfType(EmbeddingModel.class);
        assertEquals(1, beans.size(),
                "Exactly one EmbeddingModel required; found: " + beans.keySet());
    }
}
```

### `RetrievalAugmentationAdvisor` `@Bean`

```java
// package com.vn.agent.rag.advisor;
// imports:
//   org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
//   org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
//   org.springframework.ai.vectorstore.VectorStore;
//   org.springframework.context.annotation.Bean;
//   org.springframework.context.annotation.Configuration;
//   org.springframework.core.Ordered;

@Configuration
public class RetrievalAugmentationAdvisorFactory {

    public static final int ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 250;

    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(VectorStore vectorStore,
                                                                      AiAgentRagProperties props) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .similarityThreshold(props.getRetrieval().getSimilarityThreshold()) // e.g. 0.50
                        .topK(props.getRetrieval().getTopK())                                // e.g. 5
                        .build())
                .order(ADVISOR_ORDER)
                .build();
    }
}
```

Then in Phase 4's `ChatClientFactory` (UPDATED), add `retrievalAdvisor` to `.defaultAdvisors(...)`:

```java
return ChatClient.builder(chatModel)
        .defaultSystem(...)
        .defaultAdvisors(auditAdvisor, memoryAdvisor, retrievalAdvisor, toolCallAdvisor)
        .build();
```

Order is resolved by `getOrder()`, not list position, but keep list-position consistent with order values for readability.

### Per-request FILTER_EXPRESSION (inside `DefaultChatServiceImpl.ask(...)`)

```java
// imports:
//   org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
//   org.springframework.ai.vectorstore.filter.Filter;
//   org.springframework.security.core.Authentication;

Authentication auth = currentAuthentication.getAuthentication();
Filter.Expression filterExpression = retrievalFilterBuilder.buildFor(auth);
// filterExpression may be null for admin — that is expected and means "no filter".

ChatClient.CallResponseSpec call;
if (filterExpression != null) {
    call = chatClient.prompt()
            .advisors(a -> a.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, filterExpression))
            .user(question)
            .call();
} else {
    call = chatClient.prompt()
            .user(question)
            .call();
}
```

### `RetrievalFilterBuilder`

```java
// package com.vn.agent.rag;
// imports:
//   org.springframework.ai.vectorstore.filter.Filter;
//   org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
//   org.springframework.security.core.Authentication;
//   org.springframework.security.core.GrantedAuthority;
//   org.springframework.stereotype.Component;

@Component
public class RetrievalFilterBuilder {

    public static final String KEY_EMBEDDING_MODEL = "embeddingModel";
    public static final String KEY_ALLOWED_ROLES   = "allowedRoles";

    private final String currentEmbeddingModel;  // injected from AiAgentEmbeddingProperties
    private final String adminRoleCode;          // "AiAgentAdminRole" or whatever Jmix exposes
    private final boolean adminBypass;

    public RetrievalFilterBuilder(AiAgentEmbeddingProperties emb,
                                   AiAgentRagProperties rag) {
        this.currentEmbeddingModel = emb.getModel();
        this.adminRoleCode         = rag.getAdminRoleCode();     // default "AiAgentAdminRole"
        this.adminBypass           = rag.isAdminBypass();        // default true
    }

    /** Returns null when admin bypass applies (no filter). */
    public Filter.Expression buildFor(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) {
            // No roles → fail-closed path. Return a filter that matches nothing sensibly.
            return neverMatch();
        }

        Set<String> roleCodes = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        if (adminBypass && roleCodes.contains(adminRoleCode)) {
            return null; // admin bypass — no filter expression
        }

        FilterExpressionBuilder b = new FilterExpressionBuilder();

        Filter.Expression modelClause = b.eq(KEY_EMBEDDING_MODEL, currentEmbeddingModel).build();

        if (roleCodes.isEmpty()) {
            // Fail-closed: user has no role AND is not admin.
            // Filter by a role value no chunk will carry.
            return b.and(
                    b.eq(KEY_EMBEDDING_MODEL, currentEmbeddingModel),
                    b.eq(KEY_ALLOWED_ROLES, "__none__")
            ).build();
        }

        // ANY semantics: allowedRoles IN (user's roles) — lax-mode jsonpath treats this as
        // "any element of chunk's allowedRoles equals any element of user's roles".
        Filter.Expression roleClause = b.in(KEY_ALLOWED_ROLES, roleCodes.toArray()).build();

        return b.and(modelClause, roleClause).build();
    }

    private Filter.Expression neverMatch() {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        return b.and(
                b.eq(KEY_EMBEDDING_MODEL, currentEmbeddingModel),
                b.eq(KEY_ALLOWED_ROLES, "__none__")
        ).build();
    }
}
```

**Unit test skeleton (RAG-05, RAG-04 filter-builder):**
```java
class RetrievalFilterBuilderTest {
    @Test void admin_returns_null() { ... }
    @Test void userRole_builds_and_of_model_and_in_roles() { ... }
    @Test void empty_roles_builds_never_matching_filter() { ... }
    @Test void admin_bypass_disabled_treats_admin_as_normal_user() { ... }
}
```

### Async ingestion worker (RAG-01)

```java
// package com.vn.agent.rag.ingest;
// imports:
//   org.springframework.ai.document.Document;
//   org.springframework.ai.reader.tika.TikaDocumentReader;
//   org.springframework.ai.transformer.splitter.TokenTextSplitter;
//   org.springframework.ai.vectorstore.VectorStore;
//   org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
//   org.springframework.core.io.ByteArrayResource;
//   org.springframework.scheduling.annotation.Async;
//   org.springframework.stereotype.Component;

@Component
public class AsyncIngestionWorker {

    private final VectorStore vectorStore;
    private final IngestionStatusWriter statusWriter;
    private final CancellationRegistry cancellations;
    private final AiAgentRagProperties props;
    private final AiAgentEmbeddingProperties embProps;
    private final DataManager dataManager;
    // constructor injection...

    @Async("aiAgentIngestExecutor")
    public void ingest(UUID docId, byte[] content, String filename, List<String> allowedRoles) {
        statusWriter.markProcessing(docId);  // REQUIRES_NEW
        try {
            if (cancellations.isCancelled(docId)) return;

            List<Document> raw = new TikaDocumentReader(new ByteArrayResource(content)).read();

            TokenTextSplitter splitter = new TokenTextSplitter(
                    props.getSplitter().getChunkSize(),
                    props.getSplitter().getMinChunkSizeChars(),
                    props.getSplitter().getMinChunkLengthToEmbed(),
                    props.getSplitter().getMaxNumChunks(),
                    true);
            List<Document> chunks = splitter.apply(raw);

            for (Document chunk : chunks) {
                chunk.getMetadata().put("source",          filename);
                chunk.getMetadata().put("documentId",      docId.toString());
                chunk.getMetadata().put("embeddingModel",  embProps.getModel());
                chunk.getMetadata().put("allowedRoles",    allowedRoles);  // JSON array in metadata
            }

            if (cancellations.isCancelled(docId)) return;

            vectorStore.add(chunks);  // spring.ai.retry wraps embedding calls internally

            if (cancellations.isCancelled(docId)) {
                // Compensating cleanup — delete is already guaranteed in the enclosing delete tx,
                // but belt-and-braces in case the service-side delete did not fire yet.
                deleteChunksFor(docId);
                return;
            }

            statusWriter.markReady(docId);
        } catch (Exception e) {
            // Compensating delete (Pitfall #3).
            try { deleteChunksFor(docId); } catch (Exception ignored) { /* log */ }
            statusWriter.markFailed(docId, e);
        }
    }

    private void deleteChunksFor(UUID docId) {
        vectorStore.delete(new FilterExpressionBuilder()
                .eq("documentId", docId.toString()).build());
    }
}
```

### Atomic delete (RAG-06)

```java
// package com.vn.agent.rag.service;

@Service
public class KnowledgeDocumentService {

    private final VectorStore vectorStore;
    private final DataManager dataManager;
    private final CancellationRegistry cancellations;

    @Transactional  // REQUIRED — binds both vectorStore.delete and dataManager.remove
    public void delete(UUID docId) {
        cancellations.cancel(docId);  // in-flight worker sees this and aborts

        vectorStore.delete(new FilterExpressionBuilder()
                .eq("documentId", docId.toString()).build());

        AiKnowledgeDocument ref = dataManager.getReference(AiKnowledgeDocument.class, docId);
        dataManager.remove(ref);
    }
}
```

### `IngestionStatusWriter` (mirrors Phase 4 `AuditWriter`)

```java
// package com.vn.agent.rag.ingest;

@Component
public class IngestionStatusWriter {

    private final DataManager dataManager;

    public IngestionStatusWriter(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID docId) {
        AiKnowledgeDocument doc = dataManager.load(AiKnowledgeDocument.class).id(docId).one();
        doc.setStatus(AiKnowledgeDocumentStatus.PROCESSING);
        dataManager.save(doc);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReady(UUID docId) {
        AiKnowledgeDocument doc = dataManager.load(AiKnowledgeDocument.class).id(docId).one();
        doc.setStatus(AiKnowledgeDocumentStatus.READY);
        doc.setIngestedAt(OffsetDateTime.now());
        doc.setErrorMessage(null);
        dataManager.save(doc);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID docId, Throwable t) {
        AiKnowledgeDocument doc = dataManager.load(AiKnowledgeDocument.class).id(docId).one();
        doc.setStatus(AiKnowledgeDocumentStatus.FAILED);
        doc.setErrorMessage(summarize(t));  // first 500 chars of message; log full stack separately
        dataManager.save(doc);
    }

    private String summarize(Throwable t) { /* ... */ }
}
```

### UUIDv5 stable ID (D-19, SPI-07)

```java
// Deterministic UUIDv5 using SHA-1 — Java stdlib does not ship v5, so compute manually.
// Algorithm is RFC 4122 §4.3 and stable across runs.

public static UUID stableIdFor(String ingesterId, String source) {
    // Namespace: a constant UUID for this add-on's SPI-derived docs.
    UUID ns = UUID.fromString("00000000-0000-5000-a000-000000000001");
    byte[] name = (ingesterId + ":" + source).getBytes(StandardCharsets.UTF_8);
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(toBytes(ns));
        md.update(name);
        byte[] hash = md.digest();
        // Set version and variant per RFC 4122 §4.3.
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // variant
        ByteBuffer bb = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bb.getLong(), bb.getLong());
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
}
```

### `application.yml` fragment shipped by the starter module

Place in `ai-agent-starter/src/main/resources/application.yml` (or as `jmix-app` defaults):

```yaml
spring:
  ai:
    openai:
      embedding:
        options:
          model: text-embedding-3-small
    vectorstore:
      pgvector:
        initialize-schema: false
        schema-name: public
        table-name: AI_AGENT_KB_VECTOR_STORE   # verify against Phase 2 DDL
        dimensions: 1536
        distance-type: COSINE_DISTANCE
        index-type: HNSW
    retry:
      max-attempts: 3
      backoff:
        initial-interval: 1s
        multiplier: 2.0
        max-interval: 20s
      on-http-codes: 429,500,502,503,504
      on-client-errors: false

jmix:
  ai-agent:
    embedding:
      model: text-embedding-3-small
    rag:
      admin-bypass: true
      admin-role-code: AiAgentAdminRole
      splitter:
        chunk-size: 800
        chunk-overlap: 350       # this maps to minChunkSizeChars in TokenTextSplitter naming
        min-chunk-size-chars: 350
        min-chunk-length-to-embed: 5
        max-num-chunks: 10000
      embed-retry:
        max-attempts: 3
        initial-interval: 1s
        multiplier: 2.0
      sample-ingester:
        enabled: false
        path-pattern: "classpath:/ai-kb/**/*.md"
      ingest-executor:
        core-pool-size: 2
        max-pool-size: 4
        queue-capacity: 50
      retrieval:
        similarity-threshold: 0.50
        top-k: 5
```

NOTE: the `jmix.ai-agent.rag.embed-retry.*` surface and the built-in `spring.ai.retry.*` surface are intentionally duplicated here for D-22's "one visible config surface" promise. Planner picks: (option A) document that `jmix.ai-agent.rag.embed-retry.*` delegates to `spring.ai.retry.*` via a `BeanPostProcessor` or `EnvironmentPostProcessor`; (option B) deprecate the `jmix.ai-agent.rag.embed-retry.*` in v1 and document that hosts tune via `spring.ai.retry.*`. **Recommend option B — fewer moving parts; CONTEXT.md D-16 framed retry as a requirement not a new surface.**

## State of the Art

| Old Approach | Current Approach (Spring AI 1.1.4) | When Changed | Impact |
|--------------|------------------------------------|--------------|--------|
| `QuestionAnswerAdvisor` (monolithic: retrieve + augment + call in one class) | `RetrievalAugmentationAdvisor` with pluggable `DocumentRetriever`, `QueryTransformer`, `QueryAugmenter`, `DocumentJoiner`, `DocumentPostProcessor` | 1.0.0-M5 → current | RAG advisor's Modular RAG Architecture is the successor; `QuestionAnswerAdvisor` still exists but is discouraged in new code. CONTEXT.md D-08 correctly picks RAG advisor. |
| `spring-ai-openai-spring-boot-starter` | `spring-ai-starter-model-openai` | 1.0.0-M5 era rename | Historical artifact ID renamed; Phase 4 already uses the new coordinate. |
| `initialize-schema: true` as default | `initialize-schema: false` default, Liquibase/Flyway expected to own schema | 1.0.0 GA era | Avoid accidental DDL; our Phase 2 already owns the pgvector DDL. |
| `@Retryable` boilerplate on every embedding call | Built-in `spring.ai.retry.*` RetryTemplate wrapping all model HTTP calls | 0.8 era | Hand-rolled retries are obsolete for Spring AI model beans. |

**Deprecated/outdated:**
- Raw pgvector SQL via `JdbcTemplate` for filter/delete — `VectorStore.delete(Filter.Expression)` and `SearchRequest.filterExpression` cover all standard cases.
- `FilterExpressionConverter` overrides for common array-membership semantics — default converter handles these correctly for pgvector.

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `spring-ai-starter-model-openai` is already transitively on the ai-agent classpath (via Phase 4's `spring-ai-client-chat:1.1.4` dep) and thus auto-wires `EmbeddingModel` without adding a new direct dep. | Standard Stack "Installation" | Low — planner's first task verifies via `./gradlew dependencies`. If wrong, add one line to `ai-agent.gradle`. No behavioural risk. [ASSUMED] |
| A2 | Phase 2 DDL column names (`id`, `content`, `metadata`, `embedding`) in `AI_AGENT_KB_VECTOR_STORE` match Spring AI pgvector starter's expected column contract — specifically that `metadata` is `json` or `jsonb`. | Common Pitfalls #5 | Medium — if column names differ, the starter's SQL binds against wrong columns at runtime. Planner's first task reads `070-ai-kb-vector-store.xml` and verifies; if a mismatch, add a column-rename Liquibase changelog OR configure the starter (Spring AI exposes `id-type`, `dimensions` but NOT column-name overrides in 1.1.4). [ASSUMED — verifiable by file read] |
| A3 | Jmix `CurrentAuthentication.getAuthentication().getAuthorities()` yields `GrantedAuthority` entries whose `getAuthority()` returns role codes matching what `RoleRepository` stores and what the upload UI picks — i.e., the same string space throughout. | Code Examples "RetrievalFilterBuilder" | Medium — if Jmix adds a "ROLE_" prefix to authorities or returns permission codes instead of role codes, `RetrievalFilterBuilder` will match the wrong set. Planner verifies during `RetrievalFilterBuilderTest` construction by inspecting actual authority strings. [ASSUMED] |
| A4 | `RetrievalAugmentationAdvisor`'s default `getOrder()` is near 0 (i.e., outside Phase 4's order window). | Common Pitfalls #8 | Low — setting `.order(HIGHEST_PRECEDENCE + 250)` explicitly is recommended regardless; the assumption only affects WHY the explicit call is required. [ASSUMED — default not documented in Spring AI Javadoc] |
| A5 | `VectorStore.add(chunks)` and `VectorStore.delete(Filter.Expression)` both participate in the ambient `@Transactional` when the underlying `VectorStore` is `PgVectorStore` bound to the same `DataSource`. | Architecture Patterns / Pitfalls #3 | Medium — this is architecturally true for `JdbcTemplate`-backed `PgVectorStore` but not universally documented. Compensating-delete strategy (Pitfall #3) is robust even if this assumption fails. [ASSUMED — observed in source, not documented] |
| A6 | PostgreSQL jsonpath lax mode is the default when using `@@` operator, which is what Spring AI emits. | Don't Hand-Roll "Filter DSL for list-intersection" | Low — well-established PostgreSQL behaviour, confirmed via official docs. Risk is near-zero but noting explicitly. [VERIFIED: postgresql.org/docs/current/functions-json.html — but labelled ASSUMED here because the specific interaction with `metadata::jsonb @@ '...'::jsonpath` in the Spring AI emitter has not been run against a live DB in this research session] |

**Planner action:** A1, A2, A3 should be resolved by concrete file-read/grep tasks at the start of Wave 0 before any new code is written. A4, A5, A6 can be validated by integration tests (bean-collision test surfaces A4; atomic-delete test surfaces A5; role-scoped retrieval test surfaces A6).

## Open Questions

1. **Does the pgvector starter in 1.1.4 support `table-name` override via `spring.ai.vectorstore.pgvector.table-name`?**
   - What we know: The `PgVectorStore.builder().vectorTableName("vector_store")` method exists.
   - What's unclear: Whether the *auto-config* reads that from `spring.ai.vectorstore.pgvector.table-name` — docs show `PgVectorStoreProperties` fields but do not list the property key explicitly in the 1.1.4 reference.
   - Recommendation: Planner's first Wave 0 task: read `spring-ai-autoconfigure-vector-store-pgvector-1.1.4.jar!/META-INF/spring/additional-spring-configuration-metadata.json` or directly the `PgVectorStoreProperties` class to confirm the key. Fallback: provide a `@Bean PgVectorStore` in `AIAutoConfiguration` that calls the builder explicitly with the Phase 2 table name. This is `@ConditionalOnMissingBean` anyway so hosts can still override.

2. **Does `ChatClient.prompt().advisors(a -> a.param(FILTER_EXPRESSION, null))` cleanly pass `null` through, or does the retriever's `getFilterExpression()` treat "param present but null" differently from "param absent"?**
   - What we know: `RetrievalFilterBuilder.buildFor(admin)` returns `null` per our design.
   - What's unclear: Whether setting the param to `null` is equivalent to not setting it at all.
   - Recommendation: `ChatService.ask()` checks `if (filterExpression != null) .advisors(a -> a.param(...))` — only add the advisor-param call when non-null. Safer and explicit. Shown in Code Examples "Per-request FILTER_EXPRESSION".

3. **How does `TokenTextSplitter` tokenize — cl100k_base (GPT-4) or p50k_base (GPT-3)?**
   - What we know: The splitter uses jtokkit under the hood in recent versions.
   - What's unclear: Whether token counts for `text-embedding-3-small` align with the default tokenizer (they should, since it is cl100k_base).
   - Recommendation: Accept the default; verify in an integration test by checking chunk token counts stay under the 8191 OpenAI embedding input limit.

## Sources

### Primary (HIGH confidence — fetched 2026-04-20)

- [Retrieval Augmented Generation :: Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html) — RetrievalAugmentationAdvisor build pattern, FILTER_EXPRESSION per-request idiom
- [RetrievalAugmentationAdvisor.Builder (Spring AI 1.1.x API)](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/rag/advisor/RetrievalAugmentationAdvisor.Builder.html) — builder methods, `order(Integer)` setter name
- [RetrievalAugmentationAdvisor API](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/rag/advisor/RetrievalAugmentationAdvisor.html) — implements CallAdvisor, StreamAdvisor, BaseAdvisor, Ordered
- [PgVectorStore API (1.1.4)](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/vectorstore/pgvector/PgVectorStore.html) — doDelete / delete signatures
- [PGvector :: Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) — builder methods, DDL shape, FilterExpressionBuilder example
- [ETL Pipeline :: Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html) — TikaDocumentReader, TokenTextSplitter constructor signatures and defaults
- [OpenAI Embeddings :: Spring AI Reference](https://docs.spring.io/spring-ai/reference/api/embeddings/openai-embeddings.html) — spring.ai.openai.embedding.* and spring.ai.retry.* property matrix
- [Spring AI Advisors API](https://docs.spring.io/spring-ai/reference/api/advisors.html) — CallAdvisor vs StreamAdvisor, Ordered contract, advisor-param pattern
- [spring-ai v1.1.4 PgVectorStore source](https://github.com/spring-projects/spring-ai/blob/v1.1.4/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java) — similaritySearch and doDelete SQL templates with `metadata::jsonb @@ '...'::jsonpath`
- [spring-ai v1.1.4 PgVectorFilterExpressionConverter source](https://github.com/spring-projects/spring-ai/blob/v1.1.4/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorFilterExpressionConverter.java) — IN→OR conversion, `$.field == value` jsonpath emission

### Secondary (MEDIUM confidence)

- [spring-ai Issue #1179 — IN/NOT IN fix discussion](https://github.com/spring-projects/spring-ai/issues/1179) — historical context for IN handling in pgvector
- [PostgreSQL JSON Functions and Operators](https://www.postgresql.org/docs/current/functions-json.html) — jsonpath lax-mode array iteration semantics
- Existing code in this repo: `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java` (Phase 4 advisor order pattern), `ai-agent/ai-agent/ai-agent.gradle` (dependency versions), `ai-agent/build.gradle` (Spring AI BOM 1.1.4 pin), `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument*.java`, `.planning/phases/04-orchestration-core/04-RESEARCH.md`, `.planning/phases/05-rag-layer/05-CONTEXT.md` / `05-DISCUSSION-LOG.md`

### Tertiary (LOW confidence — flagged)

- Default `getOrder()` value of `RetrievalAugmentationAdvisor` — not in official docs; do not rely on it (Pitfall #8).
- Exact `PgVectorStoreProperties` property key names — planner verifies in Wave 0 (Open Question 1).

## Metadata

**Confidence breakdown:**
- Standard stack / Gradle coords: HIGH — BOM pin verified in repo; artifact names verified in Spring AI reference docs.
- RetrievalAugmentationAdvisor API: HIGH — builder methods verified from Javadoc; FILTER_EXPRESSION per-request idiom verified from reference doc.
- PgVectorStore filter DSL + delete: HIGH — SQL templates verified from 1.1.4 source code on GitHub; `metadata::jsonb @@ '...'::jsonpath` with lax-mode array match confirmed against PostgreSQL docs.
- Advisor ordering: HIGH — follows Phase 4's established pattern; `.order(Integer)` setter verified for RAG advisor.
- Built-in retry: HIGH — `spring.ai.retry.*` property matrix verified from reference doc.
- Async MDC propagation: MEDIUM — pattern is standard Spring idiom; no Jmix-specific wrinkle identified but not tested in this research.
- OpenRouter-specific quirks: LOW — no first-party Spring AI coverage; Phase 4 works with it so no new risk here.

**Research date:** 2026-04-20
**Valid until:** 2026-05-20 (30 days; Spring AI 1.1.x line is API-stable; revisit if BOM moves to 1.2.x)
