---
phase: 11-mutation-capable-built-in-tools
plan: 12
subsystem: audit
tags: [jmix, liquibase, mutation-tools, audit, idempotency]

requires:
  - phase: 11-mutation-capable-built-in-tools
    provides: Phase 11 mutation idempotency and audit coordinator
provides:
  - Durable persisted IDEMPOTENT_REPLAY audit outcomes
  - Agentstore Liquibase migration widening AI_AGENT_AUDIT_EVENT.OUTCOME
  - Replay regression asserting persisted AiAuditEvent rows
affects: [phase-11, audit, mutation-tools, agentstore]

tech-stack:
  added: []
  patterns: [agentstore includeAll schema widening, persisted audit-row regression]

key-files:
  created:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/071-widen-ai-audit-outcome.xml
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiAuditEvent.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsIdempotencyReplayTest.java

key-decisions:
  - "Preserved historical 030-ai-audit-event.xml unchanged and added ordered 071 widening migration for existing databases."
  - "Replay audit regression queries persisted AiAuditEvent rows by idempotency key through UnconstrainedDataManager."

patterns-established:
  - "Outcome enum additions that exceed existing audit width require both JPA metadata and agentstore Liquibase widening."

requirements-completed: [MUT-04, MUT-08, AUD-06, TEST-11, TEST-12]

duration: 8min
completed: 2026-04-29
---

# Phase 11 Plan 12: Replay Audit Durability Summary

**Persisted mutation replay audits now store and prove the full IDEMPOTENT_REPLAY outcome.**

## Performance

- **Duration:** 8 min
- **Started:** 2026-04-29T07:00:00Z
- **Completed:** 2026-04-29T07:08:10Z
- **Tasks:** 2
- **Files modified:** 3

## Accomplishments

- Widened `AiAuditEvent.outcome` metadata from 16 to 32 characters.
- Added agentstore changelog `071-widen-ai-audit-outcome.xml` using `modifyDataType` for `AI_AGENT_AUDIT_EVENT.OUTCOME`.
- Extended the idempotent replay regression to assert two persisted audit rows, with raw replay outcome `IDEMPOTENT_REPLAY`.

## Task Commits

1. **Task 1: Widen persisted audit outcome metadata** - `fcc4ed4` (fix)
2. **Task 2: Persisted replay audit regression** - `fcc4ed4` (fix)

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiAuditEvent.java` - widens JPA outcome column metadata.
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/071-widen-ai-audit-outcome.xml` - widens existing agentstore audit outcome column.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsIdempotencyReplayTest.java` - verifies persisted success and replay audit rows.

## Decisions Made

- Kept historical changelog `030-ai-audit-event.xml` immutable and added `071` for already-migrated databases.
- Used the existing fluent `UnconstrainedDataManager.load(AiAuditEvent.class)` path so Jmix resolves the `agentstore` store from entity metadata.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- First focused test run failed during Jmix security-role bootstrap with `MetaClass not found for class com.vn.agent.entity.AiAuditEvent` because entity enhancement was cached after the metadata change. A clean module rebuild re-enhanced main/test entities and the focused test passed.

## Validation

- `./gradlew -p ai-agent :ai-agent:compileJava` - passed.
- `./gradlew -p ai-agent :ai-agent:clean :ai-agent:test --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsIdempotencyReplayTest"` - passed.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Replay audit durability gap is closed. Plan 11-13 can address the remaining mutation-boundary PII gap.

---
*Phase: 11-mutation-capable-built-in-tools*
*Completed: 2026-04-29*
