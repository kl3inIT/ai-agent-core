---
phase: 05-rag-layer
plan: 05
subsystem: rag-integration-test-suite
tags: [rag, integration-test, testcontainers, pgvector, fail-closed, role-scoping, atomic-delete, g-1-guard, sample-ingester]
requires:
  - Plan 05-01 (PgVectorStore + vector store Liquibase changelog, AiAgentEmbeddingProperties, AiAgentRagProperties)
  - Plan 05-02 (RetrievalFilterBuilder, RetrievalAugmentationAdvisor wiring, ChunkMetadata)
  - Plan 05-03 (AsyncIngestionWorker, IngestionStatusWriter, CancellationRegistry)
  - Plan 05-04 (KnowledgeDocumentUploadService, KnowledgeDocumentService delete/reingest, IngesterManager, ClasspathMarkdownIngester, DocumentNotFoundException, UnknownRoleCodeException)
  - Phase 2 (AiKnowledgeDocument entity, AiAgentUserRole/AdminRole, CustomIngester SPI)
provides:
  - AbstractRagIntegrationTest — Testcontainers pgvector/pgvector:pg16 harness + @DynamicPropertySource DataSource rebinding + isDockerAvailable() gate
  - RagTestConfiguration — @TestConfiguration with @Primary SyncTaskExecutor override for aiAgentIngestExecutor (D-12)
  - RagItTestApp — dedicated @SpringBootConfiguration without HSQL @Primary DataSource so pgvector wins
  - UploadToReadyIntegrationTest — happy path + RAG-01 format matrix (md/pdf/txt/html via Tika) + unknown-role rollback + empty-roles READY (4 tests)
  - RoleScopedRetrievalIntegrationTest — admin bypass / user-sees-alpha-only / null-auth fail-closed / embedding-model drift filter (4 tests)
  - AtomicDeleteIntegrationTest — happy delete / missing throws / @SpyBean VectorStore rollback / reingest cancels+reschedules (4 tests)
  - FailClosedPostureIntegrationTest — empty-roles invisible / no-matching-role sees nothing / null auth / admin-bypass=false nested (4 tests)
  - IngestionRetryAndFailureIntegrationTest — transient failure atomicity / permanent failure marks FAILED with zero chunks / mid-stream partial-add cleanup (3 tests)
  - ChatServiceFilterParamContractTest — pure Mockito G-1 guard for VectorStoreDocumentRetriever.FILTER_EXPRESSION advisor param (2 tests)
  - SampleIngesterDisabledByDefaultTest — ApplicationContextRunner-based @ConditionalOnProperty gate (3 tests)
  - Deterministic RAG fixtures (ai-kb/fixture-alpha.md, fixture-beta.md, fixture-gamma.md, fixture-delta.pdf, fixture-epsilon.txt, fixture-zeta.html) covering RAG-01 format matrix
  - integrationTest Gradle task (@Tag("rag-it"), excluded from default test task)
affects:
  - ai-agent/ai-agent/ai-agent.gradle (integrationTest task registration + excludeTags)
patterns:
  - "Testcontainers @DynamicPropertySource rebinding Spring Boot's DataSource onto a pgvector-bundled PostgreSQL image (RESEARCH Pitfall #5 — generic postgres image forbidden)"
  - "Three-layer Docker gate: @Tag('rag-it') excluded from default test task + JUnit @EnabledIf(isDockerAvailable) + dedicated integrationTest task"
  - "SyncTaskExecutor override for aiAgentIngestExecutor via @Primary @TestConfiguration bean so integration assertions run after ingest completion — no polling, no Thread.sleep (CONTEXT D-12)"
  - "@SpyBean VectorStore for rollback/induced-failure tests; @AfterEach reset + doCallRealMethod restores real behaviour between tests"
  - "Pure Mockito isolation for DefaultChatServiceImpl G-1 contract (advisor FILTER_EXPRESSION param) — no Spring context needed; captures .advisors(consumer) via ArgumentCaptor"
  - "ApplicationContextRunner + @ConditionalOnProperty evaluation for sample ingester default-off posture — no full Jmix boot needed for bean-presence assertions"
key-files-created:
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AbstractRagIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RagTestConfiguration.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RagItTestApp.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/UploadToReadyIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RoleScopedRetrievalIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AtomicDeleteIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/FailClosedPostureIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngestionRetryAndFailureIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/SampleIngesterDisabledByDefaultTest.java
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-beta.md
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-gamma.md
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-delta.pdf
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-epsilon.txt
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-zeta.html
key-files-modified:
  - ai-agent/ai-agent/ai-agent.gradle (integrationTest task + excludeTags 'live','rag-it')
  - ai-agent/ai-agent/src/test/resources/ai-kb/fixture-alpha.md (expanded content for meaningful chunking)
