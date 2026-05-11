---
phase: 13-chat-task-input-stt-task-scoped-file
plan: 03
subsystem: tools.mutation
tags:
  - mutation-tool
  - bulk-save
  - phase-11-extension
  - rich-tool-description
  - audit
  - idempotency
requires:
  - Phase 11 mutation chain (MutationAuthorizationService, MutationAttributeBinder,
    MutationRequestHasher, MutationIntentRepository, MutationGuard, MutationSaveExecutor,
    MutationCommitCoordinator, MutationErrorTranslator, DiffSerializer)
  - Phase 9 AgentSystemPromptRules.MUTATION_PROMPT_RULES extension point
  - Plan 13-01 AiTaskFile foundation (independent — bulk_save_records does not depend on it
    but the plan/wave structure colocates them under Phase 13)
provides:
  - "@Tool bulk_save_records on BuiltInMutationTools (mixed-batch create/update with id-presence dispatch)"
  - "MutationSaveExecutor.bulkSave(SaveContext) — single proxy-crossed @Transactional batch save"
  - "AiMutationIntent.RESULT_SUMMARY column (REVIEWS HIGH-11) so bulk replay returns original savedIds"
  - "AiAgentMutationProperties.bulkMaxRows DoS guard (default 100)"
  - "DiffSerializer.serializeBulkArgumentsJson / serializeBulkResultSummary / serializeBulkFailureSummary"
  - "MutationRequestHasher.hashCanonical(value) for per-row sample hashing"
affects:
  - DiffSerializer constructor — now takes MutationRequestHasher
  - MutationCommitCoordinator constructor — now takes ObjectMapper (for resultSummary parse)
  - MutationIntentRepository.markCommitted — new 4-arg overload (3-arg overload preserved)
  - BuiltInMutationTools constructor — new AccessManager dependency
  - AgentSystemPromptRules.MUTATION_PROMPT_RULES — new bulk-vs-single dispatch rule
tech-stack:
  added:
    - "io.jmix.core.SaveContext (already on classpath; first use in mutation tools)"
    - "io.jmix.core.AccessManager (already on classpath; first time injected into BuiltInMutationTools)"
  patterns:
    - "Spring proxy crossing for @Transactional on bulk save (bypassing the self-invocation pitfall)"
    - "id-presence dispatch via Map.containsKey(\"id\") (D-02 mixed-batch single-tool)"
    - "Per-row CrudEntityContext after entity load/create (REVIEWS HIGH-13)"
    - "Idempotency reservation extended with optional resultSummary JSON (REVIEWS HIGH-11)"
key-files:
  created:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/091-ai-mutation-intent-result-summary.xml
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationSaveExecutor.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/DiffSerializer.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiMutationIntent.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationCommitCoordinator.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationRequestHasher.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiAgentMutationProperties.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesComposerTest.java
key-decisions:
  - "@Transactional lives ONLY on MutationSaveExecutor.bulkSave — never on the @Tool method (Spring self-invocation pitfall)"
  - "Single audit row + single AiMutationIntent row per batch (D-02 invariant)"
  - "Use existing AiToolCallOutcome enum (ERROR/BLOCKED/COMMIT_FAILED); do NOT add a non-existent FAILED member (REVIEWS HIGH-6)"
  - "Per-row AccessManager.applyRegisteredConstraints(CrudEntityContext) AFTER entity load/create (REVIEWS HIGH-13)"
  - "REVIEWS HIGH-12: distinguish id-key absent (CREATE) from explicit id:null (validation_failed)"
  - "REVIEWS HIGH-11: persist bulk savedIds in AiMutationIntent.RESULT_SUMMARY for IDEMPOTENT_REPLAY"
  - "Rich @Tool description: 5 sections + 2 EXAMPLES (~85 lines) per memory feedback_rich_tool_descriptions"
metrics:
  duration: ~9m
  completed: 2026-05-06
  tasks_completed: 2
  files_created: 1
  files_modified: 10
---

# Phase 13 Plan 03: Bulk Save Records Tool — Summary

LLM-driven mixed-batch persistence for the Phase 11 mutation chain via the new
`@Tool bulk_save_records` on `BuiltInMutationTools`. Reuses every Phase 11 collaborator
unchanged plus four targeted extensions (HIGH-11 result summary, HIGH-12 id-null rejection,
HIGH-13 per-row access constraints, DoS bulk-max-rows guard).

