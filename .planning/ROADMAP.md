# Roadmap: Jmix AI Agent (ai-agent-core)

## Milestones

- ✅ **v1.0.0 MVP** — Phases 1–8 (+ inserted 7.1, 7.2) — shipped 2026-04-26 — [archive](milestones/v1.0.0-ROADMAP.md)
- ✅ **v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces** — Phases 9–14 (+ follow-up 13.1) — shipped 2026-05-11 — [archive](milestones/v1.1.0-ROADMAP.md) · [requirements](milestones/v1.1.0-REQUIREMENTS.md) · [milestone audit](milestones/v1.1.0-MILESTONE-AUDIT.md)
- 🚧 **v1.2 — Operator Experience, Voice Input & Runtime Performance** — Phases 15–19 — in planning (started 2026-05-11; Phases 16+17 merged → new Phase 16 on 2026-05-13; old 18/19/20 renumbered to 17/18/19) — [requirements](REQUIREMENTS.md)

## Phases

<details>
<summary>✅ v1.0.0 MVP (Phases 1–8 + 7.1, 7.2) — SHIPPED 2026-04-26</summary>

Full detail: [milestones/v1.0.0-ROADMAP.md](milestones/v1.0.0-ROADMAP.md) · phase history: [milestones/v1.0.0-phases/](milestones/v1.0.0-phases/)

A reusable Jmix AI agent add-on with secure metadata-first read-only tools (via `AccessManager`/`DataManager`), Spring AI ChatClient orchestration with JDBC chat memory + conversation projection + durable audit, pgvector RAG ingestion/retrieval with role-scoped filters, prompt-injection-safe result formatting, Flow UI (chat, conversations, parameters, knowledge base, tree-lite audit), SPI extension points, packaged as `ai-agent` + `ai-agent-starter` with Spring Boot auto-config + CI + operator docs.

</details>

<details>
<summary>✅ v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces (Phases 9–14 + 13.1) — SHIPPED 2026-05-11</summary>

Full detail: [milestones/v1.1.0-ROADMAP.md](milestones/v1.1.0-ROADMAP.md) · requirements: [milestones/v1.1.0-REQUIREMENTS.md](milestones/v1.1.0-REQUIREMENTS.md) · milestone audit: [milestones/v1.1.0-MILESTONE-AUDIT.md](milestones/v1.1.0-MILESTONE-AUDIT.md)

- [x] **Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening** (7/7 plans) — completed 2026-04-27 — `agent.entities`/`agent.permissions` baseline, `describe_entity` widening, `ToolFetchPlanCustomizer` SPI + `FetchPlanIntersector`, `unknown_entity` retry contract, output-scanner leak guards.
- [x] **Phase 10: AI-Specific LLM Exposure Policy** (10/10 plans) — completed 2026-04-28 — `AiExposureRule` (`EXCLUDE`-only) + `LlmExposurePolicy` boundary, admin Flow UI, uniform `unknown_entity` opacity, RAG cross-cut. (Verification status `human_needed` — see milestone audit; substantive items fixed in code.)
- [x] **Phase 11: Mutation-Capable Built-In Tools** (16/16 plans) — completed 2026-04-29 — `BuiltInMutationTools` (default OFF), layered fail-closed gating chain, `MutationGuard` SPI, `AiMutationIntent` idempotency, PII-safe error translation, end-to-end audit incl. rollback.
- [x] **Phase 12: Configurable Chat Surfaces** (6/6 plans) — completed 2026-05-05 — `FULL_ROUTE` + `HEADER_BUTTON` surfaces over one `ChatPanelFragment`, `AiUiSettings` admin toggle, `AiChatSessionState` continuity, async auto-title.
- [x] **Phase 13: Chat Task File — Attach + LLM Read + Bulk Save** (6/6 plans) — completed 2026-05-06 — `AiTaskFile` transient entity, attach UI, Spring AI `Media` injection, `bulk_save_records` tool, default chat model swap to multimodal `qwen/qwen3.6-35b-a3b` (Apache-2.0).
- [x] **Phase 13.1: Chat Attachments — CRM-Style Right-Pane + Persistent Multi-Turn Context** (7/7 plans) — completed 2026-05-07 — jmix-crm right-pane port, per-turn-all `Media` with LRU budget cap, conversation-scoped 24h TTL, inline attachment notice rows.
- [x] **Phase 14: Intent-Driven Extraction → Form Prefill** (10/10 plans) — completed 2026-05-09, shipped via PR #28, manual UAT accepted 2026-05-11 — `IntentExtractor<T>` SPI, persisted `AiExtractionDraft` (hidden from LLM), `prepare_form_draft` + `propose_action_choices` tools, chat-rendered confirm/action rows, controller-side-only navigation, permission-gated prefill; the LLM never receives `ViewNavigators`.

