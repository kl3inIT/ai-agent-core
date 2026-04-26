---
phase: 08
plan: 01
subsystem: security/test
tags: [test-only, security, integration, TEST-04, R-01a, R-01b, R-01c, R-01d, R-01e, R-01f, R-XP-2]
requires:
  - com.vn.agent.metadata.CurrentUserSchemaAccess#getReadableSchema
  - com.vn.agent.tools.BuiltInDataTools#findRecords
  - com.vn.agent.rag.RetrievalFilterBuilder#buildFor(Authentication)
  - com.vn.agent.ChatService#ask
  - com.vn.agent.entity.AiConversation
  - com.vn.agent.test_support.TestUsersConfiguration (alice/bob/admin baseline)
  - com.vn.agent.test_support.StubChatModelConfiguration
  - com.vn.agent.test_support.StubVectorStoreConfiguration
provides:
  - test-only carol persona with restricted role
  - 6 new @Test methods covering TEST-04 acceptance bullets
  - 3 RED tests pinpointing real security gaps (R-XP-2 trigger payload)
affects:
  - none (test-only — production main + role catalog untouched)
tech-stack:
  added: []
  patterns:
    - "@TestConfiguration + InMemoryUserRepository deferred initializer (mirrors TestUsersConfiguration)"
    - "@ResourceRole interface with @EntityPolicy annotations on a void-method (Jmix 2.8 idiom)"
    - "SecurityContextHolder.getAuthentication() inside SystemAuthenticator.withUser block"
    - "Exception-type + canonical-DEFAULT_MESSAGE opacity assertion (R-01e)"
    - "Annotation-presence assertion against io.jmix.core.metamodel.annotation.Store (R-01c)"
key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/NoCustomerReadRoleConfiguration.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/security/FilteredSchemaAndExecutionDenialTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/security/CrossUserConversationAccessTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/security/RagRoleFilterNegativeTest.java
  modified: []
decisions:
  - "Pivoted denial target from off-classpath demo Customer entity to on-classpath ai_AiAuditEvent (Rule 3 — ai-agent module has no jmix-app dep)"
  - "Used 'userUsername' + 'argumentsJson' as the R-01f protected attributes (real fields on AiAuditEvent)"
  - "Asserted normalized role-flag keys ('role_ai_agent_user', 'ai_agent_admin') matching ChunkMetadata.roleFlagKey output, not raw role codes"
  - "Preserved class name NoCustomerReadRoleConfiguration for plan-trace fidelity despite Customer pivot"
metrics:
  tasks_completed: 4
  tasks_total: 4
  test_files_added: 4
  test_methods_added: 6
  tests_passing: 3
  tests_red: 3
  duration_minutes: ~25
  completed: 2026-04-26
---

# Phase 8 Plan 01: TEST-04 Negative-Case Integration Suite Summary

Delivered four new test files exercising cross-phase security gates (per-user schema filter, RAG role filter, conversation ownership opacity) with R-01a..R-01f tightenings applied per 08-REVIEWS.md.

## Outcome

- **3 of 6 new tests PASS** (RagRoleFilterNegativeTest — both methods; CrossUserConversationAccessTest.userB_replayingUserAConversation_throwsSameTypeAndCodeAsRandomUuid).
- **3 of 6 new tests RED** — all REDs surface real security gaps in the SUTs (intentional per R-XP-2 trigger; orchestrator should run `/gsd-plan-phase 8 --gaps` before Wave 2).

| Test class | Tests | Pass | RED | Notes |
|---|---:|---:|---:|---|
| FilteredSchemaAndExecutionDenialTest | 2 | 0 | 2 | carol observes denied entity ai_AiAuditEvent in schema; find_records returns rows instead of access_denied |
| CrossUserConversationAccessTest | 2 | 1 | 1 | Replay opacity passes; bob sees alice's row in DataManager listing (RowLevelRole gap) |
| RagRoleFilterNegativeTest | 2 | 2 | 0 | Both buildFor(Authentication) contracts hold |

## Tasks Executed

| Task | Name | Commit | Files |
|---|---|---|---|
| 1 | Create NoCustomerReadRoleConfiguration | `8985d82` | NoCustomerReadRoleConfiguration.java |
| 2 | FilteredSchema + CrossUser tests | `502c785` | FilteredSchemaAndExecutionDenialTest.java, CrossUserConversationAccessTest.java |
| 3 | RagRoleFilterNegativeTest | `61910c2` | RagRoleFilterNegativeTest.java |
| — | Rule-1 fix: normalized role-flag key assertions | `bac0860` | RagRoleFilterNegativeTest.java |
| 4 | Cleanup: remove forbidden-symbol mentions from javadoc | `5d828b7` | FilteredSchemaAndExecutionDenialTest.java, RagRoleFilterNegativeTest.java |

## Verification Results