## What Was Built

### Task 1 — Bulk-save executor + intent result summary + DoS guard (commit `9e92ba1`)

`MutationSaveExecutor.bulkSave(SaveContext)`: new `@Transactional` method that delegates to
`DataManager.save(saveContext)`. Single proxy-crossed boundary; any RuntimeException from
the underlying `DataManager.save` rolls back ALL rows in the SaveContext (D-02 rollback-all
invariant). The class JavaDoc already explains why this method must live here and not on
the `@Tool` bean.

`DiffSerializer` gained three new public methods + a constructor parameter:

- `serializeBulkArgumentsJson(entityName, records, idempotencyKey)` →
  `{entityName, count, sampleHashes:[...up to 3 SHA-256 hex...], idempotencyKey}`. Sample
  hashes come from the new `MutationRequestHasher.hashCanonical(value)` helper (canonical
  JSON via the existing `ORDER_MAP_ENTRIES_BY_KEYS` mapper). NEVER includes raw row values
  (P-22 / AUD-07).
- `serializeBulkResultSummary(savedIds)` → `{count, savedIds:[...UUID strings...]}`.
- `serializeBulkFailureSummary(failedRowIndex, errorCode, operation)` →
  `{failedRowIndex, errorCode, operation:"create"|"update"}`. NEVER echoes failed
  attribute names or values.

`MutationRequestHasher.hashCanonical(Object value)`: package-public helper that exposes
the canonical SHA-256 hash already used internally for `requestHash`. Lets `DiffSerializer`
hash individual records without duplicating the canonical-mapper configuration.

`AiMutationIntent.RESULT_SUMMARY` (REVIEWS HIGH-11): nullable `varchar(4000)` column added
via Liquibase changelog `091-ai-mutation-intent-result-summary.xml`. Auto-included by the
existing `<includeAll>` directive in `agentstore-changelog.xml`. Stores the bulk
`savedIds` JSON so a replay returns the original array. Single-record tools leave the
column null and behave identically to before.

`MutationIntentRepository.markCommitted` gains a 4-arg overload with the optional
`resultSummary` parameter; the original 3-arg signature is preserved by delegating with
`null`. The `findById` SELECT and `readIntent` row mapper read the new column too.

`MutationCommitCoordinator.replayResult` parses the persisted `resultSummary` (when
non-null) and surfaces it under the `"resultSummary"` key in the IDEMPOTENT_REPLAY result
map. `JsonProcessingException` is logged with marker
`AI_AGENT_MUTATION_REPLAY_RESULT_SUMMARY_PARSE_FAILED` and the replay outcome is preserved
without the summary (degrading gracefully).

`AiAgentMutationProperties.bulkMaxRows` (DoS guard): new optional `Integer` component on
the record. `resolvedBulkMaxRows()` defaults to 100 when unset or non-positive (a
misconfigured 0 must NOT silently disable the bulk tool). The single existing test
construction (`AgentSystemPromptRulesComposerTest`) was updated to pass the extra null.

### Task 2 — `bulk_save_records` `@Tool` + AgentSystemPromptRules rule (commit `58c57f9`)

`BuiltInMutationTools.bulkSaveRecords(entityName, records, idempotencyKey)`: new
`@Tool(name = "bulk_save_records", description = ...)` method. Description follows the
5-section MANDATORY / INPUT / FORMATS / ERROR / STRICTNESS template plus EXAMPLES with
TWO worked examples (xlsx onboarding all-create + PDF mixed update/create) per memory
`feedback_rich_tool_descriptions`. Roughly 85 lines of description text including all
sections.

Method body mirrors the existing `createRecord` skeleton and runs the Phase 11 chain
order:

1. `enforceMutationRole(AiAgentMutationRole.CODE)` — once.
2. **DoS guard**: empty/oversized batch → `validation_failed` BEFORE any DB work
   (`mutationProperties.resolvedBulkMaxRows()`).
3. **REVIEWS HIGH-12 id-null rejection**: any row with `containsKey("id") && get("id")==null`
   → `validation_failed` BEFORE dispatch.
4. Dispatch detection: `anyCreate = records.stream().anyMatch(r -> !r.containsKey("id"))`,
   `anyUpdate = records.stream().anyMatch(r -> r.containsKey("id") && r.get("id") != null)`.
