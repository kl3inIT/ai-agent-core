# Requirements — Jmix AI Copilot (ai-agent-core)

**Version:** v1.1.0 — Prompt Hardening, Mutation Tools & Configurable Chat Surfaces
**Last updated:** 2026-04-26 (milestone start)

## v1.1 Scope Summary

Enlarge the v1.0 read-only Copilot to mutation-capable, governance-aware, multi-surface chat — without churning the platform. Zero new core dependencies. Hard build-order: **tool/prompt foundations → exposure policy → mutation tools**; configurable surfaces / chat task input / intent-extraction sequence after foundations land.

REQ-IDs continue v1.0 conventions where the category exists (`TOOL-09…`, `AUD-06…`, `SPI-09…`, `TEST-08…`, `ENT-05…`); new categories (`PROMPT`, `MUT`, `EXP`, `SURF`, `STT`, `TASK`, `EXTRACT`) numbered from `01`.

## v1.1 Requirements

### Prompt-Contract Hardening

- [ ] **PROMPT-01**: `BaselineContextProvider.compose(...)` injects `agent.entities` — sorted alphabetical lines `name (label)`, sourced from `LlmExposurePolicy.getReadableSchema()` (post-narrowed). Truncation hint when entity count exceeds configurable threshold (default 50). Skipped when schema empty.
- [ ] **PROMPT-02**: `BaselineContextProvider.compose(...)` injects `agent.permissions` — compact map keyed by entity name with CRUD bits (`read`, `create`, `update`, `delete`) plus per-attribute `modifiable` set. Sourced from `LlmExposurePolicy + AccessManager`. Locale-sensitive labels NOT in the cache key (P-8 mitigation).
- [ ] **PROMPT-03**: System prompt rule explicitly forbids the LLM from using internal entity names (host-prefixed identifiers) and tool names (`find_records`, `RETRIEVAL`, …) in user-facing reply text. Prompt rule paired with the `agent.entities` inventory so the LLM has a label to use instead.
- [ ] **PROMPT-04**: `ToolResultFormatter.records(...)` wraps payloads as `<data entity="<label>" type="<internalName>">…` (label first). Row-level rendering puts `_instance_name` (Jmix human-friendly identifier) before raw key fields.
- [ ] **PROMPT-05**: `unknown_entity` retry contract: on `unknown_entity` tool error, the LLM must call `list_entities` exactly once and either retry with the corrected name or tell the user no such entity exists. No semantic guessing. `ToolErrorDto.expected` carries the next valid action hint.
- [ ] **PROMPT-06**: `OutputScannerAdvisor` configuration extended with patterns matching internal entity-name prefix (e.g. `\b<host_prefix>_\w+\b`) and tool-name leakage. Flag-and-audit posture preserved (no hard block on flag).

### Tool-Layer Refinements

- [ ] **TOOL-09**: `describe_entity` payload extended with selected Jmix metadata fields, sourced via `MetadataTools` (NOT raw reflection): entity-level `comment` (via `@Comment`), optional `ancestor`; attribute-level `comment`, `attributeType`, `cardinality`, `mandatory` (replaces inverted `nullable`), `readOnly`, `persistent`, `transient`, `isPrimaryKey`, `enumValues`, `relationshipTarget`, `maxLength`. Excluded fields documented inline.
- [ ] **TOOL-10**: `ToolFetchPlanCustomizer` SPI defined: `Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext ctx)`. Default impl returns `Optional.empty()` (current `_base` / `_instance_name` behavior preserved). Resolved by runtime `toolName + MetaClass`, NOT compile-time entity types. Add-on does NOT auto-discover from host `fetch-plans.xml`.
- [ ] **TOOL-11**: Override fetch plans pass an attribute-policy intersection — host plan cannot widen the projection beyond `AccessManager`-allowed attributes. Comment in code states: "fetch plan is projection, not security."
- [ ] **TOOL-12**: LLM permission inventory exposed at entity granularity in baseline (PROMPT-02) plus `describe_entity` per-attribute readability. Denied entity names are NOT revealed to the LLM (consistent with v1 "if access is denied, the model behaves as if the entity does not exist"); the inventory only lists entities the user is allowed to see.

### AI-Specific LLM Exposure Policy (SEED-007 activated)