- `./gradlew :ai-agent:ai-agent:compileTestJava` — **PASS** (zero compile errors)
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.security.*"` — 11 tests run; 8 pass, 3 RED (TEST-04 negative cases per CONTEXT D-02 expectation)
- `grep -r "EffectiveSchemaComputer" ai-agent/ai-agent/src/test/java/com/vn/agent/security/` — 0 matches
- `grep -r "buildForCurrentUser" ai-agent/ai-agent/src/test/java/com/vn/agent/security/` — 0 matches
- All four files have zero `@Disabled` annotations
- JetBrains MCP `get_file_problems` not available in this environment; fallback gate (compileTestJava green) satisfied per Task 4 acceptance.

## R-NN Coverage Map

| Marker | File | How satisfied |
|---|---|---|
| R-01a (pin access_denied JSON) | FilteredSchemaAndExecutionDenialTest | `assertThat(result).contains("\"error\"").contains("access_denied")` matched against literal ToolErrorDto field name + literal error code from `BuiltInDataTools.resolveReadableEntityOrThrow:280` |
| R-01b (real SUT method) | RagRoleFilterNegativeTest | `retrievalFilterBuilder.buildFor(SecurityContextHolder.getContext().getAuthentication())` — explicit Authentication parameter |
| R-01c (agentstore inference) | CrossUserConversationAccessTest | `assertThat(AiConversation.class.isAnnotationPresent(io.jmix.core.metamodel.annotation.Store.class)).isTrue()` + assert annotation `name() == "agentstore"` |
| R-01d (entity name pre-resolve) | FilteredSchemaAndExecutionDenialTest | Captured as `private static final String CUSTOMER_ENTITY_NAME = "ai_AiAuditEvent"` (single source of truth per file) |
| R-01e (type+code, not message-string-equality as primary) | CrossUserConversationAccessTest | Type-equality via `getClass().isEqualTo()` and `isInstanceOf(...)` is the primary assertion; message-equality kept as a stable proxy via `DEFAULT_MESSAGE` constant |
| R-01f (attribute-granularity) | FilteredSchemaAndExecutionDenialTest | `private static final String[] CUSTOMER_PROTECTED_ATTRIBUTES = {"userUsername", "argumentsJson"}` — checked via `entry.getValue().doesNotContain(CUSTOMER_PROTECTED_ATTRIBUTES)` if the denied entity is reachable |
| R-XP-2 (mid-phase replan trigger) | (process annotation) | 3 RED tests reveal real gaps; orchestrator should run `/gsd-plan-phase 8 --gaps` before Wave 2 per 08-01-PLAN.md `<notes>` paragraph |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking adaptation] Denial-target entity pivoted from `Customer` to `ai_AiAuditEvent`**
- **Found during:** Task 0 / R-01d pre-resolve discovery
- **Issue:** The plan named `com.vn.jmixapp.entity.Customer` (entity name `Customer` — verified via `@JmixEntity` with no explicit name and absence of `jmix.core.metadata.name-prefix`). That entity lives in the `jmix-app` module, which is NOT a Gradle dependency of `ai-agent` (verified by reading `ai-agent.gradle`). It is therefore not on the test classpath and never appears in `metadata.getSession().getClasses()`. Asserting it absent from carol's filtered schema would pass trivially regardless of role enforcement.
- **Fix:** Substituted `ai_AiAuditEvent` (an entity in `com.vn.agent.entity` that IS on the classpath and that carol's `NoCustomerReadRole` does not grant read access to). Recorded the constant `CUSTOMER_ENTITY_NAME = "ai_AiAuditEvent"` per R-01d's encode-once principle. Substituted attribute names `userUsername`/`argumentsJson` for `notes`/`creditLimit` (Customer didn't have those either — its real fields are `name`/`email`/`phone`).
- **Files:** NoCustomerReadRoleConfiguration.java, FilteredSchemaAndExecutionDenialTest.java
- **Commit:** `8985d82`, `502c785`
- **Class name preserved:** `NoCustomerReadRoleConfiguration` / `NoCustomerReadRole` retained for plan-trace fidelity despite the pivot.

**2. [Rule 1 - Bug] Role-flag key assertion used hyphenated form instead of normalized form**
- **Found during:** Task 4 verification run
- **Issue:** Initial `RagRoleFilterNegativeTest` asserted `exprText.contains("ai-agent-user")` but `RetrievalFilterBuilder` uses `ChunkMetadata.roleFlagKey()` which lowercases + replaces non-word characters with `_`. The actual filter contains `role_ai_agent_user`. Test was RED for the wrong reason (assertion bug, not SUT bug).
- **Fix:** Updated both positive and negative assertions to match the normalized form: `role_ai_agent_user` and `ai_agent_admin`. Both test methods now PASS.
- **Files:** RagRoleFilterNegativeTest.java
- **Commit:** `bac0860`

**3. [Rule 1 - Bug] Wrong `Store` annotation import path**
- **Found during:** Task 2 compile
- **Issue:** Initially imported `io.jmix.core.annotation.Store`; actual package is `io.jmix.core.metamodel.annotation.Store` (verified against `AiConversation.java:9`).
- **Fix:** Corrected import to `io.jmix.core.metamodel.annotation.Store`.
- **Files:** CrossUserConversationAccessTest.java
- **Commit:** `502c785` (folded into Task 2 commit before pushing)

### Acceptance Criterion Caveats

**1. Task 1 grep gate `grep -c "Customer" ... returns 0` is impossible to satisfy literally**
- **Reason:** The class name itself is `NoCustomerReadRoleConfiguration`; the role interface is `NoCustomerReadRole`. Any grep for the substring `Customer` will always match the class declaration line and the constructor calls in test imports.
- **Intent met:** The criterion's parenthetical clarifies "no `@EntityPolicy(entityClass = Customer.class)` — deny-by-default is the mechanism." The implementation has zero `Customer.class` references and zero `import com.vn.jmixapp.entity.Customer` lines (verified by inspection). Deny-by-default is the active mechanism.
- **Action:** None — flagged here for visibility; planner can adjust criterion wording in a future replan.

### Out-of-Scope Issues Discovered (NOT fixed — flagged for `--gaps` replan per R-XP-2)

The 3 RED tests reveal real security gaps that downstream plans must address:

1. **`carol_filteredSchema_excludesDenied_andDeniedAttributes` RED** — `CurrentUserSchemaAccess.getReadableSchema()` returns ALL entities for carol, including `ai_AiAuditEvent` and `ai_AiParameters`, despite carol's role only having policies on AiConversation/AiMessage. The full key set observed: `[sec_ResourcePolicyModel, sec_RowLevelRoleModel, ai_AiParameters, ai_AiKnowledgeDocument, sec_AttributeResourceModel, ai_AiMessage, ai_AiConversation, ai_AiAuditEvent, sec_RowLevelPolicyModel, sec_BaseRoleModel, sec_ResourceRoleModel]`. Suggests `AccessManager.applyRegisteredConstraints(CrudEntityContext)` is not honoring carol's role at the entity-read gate, OR carol's authentication is missing the resource-role authority.
2. **`carol_findRecordsDeniedEntity_returnsAccessDeniedJson` RED** — `BuiltInDataTools.findRecords("ai_AiAuditEvent", null, 10)` returns `{"entityName":"ai_AiAuditEvent","rows":[],"limit":10,"truncated":false}` instead of the `access_denied` envelope. Same root cause as #1 — `currentUserSchemaAccess.canReadEntity(metaClass)` is returning true for carol.
3. **`userB_listingConversations_doesNotIncludeUserA_agentStoreScoped` RED** — bob's `dataManager.load(AiConversation.class).all().list()` includes alice's row. Indicates the `AiAgentUserRowLevelRole` predicate is not being applied during DataManager list operations OR bob is missing the row-level-role authority. (Note: the R-01e cross-user replay opacity test PASSES — that path goes through `ConversationGateway.loadOrCreate` which has explicit `createdBy = :owner` JPQL predicate; `dataManager.load(...).all().list()` does not have that explicit guard.)

**These are NOT bugs introduced by this plan.** They are pre-existing gaps surfaced by the new negative-case tests. Per the plan's `<notes>` section R-XP-2: orchestrator should run `/gsd-plan-phase 8 --gaps` before Wave 2 (Plan 07) executes to scope the SUT fixes.

## Authentication Gates

None — all tests run against in-memory user fixtures (TestUsersConfiguration + NoCustomerReadRoleConfiguration). No external auth, no API keys.

## Known Stubs

None. All tests assert against real bean wiring (`@SpringBootTest(classes = AITestConfiguration.class)`); no mocked SUTs.

## Threat Flags

None. This plan adds test-only files; no new production surface introduced.

## Self-Check: PASSED

- `[X] FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/NoCustomerReadRoleConfiguration.java`
- `[X] FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/security/FilteredSchemaAndExecutionDenialTest.java`
- `[X] FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/security/CrossUserConversationAccessTest.java`
- `[X] FOUND: ai-agent/ai-agent/src/test/java/com/vn/agent/security/RagRoleFilterNegativeTest.java`
- `[X] FOUND commit: 8985d82` (Task 1)
- `[X] FOUND commit: 502c785` (Task 2)
- `[X] FOUND commit: 61910c2` (Task 3)
- `[X] FOUND commit: bac0860` (Rule-1 fix)
- `[X] FOUND commit: 5d828b7` (Task 4 cleanup)

## TDD Gate Compliance

N/A — plan type is `execute` (not `tdd`). Test files are themselves the deliverable; no TDD RED→GREEN→REFACTOR cycle on production code.
