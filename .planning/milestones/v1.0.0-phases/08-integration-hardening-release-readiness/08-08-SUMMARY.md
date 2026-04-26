---
phase: 08
plan: 08
subsystem: security
tags: [security, gap-closure, jmix-security-data, jmix-unconstrained-datamanager, dependency-fix, audit-writer-fix, conversation-gateway-fix, ingestion-status-writer-fix, chat-memory-repository-fix, TEST-04, R-XP-2, replan, replan-2, scope-expanded]
gap_closure: true
gap_closure_target: VERIFICATION.md Gap 1
supersedes: 08-08-PLAN.md@22ffd05
requires:
  - com.vn.agent.metadata.CurrentUserSchemaAccess#canReadEntity
  - com.vn.agent.tools.BuiltInDataTools#findRecords
  - com.vn.agent.security.AiAgentUserRowLevelRole
  - com.vn.agent.audit.AuditWriter
  - com.vn.agent.orchestration.ProjectingChatMemoryRepository
  - com.vn.agent.orchestration.ConversationGateway
  - com.vn.agent.rag.IngestionStatusWriter
  - io.jmix.securitydata.impl.constraint.SecurityDataConstraintsRegistration
  - io.jmix.securitydata.impl.constraint.CrudEntityConstraint
  - io.jmix.securitydata.impl.constraint.ReadEntityQueryConstraint
  - io.jmix.core.UnconstrainedDataManager
provides:
  - "12/12 GREEN in com.vn.agent.security.* (closure target)"
  - "236/236 GREEN broad broom (./gradlew :ai-agent:ai-agent:test BUILD SUCCESSFUL)"
  - "io.jmix.security:jmix-security-data-starter as production dep on ai-agent module"
  - "AuditWriter, ProjectingChatMemoryRepository, ConversationGateway, IngestionStatusWriter on UnconstrainedDataManager (canonical system-internal-infrastructure pattern)"
affects:
  - ai-agent/ai-agent/ai-agent.gradle
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationGateway.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngestionStatusWriter.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/FoundationsBootSmokeTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditTreeTraversalTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditWriterFieldMappingTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/DualLayerParityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OrchestrationIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngestionStatusWriterTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/advisor/RetrievalAuditRoundTripTest.java
key-files:
  modified:
    - ai-agent/ai-agent/ai-agent.gradle
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationGateway.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/IngestionStatusWriter.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/FoundationsBootSmokeTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditTreeTraversalTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditWriterFieldMappingTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/DualLayerParityTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OrchestrationIntegrationTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/IngestionStatusWriterTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/advisor/RetrievalAuditRoundTripTest.java
  created: []
metrics:
  tasks_completed: 6
  tasks_total: 6
  files_modified: 13
  files_planned: 5
  scope_expansion_files: 8
  tests_red_to_green: 3
  tests_in_security_suite: 12
  tests_broad_broom_total: 236
  regression_count: 0
  source_commit: 2015630
  summary_commit: pending
  completed: 2026-04-26
---

# Plan 08-08 SUMMARY (replan-2): jmix-security-data-starter + UnconstrainedDataManager scope expansion

## Outcome

| | Pre-08-08 | After 08-08 (replan-2) |
|---|---|---|
| `com.vn.agent.security.*` (closure target) | 9/12 (3 RED) | **12/12 GREEN** |
| `com.vn.agent.audit.AuditDurabilityTest` | 3/3 GREEN | **3/3 GREEN** |
| `com.vn.agent.FoundationsBootSmokeTest` | 5/5 GREEN | **5/5 GREEN** |
| `com.vn.agent.tools.PromptInjectionHarnessTest` | 5/5 GREEN | **5/5 GREEN** |
| `com.vn.agent.performance.ToolQueryCountBaselineTest` | 7/7 GREEN | **7/7 GREEN** |
| `com.vn.agent.performance.FindRecordsLimitCapTest` | 2/2 GREEN | **2/2 GREEN** |
| Targeted regression bar (named classes) | 31/34 | **34/34 GREEN** |
| **Broad broom `./gradlew :ai-agent:ai-agent:test`** | (would have 16 fails post-dep alone) | **236/236 GREEN, BUILD SUCCESSFUL** |

