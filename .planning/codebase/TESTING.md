# Testing Patterns

**Analysis Date:** 2026-06-04

Scope: `ai-agent/ai-agent/src/test/` (213 test source files, base package `com.vn.agent`).

## Test Framework

**Runner:**
- JUnit 5 (Jupiter). `junit-vintage-engine` is excluded.
- Config: `ai-agent/ai-agent/ai-agent.gradle` (the module build script; the root `ai-agent/build.gradle` holds shared subproject config).
- `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`.

**Assertion / Mocking Libraries:**
- AssertJ (`org.assertj.core.api.Assertions.assertThat`) — primary assertion style.
- Mockito (`org.mockito.Mockito.mock/when`) via `spring-boot-starter-test`.
- JUnit 5 `Assertions.*` used in the trivial smoke test only.

**Run Commands:**
```bash
./gradlew :ai-agent:test                              # default suite (excludes live, rag-it, eval)
./gradlew :ai-agent:test --tests "com.vn.agent.tools.mutation.MutationToolInvariantsTest"
./gradlew :ai-agent:evalTest                          # @Tag("eval") rubric suite
./gradlew :ai-agent:liveTest                          # @Tag("live") — requires OPENROUTER_API_KEY
./gradlew :ai-agent:integrationTest                   # @Tag("rag-it") — requires Docker (Testcontainers pgvector)
./gradlew :ai-agent:check                             # adds integrationTest when Docker is detected
```

**Test JVM tuning (`tasks.withType(Test)`):** `maxHeapSize=3g`, `maxParallelForks=1`, `forkEvery=20`, `spring.test.context.cache.maxSize=8`. The suite boots many heavyweight Jmix `@SpringBootTest` contexts; the worker JVM is recycled every 20 classes to avoid OOM before late-suite mutation tests build their contexts.

## Test Tags & Suites

| Tag | Count | Gate | Task |
|-----|-------|------|------|
| `unit` | 9 | none | default `test` |
| `eval` | 8 | rubric corpus | `evalTest` |
| `live` | 9 | `OPENROUTER_API_KEY` | `liveTest` |
| `rag-it` | 3 | Docker / Testcontainers pgvector | `integrationTest` |
| `integration` | 8 | (class-level `isDockerAvailable()` guard where needed) | default or `integrationTest` |

Default `test` does `excludeTags 'live', 'rag-it', 'eval'`.

## Test File Organization

- Co-located by feature package mirroring `src/main` (`action/`, `audit/`, `guard/`, `extraction/`, `orchestration/`, `tools/mutation/`, `performance/`, `i18n/`, `live/`).
- Naming: `<UnitUnderTest><Behavior>Test.java` (e.g. `BuiltInMutationToolsBulkSavePartialFailureTest`, `AiUiSettingsResolverReadThroughTest`).
- Shared test infra in `com.vn.agent.test_support/` (`StubChatModelConfiguration`, `StubVectorStoreConfiguration`, `TestUsersConfiguration`, `InMemoryFileStorageConfiguration`, `EvalFixtures`, `TestLoginView`).
- Mutation fixtures in `tools/mutation/fixture/` — these are Jmix-enhanced entity fixtures whose names contain "Test"; the build **excludes** `**/com/vn/agent/tools/mutation/fixture/**` from execution in every test task so the enhancer-generated classes are not run as tests.

## Test Structure & Base Context

