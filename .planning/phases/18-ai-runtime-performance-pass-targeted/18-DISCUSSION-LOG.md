# Phase 18: AI-Runtime Performance Pass (targeted) - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-06-08
**Phase:** 18-ai-runtime-performance-pass-targeted
**Mode:** advisor (full_maturity tier; NON_TECHNICAL_OWNER=false; SPEC.md loaded — implementation decisions only)
**Areas discussed:** Per-turn cache anchor, App-wide cache mechanism, AccessManager memo safety boundary, Proxy-test strategy

---

## A — Per-turn cache anchor + streaming correctness (PERF-01)

| Option | Description | Selected |
|--------|-------------|----------|
| RunContext ThreadLocal holder, cleared in finally | Extend RunContext with ThreadLocal<Map> slot; matches locked spec + existing idiom; proven visible at tool-exec under streaming; miss→recompute safe | ✓ |
| Process-wide ConcurrentHashMap<runId> | Hop-proof hits, but skipped finally → forbidden stale cross-user reuse; needs TTL sweeper | |
| Explicit PerTurnCache threaded through | Stale-proof by construction, but framework-invoked tool callbacks have no parameter seam to receive it | |

**User's choice:** RunContext ThreadLocal holder (Recommended)
**Notes:** Research confirmed `GuardedToolCallingManager.executeToolCalls` already reads RunContext ThreadLocals at tool-execution time and audit suites pass under streaming → memo IS visible. Worst case = null on foreign worker → recompute (safe). Reactor/Micrometer propagation explicitly rejected (optimizes hits at cost of required correctness).

---

## B — App-wide cache mechanism (PERF-02)

| Option | Description | Selected |
|--------|-------------|----------|
| ConcurrentHashMap + computeIfAbsent + .clear() on @EventListener | Exact Phase 17 RelatedWriteMetadataResolver twin; no proxy so internal calls hit; greppable + source-scan testable | ✓ |
| AtomicReference volatile snapshot | Functionally equivalent, lighter single-value; loses precedent symmetry | |
| Spring @Cacheable + @CacheEvict | Self-invocation trap (hiddenEntityNames private, 5 internal callers) → caches nothing without a new extracted bean (out of scope) | |

**User's choice:** ConcurrentHashMap + clear() on @EventListener (Recommended)
**Notes:** The @Cacheable self-invocation pitfall was the decisive disqualifier — it would *look* wired while leaving the per-call agentstore SELECT firing, making the locked ceiling-lowering unachievable. Metadata derivation (immutable) uses the same ConcurrentHashMap, no eviction.

---

## C — AccessManager / exposure memo safety boundary (PERF-01)

| Option | Description | Selected |
|--------|-------------|----------|
| Schema + per-MetaClass CRUD verdicts + source-level invariant test | Full PERF-01 win; boundary test-enforced (cache only in policy+RunContext, empty after clear(), never on data path) | ✓ |
| Schema-only cache | Conservative; leaves canCreate/canUpdate recomputing per mutation-tool call | |
| Cache nothing AccessManager-derived | Misses PERF-01 — per-user metamodel walk isn't denylist-bound | |

**User's choice:** Schema + CRUD verdicts + invariant test (Recommended)
**Notes:** Safe because one RunContext = one authenticated user with fixed roles/constraints, cleared in finally; the only verdict-changing event (admin denylist edit) is across-turn by design. Security boundary converted from "trust the argument" to a build-enforced invariant.

---

## D — Proxy-test strategy under boot regression (PERF-05)

| Option | Description | Selected |
|--------|-------------|----------|
| Mixed per-requirement (SELECT-count + call-count + reflection) | Each PERF-ID gets the proxy that proves its claim; confines flaky-boot SELECT-count to DB-elimination cases | ✓ |
| All SELECT-count (datasource-proxy) | Highest fidelity but exposed to full-suite boot flakiness + blind to ThreadLocal/CPU reuse | |
| All call-count (pure Mockito) | Boot-immune, fast, but can't prove a real JDBC SELECT was eliminated | |

**User's choice:** Mixed per-requirement (Recommended)
**Notes:** Crux finding — the datasource-proxy SELECT-count harness boots and passes TODAY (MutationFkBatchLoadQueryCountTest green with mutation-role config); the boot regression is full-suite fork-pressure/ordering, not a recipe failure. Mapping: PERF-01 call-count + empty-after-clear; PERF-02 lowered ToolQueryCountBaselineTest ceiling + refetch call-count + "subscribes to event" reflection; PERF-03 buildFor call-count + unchanged denylist test; PERF-04 FileStorage/Tika/settings call-count + regression-lock where already once/turn.

---

## Claude's Discretion

- Exact `CacheKey` shape, sentinel-key naming, and per-test file placement left to researcher/planner, provided D-01..D-11 hold. User delegated framework-pattern judgment ("best practice là được") and confirmed all four recommended options.

## Deferred Ideas

- None within phase scope. Fixing the underlying `@SpringBootTest` boot regression remains an unscheduled hardening item (CONCERNS.md) — explicitly NOT folded into Phase 18; the D-10/D-11 proxy strategy works around it.
