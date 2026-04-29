---
phase: 11-mutation-capable-built-in-tools
verified: 2026-04-29T07:47:05Z
status: passed
score: "5/5 must-haves verified"
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: "4/5"
  gaps_closed:
    - "Mutation-boundary PII: MutationArgumentSanitizer now hashes every non-null configured sensitive field value, including object and array values using compact JSON hash input; streaming and fallback audit regressions cover scalar, object, array, and invalid input cases."
  gaps_remaining: []
  regressions: []
---

# Phase 11: Mutation-Capable Built-In Tools Verification Report

**Phase Goal:** Hosts can opt in to LLM-driven create / update / related-write operations that go through Jmix `DataManager`, are gated fail-closed by exposure policy + `AccessManager` (entity + per-attribute) + optional `MutationGuard` SPI, are idempotent, are audited end-to-end including rollback/commit-failed/replay, and never leak user-supplied PII through error, streaming, or fallback audit paths.
**Verified:** 2026-04-29T07:47:05Z
**Status:** passed
**Re-verification:** Yes - after follow-up mutation sanitizer fix.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Default config exposes zero mutation callbacks; enabling `ai-agent.tools.mutation.enabled=true` exposes `create_record`, `update_record`, `add_related_record`, `remove_related_record`, and never `delete_record`. | VERIFIED | `BuiltInMutationTools` is conditional on `ai-agent.tools.mutation.enabled=true`; `AgentToolCallbacks` uses `ObjectProvider<BuiltInMutationTools>` and appends mutation callbacks only when the bean exists; `delete_record` is absent from the tool methods. |
| 2 | Per-attribute modify denial blocks update, returns `access_denied`, avoids `DataManager.save`, and no raw PII leaks through boundary/error paths. | VERIFIED | Access gating was previously verified. The remaining PII gap is closed: `MutationArgumentSanitizer` hashes every non-null configured sensitive field value, using `value.asText()` for scalars and compact `value.toString()` JSON for objects/arrays; `MutationToolCallbackBoundaryDecorator` uses that `safeInput` for streaming `ToolCall` and fallback `AuditWriter.writeToolCall`. |
| 3 | Same `idempotencyKey` replays original result with `outcome=IDEMPOTENT_REPLAY`, one host write, and durable audit rows including replay/rollback/commit-failed windows. | VERIFIED | `AiAuditEvent.outcome` is length 32; `071-widen-ai-audit-outcome.xml` widens `AI_AGENT_AUDIT_EVENT.OUTCOME` to `varchar(32)`; `BuiltInMutationToolsIdempotencyReplayTest` asserts two persisted audit rows and raw `getOutcomeRaw() == "IDEMPOTENT_REPLAY"`. |
| 4 | Layered fail-closed chain runs in order: marker role, exposure policy, AccessManager CRUD + attributes, reservation, coercion, guard, transactional regular `DataManager.save`. | VERIFIED | `BuiltInMutationTools` calls marker-role enforcement before resolver/reservation; `MutationAuthorizationService` uses `CrudEntityContext` and `EntityAttributeContext`; `MutationIntentRepository.reserveOrReplay` precedes save; `MutationSaveExecutor` is the transactional save boundary and injects regular `DataManager`. |
| 5 | Locale keys exist; mutation event names/outcomes are observable on `AiAuditEvent`; `AiMutationIntent` TTL defaults to 24h. | VERIFIED | EN/VI bundles contain mutation error/status/outcome keys; `AiToolCallOutcome` includes `IDEMPOTENT_REPLAY` and `COMMIT_FAILED`; widened audit schema makes outcomes observable; `AiAgentMutationProperties.resolvedIdempotencyTtl()` returns `Duration.ofHours(24)`. |