3 RED → GREEN: `FilteredSchemaAndExecutionDenialTest.carol_filteredSchema_excludesDenied_andDeniedAttributes`, `.carol_findRecordsDeniedEntity_returnsAccessDeniedJson`, `CrossUserConversationAccessTest.userB_listingConversations_doesNotIncludeUserA_agentStoreScoped`.

## Tasks Executed

| Task | Name | Result | Files |
|---|---|---|---|
| 1 | Add `jmix-security-data-starter` to `ai-agent.gradle` | done | ai-agent.gradle |
| 2 | AuditWriter: `DataManager` → `UnconstrainedDataManager` | done; AuditDurabilityTest 3/3 (canary GREEN after `:clean`) | AuditWriter.java |
| 3 | ProjectingChatMemoryRepository: `DataManager` → `UnconstrainedDataManager` | done; chat-memory pruning works under user auth | ProjectingChatMemoryRepository.java |
| 4 | FoundationsBootSmokeTest: 4 unconstrained saves (lines 151/170/181/193) | done | FoundationsBootSmokeTest.java |
| 5 | AuditDurabilityTest: 6 unconstrained verification reads + class convention comment | done; AuditDurabilityTest 3/3 GREEN | AuditDurabilityTest.java |
| 6 | Regression broom + 2 atomic commits + 08-08-SUMMARY.md | done — see Deviations (scope expanded mid-Task-6) | (orchestration) |

**Scope-expansion sub-tasks (during Task 6 — not in original plan):**

| # | Site | Why |
|---|------|---|
| 6a | FoundationsBootSmokeTest: 5 verification reads switched to `.unconstrained().load()` (lines 156/162/175/186/198 area) | Plan's prediction that row-level role grants system user access to its OWN rows was empirically falsified; reads symmetrically need `.unconstrained()` (mirrors AuditDurabilityTest's 6-site pattern) |
| 6b | ConversationGateway (production): `DataManager` → `UnconstrainedDataManager` | Same architectural family as AuditWriter / ProjectingChatMemoryRepository — manual JPQL ownership filter (`createdBy = :owner`) is the single source of authorization truth; entity-level CrudEntityConstraint is redundant |
| 6c | IngestionStatusWriter (production): `DataManager` → `UnconstrainedDataManager` | Async-ingestion status persistence under REQUIRES_NEW is system bookkeeping; user authorization is gated upstream at KnowledgeDocumentService entry |
| 6d | AuditTreeTraversalTest, AuditWriterFieldMappingTest, DualLayerParityTest, OrchestrationIntegrationTest, RetrievalAuditRoundTripTest, IngestionStatusWriterTest | Test-fixture seeding + verification reads under runWithSystem — same .unconstrained() pattern (system user is policy-gated post-dep) |

## Verification Results

```
:ai-agent:ai-agent:test --tests "com.vn.agent.security.*"
                       --tests "com.vn.agent.tools.PromptInjectionHarnessTest"
                       --tests "com.vn.agent.audit.AuditDurabilityTest"
                       --tests "com.vn.agent.performance.ToolQueryCountBaselineTest"
                       --tests "com.vn.agent.performance.FindRecordsLimitCapTest"
                       --tests "com.vn.agent.FoundationsBootSmokeTest"
BUILD SUCCESSFUL
  AdminViewAccessTest:                   tests=5  failures=0 errors=0
  RagRoleFilterNegativeTest:             tests=2  failures=0 errors=0
  FilteredSchemaAndExecutionDenialTest:  tests=3  failures=0 errors=0   (RED-1, RED-2 → GREEN)
  CrossUserConversationAccessTest:       tests=2  failures=0 errors=0   (RED-3 → GREEN, replay preserved)
  com.vn.agent.security.* TOTAL:         12/12 GREEN — closure target hit
  PromptInjectionHarnessTest:            tests=5  failures=0 errors=0
  AuditDurabilityTest:                   tests=3  failures=0 errors=0
  ToolQueryCountBaselineTest:            tests=7  failures=0 errors=0
  FindRecordsLimitCapTest:               tests=2  failures=0 errors=0
  FoundationsBootSmokeTest:              tests=5  failures=0 errors=0
TOTAL: 34/34 GREEN
```

