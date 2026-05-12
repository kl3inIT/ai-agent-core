# Jmix AI Agent (ai-agent-core)

## What This Is

A reusable, enterprise-grade **AI agent add-on (agent harness)** for Jmix applications — the engineered runtime that wires an LLM agent into a Jmix app safely, not a packaged "copilot" product. Plug it into any Jmix 2.8+ app and it immediately understands the host's data model (via the Jmix metamodel), answers questions through chat with tool calls over `DataManager`, grounds responses in uploaded business documents via RAG, can (opt-in) perform Jmix-secured create/update/related-write mutations, can read attached files directly (multimodal), can extract structured drafts that open prefilled Jmix detail views after user confirmation, and ships a built-in Flow UI (chat — full route or header-button dialog — conversations, parameters, knowledge base, exposure rules, vector-store debug, audit). Admins can narrow the LLM-visible surface below the current user's Jmix permissions via an entity-level exposure denylist. Hosts extend it through SPIs — custom tools, prompts, context providers, guards, mutation guards, fetch-plan customizers, intent extractors, custom ingesters, audit listeners — without forking.

## Core Value

**Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.**

The non-negotiable floor: read-only Q&A over host entities + documents must work with Jmix security enforced end-to-end. Everything beyond that (mutations, file read, intent extraction, multi-surface UI) is opt-in and stays behind the same Jmix `AccessManager` enforcement boundary.

## Current State