5. Resolve metaClass via `resolveCreatableEntityOrThrow` and/or
   `resolveUpdatableEntityOrThrow`.
6. Entity-level CRUD gate (`enforceCreatePermission` and/or `enforceUpdatePermission`).
7. Per-attribute write gate via `enforceAttributeWriteAccess` over the UNION of keys
   across rows (excluding id).
8. `requestHash` via `mutationRequestHasher.hash("bulk_save_records", entityName, null,
   null, null, Map.of("records", records))` — Stripe-style byte-identical retry over
   submission order.
9. `reserveOrReplay`; on non-RESERVED state delegate to
   `mutationCommitCoordinator.handleReservationResult`. The replay path now surfaces the
   persisted `savedIds` array via the HIGH-11 column.
10. **Per-row loop**: coerce attributes; load/create entity; **REVIEWS HIGH-13** run
    `accessManager.applyRegisteredConstraints(new CrudEntityContext(metaClass))` AFTER
    entity is materialized so row-state-dependent constraints see live state; check
    `isUpdatePermitted`/`isCreatePermitted`; `mutationGuard.check`; apply attributes;
    `saveContext.saving(entity)`. `failedRowIndex` is captured for audit on any throw.
11. `mutationSaveExecutor.bulkSave(saveContext)` — single proxy-crossed `@Transactional`.
12. Build `savedIds` from `entitiesInOrder` (input order is the LLM contract — NOT the
    unordered `EntitySet` iterator). Defensive check that all entities are present in the
    saved set.
13. `markCommitted` with the bulk `resultSummary` JSON (REVIEWS HIGH-11).
14. `safeWriteAudit` once with success outcome.

**Catch ladder (REVIEWS HIGH-6)** — uses only the actual `AiToolCallOutcome` enum members:

- `ToolVetoedException` (MutationGuard veto) → `BLOCKED`.
- `AccessDeniedException` (per-row CrudEntityContext deny in step 10) → `BLOCKED` with
  denial reason `row_access_denied`.
- `ToolUserError` (validation_failed, parameter_conversion_error,
  idempotency_violation, unknown_entity, id-null rejection, oversized batch) → `ERROR`.
- `Throwable` other → `mutationCommitCoordinator.translateThrowableAfterReservation`
  + `auditOutcome`, which returns `COMMIT_FAILED` if `commitState == HOST_SAVE_RETURNED`
  and `ERROR` otherwise.

`@Tool bulkSaveRecords` carries NO `@Transactional` — verified by the build (the only
`@Transactional` references in `BuiltInMutationTools` are JavaDoc/comment text, never
annotations on methods).

`AccessManager` was added as a constructor dependency on `BuiltInMutationTools` for the
HIGH-13 per-row constraints.

`AgentSystemPromptRules.MUTATION_PROMPT_RULES` gained one new rule entry directing the LLM
to prefer `bulk_save_records` for ≥2 records of the same entity, generate a fresh UUID v4
per batch, and echo row count + 3 sample rows before invoking. The new rule is appended
inside `MUTATION_PROMPT_RULES` (which is already gated by `ai-agent.tools.mutation.enabled
=true` via `AgentSystemPromptRulesComposer`). All existing rule strings are byte-for-byte
unchanged so the Phase 9 TEST-08 cross-locale prompt-contract regression remains green.

## Verification Run

```
./gradlew :ai-agent:ai-agent:compileJava       # SUCCESSFUL (Task 1 + Task 2)
./gradlew :ai-agent:ai-agent:compileTestJava   # SUCCESSFUL after AgentSystemPromptRulesComposerTest fixup
```

Grep gates (all pass):

