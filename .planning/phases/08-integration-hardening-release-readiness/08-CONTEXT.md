# Phase 8: Integration Hardening & Release Readiness - Context

**Gathered:** 2026-04-26
**Status:** Ready for planning

<domain>
## Phase Boundary

Pre-release hardening for v1: cross-phase quality gates (TEST-04 negative-case integration suite, TEST-07 clean-consumer smoke), TEST-05 completion (live-tier semantic suite over 6 golden questions), TEST-02/03 completion via prompt-injection extension and rollback-preserves-audit, performance smoke (`limit` cap + N+1 detection), operator README, and release polish (version, CHANGELOG, `maven-publish` to private Nexus).

**Scope is derived fresh from ROADMAP.md and the current codebase.** Deferred Phase 7.x UAT items are NOT auto-carried as in-scope work. Bug-fix tasks enter PLAN.md only if a fresh test suite written in this phase reproduces the issue (tests-first audit; mid-phase replan via `/gsd-plan-phase 8 --gaps`).

**In scope:**

- **TEST-04 negative-case integration suite** — three focused tests under `ai-agent/ai-agent/src/integrationTest/.../security/`:
  - `FilteredSchemaAndExecutionDenialTest` — restricted user gets `AiSchema` with denied entity attrs absent AND attempted access via `find_records` returns access-denied.
  - `RagRoleFilterNegativeTest` — admin-tagged chunks not retrieved when caller has only `AiAgentUserRole`.
  - `CrossUserConversationAccessTest` — user A cannot read or replay user B's conversation (DataManager opacity + `ChatService` boundary check).