- [ ] **EXP-01**: `AiExposureRule` Jmix entity in `agentstore` — `entityName` (Jmix metaClass name), `attributePath` (nullable; null = whole entity), `mode` enum with single value `EXCLUDE` (no `ALLOW` shape — prevents widening, P-6), `enabled`, audit fields. Liquibase changelog included in root.
- [ ] **EXP-02**: `LlmExposurePolicy` `@Component` wraps `CurrentUserSchemaAccess`; same method signatures (`getReadableSchema`, `canReadEntity`, `canReadAttribute`). Composition is `userVisible AND NOT excluded` (boolean AND, never OR).
- [ ] **EXP-03**: `BuiltInDataTools` migrated to consult `LlmExposurePolicy` instead of `CurrentUserSchemaAccess` directly (mechanical replacement at all call sites).
- [ ] **EXP-04**: `BaselineContextProvider` sources `agent.entities` and `agent.permissions` from `LlmExposurePolicy` (so denied entities never leak into the system prompt).
- [ ] **EXP-05**: `RetrievalFilterBuilder` integrates exposure policy — RAG retrieval excludes documents whose source-entity is denylisted, even when the user has Jmix read access. Admin denylist is leaky for KB docs without this.
- [ ] **EXP-06**: `LlmExposureRuleRepository` uses `UnconstrainedDataManager` (rules apply globally; user roles must NOT be able to bypass by un-granting read on `AiExposureRule`).
- [ ] **EXP-07**: Admin Flow UI: `AiExposureRuleListView` + `AiExposureRuleDetailView` with `genericFilter` + `propertyFilter`, action-column for enable/disable, gated to `AiAgentAdminRole`. Menu entry under admin section. UI label uses "Hide from AI" / "Visible to AI" (never "Allow").
- [ ] **EXP-08**: `LlmExposureChangedEvent` Spring event published on rule create/update/delete. Cache-invalidation hook for any per-request schema cache (current code has none, but the event is required for future caching).
- [ ] **EXP-09**: Negative test asserting an entity readable by the user but denylisted for the LLM does NOT appear in `list_entities`, `agent.entities`, RAG hits, or `find_records` errors (uniform `unknown_entity` opacity, P-13).
- [ ] **EXP-10**: `AiAgentAdminRole` extended with policies for `AiExposureRule` (CRUD + view + menu).

### Mutation-Capable Built-In Tools

- [ ] **MUT-01**: `BuiltInMutationTools` separate `@Component` (NOT methods on `BuiltInDataTools` — preserves the v1.0 ASM read-only test). Conditional via `@ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled", havingValue="true")` — **default OFF**.
- [ ] **MUT-02**: Mutation tool depth for v1.1: `create_record`, `update_record`, `add_related_record`, `remove_related_record`. `delete_record` deferred to v1.2 (destructive ops need separate UX — confirmation, undo).
- [ ] **MUT-03**: Layered gating per call, fail-closed, in order:
  1. `LlmExposurePolicy.canModify(entity, attribute)` (admin can deny mutation even where user can mutate).
  2. `AccessManager` `CrudEntityContext.isCreatePermitted/isUpdatePermitted` and per-attribute `EntityAttributeContext.canModify` for every attribute the LLM tries to write.
  3. Optional `MutationGuard` SPI veto (throws `ToolVetoedException`).
  4. `@Transactional` (REQUIRED, propagation default) `DataManager.save(...)` — regular `DataManager` (NOT `UnconstrainedDataManager`).
