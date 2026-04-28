---
phase: 11
reviewers: [codex]
reviewed_at: 2026-04-28T17:41:42Z
plans_reviewed:
  - 11-01-PLAN.md
  - 11-02-PLAN.md
  - 11-03-PLAN.md
  - 11-04-PLAN.md
  - 11-05-PLAN.md
  - 11-06-PLAN.md
  - 11-07-PLAN.md
  - 11-08-PLAN.md
  - 11-09-PLAN.md
  - 11-10-PLAN.md
  - 11-11-PLAN.md
---

# Cross-AI Plan Review - Phase 11

## Codex Review

## Summary

The plan set is stronger than the prior review cycle and now addresses several major design gaps: default-off callback wiring, single audit ownership, request-hash idempotency, operation-specific create/update exposure gates, prompt-rule wiring, and a more credible TEST-12 strategy. I would still block execution. The remaining risks are concentrated in `11-07`: the mutation implementation plan still has compile/API hazards, relationship mutation is too broad for the concrete design provided, idempotency finalization can still produce duplicate host writes after partial failure, and the tests do not yet prove all of the most dangerous paths.

## Strengths

- `11-01` now explicitly adds `AiMutationIntentStatus` and enum-style accessors, resolving a prior compile/API ambiguity.
- `11-04` correctly separates `canCreate` and `canUpdate`, which avoids treating create-only users as update-denied.
- `11-05` improves idempotency materially with `REQUEST_HASH`, `PENDING/COMMITTED/FAILED`, and unique-index reservation.
- `11-09` correctly avoids wrapping mutation tools in `ToolCallbackAuditDecorator` while preserving streaming events via a separate decorator.
- `11-09` explicitly wires prompt rules through both blocking and streaming `DefaultChatServiceImpl` paths.
- `11-10` addresses the prior fixture-registration issue with metadata smoke assertions.
- `11-11` gives TEST-12 a much better proof shape by using a real transactional test executor instead of a spy-thrown exception.

## Concerns

- **HIGH: `11-07` still has compile and type-contract risk around IDs.** `ToolEntityResolver.parseEntityId(String, MetaClass)` returns `Object`, but the `11-07` snippets assign it to `UUID parsedId` / `UUID parsedRelatedId` and cast `EntityValues.getId(saved)` to `UUID`. This contradicts the resolver's typed-PK design and may fail to compile unless casts are added, then fail at runtime for non-UUID host entities.

- **HIGH: Relationship attributes inside `create_record` / `update_record` are not actually handled.** D-01 allows FK UUID strings inside `attributes`, but `applyAttributes()` calls `filterLiteralValueConverter.convertValue(...)` and then `EntityValues.setValue(entity, attr, coerced)`. For class-valued Jmix attributes, the entity property usually expects an entity instance, not a UUID. The plan needs explicit relationship detection, target entity load, target read check, and `not_found` behavior.

- **HIGH: Idempotency finalization can still allow duplicate host writes.** `11-07` says if `markCommitted` fails after the host mutation returned, mark the reservation `FAILED`. But `11-05` reclaims `FAILED` rows for retry, so the same idempotency key can execute the host mutation again even though the first host write may already be committed. `FAILED` needs to distinguish "failed before host commit" from "host commit outcome unknown/committed but finalization failed."

- **HIGH: Related-write implementation remains too underspecified.** `wireInverseReference`, `clearInverseReference`, `childBelongsToParent`, and composition/orphan detection are still described as helper behavior rather than a concrete supported relationship matrix. `11-10` only creates a simple fixture with `name` and `secret`; `11-11` needs concrete parent/child fixtures and Liquibase to make related-write tests meaningful.

- **HIGH: Related-write security matrix is inconsistent with the snippets.** `add_related_record` enforces child update but not child read. `remove_related_record` enforces child read and inverse attribute modify, but not child update for non-composition unlink. The must-have says child read/update/delete are covered, but the planned code path does not consistently enforce that.

- **HIGH: Stable error-code mapping still allows unsafe messages through.** `MutationErrorTranslator` passes through stable `ToolUserError`s unchanged. But `11-07` creates `validation_failed` errors that interpolate LLM-supplied invalid attribute/relationship names into LLM-visible messages. That violates the PII/no-raw-user-input error-string goal. The translator should sanitize/rebuild messages for mutation boundaries, not pass through arbitrary stable-code messages.

- **HIGH: Direct mutation-tool tests may fail without `RunContext` setup.** `BuiltInMutationTools` uses static `RunContext` accessors for conversation/run/root audit IDs, but `11-10` and `11-11` only mention `SystemAuthenticator.withUser(...)`. If `RunContext` is not initialized in direct tool tests, audit/idempotency calls may fail or produce null audit linkage.

- **MEDIUM: `MutationIntentCleanupJob` does not implement its stated stale-PENDING diagnostic.** `11-05` must-have says cleanup logs PENDING rows older than TTL, but the planned code only deletes expired rows.