</details>

### 🚧 v1.2 — Operator Experience, Voice Input & Runtime Performance (Phases 15–19)

Five near-independent feature areas layered on the shipped v1.1 agent harness with effectively zero new runtime dependencies. Hard ordering constraint: **Phase 17 (mutation-internals hardening) must precede Phase 18 (perf pass)** — the perf pass refactors the consolidated `MutationGateChain` + the shared batch-FK load, not the duplicated sequence. Phase 15 touches the chat surface mounter and `ChatPanelFragment`; Phase 16 is admin configuration UI only (model picker + Tier-1/2/3 knob migration co-designed in one `AiParameters`/`AiUiSettings` schema). Voice input (Phase 19) lands last in the milestone: it reuses Phase 15's in-fragment streaming-status-row pattern for its error/retry row. Phases 17/18 (the "invisible" passes) can run in parallel with 15/16.

- [x] **Phase 15: Right-Sidebar Chat Surface & Observability UX** — `SIDEBAR` / right-sidebar chat surface over the existing `ChatPanelFragment`, plus ephemeral streaming-status line and collapsed-by-default per-turn tool-detail disclosure driven by the existing `StreamingEvent` flux + `AiAuditEvent` tree. Resolves the pending collapsible-tool-detail / ephemeral-status todo. The chat-state side panel is deferred. (Plans 01–05 complete 2026-05-12; TEST-19 leak gate + locale-completeness + no-DDL structural tests in place. UAT 9/10 — 15-06 gap-closure plan added 2026-05-12: new-conversation-after-errored-turn fix + Option-A inline anchoring of turn-detail/action-choice/NOTICE + disclosure restyle per the approved mockup.)
- [x] **Phase 16: Admin Settings — Model Picker & Config-Knob Migration** *(merged on 2026-05-13 from former Phase 16 + Phase 17)* — Curated open-weights model `ComboBox` + custom free-entry in the admin Parameters view (admin-only; flows to per-request `ChatOptions`; validated at use time). Co-designed three-tier knob taxonomy in the same `AiParameters`/`AiUiSettings` schema: Tier-1 runtime knobs (RAG top-k / similarity threshold / task-file token budget / TTL / model-picker knobs) become editable; Tier-2 boot toggles shown read-only with a "requires restart" note; Tier-3 secrets shown as "configured: yes/no" only. Publishes an `AiParameters` change event for cache eviction (eviction hook required by Phase 18). Per-tool description/knob overrides à la jmix-ai-backend are OUT of scope — `enabledTools` allowlist stays as the only per-tool admin lever.
 (completed 2026-05-13)
- [ ] **Phase 17: Mutation-Internals Hardening (Phase 11 follow-up)** *(was Phase 18)* — Extract the canonical `MutationGateChain`; batch-load to-one FK refs via constrained `DataManager` `IN(...)`; memoize related-write metadata. Byte-for-byte behavior-identical; Phase 9/10/11 mutation test suites pass unchanged. Promotes Backlog 999.1.
- [ ] **Phase 18: AI-Runtime Performance Pass (targeted)** *(was Phase 19)* — Per-turn memoization of schema/metadata/`AccessManager`/exposure resolution; app-wide memoized denylist + metadata derivations (evicted on `LlmExposureChangedEvent`); RAG `Filter.Expression` built once per retrieval; task-file `Media` cached per `(convId, taskFileId)`. No benchmark harness, no admin-screen perf; each change ships with a checkable proxy; existing test suites pass unchanged.
- [ ] **Phase 19: Chat Voice Input — Soniox STT (+ OpenAI fallback)** *(was Phase 20)* — Browser-recorded audio, transcribed server-side (Soniox async default, OpenAI-direct fallback), transcript lands in `MessageInput` for review before send; disjoint from `ChatService`; privacy-safe `STT_TRANSCRIPTION` audit; reuses Phase 15's in-fragment status-row pattern for its error/retry row. Lands last in the milestone. Promotes Backlog 999.2.

## Phase Details