**Score:** 5/5 roadmap truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `BuiltInMutationTools.java` | Conditional four-tool mutation component | VERIFIED | Exactly four `@Tool` methods; no shipped `delete_record`; self-audits via `MutationCommitCoordinator.safeWriteAudit`. |
| `MutationAuthorizationService.java` | Exact marker role, CRUD, attribute, exposure checks | VERIFIED | Uses exact Jmix resource-role authority equality plus `CrudEntityContext` and `EntityAttributeContext`. |
| `MutationSaveExecutor.java` | Transactional regular `DataManager` host writes | VERIFIED | `save`/`saveAll` are public `@Transactional`; no `UnconstrainedDataManager` host-save path. |
| `MutationIntentRepository.java` | Agentstore idempotency reservation/replay/finalization | VERIFIED WITH WARNING | Uses `UnconstrainedDataManager` and agentstore `REQUIRES_NEW`; UUID keys are validated but not canonicalized, so UUID case variants can create separate rows. |
| `AiMutationIntent.java` + `070-ai-mutation-intent.xml` | Dedup entity/table/indexes | VERIFIED | Agentstore Jmix entity with UUID, version, instance name, request hash, status, unique dedup index, expiry/status indexes. |
| `AiAuditEvent.java` + `071-widen-ai-audit-outcome.xml` | Durable replay outcome persistence | VERIFIED | Java metadata length 32; ordered changelog widens existing DBs; replay test asserts raw persisted value. |
| `MutationArgumentSanitizer.java` | Boundary-safe mutation argument sanitizer | VERIFIED | Hashes all non-null configured sensitive fields, including object/array values, and returns canned placeholders for unknown tools or invalid/non-object JSON. |
| `MutationToolCallbackBoundaryDecorator.java` | Sanitized streaming/fallback boundary | VERIFIED | Uses sanitized `safeInput` for `StreamingEvent.ToolCall` and fallback ERROR audit; still passes raw `toolInput` to the delegate as required. |
| `MutationToolCallbackBoundaryDecoratorSanitizerTest.java` | Regression coverage for scalar, object, array, and invalid input | VERIFIED | Focused test passed; assertions cover streaming args and persisted fallback audit args. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `AgentToolCallbacks.forCurrentUser` | `BuiltInMutationTools` | `ObjectProvider.getIfAvailable()` | WIRED | Mutation callbacks are appended only when the conditional bean is present. |
| `AgentToolCallbacks.forCurrentUser` | `MutationToolCallbackBoundaryDecorator` | Per-callback wrapper with `MutationArgumentSanitizer` dependency | WIRED | `AgentToolCallbacks` constructs every mutation wrapper with the sanitizer dependency. |
| `MutationToolCallbackBoundaryDecorator` | Streaming boundary | `safeInput` from `mutationArgumentSanitizer.sanitize(toolName, toolInput)` | WIRED | `StreamingEvent.ToolCall` receives `safeInput`, not raw `toolInput`. |
| `MutationToolCallbackBoundaryDecorator` | Fallback ERROR audit boundary | `AuditWriter.writeToolCall(... safeInput ...)` | WIRED | Delegate-thrown binding/invocation failures write sanitized arguments only. |
| `MutationToolCallbackBoundaryDecorator` | Delegate call | Original `toolInput` | WIRED | Sanitization only changes streaming/fallback audit surfaces; tool execution still receives raw arguments. |
| `MutationCommitCoordinator` replay path | `AuditWriter.writeToolCall` | `safeWriteAudit(... IDEMPOTENT_REPLAY ...)` | WIRED | Replay audit path writes through existing audit owner; outcome persistence is widened and tested. |
| `BuiltInMutationTools` | `MutationSaveExecutor` | `save`/`saveAll` | WIRED | Host writes cross a transactional proxy and use regular Jmix `DataManager`. |
| `AIConfiguration` | Default `MutationGuard` | `@ConditionalOnMissingBean(MutationGuard.class)` | WIRED | Hosts can override the no-op default. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| `BuiltInMutationTools` | Mutation result DTO | `MutationSaveExecutor.save/saveAll` | Yes | FLOWING |
| `MutationIntentRepository` | Reservation/replay state | `AI_MUTATION_INTENT` via `UnconstrainedDataManager` | Yes | FLOWING |
| `MutationCommitCoordinator` | Replay audit outcome | `AiToolCallOutcome.IDEMPOTENT_REPLAY` -> `AiAuditEvent.outcome` | Yes | FLOWING |
| `MutationArgumentSanitizer` | Sanitized JSON args | `AiAgentAuditProperties.resolvedSensitiveFields()` and `resolvedHashSensitiveFields()` | Yes | FLOWING |
| `MutationToolCallbackBoundaryDecorator` | `safeInput` | Sanitizer output capped to `ARGUMENTS_JSON_MAX_CHARS` | Yes | FLOWING |
| `DiffSerializer` | In-method audit args/diffs | Attribute maps and sensitive-field config | Yes | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Follow-up sanitizer regression | `.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.audit.MutationToolCallbackBoundaryDecoratorSanitizerTest"` | `BUILD SUCCESSFUL in 33s` | PASS |
| Plan 11-13 artifact check | `gsd-sdk query verify.artifacts .planning\phases\11-mutation-capable-built-in-tools\11-13-PLAN.md --raw` | `all_passed=true`, 4/4 artifacts | PASS |
| Plan 11-12 artifact check | `gsd-sdk query verify.artifacts .planning\phases\11-mutation-capable-built-in-tools\11-12-PLAN.md --raw` | `all_passed=true`, 3/3 artifacts | PASS |
| Plan 11-13 key-link SDK check | `gsd-sdk query verify.key-links .planning\phases\11-mutation-capable-built-in-tools\11-13-PLAN.md --raw` | Returned `Source file not found` because plan `from` fields are descriptive labels; manual key-link checks above verified the links. | SKIP |
| JetBrains file inspections | `get_file_problems` on sanitizer, boundary decorator, sanitizer test | Sanitizer: no issues. Boundary/test: only non-blocking warnings listed below. | PASS |

