---
phase: 11-mutation-capable-built-in-tools
reviewed: 2026-04-29T07:22:30Z
depth: standard
files_reviewed: 7
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationArgumentSanitizer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiAuditEvent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/071-widen-ai-audit-outcome.xml
  - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecoratorSanitizerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsIdempotencyReplayTest.java
findings:
  critical: 0
  warning: 2
  info: 0
  total: 2
status: issues_found
---

# Phase 11: Post-Gap Code Review Report

**Reviewed:** 2026-04-29T07:22:30Z
**Depth:** standard
**Files Reviewed:** 7 gap-closure files, plus unresolved findings from the prior full Phase 11 review
**Status:** issues_found

## Summary

The two prior blocking findings are resolved:

- `IDEMPOTENT_REPLAY` now fits `AiAuditEvent.outcome` Java metadata and an ordered agentstore Liquibase widening migration.
- Mutation callback boundary streaming args and delegate-thrown fallback audit args now use sanitized mutation arguments before leaving the boundary.

Two warnings from the prior full Phase 11 review remain. They are not part of the `--gaps-only` closure plans and did not block the gap verification run.

## Resolved Blockers

### CR-01: Replay Audit Outcome Exceeds Persisted Column Length

**Status:** resolved by Plan 11-12.

`AiAuditEvent.outcome` is now `@Column(name = "OUTCOME", length = 32)`, and `071-widen-ai-audit-outcome.xml` widens `AI_AGENT_AUDIT_EVENT.OUTCOME` to `varchar(32)`. `BuiltInMutationToolsIdempotencyReplayTest` now asserts persisted `AiAuditEvent` rows include raw outcome `IDEMPOTENT_REPLAY`.

### CR-02: Mutation Boundary Leaks Raw Sensitive Arguments

**Status:** resolved by Plan 11-13.

`MutationToolCallbackBoundaryDecorator` now emits and fallback-audits `safeInput` from `MutationArgumentSanitizer`, while still passing original `toolInput` to the delegate. `MutationToolCallbackBoundaryDecoratorSanitizerTest` covers scalar, object, and array sensitive-field hashing plus invalid-input fail-closed placeholders.

## Warnings

### WR-01: WARNING - Attribute Validation Runs After AccessManager Context Construction

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java`

**Issue:** `create_record`, `update_record`, and related writes call `MutationAuthorizationService.enforceAttributeWriteAccess()` before the binder/resolver has validated that the attribute or relationship actually exists. This can make unknown attributes surface as `access_denied` or translator fallback instead of stable `validation_failed`, depending on role shape.

**Suggested fix:** Add a first validation pass that resolves all requested properties/relationships before constructing `EntityAttributeContext`, then run access checks on validated property names.

### WR-02: WARNING - UUID Idempotency Keys Are Validated But Not Canonicalized

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java`

**Issue:** UUID idempotency keys are validated but persisted in caller-provided text form. UUIDs with different casing can create separate dedup rows even though they represent the same UUID.

**Suggested fix:** Parse and canonicalize before reservation, lookup, audit arguments, and tests:

```java
String canonicalKey = UUID.fromString(idempotencyKey).toString();
```

## Validation Reviewed

- `./gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsIdempotencyReplayTest"` - passed after clean Jmix enhancement rebuild.
- `./gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.audit.MutationToolCallbackBoundaryDecoratorSanitizerTest"` - passed.
- `./gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.AgentToolCallbacksMutationAuditOwnershipTest"` - passed.
- `./gradlew -p ai-agent :ai-agent:test` - passed.
- JetBrains file inspections on gap-closure Java files - no blocking findings.
- JetBrains project build - passed.