### Phase 15: Right-Sidebar Chat Surface & Observability UX
**Goal**: An operator can open chat from a right-sidebar `SIDEBAR` surface in addition to the shipped `FULL_ROUTE` and `HEADER_BUTTON` surfaces, and can see what the agent is doing while it works (an ephemeral streaming-status line) and what it did afterward (a collapsed-by-default per-turn tool-detail disclosure) — all without internal tool/entity names ever leaking into the UI, and with no new persisted state.
**Depends on**: `ChatSurfaceMounter`, `AiUiSettings`, `AiChatSessionState`, `ChatPanelFragment`, the existing `StreamingEvent` flux, and the `AiAuditEvent` tree. Independent — can overlap Phases 16/17/18. (Phase 19's STT error/retry row reuses this phase's in-fragment status-row pattern — Phase 15 ships first within the milestone, so the pattern is established.)
**Requirements**: SURF-11, OBS-01, OBS-02, OBS-04, TEST-19
**Success Criteria** (what must be TRUE):
  1. A `SIDEBAR` / right-sidebar chat surface can be enabled independently through `AiUiSettings`, mounts the shared `ChatPanelFragment`, preserves cross-surface `AiChatSessionState` continuity, and does not introduce a second chat backend, second chat memory, or duplicate fragment implementation.
  2. While a turn is streaming, an ephemeral status line renders in a sibling slot (not inside the message bubble), keyed by audit `KIND` ("thinking…", "searching data…", "retrieving documents…"), and clears completely when the turn finalizes — the status text is never concatenated into the final answer and never shows internal `@Tool` / entity names.
  3. Each completed turn shows a collapsed-by-default "what the agent did — N steps, total ms" disclosure listing humanized, label-only steps (KIND-keyed, never internal tool/entity names) with per-step timing and error/rollback indication; the disclosure is hidden entirely for turns with zero tool calls; a turn deep-links to its filtered audit list (`AiAuditEventListView?runId=...`).
  4. The panels are driven by the existing `StreamingEvent` flux + `AiAuditEvent` tree — no new persisted "turn" entity, no parallel state store; per-turn detail held in the panels does not accumulate unbounded in `AiChatSessionState`; new labels use `msg://` keys in all locale bundles.
  5. TEST-19 — a UI-layer leak test (reusing the Phase 9 leak-guard pattern packs) asserts the streaming-status line and the per-turn tool-detail disclosure never emit internal `@Tool` method names or raw entity names.
**Deferred**: Chat-state side panel for model/conversation/governance/attachment-budget facts (`OBS-FUT-01`). Do not implement it in Phase 15.
**Note**: ROADMAP success-criterion 3's `AiAuditEventListView?runId=...` deep-link clause is DESCOPED for Phase 15 per `15-SPEC.md` (the disclosure is label-only, no link). Doc-sync follow-up only.
**Plans**: 6 plans
Plans:
**Wave 1**
- [x] 15-01-PLAN.md — Add AiChatSurface.SIDEBAR + admin UI-settings participation + locale labels (no DDL)
- [x] 15-02-PLAN.md — Additive StreamingEvent.Activity(ActivityKind) variant + best-effort Activity(TOOL)/Activity(RETRIEVAL) emit sites (tool decorator/retriever; no Activity(CHAT) — UI derives it) + renderer arm

**Wave 2** *(blocked on Wave 1 completion)*
- [x] 15-03-PLAN.md — AiAgentSidebarView host view + ChatSurfaceMounter SIDEBAR fixed-position side-panel mount (same ChatPanelFragment impl/ChatService/AiChatSessionState continuity) + navbar toggle + in-panel closer + CSS push-shell (real clamp width) + @CssImport on ChatPanelFragment

**Wave 3** *(blocked on Wave 2 completion)*
- [x] 15-04-PLAN.md — Ephemeral KIND-keyed streaming-status line (neutral->CHAT-on-first-Content) + collapsed per-turn tool-detail Details in a grouped .ai-agent-turn-activity block + capped live state + child-count-aware AiAuditEvent correlation + lazy/memoized AiAuditEvent re-read (real timings on Final) + TurnDetailRenderer mapper

**Wave 4** *(blocked on Wave 3 completion)*
- [x] 15-05-PLAN.md — TEST-19 ObservabilityLeakTest (reuses Phase 9 packs) + locale-completeness/no-new-persisted-state tests + fold the 2026-04-26 todo to done/

**Wave 5 — gap closure (post-UAT)** *(closes the two open 15-UAT gaps)*
- [x] 15-06-PLAN.md — Gap 1: force-clear "new conversation" after an errored turn (conversationId stranded null) + .doOnError conversationId sync; Gap 2: anchor turn-detail/action-choice/NOTICE inline under their turn's <vaadin-message> (re-anchor after setItems + history replay) + restyle the disclosure + action-choice per the approved Option-A mockup
**UI hint**: yes

