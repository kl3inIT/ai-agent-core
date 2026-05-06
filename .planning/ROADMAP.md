# Roadmap — v1.1.0 Prompt Hardening, Mutation Tools & Configurable Chat Surfaces

**Milestone:** v1.1.0
**Granularity:** coarse
**Total phases:** 8 (Phase 9 → Phase 15, plus follow-up Phase 13.1)
**Phase numbering:** continues from v1.0.0 (which ended at Phase 8 plus inserted 7.1, 7.2). v1.1 starts at Phase 9. Phase 13 was rewritten 2026-05-05 (STT split out to a new Phase 15; Phase 13 retitled to Chat Task File — Attach + LLM Read + Bulk Save).
**Coverage:** 100% of v1.1 active REQ-IDs mapped (Future Requirements and Out of Scope intentionally NOT mapped).

**Previous milestone:** v1.0.0 MVP shipped 2026-04-26
**Archive:** [v1.0.0-ROADMAP.md](milestones/v1.0.0-ROADMAP.md)
**Phase history:** [v1.0.0-phases/](milestones/v1.0.0-phases/)

## Hard Build-Order Constraints

- Phase 9 → must precede everything (lays `agent.entities` / `agent.permissions` baseline + richer tool DTOs).
- Phase 10 → depends on Phase 9; must precede Phase 11 (admin must be able to deny mutation per entity below user permission level before opt-in).
- Phase 11 → hard-depends on Phase 9 + Phase 10.
- Phase 12 → independent of 10/11 but depends on Phase 9; sequenced after to avoid interleaving UI refactor with security work.
- Phase 13 → depends on Phase 9 + Phase 11 (`bulk_save_records` extends Phase 11 `MutationSaveExecutor`) + Phase 12 (`ChatPanelFragment.attachmentsPanel` slot is the integration point); independent of 10.
- Phase 13.1 → hard-depends on Phase 13 (modifies `AiTaskFile`, `AiTaskFileMediaResolver`, `chat-panel-fragment.xml`, `DefaultChatServiceImpl` two-phase write); independent of 14/15. UI-only + lifecycle change; no new entity, no new tool surface.
- Phase 14 → depends on Phase 9 and Phase 10; may consume `AiTaskFile` (Phase 13/13.1) by id for single-record extraction.
- Phase 15 → depends on Phase 12 (`ChatPanelFragment.messageInputSlot` integration point); independent of 10/11/13/14. Sequenced last in v1.1 (lowest priority — voice input is a "nice to have").

## Phases

- [x] **Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening** — Richer `describe_entity`, fetch-plan SPI, baseline `agent.entities` + `agent.permissions`, `unknown_entity` retry contract, output-scanner pattern additions.
- [x] **Phase 10: AI-Specific LLM Exposure Policy** — `AiExposureRule` (`EXCLUDE`-only) + `LlmExposurePolicy` boundary; admin Flow UI; RAG cross-cut. (completed 2026-04-28)
- [x] **Phase 11: Mutation-Capable Built-In Tools** — `BuiltInMutationTools` (default OFF), `MutationGuard` SPI, `AiMutationIntent` idempotency, layered fail-closed gating, audit reuse via `writeToolCall`. (completed 2026-04-29)
- [x] **Phase 12: Configurable Chat Surfaces** — `FULL_ROUTE` + `HEADER_BUTTON` Jmix `DialogWindow` surfaces over one `ChatPanelFragment`; `AiUiSettings` admin toggle; `AiChatSessionState` continuity. (completed 2026-05-02)
- [x] **Phase 13: Chat Task File — Attach + LLM Read + Bulk Save** — `AiTaskFile` transient entity (separate from KB ingestion); UI attach affordance in `attachmentsPanel`; Spring AI `Media` injection (single-turn, jmix-crm pattern); `bulk_save_records` tool extending Phase 11 `MutationSaveExecutor`; default chat model swap to multimodal `qwen/qwen3.6-35b-a3b` (Apache 2.0 self-hostable).
 (completed 2026-05-06)
- [ ] **Phase 13.1: Chat Attachments — CRM-Style Right-Pane + Persistent Multi-Turn Context (FOLLOW-UP)** — Replace Phase 13 chip-strip with verbatim port of jmix-crm Attachments right-pane (card grid + drop-zone + empty state); change `AiTaskFileMediaResolver` from single-turn to per-turn-all (with token-budget cap); extend `AiTaskFile` lifetime to conversation-scoped (or 24h TTL, configurable); add inline "[user] added attachment" ledger row in message stream. Driver: post-Phase-13 UX review confirmed CRM right-pane is more discoverable + per-turn-all `Media` is required for "AI agent thực thụ" multi-turn follow-up.
- [ ] **Phase 14: Intent-Driven Extraction → Form Prefill** — Persisted `AiExtractionDraft`; `IntentExtractor<T>` SPI; `prepare_form_draft` tool returning structured payload; controller-side navigation only.
- [ ] **Phase 15: Chat Voice Input — Soniox STT** — Browser `MediaRecorder` capture (webm/opus or mp4, no transcoding) + custom Spring `RestClient` Soniox provider (`/v1/files` + `/v1/transcriptions`, `Authorization: Bearer`); `TranscriptionService` strategy interface (`SonioxTranscriptionService` default + optional `SpringAiTranscriptionService` OpenAI fallback); `TranscriptionPostProcessor` SPI; STT_TRANSCRIPTION audit via `writeToolCall`.

## Phase Details