```
:ai-agent:ai-agent:test
BUILD SUCCESSFUL in 2m 3s
TOTAL: 236 tests across 52 classes — 236 PASS, 0 FAIL, 0 ERROR
```

**Stale-artifact gotcha hit and resolved:** First `AuditDurabilityTest` isolation run after Tasks 1-2 failed with "MetaClass not found for class com.vn.agent.entity.AiAuditEvent" — exactly the entity-enhancer mismatch documented in `08-VERIFICATION.md`. Resolved by `./gradlew :ai-agent:ai-agent:clean` + re-run.

## Root Cause Analysis

### What was missing (the architectural gap)

`jmix-security-2.8.0` ships `SecurityConstraintsRegistration` (`ExportImportEntityConstraint`, `SpecificConstraintImpl`, `EntityAttributeConstraint`). The CRUD-entity and row-level enforcement live in `jmix-security-data-2.8.1`'s `SecurityDataConstraintsRegistration`, which registers `CrudEntityConstraint`, `ReadEntityQueryConstraint`, `InMemoryCrudEntityConstraint`, and `LoadValuesConstraint` at `@PostConstruct`. The `ai-agent` module shipped with `jmix-security-starter` only — its `AccessManager.applyRegisteredConstraints(CrudEntityContext)` calls were silent no-ops at runtime, defaulting to PERMIT-ALL. RED-1, RED-2, and RED-3 in the negative-case suite all exposed this gap.

### Why the bare dep alone is insufficient (re-affirmed by attempt-#2 evidence)

Adding the dep flips RED-1 + RED-2 GREEN at the AccessManager + DataManager layer but exposes pre-existing reliance on the no-op constraints across multiple system-internal write/read paths under both end-user auth and `runWithSystem`:

- `AuditWriter` writes `ai_AiAuditEvent` under user auth; end-user roles intentionally don't grant CREATE on the audit log (tamper-evident infrastructure)
- `ProjectingChatMemoryRepository` prunes `ai_AiMessage` rows during chat-memory bookkeeping; end-user role lacks UPDATE/DELETE on `ai_AiMessage`
- `ConversationGateway` creates `ai_AiConversation` rows under user auth (and under `runWithSystem` from many tests); the entity-level CRUD check is redundant with the manual JPQL ownership filter the gateway already enforces
- `IngestionStatusWriter` flips `ai_AiKnowledgeDocument` status under REQUIRES_NEW from async workers — system bookkeeping, not user-authorized
- Under `runWithSystem`, the system user is itself policy-gated by jmix-security-data — it has no implicit CRUD on AI tables

### What attempt #2 falsified about its OWN scope assumptions

The previous plan iteration (`08-08-PLAN.md@22ffd05`, third revision before this run) widened scope to 5 files based on Investigation #2 Option C. Empirical execution falsified two of its narrower scope assumptions:

1. **"Only line 165 of FoundationsBootSmokeTest needs unlocking; the other 4 saves work because their roles grant CRUD."** — False. Under jmix-security-data, `runWithSystem` does NOT grant the system user CRUD on AI tables. Lines 151, 170, 181, 193 also need `.unconstrained().save(...)`, and lines 156/162/175/186/198 (verification reads) need `.unconstrained().load(...)`.

2. **"Only AuditWriter and ProjectingChatMemoryRepository production sites need switching."** — False. `ConversationGateway` and `IngestionStatusWriter` are equally system-internal-infrastructure. Both share the same architectural pattern (own ownership filter / REQUIRES_NEW boundary; entity-level CrudEntityConstraint is redundant). Without their switch, 7 test classes (16 individual tests) fail post-dep.

The previous executor's framing of "UnconstrainedDataManager doesn't bypass DataStoreCrudListener" was a stack-trace misread, not an actual falsification of the pattern. `UnconstrainedDataManagerImpl.save` sets `SaveContext.authorizationRequired=false`; `DataStoreCrudListener.beforeEntitySave` checks that flag and bypasses enforcement when `false`. Constrained `DataManagerImpl` delegates internally to `UnconstrainedDataManagerImpl` for the actual store I/O after applying constraints (with `authorizationRequired=true`) — which is what the misread stack trace showed (a constrained call going through the unconstrained impl class).