### Phase 16: Admin Settings — Model Picker & Config-Knob Migration
*(Merged on 2026-05-13 from former Phase 16 "Admin Model Management" + former Phase 17 "Admin Config-Knob Migration" — the model-picker persistence and the Tier-1 knob migration share the same `AiParameters`/`AiUiSettings` schema, so they ship as one phase.)*
**Goal**: An admin can pick the chat model from a curated catalog of self-hostable open-weights models (with the default marked) or type a custom model name as an escape hatch in the admin Parameters view; the choice flows through to per-request `ChatOptions`. In the same admin UI, every prior-phase `ai-agent.*` knob is classified under a documented three-tier taxonomy — Tier-1 runtime knobs become editable `AiParameters`/`AiUiSettings` fields (read fresh each turn, take effect next turn, no restart), Tier-2 boot toggles are shown read-only with a "property only — requires restart" marker, Tier-3 secrets are shown as a "configured: yes/no" indicator only — and any cache around settings evicts on an `AiParameters` change event so an admin edit is visible within one turn.
**Depends on**: Nothing hard. The `AiParameters`/`AiUiSettings` schema is designed once for both the model-picker persistence and the Tier-1 knob migration. (The `ai-agent.stt.*` knobs are out of this pass — they don't exist yet; STT lands later in Phase 19, which owns adding its own `store-transcript` toggle or leaving it a property per CFG-02's "boot toggles stay read-only" rule.) Provides the cache-invalidation hook required by Phase 18.
**Out of scope**: Per-tool description/knob overrides à la jmix-ai-backend (per-tool `description`, `topK`, `similarityThreshold`, `noResultsMessage` map shape). The codebase has only one retriever today and rich `@Tool` descriptions are designed in-source (`feedback_rich_tool_descriptions.md`) — promote to Backlog if/when a second retriever lands.
**Requirements**: MODEL-01, MODEL-02, MODEL-03, CFG-01, CFG-02, CFG-03, SEC-08, TEST-20
**Success Criteria** (what must be TRUE):
  1. In the admin Parameters/Settings view the chat-model field is a `ComboBox` populated from a configurable curated catalog of common self-hostable open-weights model slugs with readable labels (the default marked); selecting an item writes the existing free-text `model` value in the active `AiParameters` profile.
  2. The same control lets an admin enter a custom model name (any string) when the desired model is not in the curated list (`ComboBox.allowCustomValue` or a "Custom…" sentinel revealing a text field); model validity is checked at first use with a clear error surfaced, not at save time, and the chat turn falls back gracefully.
  3. Model selection is admin-only — end users cannot switch model per conversation; the chosen model flows through to per-request `ChatOptions`; all new labels use `msg://` keys in all locale bundles.
  4. TEST-20 — a curated-model allowlist test asserts every model id in the curated dropdown catalog is on a self-hostable open-weights allowlist (comment references `project_self_hostable_models_only.md`).
  5. RAG `top-k`, RAG similarity threshold, task-file token budget, task-file TTL, and any other Tier-1 knobs identified by a reviewed audit table become editable in the admin UI via the existing `AiParametersResolver`-style read-through (prefer the `AiParameters`/`AiUiSettings` value, fall back to the `module.properties` default), are read fresh on each retrieval/turn, and take effect on the next turn without a restart; the strict `default-params.yaml` seed stays strict.
  6. Boot-time / wiring knobs (`@ConditionalOnProperty` toggles such as `ai-agent.tools.mutation.enabled`) are shown in the admin UI read-only with a clear "property only — requires restart" marker; secrets (`*.api-key`) are never editable or displayed — at most a "configured: yes/no" indicator; a documented three-tier taxonomy classifies every audited knob. (When STT ships in Phase 19, its `ai-agent.stt.enabled` / `ai-agent.stt.provider` boot toggles are added under this same Tier-2 read-only treatment.)
  7. New editable settings are persisted as fields on `AiParameters`/`AiUiSettings` with an `agentstore` Liquibase changelog (included in `agentstore-changelog.xml`), bean-validation with sensible bounds, and labels in all locale bundles; an `AiParameters`/`AiUiSettings` change event is published so any cache around settings (see Phase 18) evicts — an admin edit is visible within one turn.
  8. SEC-08 — a test asserts no `*.api-key` (or other secret) property is surfaced as an editable or displayed admin setting, and boot-time `@ConditionalOnProperty` toggles are not presented as runtime-editable.
**Plans**: 9 plans (7 original + 2 gap-closure plans from UAT)
Plans:
**Wave 0**
- [x] 16-01-PLAN.md — Foundation: AiSettingsChangedEvent + KnobMetadata annotation + AuditKind.MODEL_VALIDATION_FAILURE + 7 Wave-0 test scaffolds

