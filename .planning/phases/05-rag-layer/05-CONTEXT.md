# Phase 5: RAG Layer - Context

**Gathered:** 2026-04-20
**Status:** Ready for planning

<domain>
## Phase Boundary

Land the knowledge-base ingestion + pgvector retrieval path with role-scoped filtering, as a parallel authorization channel to Jmix security. Specifically:

- File-upload path: Flow UI upload → `AiKnowledgeDocument` row (status lifecycle) → async `IngesterManager` via a dedicated `@Async` executor → Tika `TikaDocumentReader` → `TokenTextSplitter` → `EmbeddingModel` → pgvector insert.
- Single shared `EmbeddingModel` bean (OpenAI `text-embedding-3-small` via OpenRouter by default, dimension 1536 pinned by Phase 2 DDL), enforced by a bean-collision test.
- Chunk metadata contract: `source`, `documentId`, `embeddingModel`, `allowedRoles` (JSON array of Jmix role codes).
- `RetrievalAugmentationAdvisor` with per-request `FILTER_EXPRESSION` built from `CurrentAuthentication.getAuthorities()` by a dedicated `RetrievalFilterBuilder` bean.
- Fail-closed default: chunks with empty/missing `allowedRoles` invisible to non-admin callers. `AiAgentAdminRole` bypasses the filter entirely.
- `CustomIngester` SPI manager (`IngesterManager`) + one sample impl (classpath markdown folder, off by default).
- Delete-document: atomic removal of vector chunks + entity row in one transaction, including a cancellable PROCESSING path.
- Ingestion status view backing queries (service-level; the Phase 7 UI owns presentation).

