---
phase: 10
plan: 10
subsystem: ai-exposure
tags: [test, regression-gate, opacity, rag-filter, exp-09, fix-r6, fix-r8]
requires:
  - phase-10-02 (LlmExposurePolicy + getDenylistedEntityNames public method)
  - phase-10-04 (BuiltInDataTools / BaselineContextProvider call-site swap + Fix R4 unified opacity)
  - phase-10-05 (RetrievalFilterBuilder NIN clause + Fix R6 defensive ISNULL form)
provides:
  - "RetrievalFilterBuilderDenylistTest — unit-level Filter.Expression shape gate (NIN, empty short-circuit, admin bypass, ISNULL legacy-doc carve-out)"
  - "LlmExposurePolicyIntegrationTest — @SpringBootTest four-path uniform-opacity gate (list_entities, agent.entities, find_records, RAG filter clause)"
affects: []
tech-stack:
  added: []
  patterns:
    - "Pure-Mockito unit suite for Filter.Expression toString shape (no Spring context)"
    - "@SpringBootTest + SystemAuthenticator.withUser + SecurityContextHolder pull for explicit Authentication arg (matches FilteredSchemaAndExecutionDenialTest / RagRoleFilterNegativeTest)"
    - "Deterministic seeded test entity (ai_AiConversation — alice has READ via AiAgentUserRole) — Fix R8 nondeterminism guard"
    - "Per-test seed/cleanup with UnconstrainedDataManager (alice has no @EntityPolicy on AiExposureRule); rule deleted by id in @AfterEach"
key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderDenylistTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposurePolicyIntegrationTest.java
  modified: []
decisions:
  - "Test entity is ai_AiConversation (Fix R8 deterministic). alice (AiAgentUserRole) has READ on AiConversation/AiMessage WITHOUT a denylist rule, so seeding the rule and asserting absence is the only behavior change — no role-permission interference."
  - "RAG filter clause assertion (4th opacity path) lives inside LlmExposurePolicyIntegrationTest as a separate @Test method (no live VectorStore needed; RetrievalFilterBuilder.buildFor is pure-function over Authentication). Matches RESEARCH Open Question 2 recommendation. The unit-level RetrievalFilterBuilderDenylistTest pins the ISNULL branch shape separately."
  - "ISNULL branch assertion is unit-test only (RetrievalFilterBuilderDenylistTest Test 4). Integration test asserts NIN+source_entity+denylisted-name presence; the legacy-doc carve-out (Fix R6) is a Filter.Expression-shape contract, not a four-path-opacity contract."
  - "TDD shape inverted to test-first-against-existing-implementation: RED phase is N/A because Plan 10-02/04/05 already shipped the production code. Skipped the RED commit; landed each test as a single test() commit. Documented in this SUMMARY."
metrics:
  duration_min: 8
  tasks_completed: 2
  files_changed: 2
  completed_date: "2026-04-27"
---

# Phase 10 Plan 10: TEST-09 Uniform Opacity + RAG Filter Gates Summary

TEST-09 / EXP-09 closure: two complementary test suites that gate the four-path opacity contract (`list_entities`, `agent.entities`, `find_records`, RAG filter clause) and the Plan 10-05 Fix R6 / D-06 legacy-doc carve-out (`ISNULL` defensive branch).

## Objective Recap

Pin the security contract that downstream Phase 11 mutation gating and Phase 12 caching changes must not break:

- **Uniform opacity:** a denylisted entity is invisible across every LLM-facing surface (Plan 10-04 Fix R4) AND surfaces as `unknown_entity` (never `access_denied`) in tool error envelopes.
- **RAG filter shape:** `RetrievalFilterBuilder.buildFor()` produces a `Filter.Expression` that contains a NIN-shaped clause on `source_entity` when the denylist is non-empty (Plan 10-05 EXP-05).
- **Legacy-doc carve-out (D-06):** chunks ingested without a `source_entity` metadata key remain visible regardless of denylist contents (Plan 10-05 Fix R6 defensive `OR(isNull, nin)` form).

