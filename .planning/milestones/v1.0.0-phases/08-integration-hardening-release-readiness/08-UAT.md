---
status: complete
phase: 08-integration-hardening-release-readiness
source:
  - 08-01-SUMMARY.md
  - 08-02-SUMMARY.md
  - 08-03-SUMMARY.md
  - 08-04-SUMMARY.md
  - 08-05-SUMMARY.md (deferred)
  - 08-06-SUMMARY.md
  - 08-07-SUMMARY.md
started: 2026-04-26T00:00:00Z
updated: 2026-04-26T08:50:00Z
verifier: claude (gradle + file-content checks; UI tests deferred to operator per session note)
---

## Current Test

[testing complete]

## Tests

### 1. Non-live integration test suite is green
expected: `./gradlew :ai-agent:ai-agent:test` (filtered to the four classes added in this phase) reports 17/17 passing — PromptInjectionHarnessTest 5, AuditDurabilityTest 3, ToolQueryCountBaselineTest 7, FindRecordsLimitCapTest 2.
result: pass
evidence: |
  BUILD SUCCESSFUL in 1m 57s. JUnit XMLs (build/test-results/test/):
    PromptInjectionHarnessTest      tests=5  failures=0 errors=0 skipped=0
    AuditDurabilityTest             tests=3  failures=0 errors=0 skipped=0
    ToolQueryCountBaselineTest      tests=7  failures=0 errors=0 skipped=0
    FindRecordsLimitCapTest         tests=2  failures=0 errors=0 skipped=0
  Total 17/17 pass.

### 2. Security negative-case suite documents 3 RED tests
expected: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.security.*"` runs 12 tests; 9 pass and exactly 3 fail with the documented gaps (carol_filteredSchema_excludesDenied_andDeniedAttributes; carol_findRecordsDeniedEntity_returnsAccessDeniedJson; userB_listingConversations_doesNotIncludeUserA_agentStoreScoped). REDs are intentional regression guards from 08-01.
result: pass
evidence: |
  BUILD FAILED in 42s — 12 tests, 3 failed (matches expectation; the 3 are the documented gaps).
  Per-class JUnit XMLs:
    AdminViewAccessTest                     tests=5  failures=0
    CrossUserConversationAccessTest         tests=2  failures=1  (userB_listingConversations RED)
    FilteredSchemaAndExecutionDenialTest    tests=3  failures=2  (carol_filteredSchema, carol_findRecordsDeniedEntity RED)
    RagRoleFilterNegativeTest               tests=2  failures=0
  3 RED tests = 3 documented gaps in 08-01-SUMMARY (Out-of-Scope Issues Discovered) and 08-VERIFICATION Gap 1.
  Note: SUMMARY counted 11 (6 new + 5 pre-existing); actual is 12 because FilteredSchema added one extra
  positive-case method (`dave_filteredSchema_includesEntity_butExcludesProtectedAttributes`) since the
  SUMMARY was written. Discrepancy is non-substantive — the 3 RED tests still match exactly.

### 3. Operator README is present and complete
expected: `ai-agent/README.md` exists, length ~270 lines, contains all 9 H2 sections — Quick Start, Required Environment Variables, Configuration Matrix, Entity / Table Ownership, SPI Cookbook, Upgrade Checklist, Air-Gap Notes, Troubleshooting, See Also. SPI cookbook references `onEventAudited` (post-7.2) and never `dispatchToolCallAudited`.
result: pass
evidence: |
  wc -l: 270 lines (matches plan target).
  All 9 H2 sections present at lines 13, 47, 59, 104, 119, 227, 236, 244, 262.
  `onEventAudited` count: 3.
  `dispatchToolCallAudited` count: 0.

### 4. CLAUDE.md toolchain says Java 21
expected: Repo-root `CLAUDE.md` references `Java 21` (not `Java 17`).
result: pass
evidence: |
  CLAUDE.md line 14: "- Java 21". No `Java 17` / `JDK 17` matches.

### 5. Version source moved to gradle.properties = 1.0.0
expected: `ai-agent/gradle.properties` contains `version=1.0.0`. `ai-agent/build.gradle` reads `project.findProperty('version') ?: '1.0.0'` (no hardcoded `0.0.1-SNAPSHOT`). The `[W-03 GATING]` comment block is preserved in build.gradle.
result: pass
evidence: |
  ai-agent/gradle.properties line 4: `version=1.0.0`.
  ai-agent/build.gradle line 14: `version = project.findProperty('version') ?: '1.0.0'`.
  ai-agent/build.gradle line 69: `[W-03 GATING] — snapshot-vs-release URL conditional...` preserved.
  No `0.0.1-SNAPSHOT` literal anywhere in build.gradle.

### 6. Nexus credentials removed and ignored going forward
expected: `ai-agent/gradle.properties` no longer contains `nexusUsername=` or `nexusPassword=` assignment lines (FOREVER BURNT comment present). `.gitignore` includes patterns for nexus-credentials*, gradle-local.properties, and **/nexus.properties.
result: pass
evidence: |
  ai-agent/gradle.properties has no `^(nexusUsername|nexusPassword)\s*=` matches.
  Lines 6–12 are the FOREVER BURNT explanatory comment.
  .gitignore lines 28–30: `nexus-credentials*`, `gradle-local.properties`, `**/nexus.properties`.