- [ ] **MUT-04**: Mandatory `@ToolParam idempotencyKey` (UUID string) on every mutation tool. Server-side `AiMutationIntent` dedup table records `(toolName, idempotencyKey, userId, conversationId, resultEntityId, createdAt)`. Replay returns the original result with `outcome=IDEMPOTENT_REPLAY`. TTL 24h default, configurable.
- [ ] **MUT-05**: `MutationGuard` SPI defined: `interface MutationGuard { void check(MutationIntent intent) throws ToolVetoedException; }`. Default no-op bean. Mirrors `ToolGuard`.
- [ ] **MUT-06**: `AiAgentMutationProperties` (`@ConfigurationProperties("ai-agent.tools.mutation")`): `enabled` (default false), `allowDelete` (default false; reserved for v1.2), `confirmationRequired` (default true; UX hint, not enforcement), `idempotencyTtl` (default 24h).
- [ ] **MUT-07**: `MutationErrorTranslator` translates JPA / `AccessDeniedException` into stable error codes (`access_denied`, `validation_failed`, `idempotency_violation`, `concurrent_modification`) — never echoes user-supplied PII or constraint message text into the LLM result string (P-22 mitigation).
- [ ] **MUT-08**: Audit reuses `AuditWriter.writeToolCall` with `eventName` ∈ {`create_record`, `update_record`, `add_related_record`, `remove_related_record`}. `argumentsJson` carries LLM JSON; `resultSummary` carries new entity id (create), or compact diff summary (update). New `outcome` values: `IDEMPOTENT_REPLAY`, `COMMIT_FAILED` (P-4: audit logged success but transaction rolled back). Existing REQUIRES_NEW boundary keeps audit durable across mutation rollback.
- [ ] **MUT-09**: `ToolEntityResolver` shared `@Component` consumed by both `BuiltInDataTools` and `BuiltInMutationTools` — extracted from existing helpers (`resolveReadableEntityOrThrow`, `parseEntityId`, new `resolveWritableEntityOrThrow`).
- [ ] **MUT-10**: System prompt updated when mutations enabled: explicit rule that mutations are user-driven (LLM proposes via `prepare_form_draft` for complex cases; direct `create_record` only for simple, confirmed user requests). Mutation tools also emit a "preview" payload the chat UI may render before commit (when `confirmationRequired=true`).
- [ ] **MUT-11**: Locale message keys for all denial/success/idempotency/error paths in ALL existing locales (per CLAUDE.md).
- [ ] **MUT-12**: Boot-test asserts zero mutation tool callbacks present in `AgentToolCallbacks.forCurrentUser` under default config (P-2: silent default-on regression gate).

### Configurable Chat Surfaces (SEED-005 activated, refined)

- [ ] **SURF-01**: Three chat presentation surfaces over the same backend and `ChatPanelFragment`:
  1. Full route `ChatView` (existing).
  2. `SidebarChatComponent` — Vaadin component mounted into host `AppLayout` `slot="drawer-end"`.
  3. `FloatingChatLauncher` — fixed-position bottom-right launcher button + `Dialog.setModality(MODELESS).setDraggable(true)` containing the fragment.
- [ ] **SURF-02**: `AiUiSettings` Jmix entity in `agentstore`, single-row by convention. Fields: `enabledSurfaces` (set of `FULL_ROUTE`, `SIDEBAR`, `FLOATING`), `defaultSurface`, audit fields. NOT bundled into `AiParameters` (chat-behavior vs UI-rollout are orthogonal).
- [ ] **SURF-03**: `ChatSurfaceMounter` `@Component` listening to `UIInitEvent` (Vaadin) injects the configured surfaces into the host shell. Reads admin toggle and only mounts what's enabled. Host needs no code edits beyond depending on the starter.
- [ ] **SURF-04**: `AiChatSessionState` `@VaadinSessionScope` bean tracks the active `conversationId` for the user session. Switching surface mid-session calls `setConversationId(state.getCurrentConversationId())` on the new fragment instance — same conversation continues. No backend duplication.
- [ ] **SURF-05**: ONE `ChatService`, ONE `AiConversation` row, ONE `ChatPanelFragment` per surface instance, but ALL fragments in one session share the same active conversation id via `AiChatSessionState`.
- [ ] **SURF-06**: Floating launcher placement: bottom-right fixed for v1.1. `defaultPosition` configurability deferred to v1.2.
- [ ] **SURF-07**: Floating launcher z-index and dialog-stacking: launcher hides itself while a Jmix admin dialog is open (P-21 mitigation) — wire to `Dialog` open/close events on the UI.
- [ ] **SURF-08**: Admin Flow UI: `AiUiSettingsView` for runtime toggle of which surfaces are enabled/visible. Admin-only (`AiAgentAdminRole`).
- [ ] **SURF-09**: Cross-surface conversation continuity test: switch surface mid-session, send another message, verify same `conversation_id` and same JDBC memory rows.
- [ ] **SURF-10**: `ChatPanelFragment` may receive an optional `setCompactMode(boolean)` for the floating dialog (suppress conversation list, tighter layout). Defer if Vaadin sizing already handles it.

### Chat Task Input — Speech-to-Text & Task-Scoped File