| Gate | Check | Result |
|------|-------|--------|
| HIGH-11 | `RESULT_SUMMARY` in AiMutationIntent + Liquibase 091 | present |
| HIGH-11 | `markCommitted` 4-arg overload exists | present |
| HIGH-12 | `containsKey("id") && row.get("id") == null` rejection | present (line 882) |
| HIGH-13 | `applyRegisteredConstraints` + `new CrudEntityContext` inside row loop | present (lines 968–969) |
| HIGH-6 | Zero `AiToolCallOutcome.FAILED` references | confirmed (grep -c reports 0) |
| HIGH-6 | Outcomes use ERROR/BLOCKED/COMMIT_FAILED/SUCCESS/IDEMPOTENT_REPLAY only | confirmed |
| Self-invoc | Zero `@Transactional` annotations on tool methods | confirmed (7 grep hits all in JavaDoc/comments) |
| Tool name | `@Tool(name = "bulk_save_records", ...)` present | line 767 |
| Save proxy | `mutationSaveExecutor.bulkSave(saveContext)` called once | line 990 |
| Description | MANDATORY WORKFLOW + EXAMPLES sections present | line 768, 816 |
| Prompt rule | `bulk_save_records` in AgentSystemPromptRules.MUTATION_PROMPT_RULES | lines 116–118 |
| DoS guard | `mutationProperties.resolvedBulkMaxRows()` enforced before DB work | line 867 |
| Liquibase | 091 changelog auto-picked by `<includeAll>` | confirmed |

## Decisions Made

- **Single proxy crossing on `MutationSaveExecutor.bulkSave`.** Adding `@Transactional`
  to `bulkSaveRecords` would silently no-op (Spring proxy self-invocation). The plan,
  CONTEXT D-02, REVIEWS, and PATTERNS all converge on the same pattern Phase 11 already
  uses for `save` and `saveAll`.
- **One reservation, one audit, one intent row per BATCH** (not per row). This is the
  D-02 atomic-batch contract that lets idempotency, audit, and rollback semantics line up
  with how the LLM thinks about the call.
- **REVIEWS HIGH-6 outcome enum reuse over schema mutation.** `AiToolCallOutcome.FAILED`
  was referenced in the original draft of the plan but does not exist on the enum. Using
  the existing `ERROR` (validation), `BLOCKED` (deny / veto), `COMMIT_FAILED` (post-save
  finalize failure) gives the LLM a consistent error vocabulary across all mutation tools
  without a Liquibase enum widening migration.
- **REVIEWS HIGH-11 `RESULT_SUMMARY` column instead of redesign.** A single nullable
  4000-char column is the smallest possible schema change. Single-record tools pass null
  and behave identically; bulk tools persist the savedIds JSON. The cleaner alternative
  (a separate `AiMutationIntentResult` table) was rejected as over-engineered for what is
  effectively a denormalized cache.
- **REVIEWS HIGH-12 distinct dispatch via `containsKey("id")`.** Treating `id: null`
  identically to a missing `id` would silently coerce malformed LLM output into surprising
  CREATEs. The contract now rejects explicit `id: null` as `validation_failed` and only
  treats key-omitted as CREATE.
- **REVIEWS HIGH-13 per-row CrudEntityContext.** MUT-14 literally requires per-row
  evaluation; entity-level checks once per batch miss row-level policies that depend on
  loaded entity state (e.g. status-based deny-rules). Running a fresh `CrudEntityContext`
  inside the loop after `dataManager.load`/`dataManager.create` is the smallest correct
  fix.
- **DoS guard via `bulk-max-rows` (default 100).** Cheap fail-closed check before any DB
  work. Threshold is configurable; misconfigured 0 falls back to the default rather than
  silently disabling the tool.
- **Use `entitiesInOrder` for `savedIds`, not the `EntitySet` iterator.** The LLM
  contract is `savedIds[i]` corresponds to `records[i]`. `EntitySet` iteration order is
  not guaranteed, so we capture entities in submission order during the build phase and
  read ids from those references after `bulkSave` returns.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 — Blocking] DiffSerializer constructor wiring for sample-hash reuse**
- **Found during:** Task 1 implementation
- **Issue:** The plan's DiffSerializer extension needs a canonical hasher to produce
  `sampleHashes` without copy-pasting the canonical-mapper configuration that already
  lives in `MutationRequestHasher`.
- **Fix:** Added a `MutationRequestHasher` constructor parameter to `DiffSerializer` and
  exposed `MutationRequestHasher.hashCanonical(Object value)` so both classes share one
  canonical Jackson configuration. Spring autowires both — no test changes needed.
- **Files modified:** `DiffSerializer.java`, `MutationRequestHasher.java`
- **Commit:** `9e92ba1`

**2. [Rule 3 — Blocking] MutationCommitCoordinator constructor wiring for replay-summary parse**
- **Found during:** Task 1 implementation
- **Issue:** Surfacing the persisted `resultSummary` JSON on `IDEMPOTENT_REPLAY` requires
  parsing it back to a `Map<String,Object>` for the result envelope. The coordinator did
  not previously have an `ObjectMapper`.
