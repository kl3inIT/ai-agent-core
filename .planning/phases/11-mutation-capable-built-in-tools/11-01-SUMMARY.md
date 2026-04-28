---
phase: 11-mutation-capable-built-in-tools
plan: 01
subsystem: database
tags: [jmix, agentstore, liquibase, idempotency, security-roles, i18n]

# Dependency graph
requires:
  - phase: 10-ai-specific-llm-exposure-policy
    provides: AiExposureRule entity shape + AiInternalEntityNames exclusion set + AiAgentAdminRole policy precedent
provides:
  - AiMutationIntent JPA entity (agentstore) with composite unique index on (TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME)
  - AiMutationIntentStatus enum (PENDING/COMMITTED/FAILED/COMMIT_UNKNOWN) for reservation lifecycle
  - REQUEST_HASH column for same-key/different-shape rejection without storing full result JSON
  - Liquibase 070-ai-mutation-intent.xml — table + 3 indexes auto-included via parent agentstore-changelog.xml
  - aiMutation_AiMutationIntent listed in AiInternalEntityNames so admins cannot expose/denylist the dedup table
  - AiAgentMutationRole — empty @ResourceRole marker (host-composed; enforced by Plan 11-07A)
  - AiAgentAdminRole extended with @EntityPolicy(AiMutationIntent.class, ALL)
  - Locale captions for AiMutationIntent + AiMutationIntentStatus in BOTH en/vi bundles
affects: [11-02, 11-03, 11-04, 11-05, 11-06, 11-07A, 11-07B, 11-08, 11-09, 11-10, 11-11]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Composite unique index for cross-user idempotency reservation (concurrency gate at the database level)"
    - "Pre-host-save reservation status (PENDING) so the unique index serializes concurrent duplicate writes"
    - "REQUEST_HASH column rejects same-key/different-call-shape replays without persisting full result JSON"
    - "COMMIT_UNKNOWN parking lane for post-host-save finalization failures (never reclaimable)"
    - "Empty @ResourceRole marker enforced at tool entry (no entity grants by default; hosts compose with their own CRUD policies)"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiMutationIntent.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiMutationIntentStatus.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/070-ai-mutation-intent.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentMutationRole.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiInternalEntityNames.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties

key-decisions:
  - "AiMutationIntent uses composite unique index (TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME) with column-level unique=true intentionally omitted — uniqueness only via @Index, mirroring AiExposureRule BLOCKER-02 to avoid duplicate JPA-emitted constraints"
  - "AiMutationIntentStatus stored as String column STATUS_ via EnumClass<String>; getStatus()/setStatus() bridge enum and varchar, default value PENDING set in field initializer AND Liquibase defaultValue"
  - "Added IDX_AI_MUT_INTENT_STATUS index (beyond DEDUP and EXPIRES_AT) for cleanup-job diagnostics on stuck PENDING and COMMIT_UNKNOWN rows"
  - "AiAgentMutationRole intentionally has NO @EntityPolicy on AiMutationIntent — replay uses UnconstrainedDataManager and a blanket READ would leak idempotency keys/usernames/conversation IDs to all mutation-role users"

patterns-established:
  - "All AI agentstore entities: @Store(name='agentstore') + @JmixEntity + @Entity(name='<prefix>_<Name>') + UUID @JmixGeneratedValue + @Version + @InstanceName + @Index unique=true (NEVER column-level unique)"
  - "Internal AI entities (system-owned, not user-facing) extend AiInternalEntityNames.NAMES so AiExposureRule cannot enable/deny them"
  - "Host composition for mutation tooling: empty @ResourceRole marker + host-defined @EntityPolicy on host entities (SEC-07)"
  - "Locale captions for new AI entities go to BOTH messages_en.properties AND messages_vi.properties (CLAUDE.md ALL-locales rule)"

requirements-completed:
  - ENT-09
  - MUT-04
  - SEC-07
  - MUT-11

# Metrics
duration: 2min
completed: 2026-04-29
---

# Phase 11 Plan 01: Mutation Foundations Summary

**AiMutationIntent agentstore entity with composite-unique idempotency index, REQUEST_HASH/STATUS_ columns for concurrency-safe reservation/replay, AiAgentMutationRole marker, and bilingual locale captions — all foundations for the Phase 11 mutation tools.**

## Performance

- **Duration:** ~2 min (per-task gradle compile already warm)
- **Started:** 2026-04-28T19:57Z (approx, plan execution start)
- **Completed:** 2026-04-28T20:01Z (final commit fa7a744)
- **Tasks:** 2 / 2
- **Files modified:** 8 (4 created + 4 modified)

## Accomplishments

