---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: In progress
last_updated: "2026-04-19T06:32:23Z"
progress:
  total_phases: 8
  completed_phases: 2
  total_plans: 15
  completed_plans: 15
  percent: 25
---

# Project State

**Last updated:** 2026-04-19

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-04-18)

**Core value:** Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.

**Current focus:** Phase 03 — Metadata-First Runtime & Six Tools (context captured; documentation consistency repaired; planning next). Phase 02 human verification and branch merge remain pending.

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

## Session Tracking

- 2026-04-19 13:28 +07:00 — Forensic investigation complete. Resume from `.planning/forensics/report-20260419-132820.md`.
