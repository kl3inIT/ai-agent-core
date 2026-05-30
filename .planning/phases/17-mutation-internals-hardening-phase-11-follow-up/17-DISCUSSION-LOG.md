# Phase 17: Mutation-Internals Hardening (Phase 11 follow-up) - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-30
**Phase:** 17-mutation-internals-hardening-phase-11-follow-up
**Areas discussed:** Gate chain shape & adapter API, FK batch-load placement, Metadata memoization, Invariant & proxy test strategy
**Mode:** advisor (full_maturity tier; non-technical-owner reframing OFF — overridden, pure backend internals)

---

## Gate chain shape & adapter API (MUT-15)

| Option | Description | Selected |
|--------|-------------|----------|
| A. Template-method `@Component` + sealed `MutationRequest` | Single `execute(req)`, ordered private gate calls, sealed Create/Update/AddRelated/RemoveRelated/Bulk | ✓ |
| D. Template-method, enum dispatch (no sealed) | Fewer new types, `if(BULK)` branch, no compile-time exhaustiveness | |
| E. Hybrid — chain owns gate+save only, tools keep try/catch | Guarantees byte-for-byte catch parity; try/catch duplication survives | |
| (rejected) Chain-of-responsibility `List<Gate>` | Demotes source ORDER invariant to runtime wiring check | |
| (rejected) Functional pipeline | Anonymous lambdas → opaque ORDER test + exception classification | |

**User's choice:** A. Template-method + sealed `MutationRequest` (recommended)
**Notes:** Chosen for the strongest source-level gate-ORDER test ergonomics and cleanest Phase 18 memoization seam. Option E retained in CONTEXT.md as documented fallback if catch-arm unification threatens parity.

---

## FK batch-load placement (MUT-16)

| Option | Description | Selected |
|--------|-------------|----------|
| A. Two-pass methods on `MutationAttributeBinder` | Collect ids/class → one `.ids(...)` → bind from prefetch map; create/update pass 1-row list | ✓ |
| B. Dedicated `FkReferenceBatchLoader` collaborator | Isolated/testable; splits absence→error ownership across 2 beans (parity risk) | |
| C. Per-call reference-context cache | Clean forward seam; still needs eager pre-pass; signature churn | |
| (rejected) Inline in `bulkSaveRecords` only | Leaves create/update on old path → fails single-call dedup half | |

**User's choice:** A. Two-pass methods on `MutationAttributeBinder` (recommended)
**Notes:** Single coercion path keeps one owner of not-found-vs-access-denied classification. Research surfaced the key parity fact (today's path collapses missing + row-denied → `not_found`; `access_denied` only from `enforceReadPermission`), recorded as D-08.

---

## Metadata memoization (MUT-17)

| Option | Description | Selected |
|--------|-------------|----------|
| A3. `ConcurrentHashMap<record Key, Result>`, cache both outcomes, rethrow canned error | Walk-once for supported AND unsupported keys; never caches live exception | ✓ |
| A2. Success-only cache, recompute+rethrow on reject | Simplest; walk-once only for supported keys | |
| (rejected) `@Cacheable` | Host owns CacheManager; self-invocation bypass; no exception caching | |
| (rejected) Caffeine/Guava | New dependency — SPEC-forbidden | |

**User's choice:** A3. Cache both outcomes, rethrow canned error (recommended)
**Notes:** Confirms the call-count "walk once per distinct key" assertion must hold for rejecting keys too. Record key (`parentEntityName`+`relationshipName`) over raw `MetaClass`.

---

## Invariant & proxy test strategy (MUT-15/16/17)

| Option | Description | Selected |
|--------|-------------|----------|
| Pure-JUnit only (source/reflection + datasource-proxy + counting seam) | House convention; fail-closed covered by existing Phase 11 runtime tests | ✓ |
| + Mockito InOrder supplement | One runtime assertion that save spy never reached on gate throw; breaks no-mock norm | |
| (rejected) ArchUnit | No dependency, dropped Phase 2, standing preference to avoid | |

**User's choice:** Pure-JUnit only (recommended)
**Notes:** MUT-15 ordering via strictly-increasing `indexOf` + reflection for `@Transactional` absence; MUT-16 reuses existing `datasource-proxy` harness with the green narrowed boot recipe of `ToolQueryCountBaselineTest`; MUT-17 counting seam via package-private compute method + `AtomicInteger`.

## Claude's Discretion

- Exact method/field/record names, package placement of `MutationGateChain` + `MutationRequest` variants, internal shape of the `Result` holder.

## Deferred Ideas

- Broader per-turn / app-wide memoization (schema, `AccessManager`, exposure denylist, RAG, media) — Phase 18 (PERF-01/02).
- Roadmap hygiene: stale "Promotes Backlog 999.1" note in the Phase 17 ROADMAP entry (current Backlog 999.1 is unrelated "Admin-rotated provider credentials").
