---
gsd_state_version: 1.0
milestone: v1.2
milestone_name: Operator Experience, Voice Input & Runtime Performance
status: executing
stopped_at: Completed 15-04-PLAN.md
last_updated: "2026-05-12T00:30:00.000Z"
last_activity: 2026-05-12
progress:
  total_phases: 6
  completed_phases: 0
  total_plans: 5
  completed_plans: 4
  percent: 80
---

# Project State

**Last updated:** 2026-05-11 (revised — Soniox STT moved to Phase 20)

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-05-11 — after v1.1.0)

**Core value:** Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.

**Current focus:** Phase 15 — right-sidebar-chat-surface-observability-ux

## Current Position

Phase: 15 (right-sidebar-chat-surface-observability-ux) — EXECUTING
Plan: 5 of 5
Status: 15-04 complete; 15-05 (TEST-19 leak-regex test) next
Last activity: 2026-05-12

## Phase Status

| Phase | Status | Plans Complete | Started | Completed |
|-------|--------|----------------|---------|-----------|
| 9. Tool-Layer Foundations & Prompt-Contract Hardening | Complete | 7/7 | 2026-04-27 | 2026-04-27 |
| 10. AI-Specific LLM Exposure Policy | Shipped | 10/10 | 2026-04-27 | 2026-04-28 |
| 11. Mutation-Capable Built-In Tools | Shipped | 16/16 | 2026-04-28 | 2026-04-29 |
| 12. Configurable Chat Surfaces | Shipped | 6/6 | 2026-05-02 | 2026-05-05 |
| 13. Chat Task File — Attach + LLM Read + Bulk Save | Complete | 6/6 | 2026-05-05 | 2026-05-06 |
| 13.1. Chat Attachments — CRM-Style Right-Pane + Persistent Multi-Turn Context | Shipped | 7/7 | 2026-05-07 | 2026-05-07 |
| 14. Intent-Driven Extraction → Form Prefill | Merged — PR #28; manual UAT passed (14/14) 2026-05-11 | 10/10 | 2026-05-07 | 2026-05-11 |
| 15. Right-Sidebar Chat Surface & Observability UX | Executing | 4/5 | 2026-05-11 | - |
| 16. Admin Model Management | Not started | 0/? | - | - |
| 17. Admin Config-Knob Migration | Not started | 0/? | - | - |
| 18. Mutation-Internals Hardening (Phase 11 follow-up) | Not started | 0/? | - | - |
| 19. AI-Runtime Performance Pass (targeted) | Not started | 0/? | - | - |
| 20. Chat Voice Input — Soniox STT (+ OpenAI fallback) | Not started | 0/? | - | - |

## Hard Build-Order

**v1.2 (Phases 15–20):**

- **HARD CONSTRAINT: Phase 18 (mutation-internals hardening) must precede Phase 19 (perf pass)** — the perf pass refactors the consolidated `MutationGateChain` + the shared batch-FK load, not the duplicated sequence.
- Soft groupings: Phase 15 owns the chat-surface / `ChatPanelFragment` work; Phase 16 is admin model configuration UI. Phase 17 best scoped after Phase 16 (it migrates the model-picker knob — design the `AiParameters`/`AiUiSettings` schema once). Phases 18/19 (the invisible passes) can run in parallel with 15/16/17. Voice input (Phase 20) lands last in the milestone — its error/retry row reuses Phase 15's in-fragment status-row pattern (established earlier).
- STT pathway is structurally disjoint from `ChatService`/`ChatClient` (Phase 20); transcript only fills `MessageInput`, never auto-sent.
- STT audit reuses `AuditWriter.writeToolCall(eventName="stt_transcription")` — no new `AuditKind` (already reserved per AUD-06).
- OpenAI-STT fallback uses a fresh `OpenAiAudioApi` against `https://api.openai.com/v1` with an independent key — never the OpenRouter chat base-url; Soniox key / OpenAI-STT key / OpenRouter chat key are three independent properties (Phase 20).
- `ai-agent.stt.*` knobs are out of the Phase 17 config-knob pass (STT does not exist yet at that point); they are mostly Tier-2 boot toggles / Tier-3 secrets, and Phase 20 owns adding its own `store-transcript` toggle (or leaving it a property per CFG-02).
- `@Transactional` only on `MutationSaveExecutor.save`, never on the `MutationGateChain` itself; every gate throws before the save crosses the transaction boundary (Phase 18).
- Batch FK loads via constrained `DataManager` `IN(...)` only — never `UnconstrainedDataManager`, never raw JPQL (Phase 18/19 — row-level security must still apply).
- Every perf cache wires to an invalidation hook: `LlmExposureChangedEvent` for exposure-derived caches, the Phase 17 `AiParameters` change event for settings caches; user/role/exposure-sensitive data is per-`RunContext` (one turn) only (Phase 19).
- Secrets (`*.api-key`) never editable or displayed in the admin UI — indicator-only; boot-time `@ConditionalOnProperty` toggles shown read-only with a "requires restart" note (Phase 17).
- Curated model dropdown contains only self-hostable open-weights models (`project_self_hostable_models_only.md`); custom free-entry is the escape hatch (Phase 16).
- No benchmark harness, no admin-screen perf work in v1.2 (Phase 19).

**v1.1.0 (Phases 9–14 — shipped, retained for reference):**

- Hard chain: Phase 9 → Phase 10 → Phase 11.
- Soft chain: Phase 12 → Phase 13 → Phase 14 (each independent of each other; 12/13 require Phase 9; 14 requires Phase 9 AND Phase 10).
- Mutation tools default OFF on ship (Phase 11 `@ConditionalOnProperty`).
- Exposure policy is `EXCLUDE`-only at the rule shape (Phase 10).
- LLM never receives `ViewNavigators` (Phase 14).
- Audit reuses `AuditWriter.writeToolCall`; no new `AuditKind`.

## Archived Milestone

**MVP v1.0.0** — shipped 2026-04-26. Archive: `.planning/milestones/v1.0.0-ROADMAP.md`; requirements archive: `.planning/milestones/v1.0.0-REQUIREMENTS.md`; phase artifacts: `.planning/milestones/v1.0.0-phases/`.

See `.planning/MILESTONES.md` for the v1.0.0 close summary.

## Milestone v1.2 Scope

Detailed REQ-IDs in `.planning/REQUIREMENTS.md`. Roadmap in `.planning/ROADMAP.md`. 29 v1.2 requirements, all mapped, no orphans (Phase 15: 5 · 16: 4 · 17: 4 · 18: 4 · 19: 5 · 20: 7).

- **Phase 15 — Right-Sidebar Chat Surface & Observability UX:** SURF-11, OBS-01, OBS-02, OBS-04, TEST-19. `SIDEBAR` / right-sidebar chat surface over the existing `ChatPanelFragment`, enabled through `AiUiSettings` and preserving `AiChatSessionState` continuity; ephemeral streaming-status badge (KIND-keyed sibling slot, clears on completion); collapsed-by-default per-turn tool-detail disclosure (humanized label-only steps + timing, hidden when empty); deep-link from a turn into `AiAuditEventListView?runId=`; small in-memory `TurnDetail`/`ToolCallDetail` POJOs; no new entity. Driven by the existing `StreamingEvent` flux + `AiAuditEvent` tree. Establishes the in-fragment status-row pattern that Phase 20's STT error/retry row reuses. Resolves the pending `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui` todo. Chat-state side panel is deferred.
- **Phase 16 — Admin Model Management:** MODEL-01..03, TEST-20. Curated open-weights `ComboBox` (readable labels, default marked) + "custom…" free-entry in `ParametersDetailView`/`AiConfigurationView`; admin-only; chosen model flows to per-request `ChatOptions`; validate at use time; curated-list open-weights allowlist test. Smallest / lowest-risk; informs the Phase 17 audit.
- **Phase 17 — Admin Config-Knob Migration:** CFG-01..03, SEC-08. Reviewed three-tier knob audit table; RAG top-k + similarity threshold + task-file token budget + TTL + migrated model-picker knob editable via the existing `AiParametersResolver` read-through; Tier-2 boot toggles read-only with "requires restart"; Tier-3 secrets "configured: yes/no" only; new fields + `agentstore` Liquibase changelog + locale messages; `AiParameters`/`AiUiSettings` change-event publisher (single publish site) so perf caches evict. Best scoped after Phase 16. (`ai-agent.stt.*` knobs are NOT migrated here — STT lands later in Phase 20, which owns its own `store-transcript` toggle or leaves it a property per CFG-02.)
- **Phase 18 — Mutation-Internals Hardening (Phase 11 follow-up):** MUT-15..18. Extract `MutationGateChain` `@Component` (one canonical fail-closed sequence for create/update/add-related/remove-related/bulk; no `@Transactional` on the chain); `BuiltInMutationTools` `@Tool` methods become thin adapters; batch-load to-one FK refs via constrained `DataManager` `IN(...)` in `MutationAttributeBinder`; memoize related-write metadata (no eviction); extended `MutationToolInvariantsTest` for call order + absence of `@Transactional` on the chain. Byte-for-byte behavior-identical; Phase 9/10/11 mutation suites pass unchanged. **Must precede Phase 19.** Promotes Backlog 999.1.
- **Phase 19 — AI-Runtime Performance Pass (targeted):** PERF-01..05. Per-turn memoization of `getReadableSchema()`/metadata/`AccessManager`/exposure resolution (compute once per turn, share across tool calls); app-wide memoized `getDenylistedEntityNames()` (evicted on `LlmExposureChangedEvent`) + pure-metadata derivations (`name` → `MetaClass`); RAG `Filter.Expression` built once per retrieval; task-file `Media` cached per `(convId, taskFileId)` (evicted on attach/delete/TTL); prompt/context not re-serialized within a turn; each change ships a checkable proxy (SELECT-count via `datasource-proxy`, "1 query not N", call-count). Depends on Phase 18 (`MutationGateChain`) + Phase 17 (`AiParameters` change event). No benchmark harness, no admin-screen perf; existing test suites pass unchanged.
- **Phase 20 — Chat Voice Input — Soniox STT (+ OpenAI fallback):** STT-01..06, TEST-18. `com.vn.agent.stt.*` package (`TranscriptionService` + `SonioxTranscriptionService` (RestClient) + `SpringAiTranscriptionService` (fresh `OpenAiAudioApi`) + `AiAgentSttProperties` + selector `@Bean`), `@JsModule` mic recorder + `UploadHandler` audio receiver in `ChatPanelFragment.messageInputSlot`, `STT_TRANSCRIPTION` audit (hash default / raw opt-in, no new `AuditKind`), bounded `aiAgentSttExecutor`, non-blocking error+retry UI reusing Phase 15's in-fragment status-row pattern, ~60s cap. Lands last in the milestone. Promotes Backlog 999.2.