**Wave 1** *(parallel — blocked on Wave 0)*
- [x] 16-02-PLAN.md — AiUiSettings schema: 11 nullable Tier-1 columns + Liquibase changelog 120 + KIND varchar(32) widening + bean-validation test
- [x] 16-03-PLAN.md — Curated catalog: ChatModelCatalog + SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST + module.properties seed + TEST-20

**Wave 2** *(blocked on Wave 1)*
- [x] 16-04-PLAN.md — AiUiSettingsResolver + 10 caller injections + read-through fallthrough test + sentinel-survives test
- [x] 16-05-PLAN.md — Entity listeners (AiParametersEntityListener + AiUiSettingsEntityListener) + modelField ComboBox swap + single-publish-site invariant test

**Wave 3** *(blocked on Wave 2)*
- [x] 16-06-PLAN.md — @KnobMetadata annotation pass on 10 records + KnobInventoryScanner (starter) + AiUiSettingsDetailView tier1/bootConfig/secrets tabs + SEC-08 + KnobInventoryClassificationTest
- [x] 16-07-PLAN.md — DefaultChatServiceImpl catch+reissue at executeBlockingTurn + AuditKind.MODEL_VALIDATION_FAILURE audit emission + fallbackModel() accessor + locale notification keys

**Wave 4 — gap closure** *(UAT 2026-05-14; plans 08 + 09 run in parallel — no shared files)*
- [ ] 16-08-PLAN.md — UAT gap 1 (test 4 blocker): promote KnobInventory nested records to top-level @JmixEntity DTO classes under admin/config/dto + descriptor metamodel-resolution regression test (SEC-08 preserved)
- [ ] 16-09-PLAN.md — UAT gap 2 (test 12 major): extend isBadModelException classifier triad for WebClientResponseException + wrap streaming Flux with bad-model catch+reissue (P-22 / T-16-04 / Twin-publisher R2 preserved)
**UI hint**: yes

### Phase 17: Mutation-Internals Hardening (Phase 11 follow-up)
*(Was Phase 18 before the 2026-05-13 Phase 16+17 merge.)*
**Goal**: The fail-closed mutation gate sequence is extracted into one canonical `MutationGateChain` component (with the four mutation `@Tool` methods and `bulk_save_records` as thin adapters), to-one FK references are batch-loaded during attribute binding via a single constrained `DataManager` `IN(...)` per target class, and related-write metadata resolution is memoized — with behavior byte-for-byte identical to v1.1 (same gating outcomes/order, exception classification, audit rows incl. rollback, idempotency semantics, `MutationGuard` SPI contract) so the Phase 9/10/11 mutation test suites pass unchanged.
**Depends on**: Nothing hard. **HARD CONSTRAINT: must precede Phase 18** so the perf pass refactors the consolidated chain + the shared batch-FK load, not the duplicated sequence. Promotes Backlog 999.1.
**Requirements**: MUT-15, MUT-16, MUT-17, MUT-18
**Success Criteria** (what must be TRUE, as observable proxies):
  1. The fail-closed sequence (`AiAgentMutationRole` → exposure → `AccessManager` entity+attribute → `AiMutationIntent` idempotency → `MutationGuard` SPI → `@Transactional` save) lives in one `MutationGateChain` `@Component`; `create_record` / `update_record` / `add_related_record` / `remove_related_record` and `bulk_save_records` are thin adapters over it; a source-level invariant test enforces the gate order and asserts every gate throws before the transactional save (the chain carries no `@Transactional`; only `MutationSaveExecutor.save` does).
  2. To-one FK references are batch-loaded during mutation attribute binding — one constrained `DataManager.load(...).ids(...)` per target class (never `UnconstrainedDataManager`, never raw JPQL), so row-level security still applies — and a SELECT-count / "1 query not N" proxy assertion confirms it replaced per-reference loads.
  3. Related-write metadata resolution (`(parentMetaClass, relationshipName)` → supported-relationship descriptor) is memoized (immutable Jmix metamodel, no eviction needed), confirmed by a call-count assertion.
  4. The Phase 9/10/11 mutation test suites (`MutationToolInvariantsTest`, gating-order, audit-row, error-translator, host-guard-veto, row-level mutation-security tests) and the default-config zero-mutation-callback boot test all pass unchanged — no test edits.
**Plans**: 5 plans
Plans:
**Wave 0**
- [x] 17-01-PLAN.md — Wave-0 structural test seams: extend MutationToolInvariantsTest (gate-order/reflection + MUT-16 forbidden-token scan), new RelatedWriteMetadataMemoTest (MUT-17 walk-once), new MutationFkBatchLoadQueryCountTest + agentstore FK fixture (MUT-16 SELECT-count, Open Q1 option a) — DONE 2026-05-31; four seams RED naming Plans 02/03/04, zero Phase 9/10/11 parity regression

