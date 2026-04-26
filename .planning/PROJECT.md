# Jmix AI Copilot (ai-agent-core)

## What This Is

A reusable, enterprise-grade AI Copilot add-on for Jmix applications. Plug it into any Jmix 2.8+ app and it immediately understands the host's data model (via Jmix metamodel), answers questions through chat with tool calls over `DataManager`, grounds responses in uploaded business documents via RAG, and ships a built-in Flow UI (chat, conversations, parameters, knowledge base, audit). Hosts extend it through SPIs — custom tools, prompts, context providers, guards, custom ingesters, and audit listeners — without forking. (AI-specific exposure-policy SPI dropped per D-10; Jmix `AccessManager` is authoritative.)

## Core Value

**Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.**

If everything else fails, the MVP's read-only Q&A-over-host-entities-plus-documents flow must work with Jmix security enforced end-to-end.

## Current State

**Shipped version:** v1.0.0 MVP on 2026-04-26

The MVP is now a working Jmix add-on spanning packaging, secured metadata tools, Spring AI orchestration, RAG, guardrails, Flow UI, audit tree, release documentation, and GitHub Actions CI. The branch shipped through PR #3 and CI passed on `main`.

**In progress:** v1.1.0 milestone — prompt-contract hardening, mutation-capable built-in tools, AI-specific exposure governance, and configurable chat surfaces.

**Known production caveat:** the clean-consumer smoke requirement remains deferred. Plan 08-05 proved that a minimal consumer needs PostgreSQL/pgvector or a starter-provided stub VectorStore boot mode before the smoke can be made honest. Explicitly OUT of scope for v1.1; revisit in a later milestone.

## Current Milestone: v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces

**Goal:** Harden prompt/tool contracts, expand built-in tools to safe Jmix-secured mutations, give admins governance over the LLM-visible surface, and ship configurable chat surfaces (full / sidebar / floating).

**Target features:**

- Prompt-contract hardening — readable entity inventory in baseline context, internal tool/entity names hidden from user-facing chat, deterministic `unknown_entity` retry contract.
- Tool-layer refinements — richer `describe_entity` wrapper with selected Jmix metadata, host-controlled fetch-plan override SPI, LLM permission inventory at entity + attribute level.
- Mutation-capable built-in tools — create / update / related-write tools layered over `DataManager`, gated by Jmix `AccessManager` CRUD + attribute policies, opt-in by host configuration, audited end-to-end.
- AI-specific LLM exposure policy (SEED-007 activated) — admin-governed layer that narrows the LLM-visible surface BELOW the user's Jmix permissions; entity + attribute denylist/allowlist with Flow UI.
- Chat task input — speech-to-text and task-scoped file attachment in chat, separate lifecycle from KB upload.
- Intent-driven extraction → prefilled Jmix forms — intent-first workflow producing a structured draft that opens a Jmix form with prefilled data after user confirmation.
- Configurable chat surfaces (SEED-005 activated, refined) — three presentation surfaces over the same backend and reusable `ChatPanelFragment` (full `ChatView`, right-sidebar chat, floating launcher) with admin-controlled toggle for which surfaces are enabled/visible.

**Explicitly OUT of scope for v1.1:**

- Collapsible per-turn tool-detail panel + ephemeral streaming-status component in chat UI (deferred — small UX polish, not blocking).
- Clean-consumer smoke (PKG-05 / TEST-07) — Plan 08-05 carryover; deferred.

## Requirements

### Validated

- ✓ Standard two-module Jmix add-on packaging (`ai-agent` + `ai-agent-starter`) — v1.0.0
- ✓ Metadata-first schema discovery and six read-only tools over Jmix `DataManager` — v1.0.0
- ✓ Jmix-native security posture through `AccessManager`, `DataManager`, row-level roles, and `jmix-security-data` — v1.0.0
- ✓ Spring AI orchestration with ChatClient, JDBC memory, tool calling, RAG advisor, structured output, and guardrails — v1.0.0
- ✓ Durable audit tree with listener SPI and Flow UI inspection — v1.0.0
- ✓ pgvector RAG ingestion/retrieval with role-scoped filters and document lifecycle operations — v1.0.0
- ✓ Built-in Flow UI for chat, conversations, parameters, knowledge base, and audit — v1.0.0
- ✓ Release readiness: operator README, CHANGELOG 1.0.0, CI workflows, and Phase 8 regression bars green — v1.0.0