**In scope:**
- `EmbeddingModel` wiring, `AIAutoConfiguration` registration, bean-collision test.
- Ingestion pipeline (async executor, reader, splitter, embed, write) + document-level atomicity.
- Chunk-metadata contract + `RetrievalFilterBuilder` + advisor assembly slotted per Phase 4 D-02 (between `MessageChatMemoryAdvisor` and `ToolCallAdvisor`).
- Upload service + reingest service + delete service (the three service methods Phase 7 UI will wire).
- `IngesterManager` fan-out + sample classpath-markdown ingester + SPI-ingested doc mapping to synthetic `AiKnowledgeDocument` rows.
- Service-level role validation against Jmix `RoleRepository`.
- `@ConfigurationProperties` surface for splitter size/overlap, sample-ingester toggle/path, admin-bypass toggle, embedding model/dimension override.
- Unit tests for `RetrievalFilterBuilder`; integration tests for role-scoped retrieval (success criterion #3), atomic delete (#4), upload→READY flow (#1).

**Out of scope (explicit):**
- Flow UI views — `KnowledgeBaseListView`, upload dialog, role picker component, reingest/delete buttons → Phase 7 (UI-04 territory).
- `ToolGuard`, iteration caps, structured output, parameter-profile CRUD, default-params YAML → Phase 6.
- Streaming response path → Phase 7.
- Non-file KB sources beyond the sample ingester (URL crawling, S3, Confluence) → host adds via `CustomIngester`, not shipped.
- Mutation tools, auto-ingest of host entities, exposure-policy layer (per D-10 Phase 2).
- PII redaction on chunk content; content-level ACLs beyond role tagging.

</domain>

<decisions>
## Implementation Decisions

### Embedding Model & Wiring

- **D-01: Default `EmbeddingModel` is OpenAI `text-embedding-3-small` via OpenRouter.** 1536 dimensions matches the Phase 2 pgvector DDL (`embedding vector(1536)`) exactly — zero schema churn. Same OpenRouter base-url and API key as the `ChatModel` (Phase 4 D-03 pattern). Application defaults live under `jmix.ai-agent.embedding.*` with sensible fallbacks so the add-on boots plug-and-play on the demo host. No dimension probe — dimension is contractually 1536 in v1; a change requires a Liquibase changeset, a reingest, and is out of scope.

- **D-02: Reuse `spring-ai-starter-model-openai`'s auto-configured `EmbeddingModel` bean; override-friendly.** `AIAutoConfiguration` declares `@Bean @ConditionalOnMissingBean EmbeddingModel` that returns whatever the OpenAI starter produced (injected by type). Hosts override by declaring their own `EmbeddingModel` of the same type — same shape as every other SPI default (D-06 Phase 2). A dedicated bean-collision test asserts exactly one `EmbeddingModel` bean is present in the application context. Literal implementation of ROADMAP deliverable "single shared `EmbeddingModel` bean (enforced by bean-collision test)".

- **D-03: Model drift is handled by silent filter-out, not by fail-fast.** Every chunk carries `embeddingModel` in metadata (Phase 2 contract). At retrieval, the `FILTER_EXPRESSION` adds `embeddingModel == <currently-configured model>` so stale chunks are invisible. No startup probe; no boot-time failure on model change. Phase 7 admin UI can later surface a "stale chunks — reingest required" banner using the same metadata. Rationale: a model upgrade should never take chat down — the worst case is reduced recall until admin reingests.

### Allowed-Roles Tagging & Posture

- **D-04: Primary posture is "shared by default" — most docs are system-wide knowledge; restricted docs are the exception.** This is a product shape decision, not a security weakening. The upload form pre-fills `allowedRoles = [AiAgentUserRole]` so every authenticated AI user sees the doc. Admin expands an "Advanced / restrict access" section only when the doc needs narrower exposure. The filter contract itself is uniform and strict — the posture lives in the UI default, not in the service or filter.

- **D-05: Empty/missing `allowedRoles` on a chunk means admin-only (fail-closed).** ROADMAP success criterion #3 is the hard contract. Any chunk that somehow lands untagged — CustomIngester bug, legacy row, missing UI — is visible only to `AiAgentAdminRole`. Service never synthesises a default; empty-in = empty-stored. This keeps the UI default (D-04) and the service contract orthogonal and reasoning-clean.

- **D-06: `AiAgentAdminRole` bypasses the FILTER_EXPRESSION entirely.** If `CurrentAuthentication` contains `AiAgentAdminRole`, `RetrievalFilterBuilder` returns `null` (or a trivially-true expression, whichever the advisor API wants). Rationale: admin is authoritative in this add-on; untagged docs must remain debuggable; the "shared default" UX would otherwise force admins to self-tag every doc. Exposed via `jmix.ai-agent.rag.admin-bypass` (default `true`) for hosts with stricter governance — flag, not code change, to disable.

- **D-07: Role picker sources from Jmix `RoleRepository`; service re-validates every submitted role code.** The Phase 7 UI picker will pull all `ResourceRole` codes via `io.jmix.security.role.RoleRepository` (host-defined roles appear automatically). Phase 5 ships the service-layer re-check: before persist, `KnowledgeDocumentUploadService` asserts every role code exists in `RoleRepository` — defence in depth against crafted API calls or misconfigured SPI ingesters. Unknown role codes reject with a typed exception.

### Retrieval Advisor & Filter

- **D-08: Use `RetrievalAugmentationAdvisor` (not `QuestionAnswerAdvisor`).** The newer composable advisor (Retriever + QueryTransformer + DocumentJoiner) fits Phase 5's role-scoped filtering cleanly and leaves room for the Phase 6 guardrail stack and future re-rankers. Research still verifies the 1.1.4 API shape but the architectural commitment is made. Advisor slots between `MessageChatMemoryAdvisor` and `ToolCallAdvisor` per Phase 4 D-02.

- **D-09: FILTER_EXPRESSION semantics — ANY (role intersection is non-empty).** A chunk is visible when `intersection(user.authorities, chunk.allowedRoles) != ∅`. Natural "this doc is shared with roles X, Y" meaning. ALL-match and per-document strategy knobs are deferred. Combined with D-03 the full filter per request is: `embeddingModel == <current>` AND (`admin` OR `any-role-overlap`).

- **D-10: `allowedRoles` lives in chunk metadata as a JSON list; filter uses Spring AI `Filter.Expression` DSL — no raw SQL.** Chunk metadata JSON shape: `{source, documentId, embeddingModel, allowedRoles: ["role_a","role_b"]}`. `FilterExpressionBuilder` produces the IN/contains expression Spring AI's pgvector adapter will translate. Keeps retrieval portable if `VectorStore` is ever swapped; avoids a `FilterExpressionConverter` override unless 1.1.4 genuinely lacks list-membership support (researcher flag — planner falls back to pgvector raw JSON operators only if the DSL does not cover list intersection in M4).

- **D-11: `RetrievalFilterBuilder` is a standalone bean with a pure function signature.** Shape: `Filter.Expression buildFor(Authentication auth)` — returns `null` for admin (per D-06), a conjunction of `embeddingModel ==` and `allowedRoles` list-intersection otherwise. Pure, Spring-context-free unit-testable — literal ROADMAP success criterion #2 ("unit: filter-expression builder produces correct expression from a set of roles"). Advisor calls this per request.

### Ingestion Pipeline

- **D-12: Spring `@Async` on a dedicated named `TaskExecutor` bean (`aiAgentIngestExecutor`), owned by the add-on.** Bounded `ThreadPoolTaskExecutor`, daemon threads, MDC-propagating (so `runId`/user context from the upload thread reaches the worker for audit correlation). Tests swap to `SyncTaskExecutor` via a test config to run ingestion inline. Not Jmix `BackgroundTaskManager` — avoids coupling ingestion to Vaadin UI lifecycle and keeps the option open for the deferred functional-module-only split (PKG-04 / D-01 Phase 1 trigger).

- **D-13: `TokenTextSplitter` with Spring AI defaults exposed via `@ConfigurationProperties`.** `jmix.ai-agent.rag.splitter.chunk-size` (default 800), `.chunk-overlap` (default 350), `.min-chunk-size-chars` — Spring AI defaults documented on the properties class. Host tunes without code change; no per-document override in v1 (future per-ingester override is a deferred idea).

- **D-14: Document-level atomicity — fail the whole doc on any embed failure; no partial persistence.** If any chunk's embed call throws after retries exhaust, the ingestion transaction rolls back all succeeded chunks for this document. `AiKnowledgeDocument.status = FAILED` with `errorMessage` populated is committed via a `REQUIRES_NEW` method on an `IngestionStatusWriter` (same pattern as `AuditWriter` in Phase 4 D-11) so the status row survives the chunk rollback. Admin sees a single actionable state, clicks Reingest (D-15). No PARTIAL state, no resume semantics.

- **D-15: Reingest is a first-class service method.** `KnowledgeDocumentService.reingest(documentId)` — within one transaction: `VectorStore.delete(FilterExpression documentId == X)`, reset `status = PENDING`, `errorMessage = null`, `ingestedAt = null`. Async worker picks it up like a fresh upload. Used by Phase 7 admin UI and by CustomIngester's "rescan source" affordance.

- **D-16: Embed-call retry inside the worker — Spring Retry with exponential backoff, bounded attempts.** Handles transient provider hiccups (OpenRouter 429/503) without failing the whole doc. Combined with D-14: retry each embed, fail whole doc on retry exhaustion. Backoff parameters exposed as `jmix.ai-agent.rag.embed-retry.*` (max-attempts default 3, initial-interval default 1s, multiplier default 2.0).

### CustomIngester & SPI Manager

- **D-17: Ship a classpath-markdown sample ingester, disabled by default.** Bean class `ClasspathMarkdownIngester` annotated `@ConditionalOnProperty(prefix = "jmix.ai-agent.rag.sample-ingester", name = "enabled", havingValue = "true")`. Path pattern default `classpath:/ai-kb/**/*.md`, overridable via `jmix.ai-agent.rag.sample-ingester.path-pattern`. Each `.md` becomes a `Document` with `source = filename`, `allowedRoles = [AiAgentUserRole]` (shared-default, per D-04). Doubles as the reference impl for host authors AND as the integration-test fixture for success criterion #3 (tag it with admin-only in the test, assert non-admin cannot retrieve).

- **D-18: `IngesterManager` owns SPI invocation; fires admin-triggered only.** Service methods: `runAll()`, `runById(String ingesterId)`. No `ApplicationReadyEvent` auto-run, no `@Scheduled`. Phase 7 UI surfaces a "Run" button per ingester. Each run iterates the SPI beans, normalises their `List<Document>` output, and feeds the same async pipeline as user uploads — every chunk carries `embeddingModel` and `allowedRoles` identically.

- **D-19: SPI-ingested docs appear as `AiKnowledgeDocument` rows with stable synthetic IDs.** Primary key = UUID derived from a stable hash of `ingesterId + source` (e.g., UUIDv5 namespace per ingester). On re-run, existing rows are updated (not duplicated) and reingest follows D-15. Deletion via the same delete service method (D-20) — the admin Knowledge Base view treats SPI-sourced and user-uploaded docs identically.

### Delete Semantics

- **D-20: Delete is allowed in any state, including PROCESSING, with a cancellation handshake.** `KnowledgeDocumentService.delete(documentId)` — within one `@Transactional(REQUIRED)` method: (1) flip a volatile `cancelled` marker on the in-flight ingestion (or set `status = CANCELLED` the worker polls between chunks), (2) `VectorStore.delete(FilterExpression documentId == X)`, (3) `DataManager.remove(doc)`. Worker polls the marker at chunk boundaries and aborts cleanly; any chunks it already wrote are in the same delete filter anyway. Idempotent if the worker already finished. No "delete blocked, wait for processing" UX — admin action is never denied on a user-owned asset.

- **D-21: Atomic chunk removal via `VectorStore.delete(FilterExpression)`, not raw SQL.** Spring AI 1.1.4 `PgVectorStore.delete(Filter.Expression)` targets `metadata->>'documentId' == X`. The service method's `@Transactional(REQUIRED)` binds both the `VectorStore.delete` and the `DataManager.remove` to the same JDBC `PlatformTransactionManager` (pgvector table is in the host's Postgres). One rollback boundary; literal implementation of ROADMAP success criterion #4. Researcher verifies the M4 `VectorStore.delete(Filter.Expression)` shape — fallback to raw JDBC `DELETE ... WHERE metadata->>'documentId' = ?` only if 1.1.4 genuinely lacks it.