Both tests are regression gates for the threat register entries `T-10-02`, `T-10-03`, `T-10-04` declared in PLAN.md.

## What Was Built

### Task 1 — `RetrievalFilterBuilderDenylistTest` (unit, 4 tests)

Pure-Mockito unit suite at `com.vn.agent.rag.RetrievalFilterBuilderDenylistTest`. No Spring context; constructs `AiAgentEmbeddingProperties` / `AiAgentRagProperties` records directly and mocks `LlmExposurePolicy` via Mockito.

| Test | Asserts |
| ---- | ------- |
| `whenDenylistNonEmpty_thenFilterContainsNinClause` | non-empty denylist → expression contains `source_entity` AND `NIN` (case-insensitive) AND the denied entity name verbatim |
| `whenDenylistEmpty_thenNoSourceEntityClause` | empty denylist → expression does NOT contain `source_entity` (zero-overhead short-circuit per Plan 10-05) |
| `whenAdminUser_thenBuildForReturnsNull` | admin bypass returns `null` even with non-empty denylist (bypass branch is structurally above the policy lookup) |
| `whenDenylistNonEmptyAndChunkHasNoSourceEntityKey_thenChunkRemainsVisible` | **Fix R6 / D-06 cross-link** — expression contains `ISNULL` on `source_entity` so legacy chunks (no metadata key) remain visible. Documented rationale comment cross-links Plan 10-05 SUMMARY |

The `Filter.Expression` record's auto-generated `toString` renders `Filter.ExpressionType` enum literals verbatim (`NIN`, `ISNULL`, etc.) — confirmed by reading the Spring AI 1.1.4 sources jar (`spring-ai-vector-store-1.1.4-sources.jar`). String-contains assertions on the rendered expression are stable across Spring AI 1.1.x.

**Commit:** `63c85fe` — `test(10-10): add RetrievalFilterBuilder denylist unit suite`

### Task 2 — `LlmExposurePolicyIntegrationTest` (@SpringBootTest, 4 tests)

Full-stack integration suite at `com.vn.agent.exposure.LlmExposurePolicyIntegrationTest`. Uses the same `@SpringBootTest(classes = AITestConfiguration.class)` + `ImportAutoConfiguration` + `@Import(StubChatModelConfiguration, StubVectorStoreConfiguration)` shape as `FilteredSchemaAndExecutionDenialTest` and `RagRoleFilterNegativeTest`.

**Fixture (Fix R8):**
- `TEST_ENTITY_NAME = "ai_AiConversation"` — deterministic, on-classpath, alice has READ via `AiAgentUserRole`.
- `@BeforeEach` seeds an `AiExposureRule(entityName=ai_AiConversation, mode=EXCLUDE, enabled=true)` via `UnconstrainedDataManager.save()`.
- `@AfterEach` removes the seeded rule by id.
- Authentication via `systemAuthenticator.withUser("alice", () -> {...})` — same helper used by every other Phase 10 integration test.

| Test | Threat-register gate | Asserts |
| ---- | -------------------- | ------- |
| `denylistedEntityNotInListEntities` | T-10-02 | `builtInDataTools.listEntities()` JSON does NOT contain `ai_AiConversation` |
| `denylistedEntityNotInAgentEntities` | T-10-02 | `baselineContextProvider.compose(...).get("agent.entities")` text block does NOT contain `ai_AiConversation` |
| `findRecordsDenylistedEntityReturnsUnknownEntityNotAccessDenied` | T-10-04 | `builtInDataTools.findRecords(ai_AiConversation, null, 10)` JSON contains `"error"` + `unknown_entity` AND does NOT contain `access_denied` (Fix R4 unified opacity) |
| `ragFilterContainsDenylistNinClause` | T-10-03 | `Authentication` pulled from `SecurityContextHolder` inside `withUser`; `retrievalFilterBuilder.buildFor(auth).toString()` contains `source_entity` + NIN + the denylisted entity name |

