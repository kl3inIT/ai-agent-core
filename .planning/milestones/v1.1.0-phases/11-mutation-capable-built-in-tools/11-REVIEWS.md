---
phase: 11
reviewers: [codex]
reviewed_at: 2026-04-28T17:51:55Z
plans_reviewed: [11-01-PLAN.md, 11-02-PLAN.md, 11-03-PLAN.md, 11-04-PLAN.md, 11-05-PLAN.md, 11-06-PLAN.md, 11-07-PLAN.md, 11-08-PLAN.md, 11-09-PLAN.md, 11-10-PLAN.md, 11-11-PLAN.md]
---

# Cross-AI Plan Review — Phase 11

## Codex Review

## Summary

The plans are unusually thorough and cover the right Phase 11 surfaces: default-off mutation tools, layered authorization, idempotency, audit durability, prompt rules, callback wiring, and regression tests. The main weakness is that the hardest parts are still under-specified or internally inconsistent: idempotency retry semantics, cross-store finalization failure, direct-tool test setup, and related-write behavior. I would treat this as a strong draft, but not execution-ready until the HIGH concerns below are resolved.

## Strengths

- Clear build order: entity/config/SPI first, resolver/repository/translator next, tools and wiring later, tests last.
- Strong security posture: regular `DataManager` for host mutations, `AccessManager` per CRUD and attribute, LLM exposure gate above Jmix permissions, no `delete_record`.
- Good default-off contract with callback-level testing at `AgentToolCallbacks.forCurrentUser`.
- Audit ownership is explicitly handled to avoid duplicate audit rows for self-audited mutation tools.
- Good recognition of Spring proxy boundaries by moving `@Transactional` save/remove into `MutationSaveExecutor`.
- Tests target the right regressions: callback shape, access denial before save, idempotency replay, commit-failed audit, prompt-rule gating, scanner coverage.

## Concerns

- **HIGH: Parallel wave conflicts.** Wave 1 plans edit overlapping files: `AIConfiguration.java` in 11-02 and 11-03, message bundles in 11-01 and 11-02. Later waves also overlap message files. If these are executed in parallel, merge conflicts are likely.

- **HIGH: Idempotency retry semantics conflict.** 11-05 treats same key + different request hash as `idempotency_violation` even for `FAILED` rows. But D-04 says `validation_failed` should be retried with the same `idempotencyKey` after fixing values, which usually changes the request hash.

- **HIGH: Finalization failure can cause duplicate mutations.** 11-07 says if `markCommitted` fails after the host save commits, mark the reservation `FAILED`. A retry can then run the host mutation again, defeating idempotency.

- **HIGH: `AiAgentMutationRole` grants READ on all `AiMutationIntent` rows.** Replay uses `UnconstrainedDataManager`, so user READ is not needed. Without row-level scoping, this can expose idempotency keys, usernames, conversation IDs, and result entity IDs to mutation-role users.

- **HIGH: Relationship attribute assignment is under-specified.** `applyAttributes` appears to set relationship properties from UUID strings directly. Jmix entity references generally need loaded entity instances, not raw UUIDs. This is central to D-01.

- **HIGH: Related-write implementation is too vague for a security-sensitive feature.** Helpers like `wireInverseReference`, `clearInverseReference`, `childBelongsToParent`, and composition handling are sketched but not concretely specified. Many-to-many, inverse collections, composition children, and ownership detection are high-risk.

- **HIGH: Direct tool integration tests likely need `RunContext`.** Plans use `SystemAuthenticator.withUser(...)`, but `BuiltInMutationTools` also depends on static `RunContext` values for audit/conversation. Tests may fail or silently miss audit paths unless they set/clear `RunContext`.

- **HIGH: Related-write tests need concrete relationship fixtures.** 11-10’s fixture only guarantees `name` and `secret`, while 11-11 requires parent/child relationship coverage. The fixture design is not sufficient.

- **MEDIUM: Mandatory `idempotencyKey` validation is missing.** Tools accept `String idempotencyKey`, but plans do not explicitly validate nonblank UUID format before reservation.

- **MEDIUM: Stale `PENDING` rows block until cleanup.** `reserveOrReplay` returns `PENDING` even if the row has expired but the hourly job has not yet deleted it.

- **MEDIUM: Link tools return JSON object, while D-05 says raw URL string.** Either is fine, but the contract should be consistent.

- **MEDIUM: URL path segments are not encoded or validated.** `generate_entity_detail_link` appends arbitrary `entityId`; it should validate/encode to avoid malformed links.

- **LOW: Plans are very prescriptive.** 11-07 in particular contains large pseudo-code blocks that may not compile against actual APIs. The executor should be allowed to adapt while preserving behavioral invariants.

## Suggestions

- Serialize overlapping plans or split shared-file edits into one foundation plan, especially `AIConfiguration.java` and message bundles.
- Decide idempotency recovery rules explicitly:
  - If a mutation never committed, allow same key with corrected request hash for selected error codes, or change the recovery hint to require a fresh key.
  - If commit state is unknown after host save, do not mark the intent `FAILED` in a way that permits duplicate writes.
- Make `AiAgentMutationRole` an empty marker role, or add row-level “own rows only” policy before granting READ.
- Add explicit idempotency key validation: nonblank UUID, stable `parameter_conversion_error` on malformed input, before repository reservation.
- For relationship attributes in `create_record` / `update_record`, explicitly load referenced entities by ID and set the entity instance, with read/write checks on the target.
- Narrow v1.1 related-write support to well-defined relationship shapes. For unsupported metadata shapes, fail closed with `validation_failed`.
- Expand the test fixture to include concrete parent/child entities and at least one supported relationship type before writing related-write tests.
- Add a reusable test helper for authenticated `RunContext` setup/cleanup, and require every direct tool-call test to use it.
- Make link-tool output contract consistent: either raw string or `{ "url": "..." }`, then update descriptions/tests accordingly.
- Use bulk delete for cleanup if volume may be high, or at least document row-count expectations.

## Risk Assessment

**Overall risk: HIGH** before revision. The plans cover the right goals, but the remaining gaps are in the most failure-sensitive areas: idempotency under failure, mutation finalization, related-write security, and test realism. Once those are corrected, the risk drops to **MEDIUM** because the broader architecture and sequencing are sound.


---

## Consensus Summary

Single-reviewer run with --codex; no cross-reviewer consensus can be computed. Treat the Codex HIGH concerns as the current priority feedback for replanning.

### Agreed Strengths

- Not applicable for a single-reviewer run.

### Agreed Concerns

- Idempotency finalization and retry semantics still need a deterministic no-duplicate contract.
- Relationship attributes and related-write behavior need concrete, security-aware implementation rules.
- Test fixtures and direct tool-call test setup need to prove the planned mutation paths under realistic Jmix context.

### Divergent Views

- Not applicable for a single-reviewer run.
