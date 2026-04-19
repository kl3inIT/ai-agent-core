---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: In progress
last_updated: "2026-04-19T03:31:00.000Z"
progress:
  total_phases: 2
  completed_phases: 1
  total_plans: 15
  completed_plans: 5
  percent: 33
---

# Project State

**Last updated:** 2026-04-19

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-04-18)

**Core value:** Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.

**Current focus:** Phase 02 — Foundations (in progress — plan 02-01 complete)

## Phase Status

| # | Phase | Status |
|---|-------|--------|
| 1 | Walking Skeleton & Packaging De-risk | ✅ Complete (merged to master) |
| 2 | Foundations | In progress (1/11 plans) |
| 3 | Metadata-First Runtime & Six Tools | Not started |
| 4 | Orchestration Core | Not started |
| 5 | RAG Layer | Not started |
| 6 | Parameters, Structured Output & Guardrails | Not started |
| 7 | Flow UI | Not started |
| 8 | Integration Hardening & Release Readiness | Not started |

## Active Milestone

**MVP v1** — 8 phases, 73 requirements. 1/8 phases complete.

## Phase 01 Outcome

All 4 plans completed and merged into master:

- 01-01: Spring AI BOM pinned at **1.0.2** (downgrade from 2.0.0-M4 due to Spring 7 / Jmix 2.8 incompatibility), OpenRouter wired, liveTest task split
- 01-02: ChatService SPI + DefaultChatServiceImpl + AIAutoConfiguration ChatClient @Bean
- 01-03: ChatServiceMockTest + ChatServiceLiveTest (@Tag("live"), opt-in)
- 01-04: docs/versions.md, docs/consumer-smoke.md, ChatServiceSmokeRunner injection proof, ROADMAP/PROJECT D-01

**Human-verify confirmed (2026-04-18 21:55):** `jmix-app` boots from Maven-Local-resolved starter; `ChatServiceSmokeRunner` logs `ChatService bean present: class=com.vn.agent.DefaultChatServiceImpl`.

## Known Follow-ups (not blocking Phase 2)

- HSQLDB file-lock flakiness on Windows during aborted boots. Pre-boot hygiene documented; consider Postgres migration for `jmix-app` if it recurs.

## Phase 02 Progress

- 02-01: ✅ Three EnumClass<String> enums (AiMessageRole, AiKnowledgeDocumentStatus, AiToolCallOutcome) + EN/VI i18n. Decision: enum ids frozen to upper-case to match Spring AI 1.1.4 chat-memory CHECK constraint.

## Next Steps

1. Continue Phase 2 — next plan 02-02 (SPI interfaces + ToolVetoedException).
2. Phase 2 depends on Phase 1's `ChatService` SPI and the 1.0.2 BOM pin.

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
