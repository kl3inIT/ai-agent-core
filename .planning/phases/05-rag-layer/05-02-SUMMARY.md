---
phase: 05-rag-layer
plan: 02
subsystem: rag-retrieval-wiring
tags: [rag, filter, advisor, chat-client, security, role-scoping]
requires:
  - Plan 05-01 (EmbeddingModel + VectorStore beans, AiAgentRagProperties / AiAgentEmbeddingProperties)
  - Phase 4 ChatClientFactory advisor ordering (audit / memory / tool)
  - Phase 4 DefaultChatServiceImpl per-request advisor-param pattern (audit.runId, ChatMemory.CONVERSATION_ID)
provides:
  - ChunkMetadata constants class (single source of truth for ingest + retrieval)
  - RetrievalFilterBuilder @Component — pure-function buildFor(Authentication)
  - RetrievalAugmentationAdvisorFactory @Configuration — advisor bean at HIGHEST_PRECEDENCE + 250
  - ChatClient.defaultAdvisors now includes ragAdvisor between memory (+200) and tool (+300)
  - DefaultChatServiceImpl per-request VectorStoreDocumentRetriever.FILTER_EXPRESSION param
  - RetrievalFilterBuilderTest (6 unit tests, no Spring context) — ROADMAP success criterion #2
  - test_support/StubVectorStoreConfiguration — in-memory SimpleVectorStore for Phase-4 tests
affects:
  - ai-agent/ai-agent/ai-agent.gradle (spring-ai-rag dep at api scope)
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - AdvisorOrderStructuralTest, DualLayerParityTest, OrchestrationIntegrationTest, OwnershipOpacityTest
tech-stack-added:
  - org.springframework.ai:spring-ai-rag (BOM-resolved 2.0.0-M2) — RetrievalAugmentationAdvisor + VectorStoreDocumentRetriever
patterns:
  - "Pure-function bean with constructor-injected @ConfigurationProperties (BaselineContextProvider analog)"
  - "@Configuration factory + @Bean @ConditionalOnMissingBean (ChatClientFactory analog)"
  - "Per-request .advisors(spec -> spec.param(...)) conditional param injection"
  - "Option A flattened role flags (role_<code> = true) for portable list-membership filtering"
key-files-created:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactory.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/StubVectorStoreConfiguration.java
key-files-modified:
  - ai-agent/ai-agent/ai-agent.gradle
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AdvisorOrderStructuralTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/DualLayerParityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OrchestrationIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OwnershipOpacityTest.java
decisions:
  - "RetrievalFilterBuilder uses Option A flattened role_* boolean flags (not list-membership IN operator) for pgvector portability per CONTEXT.md D-10 + RESEARCH Pitfall #1"
  - "Admin bypass returns null (not Filter.Expression.ALL_PASS) so DefaultChatServiceImpl can structurally skip FILTER_EXPRESSION param; retriever then runs without any filter (matches D-06 intent)"
  - "Fail-closed empty-roles branch ANDs embeddingModel pin with eq(documentId, '__none__') sentinel — no chunk is ever written with documentId=__none__, so the filter matches zero rows"
  - "Null authentication collapses to the empty-roles fail-closed branch inside RetrievalFilterBuilder (T-05-02-05)"
  - "RetrievalAugmentationAdvisor slotted at HIGHEST_PRECEDENCE + 250 via Builder.order(int) — Context7 verification of spring-ai-rag-2.0.0-M2 sources confirmed method name"
  - "VectorStoreDocumentRetriever built with NO static .filterExpression(...) — per-request param REPLACES (not AND-s with) a static filter (RESEARCH Pitfall #3)"
  - "spring-ai-rag declared at api scope because RetrievalAugmentationAdvisor flows through a public @Bean signature visible to ai-agent-starter"
metrics:
  duration_minutes: 40
  completed_date: 2026-04-20
  tasks_completed: 4
  files_created: 5
  files_modified: 7
  commits: 5
requirements-completed: [RAG-04, RAG-05]
---

# Phase 5 Plan 02: Retrieval filter + RetrievalAugmentationAdvisor + ChatService wiring