**Wave 1** *(parallel — blocked on Wave 0; disjoint files)*
- [x] 17-02-PLAN.md — MUT-17: memoize RelatedWriteMetadataResolver via ConcurrentHashMap + record Key + Result holder + package-private computeSupported seam (no eviction)
- [x] 17-03-PLAN.md — MUT-16: two-pass FK batch-load in MutationAttributeBinder (prefetchReferences + coerceAttributes(prefetched) overload, one constrained .ids() per target class; single-call dedup for create/update; byte-identical error parity)

**Wave 2** *(blocked on Wave 1; highest parity risk, isolated)*
- [ ] 17-04-PLAN.md — MUT-15: extract canonical MutationGateChain @Component (sealed MutationRequest + ordered named gates, no @Transactional) + reduce the five @Tool methods to thin adapters; preserve bulk-only AccessDeniedException arm

**Wave 3 — parity gate** *(blocked on Waves 1+2)*
- [ ] 17-05-PLAN.md — [BLOCKING] MUT-18: full Phase 9/10/11 mutation suite + AgentToolCallbacksDefaultConfigTest pass with zero test-body edits + git-diff audit of the test tree

### Phase 18: AI-Runtime Performance Pass (targeted)
*(Was Phase 19 before the 2026-05-13 Phase 16+17 merge.)*
**Goal**: Known/suspected per-turn hotspots in chat turn execution, tool calls, mutation binding/save, media/attachment injection, RAG retrieval/filter building, and prompt/context construction are eliminated through targeted memoization — per-turn for anything user/role/exposure-sensitive, app-wide (evicted on `LlmExposureChangedEvent` / the Phase 16 `AiParameters` change event) for pure-metadata and exposure-derived caches — with no benchmark harness, no admin-screen perf work, every optimization shipping a checkable proxy, and all existing security/exposure/audit/tool/RAG test suites passing unchanged.
**Depends on**: Phase 17 (mutation-binding/FK-batch work lands in the shared `MutationGateChain`); Phase 16 (the `AiParameters` change event is the eviction hook for the settings cache). Touches only already-shipped components.
**Requirements**: PERF-01, PERF-02, PERF-03, PERF-04, PERF-05
**Success Criteria** (what must be TRUE, as observable proxies):
  1. Within a single `RunContext` (one chat turn), `getReadableSchema()` / readable-entity metadata / `AccessManager` decisions / `LlmExposurePolicy` resolution are computed once and shared across all tool calls in the turn (nothing user/role/exposure-sensitive reused across turns or users), confirmed by a call-count / SELECT-count proxy.
  2. Pure-metadata derivations (entity name → `MetaClass`) and the exposure denylist (`getDenylistedEntityNames()`) are memoized longer-lived, with the denylist and any other exposure-derived cache evicted on `LlmExposureChangedEvent`; `AccessManager` remains authoritative; a source-level/test invariant confirms every new cache wires to its invalidation event.
  3. The RAG retrieval `Filter.Expression` (role/exposure scoping) is built once per retrieval rather than rebuilt repeatedly; the `(source_entity IS NULL) OR (NOT IN <denied>)` / role clauses are preserved verbatim (no "redundant clause" removal); the existing `RetrievalFilterBuilder` denylist test passes unchanged.
  4. Task-file `Media` is encoded/resolved once per `(conversationId, taskFileId)` per turn (cache evicted on attachment add/delete/TTL) rather than re-encoded per injection; prompt/context is not re-serialized within a turn; FK batch-loading (shared with MUT-16) is in effect — each confirmed by a checkable proxy.
  5. No benchmark harness and no admin-screen perf work are introduced; each optimization ships with a checkable proxy (SELECT-count assertion via the test-scoped `datasource-proxy`, "1 query not N", or call-count assertion); the existing security / exposure / audit / tool / RAG test suites pass unchanged; an admin edit (via the Phase 16 change event) is visible within one turn.
**Plans**: TBD