### Configuration Surface

- **D-22: Every tunable lives under `jmix.ai-agent.rag.*` or `jmix.ai-agent.embedding.*` in a single `@ConfigurationProperties` class.** Exposed in the starter and documented in the phase deliverable. Keys: `embedding.model`, `embedding.provider-base-url`, `rag.admin-bypass`, `rag.splitter.{chunk-size,chunk-overlap,min-chunk-size-chars}`, `rag.embed-retry.{max-attempts,initial-interval,multiplier}`, `rag.sample-ingester.{enabled,path-pattern}`, `rag.ingest-executor.{core-pool-size,max-pool-size,queue-capacity}`. Hosts tune via `application.yml`.

### Claude's Discretion

- Exact bean and package names within `com.vn.agent.rag` (or equivalent) — planner picks.
- Whether `RetrievalAugmentationAdvisor` construction uses the builder DSL vs an `@Bean` factory — either is fine; match the pattern in Phase 4's `ChatClientFactory` for consistency.
- Whether the cancellation marker in D-20 is an in-memory concurrent map (keyed by `documentId`) or a DB status poll — both satisfy the contract; planner picks based on test-seam ergonomics.
- UUIDv5 namespace choice for D-19 synthetic IDs — any deterministic scheme works so long as re-runs produce stable keys.
- Exact Spring Retry configuration style (`@Retryable` annotation vs programmatic `RetryTemplate`) — whatever integrates cleanest with the `@Async` worker.
- `IngestionStatusWriter` method decomposition and exact enum values for any transient "writer" state — match Phase 4 `AuditWriter` shape.
- Whether `RetrievalFilterBuilder` returns `null` or `Filter.Expression.ALL_PASS` for admin — advisor-API-dependent; picker's call.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project planning
- `.planning/PROJECT.md` — Constraints (Spring AI 1.1.4 pinned; pgvector default; no Jmix internal APIs; read-only default), deferred D-01 (module split) and D-10 (exposure layer dropped)
- `.planning/REQUIREMENTS.md` — RAG-01..08, SPI-07 contract
- `.planning/ROADMAP.md` §Phase 5 — deliverables and the four success criteria (authoritative for scope)
- `.planning/phases/02-foundations/02-CONTEXT.md` — D-03 (pgvector DDL already landed with `vector(1536)`, HSQLDB-gated), D-04 (CustomIngester SPI interface shape), D-05 (no AI-specific exposure layer — Jmix security is authoritative), D-06 (`@ConditionalOnMissingBean` starter pattern for SPI defaults), D-07/D-08 (role + row-level predicate — *not* the same as chunk-level filtering, but reuses role codes)
- `.planning/phases/04-orchestration-core/04-CONTEXT.md` — D-02 (advisor order with reserved RAG slot between Memory and Tool), D-03 (per-request `ChatOptions` assembly pattern), D-11 (`REQUIRES_NEW` AuditWriter pattern — reuse for `IngestionStatusWriter` in D-14), D-13/D-14 (`TransactionSynchronizationManager.afterCommit` pattern — available if ingestion needs post-commit hooks)
- `.planning/phases/01-walking-skeleton/01-CONTEXT.md` — D-03 (plug-and-play boot is a hard contract; EmbeddingModel must not require host intervention to boot)