**Out of v1.2 scope (carried — NOT in any v1.2 phase):** Phase 10 re-verification + Nyquist `*-VALIDATION.md` backfill (phases 9/10/11/12/13/13.1); PKG-05/TEST-07 clean-consumer smoke (v1.0.0 Plan 08-05 carryover); `TranscriptionPostProcessor` SPI + custom STT-provider SPI; chat-state side panel; per-conversation end-user model switching; admin-screen performance work; attribute-path-level exposure rules; activation of dormant seeds SEED-001/002/003/004/006/008.

## Milestone v1.1.0 Scope

Detailed REQ-IDs in `.planning/milestones/v1.1.0-REQUIREMENTS.md`. Roadmap archive in `.planning/milestones/v1.1.0-ROADMAP.md`.

- Prompt-contract hardening — Phase 9.
- Tool-layer refinements — Phase 9.
- AI-specific LLM exposure policy (SEED-007 activated) — Phase 10.
- Mutation-capable built-in tools — Phase 11.
- Configurable chat surfaces (SEED-005 activated, refined) — Phase 12.
- Chat task input (STT + task-scoped file) — Phase 13.
- Intent-driven extraction → prefilled Jmix forms — Phase 14.

**Out of scope for v1.1:** collapsible tool-detail panel + ephemeral streaming-status indicator (deferred → now Phase 15 of v1.2); clean-consumer smoke / PKG-05 / TEST-07 (Plan 08-05 carryover, deferred).

## Deferred Items

Items acknowledged and deferred at v1.1.0 milestone close on 2026-05-11. Disposition updated 2026-05-11 after v1.2 roadmap creation (then revised same day — Soniox STT moved to Phase 20) — Phase 999.1 and 999.2 PROMOTED into v1.2 (Phases 18 and 20); the collapsible-tool-detail todo promoted into v1.2 Phase 15.

| Category | Item | Status | Disposition |
|----------|------|--------|-------------|
| quick_task | 260427-9ci-add-a-read-only-jmix-admin-ui-view-to-in | complete (commit 6a39184) | Done — `BaselineContextView` admin diagnostic view; scanner mis-flags it as "missing" because the file lives in a subdir. No action needed. |
| todo | 2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui | **promoted to v1.2 Phase 15** | Now in scope as Phase 15 (Right-Sidebar Chat Surface & Observability UX) — SURF-11, OBS-01, OBS-02, OBS-04. Move the todo to `done/` when Phase 15 ships. |
| scope_trim | Chat-state side panel | deferred | Removed from v1.2 Phase 15 per user decision 2026-05-11. Future `OBS-FUT-01`; do not implement in Phase 15. |
| seed | SEED-001 reviewed-learning-loop-for-agent-failures | dormant | Still dormant — needs production-incident corpus. NOT activated in v1.2. |
| seed | SEED-002 pre-deploy-answer-quality-regression-gate | dormant | Still dormant — activate when v1.1 prompt rules produce signal. NOT activated in v1.2. |
| seed | SEED-003 outputscanner-spi | dormant | Still dormant — config-driven regex scanner sufficient. NOT activated in v1.2. |
| seed | SEED-004 replay-and-diff-runner-for-chat-cases | dormant | Still dormant — pairs with SEED-002. NOT activated in v1.2. |
| seed | SEED-006 strict-file-backed-knowledge-path | dormant | Still dormant — needs retrieval-drift trigger. NOT activated in v1.2. |
| seed | SEED-008 jpql-analytics-tool-with-attribute-level-acl | dormant | Still dormant — future tool surface. NOT activated in v1.2. |
| uat_gap | 09-UAT.md / 13-HUMAN-UAT.md / 13.1-UAT-FIX-01-SUMMARY.md / 14-HUMAN-UAT.md / 14-UAT-CHECKLIST.md | resolved/passed (0 open scenarios) | No open scenarios — completed/passed UAT artifacts; scanner counts the files. No action. |
| verification_gap | 10-VERIFICATION.md | human_needed | **Deferred from v1.2** per the 2026-05-11 decision — NOT folded into any v1.2 phase. Phase 10 goal achieved (4/4 ROADMAP criteria, 12/12 REQ IDs); substantive REVIEW items (BLOCKER-01/02, WARNING-08) fixed in code. Optional `/gsd-verify-work 10` in a later hardening pass. |
| nyquist | 09/10/11/12/13/13.1 missing *-VALIDATION.md | n/a | **Deferred from v1.2** per the 2026-05-11 decision — backfill with `/gsd-validate-phase <N>` in a later hardening pass; does not block close. |
| carryover | PKG-05 / TEST-07 clean-consumer smoke | deferred | **Deferred from v1.2** per the 2026-05-11 decision — v1.0.0 Plan 08-05 carryover; needs Testcontainers pgvector OR a starter stub `VectorStore` boot mode. |
| trimmed | TranscriptionPostProcessor SPI + custom STT-provider SPI | deferred | Trimmed out of v1.2 STT scope (Phase 20) — revisit when a real host need appears. |

Resolved during v1.1.0 close (NOT deferred): 9 capture-note todos moved to `.planning/todos/done/` (work shipped in Phases 9/11/12/14 — see `done/_INDEX.md`); SEED-005 → `implemented` (Phase 12), SEED-007 → `implemented` (Phase 10). Phase 999.1 (mutation-internals hardening) → v1.2 Phase 18; Phase 999.2 (Chat Voice Input — Soniox STT) → v1.2 Phase 20 (Backlog entries removed from ROADMAP.md on 2026-05-11).

## Accumulated Context

### Pending Todos

| Todo | Disposition |
|------|-------------|
| `2026-04-26-add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui.md` | v1.2 Phase 15 (OBS-01..04, TEST-19). Move to `done/` when Phase 15 ships. |

(The other 8 capture-note todos were resolved at v1.1.0 close — see `done/_INDEX.md`.)

### Seeds Reviewed

| Seed | Disposition |
|------|-------------|
| SEED-001 — Reviewed learning loop | Dormant — no production-incident trigger yet. NOT activated in v1.2. |
| SEED-002 — Pre-deploy answer-quality regression gate | Dormant — defer until prompt rules produce signal. NOT activated in v1.2. |
| SEED-003 — OutputScanner SPI | Dormant — config-driven scanner sufficient. NOT activated in v1.2. |
| SEED-004 — Replay/diff runner | Dormant — pairs with SEED-002. NOT activated in v1.2. |
| SEED-005 — Configurable chat surfaces | **IMPLEMENTED** — Phase 12 (v1.1.0). |
| SEED-006 — Strict file-backed knowledge path | Dormant — no retrieval-drift trigger. NOT activated in v1.2. |
| SEED-007 — AI-specific LLM exposure policy | **IMPLEMENTED** — Phase 10 (v1.1.0). |
| SEED-008 — JPQL analytics tool with attribute-level ACL | Dormant — future tool surface. NOT activated in v1.2. |

### Roadmap Evolution Notes (carried)