### Phase 9: Tool-Layer Foundations & Prompt-Contract Hardening
**Goal**: The LLM sees a richer, deterministic, leakage-resistant view of the host schema and tool surface — without any new entity, SPI registration into mutation chain, or behavioral change to data-access policy.
**Depends on**: Nothing (first phase of v1.1; builds purely additively on shipped v1.0)
**Requirements**: PROMPT-01, PROMPT-02, PROMPT-03, PROMPT-04, PROMPT-05, PROMPT-06, TOOL-09, TOOL-10, TOOL-11, TOOL-12, SPI-09, TEST-08, AUD-07 (partial — hash-sensitive-fields plumbing prepared; mutation-specific use lands in Phase 11)
**Success Criteria** (what must be TRUE):
  1. The system prompt that ships to the LLM contains an alphabetically sorted `agent.entities` block (`name (label)` lines, sourced from the post-narrowed schema) and a compact `agent.permissions` map keyed by entity with CRUD bits and per-attribute `modifiable` set; locale-sensitive labels are not part of the cache key.
  2. `describe_entity` returns Jmix-metadata-derived attribute fields (comment, attributeType, cardinality, mandatory, readOnly, persistent, transient, isPrimaryKey, enumValues, relationshipTarget, maxLength) and an entity-level comment, all via `MetadataTools` (no raw reflection); excluded fields are documented inline.
  3. A user-facing chat reply to a question about host data does not contain internal entity-name prefixes (e.g. `<host_prefix>_<name>`) or raw built-in tool names; `OutputScannerAdvisor` flags such leaks (audit, no hard block) and the prompt-contract test (TEST-08) asserts the absence in Vietnamese and English locales.
  4. When the LLM calls a tool with an unknown entity name, the tool returns a structured `unknown_entity` error carrying an `expected` action hint; the LLM is instructed (and observably) calls `list_entities` exactly once and either retries with the corrected name or tells the user no such entity exists.
  5. Hosts can register a `ToolFetchPlanCustomizer` SPI bean that overrides `_base` / `_instance_name` per `(toolName, MetaClass)` at runtime; the resolved plan is intersected with `AccessManager`-allowed attributes (host plan cannot widen the projection beyond user attribute permissions); the comment in code states "fetch plan is projection, not security."
**Plans:** 6 plans
- [x] 09-01-PLAN.md — AUD-07 plumbing: AuditFieldHasher static utility + AiAgentAuditProperties (no callers wired in Phase 9)
- [x] 09-02-PLAN.md — ToolFetchPlanCustomizer SPI + FetchPlanContext record + no-op default in SpiDefaultsAutoConfiguration
- [x] 09-03-PLAN.md — BaselineContextProvider extension: agent.entities + agent.permissions (locale-free cache key) + AiAgentPromptProperties
- [x] 09-04-PLAN.md — Tool-layer changes: FetchPlanIntersector + FetchPlanResolver wiring, describe_entity TOOL-09 widening, PROMPT-04 records wrapper, unknown_entity D-14 hints
- [x] 09-05-PLAN.md — Output scanner pattern packs (HostPrefixPatternProvider + ToolNamePatternProvider) + AgentSystemPromptRules + DefaultChatServiceImpl rule wiring
- [x] 09-06-PLAN.md — TEST-08 prompt-contract regression: PromptContractMockTest (EN+VI parameterized) + PromptContractLiveTest (@Tag("live"))
- [x] 09-07-PLAN.md — Gap closure: exact entityName tool-call guidance from agent.entities/list_entities

### Phase 10: AI-Specific LLM Exposure Policy
**Goal**: Admin can narrow the LLM-visible surface (entities and attributes) below the user's Jmix permissions through a single denylist-only governance layer; the policy is uniformly enforced across schema discovery, tool calls, baseline prompt, and RAG.
**Depends on**: Phase 9 (`agent.entities` / `agent.permissions` baseline keys must already source through a single boundary so the new policy can substitute the source without churning call sites)
**Requirements**: EXP-01, EXP-02, EXP-03, EXP-04, EXP-05, EXP-06, EXP-07, EXP-08, EXP-09, EXP-10, ENT-05, SEC-05 (extension covering AiExposureRule; AiUiSettings admin policies finish in Phase 12), TEST-09
**Success Criteria** (what must be TRUE):
  1. Admin creates an `AiExposureRule` (entity-level, `mode=EXCLUDE` only — there is no `ALLOW` enum value) via `AiExposureRuleListView` / `AiExposureRuleDetailView` (gated to `AiAgentAdminRole`, with `genericFilter` + `propertyFilter`, action-column for enable/disable, and labels reading "Hide from AI" / "Visible to AI" — never "Allow"); the rule takes effect on the next chat turn. Note: `attributePath` field omitted per user decision 2026-04-27 (entity-level denylist only in v1.1).
  2. An entity that the current user can read in Jmix but is denylisted for the LLM does not appear in `list_entities`, `agent.entities`, RAG hits, or `find_records` responses; attempting to address it surfaces uniformly as `unknown_entity` (never `access_denied`), preserving denylist opacity.
  3. `BuiltInDataTools`, `BaselineContextProvider`, and `RetrievalFilterBuilder` all consult `LlmExposurePolicy` (composed as `userVisible AND NOT excluded`); `LlmExposureRuleRepository` uses `UnconstrainedDataManager` so user-role tweaks cannot bypass admin governance.
  4. Rule create/update/delete publishes `LlmExposureChangedEvent`; `AiAgentAdminRole` carries CRUD + view + menu policies for `AiExposureRule`.
**Plans:** 10/10 plans complete
- [x] 10-01-PLAN.md — Entity foundations: AiExposureRuleMode enum + AiExposureRule entity + ChunkMetadata.SOURCE_ENTITY + AiKnowledgeDocument.sourceEntityName + Liquibase changelogs 060/061
- [x] 10-02-PLAN.md — Service layer: LlmExposureChangedEvent + LlmExposureRuleRepository + AiExposureRuleEntityListener + LlmExposurePolicy
- [x] 10-03-PLAN.md — Security role extension: AiAgentAdminRole CRUD + view + menu policies for AiExposureRule + VectorStoreDebugView
- [x] 10-04-PLAN.md — Call-site swap: CurrentUserSchemaAccess → LlmExposurePolicy in BaselineContextProvider + FetchPlanIntersector + BuiltInDataTools
- [x] 10-05-PLAN.md — RAG integration: RetrievalFilterBuilder nin clause + AsyncIngestionWorker SOURCE_ENTITY metadata enrichment
- [x] 10-06-PLAN.md — AiExposureRuleListView: MetaclassComboBoxHelper + list view XML + Java with @Supply toggle renderer
- [x] 10-07-PLAN.md — AiExposureRuleDetailView: comboBox + checkbox + Java wiring + all ~53 message keys in both locales + menu.xml entries
- [x] 10-08-PLAN.md — KnowledgeBaseView extensions: sourceEntityName comboBox in upload form + editPermissions action + reingest action + sourceEntity column renderer
- [x] 10-09-PLAN.md — VectorStoreDebugView: filter input row + VectorStore.similaritySearch + FilterExpressionTextParser + expand dialog
- [x] 10-10-PLAN.md — Tests: RetrievalFilterBuilderDenylistTest (unit) + LlmExposurePolicyIntegrationTest (@SpringBootTest)
**UI hint**: yes