### Active (v1.1.0 — being defined)

Detailed REQ-IDs are produced by the v1.1 requirements gathering step that follows; this list previews the high-level commitments:

- [ ] Prompt-contract hardening: readable entity inventory in baseline context, hide internal tool/entity names from user-facing chat, enforce `unknown_entity` retry contract.
- [ ] Tool-layer refinements: richer `describe_entity` wrapper, host-override SPI for tool fetch plans, LLM permission inventory (entity + attribute level).
- [ ] Mutation-capable built-in tools: create / update / related-write under `DataManager`, gated by Jmix `AccessManager` policies, opt-in per host, audited.
- [ ] AI-specific LLM exposure policy: admin-governed denylist/allowlist that narrows the LLM-visible surface beneath the user's Jmix permissions, with Flow UI.
- [ ] Chat task input: speech-to-text and task-scoped file attachment, separate from KB ingestion.
- [ ] Intent-driven extraction → prefilled Jmix forms: intent-first workflow with confirmed UI navigation and form prefill.
- [ ] Configurable chat surfaces: full `ChatView`, right-sidebar chat, floating launcher; admin toggle for which are enabled/visible.

### Deferred (not in v1.1)

- [ ] Clean-consumer smoke (PKG-05 / TEST-07): Plan 08-05 carryover from v1.0.0. Either Postgres/pgvector Testcontainers smoke or a starter stub VectorStore boot mode. Revisit in a later milestone.
- [ ] Collapsible per-turn tool-detail panel + ephemeral streaming-status indicator in chat UI: secondary UX polish; deferred to a later milestone.

### Out of Scope

- Autonomous multi-step agents remain out of v1 scope; future loop support needs separate safety and cost controls.
- Mutation tools remain opt-in future work; v1 ships read-only tools by default.
- Auto-ingesting host entity records into the vector store remains deferred; `DataManager` stays the source of truth.
- URL/web crawling ingestion remains deferred.
- Native non-OpenAI-compatible provider starters remain deferred; hosts can swap `ChatModel` or use OpenRouter-compatible routing.
- Jmix internal APIs remain forbidden.
- Universal-agent positioning remains out of scope; this project is specifically a Jmix copilot add-on.
- Custom vector-store abstractions remain out of scope; use Spring AI `VectorStore` directly.

## Context

**Existing repo structure (brownfield):** Composite Gradle build with two includeBuilds —
- `ai-agent/` — the add-on source (modules: `ai-agent` functional, `ai-agent-starter` auto-config). Flow UI modules not yet added.
- `jmix-app/` — demo host (Customer + Order sample), HSQLDB, Vaadin Flow UI, currently consumes the add-on via `com.vn:ai-agent-starter`.

**Reference implementations (for pattern-learning only, not dependencies):**
- `D:/Study materials spring 2026/EXE101/ai/jmix-ai-backend` — Jmix + Spring AI 1.1.x + pgvector + Flow UI admin views (Chat / Parameters / VectorStore / Answer checks). Same shape we want for admin UI, but domain-specific (Jmix docs Q&A). We generalize.
- `D:/ai/traffic-law-chatbot` — Spring AI + OpenRouter wiring pattern (OpenAI starter with custom `base-url`, per-request model selection via `ChatOptions`).

**Ecosystem realities:**
- Spring AI 1.1.4 is a milestone release — APIs and starter coordinates are still shifting; research must verify current syntax via Context7/official docs, not training data.
- Jmix 2.8 uses Spring Boot 3, Java 17, Vaadin Flow — matches Spring AI's Boot 3 requirement.
- `DataManager` fluent API is the only supported entry point for secured data access; `EntityManager` bypasses Jmix security and is explicitly forbidden in this codebase (see `CLAUDE.md`).

**Why now:** Jmix lacks a first-party AI copilot. Customer enterprise Jmix apps increasingly want "ask your data" UX, but rolling it safely is expensive. A reusable metadata-first add-on lets every Jmix app get a governed copilot with minimal custom code.

## Constraints

