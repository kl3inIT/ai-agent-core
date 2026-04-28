---
gsd_state_version: 1.0
milestone: v1.1.0
milestone_name: milestone
status: phase_11_ready_to_execute
stopped_at: Plan 11-01 complete; ready for Plan 11-02
last_updated: "2026-04-28T20:23:41.054Z"
progress:
  total_phases: 6
  completed_phases: 2
  total_plans: 31
  completed_plans: 20
  percent: 65
---

# Project State

**Last updated:** 2026-04-28

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-04-26 — v1.1.0 milestone started)

**Core value:** Drop the add-on into a Jmix app and end-users can safely converse with their data and documents on day one — no agent framework code written by the host team.

**Current focus:** Phase 11 — Mutation-Capable Built-In Tools

## Current Position

Phase: 11 (Mutation-Capable Built-In Tools) — EXECUTING
Plan: 4 of 14
| Field | Value |
|-------|-------|
| Phase | Phase 11 |
| Plan | 0 of 11 complete |
| Status | Ready to execute |
| Last activity | 2026-04-28 — Phase 11 plans revised with review feedback |

## Phase Status

| Phase | Status | Plans Complete | Started | Completed |
|-------|--------|----------------|---------|-----------|
| 9. Tool-Layer Foundations & Prompt-Contract Hardening | Complete | 7/7 | 2026-04-27 | 2026-04-27 |
| 10. AI-Specific LLM Exposure Policy | Shipped | 10/10 | 2026-04-27 | 2026-04-28 |
| 11. Mutation-Capable Built-In Tools | Ready to execute | 0/11 | 2026-04-28 | - |
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

### Decisions