### Phase 11: Mutation-Capable Built-In Tools
**Goal**: Hosts can opt in to LLM-driven create / update / related-write operations that go through Jmix `DataManager`, are gated fail-closed by exposure policy + `AccessManager` (entity + per-attribute) + optional `MutationGuard` SPI, are idempotent, are audited end-to-end (including rollback), and never leak user-supplied PII through error strings.
**Depends on**: Phase 9 (richer permission inventory in baseline so the LLM knows what it can write); Phase 10 (admin must be able to deny mutation per entity below user permission level — opt-in is otherwise binary host-wide)
**Requirements**: MUT-01, MUT-02, MUT-03, MUT-04, MUT-05, MUT-06, MUT-07, MUT-08, MUT-09, MUT-10, MUT-11, MUT-12, ENT-09, AUD-06, AUD-07 (mutation-specific application: pre/post-image diff with optional PII hashing), SEC-07, SPI-10, TEST-10, TEST-11, TEST-12, TEST-13
**Success Criteria** (what must be TRUE):
  1. With default configuration, the boot test (TEST-13) asserts zero mutation tool callbacks present in `AgentToolCallbacks.forCurrentUser`; setting `ai-agent.tools.mutation.enabled=true` (a `@ConditionalOnProperty` flag) makes `create_record`, `update_record`, `add_related_record`, `remove_related_record` available — `delete_record` is reserved for v1.2 and remains absent even when mutations are enabled.
  2. A user with READ but not MODIFY on attribute `X` triggering `update_record(attribute=X)` is blocked at the per-attribute `EntityAttributeContext.canModify` step (TEST-10); the tool returns a stable structured error code (`access_denied`); `DataManager.save` is never called; the LLM never sees raw constraint or JPA exception text or user-supplied PII.
  3. Calling a mutation tool twice with the same `idempotencyKey` (mandatory `@ToolParam`) returns the original result with `outcome=IDEMPOTENT_REPLAY` (TEST-11); only one row is created/updated in the database; both calls are audited via `AuditWriter.writeToolCall` (no new `AuditKind`); known `DataManager.save` rollback failures still write a durable `ERROR` audit row, while post-host-save idempotency finalization failures write `outcome=COMMIT_FAILED` and leave the intent non-reclaimable (TEST-12).
  4. The layered fail-closed gating chain runs in order on every mutation call: `AiAgentMutationRole` marker → `LlmExposurePolicy.canModify` → `AccessManager` `CrudEntityContext` + per-attribute `EntityAttributeContext.canModify` → pre-host-save idempotency reservation → type coercion + writable-property validation → optional `MutationGuard` SPI → `@Transactional` `DataManager.save` (regular `DataManager`, never `UnconstrainedDataManager`); a host `MutationGuard` veto raises `ToolVetoedException` and aborts before save.
  5. Locale message keys for every denial / success / idempotency / error path are present in all locale bundles; new audit `eventName` strings (`create_record`, `update_record`, `add_related_record`, `remove_related_record`) and new `outcome` values (`IDEMPOTENT_REPLAY`, `COMMIT_FAILED`) are observable on `AiAuditEvent` rows; `AiMutationIntent` dedup table honors a 24h TTL by default.
**Plans:** 16/16 plans complete

**Wave 1**
- [x] 11-01-PLAN.md — Foundation: AiMutationIntent entity/status + Liquibase 070 + AiInternalEntityNames + AiAgentAdminRole + AiAgentMutationRole + locale captions
- [x] 11-02-PLAN.md — AiAgentMutationProperties @ConfigurationProperties + AiToolCallOutcome enum extension + @EnableScheduling
- [x] 11-03-PLAN.md — MutationGuard SPI + MutationIntent record + default no-op bean

**Wave 2 (blocked on Wave 1 completion)**
- [x] 11-04-PLAN.md — ToolEntityResolver shared @Component + operation-specific LlmExposurePolicy canCreate/canUpdate gates + BuiltInDataTools delegation
- [x] 11-05-PLAN.md — MutationIntentRepository reservation/replay with requestHash/status + MutationIntentCleanupJob @Scheduled hourly
- [x] 11-06-PLAN.md — MutationErrorTranslator (6 stable error codes, converter-code remapping) + locale captions

**Wave 3 (blocked on Wave 2 completion)**
- [x] 11-07-PLAN.md — Reference contract for split BuiltInMutationTools implementation (do not execute as a monolith)
- [x] 11-07A-PLAN.md — BuiltInMutationTools create/update core + DiffSerializer + MutationRequestHasher + MutationSaveExecutor
- [x] 11-07B-PLAN.md — Related-write metadata helpers + add_related_record/remove_related_record
- [x] 11-07C-PLAN.md — Commit-state, replay, non-throwing audit, and locale hardening
- [x] 11-08-PLAN.md — BuiltInLinkTools always-on: 2 @Tool methods over ViewRegistry + ServerProperties

**Wave 4 (blocked on Wave 3 completion)**
- [x] 11-09-PLAN.md — AgentToolCallbacks wiring without duplicate mutation audit + conditional AgentSystemPromptRulesComposer + ToolNamePatternProvider built-in scanner coverage

**Wave 5 (blocked on Wave 4 completion)**
- [x] 11-10-PLAN.md — Core tests: fixture Liquibase, TEST-10 access gating, TEST-11 idempotency replay/violation/reservation, TEST-13 callback shape, mutation audit ownership

**Wave 6 (blocked on Wave 5 completion)**
- [x] 11-11-PLAN.md — Supporting tests: TEST-12 commit-failed audit, related-write security, link opacity, translator coverage, prompt rules, tool-name scanner coverage

