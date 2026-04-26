# Phase 8: Integration Hardening & Release Readiness - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-26
**Phase:** 08-integration-hardening-release-readiness
**Areas discussed:** Fresh-audit method & gating, Test suite shape, Live-tier semantic suite, Release readiness

---

## Scope Shape (pre-area clarification)

| Option | Description | Selected |
|--------|-------------|----------|
| Bundle UAT + release polish | One Phase 8 covering 5 deferred UAT issues + roadmap deliverables | |
| UAT-fix only as Phase 8; roadmap as Phase 9 | Surgical UAT remediation now, defer test/docs/release | |
| Roadmap as Phase 8; UAT as 7.3 hotfix | Insert a 7.3 for the bugs, keep 8 clean | |

**User's choice:** None of the above — `"ignore all old UAT/verification debt marked obsolete; scope Phase 8 completely fresh from the current codebase, and do not carry forward any Phase 7/7.1 issues unless they are reproduced by a new audit or test"`

**Notes:** Saved as durable memory `feedback_fresh_phase_scope.md`. Reframed Phase 8 as roadmap-deliverables-only with a tests-first audit method; bug-fixes enter via `--gaps` replan only if a new test reproduces the issue. The original three options were rejected because all three assumed the deferred UAT list belongs in scope by default.

---

## Fresh-audit method & gating

### Audit entry point

| Option | Description | Selected |
|--------|-------------|----------|
| Tests-first | Test suites are the audit; failures define the bug list | ✓ |
| Playwright + manual smoke pass first | Run UI smoke, then write tests | |
| `/gsd-audit-fix` as wave 0 | Dedicated audit-fix run before main work | |
| Skip audit | PLAN.md = roadmap deliverables only; fix during execution | |

**User's choice:** Tests-first.
**Notes:** Aligns with "fresh from current code". Bug-fix work is real-failure-driven, not memory-driven.

### Audit gate timing

| Option | Description | Selected |
|--------|-------------|----------|
| Mid-phase replan after first red wave | Initial PLAN.md = deliverables only; `--gaps` appends fixes | ✓ |
| Upfront in PLAN.md based on initial code review | Planner pre-bakes anticipated fixes | |
| Defer all fixes to Phase 8.1 hotfix | Phase 8 = tests + docs + release polish only | |

**User's choice:** Mid-phase `--gaps` replan.
**Notes:** Keeps the upfront plan honest — only fixes that real tests demand.

---

## Test suite shape (TEST-04 + injection + rollback + perf smoke)

### TEST-04 negative-case suite organization

| Option | Description | Selected |
|--------|-------------|----------|
| Three focused tests, one per concern | `FilteredSchemaAndExecutionDenialTest` + `RagRoleFilterNegativeTest` + `CrossUserConversationAccessTest` | ✓ |
| One bundled `SecurityNegativeIntegrationTest` | Single class, three `@Test` methods | |
| Reuse existing tests + add only the gap | Audit Phase 04/05 coverage and patch | |

**User's choice:** Three focused tests.
**Notes:** Pin-points failure in CI; mirrors Phase 04 naming convention.

### Restricted-user fixtures

| Option | Description | Selected |
|--------|-------------|----------|
| Reuse Phase 02 roles, add 1–2 restricted personas | Production roles unchanged + test-only `NoCustomerReadRole` | ✓ |
| Build dedicated test-only personas top-down | Parallel role taxonomy in test code | |
| Use Jmix specific-permissions overrides per test | Inline `@WithMockUser`-equivalent | |

**User's choice:** Reuse + add personas.
**Notes:** Keeps production role catalog clean for v1 release.

### Schema-filter assertion style

| Option | Description | Selected |
|--------|-------------|----------|
| Compare `AiSchema` against denied-attribute ground-truth | Direct `EffectiveSchemaComputer.compute()` assertion | ✓ |
| End-to-end via `ChatService.ask` + tool-result inspection | Mock ChatModel echoes schema tool result | |
| Both — unit + integration | Stricter coverage, two tests | |