- AiMutationIntent JPA entity ships with concurrency-safe shape — composite unique index on `(TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME)` is the database-level concurrency gate; PENDING reservation status serializes duplicate concurrent writes BEFORE the host save runs.
- REQUEST_HASH column lets replay reject same-key/different-shape calls without storing the full result JSON (D-02 invariant preserved).
- COMMIT_UNKNOWN status parks rows whose host save returned but finalization failed; cleanup never deletes them (operators investigate via audit trail).
- Liquibase 070 changelog auto-discovered by parent `agentstore-changelog.xml` `<includeAll>` — table will materialize on next `bootRun`.
- `aiMutation_AiMutationIntent` joins the always-excluded internal-name set so admin denylist UI cannot accidentally surface or hide the dedup table.
- `AiAgentMutationRole` ships as an empty `@ResourceRole` marker; Plan 11-07A enforces it as the per-user opt-in gate. No `AiMutationIntent` READ policy — preserves the no-leak invariant for idempotency keys, usernames, conversation IDs.
- `AiAgentAdminRole` gains `@EntityPolicy(AiMutationIntent.class, ALL)` so operators can query the dedup table programmatically (no admin list view ships in v1.1).
- Both locale bundles carry full captions for the entity, all 13 attributes, and all 4 status enum values.

## Task Commits

Each task was committed atomically:

1. **Task 1: AiMutationIntent + Liquibase 070 + AiInternalEntityNames extension** — `f4b2eb4` (feat)
2. **Task 2: AiAgentMutationRole + AiAgentAdminRole extension + locale captions** — `fa7a744` (feat)

## Files Created/Modified

### Created
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiMutationIntent.java` — JPA entity in agentstore. UUID + Version + InstanceName, composite unique `@Index`, REQUEST_HASH + STATUS_ + ERROR_CODE + COMMITTED_AT columns, enum bridge accessors for status.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiMutationIntentStatus.java` — `EnumClass<String>` enum: PENDING / COMMITTED / FAILED / COMMIT_UNKNOWN with `fromId(...)` lookup.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/070-ai-mutation-intent.xml` — `AI_MUTATION_INTENT` table + 3 indexes (DEDUP unique, EXPIRES_AT, STATUS_); auto-included via parent changelog.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentMutationRole.java` — empty `@ResourceRole` marker, `CODE = "ai-agent-mutation"`.

### Modified
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiInternalEntityNames.java` — added `"aiMutation_AiMutationIntent"` to `NAMES` Set.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java` — added import + `@EntityPolicy(entityClass = AiMutationIntent.class, actions = EntityPolicyAction.ALL)`.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties` — 18 new keys for `AiMutationIntent` entity / attributes / status enum.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties` — matching 18 Vietnamese translations.

## Decisions Made

- **STATUS_ column over STATUS:** `STATUS` is reserved in some SQL dialects; the trailing underscore (matching existing `varchar` patterns elsewhere) keeps the schema portable and matches the canonical literal in `AiMutationIntent.@Column(name="STATUS_")`.
- **IDX_AI_MUT_INTENT_STATUS added beyond plan baseline:** the plan acceptance criteria require this index for cleanup-job stuck-PENDING / COMMIT_UNKNOWN diagnostics. Created as a non-unique secondary index in the same `<changeSet id="2">` block.
- **enum field default mirrors Liquibase defaultValue:** `private String status = AiMutationIntentStatus.PENDING.getId()` in Java + `defaultValue="PENDING"` in DDL. Consistent across boot and metadata-driven inserts.
- **Followed plan as specified for everything else** — no architectural deviations.

## Deviations from Plan

None — plan executed exactly as written. All acceptance criteria for both tasks satisfied verbatim.

## Issues Encountered

- **Gradle invocation path:** initial `./gradlew :ai-agent:compileJava` from repo root failed with "task 'compileJava' not found in project ':ai-agent'" because the project uses Gradle `includeBuild`. Resolved by running from `D:/DTH/ai-agent-core/ai-agent/` (the included build root). Both task verifications passed cleanly via `cd ai-agent && ./gradlew :ai-agent:compileJava`.

## Manual Review List

- **JetBrains MCP `get_file_problems`:** the JetBrains MCP server is not registered in this execution environment. Per CLAUDE.md workflow guidance, the following Java files should be opened in IntelliJ for `get_file_problems("path", onlyErrors=false)` triage during the next session that has the MCP available:
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiMutationIntent.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiMutationIntentStatus.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentMutationRole.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java`
  - `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiInternalEntityNames.java`

  The Gradle compile passed with zero ERROR-level Javac diagnostics, so no functional issue is expected; this is a precautionary review only.

## User Setup Required

None — no external service configuration required.

## Next Phase Readiness

- Plan 11-02 (AiAgentMutationProperties + @EnableScheduling + no-op MutationGuard bean) can start immediately. The `AiMutationIntent` entity, status enum, table DDL, internal-name exclusion, admin policy, mutation-role marker, and locale captions every later Phase 11 plan imports are now landed.
- Liquibase will create `AI_MUTATION_INTENT` on the next `bootRun`; downstream plans verify boot-time schema creation.
- No blockers.

## Self-Check: PASSED

All claimed files exist on disk and both task commit hashes are present in `git log --all`:

- 9 / 9 files verified (4 created, 4 modified, 1 SUMMARY.md)
- 2 / 2 commit hashes verified (`f4b2eb4`, `fa7a744`)

---
*Phase: 11-mutation-capable-built-in-tools*
*Completed: 2026-04-29*