**Wave 12 (gap closure; blocked on Wave 6 completion)**
- [x] 11-12-PLAN.md — Gap closure: widen `AiAuditEvent.OUTCOME` Java/Liquibase metadata and persist `IDEMPOTENT_REPLAY` audit regression
- [x] 11-13-PLAN.md — Gap closure: sanitize mutation boundary streaming/fallback audit arguments with sensitive-field hashing

**Cross-cutting constraints:**
- Mutation tools remain default-off and `delete_record` remains absent under every property combination.
- Host mutations use regular `DataManager` through `MutationSaveExecutor`; system-internal idempotency rows use `UnconstrainedDataManager`.
- Idempotency uses pre-save reservation with `REQUEST_HASH`/`STATUS_`; `AiMutationIntent` does not store full result JSON.
- Mutation callbacks are self-audited exactly once and are not wrapped by `ToolCallbackAuditDecorator`.

**Verification status:** passed in `11-VERIFICATION.md` on 2026-04-29 after gap closure plans `11-12-PLAN.md` and `11-13-PLAN.md`.

### Phase 12: Configurable Chat Surfaces
**Goal**: One `ChatPanelFragment`, one `ChatService`, one `AiConversation` per user-session, surfaced through two admin-toggleable presentations (`FULL_ROUTE` and `HEADER_BUTTON` dialog) with continuous conversation state across surface switches.
**Depends on**: Phase 9 (baseline + tool changes already shipped so UI work does not interleave with prompt-contract churn)
**Requirements**: SURF-01, SURF-02, SURF-03, SURF-04, SURF-05, SURF-06, SURF-07, SURF-08, SURF-09, SURF-10, ENT-06, TEST-14
**Success Criteria** (what must be TRUE):
  1. An admin opens `AiUiSettingsView` and toggles which surfaces are enabled (`FULL_ROUTE`, `HEADER_BUTTON`) plus the default surface; on next UI init/navigation the host shell renders only the enabled surfaces with no host-side code edits beyond the starter dependency.
  2. A user starts a conversation in the full `ChatView`, switches mid-session to the header-button `ChatDialogView`, and continues the same `conversationId`; the same JDBC-backed conversation/message history backs each turn and TEST-14 passes.
  3. The header button opens a non-modal Jmix `DialogWindow` anchored top-right (`65%` left, `5%` top, `35%` width, `75%` height, resizable/draggable), avoiding the deferred raw Vaadin bottom-right launcher and P-21 stacking mitigation.
  4. There is exactly one `AiConversation` row per active user-session conversation regardless of surface — `AiChatSessionState` (`@VaadinSessionScope`) carries the active id and reattaches to whichever fragment is mounted.
**Plans:** 6/6 plans complete

**Wave 1**
- [x] 12-01-PLAN.md — Scope-doc amendments + `AiChatSurface` / `AiUiSettings` / singleton settings service foundation

**Wave 2 (blocked on Wave 1 completion)**
- [x] 12-02-PLAN.md — `AiUiSettingsDetailView`, menu entry, admin role policies, settings access tests
- [x] 12-03-PLAN.md — `AiChatSessionState` / `AiChatUIState` + `ChatPanelFragment` continuity integration

**Wave 3 (blocked on Wave 2 completion)**
- [x] 12-04-PLAN.md — `ChatDialogView`, `ChatSurfaceMounter`, header-button dialog toggle, route/menu gating
- [x] 12-05-PLAN.md — Async conversation auto-title, pencil-edit override, hidden Phase 13 attachments slot

**Wave 4 (blocked on Wave 3 completion)**
- [x] 12-06-PLAN.md — TEST-14 cross-surface continuity, settings/title/i18n hardening tests, UAT checklist

**Cross-cutting constraints:**
- Phase 12 implements the locked two-surface scope (`FULL_ROUTE`, `HEADER_BUTTON`); `SIDEBAR`, raw Vaadin floating launcher, P-21 stacking mitigation, and compact mode are deferred.
- `AiUiSettings` is admin-managed configuration in `agentstore`, loaded no-cache through `UnconstrainedDataManager` for trusted system reads/writes and hidden from LLM-visible entity inventory.
- `AiChatSessionState` carries only the active conversation id/listeners; `CancellationRegistry` remains the active-run source of truth.
- All user-facing UI remains Jmix XML/controller based with all labels in both locale bundles.
**UI hint**: yes

### Phase 13: Chat Task File — Attach + LLM Read + Bulk Save
**Goal**: Users attach files (xlsx, pdf, docx, csv, png, jpg, …) to a chat turn; the LLM reads file content directly via Spring AI `Media` (multimodal Qwen3.6-35B-A3B) and acts on it through the existing Phase 9–11 tool surface plus a new `bulk_save_records` tool that persists multiple host entities in a single audited transaction. Pathway is structurally disjoint from KB ingestion (`IngesterManager` / `VectorStore`).
**Depends on**: Phase 9 (`agent.entities` / `agent.permissions` baseline) + Phase 11 (`MutationSaveExecutor`, `MutationGuard`, `AiMutationIntent`, `MutationErrorTranslator`, `AuditWriter.writeToolCall`) + Phase 12 (`ChatPanelFragment.attachmentsPanel` slot, `AiChatSessionState`)
**Requirements**: ENT-07, TASK-01, TASK-02, TASK-03, TASK-04, TASK-05, SEC-06 (partial — read+create on own `AiTaskFile` rows; row-level draft policy completes in Phase 14), TEST-16. NEW for v1.1 (folded into this phase rewrite): `bulk_save_records` tool spec (extends MUT-* surface) and default-model swap to `qwen/qwen3.6-35b-a3b` (Apache 2.0 self-hostable, multimodal native, replaces `openai/gpt-4o-mini`).
**Success Criteria** (what must be TRUE):
  1. A user attaches one or more files to a chat turn via an "Attach" button + chip strip rendered in the existing Phase 12 `attachmentsPanel` slot (working in BOTH `FULL_ROUTE` `ChatView` and `HEADER_BUTTON` `ChatDialogView`); each upload persists exactly one `AiTaskFile` row to `agentstore` with `conversationId` (NOT NULL FK), `messageId` (NULLABLE FK to `AiMessage`, populated on send), filename, content type, size, storage ref, TTL (default 1h). `IngesterManager` is never invoked and `VectorStore` count is unchanged after attach (TEST-16).
  2. On user-message send, `AiTaskFileMediaResolver` returns rows where `messageId IS NULL` (newly attached, not yet sent), `DefaultChatServiceImpl` injects them as Spring AI `Media` into the user message (`chatClient.prompt().user(u -> u.media(...))`), and after the message is persisted, `UPDATE AiTaskFile SET messageId = newMessageId` for those rows. Subsequent turns receive an empty `Media` list (single-turn-only injection — matches `D:/DTH/jmix-crm` `AiAttachmentMediaResolver` pattern; aligns with Spring AI `JdbcChatMemoryRepository` text-only persistence).
  3. With `ai-agent.tools.mutation.enabled=true`, `bulk_save_records(entityName, records[], idempotencyKey)` runs ONE transaction through the Phase 11 chain (`LlmExposurePolicy.canCreate/canUpdate` → `AccessManager.applyRegisteredConstraints(CrudEntityContext)` per row → per-attribute `EntityAttributeContext.canModify` per row → `AiMutationIntent` reservation once per batch → type coercion → `MutationGuard` per row → `@Transactional DataManager.save` per row → `AuditWriter.writeToolCall` once per batch). Per-row dispatch: `id != null` → update, `id == null` → create. Per-row failure (validation / `AccessDeniedException` / `MutationGuard` veto) rolls back the entire batch; audit row records `outcome=FAILED` with `failedRowIndex`. Replay with the same `idempotencyKey` returns `IDEMPOTENT_REPLAY` and persists no additional rows. `requestHash` = SHA-256 over canonical JSON of records in submission order.
  4. Default chat model is swapped to `qwen/qwen3.6-35b-a3b` in `application.properties` (`jmix.ai-agent.defaults.model` and `spring.ai.openai.chat.options.model`); admin `AiParametersDetailView` (Phase 6) override surface is unchanged. Self-host constraint preserved: model is Apache 2.0; OpenRouter API endpoint stays for dev, swap `spring.ai.openai.base-url` for prod self-host (vLLM / Ollama).
