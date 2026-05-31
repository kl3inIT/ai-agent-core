---
phase: 17-mutation-internals-hardening-phase-11-follow-up
plan: 01
subsystem: mutation-tools-test-seams
tags: [mutation, test-seams, tdd-red, select-count, reflection-invariant, agentstore-fixture]
requires:
  - "Phase 11 mutation tool surface (BuiltInMutationTools, MutationAttributeBinder, RelatedWriteMetadataResolver, MutationSaveExecutor)"
  - "QueryCountingDataSourceConfiguration (agentstore-wrapping, Phase 8 Plan 03)"
  - "MutationToolTestUsersConfiguration + MutationFixturePersistenceTestConfiguration (Phase 11 Plan 10)"
provides:
  - "MUT-15 gate-order/save-after-gates/no-@Transactional reflection invariants (RED until Plan 04)"
  - "MUT-16 forbidden-token FK-path scan (GREEN today, must stay green after Plan 03)"
  - "MUT-16 batch FK SELECT-count slope proxy (RED until Plan 03)"
  - "MUT-17 walk-once metamodel-walk call-count seam (RED until Plan 02)"
  - "computeSupported(MetaClass, String) package-private memoization seam on RelatedWriteMetadataResolver"
  - "agentstore @Store FK fixture pair (Open Question 1 option a)"
affects:
  - "Plan 02 (memoize computeSupported), Plan 03 (batch FK load), Plan 04 (extract MutationGateChain)"
tech-stack:
  added: []
  patterns:
    - "Pure-JUnit AtomicInteger counting subclass over a package-private compute seam"
    - "java.lang.reflect.Proxy key-only MetaClass stand-in (no Spring, no Mockito)"
    - "Reflection-based @Transactional-absence assertion (Class.forName + getDeclaredMethods, not regex)"
    - "Per-test agentstore changelog override via @SpringBootTest(properties=...) (global wiring untouched)"
    - "datasource-proxy SELECT-count slope probe (K=10 vs K=100) as the contractual 1-query-not-N detector"
key-files:
  created:
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/RelatedWriteMetadataMemoTest.java"
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationStoreLinkedParentFixture.java"
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationStoreLinkedChildFixture.java"
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/performance/MutationFkBatchLoadQueryCountTest.java"
    - "ai-agent/ai-agent/src/test/resources/com/vn/agent/test_liquibase/020-mutation-store-fixture.xml"
    - "ai-agent/ai-agent/src/test/resources/com/vn/agent/test_liquibase/test-agentstore-changelog.xml"
  modified:
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationToolInvariantsTest.java"
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java"
    - "ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationTestFixtureTestRole.java"
decisions:
  - "Open Question 1 resolved as option (a): agentstore @Store FK fixture pair so the existing agentstore-wrapping QueryCountingDataSourceConfiguration counts the batch FK SELECT without widening the shared counting config."
  - "Rule 3 auto-fix: extract package-private computeSupported(MetaClass, String) seam now (behavior-identical) so the pure-JUnit memo test compiles without breaking module-wide compileTestJava — Plan 02 adds the memoization."
  - "Memo test counts at the seam boundary with a Proxy key-only MetaClass (never dereferenced) instead of walking a real Jmix metamodel, keeping it pure JUnit (no Spring, no Mockito) per D-16/D-17."
  - "Per-test agentstore changelog (includes prod changelog + FK fixture tables) selected via @SpringBootTest(properties=agentstore.liquibase.change-log=...) so global test-app.properties agentstore wiring stays untouched for every other test."
metrics:
  duration: "~20 min"
  completed: "2026-05-31"
  tasks: 3
  files: 9
---

# Phase 17 Plan 01: Mutation-Internals Hardening Test Seams Summary

Four structural test seams proving the three MUT-15/16/17 refactors "did something", plus the agentstore FK fixture pair the SELECT-count proxy requires — written FIRST so Plans 02/03/04 have explicit RED targets to satisfy.

## What Was Built

- **Task 1 (MUT-15 + MUT-16):** Extended `MutationToolInvariantsTest` with four additive `@Test` methods. The three MUT-15 assertions (`mutationGateChain_gatesAppearInCanonicalOrder`, `mutationGateChain_saveTokenAppearsAfterAllGateTokens`, `mutationGateChain_carriesNoTransactionalAnnotation`) are RED until Plan 04 extracts `MutationGateChain`. The MUT-16 forbidden-token scan (`mutationAttributeBinder_fkPathUsesNoUnconstrainedDataManagerOrRawJpql`) is GREEN today and must stay green after Plan 03. The `@Transactional`-absence check uses `Class.forName` + reflection (`isAnnotationPresent` / `getDeclaredMethods`), NOT a source regex (D-14). All four original Plan 11-07C methods stay green (MUT-18 parity).
- **Task 2 (MUT-17):** New `RelatedWriteMetadataMemoTest` — pure JUnit 5 + AssertJ, zero Spring, zero Mockito. A `CountingResolver` subclass overrides the package-private `computeSupported(MetaClass, String)` seam and bumps an `AtomicInteger`. The `walkCount == 1` assertions for repeated supported/unsupported keys are RED until Plan 02 memoizes; `distinctKeysEachWalkOnce` (count == 2) and the D-12 `isNotSameAs` fresh-rethrow assertion are green.
- **Task 3 (MUT-16, Open Q1 option a):** New agentstore `@Store("agentstore")` FK fixture pair (`MutationStoreLinkedParent/ChildFixture`) + `MutationFkBatchLoadQueryCountTest` mirroring the `ToolQueryCountBaselineTest` narrowed-boot recipe. Seeds K children pointing at one parent, calls `bulk_save_records`, and asserts the SELECT slope `countLarge - countSmall <= 1` (K=10 vs K=100). RED until Plan 03 batch-loads the FK ids.

