# Phase 17: Mutation-Internals Hardening (Phase 11 follow-up) — Specification

**Created:** 2026-05-30
**Ambiguity score:** 0.15 (gate: ≤ 0.20)
**Requirements:** 4 locked

## Goal

The fail-closed mutation gate sequence currently duplicated across five `@Tool` methods is consolidated into one canonical `MutationGateChain` component (with the four mutation tools and `bulk_save_records` as thin adapters), to-one FK references are batch-loaded via one constrained `DataManager.load(...).ids(...)` per target class, and related-write metadata resolution is memoized — with behavior byte-for-byte identical to v1.1, so every Phase 9/10/11 mutation test passes unchanged.

## Background

Phase 11 shipped the LLM-mediated mutation surface in `BuiltInMutationTools` (`ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/`, default OFF via `@ConditionalOnProperty(ai-agent.tools.mutation.enabled)`). Three internal-quality gaps remain from that phase:

1. **Duplicated gate sequence.** The fail-closed sequence — marker role (`AiAgentMutationRole`) → entity resolution + LLM exposure → `AccessManager` CRUD + per-attribute → `AiMutationIntent` idempotency reserve/replay → attribute coerce → `MutationGuard` SPI veto → `@Transactional` save via `MutationSaveExecutor` — is hand-written inline in `createRecord`, `updateRecord`, the shared `executeRelatedWrite` (add/remove), and `bulkSaveRecords`. There is intentionally **no `@Transactional` on `BuiltInMutationTools`** (Spring self-invocation pitfall); only `MutationSaveExecutor.save/saveAll` crosses the proxy. The duplication is the surface Phase 18's perf pass must not refactor in four places.

2. **Per-reference FK loads.** `MutationAttributeBinder.coerceAttributeValue` loads each to-one FK reference one at a time: `dataManager.load(targetClass).id(targetId).optional()` (constrained DataManager, `enforceReadPermission` first). For `bulk_save_records` with N rows each carrying FK references to the same target class, this is N separate SELECTs — the genuine N+1.

3. **Un-memoized metadata walk.** `RelatedWriteMetadataResolver.resolveSupportedRelatedWriteRelationship(parentMetaClass, relationshipName)` walks the Jmix metamodel fresh on every related-write call (no `ConcurrentHashMap`/`computeIfAbsent` today). The metamodel is immutable at runtime.

The Phase 9/10/11 mutation test suites (~30 test files under `tools/mutation/`, `audit/`, `guard/`, `security/`) lock the externally observable behavior and must not be edited.

## Requirements

1. **Canonical `MutationGateChain` (MUT-15)**: The fail-closed gate sequence is extracted into one `@Component`; the five mutation entry points become thin adapters.
   - Current: gate sequence duplicated inline in `createRecord`, `updateRecord`, `executeRelatedWrite`, and `bulkSaveRecords`; no `@Transactional` on `BuiltInMutationTools`.
   - Target: one `MutationGateChain` `@Component` carries the canonical sequence (marker-role → resolve+exposure → `AccessManager` entity+attribute → `AiMutationIntent` idempotency → coerce → `MutationGuard` SPI → `@Transactional` save delegated to `MutationSaveExecutor`); `create_record` / `update_record` / `add_related_record` / `remove_related_record` / `bulk_save_records` are thin adapters over it. `MutationGateChain` carries **no** `@Transactional` — only `MutationSaveExecutor.save/saveAll` does.
   - Acceptance: a source-level invariant test enforces the gate order AND asserts every gate throws before the transactional save; an assertion confirms `MutationGateChain` has no `@Transactional` annotation.