**Plans:** 6/6 plans complete

**Wave 1**
- [x] 13-01-PLAN.md — AiTaskFile entity + Liquibase 090 + AiTaskFileProperties + role extensions + bilingual messages + default-model swap

**Wave 2 (blocked on Wave 1 completion)**
- [x] 13-02-PLAN.md — AiTaskFileMediaResolver (verbatim port) + AiTaskFileRepository + AiTaskFileCleanupJob + package-info TEST-16 invariant
- [x] 13-03-PLAN.md — MutationSaveExecutor.bulkSave + DiffSerializer extensions + bulk_save_records @Tool + AgentSystemPromptRules

**Wave 3 (blocked on Wave 2 completion)**
- [x] 13-04-PLAN.md — chat-panel-fragment.xml chip strip + ChatPanelFragment upload wiring + DefaultChatServiceImpl Media injection + markSent two-phase write

**Wave 4 (blocked on Wave 3 completion)**
- [x] 13-05-PLAN.md — TEST-16 dual enforcement (static + runtime) + resolver/cleanup/bulk_save_records integration tests + ROADMAP update
**UI hint**: yes
**Cross-cutting constraints:**
- STT (`STT-01..06`, `SPI-11`, `TEST-17`) is OUT of scope for Phase 13 — moved to a new Phase 15.
- No `ChatModelRouter` / dual-model routing — single multimodal model covers text and file turns. Defer dual-model split to a future phase if cost telemetry justifies.
- No `TaskFileContentExtractor` SPI / Apache POI / Tika server-side parser — Qwen3.6-VL reads xlsx/pdf/docx/png binaries natively. Add server-side extractor only if a host runs a text-only chat model.
- No admin list view for `AiTaskFile` rows in v1.1 — task files are transient; `AiAgentAdminRole` policy provisioned for future addition.
- `bulk_save_records` ships as create+update only; `bulk_delete_records` is reserved for v1.2 (destructive ops need separate UX with confirmation/undo).
- Per-row failure semantics: rollback-all only in v1.1; continue-on-error mode (sub-transaction per row) deferred until a host requests it.