Landed RAG-04 (role-scoped advisor + per-request FILTER_EXPRESSION) and RAG-05 (role intersection + admin bypass + fail-closed empty-roles). A pure-function filter builder is unit-tested without a Spring context (ROADMAP success criterion #2), the `RetrievalAugmentationAdvisor` slots between memory and tool at `HIGHEST_PRECEDENCE + 250`, and `DefaultChatServiceImpl` threads the per-request `VectorStoreDocumentRetriever.FILTER_EXPRESSION` param into the advisor chain exactly when the builder returns a non-null filter.

## Commits

| Task | Commit    | Description                                                                                |
| ---- | --------- | ------------------------------------------------------------------------------------------ |
| 1    | `df84c50` | feat: add ChunkMetadata constants + RetrievalFilterBuilder                                 |
| 2    | `30a8bea` | test: RetrievalFilterBuilder covers admin bypass + fail-closed + role-overlap              |
| 3    | `c420164` | feat: RetrievalAugmentationAdvisor factory + ChatClientFactory wiring (+250 slot)          |
| 4a   | `84aac61` | feat: DefaultChatServiceImpl passes per-request FILTER_EXPRESSION to RAG advisor           |
| 4b   | `3b4da30` | fix: restore Phase-4 integration tests under the new RAG advisor (Rule 1 regression)       |

Branch: `gsd/phase-05-rag-layer`.

## Must-Haves

- [x] `RetrievalFilterBuilder.buildFor(admin auth)` returns `null` (admin-bypass per D-06) — unit test `admin_with_bypass_on_returns_null`.
- [x] `RetrievalFilterBuilder.buildFor(non-admin auth)` returns a `Filter.Expression` containing `eq('embeddingModel', <current>)` AND a role-overlap conjunction — unit tests `non_admin_gets_embedding_pin_and_role_flag` + `multi_role_produces_or_of_role_flags`.
- [x] `RetrievalFilterBuilder.buildFor(empty-roles auth)` returns a filter that matches zero chunks (fail-closed, D-05) — unit test `empty_roles_fail_closed_uses_sentinel` asserts `__none__` sentinel + embeddingModel pin.
- [x] `RetrievalAugmentationAdvisor` is built at `Ordered.HIGHEST_PRECEDENCE + 250` and included in `ChatClient.defaultAdvisors` — `AdvisorOrderStructuralTest` asserts the four-advisor chain with the +250 slot at position 3.
- [x] `DefaultChatServiceImpl` passes `VectorStoreDocumentRetriever.FILTER_EXPRESSION` as a per-request advisor param when the filter is non-null — `if (ragFilter != null) { advisorSpec.param(...) }` inside the `.advisors(...)` lambda.
- [x] `VectorStoreDocumentRetriever` is built with NO static `.filterExpression(...)` — grep in `RetrievalAugmentationAdvisorFactory.java` confirms zero matches.

## Filter Shape (Option A)

For a caller with roles `{ai-agent-user, custom-host-role}` and default `admin-bypass=true`:

```
AND(
    eq(embeddingModel, "openai/text-embedding-3-small"),
    OR(
        eq(role_ai-agent-user, true),
        eq(role_custom-host-role, true)
    )
)
```

For admin with `admin-bypass=true` → returns `null`; `DefaultChatServiceImpl` omits the param entirely so the retriever runs unfiltered.

For empty-roles / null Authentication → fail-closed:
```
AND(
    eq(embeddingModel, "openai/text-embedding-3-small"),
    eq(documentId, "__none__")
)
```

The role-flag flattening (Option A) is mirrored by Plan 05-03's ingestion worker: each `allowedRoles = ["role-a","role-b"]` becomes metadata keys `role_role-a=true, role_role-b=true` alongside the JSON `allowedRoles` list for audit/debug.

## Advisor Order (post-Phase-5)

| # | Advisor                           | Order                        | Source                                    |
|---|-----------------------------------|------------------------------|-------------------------------------------|
| 1 | `AuditAdvisor`                    | `HIGHEST_PRECEDENCE`         | Phase 4                                   |
| 2 | `MessageChatMemoryAdvisor`        | `HIGHEST_PRECEDENCE + 200`   | Phase 4 (`.order(int)`)                   |
| 3 | `RetrievalAugmentationAdvisor`    | `HIGHEST_PRECEDENCE + 250`   | **Phase 5** (`Builder.order(int)`)        |
| 4 | `ToolCallAdvisor`                 | `HIGHEST_PRECEDENCE + 300`   | Phase 4 (`.advisorOrder(int)` — distinct) |

`AdvisorOrderStructuralTest` reflects into `DefaultChatClient.defaultChatClientRequest.advisors` and asserts all four instances + their orders.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Added `spring-ai-rag` dep at `api` scope**

- **Found during:** Task 3 — first `compileJava` after writing `RetrievalAugmentationAdvisorFactory`.
- **Issue:** Plan did not list any dep for `org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor` or `org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever`. Neither is on the classpath via `spring-ai-client-chat:1.1.4` or `spring-ai-starter-vector-store-pgvector:1.1.4`. They live in `spring-ai-rag` which the Spring AI 1.1.4 BOM resolves to version `2.0.0-M2`.
- **Fix:** Added `api 'org.springframework.ai:spring-ai-rag'` to `ai-agent.gradle`. `api` (not `implementation`) mirrors the same reasoning as Plan 05-01's pgvector decision — `RetrievalAugmentationAdvisor` is exposed as a public `@Bean` by `RetrievalAugmentationAdvisorFactory` and consumed as a constructor parameter by `ChatClientFactory`; both signatures flow through to `ai-agent-starter`'s compile classpath.
- **Files modified:** `ai-agent/ai-agent/ai-agent.gradle`.
- **Commit:** Folded into `c420164`.
- **Root cause (for future planning):** plans that introduce spring-ai advisors or retrievers should explicitly call out the `spring-ai-rag` dep alongside `spring-ai-client-chat`. The Spring AI 1.1.4 BOM treats the RAG sub-project as milestone-versioned (2.0.0-M2) which is easy to miss.

**2. [Rule 1 - Bug] Four Phase-4 integration tests regressed after adding RAG advisor to defaultAdvisors**

- **Found during:** Task 4 verification (`./gradlew :ai-agent:ai-agent:test`).
- **Issue:** `AIAutoConfiguration.aiAgentVectorStore` (Plan 05-01) registers a `PgVectorStore` unconditionally under `@ConditionalOnMissingBean`. Phase-4 integration tests (`OrchestrationIntegrationTest`, `DualLayerParityTest`, `OwnershipOpacityTest`) boot the full add-on auto-config against HSQLDB. Before Plan 05-02 the PgVectorStore was wired but never exercised. After this plan's Task 3, the newly-included `RetrievalAugmentationAdvisor` fires on every `ChatService.ask` and issues `SELECT *, embedding <=> ?::jsonb @@ '$.embeddingModel == ...' FROM public.vector_store ...` which HSQLDB rejects with `BadSqlGrammarException`.
- **Fix:** Created `test_support/StubVectorStoreConfiguration` publishing a `@Primary` in-memory `SimpleVectorStore` (Spring AI's built-in store with `SimpleVectorStoreFilterExpressionEvaluator` — fully supports our filter shape offline). Imported it into the four affected tests. Also updated `AdvisorOrderStructuralTest` to assert the new four-advisor chain (it hard-coded `.hasSize(3)`).
- **Files modified:** `OrchestrationIntegrationTest.java`, `DualLayerParityTest.java`, `OwnershipOpacityTest.java`, `AdvisorOrderStructuralTest.java` + new `StubVectorStoreConfiguration.java`.
- **Commit:** `3b4da30` — kept separate from Task 4 main change so the functional wiring (`84aac61`) is auditable in isolation from the test-infra repair.
- **Root cause:** Plan wrote "no regression on Phase 4 tests" as an acceptance criterion but did not consider that adding a new advisor that touches a `VectorStore` beans-side-effect would blow up HSQLDB-based integration tests. Planner heuristic: when adding a new advisor that takes `VectorStore`/`ChatModel`/`EmbeddingModel` as a dep, audit existing `@SpringBootTest`-based tests that boot `AIAutoConfiguration` for stub-bean requirements.

## Context7 / Source Verification Deltas

- **`RetrievalAugmentationAdvisor.Builder.order(Integer)`** — verified against `spring-ai-rag-2.0.0-M2-sources.jar` line 283. Plan's AI-SPEC reference was correct; RESEARCH's flag about `.order(int)` vs `.advisorOrder(int)` (a ToolCallAdvisor-specific name) held up.
- **`VectorStoreDocumentRetriever.FILTER_EXPRESSION`** — verified as `public static final String FILTER_EXPRESSION = "vector_store_filter_expression"` at line 59.
- **`FilterExpressionBuilder` DSL** — confirmed `eq(String, Object)`, `and(Op, Op)`, `or(Op, Op)` return `Op` (a record) not `Filter.Expression`. `Op.build()` returns the Expression. Plan's AI-SPEC sample used `b.and(Filter.Expression, Filter.Expression)` which would NOT compile — corrected in implementation: chained `Op` references throughout and called `.build()` only at the top level.
- **`Filter.Expression.toString()`** — auto-generated record `toString` (e.g. `Expression[type=EQ, left=Key[key=embeddingModel], right=Value[value=openai/text-embedding-3-small]]`). Used for test-assertion text contains() which is sufficient for the six behaviour assertions.
- **`SimpleVectorStore`** — available in `spring-ai-vector-store-1.1.4`; supports `Filter.Expression` via `SimpleVectorStoreFilterExpressionEvaluator` which is exactly what the stub test config needs.

## Verification Trace

| Acceptance Check                                                           | Command                                                                                  | Result            |
| -------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- | ----------------- |
| ChunkMetadata file + EMBEDDING_MODEL literal                               | `grep '"embeddingModel"' .../ChunkMetadata.java`                                         | 1 line            |
| ROLE_FLAG_PREFIX = "role_"                                                 | `grep 'ROLE_FLAG_PREFIX = "role_"' .../ChunkMetadata.java`                               | 1 line            |
| RetrievalFilterBuilder @Component + AiAgentAdminRole.CODE usage            | `grep 'AiAgentAdminRole.CODE' .../RetrievalFilterBuilder.java`                           | 1 line            |
| No hardcoded "ai-agent-admin" string                                       | `grep '"ai-agent-admin"' .../RetrievalFilterBuilder.java`                                | 0 lines           |
| Admin-bypass returns null                                                  | `grep 'return null' .../RetrievalFilterBuilder.java`                                     | 1 line            |
| RetrievalFilterBuilderTest — 6 @Test methods                               | `grep -c '@Test' .../RetrievalFilterBuilderTest.java`                                    | 6                 |
| Fail-closed sentinel literal                                               | `grep '__none__' .../RetrievalFilterBuilderTest.java`                                    | ≥ 1 line          |
| Advisor factory — no static filter expression                              | `grep '.filterExpression' .../RetrievalAugmentationAdvisorFactory.java`                  | 0 lines           |
| Advisor order constant                                                     | `grep 'HIGHEST_PRECEDENCE + 250' .../RetrievalAugmentationAdvisorFactory.java`           | 1 line            |
| ChatClientFactory defaults include ragAdvisor between memory and tool      | `grep 'defaultAdvisors(auditAdvisor, memoryAdvisor, ragAdvisor, toolCallAdvisor)'`       | 1 line            |
| DefaultChatServiceImpl conditional FILTER_EXPRESSION param                 | `grep 'if (ragFilter != null)' .../DefaultChatServiceImpl.java`                          | 1 line            |
| DefaultChatServiceImpl constructor injects RetrievalFilterBuilder          | `grep 'retrievalFilterBuilder' .../DefaultChatServiceImpl.java`                          | ≥ 2 lines         |
| `./gradlew :ai-agent:ai-agent:compileJava` + starter compile               | exit 0                                                                                   | PASS              |
| `./gradlew :ai-agent:ai-agent:test --rerun-tasks`                          | 0 failing tests (111 + 6 new)                                                            | BUILD SUCCESSFUL  |

## Deferred Issues

None. Plan 05-03 (async ingestion worker) will mirror the ChunkMetadata constants when writing `Document.metadata` maps — the builder is already contractually aligned via the `ChunkMetadata.ROLE_FLAG_PREFIX` constant.

## Known Stubs

None in production code. `StubVectorStoreConfiguration` and `StubEmbeddingModelConfiguration` are test-only (`src/test/java`) and are imported explicitly by the tests that need them; production wiring continues to hit the real `PgVectorStore` + `EmbeddingModel` beans.

## Self-Check: PASSED

- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java`
- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactory.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderTest.java`
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/StubVectorStoreConfiguration.java`
- FOUND: modifications in `ai-agent/ai-agent/ai-agent.gradle`, `ChatClientFactory.java`, `DefaultChatServiceImpl.java`, and the four test files.
- FOUND: commit `df84c50` (Task 1 — ChunkMetadata + builder)
- FOUND: commit `30a8bea` (Task 2 — 6 unit tests)
- FOUND: commit `c420164` (Task 3 — advisor factory + ChatClientFactory + dep)
- FOUND: commit `84aac61` (Task 4 — DefaultChatServiceImpl per-request param)
- FOUND: commit `3b4da30` (Task 4 Rule-1 regression fix — StubVectorStoreConfiguration + test updates)
- Verified: `./gradlew :ai-agent:ai-agent:test --rerun-tasks` exits 0 (BUILD SUCCESSFUL) — all tests green including the 6 new RetrievalFilterBuilderTest assertions.