- Phase 7.2 was inserted after Phase 7.1 in v1.0.0: Redesign audit schema as tree-lite (PARENT_ID).
- 2026-04-26 (v1.1 scope decision): prompt-contract hardening bundle promoted into v1.1 first phase. Activates SEED-005 (refined to three configurable chat surfaces) and SEED-007 (AI exposure policy). Adds new mutation-tools scope on top of pending todos.
- 2026-04-26 (v1.1 roadmap): six phases (9–14) defined; numbering continues from v1.0.0 close (Phase 8 + 7.1 + 7.2). All active REQ-IDs mapped; no orphans.
- 2026-05-11 (v1.2 roadmap): six phases (15–20) defined; numbering continues monotonically from v1.1.0 close (Phase 14 + follow-up 13.1). Backlog Phase 999.1 → Phase 18; Backlog Phase 999.2 → Phase 20 (Soniox STT lands last in the milestone, per the same-day revision); the collapsible-tool-detail todo + `SIDEBAR` / right-sidebar chat surface → Phase 15; chat-state side panel deferred. Hard ordering constraint: Phase 18 before Phase 19. All 29 v1.2 REQ-IDs mapped (Phase 15: 5 · 16: 4 · 17: 4 · 18: 4 · 19: 5 · 20: 7); no orphans. Deferred debt (Phase 10 re-verification, Nyquist backfill, PKG-05/TEST-07, chat-state side panel) and dormant seeds explicitly NOT folded into any v1.2 phase.

### Decisions