2. **Batch-loaded to-one FK references (MUT-16)**: To-one FK refs are batch-loaded per target class instead of per reference.
   - Current: `MutationAttributeBinder.coerceAttributeValue` issues one `dataManager.load(...).id(...)` per to-one reference; `bulk_save_records` therefore issues N loads for N rows referencing the same target class.
   - Target: `bulk_save_records` collects all to-one FK ids across **all rows** and issues **one** constrained `DataManager.load(targetClass).ids(...)` per target class for the whole batch; `create_record`/`update_record` dedup to-one references within a single call. Loads use the **constrained** `DataManager` only (never `UnconstrainedDataManager`, never raw JPQL), so row-level security still applies. (`create`/`update` single-row dedup is in scope; cross-row batching is the primary N+1 fix.)
   - Acceptance: a SELECT-count / "1 query not N" proxy confirms `bulk_save_records` issues one FK load per target class for a batch of K rows referencing the same target class; a source/static assertion confirms the FK-load path uses no `UnconstrainedDataManager` and no raw JPQL.

3. **Memoized related-write metadata (MUT-17)**: Related-write relationship resolution is memoized.
   - Current: `RelatedWriteMetadataResolver.resolveSupportedRelatedWriteRelationship` walks the metamodel fresh on every call; no cache.
   - Target: `(parentMetaClass, relationshipName)` → `SupportedRelatedRelationship` descriptor is memoized (immutable Jmix metamodel; no eviction needed).
   - Acceptance: a call-count assertion confirms the metadata walk executes once per distinct `(parentMetaClass, relationshipName)` key across repeated calls.

4. **Byte-for-byte behavior parity (MUT-18)**: Observable behavior is identical to v1.1.
   - Current: behavior defined by the Phase 9/10/11 mutation test suites + default-config boot test.
   - Target: identical gating outcomes and order, identical exception classification and `MutationErrorTranslator` outputs, identical audit rows (including rollback rows), identical idempotency semantics, identical `MutationGuard` SPI contract. FK not-found / not-readable yields the byte-identical error code (`not_found` vs `access_denied`), the same `failedRowIndex`, and full-batch rollback as the per-reference baseline.
   - Acceptance: `MutationToolInvariantsTest`, gating-order, audit-row, error-translator, host-guard-veto, and row-level mutation-security tests pass with **zero test-body edits**; the default-config zero-mutation-callback boot test passes unchanged.

## Boundaries

**In scope:**
- A `MutationGateChain` `@Component` encapsulating the canonical fail-closed sequence.
- `create_record` / `update_record` / `add_related_record` / `remove_related_record` / `bulk_save_records` reduced to thin adapters over the chain.
- A source-level invariant test for gate order + "all gates throw before transactional save" + "no `@Transactional` on the chain".
- Batch FK loading: cross-row batching for `bulk_save_records` (one constrained `.ids(...)` load per target class), single-call dedup for `create`/`update`.
- A SELECT-count proxy ("1 query not N") and the static "constrained-DataManager-only / no-raw-JPQL" assertion for the FK path.
- Memoization of `(parentMetaClass, relationshipName)` → descriptor in `RelatedWriteMetadataResolver`, with a call-count proxy.
- Low-risk adjacent internal improvements (e.g. a similar in-class memoization) **only when** all three hold: no new public surface, byte-for-byte behavior preserved, existing suites pass unchanged.

**Out of scope:**
- Per-turn memoization of schema / readable-entity metadata / `AccessManager` / `LlmExposurePolicy` resolution — that is Phase 18 (PERF-01/02).
- App-wide exposure denylist / metadata caches and `LlmExposureChangedEvent` eviction wiring — Phase 18.
- RAG `Filter.Expression`, media/attachment, and prompt-construction memoization — Phase 18.
- Any new admin UI, config knob, `@Tool`, or `@Tool`/`@ToolParam` description change — this is internals-only; the model-facing contract is frozen.
- `delete_record` — never shipped under any flag in v1.x (D-07).
- Any change to gating outcomes, error codes, audit row shape, or idempotency semantics — behavior is frozen.
- Editing the bodies of the Phase 9/10/11 mutation test suites.
- Benchmark harness or admin-screen performance work.

## Constraints