### Existing code
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java` — entity with `allowedRolesJson` (Lob), status enum, `createdBy`, `ingestedAt`. Contract already set in Phase 2; Phase 5 consumes.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocumentStatus.java` — status enum (PENDING / PROCESSING / READY / FAILED). Phase 5 may add CANCELLED if D-20 chooses status-polled cancellation — planner picks.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/spi/CustomIngester.java` — SPI interface shipped in Phase 2.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/050-ai-knowledge-document.xml` — entity DDL.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/changelog/070-ai-kb-vector-store.xml` — pgvector extension + `AI_AGENT_KB_VECTOR_STORE(id, content, metadata json, embedding vector(1536))` + HNSW cosine index, PostgreSQL-gated via `dbms="postgresql"` preCondition.
- `ai-agent/ai-agent/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java` — where `EmbeddingModel` and `VectorStore` beans register.
- `ai-agent/ai-agent/src/main/java/com/vn/autoconfigure/agent/SpiDefaultsAutoConfiguration.java` — pattern for `@ConditionalOnMissingBean` SPI defaults.

### Project conventions
- `CLAUDE.md` — DataManager-only, no EntityManager, `Metadata.create()` for entity instantiation, constructor injection, `msg://` i18n in both `messages.properties` and `messages_vi.properties`