### Phase 19: Chat Voice Input — Soniox STT (+ OpenAI fallback)
*(Was Phase 20 before the 2026-05-13 Phase 16+17 merge.)*
**Goal**: With STT enabled and a provider key configured, an operator can dictate chat input — browser-recorded audio is transcribed server-side and lands in `MessageInput` for review/edit before sending — through a pathway that is structurally disjoint from `ChatService`/`ChatClient` and privacy-safe in audit by default.
**Depends on**: `ChatPanelFragment.messageInputSlot` (stable since v1.0/Phase 12). Independent of Phases 15/16/17/18. Lands last in the milestone — its error/retry row reuses Phase 15's in-fragment streaming-status-row pattern (established earlier in the milestone). Independent of the Phase 16 config-knob pass: `ai-agent.stt.*` are mostly Tier-2 boot toggles / Tier-3 secrets, and this phase owns adding its own `store-transcript` toggle (or leaving it a property per CFG-02).
**Requirements**: STT-01, STT-02, STT-03, STT-04, STT-05, STT-06, TEST-18
**Success Criteria** (what must be TRUE):
  1. With `ai-agent.stt.enabled=true` and a provider API key configured, a mic button is present in the chat input area in both the `FULL_ROUTE` and `HEADER_BUTTON` surfaces; the user taps it, records up to ~60s of browser audio (`MediaRecorder`, `audio/webm;codecs=opus` / `audio/mp4` Safari fallback, no transcoding) with a visible countdown + auto-stop, sees distinct "recording" then "transcribing…" states, and the transcribed text appears in `MessageInput` for review/edit — it is never auto-sent and the transcription path never calls `ChatService.ask`.
  2. With STT disabled (default config) the mic button is absent (not greyed) and no `TranscriptionService` bean exists — a default-config boot test asserts zero STT beans and no mic button.
  3. The default transcription path is Soniox async STT via a custom Spring `RestClient` (`POST /v1/files` → `POST /v1/transcriptions model=stt-async-v4 language_hints:["vi","en"]` → poll `GET /v1/transcriptions/{id}` → `GET .../transcript` → `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}` in a `finally` on every path including errors); selecting `ai-agent.stt.provider=openai` routes to a fresh `OpenAiAudioApi`/`OpenAiAudioTranscriptionModel` against `https://api.openai.com/v1` with an independent key (never the OpenRouter chat base-url); the Soniox key, OpenAI-STT key, and OpenRouter chat key are three independent properties.
  4. STT failures (provider 4xx, network error, recording too long, no speech) surface a non-blocking inline error message + retry button in the input area (reusing Phase 15's in-fragment status-row pattern) while the chat flow stays usable; transcription runs on a bounded executor and the result is pushed back via `ui.access(...)`, dropped silently (after running the Soniox `DELETE`s) if the UI/conversation detached mid-transcription.
  5. Each transcription writes an `STT_TRANSCRIPTION` audit row via `AuditWriter.writeToolCall(eventName="stt_transcription", ...)` (no new `AuditKind`) recording duration/language/model/provider/outcome plus a SHA-256 hash of the transcript by default; `ai-agent.stt.audit.store-transcript=true` stores the raw transcript instead; the HTTP clients never log response bodies. TEST-18 asserts the audit row in both hash-default and `store-transcript=true` modes, a source-scan asserts the `com.vn.agent.stt` package has zero reference to `ChatService`, and the default-config boot test asserts no STT beans / no mic button.
**Plans**: TBD
**UI hint**: yes

## Progress

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1–8 (+ 7.1, 7.2) | v1.0.0 | — | Shipped | 2026-04-26 |
| 9. Tool-Layer Foundations & Prompt-Contract Hardening | v1.1.0 | 7/7 | Shipped | 2026-04-27 |
| 10. AI-Specific LLM Exposure Policy | v1.1.0 | 10/10 | Shipped (`10-VERIFICATION.md` still `human_needed` — see audit) | 2026-04-28 |
| 11. Mutation-Capable Built-In Tools | v1.1.0 | 16/16 | Shipped | 2026-04-29 |
| 12. Configurable Chat Surfaces | v1.1.0 | 6/6 | Shipped | 2026-05-05 |
| 13. Chat Task File — Attach + LLM Read + Bulk Save | v1.1.0 | 6/6 | Shipped | 2026-05-06 |
| 13.1. Chat Attachments — CRM-Style Right-Pane + Persistent Multi-Turn Context | v1.1.0 | 7/7 | Shipped | 2026-05-07 |
| 14. Intent-Driven Extraction → Form Prefill | v1.1.0 | 10/10 | Shipped (PR #28; UAT passed 2026-05-11) | 2026-05-11 |
| 15. Right-Sidebar Chat Surface & Observability UX | v1.2 | 6/6 | Complete   | 2026-05-12 |
| 16. Admin Settings — Model Picker & Config-Knob Migration *(merged from old 16+17 on 2026-05-13)* | v1.2 | 7/7 | Complete   | 2026-05-13 |
| 17. Mutation-Internals Hardening (Phase 11 follow-up) *(was 18)* | v1.2 | 3/5 | In Progress|  |
| 18. AI-Runtime Performance Pass (targeted) *(was 19)* | v1.2 | 0/? | Not started | - |
| 19. Chat Voice Input — Soniox STT (+ OpenAI fallback) *(was 20)* | v1.2 | 0/? | Not started | - |

## Notes

- Phase numbering is monotonic across milestones: v1.0.0 = Phases 1–8 (+ inserted 7.1, 7.2); v1.1.0 = Phases 9–14 (+ follow-up 13.1); v1.2 = Phases 15–19 (former Phases 16 "Admin Model Management" and 17 "Admin Config-Knob Migration" merged into the new Phase 16 on 2026-05-13; former Phases 18/19/20 renumbered to 17/18/19).
- v1.2 hard ordering constraint: **Phase 17 before Phase 18**. Soft groupings: Phase 15 owns the chat-surface / `ChatPanelFragment` work; Phase 16 is the unified admin Parameters/Settings UI (model picker + Tier-1 knob migration co-designed in one `AiParameters`/`AiUiSettings` schema); 17/18 (the invisible passes) can run in parallel with 15/16. Voice input (Phase 19) lands last — it reuses Phase 15's in-fragment status-row pattern for its error/retry row.
- v1.1.0 milestone audit: PASS on integration (8/8 cross-phase wiring) + E2E (5/5 flows); status `tech_debt` for bookkeeping (Phase 10 verification doc stale at `human_needed`; phases 9/10/11/12/13/13.1 lack `*-VALIDATION.md`). See [milestones/v1.1.0-MILESTONE-AUDIT.md](milestones/v1.1.0-MILESTONE-AUDIT.md).
- Out of v1.2 scope (carried, NOT in any v1.2 phase): Phase 10 re-verification + Nyquist `*-VALIDATION.md` backfill (phases 9/10/11/12/13/13.1); PKG-05/TEST-07 clean-consumer smoke (v1.0.0 Plan 08-05 carryover); `TranscriptionPostProcessor` SPI + custom STT-provider SPI; per-conversation end-user model switching; admin-screen performance work; attribute-path-level exposure rules; activation of dormant seeds SEED-001/002/003/004/006/008; per-tool description/knob overrides à la jmix-ai-backend (promote to Backlog if/when a second retriever lands).

## Backlog

_(Phase 999.1 (mutation-internals hardening) and Phase 999.2 (Chat Voice Input — Soniox STT) were promoted into v1.2 as Phases 18 and 20 respectively on 2026-05-11, then renumbered to Phases 17 and 19 on 2026-05-13 after the Phase 16+17 merge. See "v1.2 — Operator Experience, Voice Input & Runtime Performance" above.)_

### Phase 999.1: Admin-rotated provider credentials (API key + base URL) (BACKLOG)

**Goal:** [Captured for future planning] Give admins an in-app UI to rotate AI provider credentials (API key + base URL) without redeploying. Currently keys/URLs live in `application.yml` + env vars only.

**Requirements:** TBD

**Plans:** 0 plans

Plans:
- [ ] TBD (promote with /gsd-review-backlog when ready)

**Context (captured 2026-05-14 from Phase 16 UAT conversation):**
- Pain: provider key/url rotation requires redeploy
- Multi-provider: OpenRouter Claude needs key + url; Qwen self-hosted needs url only — UI must handle both shapes
- Hard constraint: must NOT violate Phase 16 SEC-08 invariants. The `SecretRedactionInvariantsTest.noSecretBoundEditable` and `noConditionalOnPropertyToggleBoundEditable` source-scans WILL fail the build if an editable `property=` binding to `*.api-key / *.password / *.secret / *.token` is added to any view. Plan 16-06's `SecretIndicatorRow` deliberately has no `value` field. Discuss-phase MUST resolve how a new credentials view side-steps these scans without weakening them
- Open design questions to resolve in discuss-phase:
  1. New entity `AiProviderCredential(providerId, apiKey, baseUrl, active)` vs extend `AiUiSettings`
  2. Encryption-at-rest mechanism: Jmix `EncryptedFieldType` / Jasypt / DB-level (pgcrypto)
  3. Write-only "Replace key" UX with masked `••••last4` display after save; never readback value to DOM
  4. New audit kind `PROVIDER_CREDENTIAL_ROTATED` on every write (no value logged)
  5. Runtime `ChatClient` rebuild via new `AiSettingsChangedEvent.Kind.PROVIDER_CREDENTIAL` (Plan 10-06 R2 single-publish-site invariant still applies — use the existing entity-listener twin pattern)
  6. Multi-provider UI shape: single grid with rows per-provider vs tabbed sub-views
  7. Attribute-level policy hiding `apiKey` readback even from admin — `@EntityAttributePolicy(operations={MODIFY}, attributes="apiKey")` denies READ
- Estimated scope: 4–6 plan phase