**Commit:** `2319fbc` — `test(10-10): add four-path uniform-opacity integration suite`

## Verification Performed

| Check | Result |
| ----- | ------ |
| `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.rag.RetrievalFilterBuilderDenylistTest"` | BUILD SUCCESSFUL (4 tests, 33s) |
| `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.exposure.LlmExposurePolicyIntegrationTest"` | BUILD SUCCESSFUL (4 tests, 49s) |
| `./gradlew :ai-agent:ai-agent:test` (full suite) | BUILD SUCCESSFUL (1m 58s) — no regressions |
| `grep -c "getDenylistedEntityNames" RetrievalFilterBuilderDenylistTest.java` | 4 (≥2 required) |
| `grep -c "SOURCE_ENTITY\|source_entity" RetrievalFilterBuilderDenylistTest.java` | 18 (≥2 required) |
| `grep -c "D-06\|null.*key\|legacy.*doc\|missing.*key" RetrievalFilterBuilderDenylistTest.java` | 11 (≥1 required, Fix R6 rationale comment present) |
| `grep -c "@SpringBootTest" RetrievalFilterBuilderDenylistTest.java` | 0 (pure unit test — required) |
| `grep -c "@SpringBootTest" LlmExposurePolicyIntegrationTest.java` | 1 (annotation present — required) |
| `grep -c "unknown_entity" LlmExposurePolicyIntegrationTest.java` | 4 (≥1 required) |
| `grep -c "access_denied" LlmExposurePolicyIntegrationTest.java` | 3 (≥1 required for the doesNotContain assertion) |
| `grep -c "doesNotContain" LlmExposurePolicyIntegrationTest.java` | 3 (≥2 required) |
| `grep -c "SOURCE_ENTITY\|source_entity\|ragFilter\|NIN\|NOT_IN" LlmExposurePolicyIntegrationTest.java` | 8 (≥1 required, RAG filter clause assertion — Fix R8) |
| `grep -c "TEST_ENTITY_NAME\|static final.*String" LlmExposurePolicyIntegrationTest.java` | 10 (≥1 required, deterministic entity — Fix R8) |

All acceptance criteria from PLAN.md Tasks 1 & 2 satisfied. Both test files compile cleanly via `./gradlew :ai-agent:ai-agent:compileTestJava` (covered by the `:test` task graph).

## Decisions Made