### Phase 13.1: Chat Attachments — CRM-Style Right-Pane + Persistent Multi-Turn Context (FOLLOW-UP)
**Goal**: Re-skin and re-scope the Phase 13 attachments UX to match the verbatim jmix-crm pattern (right-pane Attachments panel with card grid + drop-zone + empty-state) and lift the single-turn `Media` injection so attached files remain in the agent's context across follow-up turns within the same conversation. Phase 13's chip-strip-above-MessageInput layout and TTL-1h ephemeral lifecycle proved undiscoverable in UX review and incompatible with the "AI agent thực thụ" multi-turn workflow (e.g. user uploads xlsx → asks for analysis → asks follow-up questions → triggers `bulk_save_records` — turns 2..N currently see EMPTY `Media` because Phase 13 REQ-4 only injects on the turn that ATTACHED the file). Both Phase 12 surfaces (`FULL_ROUTE` `ChatView` + `HEADER_BUTTON` `ChatDialogView`) ship the new layout — verified working in `D:/DTH/jmix-crm` `AiConversationDetailView` AND its dialog variant.
**Depends on**: Phase 13 (`AiTaskFile` entity + Liquibase 090 + `AiTaskFileMediaResolver` + `ChatPanelFragment` + `chat-panel-fragment.xml` + `DefaultChatServiceImpl` two-phase markSent + `AiAgentUserRowLevelRole`)
**Requirements**: UI-01, RES-01, AUDIT-01, LIFE-01, UX-01, SCHEMA-01, CONTRACT-01, TEST-16-PORT, I18N-01 (locked in 13.1-SPEC.md, 9 REQs)
**Success Criteria** (what must be TRUE):
  1. Both surfaces (`ChatView` + `ChatDialogView`) render an "Attachments" right-pane (vbox with header `Attachments`, body = `<gridLayout>` with `AiTaskFileCardFragmentRenderer` cards, footer = `<upload>` drop-zone with `Drop attachment here` text + `Upload` button) verbatim per `D:/DTH/jmix-crm/.../ai-conversation-detail-view.xml` + `ai-attachment-card-fragment.xml`. Empty state shows the Jmix logo + `There's nothing here yet...` text. Phase 13 chip-strip vbox + `attachRow` hbox are removed.
  2. `AiTaskFileMediaResolver.resolveActive(conversationId)` returns ALL non-expired `AiTaskFile` rows for the conversation (NOT just `messageId IS NULL`). `DefaultChatServiceImpl` injects all of them as `Media` on every turn, subject to a token-budget cap (`ai-agent.task-file.per-turn-max-files=10` and `ai-agent.task-file.per-turn-max-total-bytes=...` default 50 MB) — when cap exceeded, resolver returns the most recent N files (LRU by `createdAt DESC`) and emits an audit event `task_file_budget_exceeded` (no new `AuditKind`, reuses `writeToolCall`).
  3. `AiTaskFile` lifetime extends to **conversation-scoped** by default: rows are deleted when their `conversationId` is deleted (FK `ON DELETE CASCADE` already exists in Liquibase 090 — confirm) AND when the operator runs the cleanup job. Default TTL becomes `ai-agent.task-file.ttl-seconds=86400` (24h, was 3600). Operators can set `ttl-seconds=-1` for "no TTL, only delete on conversation delete". `AiTaskFileCleanupJob` and the opportunistic per-turn purge respect the new value.
  4. Each successful upload INSERTs an `AiMessage` of `MessageType=SYSTEM_NOTICE` (or extends the existing assistant message stream with an inline `attachmentEvent` row) reading `[<username>] added attachment "<filename>"` (bilingual via `messages.properties` / `messages_vi.properties`). The notice is rendered between message bubbles per the CRM screenshot. Notice rows are NEVER sent to the LLM (filtered out of `ChatMemoryRepository.findByConversationId` for prompt assembly). TEST-18 asserts: turn 1 attaches a 100KB xlsx, turn 2 sends "phân tích cột A", turn 3 sends "đếm số dòng" — all 3 outbound prompts contain the same `Media` for the xlsx; `JdbcChatMemoryRepository` returns 3 user messages + 3 assistant messages (notices not included).
  5. Two-phase `markSent` write from Phase 13 REQ-4 / 13-04-PLAN is **removed** (no more `messageId` UPDATE post-send). `AiTaskFile.messageId` column may be retained NULL for backward compat, marked deprecated in JavaDoc, or removed via a Liquibase 100 cleanup changelog (decision deferred to spec-phase). All rows for the conversation are "active" until expired or conversation deleted.
  6. `AiAgentUserRowLevelRole` row-level predicate continues to scope `AiTaskFile` to `userUsername = :current_user_username`; admin role unchanged. Per-turn-all resolver uses `UnconstrainedDataManager` per Phase 13 invariant (`feedback_jmix_unconstrained_for_system_writes`).
**Plans:** 7 plans

Plans:
- [ ] 13.1-01-PLAN.md — Wave 0 schema + config + enum: Liquibase 100 dropping MESSAGE_ID/INJECTED_AT, AiTaskFile field deletion, AiTaskFileProperties seconds-flip + per-turn caps, AiTaskFileCleanupJob sentinel, application.properties rename, AiMessageRole.NOTICE entry
- [ ] 13.1-02-PLAN.md — AiTaskFileMediaResolver per-turn-all + LRU budget cap + task_file_budget_exceeded audit; AiTaskFileRepository markInjected/loadPending removal
- [ ] 13.1-03-PLAN.md — ProjectingChatMemoryRepository NOTICE-survival JPQL fix, DefaultChatServiceImpl resolveActive wiring, UserMessagePersister deletion, DefaultChatServiceImplStreamFallbackTest rewrite
- [ ] 13.1-04-PLAN.md — chat-panel-fragment.xml CRM-style split reshape, ai-task-file-card-fragment.xml + AiTaskFileCardFragmentRenderer, ai-agent-chat.css CRM appendix, 13 bilingual chatView.attachments.* keys
- [ ] 13.1-05-PLAN.md — ChatPanelFragment Java rewire: taskFilesDl loader binding, empty-state toggle, NOTICE insert/render via vaadin-message.attachment-event, budget-exceeded toast, Phase 12 contract preservation
- [ ] 13.1-06-PLAN.md — Resolver/lifecycle/notice tests: PerTurnMediaInjectionTest (TEST-18), BudgetCapTest, TtlConfigTest, NoticeFilterTest + property-rename sweep on 3 existing Phase 13 tests + widened TEST-16 source-scanner scope
- [ ] 13.1-07-PLAN.md — UI/schema/locale tests: CrmStyleLayoutTest, NoticeRenderTest, SurfaceMountingTest, LiquibaseSchemaTest, LocaleParityTest

**UI hint**: yes
**Cross-cutting constraints:**
- This is a UX + lifecycle reshape, NOT a re-implementation. Reuse 100% of Phase 13 entity, Liquibase, role, FileStorage, MIME allowlist, audit, TEST-16 invariant. No changes to `bulk_save_records`, `MutationSaveExecutor`, `AgentSystemPromptRules`, default model, or `AiParameters`.
- TEST-16 (no `IngesterManager` / `VectorStore` touch) MUST continue to pass — port the static + runtime assertions verbatim.
- Token-budget cap is mandatory: per-turn-all without a cap will blow context on a multi-file conversation. Default cap (`per-turn-max-files=10`, `per-turn-max-total-bytes=52428800`) MUST be configurable; operators with larger context windows can raise it.
- No new audit `AuditKind` and no new role. Reuse `AuditWriter.writeToolCall` for the budget-exceeded event.
- Layout port from `D:/DTH/jmix-crm`: both `ai-conversation-detail-view.xml` AND its dialog opener (`AiConversationListView` lookup-action variant) are in scope as reference. Verify the dialog variant exists in CRM source before locking the spec — if not, spec-phase derives the dialog layout from the detail layout with width adjustments.
- Phase 12 fragment contract: the `attachmentsPanel` slot is repurposed (vbox-between-list-and-input → right-pane vbox). Layout shape changes from vertical-stack to hbox-split inside `chat-panel-fragment.xml`. Verify both surfaces still respect the Phase 12 `ChatSurfaceMounter` contract (no new mounter API needed — same fragment, different internal layout).
- Locale parity: every new message key (`Attachments`, `Drop attachment here`, `There's nothing here yet...`, `[<user>] added attachment "<file>"`, budget-exceeded notice) MUST land in BOTH `messages.properties` and `messages_vi.properties`.

