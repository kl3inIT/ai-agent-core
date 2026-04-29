---
phase: 11-mutation-capable-built-in-tools
verified: 2026-04-29T06:44:12Z
status: gaps_found
score: "2/5 must-haves verified"
overrides_applied: 0
gaps:
  - truth: "Idempotent replay and new mutation audit outcomes are durable and observable on AiAuditEvent rows"
    status: failed
    reason: "AiToolCallOutcome.IDEMPOTENT_REPLAY is 17 characters, but AiAuditEvent.outcome and the AI_AGENT_AUDIT_EVENT.OUTCOME Liquibase column are still length 16, so replay audit rows can fail or truncate."
    artifacts:
      - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java"
        issue: "Defines IDEMPOTENT_REPLAY(\"IDEMPOTENT_REPLAY\") with length 17."
      - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiAuditEvent.java"
        issue: "OUTCOME mapped as @Column(length = 16)."
      - path: "ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/030-ai-audit-event.xml"
        issue: "OUTCOME column is varchar(16)."
      - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationCommitCoordinator.java"
        issue: "Replay path writes AiToolCallOutcome.IDEMPOTENT_REPLAY through AuditWriter.writeToolCall."
    missing:
      - "Add a Liquibase changelog widening AI_AGENT_AUDIT_EVENT.OUTCOME to at least varchar(32)."
      - "Update AiAuditEvent @Column(name = \"OUTCOME\", length = 32)."
      - "Add a persisted replay-audit regression that asserts an AiAuditEvent row with outcome IDEMPOTENT_REPLAY."
  - truth: "Mutation error/boundary paths never leak user-supplied PII outside the sanitized mutation audit/result path"
    status: failed
    reason: "MutationToolCallbackBoundaryDecorator stores raw toolInput in cappedInput, emits it in StreamingEvent.ToolCall, and writes it to AuditWriter.writeToolCall on delegate-thrown binding failures. This bypasses DiffSerializer sensitive-field hashing."
    artifacts:
      - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java"
        issue: "Lines 115, 119, and 138-140 use capped raw toolInput for stream/audit fallback."
      - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/DiffSerializer.java"
        issue: "Sanitization exists for in-method mutation audit rows, but the callback boundary does not use it."
    missing:
      - "Add a mutation argument sanitizer for boundary ToolCall events and delegate-thrown ERROR audit rows."
      - "Reuse the same sensitive-field hashing policy as DiffSerializer for known mutation tool JSON."
      - "Add regression coverage for streaming ToolCall args and boundary error audit arguments with jmix.ai-agent.audit.sensitive-fields=secret."
---

# Phase 11: Mutation-Capable Built-In Tools Verification Report