**User's choice:** Direct comparison.
**Notes:** Deterministic, no ChatModel needed.

### Injection + rollback test placement

| Option | Description | Selected |
|--------|-------------|----------|
| Extend existing harnesses | `PromptInjectionHarnessTest` + `AuditDurabilityTest` get new fixtures | ✓ |
| New dedicated test classes | `PoisonedFieldInjectionTest` + `RollbackPreservesAuditTest` | |

**User's choice:** Extend.
**Notes:** Existing fixtures already wired; smaller surface area.

### Performance smoke tooling

| Option | Description | Selected |
|--------|-------------|----------|
| Hibernate Statistics + per-tool query-count assertion | `getQueryExecutionCount()` baseline per tool | ✓ |
| DataSource-level connection wrapper counting executions | Provider-independent SQL counter | |
| Skip per-tool, assert limit cap only | One test, defer N+1 to v2 | |

**User's choice:** Hibernate Statistics.
**Notes:** Lightweight, no new deps.

### Test location

| Option | Description | Selected |
|--------|-------------|----------|
| `ai-agent` integrationTest source set | New tests in `ai-agent/src/integrationTest/` | ✓ |
| `jmix-app` integrationTest | End-to-end against demo host | |
| Mix — pure-unit in ai-agent, e2e in jmix-app | Most realistic but two files per concern | |

**User's choice:** ai-agent integrationTest.
**Notes:** Source set does not yet exist; planner decides whether to create it this phase or co-locate in src/test.

---

## Live-tier semantic suite — 6 golden questions

### Coverage set

| Option | Description | Selected |
|--------|-------------|----------|
| Capability-coverage set | Schema, single-entity find, multi-step, RAG, multi-turn, refusal | ✓ |
| Tool-heavy regression set | All 6 = one per built-in tool | |
| Domain-realistic conversation | One realistic admin conversation across 6 turns | |

**User's choice:** Capability-coverage.
**Notes:** Each major v1 capability lights up at least once.

### Assertion style

| Option | Description | Selected |
|--------|-------------|----------|
| `containsAnyOf` semantic anchors | Anchor token sets per question | ✓ |
| `spring-ai-test` `EvaluationModel` | Second-LLM grading | |
| Hybrid — anchors + `EvaluationModel` for refusal | Balanced | |

**User's choice:** `containsAnyOf` only.
**Notes:** Deterministic, no judge-model dep, no doubled API spend.

### Fixture format

| Option | Description | Selected |
|--------|-------------|----------|
| YAML fixture under `src/test/resources` | `golden-questions.yaml`, Jackson YAML | ✓ |
| Java records in test class | No file IO, refactor-safe | |
| Parameterized `@CsvFileSource` | Compact CSV | |

**User's choice:** YAML.
**Notes:** Edit-without-recompile parity with Phase 06.

### Gating

| Option | Description | Selected |
|--------|-------------|----------|
| `@Tag("live")` + `OPENROUTER_API_KEY` env var | Phase 04 pattern | ✓ |
| Separate `@Tag("goldenSuite")` subset | Two-tier opt-in | |
| Manual run only — no Gradle task | IDE-only | |

**User's choice:** `@Tag("live")` + env var.
**Notes:** Defense in depth, already proven.

### Model pinning

| Option | Description | Selected |
|--------|-------------|----------|
| Use `AiParameters` default profile model | Tests track production | ✓ |
| Hard-pin a specific cheap model | Stable cost across runs | |
| Run against multiple pinned models | Catches model-specific regressions, 3x cost | |

**User's choice:** Default profile.
**Notes:** Suite tracks production behavior.

---

## Release readiness — docs + clean-consumer + version/CHANGELOG/publish

### Operator docs scope