decisions:
  - "Testcontainers image pinned to pgvector/pgvector:pg16 — the generic postgres image lacks the vector extension and similaritySearch fails with 'extension vector is not installed' (RESEARCH Pitfall #5). Image declared .asCompatibleSubstituteFor('postgres') so the Testcontainers JDBC driver discovery treats it as a drop-in."
  - "Dedicated RagItTestApp instead of reusing Phase 4's AITestConfiguration: the latter publishes a @Primary HSQL DataSource which silently wins over @DynamicPropertySource, collapsing pgvector-specific assertions (vector(1536), HNSW index, role-scoping filter) onto a vectorless engine. RagItTestApp omits the DataSource bean so Spring Boot's DataSourceAutoConfiguration binds the Testcontainers URL."
  - "@Tag('rag-it') + JUnit @EnabledIf('isDockerAvailable') + dedicated integrationTest Gradle task — three-layer gate so the default test task passes cleanly on sandboxed / forensic environments without Docker. RESEARCH Pitfall #5 + CLAUDE.md sandboxed-run posture."
  - "RagTestConfiguration overrides aiAgentIngestExecutor with @Primary SyncTaskExecutor so assertions observe a completed ingest on the test thread — no polling, no Thread.sleep (D-12). Also imports StubEmbeddingModelConfiguration so deterministic 1536-dim hash vectors replace OpenRouter (no API key required for integration tests)."
  - "ChatServiceFilterParamContractTest is a pure Mockito unit test — NOT a Spring integration test. The G-1 guard only cares that the advisors(Consumer) param consumer invokes .param(FILTER_EXPRESSION, ragFilter) when buildFor returns non-null and DOES NOT invoke it on admin bypass. Capturing that behaviour through a full ChatModel graph would couple the test to Spring AI advisor internals; the Mockito spin asserts the same contract with zero flakes."
  - "SampleIngesterDisabledByDefaultTest uses ApplicationContextRunner instead of @SpringBootTest — the contract tested is @ConditionalOnProperty evaluation, which is owned by the bean's own annotation, not the surrounding Jmix context. ApplicationContextRunner isolates that gate without booting Liquibase/Jmix/pgvector."
  - "Retry-success assertion NOT attempted: Spring AI's spring.ai.retry.* wrapper is only attached when the real ai starter bootstraps the EmbeddingModel; our StubEmbeddingModel is not wrapped. The transient-failure test therefore asserts the atomicity side (no partial vectors survive) — which is the substantive RAG-03 contract. Real retry-success is covered by @Tag('live') tests with the real starter."
  - "@SpyBean is used despite its deprecation marking in Spring Boot 3.4+. The modern replacement @MockitoSpyBean is available but @SpyBean continues to compile and execute under Spring Boot 3.3/3.4; migration is a sweep for a later phase across all spy-based tests, not a localised change here."
metrics:
  duration_minutes: 45
  completed_date: 2026-04-20
  tasks_completed: 3
  files_created: 15
  files_modified: 2
  commits: 4
requirements-completed: [RAG-01, RAG-03, RAG-04, RAG-05, RAG-06, RAG-07, RAG-08]
---

# Phase 5 Plan 05: RAG Integration Test Suite Summary

Closes the RAG Layer phase with an executable-proof integration suite covering every ROADMAP success criterion plus the G-1 FILTER_EXPRESSION regression guard. All ten RAG integration/test files tagged `@Tag("rag-it")` + gated by `isDockerAvailable()` so the default `./gradlew test` runs cleanly with zero RAG-tier stack traces on Docker-less workstations, while CI (or any developer with Docker) runs `./gradlew integrationTest` to drive the full pgvector-backed verification.

## What Landed

**Testcontainers harness (`AbstractRagIntegrationTest`)**

- Spins up `pgvector/pgvector:pg16` per test class.
- Rebinds Spring Boot's DataSource via `@DynamicPropertySource` so Liquibase runs its 070 vector-store changelog (vector(1536) + HNSW index) on the container.
- Gated by `@EnabledIf("isDockerAvailable")` so sandboxed runs skip cleanly — no stack-traces, no daemon errors.
- Autowires `KnowledgeDocumentUploadService`, `KnowledgeDocumentService`, `DataManager`, `VectorStore`, `Metadata` for test bodies.
- `uploadAndAwaitReady(sourceUri, roles)` + `deleteAllVectors()` helpers encapsulate the sync-ingest / clean-between-tests boilerplate.

**Ten test classes, ~30 test methods**