The user reported the full `./gradlew -p ai-agent :ai-agent:test` gate and JetBrains `build_project` passed after the fix. This verifier reran the focused sanitizer regression and source/artifact checks rather than the full suite.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| MUT-01 | 11-07A/11-09/11-10 | Separate default-off mutation component | SATISFIED | Conditional component + callback wiring verified. Note: `REQUIREMENTS.md` still shows MUT-01 unchecked, but code evidence satisfies it. |
| MUT-02 | 11-07A/11-07B | Four mutation tools, no delete | SATISFIED | Four `@Tool` names and no `delete_record`; tests assert absence. |
| MUT-03 | 11-07A/11-07B | Layered fail-closed gating and regular `DataManager` save | SATISFIED | Authorization service + save executor wiring verified. |
| MUT-04 | 11-01/11-05/11-12 | Mandatory idempotency key, dedup, replay, TTL | SATISFIED WITH WARNING | Replay durability fixed; UUID case canonicalization remains a warning. |
| MUT-05 | 11-03 | `MutationGuard` SPI | SATISFIED | SPI and default no-op bean present. |
| MUT-06 | 11-02 | Mutation properties defaults | SATISFIED | `resolvedEnabled=false`; TTL 24h. |
| MUT-07 | 11-06/11-13 | Stable errors and no PII leakage | SATISFIED | Translator avoids raw messages; mutation boundary sanitizer now hashes scalar/object/array sensitive field values and fails closed for invalid input. |
| MUT-08 | 11-07C/11-12/11-13 | Audit event names/outcomes, durable write path | SATISFIED | Event/outcome durability verified; fallback audit args use sanitized `safeInput`. |
| MUT-09 | 11-04 | Shared `ToolEntityResolver` | SATISFIED | Consumed by read and mutation paths. |
| MUT-10 | 11-09/11-11 | Conditional mutation prompt rules | SATISFIED | Composer wired; no `prepare_form_draft` in rules. |
| MUT-11 | Multiple | Locale keys in all bundles | SATISFIED | EN/VI keys found for entity, status, error, outcome paths. |
| MUT-12 | 11-09/11-10 | Default-config callback boot test | SATISFIED | Callback wiring and absence of `delete_record` verified from source/tests. |
| ENT-09 | 11-01 | `AiMutationIntent` entity | SATISFIED | Entity and Liquibase verified. |
| AUD-06 | 11-02/11-12 | Audit outcome/eventName extensions | SATISFIED | Enum values and widened outcome persistence verified. |
| AUD-07 | 11-07A/11-13 | Mutation diff + PII hashing | SATISFIED | In-method `DiffSerializer` hashes sensitive values; boundary sanitizer now hashes scalar/object/array configured sensitive fields. |
| SEC-07 | 11-01/11-07A | Explicit marker mutation role | SATISFIED | Empty role plus exact authority check in `MutationAuthorizationService`. |
| SPI-10 | 11-03 | `MutationGuard` SPI | SATISFIED | Interface and default bean present. |
| TEST-10 | 11-10/11-13 | Mutation gating integration | SATISFIED | Access gating and boundary sanitizer regressions verified. |
| TEST-11 | 11-10/11-12 | Idempotency replay | SATISFIED | Replay test asserts persisted audit row with `IDEMPOTENT_REPLAY`. |
| TEST-12 | 11-11/11-12/11-13 | Audit-vs-transaction windows | SATISFIED | Commit-failed/rollback/replay durability code and tests remain in place; boundary fallback audit regression passed. |
| TEST-13 | 11-10 | Default-config callback test | SATISFIED | Default/enablement callback tests exist and prior verification passed them; source wiring unchanged by sanitizer fix. |