- [ ] **STT-01**: `AudioCaptureComponent` Vaadin component beside `MessageInput` in `ChatPanelFragment`. Mic button uses `executeJs` to invoke browser `MediaRecorder` API; produces `webm/opus` or `mp4` (no transcoding). Click-to-toggle UX with hard 60-second cap.
- [ ] **STT-02**: `TranscriptionService` interface + `SpringAiTranscriptionService` impl wrapping Spring AI 1.1.4 `OpenAiAudioTranscriptionModel`. Returns transcript text. Does NOT call `ChatService.ask` directly — text injects into the input field for user review/edit before send.
- [ ] **STT-03**: `AiAgentTranscriptionProperties` (`@ConditionalOnProperty(prefix="ai-agent.stt", name="enabled")` — **default OFF**): `model`, `language` (passed as Spring AI option), `maxDurationSeconds` (default 60). Operator docs note: OpenRouter does not proxy `/audio/transcriptions`; STT must point at OpenAI directly with a separate key.
- [ ] **STT-04**: `TranscriptionPostProcessor` SPI (optional) — host can rewrite transcripts (PII redaction, vocabulary normalization).
- [ ] **STT-05**: `STT_TRANSCRIPTION` audit event records duration, language, model, outcome, transcript hash (SHA-256) — NOT raw text by default. `ai-agent.stt.audit.storeTranscript=false` default. Hosts who need raw text must opt in explicitly.
- [ ] **STT-06**: STT failures degrade gracefully: input field shows error message + retry button; chat flow continues normally.
- [ ] **TASK-01**: Task-scoped file attachment in chat — separate UI affordance from `MessageInput` for "attach file for current task." Distinct from KB upload (`KnowledgeBaseView`).
- [ ] **TASK-02**: Task files are transient (conversation-scoped); they NEVER touch `VectorStore` / `IngesterManager`. Lifecycle: create on attach, delete on conversation end or after TTL (default 1 hour).
- [ ] **TASK-03**: `AiTaskFile` Jmix entity in `agentstore` — `id`, `conversationId`, `filename`, `contentType`, `sizeBytes`, `storageRef`, `createdAt`, `expiresAt`. Storage backed by Jmix `FileStorage` with default `local` storage.
- [ ] **TASK-04**: Backend contract: downstream features (intent extraction in EXTRACT-*) consume `AiTaskFile` by id, not by re-uploading. Files do not enter the chat memory message text.
- [ ] **TASK-05**: UI clearly distinguishes three input affordances: (a) plain text via `MessageInput`, (b) task-scoped file attachment for current intent, (c) KB upload via existing `KnowledgeBaseView`.

### Intent-Driven Extraction → Prefilled Jmix Forms

- [ ] **EXTRACT-01**: User selects an intent before sending. Add-on ships a default "auto" intent that uses the current chat path; intents lock the workflow when chosen. v1.1 ships at least one named intent end-to-end (e.g. PDF → customer-draft) plus an SPI for hosts.
- [ ] **EXTRACT-02**: `IntentExtractor<T>` SPI: `Class<T> targetType()`, `String entityName()`, `T extract(ExtractionInput input)`. Hosts implement per-intent extractors. Add-on ships ONE reference impl using `chatClient.prompt().call().entity(Class)` against a metadata-derived DTO synthesized from `MetaClass`.
- [ ] **EXTRACT-03**: Intent-extraction model routing: follow active `AiParameters` profile (no separate model pin in v1.1). Operator docs note that weak-JSON-adherence models may produce parse errors.
- [ ] **EXTRACT-04**: `AiExtractionDraft` Jmix entity in `agentstore`: `id`, `userUsername`, `targetEntityName`, `intentId`, `payloadJson`, `sourceConversationId`, `sourceTaskFileId` (nullable), `createdAt`, `expiresAt` (TTL default 1h), `confirmed` boolean. Persisted (NOT `VaadinSession`-cached) so the form load by id survives navigation. Per-user row-level policy.
- [ ] **EXTRACT-05**: `ExtractionService` orchestrates: receive input (file id from `AiTaskFile` and/or text), dispatch to matching `IntentExtractor`, persist `AiExtractionDraft`, return `draftId` + `instance_name` summary.
- [ ] **EXTRACT-06**: `ExtractionToolBridge` exposes a single `@Tool prepare_form_draft(intentId, contextRefs)` to the LLM. The LLM has NO `ViewNavigators` or any UI-mutation primitive (P-17 mitigation). Tool result is a structured payload `{ "action": "open_form_with_draft", "draftId": "...", "entityName": "...", "instanceName": "..." }` that the chat UI client recognizes.
- [ ] **EXTRACT-07**: `ChatPanelFragment` response renderer recognizes the `open_form_with_draft` shape and renders a "Open form to confirm" button. Click invokes `ViewNavigators.detailView(host, X.class).newEntity().withInitializer(e -> draftLoader.apply(draftId, e)).navigate()` — controller-side, after `accessManager.isPermitted(ViewContext)` check.
- [ ] **EXTRACT-08**: `DraftLoader` helper applies `payloadJson` to the editing entity via Jmix `DataContext.create(...)` and `setValueIfPermitted` (per-attribute `EntityAttributeContext.canModify`) — NOT raw `setValue` (P-18 mitigation). `dataContext.validate()` runs before `Save`.
- [ ] **EXTRACT-09**: Draft lifecycle: deleted on confirmed Save (or explicit cancel) or after TTL. Cleanup job runs hourly.
- [ ] **EXTRACT-10**: Negative test: LLM cannot bypass the draft → confirm flow (no direct `ViewNavigators` call from any `@Tool`-bearing class — design rule + grep-based test).