### Jmix skills (invoke via Skill tool before implementing)
- `jmix-services` — `DataManager` patterns, `@Transactional` REQUIRED vs REQUIRES_NEW semantics for D-14 / D-20
- `jmix-entities` — `Metadata.create()`, `@Version`, `@InstanceName` for any synthetic doc rows in D-19
- `jmix-liquibase` — reserved only if Phase 5 needs CANCELLED status addition or any new column (avoid if possible; Phase 2 DDL is intended to be complete)
- `jmix-security-roles` — `RoleRepository` lookup for D-07 role validation; `CurrentAuthentication.getAuthorities()` shape for D-11
- `jmix-testing` — `@SpringBootTest` + `@Tag("live")` gating for any live-embedding test

### External reference implementations (pattern source, NOT a dependency)
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend` — Jmix + Spring AI pgvector + admin KB view reference; generalise pattern, do not copy domain specifics
- `D:/ai/traffic-law-chatbot` — OpenRouter base-url + OpenAI-compatible starter wiring; same pattern applies to the embedding endpoint

### Spring AI docs (use Context7 before writing code — M4 API shifts)
- `/spring-projects/spring-ai/v1.1.4` — `RetrievalAugmentationAdvisor` constructor / builder shape, `Retriever` / `QueryTransformer` / `DocumentJoiner` composition
- `/spring-projects/spring-ai/v1.1.4` — `Filter.Expression` DSL, `FilterExpressionBuilder` list-membership operators (IN / contains); confirm D-10 stays portable
- `/spring-projects/spring-ai/v1.1.4` — `PgVectorStore.builder()` with `vectorTableName`, `initializeSchema(false)`, `.delete(Filter.Expression)` signature (D-21 verification)
- `/spring-projects/spring-ai/v1.1.4` — `TikaDocumentReader`, `TokenTextSplitter` constructor defaults (confirm the 800/350 figures in D-13)
- `/spring-projects/spring-ai/v1.1.4` — `EmbeddingModel` abstraction, `OpenAiEmbeddingModel` auto-configuration keys for D-01/D-02

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets (from prior phases)
- `AiKnowledgeDocument` entity + `AiKnowledgeDocumentStatus` enum — Phase 2 (`com.vn.agent.entity`)
- `CustomIngester` SPI interface — Phase 2 (`com.vn.agent.spi`)
- `SpiDefaultsAutoConfiguration` no-op defaults pattern — Phase 2 (override via `@ConditionalOnMissingBean`)
- pgvector DDL + HSQLDB-safe preCondition gating — Phase 2
- `AIAutoConfiguration` — already hosts `ChatModel`, `ChatMemoryRepository`, `ChatClient`, `AuditAdvisor`, `AuditWriter` (Phase 4); Phase 5 adds `EmbeddingModel`, `VectorStore`, `RetrievalAugmentationAdvisor`, `RetrievalFilterBuilder`, `IngestionStatusWriter`, `IngesterManager`
- `AuditWriter` `REQUIRES_NEW` pattern — Phase 4 D-11; directly analogous to `IngestionStatusWriter` in D-14
- `AiAgentUserRole` / `AiAgentAdminRole` — Phase 2; consumed by D-04 default and D-06 admin bypass
- OpenRouter wiring for `ChatModel` — Phase 4; embedding endpoint reuses same base-url + API key by default (D-01)
- Spring AI 1.1.4 BOM + `spring-ai-starter-model-openai` — already on classpath; provides both chat and embedding auto-configuration

### Established Patterns
- Plug-and-play boot contract: add-on must not require host intervention to start (Phase 1 D-03)
- `@ConditionalOnMissingBean` for every extensible bean
- Dedicated writer beans for transactional-boundary control (`AuditWriter` pattern)
- Constructor injection for services; `@ViewComponent` + `@Autowired` split for views (Phase 7)
- `msg://` i18n keys in both `messages.properties` and `messages_vi.properties`