## Verification Results

| Test | Result | Notes |
|------|--------|-------|
| `MutationToolInvariantsTest` | 5 green / 3 RED | 4 original + MUT-16 scan green; 3 MUT-15 assertions RED naming `MutationGateChain.java`/Plan 04 |
| `RelatedWriteMetadataMemoTest` | 1 green / 2 RED | `distinctKeysEachWalkOnce` green; 2 `walkCount==1` assertions RED naming Plan 02 |
| `RelatedWriteMetadataResolverTest` | all green | confirms the `computeSupported` extraction is behavior-identical |
| `MutationFkBatchLoadQueryCountTest` | boots, 1 RED | boots cleanly (no boot regression); slope RED — observed K=10 → 51 selects, K=100 → 411 selects, slope = 360 (per-row FK SELECTs) — RED until Plan 03 |
| `com.vn.agent.tools.mutation.*` (full suite) | 91 green / 5 RED | the ONLY failures are the 3 MUT-15 + 2 MUT-17 intended-RED seams; every pre-existing Phase 9/10/11 mutation test passes (no parity regression) |

Each RED assertion names its implementing plan (02 memo, 03 batch FK, 04 gate chain), satisfying the plan's success criterion.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Extracted `computeSupported(MetaClass, String)` seam in `RelatedWriteMetadataResolver`**
- **Found during:** Task 2
- **Issue:** The memo test must override the package-private `computeSupported` metamodel-walk seam (per plan), but that method did not yet exist — Plan 02 was assumed to extract it. Referencing a non-existent method would break module-wide `compileTestJava`, blocking Task 3's verification from running.
- **Fix:** Extracted the existing walk body of `resolveSupportedRelatedWriteRelationship` into a new package-private `computeSupported(MetaClass, String)`; the public method now delegates to it. Behavior-identical (no memoization yet — Plan 02 owns that). `RelatedWriteMetadataResolverTest` (~13 tests) stays fully green, confirming the extraction changed nothing observable.
- **Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java`
- **Commit:** 817fb20

**2. [Rule 2 - Missing critical functionality] Granted `MutationTestFixtureTestRole` CRUD + attribute policies on the two new agentstore FK fixtures**
- **Found during:** Task 3
- **Issue:** `bulk_save_records` runs as `mutation-user`, whose fixture CRUD role granted policies only on the five existing main-store fixtures. Without policies on `MutationStoreLinkedParent/ChildFixture`, `LlmExposurePolicy.canCreate` returns false and the tool returns `unknown_entity` before any FK load — the SELECT-count proxy could never measure the FK path.
- **Fix:** Added `@EntityPolicy(READ/CREATE/UPDATE)` + wildcard `@EntityAttributePolicy(MODIFY)` for both agentstore FK fixtures on `MutationTestFixtureTestRole`. Additive only; no existing policy removed.
- **Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationTestFixtureTestRole.java`
- **Commit:** f27d23d

### Design choice (plan-sanctioned)

- Open Question 1 resolved as option (a): agentstore `@Store` FK fixtures + a per-test agentstore changelog override, leaving the shared `QueryCountingDataSourceConfiguration` and global `test-app.properties` untouched.
- The memo test counts at the `computeSupported` seam boundary with a `java.lang.reflect.Proxy` key-only `MetaClass` (never dereferenced) rather than booting a real Jmix metamodel — keeps it pure JUnit (no Spring, no Mockito) per D-16/D-17 while still proving the walk-once memoization contract.

## Threat Model Compliance

- **T-17-01 (mitigate):** The gate-order + save-after-gates + reflection `@Transactional`-absence assertions structurally enforce the fail-closed ordering once Plan 04 lands.
- **T-17-02 (mitigate):** The `mutationAttributeBinder_fkPathUsesNoUnconstrainedDataManagerOrRawJpql` scan is GREEN today and fails the build if the FK batch path ever switches to `UnconstrainedDataManager`/raw JPQL (D-09).
- **T-17-03 (accept):** The memo test deliberately varies no security context — relationship support is a pure, security-independent metamodel fact.
- **T-17-SC (accept):** No package installs in this plan; `datasource-proxy`/JUnit/AssertJ already on `testImplementation`. No legitimacy checkpoint required.

## Known Stubs

None. The seams are intentionally RED (not stubs) — each names its implementing plan and the RED is the contract.

## No new threat surface

This plan adds test-only code (test source tree, never shipped in the addon jar) plus one behavior-identical production seam extraction. No new network endpoints, auth paths, file access, or schema changes at trust boundaries.

## Self-Check: PASSED

All 6 created files + the SUMMARY exist on disk; all four commits (60e38d2, 817fb20, f27d23d, 3f2e412) present in git history.