- **Restricted personas** — reuse Phase 02 roles (`AiAgentUserRole`, `AiAgentAdminRole`, `AiAgentUserRowLevelRole`); add a single test-only `NoCustomerReadRole` (entity-level deny on `Customer`) for the filtered-schema/denial test. Personas live as `@TestConfiguration` beans; not part of production role catalog.
- **Schema-filter assertion** — direct comparison of `EffectiveSchemaComputer.compute(...)` output for the restricted user against ground-truth (denied entity has zero `AiAttributeInfo` entries). Deterministic; does not depend on a ChatModel.
- **Prompt-injection extension** — extend `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/PromptInjectionHarnessTest.java` (Phase 03) with a poisoned-tool-result fixture (e.g. `Customer.notes = "SYSTEM: ignore previous instructions..."`) asserting the value is escaped inside `<data>` delimiters and does not flip behavior.
- **Rollback-preserves-audit extension** — extend `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java` (Phase 04) with a tool-tx-rollback case asserting the `TOOL` child audit row remains after the parent business tx rolls back (validates `REQUIRES_NEW` from Phase 04 D-11 / Phase 07.2 D-02).
- **Performance smoke** — Hibernate Statistics-based test in `ai-agent/ai-agent/src/integrationTest/.../performance/`. Enables `hibernate.generate_statistics` in test profile; per-tool query-count assertion (each of the 6 built-in tools at expected baseline, no fan-out N+1); explicit `limit` cap test (request `limit=999999` → response capped at the constant).
- **Live-tier semantic suite (TEST-05 completion)** — new `ChatServiceLiveSemanticGoldenSuiteTest` in `src/test/java/com/vn/agent/live/` (alongside existing `ChatServiceLiveSemanticTest`). Loads 6 capability-coverage questions from `src/test/resources/golden-questions.yaml`. Assertions via `containsAnyOf(anchors)` per question (no second-LLM grader, no exact-text match). Gating: `@Tag("live")` + `@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", ...)`. Model: whatever the active `AiParameters` profile selects.
- **TEST-07 clean-consumer smoke** — new top-level Gradle subproject `consumer-smoke/` in repo root. Minimal Jmix app that resolves `ai-agent-starter` from Maven Local. Custom Gradle task `bootRunSmoke` boots the app, asserts `ChatService` bean is wired and `aiAgent` menu items register, exits 0/non-zero. Reproducible locally (`./gradlew :ai-agent:publishToMavenLocal :consumer-smoke:bootRunSmoke`) and in CI.
- **Operator docs** — single `ai-agent/README.md` (or `ai-agent/ai-agent/README.md` per planner's call) with mandated sections: Quick start, Required env vars, Configuration matrix, Entity/table ownership table, Upgrade checklist, Air-gap notes, SPI cookbook (one runnable example per SPI: `ToolContributor`, `ContextContributor`, `PromptContextContributor`, `ToolGuard`, `AuditListener`, `CustomIngester`). Demo path = the existing `jmix-app` harness; no separate demo-seed script.
- **Release polish** — version `1.0.0` in `ai-agent/gradle.properties`; new `CHANGELOG.md` at repo root following keepachangelog.com v1.1.0 (Added/Changed/Deprecated/Removed/Fixed/Security per phase). `maven-publish` plugin wired in `ai-agent/ai-agent/ai-agent.gradle` (and starter gradle as appropriate) using the user-supplied `publishing { repositories { maven { name='CustomNexus' ... } } }` block targeting `https://nexus.x2h.com.vn/repository/jmix-internal-snapshots/` with `nexusUsername`/`nexusPassword` project properties and `allowInsecureProtocol = true`.
- **CI workflow** — new `.github/workflows/ai-agent-ci.yml` with two PR-blocking jobs: (1) `./gradlew :ai-agent:ai-agent:test :ai-agent:ai-agent:integrationTest`; (2) `./gradlew :ai-agent:ai-agent:publishToMavenLocal :consumer-smoke:bootRunSmoke`. Live-tier suite as a separate `workflow_dispatch` workflow gated on `OPENROUTER_API_KEY` secret. Tag-pushed publish to Nexus as a third `workflow_dispatch` (or release-tag-triggered) workflow.
- **Mid-phase replan path** — once the test suites land and the first wave runs red, the orchestrator runs `/gsd-plan-phase 8 --gaps` to append bug-fix tasks. ROADMAP success criterion #1 (all integration tests green on CI non-live tier) is the gate.

**Out of scope:**

- **Auto-carrying Phase 7.1 deferred UAT issues.** The 5 deferred items (streaming `chatView.error.generic`, raw i18n key leak, tool-call markdown, knowledge upload, cancel audit, `conversationId` URL sync) are NOT automatically in scope. They enter scope only if the new test suites reproduce them, at which point `--gaps` replan adds them.
- **Mutation tools, new SPIs, new entities.** v1 surface is frozen.
- **`spring-ai-test` `EvaluationModel` for live-tier grading.** `containsAnyOf` anchors only.
- **Multi-model parameterization for live-tier.** Single model (active profile) per run.
- **Sonatype OSSRH / Maven Central publishing.** Private Nexus only this phase; Central is a v2 concern.
- **`maven-publish` of the demo `jmix-app` harness.** Only `ai-agent` and `ai-agent-starter` publish.
- **CI workflow for arbitrary host integrators.** This phase ships our own CI; consumer projects wire their own.
- **Demo seed script / `:jmix-app:demoSeed` task.** Existing Liquibase Customer/Order seeds + manual KB upload is sufficient for the README walk-through.
- **Three-tier source-set retrofit (TEST-01 completion).** If the planner finds creating `src/integrationTest` adds risk, co-locating the new tests in `src/test` is acceptable — flag in PLAN.md.
- **Any vector-store retrieval audit / streaming-thread propagation / cancellation-outcome / URL-sync / Liquibase recovery work** UNLESS reproduced by a fresh test in this phase.

</domain>

<decisions>
## Implementation Decisions

### Audit Method & Gating

- **D-01: Tests-first audit.** Phase 8 begins by writing the test suites (TEST-04 + injection extension + rollback extension + perf smoke + live-tier golden suite + clean-consumer smoke). Whatever those suites turn red against the current codebase IS the bug list — there is no separate Playwright pass, no `/gsd-audit-fix` wave, no upfront code-review-derived bug seeding. *Why:* aligns with "scope new phases fresh, not from old UAT debt" — the test suites are the audit, and they enforce that bug-fix work is real-failure-driven, not memory-driven.

- **D-02: Mid-phase `--gaps` replan after first red wave.** Initial PLAN.md contains only ROADMAP deliverables. After the first test wave runs and surfaces concrete failures, the orchestrator invokes `/gsd-plan-phase 8 --gaps` to append bug-fix tasks. *How to apply:* planner does NOT pre-bake fixes for the 5 Phase 7.1 deferred issues; only fixes that an actual red test demands enter scope.

### TEST-04 — Negative-Case Integration Suite

- **D-03: Three focused tests, one per ROADMAP acceptance concern.** `FilteredSchemaAndExecutionDenialTest`, `RagRoleFilterNegativeTest`, `CrossUserConversationAccessTest` — each owns one bullet of TEST-04. Pin-points failure in CI; mirrors the per-concern naming convention from Phase 04 (`AdvisorOrderStructuralTest`, `OwnershipOpacityTest`, etc.).

- **D-04: Reuse Phase 02 production roles + add one test-only `NoCustomerReadRole`.** Production role catalog (`AiAgentUserRole`, `AiAgentAdminRole`, `AiAgentUserRowLevelRole`) is unchanged. The single new restricted persona lives in test-only `@TestConfiguration` and exercises entity-level access denial against `Customer`. *Why:* keeps the ResourceRole catalog clean for v1 release; doesn't ship test scaffolding as production code.

- **D-05: Schema-filter assertion via direct `EffectiveSchemaComputer` comparison.** Test calls `EffectiveSchemaComputer.compute(restrictedUserContext)` and asserts the returned `AiSchema`'s `AiEntityInfo` for `Customer` has zero `AiAttributeInfo` entries (or `Customer` is absent entirely, depending on policy granularity). No ChatModel required; deterministic. Mirrors Phase 03 unit-test style.

- **D-06: Extend existing harness classes for injection + rollback cases.** `PromptInjectionHarnessTest` (Phase 03) gains a poisoned-tool-result fixture; `AuditDurabilityTest` (Phase 04) gains a tool-tx-rollback case. *Why:* both classes already have working `@SpringBootTest` fixtures; smaller surface area than new files.

### Performance Smoke

- **D-07: Hibernate Statistics + per-tool query-count assertion.** Enable `spring.jpa.properties.hibernate.generate_statistics=true` in the integrationTest profile. For each of the 6 built-in tools, run a tool-call against the demo Customer/Order schema and assert `SessionFactory.getStatistics().getQueryExecutionCount()` matches a baseline (e.g. `find_records` with `limit=10` → 1 SELECT, no related-fetch fan-out). Separate test asserts `find_records(limit=999999)` is capped at the configured constant. *Why:* lightweight, no new deps, works against existing `DataManager` path.

### TEST-05 — Live-Tier Semantic Suite

- **D-08: Capability-coverage 6-question set.** The 6 golden questions cover (1) schema introspection, (2) single-entity find, (3) multi-step tool chain, (4) RAG retrieval, (5) multi-turn memory, (6) refusal/guardrail. Each major v1 capability lights up at least once; trade-off accepted that no two questions exercise the same capability deeply.

- **D-09: `containsAnyOf` semantic anchors only — no `EvaluationModel`, no exact-text.** Mirrors `ChatServiceLiveSemanticTest` from Phase 04. Each question carries a small set of anchor tokens (e.g. `{"order", "customer", "$"}`); pass if any anchor appears (case-insensitive). *Why:* deterministic ceiling on flakiness, no doubled API spend, no judge-model dependency. The refusal/guardrail question uses negative anchors (e.g. assert response does NOT contain `"SYSTEM:"`).

- **D-10: YAML fixture under `src/test/resources/golden-questions.yaml`.** Schema: `[{id, prompt, anchors[], notAnchors[]?, expectedTools[]?, multiTurnPrior[]?, notes}]`. Loaded via Jackson YAML. *Why:* edit-without-recompile parity with Phase 06 `default-params.yaml`.

- **D-11: `@Tag("live")` + `@EnabledIfEnvironmentVariable(OPENROUTER_API_KEY)` gating.** Proven Phase 04 pattern. Default `./gradlew test` excludes; existing `liveTest` Gradle task includes; JUnit additionally skips when env var is missing.

- **D-12: Use the active `AiParameters` profile model.** Live test invokes `ChatService.ask(...)` normally; whichever model the active profile selects is what runs. *Why:* the suite tracks production behavior; pinning to a cheap model would diverge the test path from real usage. If cost becomes a concern, profile can be set in test setup to a cheaper model — but that's a future optimization, not a Phase 8 decision.

### TEST-07 — Clean-Consumer Smoke

- **D-13: Dedicated `consumer-smoke/` Gradle subproject in repo root.** Minimal Jmix app (single view, default DB) that depends on `com.vn.agent:ai-agent-starter` from Maven Local. Custom Gradle task `:consumer-smoke:bootRunSmoke` boots the app on a random port, asserts `ChatService` bean is wired and `aiAgent.*` menu entries register (via Vaadin route registry inspection or Spring `ApplicationContext` introspection), exits with non-zero on failure. Pipeline: `./gradlew :ai-agent:ai-agent:publishToMavenLocal :ai-agent:ai-agent-starter:publishToMavenLocal :consumer-smoke:bootRunSmoke`. *Why:* one source of truth, reproducible locally + CI; `docs/consumer-smoke.md` becomes the human-readable companion.

### Operator Docs

- **D-14: Single `README.md` at the add-on root** (`ai-agent/README.md` or `ai-agent/ai-agent/README.md` — planner picks based on consumer-discoverability conventions). Mandated sections: Quick start (3 commands to a running chat), Required env vars table, Configuration matrix (every `jmix.ai-agent.*` property + default + when to override), Entity/table ownership (every `AI_AGENT_*` table + owning entity + which Liquibase changeset creates it), Upgrade checklist (what to re-include, what changes between versions), Air-gap notes (no telemetry, where bundled keys would live if any), SPI cookbook (one minimal working example per SPI).

- **D-15: Demo path = existing `jmix-app` harness; no separate demo-seed script.** README walks the reader through cloning the repo, setting `OPENROUTER_API_KEY`, `./gradlew :jmix-app:bootRun`, login `admin/admin`, opening `/ai-agent/chat`. The existing Customer/Order Liquibase seeds + a brief KB-upload step satisfy ROADMAP success criterion #3 (working demo in <10 minutes).

### Release Polish

- **D-16: Version `1.0.0` in `ai-agent/gradle.properties`.** v1 MVP shipping label per PROJECT.md narrative. No `-RC1` hedge.

- **D-17: `CHANGELOG.md` at repo root, Keep-a-Changelog v1.1.0 format.** Sections per release: Added / Changed / Deprecated / Removed / Fixed / Security. Backfill entries for Phases 01–07.2 by scanning ROADMAP.md and phase SUMMARY files; create a fresh `[1.0.0] - 2026-XX-XX` section for the v1 release at end of phase.

- **D-18: `maven-publish` to private Nexus per user-supplied snippet.** Wire in `ai-agent/ai-agent/ai-agent.gradle` (and `ai-agent-starter` gradle) with the exact block:
  ```groovy
  publishing {
      repositories {
          maven {
              name = 'CustomNexus'
              url = 'https://nexus.x2h.com.vn/repository/jmix-internal-snapshots/'
              credentials {
                  username = project.findProperty('nexusUsername')
                  password = project.findProperty('nexusPassword')
              }
              allowInsecureProtocol = true
          }
      }
      publications {
          javaMaven(MavenPublication) {
              artifactId = archName
              from components.java
          }
      }
  }
  ```
  Credentials sourced from project properties (CI provides via `-PnexusUsername=... -PnexusPassword=...` from secrets). *Why:* user-prescribed; matches host org infrastructure.

- **D-19: CI workflow file `.github/workflows/ai-agent-ci.yml` lands in this phase.** Two PR-blocking jobs (tests/integrationTest; publishToMavenLocal + consumer-smoke). Live-tier and remote `publish` are separate `workflow_dispatch`-triggered workflows so PR runs don't pay live-API cost or hit Nexus on every push.

### Claude's Discretion

- Naming of new test classes within the patterns above (e.g. exact package layout under `security/`, `performance/`).
- Whether the `consumer-smoke` subproject uses the bundled HSQLDB or a Postgres testcontainer (default to HSQLDB for speed; planner may upgrade if Liquibase pgvector path needs verification).
- Exact menu-presence assertion mechanism in `bootRunSmoke` (Vaadin route registry vs Spring `MenuConfig` introspection vs HTTP probe of `/login` + `/ai-agent/chat`).
- CHANGELOG backfill granularity (per-phase entries vs per-plan entries).
- Whether to retain `docs/consumer-smoke.md` as a manual companion to the automated `consumer-smoke` subproject (recommended: keep, link from README).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope & roadmap
- `.planning/ROADMAP.md` § Phase 8 — deliverables list and success criteria (4 criteria)
- `.planning/REQUIREMENTS.md` lines 109–117 — TEST-01..TEST-07 acceptance text; TEST-04 + TEST-07 land here, TEST-02/03/05 completion

### Prior context that shapes this phase
- `.planning/phases/04-orchestration-core/04-CONTEXT.md` — D-11 `AuditWriter` is sole transactional surface (`REQUIRES_NEW`), D-13/14 `afterCommit` listener fan-out — relevant for rollback test
- `.planning/phases/05-rag-layer/05-CONTEXT.md` — RAG role-filter contract (RAG-04/05) — relevant for `RagRoleFilterNegativeTest`
- `.planning/phases/07.2-redesign-audit-schema-tree-lite/07.2-CONTEXT.md` — D-01/D-02 mutable root row, D-03/D-04 `AuditListener.onEventAudited(UUID, String kind)` SPI shape, `AuditKind` open string set — relevant for any audit-shaped fix that lands via `--gaps`
- `.planning/PROJECT.md` — v1 release narrative (1.0.0 framing); core value statement governs operator README emphasis

### Existing test patterns to mirror or extend
- `ai-agent/ai-agent/src/test/java/com/vn/agent/tools/PromptInjectionHarnessTest.java` (Phase 03) — extend with poisoned tool-result fixture
- `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java` (Phase 04) — extend with tool-tx-rollback case
- `ai-agent/ai-agent/src/test/java/com/vn/agent/live/ChatServiceLiveSemanticTest.java` (Phase 04) — pattern source for `@Tag("live")` + `containsAnyOf` + env-var gating
- `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/AdvisorOrderStructuralTest.java` (Phase 04) — naming convention for security tests
- `ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/OwnershipOpacityTest.java` (Phase 04) — closest existing analog for `CrossUserConversationAccessTest`

### Existing docs to extend or reference
- `docs/consumer-smoke.md` — manual flow that the new `consumer-smoke/` Gradle subproject codifies; keep as human companion
- `docs/versions.md` — version-matrix doc; bumping to 1.0.0 may require a section update
- `ai-agent/build.gradle` + `ai-agent/ai-agent/ai-agent.gradle` + `ai-agent/ai-agent-starter/ai-agent-starter.gradle` — `maven-publish` block lands here
- `ai-agent/settings.gradle` — confirms Jmix `${p1.name}.gradle` build-file convention

### Memory references (downstream agents should respect)
- `feedback_fresh_phase_scope.md` — scope new phases fresh; no auto-carry of UAT debt
- `feedback_pragmatic_modules.md` — keep 2-module shape; do NOT split into ai-agent-flowui in this phase
- `feedback_no_archunit.md` — TEST-06 stays dropped
- `feedback_jetbrains_mcp_in_workflow.md` — run `get_file_problems` on touched Java files before declaring done

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- **`PromptInjectionHarnessTest`** (`com.vn.agent.tools`) — already wires `@SpringBootTest` + mock ChatModel + tool execution. Extend with poisoned-tool-result fixture rather than building a new file.
- **`AuditDurabilityTest`** (`com.vn.agent.audit`) — already tests `REQUIRES_NEW` audit isolation; add a tool-tx-rollback method using the same fixture.
- **`ChatServiceLiveSemanticTest`** (`com.vn.agent.live`) — pattern source for `@Tag("live")` + `containsAnyOf` + `OPENROUTER_API_KEY` gating; the new golden-suite test follows this shape.
- **Phase 02 role catalog** — `AiAgentUserRole`, `AiAgentAdminRole`, `AiAgentUserRowLevelRole` already cover the production role surface; restricted personas are test-only additions.
- **Phase 03 `EffectiveSchemaComputer`** — already returns a per-user filtered `AiSchema`; `FilteredSchemaAndExecutionDenialTest` calls it directly.
- **`docs/consumer-smoke.md`** — already documents the manual smoke flow; the new `consumer-smoke/` subproject automates it.

### Established Patterns

- **Test source layout** — only `src/main` and `src/test` exist under `ai-agent/ai-agent/`. The planned `src/integrationTest` source set does NOT yet exist (TEST-01 partially shipped). Planner picks: (a) create `src/integrationTest` source set this phase to honor TEST-01, OR (b) co-locate the new tests in `src/test` and document the deviation. Default recommendation: create the source set — the release-readiness phase is the right place to land the three-tier intent.
- **Build files** — Jmix convention: each module has `<module>.gradle` (e.g. `ai-agent/ai-agent/ai-agent.gradle`), not `build.gradle`. The `maven-publish` snippet lands in those files.
- **`@Tag("live")` + `liveTest` Gradle task** — already configured in Phase 01; the new golden-suite test reuses this without new wiring.
- **i18n locale parity** — bilingual EN+VI on every user-visible string; SPI cookbook examples in README do NOT need locale parity (English-only is acceptable for code docs).
- **REQUIRES_NEW audit transactions** — Phase 04 D-11; rollback-preserves-audit test asserts this contract directly.

### Integration Points

- **`.github/workflows/`** — does not exist; Phase 8 creates the directory and the CI workflow file.
- **`consumer-smoke/`** — does not exist; new top-level Gradle subproject. Update `ai-agent/settings.gradle` (or root `settings.gradle` if the consumer-smoke project lives outside `ai-agent/`) to include it.
- **`CHANGELOG.md`** — does not exist at repo root; created in this phase.
- **`gradle.properties`** — `version` property bumped to `1.0.0`.
- **`ai-agent/ai-agent/ai-agent.gradle` + `ai-agent/ai-agent-starter/ai-agent-starter.gradle`** — `maven-publish` plugin and the user-supplied `publishing { ... }` block land in these.

</code_context>

<specifics>
## Specific Ideas

- **Nexus snippet (verbatim from user):**
  ```groovy
  publishing {
      repositories {
          maven {
              name = 'CustomNexus'
              url = 'https://nexus.x2h.com.vn/repository/jmix-internal-snapshots/'
              credentials {
                  username = project.findProperty('nexusUsername')
                  password = project.findProperty('nexusPassword')
              }
              allowInsecureProtocol = true
          }
      }
      publications {
          javaMaven(MavenPublication) {
              artifactId = archName
              from components.java
          }
      }
  }
  ```
- **Tests-first audit framing.** The user explicitly redirected the phase scope from "carry forward 7.1 deferred UAT items" to "scope completely fresh from current code; do not carry forward unless reproduced by a new audit/test." Saved as durable memory (`feedback_fresh_phase_scope.md`).
- **Capability-coverage golden questions.** Six-question set is fixed in scope; planner picks exact prompts during planning, but the capability matrix (schema introspection / single-entity find / multi-step tool chain / RAG / multi-turn / refusal) is locked.
- **`containsAnyOf` over `EvaluationModel`.** No second-LLM judge for live-tier; deterministic anchor-token assertion only.

</specifics>

<deferred>
## Deferred Ideas

- **Sonatype OSSRH / Maven Central publishing.** Phase 8 is private Nexus only; Central is a v2 release-engineering concern.
- **Multi-model live-tier parameterization.** Run live suite against multiple models (gpt-4o-mini, claude-haiku, gemini-flash) — defer until production usage justifies catching model-specific regressions.
- **`spring-ai-test` `EvaluationModel`-based grading.** Useful for adversarial / refusal evaluation but doubles cost; defer unless `containsAnyOf` proves insufficient.
- **`/gsd-audit-fix` formal pass.** The 5 deferred Phase 7.1 issues live in `07.1-UAT.md` and are not pre-listed as in-scope. If post-Phase-8 reveals reproducible debt, a small Phase 8.1 hotfix or a fresh `/gsd-audit-fix` run handles it. Documented here so future phases know they were considered.
- **Demo-seed Gradle task.** `:jmix-app:demoSeed` would bulletproof the <10-min demo claim; deferred because the existing Liquibase Customer/Order seeds + a manual KB upload meet the bar.
- **Three-tier source-set retrofit (TEST-01 completion as a standalone phase).** If the planner finds creating `src/integrationTest` adds risk, this becomes a Phase 8.1 cleanup instead.
- **`docs/`-folder split for operator material.** Single `README.md` is the v1 shape; a structured `docs/` tree is a v2 docs-engineering concern.
- **Per-SPI runnable example projects** (separate Gradle subprojects demonstrating `ToolContributor`, `CustomIngester`, etc.). Deferred — README cookbook covers the v1 bar.

</deferred>

---

*Phase: 08-integration-hardening-release-readiness*
*Context gathered: 2026-04-26*