No orphaned Phase 11 requirement IDs were found. The union of plan frontmatter IDs matches the requested set: MUT-01..12, ENT-09, AUD-06, AUD-07, SEC-07, SPI-10, TEST-10..13.

### Anti-Patterns Found

No blocker anti-patterns remain in the verified mutation sanitizer, boundary, replay audit, or test files.

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java` | 211 | UUID idempotency key validated but stored in caller text form | WARNING | Uppercase/lowercase UUID spellings can bypass logical idempotency. This is a prior review warning, not a phase-blocking gap. |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java` | 191 | Attribute access check before binder property validation | WARNING | Unknown attributes may surface as `access_denied` rather than stable `validation_failed`. This is a prior review warning, not a phase-blocking gap. |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java` | 176 | JetBrains duplicated-code weak warning | WARNING | Non-blocking maintainability warning; does not affect sanitizer correctness. |
| `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java` | 207 | JetBrains says `user != null` is always true | WARNING | Defensive fallback retained; does not affect sanitizer correctness. |
| `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecoratorSanitizerTest.java` | 248 | JetBrains suggests `List.getFirst()` | WARNING | Skipped because the project targets Java 17 and existing review guidance keeps `List.get(0)`. |

The `UNPARSEABLE_PLACEHOLDER` test constant is intentional fail-closed behavior, not a stub. `return null` matches in the inspected files are defensive helper fallbacks or nullable outcomes, not empty implementations.

### Human Verification Required

None. The remaining verification surface is source/test-level and was checked programmatically.

### Gaps Summary

No blocking gaps remain. The previous replay durability blocker was already closed, and the follow-up mutation sanitizer fix closes the remaining PII boundary blocker: scalar, object, and array values under configured sensitive field names are hashed before leaving through streaming or fallback audit paths, while invalid mutation input fails closed to a canned placeholder.

Two prior review warnings remain non-blocking: UUID idempotency key canonicalization and unknown-attribute error classification order. They are tracked above as warnings and do not prevent Phase 11 goal achievement.

---

_Verified: 2026-04-29T07:47:05Z_
_Verifier: the agent (gsd-verifier)_