- **Fix:** Added an `ObjectMapper` constructor parameter (Spring already provides a bean).
  `JsonProcessingException` is logged with a sanitized marker and the replay degrades to
  the old shape (entityId-only) rather than failing.
- **Files modified:** `MutationCommitCoordinator.java`
- **Commit:** `9e92ba1`

**3. [Rule 1 — Bug] Defensive `EntitySet` membership check after bulkSave**
- **Found during:** Task 2 implementation
- **Issue:** A host entity listener could in theory drop a row from the saved
  `EntitySet`. Without a check the LLM would receive `savedIds` for entities that did not
  actually persist, and the rollback contract would be silently violated.
- **Fix:** After `bulkSave` returns, iterate `entitiesInOrder` and call
  `saved.optional(entity).isPresent()`. If any row is missing throw
  `IllegalStateException` so the catch-all promotes to `COMMIT_FAILED` (the host save
  returned but something dropped a row, which is the semantic of `commit-unknown`).
- **Files modified:** `BuiltInMutationTools.java`
- **Commit:** `58c57f9`

**4. [Rule 2 — Critical functionality] `AccessDeniedException` catch branch**
- **Found during:** Task 2 implementation
- **Issue:** The plan calls for per-row `accessManager.applyRegisteredConstraints` that
  throws `AccessDeniedException` on row deny. Without a dedicated catch branch, that
  exception falls into the catch-all `Throwable` branch where it would be re-translated
  through `commitFailed` if `commitState == HOST_SAVE_RETURNED`, masking the security
  signal.
- **Fix:** Added an explicit `AccessDeniedException` catch BEFORE the `ToolUserError`
  branch. Maps to `BLOCKED` outcome with `denialReason = "row_access_denied"`. Preserves
  the security audit trail.
- **Files modified:** `BuiltInMutationTools.java`
- **Commit:** `58c57f9`

**5. [Rule 3 — Blocking] Test fixture — AgentSystemPromptRulesComposerTest**
- **Found during:** Task 1 compile
- **Issue:** Adding `bulkMaxRows` as a record component changed the `AiAgentMutationProperties`
  canonical constructor arity. The single existing direct-construction site
  (`AgentSystemPromptRulesComposerTest`) failed to compile.
- **Fix:** Pass an extra `null` for the new component in both test invocations. No
  behaviour change — `resolvedBulkMaxRows()` falls back to the default.
- **Files modified:** `AgentSystemPromptRulesComposerTest.java`
- **Commit:** `9e92ba1`

### Auth Gates

None — Task 1 and Task 2 are entirely code work, no third-party authentication needed.

## Known Stubs

None — every code path in `bulk_save_records` either persists, audits, or surfaces a
stable error code. The Liquibase 091 changelog applies on next app boot via the existing
`<includeAll>` directive (no manual `<include>` line needed; matches the Phase 11
precedent).

## Self-Check: PASSED

- `MutationSaveExecutor.bulkSave(SaveContext)` — present at line 65, annotated
  `@Transactional`, calls `dataManager.save(saveContext)` once.
- `DiffSerializer.serializeBulkArgumentsJson` / `serializeBulkResultSummary` /
  `serializeBulkFailureSummary` — all three present.
- `AiMutationIntent.resultSummary` field + getter/setter — present.
- Liquibase `091-ai-mutation-intent-result-summary.xml` — present, auto-included.
- `MutationIntentRepository.markCommitted` 4-arg overload — present.
- `MutationCommitCoordinator.replayResult` reads `resultSummary` and surfaces it on
  `IDEMPOTENT_REPLAY` — present.
- `AiAgentMutationProperties.resolvedBulkMaxRows()` — present, default 100.
- `BuiltInMutationTools.bulkSaveRecords` `@Tool` method — present at line 776, calls
  `mutationSaveExecutor.bulkSave` exactly once, no `@Transactional` annotation.
- `AgentSystemPromptRules.MUTATION_PROMPT_RULES` — bulk-save rule present, existing rules
  byte-for-byte unchanged.
- `./gradlew :ai-agent:ai-agent:compileJava` — BUILD SUCCESSFUL.
- `./gradlew :ai-agent:ai-agent:compileTestJava` — BUILD SUCCESSFUL.
- Commits `9e92ba1` and `58c57f9` exist on the current branch.