**Phase Goal:** Hosts can opt in to LLM-driven create / update / related-write operations that go through Jmix `DataManager`, are gated fail-closed by exposure policy + `AccessManager` (entity + per-attribute) + optional `MutationGuard` SPI, are idempotent, are audited end-to-end (including rollback), and never leak user-supplied PII through error strings.
**Verified:** 2026-04-29T06:44:12Z
**Status:** gaps_found
**Re-verification:** No - initial verification. No previous `*-VERIFICATION.md` was present.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Default config exposes zero mutation callbacks; enabling `ai-agent.tools.mutation.enabled=true` exposes `create_record`, `update_record`, `add_related_record`, `remove_related_record`, and never `delete_record`. | VERIFIED | `BuiltInMutationTools` is `@ConditionalOnProperty(prefix = "ai-agent.tools.mutation", name = "enabled", havingValue = "true")`; exactly four `@Tool` methods exist. `AgentToolCallbacks` uses `ObjectProvider<BuiltInMutationTools>`. Callback tests assert default 8 callbacks, enabled 12 callbacks, and no `delete_record`. |
| 2 | Per-attribute modify denial blocks `update_record`, returns `access_denied`, avoids `DataManager.save`, and no raw user PII leaks through boundary/error paths. | FAILED | Access gating itself is implemented in `MutationAuthorizationService.enforceAttributeWriteAccess()` using `EntityAttributeContext.canModify`, and `BuiltInMutationToolsAccessGatingTest` verifies no `MutationSaveExecutor.save/saveAll`. However `MutationToolCallbackBoundaryDecorator` emits/writes raw `toolInput` on stream and binding-failure audit fallback, bypassing sensitive-field hashing. |
| 3 | Same `idempotencyKey` replays original result with `outcome=IDEMPOTENT_REPLAY`, one host write, both calls audited, rollback/commit-failed windows durable. | FAILED | Reservation/replay implementation and focused tests exist, but replay audit durability is not satisfied because `IDEMPOTENT_REPLAY` cannot safely fit in the persisted audit `OUTCOME` column length 16. |
| 4 | Layered fail-closed chain runs in order: mutation marker, exposure policy, AccessManager CRUD + attribute checks, reservation, coercion/validation, optional guard, transactional regular `DataManager.save`. | VERIFIED | `BuiltInMutationTools` calls `enforceMutationRole`, `ToolEntityResolver.resolveCreatable/UpdatableEntityOrThrow`, CRUD/attribute checks, `reserveOrReplay`, binder coercion, `MutationGuard.check`, then `MutationSaveExecutor.save/saveAll`. `MutationSaveExecutor` is `@Transactional` and injects regular `DataManager`, not `UnconstrainedDataManager`. |
| 5 | Locale keys exist; mutation event names/outcomes are observable on `AiAuditEvent`; `AiMutationIntent` TTL defaults to 24h. | FAILED | Locale keys and TTL default are present. Event names are written through `AuditWriter.writeToolCall`. `COMMIT_FAILED` fits, but `IDEMPOTENT_REPLAY` is not safely observable because audit column metadata/DDL remains length 16. |

