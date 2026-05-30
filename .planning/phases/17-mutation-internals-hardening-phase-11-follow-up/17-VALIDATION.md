---
phase: 17
slug: mutation-internals-hardening-phase-11-follow-up
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-05-30
---

# Phase 17 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Phase 17 is a **behavior-frozen internal refactor** — the dominant validation signal is the
> existing Phase 9/10/11 mutation suites passing **unchanged**. New tests only prove the three
> structural invariants (gate order, 1-query FK load, memoization walk-once).

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + AssertJ; pure-JUnit source/reflection for invariants; `net.ttddyy:datasource-proxy:1.11.0` for SELECT-count |
| **Config file** | Gradle module `ai-agent/ai-agent` (included build) |
| **Quick run command** | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.*"` |
| **Full suite command** | `./gradlew :ai-agent:ai-agent:test` |
| **Estimated runtime** | quick ~30–60s · full a few min (included-build module) |

> Run via the included-build path `:ai-agent:ai-agent:*` — the root has no `:ai-agent:test` task (STATE.md Phase 12 decision).

---

## Sampling Rate

- **After every task commit:** Run `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.*"`
- **After every plan wave:** Run `./gradlew :ai-agent:ai-agent:test` (catches cross-cutting parity regressions, esp. audit-row / idempotency)
- **Before `/gsd-verify-work`:** Full suite must be green with **zero edits** to the Phase 9/10/11 mutation suites
- **Max feedback latency:** ~60 seconds (quick), full suite per wave

---

## Per-Task Verification Map

> Task IDs (`17-NN-NN`) are assigned during planning; rows below are the requirement-level proxies the
> planner MUST allocate to tasks. The "throws-before-save / parity" requirements are satisfied by the
> existing suites passing unchanged — no new behavioral test is written for them.

| Requirement | Wave | Observable Proxy | Test Type | Automated Command | Seam Required | File |
|-------------|------|------------------|-----------|-------------------|---------------|------|
| MUT-15 | 1 | Gate ORDER strictly increasing + save-token after all gates + no `@Transactional` on chain | source/reflection | `--tests "*MutationToolInvariantsTest"` | `MutationGateChain` with named ordered private gates; declared methods + class reflected | ⚠️ extend existing |
| MUT-15 | 1 | Five `@Tool` methods are thin adapters calling `mutationGateChain.execute(...)` | source assertion | `--tests "*MutationToolInvariantsTest"` | adapters delegate to chain | ⚠️ extend existing |
| MUT-16 | 1 | One FK SELECT per target class for K-row batch (slope ≈ 0, K 10→100) | SELECT-count (datasource-proxy, narrowed boot) | `--tests "*MutationFkBatchLoadQueryCountTest"` | agentstore FK fixture OR widened counting config (Open Q1); `QueryCountHolder` clear/select-count pattern | ❌ Wave 0 |
| MUT-16 | 1 | FK path uses no `UnconstrainedDataManager`, no raw JPQL | source-scan | `--tests "*MutationToolInvariantsTest"` | forbidden-token scan of `MutationAttributeBinder.java` | ⚠️ extend existing |
| MUT-16 | 2 | FK not-found / not-readable → identical error code + `failedRowIndex` + full-batch rollback | behavioral (existing, unchanged) | `--tests "*BulkSavePartialFailureTest" "*RelationshipExposureTest" "*MutationErrorTranslatorTest"` | none new — parity gate | ✅ exists |
| MUT-17 | 1 | Walk runs once per distinct `(entity, relationship)` key (supported AND unsupported) | call-count (pure JUnit + `AtomicInteger`) | `--tests "*RelatedWriteMetadataMemoTest"` | package-private compute/`walk` seam (D-13) | ❌ Wave 0 |
| MUT-18 | 2 | Phase 9/10/11 mutation suites + default-config zero-callback boot pass with **zero test edits** | regression (all existing) | `./gradlew :ai-agent:ai-agent:test` | none — parity gate | ✅ exists |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] Extend `MutationToolInvariantsTest` — gate-ORDER (strictly-increasing `indexOf`), "save token after all gate tokens", `@Transactional`-absence reflection, thin-adapter delegation assertion, and the MUT-16 forbidden-token source-scan on `MutationAttributeBinder.java`.
- [ ] New `MutationFkBatchLoadQueryCountTest` — mirror `ToolQueryCountBaselineTest` narrowed boot recipe + datasource-proxy; **resolve Open Question 1 first** (agentstore FK fixture vs widened counting config).
- [ ] New `RelatedWriteMetadataMemoTest` — pure-JUnit `AtomicInteger` counting seam over the package-private compute method; assert the walk advances exactly once across two `resolve` calls for the same key, once each for supported AND unsupported keys.
- [ ] *(Conditional on Open Q1 = option a)* New agentstore FK test fixture — `@Store("agentstore")` entity with a `@ManyToOne` + parent, registered via the existing test-only Jmix module pattern.
- [ ] No framework install needed (JUnit 5 + datasource-proxy already present).

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| — | — | — | — |

*All phase behaviors have automated verification — this is a refactor whose correctness is proven by existing-suite parity + three new structural proxies.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references (3–4 new/extended test files above)
- [ ] No watch-mode flags
- [ ] Feedback latency < 60s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