- **Test entity choice (Fix R8):** `ai_AiConversation` chosen because alice (canonical `AiAgentUserRole` test user) has READ on it WITHOUT any denylist rule. This makes the test fixture deterministic across:
  - The seeded denylist rule is the ONLY behavior change between baseline and the assertion run.
  - alice is non-admin so `RetrievalFilterBuilder.buildFor` does NOT take the bypass branch (admin bypass returns `null` and would skip the policy lookup entirely — wrong for this gate).
  - `FilteredSchemaAndExecutionDenialTest` already proved alice has READ on `ai_AiConversation` via `AiAgentUserRole` (it's one of the two entities granted there).
- **RAG filter clause assertion location:** included as a 4th `@Test` method in `LlmExposurePolicyIntegrationTest` (not extracted to a separate test). RetrievalFilterBuilder is a pure function over `Authentication`; no live `VectorStore` is required to assert the `Filter.Expression` shape. Matches RESEARCH Open Question 2 recommendation.
- **Two-tier RAG filter coverage:** the unit-level `RetrievalFilterBuilderDenylistTest` pins the `ISNULL` branch (Fix R6 / D-06 carve-out — the implementation detail of the defensive nullable form). The integration test pins the cross-system contract (NIN clause present + denylisted name in the value list). Both gates are necessary: a future refactor to `bare nin` (dropping the `isNull` branch) would silently pass the integration test but fail the unit test.
- **Cleanup pattern:** `UnconstrainedDataManager.remove(rule)` keyed by the seeded rule's id — does NOT use `dataManager.removeAll` or wildcard cleanup (would delete any host-supplied rules in the agentstore between test runs).
- **TDD inversion (per `tdd="true"` in plan):** the production-code RED phase is N/A because Plan 10-02 / 10-04 / 10-05 already shipped `LlmExposurePolicy.getDenylistedEntityNames`, `BuiltInDataTools` Fix R4 unified opacity, and `RetrievalFilterBuilder` defensive nin clause. Plan 10-10's role is to add the regression gates AGAINST those existing behaviors. Each test landed as a single `test()` commit; no separate RED commit was created since there was no production code to introduce. Documented under Decisions per the TDD-execution reference.

## Deviations from Plan

None. Both tests passed on the first compile + run; full suite remained green; all acceptance-criterion grep counts met or exceeded the threshold; no Rule 1/2/3/4 deviations encountered.

## Threat Model Compliance

Threat register from PLAN.md (regression-gate dispositions):

- **T-10-04 (mitigate, Information Disclosure at uniform unknown_entity opacity):** `findRecordsDenylistedEntityReturnsUnknownEntityNotAccessDenied` is the regression gate. A future change to `BuiltInDataTools.resolveReadableEntityOrThrow` that re-introduced an `access_denied` error code on the denial path (or that distinguished denylisted entities from non-existent ones in any other way) would fail this test. Captures Plan 10-04 Fix R4.
- **T-10-02 (mitigate, Information Disclosure at agent.entities):** `denylistedEntityNotInAgentEntities` AND `denylistedEntityNotInListEntities` together gate the BaselineContextProvider + BuiltInDataTools narrowing through `LlmExposurePolicy`. A regression that swapped the policy back to `CurrentUserSchemaAccess` (undoing Plan 10-04) would fail both.
- **T-10-03 (mitigate, Information Disclosure at RAG filter):** `ragFilterContainsDenylistNinClause` (integration) + all 4 `RetrievalFilterBuilderDenylistTest` cases (unit) together gate the Plan 10-05 NIN clause shape AND the Fix R6 defensive `ISNULL` branch. A regression that dropped the entire NIN clause would fail the integration test; a regression that dropped only the `isNull` half (silently breaking legacy-doc visibility under pgvector JSONPath) would fail the unit test.

No new threat surface introduced — these are pure test additions.

## Open Items / Follow-ups

- The four-path opacity contract is now gated. Phase 11 mutation gating must NOT regress these tests (the planned `LlmExposurePolicy.canModify` wire-in is structurally separate from the read-side surface tested here).
- Phase 12+ caching consumer of `LlmExposureChangedEvent` must keep the per-call read semantics observable from the test perspective (i.e. the integration test must continue to see the seeded rule via `getDenylistedEntityNames`); the cache may add latency but cannot alter the contract.
- The `RetrievalFilterBuilderDenylistTest` Fix R6 assertion (`ISNULL`) is the pinpoint regression gate for Plan 10-05's defensive nullable form. If a future Spring AI release lands a deterministic missing-key contract on `nin` (so bare `nin` becomes safe across all converters), Plan 10-05 may be simplified — and this test must be updated alongside.

## Self-Check: PASSED

Files exist (verified via `git diff --name-only HEAD~2..HEAD`):

- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderDenylistTest.java` — FOUND
- `ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposurePolicyIntegrationTest.java` — FOUND

Commits exist (verified via `git log --oneline -5`):

- `63c85fe` — Task 1 (RetrievalFilterBuilderDenylistTest)
- `2319fbc` — Task 2 (LlmExposurePolicyIntegrationTest)

Targeted-test + full-suite green on the final run (1m 58s, no transient flakes).