**Score:** 2/5 roadmap truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `BuiltInMutationTools.java` | Conditional mutation tool component with four tool methods | VERIFIED | Exists, substantive, wired through `AgentToolCallbacks`; no `delete_record` method. |
| `MutationAuthorizationService.java` | Marker role, CRUD, per-attribute, exposure checks | VERIFIED | Uses exact Jmix role authority equality, `CrudEntityContext`, and `EntityAttributeContext`. |
| `MutationSaveExecutor.java` | Transactional regular `DataManager` host writes | VERIFIED | `@Transactional save/saveAll`; no `UnconstrainedDataManager` in host save path. |
| `MutationIntentRepository.java` | Idempotency reservation/replay/finalization | VERIFIED WITH WARNING | Uses `UnconstrainedDataManager` and agentstore `REQUIRES_NEW`; UUID keys are validated but not canonicalized, so uppercase/lowercase UUID text can create separate rows. |
| `AiMutationIntent.java` + `070-ai-mutation-intent.xml` | Agentstore dedup entity/table/indexes | VERIFIED | UUID id, version, instance name, request hash, status, unique dedup index, expiry/status indexes present. |
| `DiffSerializer.java` | Sanitized mutation arguments and diff audit payloads | PARTIAL | In-method mutation audit payloads hash configured sensitive fields; callback-boundary fallback does not use this sanitizer. |
| `AiAuditEvent.java` + `030-ai-audit-event.xml` | Persist mutation outcomes | FAILED | `OUTCOME` length remains 16 while `IDEMPOTENT_REPLAY` is 17 characters. |
| `MutationToolCallbackBoundaryDecorator.java` | Mutation callback stream/error boundary | FAILED | Substantive and wired, but raw `toolInput` leaks through stream/audit fallback. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `AgentToolCallbacks.forCurrentUser` | `BuiltInMutationTools` | `ObjectProvider.getIfAvailable()` | WIRED | Adds mutation callbacks only when conditional bean exists. |
| `AgentToolCallbacks.forCurrentUser` | `MutationToolCallbackBoundaryDecorator` | Per-callback wrapper | WIRED WITH GAP | Correctly avoids duplicate generic audit wrapping, but boundary args are not sanitized. |
| `BuiltInMutationTools` | `MutationAuthorizationService` | Entry checks in every mutation path | WIRED | Marker, CRUD, attribute, read/update relationship gates are called before save. |
| `BuiltInMutationTools` | `MutationIntentRepository` | `reserveOrReplay` before save, `markCommitted` after save | WIRED | Idempotency storage is real; replay audit persistence gap remains. |
| `BuiltInMutationTools` | `MutationSaveExecutor` | `save/saveAll` | WIRED | Host writes cross a transactional proxy and use regular Jmix `DataManager`. |
| `MutationCommitCoordinator` | `AuditWriter.writeToolCall` | `safeWriteAudit` | WIRED WITH GAP | Single in-method audit owner; replay outcome can exceed column length. |
| `AIConfiguration` | Default `MutationGuard` | `@ConditionalOnMissingBean(MutationGuard.class)` | WIRED | Host beans can override no-op default. |
| `MutationIntentCleanupJob` | `MutationIntentRepository.deleteExpired` | Hourly `@Scheduled` | WIRED | Deletes expired COMMITTED/FAILED only; logs PENDING/COMMIT_UNKNOWN. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `BuiltInMutationTools` | Mutation result DTO | `MutationSaveExecutor.save/saveAll` using regular `DataManager` | Yes | FLOWING |
| `MutationIntentRepository` | Reservation/replay state | `AI_MUTATION_INTENT` via `UnconstrainedDataManager` | Yes | FLOWING |
| `MutationCommitCoordinator` | Replay audit outcome | `AiToolCallOutcome.IDEMPOTENT_REPLAY` -> `AiAuditEvent.outcome` | Not safely persisted | FAILED |
| `MutationToolCallbackBoundaryDecorator` | Streaming/audit args | Raw `toolInput` capped string | Real but unsanitized | FAILED |
| `DiffSerializer` | In-method audit args/diffs | Raw attributes passed through sensitive-field hasher | Yes for in-method paths | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Callback shape, default-off/opt-in, access gating, idempotency replay tests | `.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.AgentToolCallbacksDefaultConfigTest" --tests "com.vn.agent.tools.mutation.AgentToolCallbacksMutationEnabledTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsIdempotencyReplayTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsAccessGatingTest"` | `BUILD SUCCESSFUL in 53s` | PASS |
| Supplied full regression gate | `.\gradlew -p ai-agent :ai-agent:test` | Provided gate evidence: `BUILD SUCCESSFUL` | PASS |
| Schema drift gate | Provided gate evidence | `drift_detected=false` | PASS |
| Codebase drift gate | Provided gate evidence | `skipped: no-structure-md` | SKIP |
| Static audit outcome length check | `rg -n "IDEMPOTENT_REPLAY|@Column\(.*OUTCOME|OUTCOME"` | Found enum value length 17 vs Java/DDL length 16 | FAIL |
| Static boundary PII check | Read `MutationToolCallbackBoundaryDecorator` lines 115, 119, 138-140 | Raw capped `toolInput` emitted and audited on fallback | FAIL |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| MUT-01 | 11-07 | Separate conditional mutation component, default off | SATISFIED | `BuiltInMutationTools` conditional bean; callback tests. |
| MUT-02 | 11-07 | Four v1.1 mutation tools, no delete | SATISFIED | Four `@Tool` methods; tests assert no `delete_record`. |
| MUT-03 | 11-07/11-10 | Layered fail-closed gating and regular DataManager save | SATISFIED | Authorization service + save executor wiring verified. |
| MUT-04 | 11-01/11-05/11-10 | Mandatory idempotency key, dedup table, replay, TTL | PARTIAL | Core implementation exists; replay audit persistence blocked by outcome column length. UUID case canonicalization warning remains. |
| MUT-05 | 11-03 | `MutationGuard` SPI and default no-op | SATISFIED | SPI and `AIConfiguration.noopMutationGuard()` present. |
| MUT-06 | 11-02 | Mutation properties defaults | SATISFIED | `AiAgentMutationProperties` resolved defaults: enabled false, TTL 24h. |
| MUT-07 | 11-06/11-11 | Stable error taxonomy, no raw exception text | PARTIAL | Translator uses canned messages, but callback-boundary fallback still exposes raw args to stream/audit. |
| MUT-08 | 11-07/11-07C | Audit via `writeToolCall`, new outcomes | BLOCKED | Single audit owner exists, but `IDEMPOTENT_REPLAY` cannot safely persist in `OUTCOME varchar(16)`. |
| MUT-09 | 11-04 | Shared `ToolEntityResolver` | SATISFIED | Consumed by read and mutation paths. |
| MUT-10 | 11-09/11-11 | Conditional mutation prompt rules | SATISFIED | Composer tests cover enabled/default and absence of `prepare_form_draft`. |
| MUT-11 | Multiple | Locale keys in all bundles | SATISFIED | EN/VI keys for entity/status/error/outcome paths found. |
| MUT-12 | 11-10/11-11 | Default-config boot test | SATISFIED | `AgentToolCallbacksDefaultConfigTest`; focused Gradle spot-check passed. |
| ENT-09 | 11-01 | `AiMutationIntent` entity | SATISFIED | Entity + Liquibase table/indexes verified. |
| AUD-06 | 11-02/11-07 | Outcome/eventName audit extensions | BLOCKED | Enum/event names exist; persisted `IDEMPOTENT_REPLAY` outcome length invalid. |
| AUD-07 | 11-07/11-10 | Mutation diff + sensitive-field hashing | PARTIAL | `DiffSerializer` hashes in-method audit paths; boundary fallback bypasses it. |
| SEC-07 | 11-01/11-10 | Explicit marker mutation role | SATISFIED | Empty role, exact authority check, tests for missing/fake marker. |
| SPI-10 | 11-03 | MutationGuard SPI | SATISFIED | Interface and default bean present. |
| TEST-10 | 11-10 | Mutation gating test | SATISFIED | Focused Gradle spot-check passed. |
| TEST-11 | 11-10 | Idempotency replay test | SATISFIED WITH WARNING | Exact-string replay test passes; UUID case variant not covered. |
| TEST-12 | 11-11 | Audit-vs-transaction tests | PARTIAL | Tests exist, but they do not catch the persisted outcome column length failure on strict DBs. |
| TEST-13 | 11-10 | Default-config callback test | SATISFIED | Focused Gradle spot-check passed. |