- 2026-04-27 (Plan 09-01): AUD-07 plumbing (`AuditFieldHasher` + `AiAgentAuditProperties`) shipped with intentional zero callers per CONTEXT D-18. Phase 11 `MutationErrorTranslator` is the planned consumer. SHA-256 over UTF-8 byte encoding (locale-independent), lowercase 64-char hex via `java.util.HexFormat`. No SPI extraction — deferred until a host requests non-SHA-256 hashing.
- 2026-04-27 (Plan 09-01): Spring config defaults landed in `module.properties`, NOT in `default-params.yaml` (which is strict `AiParameters` seed YAML). Planner-review carve-out honored.
- [Phase ?]: Plan 09-02: Locked the Phase-9 SPI contract surface — ToolFetchPlanCustomizer (D-09 signature) + FetchPlanContext concrete request snapshot + SpiDefaultsAutoConfiguration no-op default. FetchPlanContext does NOT carry RunContext (per D-10 review correction: RunContext is final + private constructor + static accessors). Verbatim TOOL-11 phrase 'fetch plan is projection, not security.' authored at the SPI seam; Plan 09-04 will repeat the phrase at the FetchPlanIntersector consumer seam.
- [Phase ?]: 2026-04-27 (Plan 09-03): BaselineContextProvider emits agent.entities + agent.permissions per chat turn from CurrentUserSchemaAccess + AccessManager + MessageTools. agent.permissions is locale-invariant by construction (P-8): TreeMap entity-keys + LinkedHashMap r,u,c,d,modifiable order + TreeSet attribute iteration; only agent.entities carries locale-resolved labels (parenthesized suffix). Same sorted/capped entity list drives both blocks. Phase 10 LlmExposurePolicy substitution is a single-line swap of the getReadableSchema() call site.
- [Phase ?]: 2026-04-27 (Plan 09-04): FetchPlanResolver + FetchPlanIntersector landed in com.vn.agent.tools.fetchplan; verbatim TOOL-11 phrase exposed as public constant FetchPlanIntersector.PROJECTION_NOT_SECURITY_COMMENT and referenced from class Javadoc via {@value}. PLAN_NARROWED: greppable audit-prefix with AiToolCallOutcome.FLAGGED — no new outcome enum value. describe_entity widened via MetadataTools (no raw reflection); ToolResultFormatter.records emits literal PROMPT-04 envelope <data entity><label></data> with label first via MessageTools.getEntityCaption. UNKNOWN_ENTITY_HINTS verbatim D-14 with em dash preserved on hint #3. Phase 3 D-08 access_denied opacity preserved (Phase 10 will unify, not Phase 9).
- [Phase ?]: 2026-04-27 (Plan 09-05): Output-scanner Phase 9 pattern packs (HOST_PREFIX_LEAK / TOOL_NAME_LEAK) shipped as @Component providers with startup snapshot at ApplicationReadyEvent + lazy-fallback build in asPattern() (eager-singleton ordering safety). OutputScannerAdvisor widened to implement CallAdvisor + StreamAdvisor; streaming uses ChatClientMessageAggregator. Pattern.quote per token (T-09-22 ReDoS). Default-on toggles in module.properties.
- [Phase ?]: 2026-04-27 (Plan 09-05): AgentSystemPromptRules.PROMPT_RULES carries verbatim PROMPT-03 vocabulary rules + D-15 retry contract whose three hint substrings match BuiltInDataTools.UNKNOWN_ENTITY_HINTS BYTE-FOR-BYTE (em dash U+2014 preserved). Lowercase 'if' bullets sacrificed sentence-case to keep the cross-assertion green for TEST-08 in Plan 09-06. Constant lives in com.vn.agent.guard alongside OutputScannerAdvisor (both leak-prevention). DefaultChatServiceImpl wires PROMPT_RULES at BOTH composition sites (blocking ask + streaming stream) so rules apply on every turn regardless of transport mode and even when profile prompt is blank. Hardcoded English (no i18n) per RESEARCH Pitfall 7 — model-directed instructions, not user-facing UI.
- [Phase ?]: Plan 09-06 (TEST-08): cross-locale prompt-contract regression suite landed; Phase 9 feature-complete
- [Phase ?]: Plan 10-01: AiExposureRule (entity-level only, no attributePath) + AiExposureRuleMode (EXCLUDE only) in com.vn.agent.exposure. Liquibase 060+061 auto-loaded. ChunkMetadata.SOURCE_ENTITY=source_entity constant for EXP-05 NOT IN denylist (Plan 10-05 consumer).
- [Phase ?]: Plan 10-02: dataManager.load(EntityClass).query() auto-resolves store from @Store annotation; .store() chain method only applies to raw-JPQL loadValue paths
- [Phase ?]: Plan 10-02: LlmExposurePolicy.canModify ships unused in Phase 10; Phase 11 mutation gating wires it before DataManager.save
- [Phase 10]: Plan 10-03: AiAgentAdminRole extended with @EntityPolicy AiExposureRule + menu/view IDs for AiExposureRule list/detail and VectorStoreDebug; pure additive (zero existing policies removed); SEC-05 partially complete (AiUiSettings policies will fully close in Phase 12)
- [Phase ?]: Plan 10-04: Mechanical call-site swap complete — BaselineContextProvider, BuiltInDataTools, FetchPlanIntersector inject LlmExposurePolicy instead of CurrentUserSchemaAccess. Fix R4 unification: ALL canReadEntity()==false branches in BuiltInDataTools throw unknown_entity (not access_denied) — full opacity per EXP-09 + Phase 3 D-08. Fix R5: FetchPlanIntersector routes both canReadAttribute AND canReadEntity through the policy. UNKNOWN_ENTITY_HINTS byte-for-byte preserved (em dash U+2014). ToolQueryCountBaselineTest recalibrated for D-14 no-cache: list_entities/describe_entity ceiling raised from 0 to 5 SELECTs to absorb the per-call agentstore policy lookup.
- [Phase ?]: Plan 10-05: RetrievalFilterBuilder applies defensive (source_entity IS NULL) OR (NOT IN <denied>) for non-empty denylist (Fix R6); AsyncIngestionWorker.enrich mirrors sourceEntityName to ChunkMetadata.SOURCE_ENTITY when non-null. Legacy chunks unaffected until reingested (D-06).
- [Phase ?]: Plan 10-06: Toggle save uses UnconstrainedDataManager and Fix R2 enforced — view does NOT inject ApplicationEventPublisher; AiExposureRuleEntityListener remains the single LlmExposureChangedEvent publish site
- [Phase ?]: Plan 10-06: MetaclassComboBoxHelper extracted as shared @Component for reuse across exposure detail view (10-07) and KB upload form (10-08); single source of truth for @SystemLevel + AI-* internals exclusion
- [Phase ?]: Plan 10-07: detail view reuses MetaclassComboBoxHelper; ComboBox<MetaClass> value-bridged in controller; ReadyEvent for pre-select; 10-08 reingest error keys shipped ahead in Group B; menu uses <item>; EN bundle is messages_en.properties
- [Phase ?]: Plan 10-09: VectorStoreDebugView shipped — plain Vaadin Grid<Document> (Fix R7) over VectorStore.similaritySearch (empty query, topK=100, threshold=0.0); FilterExpressionTextParser with inline setErrorMessage on parse error; metadataFilterField is TypedTextField<String> (Fix W2); 3 programmatic addColumn calls; expand uses standard Vaadin Dialog (Document is Spring AI POJO, no Jmix metaclass); read-only — no edit/delete per CONTEXT D-09
- [Phase ?]: Plan 10-08: KB upload sourceEntityName persisted BEFORE dataManager.save (D-07 invariant); KnowledgeDocumentService.updatePermissionsAndReingest returns UpdatePermissionsResult enum so view has zero business logic (CLAUDE.md compliant); 3-arg upload overload preserved for backward compatibility with IngesterManager + tests
- [Phase ?]: Plan 10-10: TEST-09 four-path uniform-opacity gate landed (RetrievalFilterBuilderDenylistTest unit + LlmExposurePolicyIntegrationTest integration). Two-tier RAG filter coverage. Phase 10 complete (10/10 plans).
- [Phase ?]: Plan 11-01: AiMutationIntent agentstore entity ships with composite unique index on (TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME) + REQUEST_HASH + STATUS_ enum (PENDING/COMMITTED/FAILED/COMMIT_UNKNOWN). PENDING reserved before host save so DB unique index serializes duplicates. COMMIT_UNKNOWN parks post-save finalization failures. AiAgentMutationRole is empty marker (no AiMutationIntent READ to avoid leaking idempotency keys); AiAgentAdminRole gains @EntityPolicy(AiMutationIntent, ALL); both locale bundles updated. IDX_AI_MUT_INTENT_STATUS added beyond plan baseline for cleanup-job diagnostics.
- [Phase ?]: Plan 11-02: AiAgentMutationProperties (@ConfigurationProperties ai-agent.tools.mutation) record + AiToolCallOutcome enum extension (IDEMPOTENT_REPLAY/COMMIT_FAILED via EnumClass<String>, no schema migration) + @EnableScheduling on AIConfiguration. Rule 3 auto-fix: AiAuditEventDetailDialog.outcomeTheme switch extended with the 2 new cases. Rule 2 auto-fix: bilingual auditList.outcome.* lowercase keys added alongside metaclass-format keys (AiAuditEventListView+DetailDialog use lowercase convention).
- [Phase ?]: MutationIntent attributes use Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) NOT Map.copyOf — null attribute values represent optional-field clears
- [Phase ?]: MutationGuard default no-op bean lives directly in AIConfiguration via @ConditionalOnMissingBean — no separate SpiDefaultsAutoConfiguration class (mirrors aiAgentIngestExecutor precedent)
- [Phase ?]: ToolVetoedException reused verbatim for MutationGuard veto path — no new exception type

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

### Quick Tasks Completed

| # | Description | Date | Commit | Directory |
|---|-------------|------|--------|-----------|
| 260427-9ci | Add a read-only Jmix admin UI view to inspect the current AI baseline context generated by BaselineContextProvider.renderAsText, including agent.entities and agent.permissions, with refresh only and no editing | 2026-04-26 | 6a39184 | [260427-9ci-add-a-read-only-jmix-admin-ui-view-to-in](./quick/260427-9ci-add-a-read-only-jmix-admin-ui-view-to-in/) |

## Session Continuity

**Last session:** 2026-04-28T20:23:27.879Z
**Stopped at:** Plan 11-01 complete; ready for Plan 11-02
**Resume file:** None
**Blockers:** None.
**Next action:** Execute Phase 11.