### The architectural anchor: UnconstrainedDataManager as system-internal-infrastructure pattern

Per Jmix Context7 docs (`/jmix-framework/jmix-context7` `data-access/data-manager.html`):

> The `UnconstrainedDataManager` interface provides the same methods as `DataManager` but bypasses all security policy checks. It can be used to override security constraints when necessary in your application code.

System-internal infrastructure shares these properties:
- Operates under arbitrary caller authentication (user-auth or `runWithSystem`)
- Persists/reads system-owned entities the end user cannot directly observe
- Either enforces ownership manually (manual JPQL filter) OR has its own boundary (REQUIRES_NEW / async worker / by-id-only-after-upstream-gate)

For these classes, entity-level CRUD policies are redundant with the existing authorization gate; routing through `UnconstrainedDataManager` declares the intent at the type level. This is the canonical Jmix pattern (also recommended for Quartz jobs and unauthenticated contexts per the same docs).

Project memory `feedback_jmix_unconstrained_for_system_writes` already codifies this for the project. The plan's scope expansion brings ConversationGateway and IngestionStatusWriter into compliance with that standard.

## R-XP-2 Coverage Map

| Requirement | Test that flips | Mechanism |
|---|---|---|
| R-XP-2-FIX-1 | `FilteredSchemaAndExecutionDenialTest.carol_filteredSchema_excludesDenied_andDeniedAttributes` | `CrudEntityConstraint` (registered via `SecurityDataConstraintsRegistration`) populates `CrudEntityContext.setReadDenied()` for carol on `ai_AiAuditEvent` (no ALLOW policy in `NoCustomerReadRoleConfiguration`) → `CurrentUserSchemaAccess.canReadEntity` returns false → schema list excludes audit |
| R-XP-2-FIX-2 | `FilteredSchemaAndExecutionDenialTest.carol_findRecordsDeniedEntity_returnsAccessDeniedJson` | Same `CrudEntityConstraint` → `BuiltInDataTools.resolveReadableEntityOrThrow` calls `currentUserSchemaAccess.canReadEntity(metaClass)` → throws `AccessDeniedToolException` → `findRecords` returns the access_denied JSON envelope |
| R-XP-2-FIX-3 | `CrossUserConversationAccessTest.userB_listingConversations_doesNotIncludeUserA_agentStoreScoped` | `ReadEntityQueryConstraint` (registered alongside `CrudEntityConstraint`) appends `@JpqlRowLevelPolicy where {E}.createdBy = :current_user_username` to bob's `dataManager.load(AiConversation.class).all().list()` query → alice's row excluded at the SQL layer |
| TEST-04 | All 12 `com.vn.agent.security.*` tests | Closure-target gate now satisfied; the test class itself is the contractual deliverable per 08-01 |

## Deviations from Plan