- **Tech stack**: Jmix 2.8 + Spring Boot 3 + Vaadin Flow + Java 17 — fixed by host ecosystem
- **Spring AI version**: 1.1.4 — pinned via BOM (upgraded between Phase 1 wave start and Phase 2 start per D-10; STACK.md updated accordingly)
- **Vector store default**: pgvector — reuses Postgres infra familiar to Jmix enterprise deployments
- **Data access**: `DataManager` only — `EntityManager` forbidden by project conventions and breaks Jmix security model
- **Entities**: No Lombok on entities; UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`; instantiate via `Metadata.create()` / `DataManager.create()`
- **Security**: Jmix roles + data security is the single enforcement layer (per D-10 / MEMORY "AI is just another Jmix client"). No AI-specific exposure layer in v1; revisit only if a concrete "AI must see less than user" use case surfaces.
- **Packaging**: Must be distributable as Maven artifacts; no internal Jmix APIs; starter auto-configuration conventions
- **Safety**: Read-only default; all tool calls auditable; mutations require explicit host opt-in
- **Testing**: Live LLM tests must be opt-in and excluded from default CI (cost + flakiness)
- **UI**: Vaadin Flow server-side; all labels via `msg://` keys in `messages*.properties` (per `CLAUDE.md`)

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Keep existing two-sided repo: `ai-agent/` add-on + `jmix-app/` host | Repo already formalizes the add-on / consumer split; demo host doubles as integration-test harness | ✓ Good |
| Spring AI 1.1.4 with official primitives only (upgraded per D-10) | Product spec mandate; avoid custom abstractions so Spring AI upgrades stay cheap | ✓ Good |
| OpenAI-compatible provider (via OpenRouter) as MVP default | Matches reference projects; one starter covers many models; per-request model switching via `ChatOptions` | ✓ Good |
| pgvector as default vector store | Reuses Postgres familiar to Jmix enterprise; Spring AI first-class support; matches reference | ✓ Good |
| Audit records as Jmix JPA entity in host DB | Queryable via DataManager, visible in Jmix admin UI, inherits Jmix security; exposable via SPI listener for side-channels | ✓ Good |
| Read-only MVP (6 generic tools, no mutations) | Safety + scope control; mutation SPI scaffolded for later opt-in | ✓ Good |
| MVP UI: Chat + Conversations + Parameters + KB + Audit | Full admin suite modeled on `jmix-ai-backend` reference; "plug and play" requires no external tools | ✓ Good |
| Any authenticated user gets Chat; admin role gates settings | Low friction for end-users; safe defaults for governance | ✓ Good |
| File upload only for KB ingestion in v1 | Covers enterprise doc flows; URL crawling/entity auto-ingest deferred | ✓ Good |

### Deferred Decisions

- **Split add-on into 4 modules** (`ai-agent-flowui` + `ai-agent-flowui-starter` alongside existing `ai-agent` + `ai-agent-starter`) — was Key Decision #2. **Deferred** per [D-01 in Phase 1 CONTEXT](milestones/v1.0.0-phases/01-walking-skeleton/01-CONTEXT.md). Trigger: a named REST-only consumer use case that cannot accept Vaadin deps. Until then, `ai-agent-starter` ships with UI and PKG-04 (zero-Vaadin functional module posture) remains open.

- **Ship `AiExposureRule` entity + `EntityExposurePolicy` SPI + `ExposureRuleListView`** — was part of v1 scope. **Deferred** per [D-10 in Phase 2 CONTEXT](milestones/v1.0.0-phases/02-foundations/02-CONTEXT.md). Trigger: a concrete consumer case where Jmix `AccessManager` + `DataManager` row-/attribute-level policies are insufficient to constrain what the agent sees. Until then, authorization is the host's existing Jmix security stack (per MEMORY note "AI is just another Jmix client").

- **ArchUnit enforcement of layering / `.impl.` imports / `no DataManager.save in @Tool`** (TEST-06, parts of TOOL-08) — was part of v1 scope. **Deferred** per [D-10 in Phase 2 CONTEXT](milestones/v1.0.0-phases/02-foundations/02-CONTEXT.md) per MEMORY note "Avoid ArchUnit until drift". Code review + targeted unit-test conventions remain authoritative until rule drift justifies ArchUnit.

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-26 — v1.1.0 milestone started*