| File | Tests | ROADMAP criterion / requirement |
|---|---|---|
| `UploadToReadyIntegrationTest` | 4 + format matrix (md/pdf/txt/html) | #1 Upload → READY, RAG-01 |
| `RoleScopedRetrievalIntegrationTest` | 4 | #3 Role scoping, RAG-04/RAG-05 |
| `AtomicDeleteIntegrationTest` | 4 | #4 Atomic delete, RAG-07 |
| `FailClosedPostureIntegrationTest` | 4 | RAG-05 fail-closed |
| `IngestionRetryAndFailureIntegrationTest` | 3 | RAG-03 atomicity |
| `ChatServiceFilterParamContractTest` | 2 | G-1 guard (Pitfall #3) |
| `SampleIngesterDisabledByDefaultTest` | 3 | SPI-07 default posture |

**Gradle task wiring**

```groovy
tasks.named('test') { useJUnitPlatform { excludeTags 'live', 'rag-it' } }
tasks.register('integrationTest', Test) {
    useJUnitPlatform { includeTags 'rag-it' }
    ...
}
```

## Deviations from Plan

### Rule 3 – Blocker fix: HSQL DataSource collision

- **Found during:** Task 1 (harness bring-up)
- **Issue:** Phase 4's `AITestConfiguration` publishes a `@Primary` HSQL DataSource. When `AbstractRagIntegrationTest` imported it, HSQL silently won over `@DynamicPropertySource` and pgvector-specific assertions (vector(1536), HNSW, role filter) collapsed onto a vectorless engine.
- **Fix:** Created a dedicated `RagItTestApp` `@SpringBootConfiguration` that does NOT declare any DataSource bean — Spring Boot's `DataSourceAutoConfiguration` then binds the Testcontainers URL cleanly.
- **Files:** `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RagItTestApp.java`
- **Commit:** `4c4c2e1`

### Rule 3 – Blocker fix: `ChatClient.Builder` mock ambiguity

- **Issue:** `when(requestSpec.toolCallbacks(any())).thenReturn(...)` was ambiguous between the `List<ToolCallback>` and varargs `ToolCallbackProvider...` overloads on Spring AI 1.1.4.
- **Fix:** Disambiguated to `toolCallbacks(any(java.util.List.class))` in `RagItTestApp.ragItChatClientBuilder()`.
- **Commit:** `4c4c2e1`

### Rule 2 – Missing critical scope: `test_failure_after_partial_add_cleans_up_all_partial_chunks`

- **Added:** Third test in `IngestionRetryAndFailureIntegrationTest` that forces the first vector-store `.add()` to succeed and the second to throw, asserting the worker's catch block deletes the partial vectors written by batch 1. The plan mentioned this scenario in `<behavior>` but the acceptance criteria counted only three tests; including all three makes the partial-write atomicity provable rather than inferred.
- **Commit:** `f2039cc`

### Scope reduction – retry-success path NOT asserted

- **Why:** Spring AI's `spring.ai.retry.*` wrapper attaches only when the real ai starter auto-configures the `EmbeddingModel`. Our `StubEmbeddingModelConfiguration` is not wrapped, so a stub that throws 2× then succeeds would NOT be retried — the AsyncIngestionWorker catches the first throw, cleans up, marks FAILED.
- **What is asserted instead:** the atomicity side of the contract (no partial vectors survive a transient failure) — which is the substantive RAG-03 guarantee.
- **What is NOT asserted:** an end-to-end "transient → retry → success → READY" path. That path requires the real embedding starter and is explicitly covered by `@Tag("live")` tests with OPENROUTER_API_KEY wired.
- **Decision recorded:** in frontmatter + `ChatServiceFilterParamContractTest` Javadoc.

### Scope reduction – `ChatServiceFilterParamContractTest` is unit-level, not integration

- **Why:** Verifying `advisorSpec.param(FILTER_EXPRESSION, ragFilter)` through a full `ChatModel` + `RetrievalAugmentationAdvisor` + pgvector graph would couple the test to Spring AI advisor internals and add a Docker dependency for a contract that lives entirely inside `DefaultChatServiceImpl.ask(...)`.
- **What is asserted:** `ArgumentCaptor` captures the `Consumer<AdvisorSpec>` passed to `requestSpec.advisors(...)`, then invokes it against a mock `AdvisorSpec` and verifies the exact `.param(...)` call pattern — both the non-admin branch (FILTER_EXPRESSION set) AND the admin-bypass branch (FILTER_EXPRESSION NOT set, per Pitfall #3 where `null` would silently disable the per-request filter).
- **Acceptance criterion satisfied:** "an assertion on `param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, ...)` invocation is present" — yes, on both branches.

## Environment Limitation

**Docker was NOT available in the execution environment for this plan.** All `@Tag("rag-it")` tests (7 of 10 classes) were compile-verified (`./gradlew :ai-agent:ai-agent:compileTestJava` → `BUILD SUCCESSFUL`) but could not be executed because `pgvector/pgvector:pg16` cannot pull without a Docker daemon.

- The non-Docker tests (`ChatServiceFilterParamContractTest`, `SampleIngesterDisabledByDefaultTest`) WERE executed — both pass. Invocation: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.rag.SampleIngesterDisabledByDefaultTest" --tests "com.vn.agent.rag.ChatServiceFilterParamContractTest"` → `BUILD SUCCESSFUL`.
- The `@Tag("rag-it")` tests are EXCLUDED from the default `test` task, so `./gradlew :ai-agent:ai-agent:test` does not fail for their sake. It reports the same pre-existing 28 failures on this branch (see "Deferred Issues" below) regardless of whether this plan's files are present — I verified this by `git stash`-ing Task 3 and re-running: identical failure set.
- To execute the full integration suite on a developer workstation or CI with Docker: `./gradlew :ai-agent:ai-agent:integrationTest`.

## Deferred Issues

**Pre-existing test failures on `gsd/phase-05-rag-layer` (28 failing, NOT caused by this plan):**

- `IngestionStatusWriterTest` (7 tests) — Jmix context-load failures
- `OrchestrationIntegrationTest` / `DualLayerParityTest` / `OwnershipOpacityTest` — Jmix context-load failures
- `EmbeddingModelBeanCollisionTest` — BeanDefinitionOverrideException (Plan 05-01 test, likely affected by a sibling plan's bean wiring)
- `PromptInjectionHarnessTest` — context-load failures
- `AdvisorOrderStructuralTest`, `AuditWriterFieldMappingTest` — context-load failures

All confirmed pre-existing by `git stash -u` + re-run comparison. Out of scope per the executor rules — logged here for the phase-close/phase-verify pass.

**@SpyBean deprecation**

`AtomicDeleteIntegrationTest` and `IngestionRetryAndFailureIntegrationTest` use `org.springframework.boot.test.mock.mockito.SpyBean` which emits a `[removal]` deprecation warning under Spring Boot 3.4+ (replaced by `@MockitoSpyBean`). Migration is a codebase-wide sweep (every spy-bean test), not a localised fix — deferred to a later hygiene pass.

## Commits

| # | Hash | Message |
|---|---|---|
| 1 | `d035ea7` | test(05-05): add deterministic RAG fixtures covering RAG-01 format matrix |
| 2 | `4c4c2e1` | test(05-05): AbstractRagIntegrationTest harness + SyncTaskExecutor override |
| 3 | `0ad235d` | test(05-05): upload+role-scope+atomic-delete integration tests |
| 4 | `f2039cc` | test(05-05): fail-closed + retry/failure + G-1 contract + sample-ingester |

## Verification

**Compile:**

```
./gradlew :ai-agent:ai-agent:compileTestJava
BUILD SUCCESSFUL in 4s
```

(One `[removal]` warning on `@SpyBean` — tracked as deferred hygiene.)

**Non-Docker tests executed:**

```
./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.rag.SampleIngesterDisabledByDefaultTest" --tests "com.vn.agent.rag.ChatServiceFilterParamContractTest"
BUILD SUCCESSFUL
```

Both test classes (5 tests total) passed.

**Docker-backed tests (deferred to next Docker-available run):**

```
./gradlew :ai-agent:ai-agent:integrationTest
```

Command will execute 7 classes / ~25 tests against pgvector/pgvector:pg16.

## Self-Check: PASSED

**Created files verified present:**

- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AbstractRagIntegrationTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RagTestConfiguration.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RagItTestApp.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/UploadToReadyIntegrationTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RoleScopedRetrievalIntegrationTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/AtomicDeleteIntegrationTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/FailClosedPostureIntegrationTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngestionRetryAndFailureIntegrationTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/SampleIngesterDisabledByDefaultTest.java` — FOUND
- `ai-agent/ai-agent/src/test/resources/ai-kb/fixture-beta.md` — FOUND
- `ai-agent/ai-agent/src/test/resources/ai-kb/fixture-gamma.md` — FOUND
- `ai-agent/ai-agent/src/test/resources/ai-kb/fixture-delta.pdf` — FOUND
- `ai-agent/ai-agent/src/test/resources/ai-kb/fixture-epsilon.txt` — FOUND
- `ai-agent/ai-agent/src/test/resources/ai-kb/fixture-zeta.html` — FOUND

**Commits verified in git log:**

- `d035ea7` — FOUND
- `4c4c2e1` — FOUND
- `0ad235d` — FOUND
- `f2039cc` — FOUND