- **MEDIUM: `MutationErrorTranslator` may catch the wrong Jmix access-denied type.** The plan uses `io.jmix.security.AccessDeniedException`; verify against the actual codebase/Jmix 2.8 imports. If the real type is `io.jmix.core.AccessDeniedException` or only Spring Security's type, this is a compile blocker.

- **MEDIUM: TEST-13 count assertions are weakened.** `11-10` says assert size 8/12 "only if no local contributors are present." The requirement is specifically about `AgentToolCallbacks.forCurrentUser()` shape. Name assertions are useful, but exact count should be enforced in this controlled test context.

- **LOW: Link tool result contract is inconsistent.** D-05 says raw URL string, while `11-08` returns JSON `{ "url": ... }`. Either is workable, but the contract and tests should choose one.

## Suggestions

- Change mutation ID handling to one consistent model: either explicitly support only UUID host IDs everywhere, or store/replay IDs as strings plus entity name and keep `parseEntityId` returning `Object`.
- Add a dedicated `coerceAttributeValue(MetaProperty, Object)` path that loads class-valued relationship targets via `DataManager` and returns `not_found` for valid UUIDs with no row.
- Add a terminal idempotency status such as `COMMIT_UNKNOWN` or `FINALIZATION_FAILED` and never reclaim it for automatic re-execution.
- Narrow related-write support for v1.1 to a documented subset, for example child-side to-one inverse only, plus composition remove only when metadata proves orphan removal. Fail closed for everything else.
- Add concrete `MutationParentFixture` / `MutationChildFixture` entities and Liquibase before writing related-write tests.
- Make `MutationErrorTranslator` rebuild all mutation-boundary `ToolUserError` messages from code + metaClass, preserving only safe `expected` hints.
- Add a test helper that establishes both authentication and `RunContext` before direct calls to `BuiltInMutationTools`.
- Make TEST-13 exact in the base context: 8 default callbacks, 12 enabled callbacks, zero `delete_record`.

## Risk Assessment

**Overall risk: HIGH.** The architecture is close, but the implementation plan still leaves the hardest part, generalized safe mutation, with too much ambiguity. The biggest risks are duplicate writes after idempotency finalization failure, relationship mutation correctness, unsafe relationship attribute coercion, and tests that may not initialize the same runtime context as chat execution. I would revise before autonomous execution.

## Prior HIGH Disposition

| Prior HIGH | Disposition | Reason |
|---|---:|---|
| `11-07` likely compile blockers | **PARTIALLY RESOLVED** | Parse argument order and status accessors are addressed, but `Object` -> `UUID` ID handling and several helper/API assumptions remain risky. |
| Stable mutation error-code mapping not enforced | **PARTIALLY RESOLVED** | Translator exists and tool catches route through it, but pass-through stable `ToolUserError` messages can still leak raw invalid attribute/relationship strings. |
| Idempotency finalization fragile | **PARTIALLY RESOLVED** | Reservation/hash/status improves concurrency, but post-host-commit `markCommitted` failure can still become `FAILED` and allow duplicate retry. |
| Related-write execution under-specified | **UNRESOLVED** | The plan adds desired behavior text, but no concrete supported relationship matrix or parent/child fixture exists. |
| TEST-12 does not prove rollback/audit guarantee | **FULLY RESOLVED** | `11-11` uses a real transactional test executor that saves then throws, which can prove rollback plus durable REQUIRES_NEW audit. |
| Mutation test fixture may not be registered in Jmix metadata | **FULLY RESOLVED** | `11-10` explicitly requires test module registration and `metadata.getClass("test_MutationTestFixture")` smoke assertion. |
---

## Consensus Summary

Only the Codex reviewer was invoked for this run because the command was `--codex`. A cross-reviewer consensus cannot be computed from a single reviewer.

This cycle leaves seven unresolved HIGH-severity concerns. Four are carried forward from the prior review cycle as unresolved or partially resolved, and three are newly raised or newly split out from the current plan review.

### Agreed Strengths

- Single-reviewer run; no multi-reviewer agreement threshold is available.
- Codex found the revised plan set stronger than the prior cycle, especially around default-off callback wiring, single audit ownership, request-hash idempotency, operation-specific create/update exposure gates, prompt-rule wiring, and TEST-12's transactional proof shape.

### Agreed Concerns

- Single-reviewer run; no multi-reviewer agreement threshold is available.
- Current unresolved HIGH concerns:
  - `11-07` still has compile and type-contract risk around ID handling.
  - Relationship attributes inside `create_record` / `update_record` are not actually handled.
  - Idempotency finalization can still allow duplicate host writes after partial failure.
  - Related-write implementation remains too underspecified.
  - Related-write security enforcement is inconsistent with the stated matrix.
  - Stable error-code mapping can still pass unsafe mutation-boundary messages through.
  - Direct mutation-tool tests may fail or prove the wrong thing without `RunContext` setup.

### Divergent Views

None. Only Codex was invoked in this cycle.