### Phase 15: Chat Voice Input — Soniox STT
**Goal**: Users can dictate chat input via browser-recorded audio transcribed server-side through Soniox STT — text appears in `MessageInput` for review/edit before send; pathway is disjoint from the chat client and audit-privacy-safe by default.
**Depends on**: Phase 12 (`ChatPanelFragment.messageInputSlot` is the integration point and is stable from v1.0 + Phase 12 surface contract)
**Requirements**: STT-01, STT-02, STT-03, STT-04, STT-05, STT-06, SPI-11, TEST-17
**Success Criteria** (what must be TRUE):
  1. With `ai-agent.stt.enabled=true` and a Soniox API key (`ai-agent.stt.soniox.api-key`), the user clicks the mic button, records up to 60s via browser `MediaRecorder` (no transcoding — webm/opus or mp4 directly to `Authorization: Bearer` `POST /v1/files` then `POST /v1/transcriptions` with `model=stt-async-v4` + `language_hints: ["vi","en"]`), and the transcribed text appears in `MessageInput` for review/edit before send — `TranscriptionService` does NOT call `ChatService.ask` directly.
  2. `TranscriptionService` is a strategy interface with a default `SonioxTranscriptionService` impl (custom Spring `RestClient`-based — Soniox has no Java SDK) and an optional `SpringAiTranscriptionService` OpenAI-direct impl; selected via `ai-agent.stt.provider=soniox|openai|<custom-bean-name>` (default `soniox`). Hosts can register their own `TranscriptionService` bean and select it by bean name.
  3. By default the `STT_TRANSCRIPTION` audit row (via `AuditWriter.writeToolCall` `eventName=stt_transcription`, no new `AuditKind`) records duration, language, model, outcome, and SHA-256 transcript hash (NOT raw text); flipping `ai-agent.stt.audit.storeTranscript=true` stores raw transcript instead (TEST-17 covers both modes).
  4. STT failures (provider 4xx, recording too long, network) surface a non-blocking error message + retry button in the input area; the chat flow itself remains usable. An optional `TranscriptionPostProcessor` SPI bean rewrites transcripts (PII redaction / vocabulary normalization) before they reach the input field.
**Plans**: TBD
**UI hint**: yes
**Cross-cutting constraints:**
- Operator docs note: Soniox uses an independent API key from OpenAI/OpenRouter chat key. OpenRouter does NOT proxy `/audio/transcriptions`; if `provider=openai` is selected, OpenAI key is required directly.
- Soniox file/transcription resources are cleaned up via `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}` after retrieval.
- This phase is sequenced last in v1.1; deferral past v1.1 close into v1.2 is acceptable if other phases consume schedule.

### Phase 14: Intent-Driven Extraction → Form Prefill
**Goal**: The LLM produces a structured draft for a host entity (e.g. PDF → customer-draft), the user confirms via a chat-rendered button, and a Jmix detail view opens prefilled — all without giving the LLM any UI-mutation primitive and with `AccessManager` validating the eventual save exactly as for any human form submission.
**Depends on**: Phase 9 (richer baseline / structured tool outputs); Phase 10 (exposure policy gates which entities the extractor may target)
**Requirements**: EXTRACT-01, EXTRACT-02, EXTRACT-03, EXTRACT-04, EXTRACT-05, EXTRACT-06, EXTRACT-07, EXTRACT-08, EXTRACT-09, EXTRACT-10, ENT-08, SPI-12, TEST-15, SEC-06 (row-level read on own `AiExtractionDraft` rows completes here)
**Success Criteria** (what must be TRUE):
  1. With at least one named intent registered (the add-on ships one reference end-to-end intent plus the SPI for hosts), the user picks an intent, optionally attaches an `AiTaskFile`, and the LLM calls `prepare_form_draft(intentId, contextRefs)` exactly once — the tool returns a structured payload `{ "action": "open_form_with_draft", "draftId": "...", "entityName": "...", "instanceName": "..." }`; the LLM never receives a `ViewNavigators` or any UI-mutation primitive (TEST-15 grep / source-scanner gate).
  2. `ChatPanelFragment` recognizes the `open_form_with_draft` shape and renders an "Open form to confirm" button; clicking it (controller side) invokes `ViewNavigators.detailView(...).newEntity().withInitializer(...)` after `AccessManager.isPermitted(ViewContext)` passes; the Jmix detail view opens prefilled from `AiExtractionDraft.payloadJson`.
  3. The prefill applies via `DataContext.create(...)` and per-attribute `EntityAttributeContext.canModify`-gated `setValueIfPermitted` (never raw `setValue`); `dataContext.validate()` runs before Save; on Save the draft is deleted and a normal Jmix-secured save executes.
  4. `AiExtractionDraft` rows expire after TTL (default 1h, hourly cleanup job); each row is row-level-scoped to its owner `userUsername` (`AiAgentUserRole` row policy), persisted (not `VaadinSession`-cached), and survives navigation; `prepare_form_draft` invocations are audited via `AuditWriter.writeToolCall` with `eventName=prepare_form_draft`.
**Plans**: TBD
**UI hint**: yes

## Phase Dependency Graph

```
Phase 9 (foundations)
  ├── Phase 10 (exposure policy)
  │     ├── Phase 11 (mutation tools)
  │     │     └── Phase 13 (chat task file + bulk_save_records)
  │     │           └── Phase 13.1 (CRM-style right-pane + per-turn-all Media)
  │     └── Phase 14 (intent extraction)
  ├── Phase 12 (chat surfaces)
  │     ├── Phase 13 (attachmentsPanel slot)
  │     └── Phase 15 (messageInputSlot mic button)
  └── (Phase 14 also uses Phase 9)
```

Hard chain: 9 → 10 → 11. Phase 13 depends on 9 + 11 + 12 (mutation chain extension + UI slot). Phase 13.1 hard-depends on Phase 13 only (UX + lifecycle reshape; no new entity, no new tool surface). Phase 14 assumes 9 + 10 (and may consume Phase 13/13.1 `AiTaskFile` by id). Phase 15 assumes 12 only.