**Shipped versions:** v1.0.0 MVP (2026-04-26) · v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces (2026-05-11, via PR #28).

v1.1.0 turned the read-only MVP into a mutation-capable, governance-aware, multi-surface AI agent without new core dependencies: prompt/tool-contract hardening (`agent.entities`/`agent.permissions` baseline, leak guards, `unknown_entity` retry), an admin LLM-exposure denylist (`AiExposureRule` + `LlmExposurePolicy`, uniform `unknown_entity` opacity), opt-in built-in mutation tools (layered fail-closed gating, idempotency, PII-safe errors, full audit), configurable chat surfaces (`FULL_ROUTE` + `HEADER_BUTTON` over one `ChatPanelFragment`, cross-surface continuity), chat task files (transient, attach + multimodal `Media` read + `bulk_save_records`, default model swap to Apache-2.0 `qwen/qwen3.6-35b-a3b`, CRM-style right-pane with per-turn-all injection), and intent-driven extraction → prefilled Jmix forms (`IntentExtractor<T>` SPI, `prepare_form_draft` / `propose_action_choices`, controller-side-only navigation, permission-gated prefill). Milestone audit: integration + E2E PASS; status `tech_debt` for bookkeeping (see below).

**Carried debt (not blockers):**
- Phase 10 `10-VERIFICATION.md` is still `human_needed` — goal achieved (4/4 ROADMAP criteria, 12/12 REQ IDs); the substantive REVIEW items (BLOCKER-01/02, WARNING-08) are fixed in code; visual-UI checks + WARNING-01 (RAG partial-failure window) never formally recorded. Optional `/gsd-verify-work 10`.
- Nyquist `*-VALIDATION.md` exists only for Phase 14; backfill phases 9/10/11/12/13/13.1 with `/gsd-validate-phase` (v1.2 hardening pass).
- Clean-consumer smoke (PKG-05 / TEST-07) still deferred from v1.0.0 Plan 08-05 — needs PostgreSQL/pgvector Testcontainers OR a starter-provided stub `VectorStore` boot mode.

## Current Milestone: v1.2 Operator Experience, Voice Input & Runtime Performance

**Goal:** Turn the v1.1 AI agent into one operators can tune and observe — admin model/config management, in-chat observability, voice input, mutation-internals hardening, and a targeted AI-runtime performance pass.

**Target features:**
- **Chat voice input — Soniox STT (trimmed):** Soniox default transcription path + OpenAI-direct fallback, browser `MediaRecorder` capture, privacy-safe `STT_TRANSCRIPTION` audit (SHA-256 hash by default / raw transcript opt-in), non-blocking error + retry UI, transcript lands in `MessageInput` for user review before send. `TranscriptionPostProcessor` SPI and custom-provider SPI deferred until a real host need appears.
- **Right-sidebar chat surface & observability UX:** `SIDEBAR` / right-sidebar chat surface over the existing `ChatPanelFragment`, plus collapsible per-turn tool-detail panel and ephemeral streaming-status indicator (resolves the pending `add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui` todo). The chat-state side panel is deferred.
- **Admin model management:** curated common-model dropdown + custom model-name free-entry in the admin Parameters/Settings UI; admin-only (no per-conversation end-user model switching). Curated defaults stay self-hostable open-weights per project policy; the custom-entry field allows anything the host routes to.
- **Admin config-knob migration:** audit prior-phase properties-only knobs (RAG top-k / similarity threshold, mutation toggle, task-file token budget, chat surface mode, STT toggle, etc.) and migrate the operator-relevant ones into editable `AiParameters` / admin UI.
- **Phase 11 mutation-internals hardening:** refactor duplicated mutation-gate sequencing, batch-load to-one FK references during mutation binding, cache related-write metadata resolution where safe (ROADMAP Backlog → Phase 999.1).
- **AI-runtime performance pass (targeted — no benchmark harness):** optimize known/suspected hotspots in chat turn execution, tool calls, mutation binding/save flow, media/attachment injection, RAG retrieval / filter building, prompt/context construction, and repeated metadata / security / exposure-policy resolution. Admin-screen performance is explicitly NOT in scope.

**Deferred to a later hardening pass (NOT v1.2):** Phase 10 re-verification + Nyquist `*-VALIDATION.md` backfill (phases 9/10/11/12/13/13.1); PKG-05/TEST-07 clean-consumer smoke (v1.0.0 Plan 08-05 carryover); `TranscriptionPostProcessor` + custom STT-provider SPIs; per-conversation end-user model switching; admin-screen performance work; dormant-seed activation (SEED-001/002/003/004/006/008 triggers not met).

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
- ✓ Prompt-contract hardening: baseline `agent.entities` / `agent.permissions`, internal vocabulary guardrails, deterministic `unknown_entity` retry contract, output scanner pattern packs, and cross-locale prompt-contract tests — v1.1.0 / Phase 9 (PROMPT-01..06, TEST-08)
- ✓ Tool-layer refinements: richer `describe_entity` via `MetadataTools`, host fetch-plan override SPI (`ToolFetchPlanCustomizer`), ACL-intersected fetch plans (projection, not security), prompt-safe record envelope — v1.1.0 / Phase 9 (TOOL-09..12, SPI-09)
- ✓ AI-specific LLM exposure policy: admin-governed entity-level `EXCLUDE` denylist (`AiExposureRule` + `LlmExposurePolicy`, composition `userVisible AND NOT excluded`), uniformly enforced across schema discovery / tool calls / baseline prompt / RAG; uniform `unknown_entity` opacity; admin Flow UI; `LlmExposureChangedEvent` — v1.1.0 / Phase 10 (EXP-01..10, ENT-05, SEC-05 partial, TEST-09) (activated SEED-007)
- ✓ Mutation-capable built-in tools: opt-in `create_record` / `update_record` / `add_related_record` / `remove_related_record` over `DataManager`, layered fail-closed gating (`AiAgentMutationRole` → exposure → `AccessManager` entity+attribute → `AiMutationIntent` idempotency → `MutationGuard` SPI → `@Transactional` save), PII-safe `MutationErrorTranslator`, end-to-end audit incl. rollback; always-on `BuiltInLinkTools` (deep links) — v1.1.0 / Phase 11 (MUT-01..12, ENT-09, AUD-06, AUD-07, SEC-07, SPI-10, TEST-10..13)
- ✓ Configurable chat surfaces: `FULL_ROUTE` `ChatView` + `HEADER_BUTTON` `ChatDialogView` over one `ChatPanelFragment`, `AiUiSettings` admin toggle, `ChatSurfaceMounter`, `AiChatSessionState` cross-surface conversation continuity, async auto-titled conversations — v1.1.0 / Phase 12 (SURF-01..10, ENT-06, SEC-05 partial, TEST-14) (activated SEED-005; floating-launcher corner placement deferred → SURF-11)
- ✓ Chat task file: `AiTaskFile` transient entity (structurally disjoint from KB ingestion), attach UI, Spring AI `Media` injection, `bulk_save_records` tool (one transaction, batch idempotency), default chat model swap to multimodal `qwen/qwen3.6-35b-a3b` (Apache-2.0, self-hostable) — v1.1.0 / Phase 13 (TASK-01..06, ENT-07, MUT-14, SEC-06 partial, TEST-16)
- ✓ Chat attachments CRM-style right-pane + persistent multi-turn context: jmix-crm right-pane port (card grid + drop-zone + empty state), per-turn-all `Media` injection with LRU token-budget cap + `task_file_budget_exceeded` audit, conversation-scoped 24h TTL, inline `[user] added attachment` notice rows — v1.1.0 / Phase 13.1 (UI-01, RES-01, AUDIT-01, LIFE-01, UX-01, SCHEMA-01, CONTRACT-01, TEST-16-PORT, I18N-01)
- ✓ Intent-driven extraction → prefilled Jmix forms: `IntentExtractor<T>` SPI + `IntentRegistry` + prompt-only `MetaClassDtoSynthesizer`, persisted `AiExtractionDraft` (owner-scoped, TTL, hidden from the LLM), `prepare_form_draft` + server-validated `propose_action_choices` tools, chat-rendered confirm/action rows, controller-side-only navigation (`OpenFormWithDraftHandler`), permission-gated `setValueIfPermitted` prefill, host `CustomerDraftIntentExtractor` reference; LLM never receives `ViewNavigators` or any UI-mutation primitive — v1.1.0 / Phase 14 (EXTRACT-01..10, ENT-08, SPI-12, SEC-06 partial, TEST-15)

### Active

v1.2 — Operator Experience, Voice Input & Runtime Performance (REQ-IDs assigned in `REQUIREMENTS.md`, phases in `ROADMAP.md`):

- [ ] Chat voice input — Soniox STT (trimmed): Soniox default + OpenAI-direct fallback, `MediaRecorder` capture, privacy-safe `STT_TRANSCRIPTION` audit (hash default / raw opt-in), non-blocking error + retry UI, transcript → `MessageInput` for review before send. (Promotes ROADMAP Backlog Phase 999.2; `TranscriptionPostProcessor`/custom-provider SPIs deferred.)
- [ ] Right-sidebar chat surface & observability UX: `SIDEBAR` / right-sidebar chat surface over the existing `ChatPanelFragment`, collapsible per-turn tool-detail panel, and ephemeral streaming-status indicator. (Resolves the pending `add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui` todo; chat-state side panel deferred.)
- [ ] Admin model management: curated common-model dropdown + custom model-name free-entry in admin Parameters/Settings UI; admin-only.
- [ ] Admin config-knob migration: surface operator-relevant prior-phase properties knobs as editable `AiParameters` / admin UI settings.
- [ ] Phase 11 mutation-internals hardening: dedup gate sequencing, batch-load to-one FK refs during binding, cache related-write metadata. (Promotes ROADMAP Backlog Phase 999.1.)
- [ ] AI-runtime performance pass (targeted): chat turn execution, tool calls, mutation binding/save, media/attachment injection, RAG retrieval/filter building, prompt/context construction, repeated metadata/security/exposure-policy resolution.

### Deferred (carried to v1.3+ / later hardening pass)

- [ ] **Phase 10 re-verification + Nyquist backfill** — `/gsd-verify-work 10` to flip the stale `human_needed` status; `/gsd-validate-phase` for phases 9/10/11/12/13/13.1 (no `*-VALIDATION.md`). Deferred from v1.2 per user decision 2026-05-11.
- [ ] **Clean-consumer smoke (PKG-05 / TEST-07)** — Plan 08-05 carryover from v1.0.0. Postgres/pgvector Testcontainers smoke OR a starter stub `VectorStore` boot mode. Deferred from v1.2 per user decision 2026-05-11.
- [ ] **`TranscriptionPostProcessor` SPI + custom STT-provider SPI** — trimmed out of v1.2 STT scope; revisit when a real host need appears.
- [ ] **Per-conversation end-user model switching** — v1.2 model picker is admin-only; per-conversation switching deferred.
- [ ] **Chat-state side panel** — model/conversation/governance/attachment-budget summary in chat is deferred per user decision 2026-05-11; Phase 15 implements the `SIDEBAR` / right-sidebar chat surface but must not add this separate state panel.
- [ ] **Admin-screen performance work** — out of the v1.2 perf pass (which is AI-runtime only); separate effort if needed.
- [ ] **Attribute-path-level exposure rules** (vs. entity-level only) — `attributePath` field on `AiExposureRule`; deferred per user decision 2026-04-27.

### Out of Scope

- Autonomous multi-step agents remain out of v1 scope; future loop support needs separate safety and cost controls.
- Mutation tools remain opt-in future work; v1 ships read-only tools by default.
- Auto-ingesting host entity records into the vector store remains deferred; `DataManager` stays the source of truth.
- URL/web crawling ingestion remains deferred.
- Native non-OpenAI-compatible provider starters remain deferred; hosts can swap `ChatModel` or use OpenRouter-compatible routing.
- Jmix internal APIs remain forbidden.
- Universal-agent positioning remains out of scope; this project is specifically a Jmix AI agent add-on (a metadata-first, Jmix-secured agent — not a generic agent framework).
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

**Why now:** Jmix lacks a first-party AI agent. Customer enterprise Jmix apps increasingly want "ask your data" UX, but rolling it safely is expensive. A reusable metadata-first add-on lets every Jmix app get a governed AI agent with minimal custom code.

## Constraints

- **Tech stack**: Jmix 2.8 + Spring Boot 3 + Vaadin Flow + Java 21 — fixed by host ecosystem (toolchain moved to Java 21 during v1.1; some older docs/AGENTS.md still say 17)
- **Spring AI version**: 1.1.x — pinned via BOM (upgraded per D-10; verify current syntax via Context7/official docs, not training data)
- **Vector store default**: pgvector — reuses Postgres infra familiar to Jmix enterprise deployments
- **Data access**: `DataManager` only — `EntityManager` forbidden by project conventions and breaks Jmix security model. System-internal writes (audit, idempotency, cleanup, exposure-rule reads, task-file resolution) use `UnconstrainedDataManager`; the regular `DataManager` is used for everything user-attributable, including LLM-driven mutations.
- **Entities**: No Lombok on entities; UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`; instantiate via `Metadata.create()` / `DataManager.create()`
- **Security**: Jmix roles + data security is the primary enforcement layer ("AI is just another Jmix client"). As of v1.1.0 there is **also** an admin-governed AI-specific exposure layer (`AiExposureRule` / `LlmExposurePolicy`, entity-level `EXCLUDE` only) that can narrow the LLM-visible surface **below** the current user's Jmix permissions — it never widens, and `AccessManager` remains authoritative for actual data access. Mutations require an explicit `AiAgentMutationRole` marker + host `ai-agent.tools.mutation.enabled=true` + normal Jmix CRUD/attribute policies. The LLM never receives `ViewNavigators` or any UI-mutation primitive.
- **Packaging**: Must be distributable as Maven artifacts; no internal Jmix APIs; starter auto-configuration conventions
- **Safety**: Read-only default; mutation tools default OFF (`@ConditionalOnProperty`, boot test asserts zero mutation callbacks under default config); `delete_record` reserved (absent even when mutations enabled); all tool calls auditable via `AuditWriter.writeToolCall` with no new `AuditKind`
- **Testing**: Live LLM tests must be opt-in and excluded from default CI (cost + flakiness)
- **UI**: Vaadin Flow server-side, Jmix XML view descriptors + Jmix components by default; all labels via `msg://` keys in ALL locale bundles (`messages.properties` + `messages_*.properties`), per `CLAUDE.md`

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
| File upload only for KB ingestion in v1.0 | Covers enterprise doc flows; URL crawling/entity auto-ingest deferred | ✓ Good |
| **v1.1**: AI-specific exposure layer reinstated as admin governance (`AiExposureRule`, entity-level `EXCLUDE` only; `attributePath` deferred) | A concrete "AI must see less than user" need surfaced; `EXCLUDE`-only shape prevents widening; composition stays `userVisible AND NOT excluded` | ✓ Good |
| **v1.1**: Mutation tools ship default-OFF behind `@ConditionalOnProperty` + `AiAgentMutationRole` marker; `delete_record` reserved | Safety; opt-in is per-host; destructive ops need separate confirmation/undo UX | ✓ Good |
| **v1.1**: Two chat surfaces (`FULL_ROUTE` + `HEADER_BUTTON` Jmix `DialogWindow`); floating raw-Vaadin launcher + `SIDEBAR` + compact mode deferred | Jmix `DialogWindow` participates in the normal overlay stack (kills the old P-21 stacking problem); two surfaces cover the demand | ✓ Good |
| **v1.1**: Chat task files are transient, structurally disjoint from KB ingestion; default chat model swapped to multimodal `qwen/qwen3.6-35b-a3b` (Apache-2.0) | File-read-then-act needs a multimodal model; self-host constraint requires open-weights; KB/`VectorStore` must stay untouched (TEST-16) | ✓ Good |
| **v1.1**: Per-turn-all `Media` injection (13.1) with a mandatory LRU token-budget cap, replacing 13's single-turn injection | "AI agent thực thụ" multi-turn follow-up requires the file to stay in context; cap prevents context blowout on multi-file conversations | ✓ Good |
| **v1.1**: Intent extraction never gives the LLM `ViewNavigators`/UI-mutation primitives; `prepare_form_draft` returns a structured payload, controller navigates after `AccessManager` view check; prefill is `setValueIfPermitted` only | Keeps the LLM out of UI control; Jmix detail-view validation + security remain the authority for the eventual Save (TEST-15 scanner enforces) | ✓ Good |
| **v1.1**: Strict-mode extraction is prompt-only (`MetaClassDtoSynthesizer` emits schema text, no runtime DTO bytecode for host metamodels) | Avoids generating runtime classes; works with any host metamodel | ✓ Good |

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
*Last updated: 2026-05-11 — milestone v1.2 (Operator Experience, Voice Input & Runtime Performance) started*