### New Entities

- [ ] **ENT-05**: `AiExposureRule` (per EXP-01)
- [ ] **ENT-06**: `AiUiSettings` (per SURF-02)
- [ ] **ENT-07**: `AiTaskFile` (per TASK-03)
- [ ] **ENT-08**: `AiExtractionDraft` (per EXTRACT-04)
- [ ] **ENT-09**: `AiMutationIntent` (per MUT-04 — idempotency dedup table)

All five entities follow CLAUDE.md conventions: `@JmixEntity` + UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`, no Lombok. Liquibase changelogs included in root `changelog.xml`.

### New SPIs

- [ ] **SPI-09**: `ToolFetchPlanCustomizer` (per TOOL-10)
- [ ] **SPI-10**: `MutationGuard` (per MUT-05)
- [ ] **SPI-11**: `TranscriptionPostProcessor` (per STT-04)
- [ ] **SPI-12**: `IntentExtractor<T>` (per EXTRACT-02)

All SPIs default to no-op beans where applicable, follow MEMORY rule "SPIs only for app-specific behavior" (these all have concrete consumer use cases identified).

### Audit Extensions

- [ ] **AUD-06**: `AuditWriter.writeToolCall` `outcome` enum extended: `IDEMPOTENT_REPLAY`, `COMMIT_FAILED`. New `eventName` strings: `create_record`, `update_record`, `add_related_record`, `remove_related_record`, `prepare_form_draft`, `STT_TRANSCRIPTION`. No new audit kind.
- [ ] **AUD-07**: Mutation audit row carries pre-image + post-image diff summary in `resultSummary`; PII-bearing fields hashed if `ai-agent.audit.hashSensitiveFields=true` (default true). Field-set configurable.

### Security Extensions

- [ ] **SEC-05**: `AiAgentAdminRole` extended with policies for new entities: `AiExposureRule` (CRUD + view + menu), `AiUiSettings` (read + update; no create/delete since single-row).
- [ ] **SEC-06**: `AiAgentUserRole` extended: read on own `AiExtractionDraft` rows (row-level policy by `userUsername`), read+create on own `AiTaskFile` rows.
- [ ] **SEC-07**: New `AiAgentMutationRole` resource role granting CRUD on entities the LLM may mutate (host composes it with their own roles). Default role catalog ships empty mutation set; hosts opt in.

### Testing

- [ ] **TEST-08**: Prompt-contract suite (regression-locks PROMPT-03/04/05): chat reply to "có bao nhiêu khách hàng?" must NOT contain the literal substring matching the internal entity-name pattern; reply must NOT contain literal tool names. Runs in Vietnamese AND English locales.
- [ ] **TEST-09**: `LlmExposurePolicy` integration test — entity readable by user but denylisted for LLM does not appear in `list_entities`, `agent.entities`, RAG hits, or surface as `access_denied` (uniform `unknown_entity`).
- [ ] **TEST-10**: Mutation gating integration test — user with READ but not MODIFY on attribute `X` triggers `update_record(attribute=X)` → blocked at gating step 2; tool returns structured error; `DataManager.save` never called.
- [ ] **TEST-11**: Mutation idempotency test — same `idempotencyKey` twice returns the same result; no duplicate row; second call audited with `outcome=IDEMPOTENT_REPLAY`.
- [ ] **TEST-12**: Mutation audit-vs-transaction test — force `DataManager.save` to throw post-flush; assert audit row written with `outcome=COMMIT_FAILED` (P-4 regression gate).
- [ ] **TEST-13**: Default-config boot test — assert zero mutation tool callbacks under default settings (P-2 silent default-on gate).
- [ ] **TEST-14**: Cross-surface conversation continuity test — switch surface mid-session, verify same `conversation_id` and JDBC memory rows.
- [ ] **TEST-15**: Intent-extraction navigation test — assert no `@Tool`-bearing class imports `ViewNavigators` (grep / source-scanner test); assert `prepare_form_draft` returns structured payload, NOT triggering navigation server-side.
- [ ] **TEST-16**: Task file isolation test — `AiTaskFile` upload does NOT trigger `IngesterManager` invocation; `VectorStore` count unchanged after task-file attach.
- [ ] **TEST-17**: STT audit privacy test — by default, `STT_TRANSCRIPTION` audit row contains transcript hash, NOT raw text. Setting `ai-agent.stt.audit.storeTranscript=true` flips it.

## Future Requirements (Deferred to v1.2+)

- [ ] **MUT-13**: `delete_record` mutation tool with confirmation/undo UX. Deferred — destructive ops need separate UX work.
- [ ] **SURF-11**: Configurable floating-launcher corner placement. Deferred — bottom-right is industry standard.
- [ ] **EXTRACT-11**: Multi-intent extraction in a single turn (parallel intent dispatch). Deferred — single-intent per turn covers v1.1 needs.
- [ ] **EXP-11**: Time-bounded exposure rules (auto-expire after date). Deferred until governance demand surfaces.
- [ ] Pre-deploy answer-quality regression gate (SEED-002). Dormant — activate when prompt rules from v1.1 produce signal.
- [ ] Reviewed learning loop for agent failures (SEED-001). Dormant — needs production-incident corpus.
- [ ] Replay/diff runner (SEED-004). Dormant — pairs with SEED-002 future activation.
- [ ] Strict file-backed knowledge path (SEED-006). Dormant — needs retrieval-drift trigger.
- [ ] OutputScanner SPI (SEED-003). Dormant — config-driven scanner sufficient.

## Out of Scope

- **Clean-consumer smoke (PKG-05 / TEST-07)**: Plan 08-05 carryover from v1.0.0. Deferred — needs Postgres/pgvector Testcontainers OR starter stub VectorStore boot mode; both have separate tradeoff conversations. Revisit in a later milestone.
- **Collapsible per-turn tool-detail panel + ephemeral streaming-status indicator** (pending todo `add-collapsible-tool-detail-and-ephemeral-status-to-chat-ui`): UX polish; not blocking.
- **Autonomous multi-step agents** (loop / planner): out per PROJECT.md.
- **DELETE mutation tool**: deferred to v1.2 (see MUT-13).
- **Auto-ingesting host entity records into vector store**: per PROJECT.md, `DataManager` remains source of truth.
- **URL/web-crawling KB ingestion**: deferred per PROJECT.md.
- **Universal-agent positioning**: out per PROJECT.md.
- **Custom vector-store abstractions**: out per PROJECT.md — use Spring AI `VectorStore` directly.
- **Jmix internal APIs**: forbidden per PROJECT.md.

## Traceability

Phase mapping is filled in by the roadmapper step.

| REQ-ID range | Category | Target phase |
|--------------|----------|--------------|
| PROMPT-01 .. PROMPT-06 | Prompt-contract hardening | TBD |
| TOOL-09 .. TOOL-12 | Tool-layer refinements | TBD |
| EXP-01 .. EXP-10 | AI-specific exposure policy | TBD |
| MUT-01 .. MUT-12 | Mutation-capable tools | TBD |
| SURF-01 .. SURF-10 | Configurable chat surfaces | TBD |
| STT-01 .. STT-06 | Speech-to-text input | TBD |
| TASK-01 .. TASK-05 | Task-scoped file attachment | TBD |
| EXTRACT-01 .. EXTRACT-10 | Intent-driven extraction | TBD |
| ENT-05 .. ENT-09 | New entities | TBD (per consumer phase) |
| SPI-09 .. SPI-12 | New SPIs | TBD (per consumer phase) |
| AUD-06, AUD-07 | Audit extensions | TBD (per consumer phase) |
| SEC-05 .. SEC-07 | Security extensions | TBD (per consumer phase) |
| TEST-08 .. TEST-17 | New tests | TBD (per consumer phase) |
