---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: Executing Phase 03
last_updated: "2026-04-19T18:30:00.000Z"
progress:
  total_phases: 3
  completed_phases: 2
  total_plans: 20
  completed_plans: 18
  percent: 90
---

# Project State

**Last updated:** 2026-04-19

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-04-18)

**Core value:** Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.

**Current focus:** Phase 03 — metadata-first-runtime-six-tools

## Phase Status

| # | Phase | Status |
|---|-------|--------|
| 1 | Walking Skeleton & Packaging De-risk | ✅ Complete (merged to master) |
| 2 | Foundations | ✅ Complete (11/11 plans, static verification PASS — pending human Gradle verify) |
| 3 | Metadata-First Runtime & Six Tools | Context captured; planning pending |
| 4 | Orchestration Core | Not started |
| 5 | RAG Layer | Not started |
| 6 | Parameters, Structured Output & Guardrails | Not started |
| 7 | Flow UI | Not started |
| 8 | Integration Hardening & Release Readiness | Not started |

## Active Milestone

**MVP v1** — 8 phases, 69 requirements. 2/8 phases complete.

## Phase 01 Outcome

All 4 plans completed and merged into master:

- 01-01: Spring AI BOM pinned at **1.1.4** (landed on 1.0.2 initially; upgraded before Phase 2 completion per D-10), OpenRouter wired, liveTest task split
- 01-02: ChatService SPI + DefaultChatServiceImpl + AIAutoConfiguration ChatClient @Bean
- 01-03: ChatServiceMockTest + ChatServiceLiveTest (@Tag("live"), opt-in)
- 01-04: docs/versions.md, docs/consumer-smoke.md, ChatServiceSmokeRunner injection proof, ROADMAP/PROJECT D-01

**Human-verify confirmed (2026-04-18 21:55):** `jmix-app` boots from Maven-Local-resolved starter; `ChatServiceSmokeRunner` logs `ChatService bean present: class=com.vn.agent.DefaultChatServiceImpl`.

## Known Follow-ups (not blocking Phase 2)

- HSQLDB file-lock flakiness on Windows during aborted boots. Pre-boot hygiene documented; consider Postgres migration for `jmix-app` if it recurs.

## Phase 02 Progress

All 11 plans complete on branch `gsd/phase-02-foundations`:

- 02-01: ✅ 3 EnumClass<String> enums (AiMessageRole, AiKnowledgeDocumentStatus, AiToolCallOutcome) + EN/VI i18n. Enum ids upper-case to match Spring AI 1.1.4 chat-memory CHECK constraint.
- 02-02: ✅ 6 SPI interfaces + ToolVetoedException in com.vn.agent.spi (signatures + Javadoc only)
- 02-03: ✅ 5 JPA entities (AiConversation composes AiMessage via @Composition+CASCADE; enum adapters; no Lombok) + i18n
- 02-04: ✅ 5 entity DDL Liquibase changelogs (010-050) + add-on master + host jmix-app explicit `<include>` (D-02)
- 02-05: ✅ SPRING_AI_CHAT_MEMORY DDL (postgres+hsqldb gated) + `initialize-schema=never`
- 02-06: ✅ pgvector 070 DDL (vector(1536) + HNSW vector_cosine_ops) postgres-gated belt-and-suspenders
- 02-07: ✅ SpiDefaultsAutoConfiguration with 6 @ConditionalOnMissingBean no-op defaults + AutoConfiguration.imports
- 02-08: ✅ 3 Jmix roles (AiAgentUserRole, AiAgentAdminRole, AiAgentUserRowLevelRole with :current_user_username JPQL) + i18n
- 02-09: ✅ @JmixModule(dependsOn) widened to include DataConfiguration + SecurityConfiguration
- 02-10: ✅ FoundationsBootSmokeTest @SpringBootTest (5 assertions: Liquibase, entity round-trip, row-level isolation, SPI defaults, role catalog)
- 02-11: ✅ D-10 scope reductions + D-02 include correction synced to REQUIREMENTS/ROADMAP/PROJECT/STACK docs

**Static verification:** PASS (16/16 must-haves — see `.planning/phases/02-foundations/VERIFICATION.md`)

## Next Steps

1. **Human-verify Phase 2:** run `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.FoundationsBootSmokeTest"` and `./gradlew :jmix-app:bootRun` to confirm the phase gate passes on a real JVM.
2. Merge `gsd/phase-02-foundations` → `master` once human-verified.
3. Start Phase 3 — Metadata-First Runtime & Six Tools.