No orphaned Phase 11 requirement IDs were found. The plan frontmatter covers all requested IDs: MUT-01..12, ENT-09, AUD-06, AUD-07, SEC-07, SPI-10, TEST-10..13.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiAuditEvent.java` | 92 | `@Column(name = "OUTCOME", length = 16)` | BLOCKER | New 17-character replay outcome may not persist. |
| `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/030-ai-audit-event.xml` | 31 | `OUTCOME varchar(16)` | BLOCKER | Database schema can reject/truncate replay audit rows. |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java` | 115 | Raw capped `toolInput` | BLOCKER | Sensitive mutation arguments can leak to stream/audit fallback. |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java` | 211 | UUID key validated but original text stored | WARNING | Same UUID with different casing can bypass idempotency. |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java` | 191 | Attribute access checked before binder validates property existence | WARNING | Unknown attribute can surface as `access_denied` instead of stable `validation_failed`. |

Stub scan found no placeholder/TODO-only implementation in the Phase 11 mutation code. `return null` hits were enum lookup or defensive helper cases, not stubs.

### Human Verification Required

None. The blocking issues are source/schema-level and do not require visual or external-service validation. The absence of a Phase 11 `SECURITY.md` is advisory per gate evidence and did not drive this verdict.

### Gaps Summary

Phase 11 is not ready to proceed. The core mutation tool surface is real and mostly wired, and focused tests passed. The phase goal still fails in two places:

1. Replay audit durability is not guaranteed because `IDEMPOTENT_REPLAY` cannot safely fit in the persisted audit outcome column.
2. The mutation callback boundary leaks raw mutation arguments before the sanitized in-method audit path can run.

Later roadmap phases 12-14 do not explicitly cover these audit/privacy defects, so nothing is deferred.

---

_Verified: 2026-04-29T06:44:12Z_
_Verifier: the agent (gsd-verifier)_
