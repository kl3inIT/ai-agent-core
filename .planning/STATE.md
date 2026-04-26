---
gsd_state_version: 1.0
milestone: v1.1.0
milestone_name: milestone
status: Roadmap defined
last_updated: "2026-04-26T17:22:21.939Z"
progress:
  total_phases: 6
  completed_phases: 0
  total_plans: 0
  completed_plans: 0
---

# Project State

**Last updated:** 2026-04-26

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-04-26 — v1.1.0 milestone started)

**Core value:** Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.

**Current focus:** v1.1.0 — roadmap defined (Phases 9-14); ready for `/gsd-plan-phase 9`.

## Current Position

| Field | Value |
|-------|-------|
| Phase | Phase 9 (not started) |
| Plan | — |
| Status | Roadmap defined; awaiting Phase 9 planning |
| Last activity | 2026-04-26 — ROADMAP.md written; six phases (9-14) mapped to all v1.1 active REQ-IDs |

## Phase Status

| Phase | Status | Plans Complete | Started | Completed |
|-------|--------|----------------|---------|-----------|
| 9. Tool-Layer Foundations & Prompt-Contract Hardening | Not started | 0/0 | - | - |
| 10. AI-Specific LLM Exposure Policy | Not started | 0/0 | - | - |
| 11. Mutation-Capable Built-In Tools | Not started | 0/0 | - | - |
| 12. Configurable Chat Surfaces | Not started | 0/0 | - | - |
| 13. Chat Task Input — STT + Task-Scoped File | Not started | 0/0 | - | - |
| 14. Intent-Driven Extraction → Form Prefill | Not started | 0/0 | - | - |

## Hard Build-Order

- Hard chain: Phase 9 → Phase 10 → Phase 11.
- Soft chain: Phase 12 → Phase 13 → Phase 14 (each independent of each other; 12/13 require Phase 9; 14 requires Phase 9 AND Phase 10).
- Mutation tools default OFF on ship (Phase 11 `@ConditionalOnProperty`).
- Exposure policy is `EXCLUDE`-only at the rule shape (Phase 10).
- LLM never receives `ViewNavigators` (Phase 14).
- Audit reuses `AuditWriter.writeToolCall`; no new `AuditKind`.

## Archived Milestone

**MVP v1.0.0** — shipped 2026-04-26. Archive: `.planning/milestones/v1.0.0-ROADMAP.md`; requirements archive: `.planning/milestones/v1.0.0-REQUIREMENTS.md`; phase artifacts: `.planning/milestones/v1.0.0-phases/`.

See `.planning/MILESTONES.md` for the v1.0.0 close summary.

## Milestone v1.1.0 Scope

Detailed REQ-IDs in `.planning/REQUIREMENTS.md`. Roadmap in `.planning/ROADMAP.md`.

- Prompt-contract hardening — Phase 9.
- Tool-layer refinements — Phase 9.
- AI-specific LLM exposure policy (SEED-007 activated) — Phase 10.
- Mutation-capable built-in tools — Phase 11.
- Configurable chat surfaces (SEED-005 activated, refined) — Phase 12.
- Chat task input (STT + task-scoped file) — Phase 13.
- Intent-driven extraction → prefilled Jmix forms — Phase 14.

**Out of scope for v1.1:** collapsible tool-detail panel + ephemeral streaming-status indicator (deferred); clean-consumer smoke / PKG-05 / TEST-07 (Plan 08-05 carryover, deferred).

## Accumulated Context

### Pending Todos (9 — disposition)

| Todo | Disposition in v1.1 |
|------|---------------------|
| `2026-04-26-inject-readable-entity-inventory-into-baseline-context.md` | Phase 9 (PROMPT-01) |
| `2026-04-26-hide-internal-tool-and-entity-names-from-user-facing-chat.md` | Phase 9 (PROMPT-03, PROMPT-04, PROMPT-06) |
| `2026-04-24-enforce-unknown-entity-retry-contract.md` | Phase 9 (PROMPT-05) |
| `2026-04-24-refine-describe-entity-wrapper-around-selected-jmix-metadata.md` | Phase 9 (TOOL-09) |
| `2026-04-24-add-explicit-host-override-for-tool-fetch-plans.md` | Phase 9 (TOOL-10, TOOL-11) |
| `2026-04-24-add-llm-permission-inventory.md` | Phase 9 (TOOL-12, PROMPT-02) |
| `2026-04-24-add-dedicated-chat-speech-and-file-task-input.md` | Phase 13 |
| `2026-04-24-add-intent-driven-extraction-to-prefilled-jmix-forms.md` | Phase 14 |
| `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` | DEFERRED (out of v1.1 scope) |

### Seeds Reviewed at v1.1 Start

| Seed | Disposition |
|------|-------------|
| SEED-001 — Reviewed learning loop | Dormant — no production-incident trigger yet |
| SEED-002 — Pre-deploy answer-quality regression gate | Dormant — defer until prompt rules from v1.1 produce signal |
| SEED-003 — OutputScanner SPI | Dormant — config-driven scanner sufficient |
| SEED-004 — Replay/diff runner | Dormant — pairs with SEED-002 |
| SEED-005 — Configurable chat surfaces | **ACTIVATED** — Phase 12 |
| SEED-006 — Strict file-backed knowledge path | Dormant — no retrieval-drift trigger |
| SEED-007 — AI-specific LLM exposure policy | **ACTIVATED** — Phase 10 (paired with Phase 11 mutation tools) |

### Roadmap Evolution Notes (carried)

- Phase 7.2 was inserted after Phase 7.1 in v1.0.0: Redesign audit schema as tree-lite (PARENT_ID).
- 2026-04-26 (v1.1 scope decision): prompt-contract hardening bundle promoted into v1.1 first phase. Activates SEED-005 (refined to three configurable chat surfaces) and SEED-007 (AI exposure policy). Adds new mutation-tools scope on top of pending todos.
- 2026-04-26 (v1.1 roadmap): six phases (9-14) defined; numbering continues from v1.0.0 close (Phase 8 + 7.1 + 7.2). All active REQ-IDs mapped; no orphans.

## Session Continuity

**Next action:** `/gsd-plan-phase 9` (Tool-Layer Foundations & Prompt-Contract Hardening).