## Key Artifacts

| Artifact | Path |
|---|---|
| Product context | `.planning/PROJECT.md` |
| Requirements | `.planning/REQUIREMENTS.md` |
| Roadmap | `.planning/ROADMAP.md` |
| Research summary | `.planning/research/SUMMARY.md` |
| Phase 1 plans/summaries | `.planning/phases/01-walking-skeleton/` |
| Codebase map | `.planning/codebase/` |
| Config | `.planning/config.json` |

## Phase 03 Progress

- 03-01: ✅ Metadata core — 6 files under `com.vn.agent.metadata`: `AiSchema`, `AiEntityInfo`, `AiAttributeInfo`, `UserEditableStringIndex` (DTOs); `MetamodelScanner` (TOOL-01, `ApplicationReadyEvent`, MetadataTools.isJpa-backed D-13 index); `EffectiveSchemaComputer` (TOOL-02, stateless AccessManager filter + per-request MessageTools labels). Commits `fec54d5`, `0856763`, `00960b7`. Deviation: plan said `io.jmix.core.security.AccessManager`; Jmix 2.8 ships it at `io.jmix.core.AccessManager` (Rule 1 bug-fix applied at Task 3 write time).
- 03-02: ✅ Filter DSL + tool primitives — 10 files under `com.vn.agent.filter` and `com.vn.agent.tools`: sealed `FilterNode` (+ `AndNode`/`OrNode`/`NotNode`/`LeafNode`); `LiteralCoercer` (strict fail-closed D-07); `FilterDslMapper` (TOOL-05, DeMorgan NOT, 13 D-05 operators, depth cap + per-hop AccessManager from D-08); `ToolLimits` (TOOL-06 DEFAULT_LIMIT=20/MAX_LIMIT=100/DEFAULT_MAX_FILTER_DEPTH=3); `ToolErrorDto` + `ToolUserError`. `module.properties` gains `jmix.ai-agent.tools.max-filter-depth = 3`. Commits `998c500`, `c08d8d3`, `542891b`. Deviations: (1) Jmix 2.8 op constant is `NOT_CONTAINS`, not `DOES_NOT_CONTAIN` — mapper accepts both DSL spellings, emits real Jmix constant (Rule 1 bug); (2) renamed a Javadoc mention of `JpqlCondition` to `raw-JPQL` to satisfy acceptance grep (Rule 3).
- 03-03: ✅ LLM-facing tool surface — 4 files added / 2 modified: `ToolResultFormatter` (`<data>` wrap + literal-delimiter HTML escape, D-13/Pitfall 4), `BuiltInDataTools` (single @Component, six @Tool methods — `list_entities`/`describe_entity`/`find_records`/`count_records`/`get_record`/`get_related_records` — all read-only via `DataManager.load`/`.getCount`, `FetchPlan.INSTANCE_NAME`, `ToolLimits.clampLimit`), `AgentToolCallbacks` (per-request `ToolCallback[]` via `MethodToolCallbackProvider` — Phase 4 entry point), `AiToolsAutoConfiguration` (`@AutoConfigureAfter` AI + SPI defaults). `AutoConfiguration.imports` now 3 lines. `ai-agent.gradle` adds `org.ow2.asm:asm:9.7` testImplementation for Plan 04 D-16. Commits `d4f3e98`, `bfd76c9`, `ece124c`. Deviations: (1) Spring AI 1.1.4 has no `ToolCallbacks.from(bean)` — replaced with `MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()` (Rule 1 bug in plan interfaces); (2) `LoadContext.createQuery(...)` is not a static factory — used `new LoadContext.Query(...)` (Rule 1 bug in plan interfaces).

## Session Tracking

- 2026-04-19 13:28 +07:00 — Forensic investigation complete. Resume from `.planning/forensics/report-20260419-132820.md`.
- 2026-04-19 16:24 +07:00 — Plan 03-01 complete (metadata core). Next: 03-02 (FilterNode DSL / LiteralCoercer / FilterDslMapper).
- 2026-04-19 17:35 +07:00 — Plan 03-02 complete (Filter DSL + tool primitives). Next: 03-03 (ToolResultFormatter + BuiltInDataTools six @Tool methods).
- 2026-04-19 18:30 +07:00 — Plan 03-03 complete (LLM-facing tool surface). Next: 03-04 (unit tests + PromptInjectionHarnessTest + ASM BuiltInDataToolsReadOnlyTest).