### Integration Points
- `AIAutoConfiguration` — add new `@Bean @ConditionalOnMissingBean` declarations for `EmbeddingModel`, `VectorStore`, `RetrievalAugmentationAdvisor`, and wire the advisor into the `ChatClient.defaultAdvisors(...)` list in the correct slot (Phase 4 D-02 order)
- Advisor chain — Phase 4 already reserves the slot between `MessageChatMemoryAdvisor` and `ToolCallAdvisor`; Phase 5 inserts `RetrievalAugmentationAdvisor` there
- `ChatService.ask()` — no signature change; Phase 5 is entirely additive at the advisor level
- `jmix-app` demo host — flip `jmix.ai-agent.rag.sample-ingester.enabled=true` and run against a Postgres profile to exercise the full path end-to-end in integration tests

</code_context>

<specifics>
## Specific Ideas

- The "shared by default" UX posture (D-04) is a product decision, not a security weakening. Every service-level and filter-level decision remains fail-closed; only the upload form pre-fills a broad role. Phase 7 UI spec must preserve this (form defaults, not service defaults).
- `RetrievalFilterBuilder.buildFor(Authentication)` is the single testable artefact for success criterion #2. Unit test feeds `Set<String>` role codes directly; no Spring context required.
- The integration test for success criterion #3 uses the sample classpath-markdown ingester (D-17) as the fixture: one doc tagged `[AiAgentAdminRole]` only, one doc tagged `[AiAgentUserRole]`. A test user with `AiAgentUserRole` only retrieves the second; a test user with `AiAgentAdminRole` retrieves both (admin bypass, D-06).
- Delete-atomicity test (success criterion #4): seed a doc with N chunks, call `delete(id)`, assert both the `AiKnowledgeDocument` row is gone AND `COUNT(*) FROM AI_AGENT_KB_VECTOR_STORE WHERE metadata->>'documentId' = 'X'` returns 0, inside the same transaction boundary (Testcontainers Postgres or `@Tag("live")` gated).
- Model drift filter (D-03) uses the literal `embeddingModel` property string — no canonical-form transformation. If host switches from `text-embedding-3-small` to `text-embedding-3-large`, retrieval returns no results until reingest; this is the intended safety behaviour.
- Chunk-metadata JSON shape is a contract, not just a Spring AI detail — the `RetrievalFilterBuilder` unit test asserts the literal metadata keys (`embeddingModel`, `allowedRoles`, `documentId`, `source`). Any renaming is a breaking change requiring a reingest.

</specifics>

<deferred>
## Deferred Ideas

- **Per-document FILTER_EXPRESSION strategy (ANY vs ALL)** — v1 is ANY-only (D-09); per-doc strategy enum deferred until a host requires "must hold all listed roles" semantics.
- **Per-ingester or per-doc splitter configuration** — v1 is global via `@ConfigurationProperties` (D-13); per-source overrides deferred until a host has a concrete "this CSV needs 200/50, PDFs need 1200/300" case.
- **Scheduled CustomIngester invocation** — D-18 is admin-triggered only; `@Scheduled` surface deferred.
- **URL/web crawling ingester** — explicitly out of scope per `PROJECT.md`.
- **Auto-ingest of host entity records into the vector store** — explicitly out of scope per `PROJECT.md`; `DataManager` remains source of truth for structured data.
- **PII redaction / content ACL beyond role tagging** — not modelled; revisit when a host surfaces a concrete scrubbing requirement.
- **D-19 UUIDv5 synthetic IDs for SPI-ingested docs** — deferred from Phase 5 per checker iteration 1 review. The D-19 decision (stable UUIDv5 keys so ingester re-runs update rather than duplicate) requires extending `KnowledgeDocumentUploadService` with an id-aware upsert overload + UUIDv5 helper; out of scope for Phase 5 plan bandwidth. In v1, re-running a CustomIngester creates duplicate `AiKnowledgeDocument` rows; operator workaround is delete-then-reingest. Revisit when a host surfaces a concrete ingester-rerun use case.
- **Partial-ingestion resume semantics** — D-14 is doc-level atomic; resume deferred to avoid "half-indexed document" retrieval footguns.
- **Stale-chunk admin banner UI** — metadata supports it (D-03) but presentation is Phase 7.
- **Dimension migration tooling** — v1 pins 1536 (D-01); larger/smaller dimensions require DDL change + full reingest, deferred until a concrete embedding-model upgrade drives it.
- **`StructuredOutputValidationAdvisor` interaction with retrieval advisor** — Phase 6 territory.
- **Per-user chunk quotas / tenant-level KB partitioning** — not modelled; Jmix row-level security on `AiKnowledgeDocument` could satisfy this at the entity layer if a host asks.

</deferred>

---

*Phase: 05-rag-layer*
*Context gathered: 2026-04-20*