- 2026-04-27 (Plan 09-01): AUD-07 plumbing (`AuditFieldHasher` + `AiAgentAuditProperties`) shipped with intentional zero callers per CONTEXT D-18. Phase 11 `MutationErrorTranslator` is the planned consumer. SHA-256 over UTF-8 byte encoding (locale-independent), lowercase 64-char hex via `java.util.HexFormat`. No SPI extraction — deferred until a host requests non-SHA-256 hashing. (v1.2 note: Phase 20 STT audit reuses `AuditFieldHasher` for the transcript hash.)
- 2026-04-27 (Plan 09-01): Spring config defaults landed in `module.properties`, NOT in `default-params.yaml` (which is strict `AiParameters` seed YAML). Planner-review carve-out honored. (v1.2 note: Phase 17 config-knob migration extends the `AiParametersResolver` read-through; the strict `default-params.yaml` seed stays strict.)
- [Phase ?]: Plan 09-02: Locked the Phase-9 SPI contract surface — ToolFetchPlanCustomizer (D-09 signature) + FetchPlanContext concrete request snapshot + SpiDefaultsAutoConfiguration no-op default. FetchPlanContext does NOT carry RunContext (per D-10 review correction: RunContext is final + private constructor + static accessors). Verbatim TOOL-11 phrase 'fetch plan is projection, not security.' authored at the SPI seam; Plan 09-04 will repeat the phrase at the FetchPlanIntersector consumer seam.
- [Phase ?]: 2026-04-27 (Plan 09-03): BaselineContextProvider emits agent.entities + agent.permissions per chat turn from CurrentUserSchemaAccess + AccessManager + MessageTools. agent.permissions is locale-invariant by construction (P-8): TreeMap entity-keys + LinkedHashMap r,u,c,d,modifiable order + TreeSet attribute iteration; only agent.entities carries locale-resolved labels (parenthesized suffix). Same sorted/capped entity list drives both blocks. Phase 10 LlmExposurePolicy substitution is a single-line swap of the getReadableSchema() call site. (v1.2 note: Phase 19 PERF-01 memoizes getReadableSchema()/agent.permissions per RunContext — agent.permissions cacheable without locale, agent.entities must include locale per PERF-02.)
- [Phase ?]: 2026-04-27 (Plan 09-04): FetchPlanResolver + FetchPlanIntersector landed in com.vn.agent.tools.fetchplan; verbatim TOOL-11 phrase exposed as public constant FetchPlanIntersector.PROJECTION_NOT_SECURITY_COMMENT and referenced from class Javadoc via {@value}. PLAN_NARROWED: greppable audit-prefix with AiToolCallOutcome.FLAGGED — no new outcome enum value. describe_entity widened via MetadataTools (no raw reflection); ToolResultFormatter.records emits literal PROMPT-04 envelope <data entity><label></data> with label first via MessageTools.getEntityCaption. UNKNOWN_ENTITY_HINTS verbatim D-14 with em dash preserved on hint #3. Phase 3 D-08 access_denied opacity preserved (Phase 10 will unify, not Phase 9).
- [Phase ?]: 2026-04-27 (Plan 09-05): Output-scanner Phase 9 pattern packs (HOST_PREFIX_LEAK / TOOL_NAME_LEAK) shipped as @Component providers with startup snapshot at ApplicationReadyEvent + lazy-fallback build in asPattern() (eager-singleton ordering safety). OutputScannerAdvisor widened to implement CallAdvisor + StreamAdvisor; streaming uses ChatClientMessageAggregator. Pattern.quote per token (T-09-22 ReDoS). Default-on toggles in module.properties. (v1.2 note: Phase 15 TEST-19 reuses these pattern packs at the UI layer for the observability-leak test.)
- [Phase ?]: 2026-04-27 (Plan 09-05): AgentSystemPromptRules.PROMPT_RULES carries verbatim PROMPT-03 vocabulary rules + D-15 retry contract whose three hint substrings match BuiltInDataTools.UNKNOWN_ENTITY_HINTS BYTE-FOR-BYTE (em dash U+2014 preserved). Lowercase 'if' bullets sacrificed sentence-case to keep the cross-assertion green for TEST-08 in Plan 09-06. Constant lives in com.vn.agent.guard alongside OutputScannerAdvisor (both leak-prevention). DefaultChatServiceImpl wires PROMPT_RULES at BOTH composition sites (blocking ask + streaming stream) so rules apply on every turn regardless of transport mode and even when profile prompt is blank. Hardcoded English (no i18n) per RESEARCH Pitfall 7 — model-directed instructions, not user-facing UI.
- [Phase ?]: Plan 09-06 (TEST-08): cross-locale prompt-contract regression suite landed; Phase 9 feature-complete
- [Phase ?]: Plan 10-01: AiExposureRule (entity-level only, no attributePath) + AiExposureRuleMode (EXCLUDE only) in com.vn.agent.exposure. Liquibase 060+061 auto-loaded. ChunkMetadata.SOURCE_ENTITY=source_entity constant for EXP-05 NOT IN denylist (Plan 10-05 consumer).
- [Phase ?]: Plan 10-02: dataManager.load(EntityClass).query() auto-resolves store from @Store annotation; .store() chain method only applies to raw-JPQL loadValue paths
- [Phase ?]: Plan 10-02: LlmExposurePolicy.canModify ships unused in Phase 10; Phase 11 mutation gating wires it before DataManager.save
- [Phase 10]: Plan 10-03: AiAgentAdminRole extended with @EntityPolicy AiExposureRule + menu/view IDs for AiExposureRule list/detail and VectorStoreDebug; pure additive (zero existing policies removed); SEC-05 partially complete (AiUiSettings policies will fully close in Phase 12)
- [Phase ?]: Plan 10-04: Mechanical call-site swap complete — BaselineContextProvider, BuiltInDataTools, FetchPlanIntersector inject LlmExposurePolicy instead of CurrentUserSchemaAccess. Fix R4 unification: ALL canReadEntity()==false branches in BuiltInDataTools throw unknown_entity (not access_denied) — full opacity per EXP-09 + Phase 3 D-08. Fix R5: FetchPlanIntersector routes both canReadAttribute AND canReadEntity through the policy. UNKNOWN_ENTITY_HINTS byte-for-byte preserved (em dash U+2014). ToolQueryCountBaselineTest recalibrated for D-14 no-cache: list_entities/describe_entity ceiling raised from 0 to 5 SELECTs to absorb the per-call agentstore policy lookup. (v1.2 note: Phase 19 PERF-01/02 may lower this ceiling again via per-turn memoization + the app-wide denylist cache evicted on LlmExposureChangedEvent — recalibrate ToolQueryCountBaselineTest only if the perf change demonstrably reduces queries; the security/opacity assertions stay unchanged.)
- [Phase ?]: Plan 10-05: RetrievalFilterBuilder applies defensive (source_entity IS NULL) OR (NOT IN <denied>) for non-empty denylist (Fix R6); AsyncIngestionWorker.enrich mirrors sourceEntityName to ChunkMetadata.SOURCE_ENTITY when non-null. Legacy chunks unaffected until reingested (D-06). (v1.2 note: Phase 19 PERF-03 builds this Filter.Expression once per retrieval — the (source_entity IS NULL) OR (NOT IN <denied>) / role clauses are preserved verbatim; the RetrievalFilterBuilder denylist test passes unchanged.)
- [Phase ?]: Plan 10-06: Toggle save uses UnconstrainedDataManager and Fix R2 enforced — view does NOT inject ApplicationEventPublisher; AiExposureRuleEntityListener remains the single LlmExposureChangedEvent publish site. (v1.2 note: Phase 17 mirrors this pattern — an AiParameters/AiUiSettings entity listener is the single change-event publish site for the settings-cache eviction hook required by Phase 19.)
- [Phase ?]: Plan 10-06: MetaclassComboBoxHelper extracted as shared @Component for reuse across exposure detail view (10-07) and KB upload form (10-08); single source of truth for @SystemLevel + AI-* internals exclusion
- [Phase ?]: Plan 10-07: detail view reuses MetaclassComboBoxHelper; ComboBox<MetaClass> value-bridged in controller; ReadyEvent for pre-select; 10-08 reingest error keys shipped ahead in Group B; menu uses <item>; EN bundle is messages_en.properties
- [Phase ?]: Plan 10-09: VectorStoreDebugView shipped — plain Vaadin Grid<Document> (Fix R7) over VectorStore.similaritySearch (empty query, topK=100, threshold=0.0); FilterExpressionTextParser with inline setErrorMessage on parse error; metadataFilterField is TypedTextField<String> (Fix W2); 3 programmatic addColumn calls; expand uses standard Vaadin Dialog (Document is Spring AI POJO, no Jmix metaclass); read-only — no edit/delete per CONTEXT D-09
- [Phase ?]: Plan 10-08: KB upload sourceEntityName persisted BEFORE dataManager.save (D-07 invariant); KnowledgeDocumentService.updatePermissionsAndReingest returns UpdatePermissionsResult enum so view has zero business logic (CLAUDE.md compliant); 3-arg upload overload preserved for backward compatibility with IngesterManager + tests
- [Phase ?]: Plan 10-10: TEST-09 four-path uniform-opacity gate landed (RetrievalFilterBuilderDenylistTest unit + LlmExposurePolicyIntegrationTest integration). Two-tier RAG filter coverage. Phase 10 complete (10/10 plans). (v1.2 note: TEST-09's RAG leg + RetrievalFilterBuilderDenylistTest must pass unchanged after Phase 19 PERF-03.)
- [Phase ?]: Plan 11-01: AiMutationIntent agentstore entity ships with composite unique index on (TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME) + REQUEST_HASH + STATUS_ enum (PENDING/COMMITTED/FAILED/COMMIT_UNKNOWN). PENDING reserved before host save so DB unique index serializes duplicates. COMMIT_UNKNOWN parks post-save finalization failures. AiAgentMutationRole is empty marker (no AiMutationIntent READ to avoid leaking idempotency keys); AiAgentAdminRole gains @EntityPolicy(AiMutationIntent, ALL); both locale bundles updated. IDX_AI_MUT_INTENT_STATUS added beyond plan baseline for cleanup-job diagnostics.
- [Phase ?]: Plan 11-02: AiAgentMutationProperties (@ConfigurationProperties ai-agent.tools.mutation) record + AiToolCallOutcome enum extension (IDEMPOTENT_REPLAY/COMMIT_FAILED via EnumClass<String>, no schema migration) + @EnableScheduling on AIConfiguration. Rule 3 auto-fix: AiAuditEventDetailDialog.outcomeTheme switch extended with the 2 new cases. Rule 2 auto-fix: bilingual auditList.outcome.* lowercase keys added alongside metaclass-format keys (AiAuditEventListView+DetailDialog use lowercase convention).
- [Phase ?]: MutationIntent attributes use Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) NOT Map.copyOf — null attribute values represent optional-field clears
- [Phase ?]: MutationGuard default no-op bean lives directly in AIConfiguration via @ConditionalOnMissingBean — no separate SpiDefaultsAutoConfiguration class (mirrors aiAgentIngestExecutor precedent)
- [Phase ?]: ToolVetoedException reused verbatim for MutationGuard veto path — no new exception type
- [Phase ?]: Plan 11-04: ToolEntityResolver shared @Component centralizes Phase 10 R4 unknown_entity opacity for both READ and WRITE tool paths
- [Phase ?]: Plan 11-04: LlmExposurePolicy split into operation-specific canCreate/canUpdate; canModify retained as backward-compatible alias delegating to canUpdate
- [Phase ?]: Repository reservation uses TransactionTemplate (REQUIRES_NEW) so commit-time DataIntegrityViolationException is caught around execute(...) and re-classified
- [Phase ?]: MutationIntentFailureProbe is a package-public ObjectProvider test seam for TEST-12 COMMIT_UNKNOWN coverage
- [Phase ?]: Cleanup job logs but never deletes PENDING/COMMIT_UNKNOWN; auto-deletion would allow duplicate host writes after a finalization failure
- [Phase ?]: MutationErrorTranslator NEVER echoes raw exception text or LLM-supplied attribute names; pre-typed ToolUserError instances are sanitized via canned safe templates per code (P-22 mitigation)
- [Phase ?]: commitFailed maps to concurrent_modification stable code with 'do not retry automatically' hint; the 6-code D-04 taxonomy is closed (no synthetic 7th code for commit-unknown). (v1.2 note: Phase 18 MUT-18 — the 6-code taxonomy stays closed; MutationErrorTranslator outputs are byte-for-byte identical after the MutationGateChain extraction.)
- [Phase ?]: Both OptimisticLockException flavors (jakarta + Spring's translated) AND both AccessDeniedException flavors (Spring + io.jmix.core.security) are caught explicitly per RESEARCH Pitfall 5
- [Phase ?]: Plan 11-07B: related-write tools narrowly support non-composition parent OneToMany(mappedBy) + child to-one inverse only. (v1.2 note: Phase 18 MUT-17 memoizes the (parentMetaClass, relationshipName) → supported-relationship descriptor — immutable Jmix metamodel, no eviction.)
- [Phase ?]: Plan 11-07C locked mutation-tool invariants via JavaDoc + MutationToolInvariantsTest source-level enforcement. (v1.2 note: Phase 18 MUT-15 extends MutationToolInvariantsTest to lock the MutationGateChain gate order + assert no @Transactional on the chain; the existing assertions stay unchanged.)
- [Phase ?]: 11-09: ObjectProvider.getIfAvailable for BuiltInMutationTools per RESEARCH Q5
- [Phase ?]: 11-09: Mutation callbacks ride MutationToolCallbackBoundaryDecorator (NOT ToolCallbackAuditDecorator) — single audit owner from Plan 11-07C preserved
- [Phase ?]: 11-09: Sibling top-level @Component AgentSystemPromptRulesComposer (no nested @Component precedent)
- [Phase 11]: Use a test-only Jmix module to make mutation fixture persistence win the reverse persistence.xml scan. — Keeps Plan 11-07B fixture ownership intact while making Plan 11-10 integration tests executable.
- [Phase 11]: Use @MockitoBean for mutation guard capture tests instead of nested @TestConfiguration. — Prevents guard test doubles from leaking into unrelated Spring test contexts.
- [Phase 11]: Preserve scalar null mutation attributes before structured-filter literal conversion. — Null values represent optional-field clears at the mutation prompt boundary.
- [Phase 11]: Use @MockitoBean for the commit-unknown failure probe so the failure does not leak into unrelated Spring test contexts.
- [Phase 11]: Keep Java 17-compatible List.get(0) assertions even when JetBrains suggests List.getFirst().
- [Phase 11]: Align the existing tool-name scanner baseline test with Phase 11's read, link, and mutation built-in names.
- [Phase 12]: Persist enabled chat surfaces as deterministic enabledSurfaceIds text with typed helper methods, not as an enum collection property. — Jmix enum collection persistence is not used here, and the Phase 12 contract forbids an enabledSurfaces JavaBean collection on AiUiSettings.
- [Phase 12]: Keep the existing agentstore includeAll changelog strategy and document that it picks up 080-ai-ui-settings.xml. — Changing old changelog include paths could alter Liquibase change identity for deployed databases; includeAll already loads the new file. (v1.2 note: Phase 17 CFG-03 adds a new agentstore changelog included in agentstore-changelog.xml.)
- [Phase 12]: Use the included-build Gradle path :ai-agent:ai-agent:* for Phase 12 add-on verification. — The root checkout has no :ai-agent:test task; Gradle exposes the functional module through the nested included-build path.
- [Phase 12]: Use a singleton StandardDetailView for AiUiSettings that loads through AiUiSettingsService.loadCurrent(), so admins edit only AiUiSettings.SINGLETON_ID and cannot create arbitrary settings rows. — Matches the singleton settings model and avoids arbitrary configuration rows. (v1.2 note: Phase 17 reuses this singleton settings pattern for any new AiUiSettings/AiOperatorSettings fields.)
- [Phase 12]: Keep settings surface controls controller-managed and persist through getEnabledSurfaceSet/setEnabledSurfaceSet, not an enabledSurfaces Jmix entity property. — Avoids unsupported Jmix enum collection binding and preserves the Phase 12 entity contract.
- [Phase 12]: AiAgentAdminRole grants AiUiSettings READ/UPDATE plus view/menu policies only; CREATE/DELETE stay service-internal for the singleton row. — Admins can edit settings while singleton creation remains trusted service code.
- [Phase 12]: Use DialogWindow<?> in AiChatUIState until ChatDialogView is introduced by Plan 12-04. — Keeps Plan 12-03 compiling while preserving the per-UI dialog handle contract.
- [Phase 12]: Keep active stream/run authority in ChatPanelFragment plus CancellationRegistry; AiChatSessionState stores only currentConversationId and listeners. — Preserves D-10 and avoids turning session state into a cancellation authority. (v1.2 note: Phase 15 OBS-04 — per-turn observability detail does not accumulate unbounded in AiChatSessionState; lazy-query the audit tree on disclosure expand.)
- [Phase 12]: Plan 12-05: Use ConversationTitleEligibilityPublisher to isolate title eligibility from DefaultChatServiceImpl. — Keeps chat runtime changes narrow while proving publication happens only after assistant response handling returns.
- [Phase 12]: Plan 12-05: Manual title edits check ownership through ConversationGateway and save through secured DataManager. — Preserves the AI-as-Jmix-client security model while allowing user-visible title overrides to block future auto-title clobbering.
- [Phase ?]: Phase 13 Plan 13-01: AiTaskFile entity foundation + qwen/qwen3.6-35b-a3b model swap shipped; D-03 schema lock (separate addForeignKeyConstraint with onDelete=SET NULL for MESSAGE_ID, REVIEWS HIGH-2); injectedAt is authoritative pending marker (REVIEWS HIGH-1); user role gains DELETE for Plan 04 chip removal (REVIEWS HIGH-3); default-params.yaml model swapped (REVIEWS HIGH-9). (v1.2 note: Phase 16 makes the model field a curated ComboBox writing the existing free-text model value; default stays qwen/qwen3.6-35b-a3b per project_self_hostable_models_only.md.)
- [Phase ?]: Phase 13 Plan 13-02: AiTaskFileRepository + AiTaskFileMediaResolver + AiTaskFileCleanupJob + package-info shipped. Resolver predicate is injectedAt IS NULL per REVIEWS HIGH-1. markInjected runs in REQUIRES_NEW agentstore tx; deleteRow uses blob-first ordering with retry-on-failure semantics. TEST-16 forbidden-token grep gates at zero across all .java sources except the allowlisted package-info.java JavaDoc (REVIEWS HIGH-8).
- [Phase ?]: 13-03: bulk_save_records as single @Tool with id-presence dispatch (D-02 mixed-batch); ONE @Transactional boundary on MutationSaveExecutor.bulkSave. (v1.2 note: Phase 18 — bulk_save_records becomes a thin adapter over MutationGateChain; the single @Transactional boundary stays on MutationSaveExecutor only.)
- [Phase ?]: 13-03: AiMutationIntent.RESULT_SUMMARY column persists bulk savedIds for IDEMPOTENT_REPLAY (REVIEWS HIGH-11)
- [Phase ?]: 13-03: Per-row CrudEntityContext after entity load/create satisfies MUT-14 row-state-dependent constraint enforcement (REVIEWS HIGH-13)
- [Phase 13]: Plan 13-06 gap closure: extracted `DefaultChatServiceImpl.executeBlockingTurn(...)` private helper to eliminate the BLK-01 streaming-fallback double-write — both `ask(...)` and the streaming `catch(UnsupportedOperationException)` now delegate to the same helper passing already-resolved Media + already-persisted user-message id, so `userMessagePersister.persistUserMessage` and `taskFileMediaResolver.resolvePending` each fire EXACTLY ONCE per turn on the D-04 graceful-fallback path. D-03 streaming-success doOnComplete invariant left untouched. BLK-04 (prompt-build orphan) closed implicitly. Regression locked by `DefaultChatServiceImplStreamFallbackTest` (5 Mockito tests, pure JUnit — sidesteps deferred AiAuditEvent boot regression).
- [Phase ?]: Phase 13.1 Plan 01: Wave-0 schema lock — dropped AI_TASK_FILE.MESSAGE_ID/INJECTED_AT (Liquibase 100), removed AiTaskFile.message/injectedAt fields, flipped AiTaskFileProperties to long ttlSeconds + perTurnMaxFiles + perTurnMaxTotalBytes with -1 sentinel, added AiMessageRole.NOTICE + bilingual captions. Compile passes via Rule 3 stubs in AiTaskFileRepository.markInjected, ConversationDetailView switch, and ChatPanelFragment.setExpiresAt; full rewrite in Plans 13.1-02/04. Three Phase 13 tests pinned to ai-agent.task-file.ttl=PT1H deferred to Plan 13.1-06. (v1.2 note: Phase 17 may surface ttlSeconds + perTurnMaxFiles + perTurnMaxTotalBytes as editable AiParameters/AiUiSettings knobs — the -1 sentinel semantics carry over.)
- [Phase 13.1]: Plan 02: AiTaskFileMediaResolver.resolveActive(UUID) per-turn-all + LRU budget cap + task_file_budget_exceeded audit (REQUIRES_NEW; failure swallowed to log.warn). Resolved record now (media, budgetExceeded); taskFileIds dropped. AiTaskFileRepository slimmed to {loadExpired, deleteRow, deleteAllExpired} — markInjected and loadPending deleted with their AiMessage/Optional imports. New BudgetExceededAuditKeys constants are the single source of truth shared with the upcoming BudgetCapTest (Plan 13.1-06) — 9-key argumentsJson order locked via LinkedHashMap; Jackson JsonProcessingException falls back to a String.format literal that ALSO references the constants. Rule-3 stub: DefaultChatServiceImpl resolvePending/markInjected/taskFileIds call sites swapped for resolveActive + structural no-ops; Plan 13.1-03 deletes the dead UserMessagePersister wiring + executeBlockingTurn parameter end-to-end. (v1.2 note: Phase 19 PERF-04 caches the encoded Media per (conversationId, taskFileId) per turn — evicted on attachment add/delete; resolveActive's per-turn-all + LRU budget-cap semantics are preserved.)
- [Phase 13.1]: Plan 03: ProjectingChatMemoryRepository.saveAll JPQL excludes role=NOTICE so notice rows survive the delete-recreate projection wipe each turn (D-A1). DefaultChatServiceImpl now calls AiTaskFileMediaResolver.resolveActive(convId) once per turn on both the blocking ask() and the streaming stream() transports; UserMessagePersister field/parameter and the markInjected stamping path are removed; executeBlockingTurn signature drops userMessageIdAlreadyPersisted while preserving the BLK-01 single-write streaming-fallback invariant. ChatResponseDto + StreamingEvent.Final extended with a budgetExceeded boolean propagated from Resolved.budgetExceeded() on both transports (D-D1). UserMessagePersister.java deleted; chat-memory advisor's own AiMessage projection is the sole user-message persistence path. DefaultChatServiceImplStreamFallbackTest rewritten with 4 cases (A/B/C/D) covering resolveActive-once-per-turn on both transports + budgetExceeded propagation on both — all green on pure JUnit 5 + Mockito.
- [Phase 13.1]: Plan 04: chat-panel-fragment.xml reshaped into a horizontal `<split splitterPosition="68">` with chatPanel left and attachmentsPanel right; right-pane vbox keeps id="attachmentsPanel" so the Phase 12 ChatSurfaceMounter slot contract is preserved (REQ-7 zero-diff). New ai-task-file-card-fragment.xml + AiTaskFileCardFragmentRenderer.java (extends FragmentRenderer<JmixCard, AiTaskFile> + @RendererItemContainer("taskFileDc")) ship the per-row card with DOWNLOAD action via Jmix Downloader and TRASH action via Dialogs option-confirm; performDelete removes the JPA row first and best-effort removes the storage blob (log-and-continue on blob failure — TTL cleanup sweeps orphans, D-B1). resolveIcon adds an image-extension arm (png/jpg/jpeg/gif/webp → FILE_PICTURE) on top of the CRM csv/xlsx → TABLE and pdf/html/md/txt → FILE_TEXT_O switch. ai-agent-chat.css gets a verbatim CRM appendix (14 new top-level rules) with the single mechanical rename .ai-conversation-message-list → .ai-agent-chat-panel__messages. 13 new chatView.attachments.* keys land in both messages_en.properties and messages_vi.properties. Java fragment controller wiring (taskFilesDl loader binding, empty-state toggle, NOTICE rendering, budgetExceeded toast) is owned by Plan 13.1-05 — this plan delivers the stable XML/CSS/i18n surface against which Plan 05 builds. One Rule-1 deviation: the documentation comment in chat-panel-fragment.xml was rephrased so it doesn't name the deleted `attachRow`/`chip-strip` ids (the verify regex scans comments alongside elements). (v1.2 note: Phase 15 adds the `SIDEBAR` / right-sidebar chat surface over the same fragment; Phase 20 mounts the mic recorder in messageInputSlot; the chat-state panel is deferred and must not be added to attachmentsPanel in Phase 15.)
- [Phase 13.1]: Plan 05: ChatPanelFragment.java rewired in a single-file rewrite — chip-strip + MessageList substrate retired; right-pane data loader (taskFilesDl) bound programmatically with :conversationId in onReady, setConversationIdInternal, ensureConversationIdForSubmit, ensureConversationIdForUpload; empty-state toggle wired via @Subscribe(id="taskFilesDl", target=Target.DATA_LOADER) onTaskFilesPostLoad over CollectionLoader.PostLoadEvent. messageListSlot migrated from MessageList to a VerticalLayout of MessageBubbleComponent Composites for USER/ASSISTANT plus raw <vaadin-message class="attachment-event"> sibling Elements for NOTICE (Pitfall 7 Option A). handleUploadedFile persists an AiMessage(role=NOTICE) via metadataApi.create with seq computed via DataManager.loadValue(...).store("agentstore") (memory feedback_jmix_loadvalue_store), log-and-continue on failure. Budget-exceeded toast invoked via single showBudgetExceededToast() helper from BOTH paths: streaming consumer reads StreamingEvent.Final.budgetExceeded() inside doOnNext; package-private onBlockingResponse(ChatResponseDto) reads ChatResponseDto.budgetExceeded() (covers blocking-path consumers and tests per CONTEXT D-D1). attachmentsPanel field type stays VerticalLayout (REQ-7 / Pitfall 6); Phase 12 contract files (ChatSurfaceMounter, AiUiSettingsService, AiUiSettings, ChatView, ChatDialogView) have ZERO diff. Two Rule-3 auto-fixes: (a) bundle-key resolution form switched from class-scoped Messages.getMessage(class, key) to bare Messages.getMessage(key) + explicit-group Messages.formatMessage("com.vn.agent", key, params) per memory feedback_jmix_messages_over_spring; (b) field-block comment rephrased to remove a literal "MessageListItem" token that tripped the verify regex. (v1.2 note: Phase 15's streaming-status badge renders in a sibling slot of the message list — not inside MessageBubbleComponent; it clears completely on turn finalization.)
- [Phase 13.1]: Plan 06: 4 new @SpringBootTest regressions land — PerTurnMediaInjectionTest (TEST-18 — 3 sequential resolveActive calls return the same Media bytes; reflection guard via Class#getDeclaredMethods asserts no markInjected/loadPending overload survives on AiTaskFileRepository), BudgetCapTest + BudgetCapSentinelTest (REQ-3 default-caps drop-oldest + LRU + 9-key argumentsJson via BudgetExceededAuditKeys constants; sentinel caps=-1 returns all rows + zero audit rows), TtlConfigTest + TtlConfigSentinelSkipsCleanupTest + TtlConfigFkCascadeUnderSentinelTest (REQ-4 default ttlSeconds=86400; sentinel ttl-seconds=-1 skip on cleanup-job; FK cascade still reaps under sentinel), NoticeFilterTest (REQ-5 D-A1 NOTICE survives ProjectingChatMemoryRepository.saveAll wipe + D-A2 Spring AI store never carries NOTICE). Sentinel-context fixtures ship as TOP-LEVEL sibling classes (NOT @Nested) so Spring Boot context cache stays clean. The 3 existing Phase 13 tests pinned to ai-agent.task-file.ttl=PT1H now use ttl-seconds=3600; AiTaskFileMediaResolverIntegrationTest's @Disabled placeholder replaced with 3 happy-path cases against resolveActive. TaskFileNoVectorStoreSourceScannerTest scope widened to AiTaskFileCardFragmentRenderer.java + chat-panel-fragment.xml — both pass with 0 forbidden-token references. compileTestJava + scanner test BUILD SUCCESSFUL. Test runtime for the 4 new @SpringBootTest classes inherits the pre-existing Phase 11/13 atmosphere-runtime / agentstoreEntityManagerFactory boot regression documented in .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md (verified by reproducing on the pre-Plan-06 AiTaskFileMediaResolverIntegrationTest @Disabled placeholder); not introduced by this plan, surface stays compileTestJava-green and source-correct. (v1.2 note: the pre-existing Phase 11/13 @SpringBootTest boot regression still affects module-level Spring-context tests; v1.2 phases prefer XML/source-scan or pure-Mockito tests for UI/contract coverage as in Phases 13.1/14.)
- [Phase 13.1]: Plan 07: 5 plan-required tests land green TODAY (sidesteps the deferred Spring-context boot regression). CrmStyleLayoutTest (3 cases) parses chat-panel-fragment.xml + chat-view.xml + chat-dialog-view.xml directly to assert split splitterPosition=68 + the documented right-pane slot ids (attachmentsPanel/Title/EmptyState/GridLayout/taskFileUpload) + zero attachRow/attachButton residue + both surfaces mount the same ChatPanelFragment. NoticeRenderTest (3 cases) source-scans ChatPanelFragment.java for the appendNoticeRow helper + raw <vaadin-message class="attachment-event"> Element substrate + setProperty("text",...) T-13.1-17 escape mitigation + clearMessageList wipes both Component and raw Element children + zero MessageListItem residue + AiMessageRole.NOTICE enum guard. SurfaceMountingTest (2 cases) asserts both surface descriptors mount the fragment with no slot-id overrides + git diff against origin/main on the 5 Phase 12 contract files returns empty. LiquibaseSchemaTest (5 cases) parses 100-ai-task-file-drop-dead-columns.xml + 090-ai-task-file.xml. LocaleParityTest extension (+2 tests) asserts all 14 chatView.attachments.* + AiMessageRole.NOTICE keys present + non-blank in BOTH bundles. All 16 testcases green.
- [Phase 14]: Plan 14-01 compiled against the Gradle Java 21 toolchain while avoiding preview APIs because AGENTS.md still names Java 17.
- [Phase 14]: Plan 14-01 used XML/source structural tests for draft foundation contracts because the shared module Spring Boot context is blocked by a pre-existing AiAuditEvent metaclass boot regression.
- [Phase 14]: Plan 14-02 keeps structured-output target as Map/prompt JSON schema; MetaClassDtoSynthesizer emits schema text only, no runtime DTO bytecode. — This preserves the Phase 14 decision that strict mode is prompt-only and avoids generating runtime classes for host metamodels.
- [Phase 14]: IntentRegistry eligibility is recalculated per request and filters named intents through LlmExposurePolicy plus Jmix create/read permission; Auto remains UI-only. — Exposure, security, and locale are request-sensitive, and Auto is a UI selection rather than an IntentExtractor bean. (v1.2 note: Phase 19 PERF-01 may memoize the per-request eligibility computation within the RunContext — never across requests/users/LlmExposureChangedEvent.)
- [Phase 14]: Plan 14-02 uses Mockito/Jackson unit tests for registry and schema contracts instead of Spring Boot tests. — The behavior under test is independent of the Jmix boot context, and this avoids the known shared module Spring context blocker while covering planned contracts.
- [Phase 14]: Plan 14-03 keeps prepare_form_draft audit ownership inside ExtractionService; ToolCallbackAuditDecorator emits streaming payloadJson but skips duplicate generic audit rows for that tool.
- [Phase 14]: ExtractionToolBridge is payload-only: it returns open_form_with_draft payloads and has no ViewNavigators or navigation calls.
- [Phase 14]: Chat intent ids are per-turn only; blank/Auto maps to the default chat path while named ids are resolved through IntentRegistry before prompt/tool setup. — Preserves existing callers and ensures stale or unauthorized named intents fail closed before the LLM receives prompt rules or callbacks.
- [Phase 14]: Named-intent callback gating filters Spring AI callbacks by ToolDefinition name and requires exactly one prepare_form_draft callback. — This makes tool-surface isolation structural instead of relying on prompt wording, while preserving Auto turns' full tool surface.
- [Phase 14]: ExtractionToolBridge uses chat-scoped ExtractionInput only when DefaultChatServiceImpl populated extraction-turn state, not merely because audit context has a conversation id. — AuditAdvisor also writes RunContext conversation ids; checking extraction-specific fields prevents direct tool calls from losing explicit contextRefs.
- [Phase 14]: DraftLoader is UI-free and applies payload fields only after EntityAttributeContext.canModify passes.
- [Phase 14]: OpenFormWithDraftHandler reloads the draft by draftId on every open call and is the only chat-intent class that imports ViewNavigators.
- [Phase 14]: Draft rows are marked confirmed and removed only from the StandardDetailView.AfterSaveEvent path; close events only remove listener registrations.
- [Phase 14]: The handler checks create permission in addition to UiShowViewContext before opening a new detail view.
- [Phase 14]: Plan 14-06: Intent row uses Jmix radioButtonGroup plus @Supply ComponentRenderer, with Auto as the first/default option and hidden row when no named intents are eligible.
- [Phase 14]: Plan 14-06: StreamEventRenderer parses only prepare_form_draft ToolResult.payloadJson and returns a structured DraftPayload marker; human-readable summaries are never parsed for extraction UI. (v1.2 note: Phase 15 extends StreamEventRenderer-style consumption of the StreamingEvent flux for the ephemeral status badge + per-turn tool-detail disclosure — humanized label-only steps, never internal tool/entity names.)
- [Phase 14]: Plan 14-06: Confirm rows are appended by ChatPanelFragment and delegate clicks to OpenFormWithDraftHandler; StreamEventRenderer remains navigation-free.
- [Phase 14]: Plan 14-06: UI tests use source/XML contract checks because full Jmix UI boot remains affected by the pre-existing agentstore Spring context blocker documented in prior phase summaries.
- [Phase 14]: Plan 14-09 is a dependent gap-closure pass for `14-VERIFICATION.md` blockers BL-02 through BL-05; BL-01 was narrowed by user correction so datasource/UI defaults stay in application.properties and only OpenRouter API key remains env-backed. It intentionally adds no new AI tool, entity table, audit kind, Jmix view/menu, or AI-specific exposure layer.
- [Phase 15]: Plan 15-01: `AiChatSurface.SIDEBAR("SIDEBAR")` added as the third enum constant; `com.vn.agent.entity/AiChatSurface.SIDEBAR` label added to messages_en.properties (`Right sidebar`) + messages_vi.properties (`Thanh bên phải`). No DDL — `ENABLED_SURFACE_IDS`/`DEFAULT_SURFACE` are existing varchar columns (OBS-04 by construction). No production code change beyond the constant + labels: `AiUiSettingsDetailView` already calls `setItems(AiChatSurface.class)` for both the enabled-surface checkbox group and the `defaultSurface` radio group, and `AiUiSettings#enabledSurfaceIds` default already delegates to `EnumSet.allOf(AiChatSurface.class)`. RESEARCH Open Q2 resolved: `createDefaultSettings()` stays `EnumSet.allOf` so fresh installs ship all three surfaces enabled; `defaultSurface` stays `FULL_ROUTE` — the side panel starts closed (D-04), so enabled-by-default only means the navbar toggle is present (parity with HEADER_BUTTON). Tests-only changes: extended AiUiSettingsModelTest + AiUiSettingsDetailViewTest, and updated default-seed assertions in AiUiSettingsDetailViewTest + AiUiSettingsServiceSingletonTest to include SIDEBAR. Plan 03 (`ChatSurfaceMounter`) can now gate the side-panel mount on `getEnabledSurfaceSet().contains(AiChatSurface.SIDEBAR)`.
- [Phase ?]: Phase 15: Activity(CHAT) is NOT emitted from the orchestration edge (review point #11); DefaultChatServiceImpl unchanged, UI derives CHAT from the first Content event
- [Phase 15]: Plan 15-03: SIDEBAR chat surface mounted by ChatSurfaceMounter through a new lean Jmix host view `AiAgentSidebarView` (`@ViewController("AiAgent_Sidebar")`, `ai-agent-sidebar-view.xml` = a single `<fragment id=chatPanelFragment class=...ChatPanelFragment/>` — NO new fragment subclass; syncs `setConversationId` from `AiChatSessionState` on BeforeShow/Ready, mirroring ChatDialogView). The host view is created via `views.create(AiAgentSidebarView.class)` and SHOWN via `views.create(...)` + `panelDiv.getElement().appendChild(sidebarView.getElement())` + `ViewControllerUtils.fireEvent(new View.BeforeShowEvent(v))` then `ViewControllerUtils.fireEvent(new View.ReadyEvent(v))` (chosen over a DialogWindow wrapper — the `<vaadin-dialog>` overlay doesn't live inside an arbitrary Div and brings modality machinery; the `fireEvent` path drives the non-routed view's lifecycle and ReadyEvent propagates to the fragment's `@Subscribe onReady` via `Fragment.onHostReadyInternal`; no modal curtain). The panel is a `position:fixed` `Div.ai-agent-sidebar` appended to `UI.getCurrent().getElement()` (NOT the AppLayout content slot — survives navigation; re-asserted on `AfterNavigationEvent` along with the push class + toggle visibility), containing a `Div.ai-agent-sidebar__header` close button + the host view. A far-right navbar toggle `aiAgentSidebarToggleButton` (VaadinIcon.PANEL, LUMO_TERTIARY+LUMO_ICON, `aria-pressed` + `.ai-agent-sidebar-toggle--active`) and the in-panel closer both route through one `toggleSidebar(ui)` (flips `--open` + `ai-agent-content--pushed` + `--active` + `aria-pressed` + the toggle's `aria-label` together). Sidebar state (panelDiv / sidebarHostView / toggleButton / sidebarOpen) lives on the per-UI `MountedChatSurfaceState` — `AiChatUIState.java` left untouched. Gating: `shouldShowSidebar(settings, permitted)` = `getEnabledSurfaceSet().contains(SIDEBAR) && isSidebarViewPermitted()` where `isSidebarViewPermitted()` = `UiShowViewContext("AiAgent_Sidebar")` + `accessManager.applyRegisteredConstraints`; disabled/not-permitted = mount-always-then-`setVisible(false)` (mirrors the magic header button — RESEARCH Open Q3). `AiAgent_Sidebar` view id added to AiAgentUserRole.userViews() + AiAgentAdminRole.adminViews(). `@CssImport("./styles/ai-agent-chat.css")` moved onto ChatPanelFragment (REVIEWS #5 — was only on the bubble component, which the MessageList live path never instantiates; the existing bubble-component import stays). CSS (Task 2): `.ai-agent-sidebar` width `clamp(640px, 32vw, 760px)` (REVIEWS #4 — 640px min ⇒ the fragment's 32%-width attachments pane gets ≈205px), `top: var(--lumo-size-xl, 3.5rem)`, `display:none` default; `.ai-agent-sidebar--open`; `.ai-agent-sidebar__header`; `.ai-agent-content--pushed` (`padding-right` matching the clamp); `.ai-agent-sidebar-toggle--active`; `@media (max-width: 768px)` → 100vw overlay + push dropped. No new theme.json / frontend/themes/; no `.ai-agent-status` (deferred → Plan 04); no change to chat-panel-fragment.xml / FULL_ROUTE menu / HEADER_BUTTON dialog logic. New `msg://` keys (en+vi): chatSurfaceMounter.sidebarToggle.ariaLabel.open / .closed, chatSurfaceMounter.sidebarCloser.ariaLabel. SURF-11 "no second ChatService / no second chat memory / no duplicate fragment implementation" holds — same `ChatPanelFragment` CLASS via XML + singleton `ChatService` bean + `AiChatSessionState.currentConversationId` continuity (NOT the same physical fragment object — the sidebar's fragment is a distinct instance, like ChatDialogView's). Proven by ChatSurfaceMounterTest.sidebarUsesTheSameSingletonChatServiceAndExistingFragmentImplementation + ChatPanelFragmentSurfaceSwitchTest.sidebarSurfaceReusesSessionConversationIdForCrossSurfaceContinuity. Two Rule-deviations: reworded a ChatPanelFragment comment to drop the literal token `MessageBubbleComponent` (NoticeRenderTest scans source comments), and added `hs_err_pid*.log`/`replay_pid*.log` to .gitignore (Gradle test-worker crash dumps).

- [Phase 15]: Plan 15-04: in-fragment observability. (1) Status line (OBS-01) — `statusRow` `<span class="ai-agent-status" role="status" aria-live="polite">` appended after `<vaadin-message-list>` in `messageListSlot` (NOT inside a `MessageListItem` — same NOTICE-row sibling trick); neutral typing indicator at turn start, flips to CHAT on the FIRST `Content` event (review #6 — Content implies CHAT regardless of any prior `Activity(RETRIEVAL)`; a later `Activity` still wins), TOOL/RETRIEVAL on `Activity` events; `removeStatusRow()` in `.doOnComplete`/`.doOnError`/`finishStreamInternal()`/`clearMessageList()`/the stop path (via `finishStreamInternal`)/`onDetach` — GONE in every teardown site (review #7), never blanked, never concatenated into the bubble; `Element.setText` (HTML-escaped). (2) Per-turn tool-detail `Details` (OBS-02) — one ordered `Div.ai-agent-turn-activity` appended after `<vaadin-message-list>` holds the collapsed-by-default `<vaadin-details>` per turn with ≥1 tool call (review #2 — a `Details` cannot sit between two `MessageListItem`s); label-only KIND-keyed step rows via `TurnDetailRenderer` (Task 1, committed earlier in `02b2154`) with per-step ms (em-dash `"—"` when unknown, never `"0 ms"`) + an error/rollback indicator. Live turn: `ToolCall`/`ToolResult` (dedup by `toolCallId`)/`Activity(RETRIEVAL)` accumulate into a per-fragment `liveTurnSteps` list capped at 50 (review #12), cleared on every terminal/teardown site (NOT `AiChatSessionState` — OBS-04); on `Final` the disclosure's timings come from a lazy `loadTurnSteps(activeRunId, conversationId)` read of that runId's `AiAuditEvent` TOOL/RETRIEVAL children (review #8 — real `latencyMs`), with the transient arrival-delta steps used only as a fallback. Post-navigation (SPEC req 5 / CONTEXT D-07): after the history-replay `setItems`, `correlateHistoryTurnDetails` loads the conversation's CHAT-root `runId`s (ordered by `startedAt`) + each root's child count via TWO narrow raw-JPQL `loadValues` reads with `.store("agentstore")` (raw `loadValues` does NOT infer the agentstore store — project memory `feedback_jmix_loadvalue_store`; two-query form chosen — a single `left join` on a `@Composition` self-relation is awkward in JPQL), zips them 1:1 against the replayed ASSISTANT turns ONLY when the counts match (else NO disclosures — debug-log, never throw, never guess; review #3), appends a collapsed history `Details` anchored by `runId` only for roots with `childCount > 0` (zero-child roots get NO placeholder); expanding lazily + memoizedly (`TURN_DETAILS_LOADED_KEY` on the `Details`) re-reads that runId's children — collapse+re-expand re-queries nothing (proven by a query-count spy on a delegating `UnconstrainedDataManager` mock — review #13). Constrained-vs-unconstrained decision (RESEARCH Open Q1, confirmed against `AiAuditEventListView`): `AiAgentUserRole` has NO `AiAuditEvent` `EntityPolicy` (Javadoc says so; `AiAuditEventListView` is admin-only) ⇒ all three audit reads use `UnconstrainedDataManager` with a MANDATORY `where e.userUsername = :me and e.conversation.id = :cid` clause (+ `e.runId = :rid` + `e.parent is not null` + a narrow fetch plan: `kind`/`startedAt`/`finishedAt`/`latencyMs`/`outcome`/`errorClass` — no name columns, no LOBs); never `runId`-only unconstrained; the conversation was already ownership-checked at `setConversationIdInternal`. Both paths funnel through `TurnDetailRenderer` (label-only `msg://` keys) — T-15-D1 by construction (TEST-19 in Plan 05 enforces). New `msg://` keys (en+vi): `chatView.status.{neutral,chat,tool,retrieval}`, `chatView.turnDetail.{summary,summaryPending,step.tool,step.retrieval,step.chat,errorIndicator,unknownDuration}`. CSS: `.ai-agent-status` + animated-ellipsis `@keyframes ai-agent-status-pulse` + `@media (prefers-reduced-motion: reduce)` guard; `.ai-agent-turn-activity` + step-row rules — no change to the `.ai-agent-sidebar*` rules Plan 03 added. No new `@Entity`/`@Table`/Liquibase; `AiMessage` / `AiAuditEvent` / `AiChatSessionState` / `chat-panel-fragment.xml` unchanged (`git diff` confirms). Tests (plain JUnit + Mockito, mirroring the existing fragment-test harness — the `accessUi`-wrapped UI mutations need a live UI to run, so the `doOnNext`/history wiring is also asserted via source scan): `ChatPanelFragmentStatusLineTest` (showStatus/removeStatusRow contract, sibling ordering, role/aria-live, removal in `finishStreamInternal`/`clearMessageList`), `ChatPanelFragmentTurnDetailTest` (loadTurnSteps narrow-fetch-plan + mandatory filter, appendTurnDetails one collapsed `Details` with label-only rows + real ms + error indicator + em-dash, same-runId replace, clearMessageList drop, `LIVE_TURN_STEP_CAP == 50`), `ChatPanelFragmentTurnDetailHistoryTest` (matching counts ⇒ Details only for roots with children, no placeholder for zero-child, lazy load once + memoize via query-count spy, count-mismatch ⇒ none + no throw, throwing agentstore swallowed). Full add-on module test green.

### Performance Metrics

| Phase-Plan | Duration | Tasks | Files | Date |
|------------|----------|-------|-------|------|
| 09-01 | ~6 min | 2 | 4 | 2026-04-27 |
| Phase 09 P02 | 6min | 3 tasks | 3 files |
| Phase 09 P03 | 25min | 2 tasks | 5 files |
| Phase 09 P04 | 28min | 3 tasks | 10 files |
| Phase 09 P05 | 22min | 2 tasks | 14 files |
| Phase 09 P06 | 30min | 2 tasks | 3 files |
| Phase 09 P07 | 12min | 4 tasks | 6 files |
| Phase 10 P01 | 15 | 2 tasks | 6 files |
| Phase 10 P10-02 | 9 | 2 tasks | 5 files |
| Phase 10 P03 | 1 | 1 tasks | 1 files |
| Phase 10 P10-04 | 18 | 2 tasks | 9 files |
| Phase 10 P10-05 | 12 | 2 tasks | 3 files |
| Phase 10 P10-06 | 12 | 2 tasks | 5 files |
| Phase 10 P10-07 | 18 | 2 tasks | 5 files |
| Phase Phase 10 PP10-09 | 8 | 2 tasks | 2 files |
| Phase Phase 10 PP10-08 | 25 | 2 tasks | 7 files |
| Phase Phase 10 PP10-10 | 5 | 2 tasks | 2 files |
| Phase Phase 11 PP11-01 | 2min | 2 tasks | 8 files |
| Phase Phase 11 PP11-02 | 3min | 2 tasks | 6 files |
| Phase 11-mutation-capable-built-in-tools P03 | 2min | 1 tasks | 3 files |
| Phase 11 P04 | 11min | 2 tasks | 4 files |
| Phase 11 P05 | 9min | 2 tasks | 2 files |
| Phase 11-mutation-capable-built-in-tools P06 | 6min | 2 tasks | 3 files |
| Phase 11 P07B | 1h | 4 tasks | 13 files |
| Phase 11-mutation-capable-built-in-tools P07C | 12m | 2 tasks | 2 files |
| Phase 11 P08 | 25min | 1 tasks | 3 files |
| Phase 11 P09 | 35min | 2 tasks | 9 files |
| Phase 11 P10 | 25min | 4 tasks | 19 files |
| Phase 11-mutation-capable-built-in-tools P11 | 20min | 2 tasks | 10 files |
| Phase 11 P12 | 8min | 2 tasks | 3 files |
| Phase 11 P13 | 12min | 2 tasks | 4 files |
| Phase 12 P01 | 16 min | 3 tasks | 12 files |
| Phase 12 P02 | 23 min | 2 tasks | 9 files |
| Phase 12 P03 | 10 min | 2 tasks | 5 files |
| Phase 12 P04 | 38 min | 3 tasks | 9 files |
| Phase 12 P05 | 43 min | 3 tasks | 17 files |
| Phase 12 P06 | 29 min | 3 tasks | 5 files |
| Phase 13 P13-01 | 25min | 2 tasks | 11 files |
| Phase 13 P13-02 | 25min | 2 tasks | 4 files |
| Phase 13 P03 | 9m | 2 tasks | 11 files |
| Phase 13 P13-04 | ~10m | 2 tasks | 4 files |
| Phase 13 P13-06 (gap) | ~22m | 2 tasks | 2 files |
| Phase 13.1 P01 | 25min | 2 tasks | 10 files |
| Phase 13.1 P02 | ~20min | 2 tasks | 4 files |
| Phase 13.1 P03 | ~25min | 2 tasks | 10 files |
| Phase 13.1 P04 | ~15min | 2 tasks | 6 files |
| Phase 13.1 P05 | ~30min | 3 tasks | 1 file |
| Phase 13.1 P06 | ~50min | 2 tasks | 8 files |
| Phase 13.1 P07 | ~25min | 2 tasks | 5 files |
| Phase 14 P01 | ~15min | 4 tasks | 13 files |
| Phase 14 P02 | ~15min | 4 tasks | 9 files |
| Phase 14 P03 | 34min | 4 tasks | 19 files |
| Phase 14 P04 | 29min | 5 tasks | 16 files |
| Phase 14 P05 | 23min | 3 tasks | 10 files |
| Phase 14 P06 | 17min | 6 tasks | 10 files |
| Phase 14 P07 | 46min | 3 tasks | 8 files |
| Phase 14 P08 | 2h 29m | 5 tasks | 6 files |
| Phase 15 P01 | ~30min | 2 tasks | 6 files |
| Phase 15 P02 | 35min | 2 tasks | 8 files |
| Phase 15 P03 | ~95min | 2 tasks | 12 files |
| Phase 15 P04 | ~75min | 3 tasks | 8 files |

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260427-9ci | Add a read-only Jmix admin UI view to inspect the current AI baseline context generated by BaselineContextProvider.renderAsText, including agent.entities and agent.permissions, with refresh only and no editing | 2026-04-26 | 6a39184 | [260427-9ci-add-a-read-only-jmix-admin-ui-view-to-in](./quick/260427-9ci-add-a-read-only-jmix-admin-ui-view-to-in/) |

## Session Continuity

**Last session:** 2026-05-12T00:30:00.000Z
**Stopped at:** Completed 15-04-PLAN.md
**Resume file:** None
**Blockers:** Pre-existing Phase 11/13 Spring-context boot regression (atmosphere-runtime / agentstoreEntityManagerFactory) still affects module-level @SpringBootTest classes; documented in .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md. v1.2 phases should prefer XML/source-scan or pure-Mockito tests for UI/contract coverage where the boot context is implicated.
**Working-tree changes (uncommitted) carried from the v1.1.0 close session:** docker-compose.yml + docker/postgres/init/01-init-databases.sh (local pgvector Postgres on host port 5432); jmix-app application-local.properties (new — `--spring.profiles.active=local` overrides datasource URLs to localhost:5432; application.properties itself UNCHANGED). Plus pre-existing 14-11 WIP (cancel control, transcript-leak fix, ambiguous-count rules, full-page prefill source-conversation). See 14-HUMAN-UAT.md "Session Handoff - 2026-05-11 (UAT COMPLETE)". (Note: local dev runs on http://localhost:8088 — see memory project_local_dev_port; never auto-start bootRun.)
**Next action:** `/gsd-execute-phase 15` to run Plan 15-05 (TEST-19 — the Phase-9 leak-regex test over the rendered status text + the per-turn Details rows; plus the NoNewPersistedStateTest / AiChatSessionStateTest invariants), then phase verification. (Phases 16+ independent; 17 best scoped after 16; **18 must precede 19**; voice input is Phase 20, last — its STT error/retry row reuses Phase 15's in-fragment status-row pattern.)

## Operator Next Steps

- Plan the first v1.2 phase with `/gsd-plan-phase 15` (or 16 — both independent). When you reach Phase 20 (Soniox STT, last in the milestone), consider `/gsd-research-phase 20` first (Soniox/OpenAI STT API shapes are MEDIUM-confidence).