This plan superseded `08-08-PLAN.md@22ffd05` (replan-2 over the original Investigation #1 plan). During execution of Task 6's regression broom, three additional scope expansions were authorized by the orchestrator before commit:

1. **FoundationsBootSmokeTest verification-read sites** (sub-task 6a) — Plan Task 4 predicted only line 165 needed unlocking. Empirical run showed `all_five_entities_round_trip` failed at line 154-155's constrained `.load(AiConversation).id().one()` because `ReadEntityQueryConstraint` filtered the query to 0 rows even under `runWithSystem`. Same architectural pattern as the existing 4 unconstrained saves; symmetric `.unconstrained().load()` extension to 5 verification reads. Resolution: in-scope file (FoundationsBootSmokeTest was already in `files_modified`); no new file added by this sub-task.

2. **ConversationGateway + IngestionStatusWriter production switches** (sub-tasks 6b, 6c) — Plan declared 5 files; broad-broom regression revealed 16 failures across 7 test classes with the identical `AccessDeniedException: ai_AiConversation/AiKnowledgeDocument create` failure mode flowing through these two production classes. Both are legitimate system-internal-infrastructure (matches `feedback_jmix_unconstrained_for_system_writes` description "audit log writers, **seeders**, async workers, any infrastructure persistence"); both already enforce their own authorization gate (manual JPQL ownership filter / by-id-only-after-upstream-gate). Resolution: extended to 13 files total. User explicitly authorized this expansion ("Option 1: widen scope inline").

3. **6 additional test files** (sub-task 6d) — Test fixture seeding + verification reads under `runWithSystem` for system-internal entities follow the same `.unconstrained()` idiom as production sites. Surface area: AuditTreeTraversalTest (1 read), AuditWriterFieldMappingTest (1 fixture save + 5 reads), DualLayerParityTest (1 read), OrchestrationIntegrationTest (3 reads), RetrievalAuditRoundTripTest (1 fixture save + 1 read), IngestionStatusWriterTest (1 fixture save + 6 reads). All edits are mechanical applications of the same pattern.

**Net scope:** 5 planned → 13 actual. 8 files added (2 production + 6 tests). Pinned files **untouched** throughout (`FilteredSchemaAndExecutionDenialTest.java`, `CrossUserConversationAccessTest.java`, `RagRoleFilterNegativeTest.java`, `NoCustomerReadRoleConfiguration.java`, `TestUsersConfiguration.java`, `AiAgentUserRowLevelRole.java`, `AiAgentUserRole.java`, `CurrentUserSchemaAccess.java`, `BuiltInDataTools.java`, `ai-agent-starter.gradle`, `jmix-app/build.gradle`).

`OwnershipOpacityTest` (~1 expected fail) auto-flipped GREEN once `ConversationGateway` switched — no test edit needed.

## Authentication Gates

N/A — no external auth dependencies introduced by this plan. The plan operates entirely on the `jmix-security-data` Spring autoconfig boundary and the `UnconstrainedDataManager` API surface (both shipped by Jmix BOM, no version bump).

## Threat Flags

| Threat | Disposition | Notes |
|---|---|---|
| T-08-08-01 (Elevation of Privilege via missing CrudEntityConstraint) | **mitigated** | `SecurityDataConstraintsRegistration` now registers `CrudEntityConstraint`; `AccessManager.applyRegisteredConstraints(CrudEntityContext)` no longer a no-op |
| T-08-08-02 (Information Disclosure via unfiltered DataManager.load.all) | **mitigated** | `ReadEntityQueryConstraint` now appends `@JpqlRowLevelPolicy` predicate to query JPQL; cross-user reads filtered at SQL layer |
| T-08-08-03 (Tampering regression on AuditWriter under user auth) | **mitigated and tested under realistic user authentication** | AuditWriter on UnconstrainedDataManager; AuditDurabilityTest 3/3 GREEN; canonical Jmix pattern applied |
| T-08-08-04 (DoS / perf regression in ToolQueryCount slope detector) | **accept** | ToolQueryCountBaselineTest 7/7 GREEN; R-03h slope-based assertion intact (CrudEntityConstraint adds in-memory checks, no extra DB round-trips) |
| T-08-08-05 (Repudiation in audit pipeline REQUIRES_NEW) | **accept** | AuditDurabilityTest 3/3 GREEN under new wiring; REQUIRES_NEW propagation contract preserved |
| T-08-08-06 (Tampering: audit log write path bypassed by adversarial code path) | **accept** | UnconstrainedDataManager scope intentionally narrow — only AuditWriter (4 write methods, all `@Transactional REQUIRES_NEW`); audit pipeline is the sole legitimate consumer |
| T-08-08-07 (Tampering: ProjectingChatMemoryRepository chat-memory pruning bypasses CrudEntityConstraint) | **accept** | Pruning JPQL is scoped by `conversationId` UUID lookup (Spring AI MessageWindowChatMemory passes only the active conversation's id); unconstrained scope does not enable cross-user row access |
| T-08-08-08 (Information Disclosure: AuditDurabilityTest verification reads using .unconstrained() could mask real read-policy bugs) | **accept** | AuditDurabilityTest verifies durability (REQUIRES_NEW propagation), not read policy. Read-policy correctness for `ai_AiAuditEvent` has orthogonal coverage in `FilteredSchemaAndExecutionDenialTest.carol_*` (which uses constrained DM by design) |
| T-08-08-09 (NEW: ConversationGateway entity-CRUD bypass via UnconstrainedDataManager) | **accept** | ConversationGateway's manual JPQL ownership filter (`where c.id = :id and c.createdBy = :owner`) is the single source of authorization truth — it correctly enforces D-09 ownership opacity. Entity-level CrudEntityConstraint would only add a redundant check that blocks legitimate same-user CRUD when end-user role lacks entity-level grants |
| T-08-08-10 (NEW: IngestionStatusWriter entity-CRUD bypass via UnconstrainedDataManager) | **accept** | Status persistence is by-id-only after the upstream KnowledgeDocumentService entry-point gate authorizes the operation; status updates are system bookkeeping, not user-authorized direct writes. REQUIRES_NEW boundary preserves atomicity. No cross-user access enabled — caller passes a specific document id |

## Self-Check

- [x] All 6 plan tasks executed (with documented in-Task-6 scope expansion)
- [x] 12/12 GREEN in `com.vn.agent.security.*` (closure target)
- [x] 34/34 GREEN in named regression bar
- [x] 236/236 GREEN broad broom (`./gradlew :ai-agent:ai-agent:test` BUILD SUCCESSFUL)
- [x] Two atomic commits: source/test (HEAD~1 = `2015630`, 13 files), SUMMARY (HEAD = pending until this commit)
- [x] No pinned file in any commit's diff (`FilteredSchemaAndExecutionDenialTest.java`, `CrossUserConversationAccessTest.java`, `RagRoleFilterNegativeTest.java`, `NoCustomerReadRoleConfiguration.java`, `TestUsersConfiguration.java`, `AiAgentUserRowLevelRole.java`, `AiAgentUserRole.java`, `CurrentUserSchemaAccess.java`, `BuiltInDataTools.java`, `ai-agent-starter.gradle`, `jmix-app/build.gradle`)
- [x] No `@Disabled` introduced
- [x] Stale-artifact `:clean` gotcha encountered once and documented
- [x] Architectural pattern aligned with `feedback_jmix_unconstrained_for_system_writes` for all 4 production sites and all 8 test files

## TDD Gate Compliance

N/A — gap-closure on existing pinned tests. Plan type is `execute`, not `tdd`. RED→GREEN flips were achieved by SUT/infrastructure fixes, not by adding new tests.

## Supersession Traceability

- **Originating plan** `08-08-PLAN.md@4955924` — single-line gradle dep change. Falsified by gsd-executor evidence captured in `08-08-INVESTIGATION.md` (attempt #1).
- **Predecessor (replan-2)** `08-08-PLAN.md@22ffd05` — 5-file scope (gradle dep + AuditWriter + ProjectingChatMemoryRepository + FoundationsBootSmokeTest + AuditDurabilityTest). Halted by gsd-executor before commit because Task 4's narrow line-165-only assumption was empirically false. Investigation #2 (`08-08-INVESTIGATION-2.md`) captured the evidence and recommended Option C (Hybrid).
- **Current plan** `08-08-PLAN.md@05c0a0c` — third revision adopting Option C. Executed by orchestrator-driven sequential edits with empirical scope expansion to 13 files when broad-broom revealed two more production sites in the same architectural family. All scope expansions are documented above and align with `feedback_jmix_unconstrained_for_system_writes`.
- **References:** `08-08-INVESTIGATION.md` (attempt #1), `08-08-INVESTIGATION-2.md` (attempt #2 + Option C recommendation), `08-VERIFICATION.md` Gap 1 (closure target), Jmix Context7 `/jmix-framework/jmix-context7` `data-access/data-manager.html` (canonical UnconstrainedDataManager doc).

## Next Steps

- This plan closes Phase 8 VERIFICATION.md Gap 1. The phase had previously been marked `passed_with_deferral` with this gap as known carry-over; with closure achieved, the carry-over note can be retired.
- REQUIREMENTS.md TEST-04 (line 114, previously unchecked because of the 3 REDs) is now satisfiable — orchestrator may flip the checkbox during the next verification pass.
- No follow-up plan needed for this gap. Other Phase 8 gaps (Gap 2 = 08-05 deferral, Gap 3 = 08-03 R-03e calibration) remain documented as future work but are out of scope for 08-08.