| Option | Description | Selected |
|--------|-------------|----------|
| Single `README.md` with mandated sections | Quick start, env vars, config matrix, ownership, upgrade, air-gap, SPI cookbook | ✓ |
| `README.md` + companion `docs/` folder | Deep material in `docs/*.md` | |
| README + per-SPI Javadoc + lightweight cookbook | IDE-discoverable docs | |

**User's choice:** Single README.
**Notes:** Scannable, maintainable, single source of truth.

### Demo-seed script

| Option | Description | Selected |
|--------|-------------|----------|
| No script — README walks through `jmix-app` | Reuse existing harness | ✓ |
| Add `:jmix-app:demoSeed` Gradle task | Bulletproof <10min demo | |
| Add `scripts/demo-smoke.sh` | curl-based smoke | |

**User's choice:** No script.
**Notes:** Existing Liquibase seeds + manual KB upload meet the bar.

### Clean-consumer smoke (TEST-07)

| Option | Description | Selected |
|--------|-------------|----------|
| Dedicated `consumer-smoke` Gradle subproject | Reproducible local + CI | ✓ |
| Scripted CI step that scaffolds fresh app per run | Most realistic, slow + fragile | |
| Reuse `docs/consumer-smoke.md` as manual gate | Self-attested only | |

**User's choice:** Dedicated subproject.
**Notes:** One source of truth.

### Release polish aggressiveness

| Option | Description | Selected |
|--------|-------------|----------|
| 1.0.0 + Keep-a-Changelog + `publishToMavenLocal` proven | MVP shipping | ✓ (with publish to remote Nexus, not local) |
| 1.0.0-RC1 + Sonatype publish dry-run | Hedged | |
| 0.1.0 + minimal CHANGELOG | Cheapest | |

**User's choice:** 1.0.0 + Keep-a-Changelog, plus user redirected publish target from Maven Local to remote Nexus.

### CI workflow

| Option | Description | Selected |
|--------|-------------|----------|
| Add minimal CI workflow files this phase | Two PR-blocking jobs | ✓ |
| Defer CI to a separate phase | Phase 8 lands suites only | |
| Document CI in README, no actual workflow | Self-attested TEST-07 | |

**User's choice:** Add workflows. **User clarification:** "publish not publishToMavenLocal" — CI uses real `gradle publish` to a remote target.

### Publish target

| Option | Description | Selected |
|--------|-------------|----------|
| GitHub Packages | Default option presented | |
| Sonatype OSSRH → Maven Central | Public Maven Central | |
| Private/internal Nexus or Artifactory | Corporate repo | ✓ (specifically `https://nexus.x2h.com.vn/repository/jmix-internal-snapshots/`) |
| Both Maven Local AND remote target | Decoupled | |

**User's choice:** Private Nexus, with verbatim Gradle `publishing { ... }` snippet provided. `allowInsecureProtocol = true`, credentials via `nexusUsername` / `nexusPassword` project properties.

---

## Claude's Discretion

- Naming of new test classes within the patterns above (exact package layout under `security/`, `performance/`).
- Whether `consumer-smoke` uses HSQLDB or a Postgres testcontainer.
- Exact menu-presence assertion mechanism in `bootRunSmoke`.
- CHANGELOG backfill granularity.
- Whether to retain `docs/consumer-smoke.md` alongside the automated subproject.

## Deferred Ideas

- Sonatype OSSRH / Maven Central — v2 release engineering.
- Multi-model live-tier parameterization — wait until production usage justifies it.
- `spring-ai-test` `EvaluationModel` — defer unless anchors prove insufficient.
- `/gsd-audit-fix` formal pass against the 7.1 deferred list — only if Phase 8 tests reproduce them.
- `:jmix-app:demoSeed` Gradle task — defer; manual seed bar is sufficient.
- Three-tier source-set retrofit (TEST-01 completion) — may become Phase 8.1 cleanup if planner finds risk.
- `docs/`-folder split — v2 docs-engineering concern.
- Per-SPI runnable example projects — README cookbook covers v1.