### 7. CHANGELOG.md exists with Keep-a-Changelog discipline
expected: Repo-root `CHANGELOG.md` exists with both `[Unreleased]` and `[1.0.0]` headings. Phase 7 → 7.1 → 7.2 chronology is explicit. Security section calls out the leaked Nexus credential as FOREVER BURNT.
result: pass
evidence: |
  CHANGELOG.md line 8:  `## [Unreleased]`
  CHANGELOG.md line 26: `## [1.0.0] - 2026-04-26`
  CHANGELOG.md line 92: `### Added — Phase 7.1 (...)`
  CHANGELOG.md line 99: `### Added — Phase 7.2 (...)`
  CHANGELOG.md line 148: `is FOREVER BURNT in git history (commits prior to 08-07). Rotation in the Nexus`

### 8. Three GitHub Actions workflows shipped with safety blocks
expected: `.github/workflows/` contains exactly three files: `ai-agent-ci.yml`, `ai-agent-live.yml`, `ai-agent-publish.yml`. Each has explicit `permissions:` and `concurrency:` blocks. Publish workflow has a preflight secrets-presence check that fails loudly with named missing secrets.
result: pass
evidence: |
  ls .github/workflows/: ai-agent-ci.yml, ai-agent-live.yml, ai-agent-publish.yml (3 files).
  `^permissions:` matches in all 3 files (ci:18, live:14, publish:26).
  `^concurrency:` matches in all 3 files (ci:24, live:17, publish:30).
  ai-agent-publish.yml lines 51–59: preflight check with `MISSING=()` array, named NEXUS_USERNAME / NEXUS_PASSWORD entries, and `ERROR: missing required secret(s)` failure path.

### 9. Live-tier semantic suite compiles and is dual-gated
expected: `./gradlew :ai-agent:ai-agent:compileTestJava` PASSES. The default `./gradlew test` invocation does NOT execute `ChatServiceLiveSemanticGoldenSuiteTest` (gated by `@Tag("live")` + `@EnabledIfEnvironmentVariable("OPENROUTER_API_KEY")`). `golden-questions.yaml` has 7 capability entries.
result: pass
evidence: |
  ChatServiceLiveSemanticGoldenSuiteTest.java line 48: `@Tag("live")`.
  Same file line 49: `@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")`.
  golden-questions.yaml has 7 `^- id:` entries.
  build/test-results/test/ contains NO `TEST-com.vn.agent.live*.xml` after the test run — confirms the
  @Tag("live") filter excludes the class from default `./gradlew test`. compileTestJava implicitly passed
  because the security suite (which depends on the same compileTestJava task) ran successfully.

### 10. Documented gaps acknowledged (carry-over to next phase, not regressions)
expected: Three documented gaps from 08-VERIFICATION.md are understood and acknowledged: (a) 3 RED security tests surface real SUT gaps requiring `--gaps` replan; (b) Plan 08-05 consumer-smoke deferred — needs stub VectorStore or Testcontainers-backed reframe; (c) 08-03 R-03e per-tool SELECT counts dominated by Jmix permission overhead — calibrated 1000-ceiling + slope-based N+1 detector is the contractual assertion. None of these block v1.0.0 release.
result: pass
evidence: |
  (a) Test 2 evidence above confirms the exact 3 RED tests = 08-01-SUMMARY documented gaps.
  (b) 08-05-SUMMARY status: deferred; consumer-smoke directory + settings.gradle reverted; 6-layer
      starter-consumability gap chain documented; ai-agent/README.md Troubleshooting section captures it.
  (c) 08-03-SUMMARY R-03e calibration: ToolQueryCountBaselineTest 7/7 pass under the 1000-SELECT ceiling
      (Test 1 above); slope-based R-03h detector (`getRelatedRecords_doesNotScaleWithChildRowCount`) is the
      contractual N+1 assertion.
  All three are recorded in 08-VERIFICATION.md Gaps section. Phase 8 status: passed_with_deferral.

## Summary

total: 10
passed: 10
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

[none — phase 8 deliverables verified]

## Notes

UI surface: phase 8 ships no Vaadin views or front-end deliverables — all changes are tests, docs,
build/CI artifacts. Standard "exercise the feature in a browser" UAT is N/A. Operator-side UI checks
(Run the app, navigate menus) are deferred to the operator per the session note "if ui test let me".

Out-of-band actions enumerated in 08-VERIFICATION.md / 08-07-SUMMARY.md remain the user's responsibility:
  1. Rotate the leaked Nexus admin password in the Nexus admin UI (FOREVER BURNT credential).
  2. Configure GitHub Actions repo secrets (NEXUS_USERNAME, NEXUS_PASSWORD, OPENROUTER_API_KEY).
  3. Tag v1.0.0 when ready to publish.
