---
gsd_state_version: 1.0
milestone: v1.1.0
milestone_name: Prompt Hardening, Mutation Tools & Configurable Chat Surfaces
status: Defining requirements
last_updated: "2026-04-26T00:00:00.000Z"
last_activity: 2026-04-26
progress:
  total_phases: 0
  completed_phases: 0
  total_plans: 0
  completed_plans: 0
  percent: 0
---

# Project State

**Last updated:** 2026-04-26

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-04-26 — v1.1.0 milestone started)

**Core value:** Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.

**Current focus:** v1.1.0 — defining requirements and roadmap.

## Current Position

| Field | Value |
|-------|-------|
| Phase | Not started (defining requirements) |
| Plan | — |
| Status | Defining requirements |
| Last activity | 2026-04-26 — Milestone v1.1.0 started (scope confirmed via `/gsd-new-milestone`) |

## Archived Milestone

**MVP v1.0.0** — shipped 2026-04-26. Archive: `.planning/milestones/v1.0.0-ROADMAP.md`; requirements archive: `.planning/milestones/v1.0.0-REQUIREMENTS.md`; phase artifacts: `.planning/milestones/v1.0.0-phases/`.

See `.planning/MILESTONES.md` for the v1.0.0 close summary.

## Milestone v1.1.0 Scope (preview)

Detailed REQ-IDs are produced by the requirements step that follows this state reset. The high-level commitments captured at scope confirmation:

- Prompt-contract hardening — readable entity inventory, hide internal tool/entity names, `unknown_entity` retry contract.
- Tool-layer refinements — richer `describe_entity` wrapper, host-override SPI for tool fetch plans, LLM permission inventory.
- Mutation-capable built-in tools — create / update / related-write under `DataManager`, gated by Jmix `AccessManager` policies, opt-in per host, audited.
- AI-specific LLM exposure policy (SEED-007 activated) — admin-governed denylist/allowlist below user's Jmix permissions, with Flow UI.
- Chat task input — speech-to-text + task-scoped file attachment, separate from KB upload.
- Intent-driven extraction → prefilled Jmix forms.
- Configurable chat surfaces (SEED-005 activated, refined) — full / right-sidebar / floating launcher with admin toggle.

**Out of scope for v1.1:** collapsible tool-detail panel + ephemeral streaming-status indicator (deferred); clean-consumer smoke / PKG-05 / TEST-07 (Plan 08-05 carryover, deferred).

## Accumulated Context

### Pending Todos (9, all addressed in this milestone except where noted)

| Todo | Disposition in v1.1 |
|------|---------------------|
| `2026-04-26-inject-readable-entity-inventory-into-baseline-context.md` | In scope (prompt-contract hardening) |
| `2026-04-26-hide-internal-tool-and-entity-names-from-user-facing-chat.md` | In scope (prompt-contract hardening) |
| `2026-04-24-enforce-unknown-entity-retry-contract.md` | In scope (prompt-contract hardening) |
| `2026-04-24-refine-describe-entity-wrapper-around-selected-jmix-metadata.md` | In scope (tool-layer refinements) |
| `2026-04-24-add-explicit-host-override-for-tool-fetch-plans.md` | In scope (tool-layer refinements) |
| `2026-04-24-add-llm-permission-inventory.md` | In scope (tool-layer refinements) |
| `2026-04-24-add-dedicated-chat-speech-and-file-task-input.md` | In scope (chat task input) |
| `2026-04-24-add-intent-driven-extraction-to-prefilled-jmix-forms.md` | In scope (intent-driven extraction) |
| `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` | DEFERRED (out of v1.1 scope) |

### Seeds Reviewed at v1.1 Start

| Seed | Disposition |
|------|-------------|
| SEED-001 — Reviewed learning loop | Dormant — no production-incident trigger yet |
| SEED-002 — Pre-deploy answer-quality regression gate | Dormant — defer until prompt rules from v1.1 produce signal |
| SEED-003 — OutputScanner SPI | Dormant — no insufficiency signal |
| SEED-004 — Replay/diff runner | Dormant — pairs with SEED-002 future activation |
| SEED-005 — Configurable chat surfaces (refined: full / sidebar / floating + admin toggle) | **ACTIVATED** — in scope |
| SEED-006 — Strict file-backed knowledge path | Dormant — no retrieval-drift trigger |
| SEED-007 — AI-specific LLM exposure policy | **ACTIVATED** — in scope (paired with mutation tools) |

### Roadmap Evolution Notes (carried)

- Phase 7.2 was inserted after Phase 7.1 in v1.0.0: Redesign audit schema as tree-lite (PARENT_ID).
- 2026-04-26 (v1.1 scope decision): prompt-contract hardening bundle promoted into v1.1 first phase. Activates SEED-005 (refined to three configurable chat surfaces) and SEED-007 (AI exposure policy). Adds new mutation-tools scope on top of pending todos.
