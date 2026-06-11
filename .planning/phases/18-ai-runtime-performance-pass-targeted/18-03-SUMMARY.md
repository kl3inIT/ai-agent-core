---
phase: 18-ai-runtime-performance-pass-targeted
plan: 03
subsystem: rag
tags: [perf, rag, retrieval-filter, exposure-denylist, PERF-03]
requires: ["18-01"]
provides:
  - "RetrievalFilterBuilder.buildFor documented as once-per-retrieval; denylist read reuses the PERF-02 app-wide cache"
  - "RetrievalFilterBuilderBuildOncePerRetrievalTest — call-count proxy (times(1) per buildFor) + verbatim-clause smoke"
affects:
  - "RAG retrieval filtering hot path (one denylist read per retrieval, memoized via Plan 18-01)"
tech-stack:
  added: []
  patterns:
    - "Call-count proxy test (verify times(1)) as the PERF-03 checkable proxy — pure JUnit 5 + Mockito, @Tag(unit), no @SpringBootTest"
    - "Structural once-ness preserved (no new cache in RetrievalFilterBuilder); the denylist memo lives in LlmExposurePolicy (Plan 18-01)"
key-files:
  created:
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderBuildOncePerRetrievalTest.java"
  modified:
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java"
decisions:
  - "Task 1 production change is a clarifying Javadoc only (NOTE): buildFor already read the denylist exactly once (:99) and built the filter once per call before this plan; the PERF-02 cache reuse is structural via Plan 18-01, so no code change to the call site was required. No new cache field added to RetrievalFilterBuilder."
  - "The new proxy counts at the LlmExposurePolicy.getDenylistedEntityNames() seam (verify times(1)), complementing — not duplicating — the byte-for-byte RetrievalFilterBuilderDenylistTest clause guard, which was left unchanged."
metrics:
  duration: ~4 min
  completed: 2026-06-09
---

# Phase 18 Plan 03: RAG Filter Built Once Per Retrieval (PERF-03) Summary

The RAG `Filter.Expression` in `RetrievalFilterBuilder.buildFor(Authentication)` is now documented and locked as built once per retrieval, with the denylist read reusing the Plan 18-01 (PERF-02) app-wide `denylistCache`; a new pure-JUnit call-count proxy asserts exactly one denylist lookup per `buildFor`, and the existing verbatim-clause guard passes byte-for-byte unchanged.

## What Was Done

### Task 1 — Once-per-retrieval build, PERF-02 cache reuse, clauses verbatim (commit `90c9584`)

Audited `RetrievalFilterBuilder.buildFor`: it already reads the denylist via exactly ONE `llmExposurePolicy.getDenylistedEntityNames()` call (line 99) and assembles the `Filter.Expression` once per call (no loop re-invokes the build). Since Plan 18-01, that single call hits the app-wide `denylistCache` automatically (a memoized read, not a per-call agentstore SELECT). The optimization is therefore structural via Plan 18-01 — no code change to the call site was required.

The production change is a clarifying Javadoc on `buildFor` noting: (a) the denylist is read once via the PERF-02 cache, (b) the filter is assembled once per retrieval, (c) role extraction stays request-fresh (NOT cached across requests, T-18-10), and (d) the NIN / per-role / admin-bypass / fail-closed clauses are preserved verbatim (T-18-08). No new `ConcurrentHashMap` / `computeIfAbsent` / `ThreadLocal` cache was added to `RetrievalFilterBuilder` — the denylist memo lives in `LlmExposurePolicy`.

### Task 2 — PERF-03 once-per-retrieval call-count proxy (commit `7083b3e`)

Created `RetrievalFilterBuilderBuildOncePerRetrievalTest` (`@Tag("unit")`, pure JUnit 5 + Mockito, no `@SpringBootTest` — sidesteps the pre-existing Phase 11/13 boot regression). It builds a real `RetrievalFilterBuilder` over a Mockito-mocked `LlmExposurePolicy` stubbed with a non-empty denylist, and asserts:

1. `verify(policy, times(1)).getDenylistedEntityNames()` after a single `buildFor` — one lookup per retrieval (memoized via Plan 18-01).
2. Verbatim-clause smoke: the built expression still contains the NIN `source_entity` clause, the denied entity name, a per-role flag key, and the embedding-model pin (no redundant-clause removal, T-18-08).
3. A second independent `buildFor` issues its own single lookup (`times(2)` total) — per-retrieval once-ness, not a cross-retrieval cache claim; role extraction stays request-fresh (T-18-10).

## Verification

- `./gradlew :ai-agent:test --tests "com.vn.agent.rag.RetrievalFilterBuilderBuildOncePerRetrievalTest" --tests "com.vn.agent.rag.RetrievalFilterBuilderDenylistTest"` → **BUILD SUCCESSFUL** (both classes green).
- `git diff HEAD -- RetrievalFilterBuilderDenylistTest.java` → **empty** (4-test verbatim-clause guard unchanged).
- Acceptance criteria met: exactly one `getDenylistedEntityNames()` call in `buildFor`; NIN + per-role OR composition present and unchanged in shape; no new cache field in `RetrievalFilterBuilder`.

## Deviations from Plan

None beyond the planned `[NOTE]`. Per the plan's Task 1 guidance, `buildFor` already read the denylist exactly once and built the filter once before this plan, so the production change is a clarifying Javadoc rather than a structural edit — recorded as a decision above. No Rule 1–4 deviations.

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java` (modified — Javadoc on `buildFor`)
- FOUND: `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderBuildOncePerRetrievalTest.java` (created)
- FOUND: commit `90c9584` (Task 1) in git log
- FOUND: commit `7083b3e` (Task 2) in git log