- **Byte-for-byte behavior parity (MUT-18)** governs every change: same gating order/outcomes, exception classification, `MutationErrorTranslator` outputs, audit rows (incl. rollback), idempotency semantics, and `MutationGuard` SPI contract.
- FK batch-load MUST use the **constrained** `DataManager` `.ids(...)` — never `UnconstrainedDataManager`, never raw JPQL — so row-level security is preserved.
- FK not-found / not-readable during batch-load MUST classify to the byte-identical error code, the same `failedRowIndex`, and whole-batch rollback as the current per-reference load.
- `MutationGateChain` MUST NOT be annotated `@Transactional`; only `MutationSaveExecutor.save/saveAll` crosses the transaction boundary, and every gate MUST throw before the save (fail-closed).
- Memoization is keyed on the immutable Jmix metamodel — **no eviction** logic.
- **HARD ORDERING: Phase 17 must precede Phase 18** — the perf pass refactors the consolidated chain + shared batch-FK load, not a duplicated sequence.
- No new runtime dependencies.

## Acceptance Criteria

- [ ] A single `MutationGateChain` `@Component` holds the canonical fail-closed sequence, and the four mutation `@Tool` methods + `bulk_save_records` delegate to it as thin adapters.
- [ ] A source-level invariant test asserts the gate order AND that every gate throws before the transactional save; `MutationGateChain` carries no `@Transactional` (only `MutationSaveExecutor.save/saveAll` does).
- [ ] `bulk_save_records` issues exactly one constrained `DataManager.load(...).ids(...)` per target class for a K-row batch referencing the same target class (SELECT-count "1 query not N" proxy passes).
- [ ] A static/source assertion confirms the FK-load path uses no `UnconstrainedDataManager` and no raw JPQL.
- [ ] FK not-found / not-readable produces the byte-identical error code, `failedRowIndex`, and full-batch rollback as the per-reference baseline.
- [ ] `RelatedWriteMetadataResolver` memoizes `(parentMetaClass, relationshipName)` → descriptor; a call-count assertion confirms the metamodel walk runs once per distinct key.
- [ ] Phase 9/10/11 mutation suites (`MutationToolInvariantsTest`, gating-order, audit-row, error-translator, host-guard-veto, row-level mutation-security) pass with zero test-body edits.
- [ ] The default-config zero-mutation-callback boot test passes unchanged.

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                        |
|--------------------|-------|------|--------|--------------------------------------------------------------|
| Goal Clarity       | 0.88  | 0.75 | ✓      | Three concrete refactors, each with a measurable proxy       |
| Boundary Clarity   | 0.80  | 0.70 | ✓      | Phase 18 split explicit; "low-risk adjacent" fenced by guards|
| Constraint Clarity | 0.85  | 0.65 | ✓      | Byte-parity, constrained-DM, no-@Transactional, no-eviction  |
| Acceptance Criteria| 0.85  | 0.70 | ✓      | 8 pass/fail checks incl. proxies                             |
| **Ambiguity**      | 0.15  | ≤0.20| ✓      |                                                              |

Status: ✓ = met minimum, ⚠ = below minimum (planner treats as assumption)

## Interview Log

| Round | Perspective     | Question summary                                  | Decision locked                                                                 |
|-------|-----------------|---------------------------------------------------|---------------------------------------------------------------------------------|
| 1     | Researcher      | Where must FK batch-loading apply (MUT-16)?       | Cross-row batching for `bulk_save_records`; single-call dedup for create/update |
| 1     | Failure Analyst | FK not-found/not-readable error behavior?         | Byte-identical error code + `failedRowIndex` + full-batch rollback              |
| 1     | Boundary Keeper | Internals-only, or allow adjacent improvements?   | Internals-only + zero new surface; low-risk adjacent allowed only under guards  |

---

*Phase: 17-mutation-internals-hardening-phase-11-follow-up*
*Spec created: 2026-05-30*
*Next step: /gsd-discuss-phase 17 — implementation decisions (how to build what's specified above)*