**Full Spring boot context** (88 classes use `@SpringBootTest`):
```java
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({ AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class,
        AiToolsAutoConfiguration.class })
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
```
- `AITestConfiguration` (`src/test/java/com/vn/agent/AITestConfiguration.java`) is the boot config: `@SpringBootConfiguration @EnableAutoConfiguration @Import(AIConfiguration.class) @JmixModule`, supplies a `@Primary` HSQLDB embedded `DataSource` and a Mockito `ChatClient.Builder` stub (so `contextLoads` is green without a live key).
- `@UiTest` (8 classes) for Jmix Flow UI tests via `jmix-flowui-test-assist`.
- The auto-config classes live in the sibling `:ai-agent-starter` module, pulled onto the test classpath only (with the transitive `com.vn:ai-agent` jar excluded so Liquibase doesn't discover `changelog.xml` twice).

## KNOWN @SpringBootTest Boot Regression (IMPORTANT)

A pre-existing Phase 11/13 regression blocks full-autoconfig boot of the ai-agent context: `atmosphere-runtime` / `agentstoreEntityManagerFactory` (`AnnotatedResourceRoleProvider`). Documented in `.planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md`.

Affected plans (13.1-06, 13.1-07, 14-01, 14-02, 16-01/02/03) use **narrowed boot recipes or pure-JUnit/Mockito workarounds** instead of full `@SpringBootTest`. When the nominal contract calls for `@SpringBootTest` but the context won't boot, follow this established playbook: construct the real unit under test directly and mock only its collaborators (e.g. `AiUiSettingsResolverReadThroughTest` instantiates a real `AiUiSettingsResolver` and mocks `UnconstrainedDataManager` + the property beans). Restore `@SpringBootTest` once the regression is fixed.

## Pure-JUnit Source / Reflection Invariant Tests

A distinctive house convention: structural contracts are enforced by **pure-JUnit tests that read `.java` source via `Files.readString` or inspect classes by reflection — no Spring, no mocks**. Canonical example: `tools/mutation/MutationToolInvariantsTest.java`. It:
- Resolves the module root relative to the JVM working dir, walks `src/main/java/com/vn/agent/tools/mutation`, and greps source for forbidden/required tokens (`auditWriter.writeToolCall(` isolation, gate-token source order, no raw JPQL in `MutationAttributeBinder`).
- Uses reflection (`Class.forName` + `isAnnotationPresent(Transactional.class)`) to prove `MutationGateChain` carries no `@Transactional` on class or any declared method.
- Encodes intentionally-RED assertions for not-yet-committed files (TDD against future plans).

This pattern **replaces ArchUnit**, which was dropped in Phase 2 and is NOT a dependency. Prefer it for grep-level / annotation-presence invariants. Other examples: `*InvariantTest` / `*InvariantsTest` classes across `admin/config`, `audit`, `guard`.

## Query-Count Performance Harness

JDBC SELECT counting uses **`net.ttddyy:datasource-proxy:1.11.0`** (EclipseLink has no Hibernate `Statistics` API).
- `performance/QueryCountingDataSourceConfiguration.java` — `@TestConfiguration` `BeanPostProcessor` that wraps the EXISTING `agentstoreDataSource` bean in-place (NOT a new `@Primary` bean) with `ProxyDataSourceBuilder...countQuery()`. **It targets `agentstoreDataSource`, not the main `dataSource`**, because every `ai_*` entity is `@Store("agentstore")` — wrapping the main datasource yields zero counts.
- `performance/ToolQueryCountBaselineTest.java` — reads counts via `QueryCountHolder.get("counting-ds").getSelect()`, clears with `QueryCountHolder.clear()` in `@BeforeEach`.
- Pattern: warm up once (prime EclipseLink L2 + Jmix permission caches), `QueryCountHolder.clear()`, then measure the second call. The contractual N+1 detector is the **slope test** (`getRelatedRecords_doesNotScaleWithChildRowCount`: 10 vs 100 children, delta ≤ 1) — absolute-count ceilings are generous (≤1000) because Jmix per-attribute permission fan-out dominates and is non-deterministic.
- Also `performance/MutationFkBatchLoadQueryCountTest.java`, `performance/FindRecordsLimitCapTest.java`.

## Mocking

- `StubChatModelConfiguration` / `StubVectorStoreConfiguration` stub Spring AI dependencies; `ChatClient.Builder` is Mockito-mocked in `AITestConfiguration` for context-load tests.
- Real-collaborator-plus-mocked-edges is the workaround style under the boot regression (mock `UnconstrainedDataManager`, property beans).
- `SystemAuthenticator.withUser("admin", () -> ...)` wraps DataManager calls in integration tests so security policies resolve.

**What NOT to mock:** the unit under test itself (instantiate it real), the gate/security sequence in mutation tests (use fixtures + `@SpringBootTest`), Jmix metamodel.

## Fixtures, Test Users & Data

- `agentstore` is a second Jmix datastore; tests seed via `AuditWriter` / `DataManager` under `SystemAuthenticator.withUser`. Schema is created by Liquibase against an embedded HSQLDB (`testRuntimeOnly org.hsqldb:hsqldb`) — agentstore-backed entities resolve through `agentstoreDataSource` / `agentstoreEntityManagerFactory`, the same beans implicated in the boot regression above.
- Test users/roles: `test_support/TestUsersConfiguration`, `tools/mutation/MutationToolTestUsersConfiguration`, `MutationTestFixtureTestRole`, `NoCustomerReadRoleConfiguration` (negative read-access cases).
- Eval/golden corpora: `src/test/resources/eval/*.yaml`, `golden-questions.yaml`, `prompt-contract-fixtures.yaml`; `ai-kb/` for RAG ingestion fixtures. Corpus-driven parameterised tests surface rubric drift as a CI test-count change.

## Coverage

No coverage tool (JaCoCo) configured; no enforced threshold. Confidence comes from invariant + integration + eval + live tiers rather than line coverage.

## Test Types Summary

- **Unit / invariant:** pure-JUnit source-scan + reflection (`MutationToolInvariantsTest`); Mockito unit tests under boot-regression workaround.
- **Integration:** `@SpringBootTest` over `AITestConfiguration` + stub AI beans; `@UiTest` for Flow views.
- **Performance:** datasource-proxy query-count baselines + slope N+1 detector.
- **Live (`@Tag("live")`):** real LLM round-trips, semantic golden suites (`live/ChatServiceLiveSemanticTest`), gated on `OPENROUTER_API_KEY`.
- **RAG integration (`@Tag("rag-it")`):** Testcontainers pgvector, Docker-gated, wired into `check` when Docker is present.
- **Eval (`@Tag("eval")`):** rubric corpus suite, opt-in.

---

*Testing analysis: 2026-06-04*