Sequence in v1.1: 9 ✓ → 10 ✓ → 11 ✓ → 12 ✓ → 13 ✓ → **13.1** → 14 → 15.

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 9. Tool-Layer Foundations & Prompt-Contract Hardening | 7/7 | Complete | 2026-04-27 |
| 10. AI-Specific LLM Exposure Policy | 10/10 | Complete   | 2026-04-28 |
| 11. Mutation-Capable Built-In Tools | 16/16 | Complete    | 2026-04-29 |
| 12. Configurable Chat Surfaces | 6/6 | Complete   | 2026-05-02 |
| 13. Chat Task File — Attach + LLM Read + Bulk Save | 6/6 | Complete    | 2026-05-06 |
| 14. Intent-Driven Extraction → Form Prefill | 0/0 | Not started | - |
| 15. Chat Voice Input — Soniox STT | 0/0 | Not started | - |

## Coverage Validation

All v1.1 active REQ-IDs in REQUIREMENTS.md are mapped to exactly one phase. Future Requirements (MUT-13, SURF-11, EXTRACT-11, EXP-11, dormant SEEDs) and Out of Scope items (PKG-05/TEST-07 carryover, collapsible tool-detail, autonomous agents, DELETE tool, etc.) are intentionally NOT mapped.

| Category | Count | Phase(s) |
|----------|-------|----------|
| PROMPT-01..06 | 6 | Phase 9 |
| TOOL-09..12 | 4 | Phase 9 |
| EXP-01..10 | 10 | Phase 10 |
| MUT-01..12 | 12 | Phase 11 |
| SURF-01..10 | 10 | Phase 12 |
| TASK-01..05 | 5 | Phase 13 |
| STT-01..06 | 6 | **Phase 15** (split out from Phase 13 on 2026-05-05) |
| EXTRACT-01..10 | 10 | Phase 14 |
| ENT-05 | 1 | Phase 10 |
| ENT-06 | 1 | Phase 12 |
| ENT-07 | 1 | Phase 13 |
| ENT-08 | 1 | Phase 14 |
| ENT-09 | 1 | Phase 11 |
| SPI-09 | 1 | Phase 9 |
| SPI-10 | 1 | Phase 11 |
| SPI-11 | 1 | **Phase 15** (TranscriptionPostProcessor — moved with STT) |
| SPI-12 | 1 | Phase 14 |
| AUD-06 | 1 | Phase 11 |
| AUD-07 | 1 | Phase 11 (plumbing prepared in Phase 9) |
| SEC-05 | 1 | Phase 10 (extends in Phase 12 for AiUiSettings) |
| SEC-06 | 1 | Phase 13 + Phase 14 (split: AiTaskFile in 13, AiExtractionDraft in 14) |
| SEC-07 | 1 | Phase 11 |
| TEST-08 | 1 | Phase 9 |
| TEST-09 | 1 | Phase 10 |
| TEST-10..13 | 4 | Phase 11 |
| TEST-14 | 1 | Phase 12 |
| TEST-15 | 1 | Phase 14 |
| TEST-16 | 1 | Phase 13 (task-file isolation) |
| TEST-17 | 1 | **Phase 15** (STT audit privacy — moved with STT) |

**No orphans. No duplicates.**

## Notes

- Phase numbering continues from v1.0.0 (which ended at Phase 8 plus inserted 7.1, 7.2). v1.1 starts at Phase 9 — no renumbering.
- Every phase that introduces a new entity (Phase 10: `AiExposureRule`; Phase 11: `AiMutationIntent`; Phase 12: `AiUiSettings`; Phase 13: `AiTaskFile`; Phase 14: `AiExtractionDraft`) bundles its Liquibase changelog (included in root `changelog.xml`), all-locale message bundle entries, and role-policy updates as part of the same phase, per CLAUDE.md.
- Mutation tools (Phase 11) ship default OFF via `@ConditionalOnProperty`; the boot test asserts zero mutation callbacks under default config. Phase 13 `bulk_save_records` joins under the same gate.
- Exposure policy (Phase 10) is `EXCLUDE`-only at the rule-shape level; UI labels read "Hide from AI" / "Visible to AI"; composition is `userVisible AND NOT excluded`. `attributePath` field omitted in v1.1 per user decision 2026-04-27 (entity-level denylist only).
- LLM never receives `ViewNavigators` or any UI-mutation primitive (Phase 14): controller renders the confirm card; controller navigates after `AccessManager.isPermitted(ViewContext)`.
- Audit reuses `AuditWriter.writeToolCall` end-to-end; no new `AuditKind`. New `eventName` strings (`bulk_save_records` Phase 13; `stt_transcription` Phase 15) and existing `outcome` values are the only audit surface changes.
- 2026-05-05 (v1.1 mid-milestone): Phase 13 scope was rewritten and STT split into a new Phase 15. Driver: user prioritized attach-file-then-LLM-process and bulk-save productivity over voice dictation, AND required a self-hostable open-weights model (Qwen3.6-35B-A3B Apache 2.0) instead of the original Spring AI `OpenAiAudioTranscriptionModel` (proprietary). Phase 13 now ships `AiTaskFileMediaResolver` (jmix-crm pattern), `bulk_save_records` (extends Phase 11 `MutationSaveExecutor`), and a default-model swap to Qwen3.6-35B-A3B. STT moves to Phase 15 with a Soniox-first provider strategy (custom Spring `RestClient`, no Java SDK).
- 2026-05-05: default chat model swap from `openai/gpt-4o-mini` → `qwen/qwen3.6-35b-a3b` is bundled into Phase 13 (multimodal native is required for the file-read deliverable). Embedding model unchanged (`qwen/qwen3-embedding-4b`).

## Backlog

### Phase 999.1: Phase 11 Mutation Hardening Follow-ups (BACKLOG)

**Goal:** Capture post-ship hardening for the mutation tool internals: refactor duplicated mutation gate sequencing, batch-load to-one FK references during mutation binding, and cache related-write metadata resolution where safe.
**Requirements:** TBD
**Plans:** 0 plans

Plans:
- [ ] TBD (promote with `$gsd-review-backlog` when ready)
