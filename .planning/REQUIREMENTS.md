# Requirements — Jmix AI Copilot (ai-agent-core)

**Version:** v1.1.0 — Prompt Hardening, Mutation Tools & Configurable Chat Surfaces
**Last updated:** 2026-05-05 (Phase 13 scope rewrite — STT split out to a new Phase 15)

## v1.1 Scope Summary

Enlarge the v1.0 read-only Copilot to mutation-capable, governance-aware, multi-surface chat — without churning the platform. Zero new core dependencies. Hard build-order: **tool/prompt foundations → exposure policy → mutation tools**; configurable surfaces / chat task file / intent-extraction / chat voice input sequence after foundations land.

REQ-IDs continue v1.0 conventions where the category exists (`TOOL-09…`, `AUD-06…`, `SPI-09…`, `TEST-08…`, `ENT-05…`); new categories (`PROMPT`, `MUT`, `EXP`, `SURF`, `TASK`, `STT`, `EXTRACT`) numbered from `01`.

**2026-05-05 scope-shift note:** Phase 13 was originally "STT + Task-Scoped File"; rewritten to **"Chat Task File — Attach + LLM Read + Bulk Save"** (TASK-* + new MUT-14 + bundled default-model swap to `qwen/qwen3.6-35b-a3b` Apache 2.0 self-hostable multimodal). STT-* + SPI-11 + TEST-17 are split out to a new **Phase 15: Chat Voice Input — Soniox STT** (Soniox provider via custom Spring `RestClient`, no Java SDK; OpenAI strategy fallback). Driver: user prioritized attach-file-then-LLM-process and bulk-save productivity over voice dictation, AND required a self-hostable open-weights chat model. See `.planning/ROADMAP.md` Notes for full context.

## v1.1 Requirements

### Prompt-Contract Hardening

- [x] **PROMPT-01**: `BaselineContextProvider.compose(...)` injects `agent.entities` — sorted alphabetical lines `name (label)`, sourced from `LlmExposurePolicy.getReadableSchema()` (post-narrowed). Truncation hint when entity count exceeds configurable threshold (default 50). Skipped when schema empty.
- [x] **PROMPT-02**: `BaselineContextProvider.compose(...)` injects `agent.permissions` — compact map keyed by entity name with CRUD bits (`read`, `create`, `update`, `delete`) plus per-attribute `modifiable` set. Sourced from `LlmExposurePolicy + AccessManager`. Locale-sensitive labels NOT in the cache key (P-8 mitigation).
- [x] **PROMPT-03**: System prompt rule explicitly forbids the LLM from using internal entity names (host-prefixed identifiers) and tool names (`find_records`, `RETRIEVAL`, …) in user-facing reply text. Prompt rule paired with the `agent.entities` inventory so the LLM has a label to use instead.
- [x] **PROMPT-04**: `ToolResultFormatter.records(...)` wraps payloads as `<data entity="<label>" type="<internalName>">…` (label first). Row-level rendering puts `_instance_name` (Jmix human-friendly identifier) before raw key fields.
- [x] **PROMPT-05**: `unknown_entity` retry contract: on `unknown_entity` tool error, the LLM must call `list_entities` exactly once and either retry with the corrected name or tell the user no such entity exists. No semantic guessing. `ToolErrorDto.expected` carries the next valid action hint.
- [x] **PROMPT-06**: `OutputScannerAdvisor` configuration extended with patterns matching internal entity-name prefix (e.g. `\b<host_prefix>_\w+\b`) and tool-name leakage. Flag-and-audit posture preserved (no hard block on flag).

### Tool-Layer Refinements

- [x] **TOOL-09**: `describe_entity` payload extended with selected Jmix metadata fields, sourced via `MetadataTools` (NOT raw reflection): entity-level `comment` (via `@Comment`), optional `ancestor`; attribute-level `comment`, `attributeType`, `cardinality`, `mandatory` (replaces inverted `nullable`), `readOnly`, `persistent`, `transient`, `isPrimaryKey`, `enumValues`, `relationshipTarget`, `maxLength`. Excluded fields documented inline.
- [x] **TOOL-10**: `ToolFetchPlanCustomizer` SPI defined: `Optional<FetchPlan> overrideFor(String toolName, MetaClass metaClass, FetchPlanContext ctx)`. Default impl returns `Optional.empty()` (current `_base` / `_instance_name` behavior preserved). Resolved by runtime `toolName + MetaClass`, NOT compile-time entity types. Add-on does NOT auto-discover from host `fetch-plans.xml`.
- [x] **TOOL-11**: Override fetch plans pass an attribute-policy intersection — host plan cannot widen the projection beyond `AccessManager`-allowed attributes. Comment in code states: "fetch plan is projection, not security."
- [x] **TOOL-12**: LLM permission inventory exposed at entity granularity in baseline (PROMPT-02) plus `describe_entity` per-attribute readability. Denied entity names are NOT revealed to the LLM (consistent with v1 "if access is denied, the model behaves as if the entity does not exist"); the inventory only lists entities the user is allowed to see.

### AI-Specific LLM Exposure Policy (SEED-007 activated)

- [x] **EXP-01**: `AiExposureRule` Jmix entity in `agentstore` — `entityName` (Jmix metaClass name), `attributePath` (nullable; null = whole entity), `mode` enum with single value `EXCLUDE` (no `ALLOW` shape — prevents widening, P-6), `enabled`, audit fields. Liquibase changelog included in root.
- [x] **EXP-02**: `LlmExposurePolicy` `@Component` wraps `CurrentUserSchemaAccess`; same method signatures (`getReadableSchema`, `canReadEntity`, `canReadAttribute`). Composition is `userVisible AND NOT excluded` (boolean AND, never OR).
- [x] **EXP-03**: `BuiltInDataTools` migrated to consult `LlmExposurePolicy` instead of `CurrentUserSchemaAccess` directly (mechanical replacement at all call sites).
- [x] **EXP-04**: `BaselineContextProvider` sources `agent.entities` and `agent.permissions` from `LlmExposurePolicy` (so denied entities never leak into the system prompt).
- [x] **EXP-05**: `RetrievalFilterBuilder` integrates exposure policy — RAG retrieval excludes documents whose source-entity is denylisted, even when the user has Jmix read access. Admin denylist is leaky for KB docs without this.
- [x] **EXP-06**: `LlmExposureRuleRepository` uses `UnconstrainedDataManager` (rules apply globally; user roles must NOT be able to bypass by un-granting read on `AiExposureRule`).
- [x] **EXP-07**: Admin Flow UI: `AiExposureRuleListView` + `AiExposureRuleDetailView` with `genericFilter` + `propertyFilter`, action-column for enable/disable, gated to `AiAgentAdminRole`. Menu entry under admin section. UI label uses "Hide from AI" / "Visible to AI" (never "Allow").
- [x] **EXP-08**: `LlmExposureChangedEvent` Spring event published on rule create/update/delete. Cache-invalidation hook for any per-request schema cache (current code has none, but the event is required for future caching).
- [x] **EXP-09**: Negative test asserting an entity readable by the user but denylisted for the LLM does NOT appear in `list_entities`, `agent.entities`, RAG hits, or `find_records` errors (uniform `unknown_entity` opacity, P-13).
- [x] **EXP-10**: `AiAgentAdminRole` extended with policies for `AiExposureRule` (CRUD + view + menu).

### Mutation-Capable Built-In Tools

- [x] **MUT-01**: `BuiltInMutationTools` separate `@Component` (NOT methods on `BuiltInDataTools` — preserves the v1.0 ASM read-only test). Conditional via `@ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled", havingValue="true")` — **default OFF**.
- [x] **MUT-02**: Mutation tool depth for v1.1: `create_record`, `update_record`, `add_related_record`, `remove_related_record`. `delete_record` deferred to v1.2 (destructive ops need separate UX — confirmation, undo).
- [x] **MUT-03**: Layered gating per call, fail-closed, in order:
  1. `LlmExposurePolicy.canModify(entity, attribute)` (admin can deny mutation even where user can mutate).
  2. `AccessManager` `CrudEntityContext.isCreatePermitted/isUpdatePermitted` and per-attribute `EntityAttributeContext.canModify` for every attribute the LLM tries to write.
  3. Optional `MutationGuard` SPI veto (throws `ToolVetoedException`).
  4. `@Transactional` (REQUIRED, propagation default) `DataManager.save(...)` — regular `DataManager` (NOT `UnconstrainedDataManager`).
- [x] **MUT-04**: Mandatory `@ToolParam idempotencyKey` (UUID string) on every mutation tool. Server-side `AiMutationIntent` dedup table records `(toolName, idempotencyKey, userId, conversationId, resultEntityId, createdAt)`. Replay returns the original result with `outcome=IDEMPOTENT_REPLAY`. TTL 24h default, configurable.
- [x] **MUT-05**: `MutationGuard` SPI defined: `interface MutationGuard { void check(MutationIntent intent) throws ToolVetoedException; }`. Default no-op bean. Mirrors `ToolGuard`.
- [x] **MUT-06**: `AiAgentMutationProperties` (`@ConfigurationProperties("ai-agent.tools.mutation")`): `enabled` (default false), `allowDelete` (default false; reserved for v1.2), `confirmationRequired` (default true; UX hint, not enforcement), `idempotencyTtl` (default 24h).
- [x] **MUT-07**: `MutationErrorTranslator` translates JPA / `AccessDeniedException` into stable error codes (`access_denied`, `validation_failed`, `idempotency_violation`, `concurrent_modification`) — never echoes user-supplied PII or constraint message text into the LLM result string (P-22 mitigation).
- [x] **MUT-08**: Audit reuses `AuditWriter.writeToolCall` with `eventName` ∈ {`create_record`, `update_record`, `add_related_record`, `remove_related_record`}. `argumentsJson` carries LLM JSON; `resultSummary` carries new entity id (create), or compact diff summary (update). New `outcome` values: `IDEMPOTENT_REPLAY`, `COMMIT_FAILED` (`COMMIT_FAILED` means host save returned but idempotency finalization failed, so commit outcome must be verified). Existing REQUIRES_NEW boundary keeps audit durable across mutation rollback.
- [x] **MUT-09**: `ToolEntityResolver` shared `@Component` consumed by both `BuiltInDataTools` and `BuiltInMutationTools` — extracted from existing helpers (`resolveReadableEntityOrThrow`, `parseEntityId`, new `resolveWritableEntityOrThrow`).
- [x] **MUT-10**: System prompt updated when mutations enabled: explicit rule that mutations are user-driven; direct mutation tools are for simple, confirmed user requests only, while complex extraction/form-draft workflows defer to the later intent-driven extraction phase. `confirmationRequired=true` is a UX hint for chat surfaces and does not add a Phase 11 dry-run/preview payload.
- [x] **MUT-11**: Locale message keys for all denial/success/idempotency/error paths in ALL existing locales (per CLAUDE.md).
- [x] **MUT-12**: Boot-test asserts zero mutation tool callbacks present in `AgentToolCallbacks.forCurrentUser` under default config (P-2: silent default-on regression gate).
- [x] **MUT-14**: `bulk_save_records(entityName, records[], idempotencyKey)` `@Tool` method on `BuiltInMutationTools` (joins existing Phase 11 `@ConditionalOnProperty` gate — default OFF). Per-row dispatch: `id != null` → update path, `id == null` → create path. Runs ONE transaction through the Phase 11 chain (`LlmExposurePolicy.canCreate/canUpdate` once for entity → `AccessManager.applyRegisteredConstraints(CrudEntityContext)` per row → per-attribute `EntityAttributeContext.canModify` per row → `AiMutationIntent` reservation ONCE per batch with `requestHash` = SHA-256 of canonical JSON in submission order → type coercion → `MutationGuard` veto per row → `@Transactional DataManager.save` per row inside one transaction → `AuditWriter.writeToolCall` ONCE per batch with `eventName=bulk_save_records` + `argumentsJson={count, entityName, sampleHashes, idempotencyKey}`). Per-row failure (validation / `AccessDeniedException` / `MutationGuard` veto) rolls back the entire batch (rollback-all only in v1.1; continue-on-error deferred); audit `outcome=FAILED` with `failedRowIndex` + `failedAttribute` (when available). Replay with same `idempotencyKey` returns `IDEMPOTENT_REPLAY`. Tool description follows the 5-section MANDATORY/INPUT/FORMATS/ERROR/STRICTNESS+EXAMPLES rich-tool template with TWO worked examples (xlsx-create-only batch + mixed PDF-update batch). FORMATS section explicitly: "omit `id` to create; include `id` to update; never include `id: null`".

### Configurable Chat Surfaces (SEED-005 activated, refined)

- [x] **SURF-01**: Two chat presentation surfaces over the same backend and `ChatPanelFragment`:
  1. `FULL_ROUTE` — the existing full-route `ChatView`.
  2. `HEADER_BUTTON` — a host-navbar button that opens the shared chat fragment in a non-modal Jmix `DialogWindow`.
- [x] **SURF-02**: `AiUiSettings` Jmix entity in `agentstore`, single-row by convention. Persisted fields are `enabledSurfaceIds` (deterministic text-backed id list containing `FULL_ROUTE` and/or `HEADER_BUTTON`), `defaultSurface`, and audit fields. The controller/view-model layer may describe this as enabled surfaces, but the entity must not expose a JavaBean `enabledSurfaces` collection property. NOT bundled into `AiParameters` (chat-behavior vs UI-rollout are orthogonal).
- [x] **SURF-03**: `ChatSurfaceMounter` `@Component` listening to `UIInitEvent` (Vaadin) and navigation events injects the configured header-button surface into the host shell. Reads admin toggle and only mounts what's enabled. Host needs no code edits beyond depending on the starter.
- [x] **SURF-04**: `AiChatSessionState` `@VaadinSessionScope` bean tracks the active `conversationId` for the user session. Switching surface mid-session calls `setConversationId(state.getCurrentConversationId())` on the new fragment instance — same conversation continues. No backend duplication.
- [x] **SURF-05**: ONE `ChatService`, ONE `AiConversation` row, ONE active `ChatPanelFragment` per UI tab, and all mounted fragments in one session share the same active conversation id via `AiChatSessionState`.
- [x] **SURF-06**: Header-button surface opens a non-modal Jmix `DialogWindow` anchored top-right (`65%` left, `5%` top, `35%` width, `75%` height, resizable/draggable when supported by Jmix). Dialog size/position configurability is deferred to v1.2.
- [x] **SURF-07**: Jmix `DialogWindow` participates in the normal Vaadin/Jmix overlay stack, so the old P-21 raw-dialog stacking mitigation is moot and out of scope for v1.1.
- [x] **SURF-08**: Admin Flow UI: `AiUiSettingsView` for runtime toggle of which surfaces are enabled/visible. Admin-only (`AiAgentAdminRole`).
- [x] **SURF-09**: Cross-surface conversation continuity test: switch surface mid-session, send another message, verify same `conversation_id` and same JDBC memory rows.
- [x] **SURF-10**: Compact-mode work is deferred because both v1.1 surfaces use the full `ChatPanelFragment` layout.

### Chat Task File — Attach + LLM Read + Bulk Save (Phase 13)

> **Phase scope rewritten 2026-05-05.** Originally bundled with STT; STT moved to new Phase 15. Phase 13 now centers on attach-file-then-LLM-processing with `bulk_save_records` as the headline new tool. Default chat model swaps to `qwen/qwen3.6-35b-a3b` (Apache 2.0 multimodal native, self-hostable) bundled with this phase.

- [x] **TASK-01**: Task-scoped file attachment in chat — separate UI affordance from `MessageInput` for "attach file for current task." Distinct from KB upload (`KnowledgeBaseView`). Renders as Button + chip strip in the existing Phase 12 `attachmentsPanel` slot, working in BOTH `FULL_ROUTE` (`ChatView`) and `HEADER_BUTTON` (`ChatDialogView`).
- [x] **TASK-02**: Task files are transient (conversation-scoped); they NEVER touch `VectorStore` / `IngesterManager`. Lifecycle: create on attach, delete on TTL (default 1 hour) via hourly scheduled cleanup job + opportunistic cleanup on chat-send entry.
- [x] **TASK-03**: `AiTaskFile` Jmix entity in `agentstore` — `id`, `conversationId` (NOT NULL FK → `AiConversation`), `messageId` (NULLABLE FK → `AiMessage`, populated on send via 2-phase write), `userUsername`, `filename`, `contentType`, `sizeBytes`, `storageRef` (`@PropertyDatatype("fileRef")`), `createdAt`, `expiresAt`. Storage backed by Jmix `FileStorage` with default `local` storage. MIME allowlist hard-coded mirroring `D:/DTH/jmix-crm` `AiAttachmentMediaResolver`: pdf, csv, doc, docx, xls, xlsx, html, txt, md, png, jpg/jpeg, gif, webp. Per-file size cap reuses `jmix.ai-agent.rag.upload.max-file-size-bytes` (100 MB). Liquibase changelog `090-ai-task-file.xml` included in root.
- [x] **TASK-04**: Files reach the LLM via Spring AI `Media` injected into the user message ON THE SEND TURN (matches `D:/DTH/jmix-crm` `AiAttachmentMediaResolver` pattern). Single-turn-only injection: subsequent turns receive an empty `Media` list — Spring AI `JdbcChatMemoryRepository` persists `content TEXT` only, so re-injection would force re-reading bytes from `FileStorage` every turn at 5–15× token cost. The assistant's first-turn paraphrase becomes the persistent textual record. Files DO NOT enter chat memory `content` (Spring AI persists text only by design). Downstream Phase 14 `prepare_form_draft` may consume `AiTaskFile` by id for single-record extraction.
- [x] **TASK-05**: UI clearly distinguishes three input affordances: (a) plain text via `MessageInput`, (b) task-scoped file attachment for current intent (chip strip), (c) KB upload via existing `KnowledgeBaseView`.
- [x] **TASK-06**: `AiTaskFileMediaResolver` (`@Component`) returns rows where `messageId IS NULL` (newly attached, not yet sent) for the active conversation; `DefaultChatServiceImpl.ask(...)` and `.stream(...)` invoke the resolver and inject `Media` via `chatClient.prompt().user(u -> u.media(media.toArray(new Media[0])))`. After the new `AiMessage` is persisted, `UPDATE AiTaskFile SET messageId = newMessageId WHERE id IN (resolvedIds)`. Default model `qwen/qwen3.6-35b-a3b` (multimodal native) is set in `application.properties` for both `jmix.ai-agent.defaults.model` and `spring.ai.openai.chat.options.model`; admin per-conversation override surface via `AiParametersDetailView` (Phase 6) is unchanged.

### Chat Voice Input — Soniox STT (Phase 15)

> **Phase scope created 2026-05-05** by splitting STT-* + SPI-11 + TEST-17 out of original Phase 13. Vendor changed from Spring AI `OpenAiAudioTranscriptionModel` to Soniox via custom Spring `RestClient` (Soniox has no Java SDK); strategy `TranscriptionService` interface keeps OpenAI as optional fallback impl.

- [ ] **STT-01**: `AudioCaptureComponent` Vaadin component beside `MessageInput` in `ChatPanelFragment`. Mic button uses `executeJs` to invoke browser `MediaRecorder` API; produces `webm/opus` or `mp4` (no transcoding — Soniox accepts both directly). Click-to-toggle UX with hard 60-second cap.
- [ ] **STT-02**: `TranscriptionService` strategy interface with default `SonioxTranscriptionService` impl (custom Spring `RestClient`-based — Soniox has no official Java SDK as of 2026-05). Flow: `POST /v1/files` (multipart upload) → `POST /v1/transcriptions` (`{file_id, model: "stt-async-v4", language_hints: ["vi","en"]}`) → poll `GET /v1/transcriptions/{id}` → `GET /v1/transcriptions/{id}/transcript` → cleanup `DELETE /v1/files/{id}` + `DELETE /v1/transcriptions/{id}`. Auth: `Authorization: Bearer ${ai-agent.stt.soniox.api-key}`. Optional `SpringAiTranscriptionService` impl wrapping Spring AI `OpenAiAudioTranscriptionModel` available as fallback. Returns transcript text. Does NOT call `ChatService.ask` directly — text injects into the input field for user review/edit before send.
- [ ] **STT-03**: `AiAgentTranscriptionProperties` (`@ConditionalOnProperty(prefix="ai-agent.stt", name="enabled")` — **default OFF**): `provider` (default `soniox`; values `soniox|openai|<custom-bean-name>`), `soniox.api-key`, `soniox.base-url` (default `https://api.soniox.com/v1`), `soniox.model` (default `stt-async-v4`), `soniox.language-hints` (default `["vi","en"]`), `language` (passed as provider option), `maxDurationSeconds` (default 60). Operator docs note: Soniox uses an INDEPENDENT API key from the OpenRouter chat key; OpenRouter does not proxy `/audio/transcriptions`; if `provider=openai` is selected, OpenAI key is required directly.
- [ ] **STT-04**: `TranscriptionPostProcessor` SPI (optional) — host can rewrite transcripts (PII redaction, vocabulary normalization) before they reach the input field.
- [ ] **STT-05**: `stt_transcription` audit event reuses `AuditWriter.writeToolCall` with `eventName=stt_transcription` (no new `AuditKind`). Records duration, language, model, outcome, transcript hash (SHA-256) — NOT raw text by default. `ai-agent.stt.audit.storeTranscript=false` default. Hosts who need raw text must opt in explicitly.
- [ ] **STT-06**: STT failures (provider 4xx, recording too long, network) degrade gracefully: input field shows error message + retry button; chat flow continues normally.

### Intent-Driven Extraction → Prefilled Jmix Forms

- [ ] **EXTRACT-01**: User selects an intent before sending. Add-on ships a default "auto" intent that uses the current chat path; intents lock the workflow when chosen. v1.1 ships at least one named intent end-to-end (e.g. PDF → customer-draft) plus an SPI for hosts.
- [x] **EXTRACT-02**: `IntentExtractor<T>` SPI: `Class<T> targetType()`, `String entityName()`, `T extract(ExtractionInput input)`. Hosts implement per-intent extractors. Add-on ships ONE reference impl using `chatClient.prompt().call().entity(Class)` against a metadata-derived DTO synthesized from `MetaClass`.
- [x] **EXTRACT-03**: Intent-extraction model routing: follow active `AiParameters` profile (no separate model pin in v1.1). Operator docs note that weak-JSON-adherence models may produce parse errors.
- [x] **EXTRACT-04**: `AiExtractionDraft` Jmix entity in `agentstore`: `id`, `userUsername`, `targetEntityName`, `intentId`, `payloadJson`, `sourceConversationId`, `sourceTaskFileId` (nullable), `createdAt`, `expiresAt` (TTL default 1h), `confirmed` boolean. Persisted (NOT `VaadinSession`-cached) so the form load by id survives navigation. Per-user row-level policy.
- [x] **EXTRACT-05**: `ExtractionService` orchestrates: receive input (file id from `AiTaskFile` and/or text), dispatch to matching `IntentExtractor`, persist `AiExtractionDraft`, return `draftId` + `instance_name` summary.
- [ ] **EXTRACT-06**: `ExtractionToolBridge` exposes a single `@Tool prepare_form_draft(intentId, contextRefs)` to the LLM. The LLM has NO `ViewNavigators` or any UI-mutation primitive (P-17 mitigation). Tool result is a structured payload `{ "action": "open_form_with_draft", "draftId": "...", "entityName": "...", "instanceName": "..." }` that the chat UI client recognizes.
- [ ] **EXTRACT-07**: `ChatPanelFragment` response renderer recognizes the `open_form_with_draft` shape and renders a "Open form to confirm" button. Click invokes `ViewNavigators.detailView(host, X.class).newEntity().withInitializer(e -> draftLoader.apply(draftId, e)).navigate()` — controller-side, after `accessManager.isPermitted(ViewContext)` check.
- [ ] **EXTRACT-08**: `DraftLoader` helper applies `payloadJson` to the editing entity via Jmix `DataContext.create(...)` and `setValueIfPermitted` (per-attribute `EntityAttributeContext.canModify`) — NOT raw `setValue` (P-18 mitigation). `dataContext.validate()` runs before `Save`.
- [x] **EXTRACT-09**: Draft lifecycle: deleted on confirmed Save (or explicit cancel) or after TTL. Cleanup job runs hourly.
- [ ] **EXTRACT-10**: Negative test: LLM cannot bypass the draft → confirm flow (no direct `ViewNavigators` call from any `@Tool`-bearing class — design rule + grep-based test).

### New Entities

- [x] **ENT-05**: `AiExposureRule` (per EXP-01)
- [x] **ENT-06**: `AiUiSettings` (per SURF-02)
- [x] **ENT-07**: `AiTaskFile` (per TASK-03)
- [x] **ENT-08**: `AiExtractionDraft` (per EXTRACT-04)
- [x] **ENT-09**: `AiMutationIntent` (per MUT-04 — idempotency dedup table)

All five entities follow CLAUDE.md conventions: `@JmixEntity` + UUID + `@JmixGeneratedValue` + `@Version` + `@InstanceName`, no Lombok. Liquibase changelogs included in root `changelog.xml`.

### New SPIs

- [x] **SPI-09**: `ToolFetchPlanCustomizer` (per TOOL-10)
- [x] **SPI-10**: `MutationGuard` (per MUT-05)
- [ ] **SPI-11**: `TranscriptionPostProcessor` (per STT-04)
- [x] **SPI-12**: `IntentExtractor<T>` (per EXTRACT-02)

All SPIs default to no-op beans where applicable, follow MEMORY rule "SPIs only for app-specific behavior" (these all have concrete consumer use cases identified).

### Audit Extensions

- [x] **AUD-06**: `AuditWriter.writeToolCall` `outcome` enum extended: `IDEMPOTENT_REPLAY`, `COMMIT_FAILED`. New `eventName` strings: `create_record`, `update_record`, `add_related_record`, `remove_related_record`, `prepare_form_draft`, `STT_TRANSCRIPTION`. No new audit kind.
- [x] **AUD-07**: Mutation audit row carries pre-image + post-image diff summary in `resultSummary`; PII-bearing fields hashed if `ai-agent.audit.hashSensitiveFields=true` (default true). Field-set configurable.

### Security Extensions

- [x] **SEC-05**: `AiAgentAdminRole` extended with policies for new entities: `AiExposureRule` (CRUD + view + menu — done Phase 10-03), `AiUiSettings` (read + update; no create/delete since single-row — done Phase 12-02).
- [x] **SEC-06**: `AiAgentUserRole` extended: read on own `AiExtractionDraft` rows (row-level policy by `userUsername`), read+create on own `AiTaskFile` rows.
- [x] **SEC-07**: New `AiAgentMutationRole` resource role is an explicit AI-mutation marker gate. It grants no entity CRUD by itself; mutation tools require the marker role AND normal Jmix create/update policies. Hosts opt users in by assigning/composing this marker with their own entity roles.

### Testing

- [x] **TEST-08**: Prompt-contract suite (regression-locks PROMPT-03/04/05): chat reply to "có bao nhiêu khách hàng?" must NOT contain the literal substring matching the internal entity-name pattern; reply must NOT contain literal tool names. Runs in Vietnamese AND English locales.
- [x] **TEST-09**: `LlmExposurePolicy` integration test — entity readable by user but denylisted for LLM does not appear in `list_entities`, `agent.entities`, RAG hits, or surface as `access_denied` (uniform `unknown_entity`).
- [x] **TEST-10**: Mutation gating integration test — user with READ but not MODIFY on attribute `X` triggers `update_record(attribute=X)` → blocked at gating step 2; tool returns structured error; `DataManager.save` never called.
- [x] **TEST-11**: Mutation idempotency test — same `idempotencyKey` twice returns the same result; no duplicate row; second call audited with `outcome=IDEMPOTENT_REPLAY`.
- [x] **TEST-12**: Mutation audit-vs-transaction test — known host save rollback writes a durable audit row with `outcome=ERROR`; post-host-save idempotency finalization failure writes `outcome=COMMIT_FAILED` and leaves the intent non-reclaimable (`COMMIT_UNKNOWN` or retained `PENDING`); post-COMMITTED audit/result failures leave the intent `COMMITTED` and exact retry replays.
- [x] **TEST-13**: Default-config boot test — assert zero mutation tool callbacks under default settings (P-2 silent default-on gate).
- [x] **TEST-14**: Cross-surface conversation continuity test — switch surface mid-session, verify same `conversation_id` and JDBC memory rows.
- [ ] **TEST-15**: Intent-extraction navigation test — assert no `@Tool`-bearing class imports `ViewNavigators` (grep / source-scanner test); assert `prepare_form_draft` returns structured payload, NOT triggering navigation server-side.
- [x] **TEST-16**: Task file isolation test — `AiTaskFile` upload does NOT trigger `IngesterManager` invocation; `VectorStore` count unchanged after task-file attach.
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

Phase mapping filled in by the roadmapper. v1.1 phases continue numbering from v1.0 close (Phase 8 + inserted 7.1, 7.2). v1.1 starts at Phase 9.

| REQ-ID range | Category | Target phase |
|--------------|----------|--------------|
| PROMPT-01 .. PROMPT-06 | Prompt-contract hardening | Phase 9 |
| TOOL-09 .. TOOL-12 | Tool-layer refinements | Phase 9 |
| EXP-01 .. EXP-10 | AI-specific exposure policy | Phase 10 |
| MUT-01 .. MUT-12 | Mutation-capable tools | Phase 11 |
| MUT-14 | bulk_save_records (extends Phase 11 chain) | Phase 13 |
| SURF-01 .. SURF-10 | Configurable chat surfaces | Phase 12 |
| TASK-01 .. TASK-06 | Chat task file (attach + Media injection) | Phase 13 |
| STT-01 .. STT-06 | Soniox speech-to-text input | **Phase 15** (split out from Phase 13 on 2026-05-05) |
| EXTRACT-01 .. EXTRACT-10 | Intent-driven extraction | Phase 14 |
| ENT-05 | New entity (AiExposureRule) | Phase 10 |
| ENT-06 | New entity (AiUiSettings) | Phase 12 |
| ENT-07 | New entity (AiTaskFile) | Phase 13 |
| ENT-08 | New entity (AiExtractionDraft) | Phase 14 |
| ENT-09 | New entity (AiMutationIntent) | Phase 11 |
| SPI-09 | ToolFetchPlanCustomizer | Phase 9 |
| SPI-10 | MutationGuard | Phase 11 |
| SPI-11 | TranscriptionPostProcessor | **Phase 15** (moved with STT) |
| SPI-12 | IntentExtractor<T> | Phase 14 |
| AUD-06 | Audit eventName + outcome extensions | Phase 11 |
| AUD-07 | Pre/post-image diff + PII hashing | Phase 11 (plumbing prepared in Phase 9) |
| SEC-05 | AiAgentAdminRole extension (AiExposureRule, AiUiSettings) | Phase 10 (AiExposureRule) + Phase 12 (AiUiSettings) |
| SEC-06 | AiAgentUserRole extension (AiTaskFile, AiExtractionDraft) | Phase 13 (AiTaskFile) + Phase 14 (AiExtractionDraft) |
| SEC-07 | AiAgentMutationRole | Phase 11 |
| TEST-08 | Prompt-contract suite | Phase 9 |
| TEST-09 | Exposure policy uniform-opacity | Phase 10 |
| TEST-10 | Mutation gating | Phase 11 |
| TEST-11 | Mutation idempotency | Phase 11 |
| TEST-12 | Mutation audit-vs-transaction | Phase 11 |
| TEST-13 | Default-config boot test | Phase 11 |
| TEST-14 | Cross-surface continuity | Phase 12 |
| TEST-15 | Intent-extraction navigation | Phase 14 |
| TEST-16 | Task file isolation | Phase 13 |
| TEST-17 | STT audit privacy | **Phase 15** (moved with STT) |

**Coverage:** 100% of v1.1 active REQ-IDs above are mapped to exactly one phase (split-mapped categories — SEC-05, SEC-06 — note both phases for the entity-specific sub-policies, but each individual sub-policy lands in exactly one phase). Future Requirements and Out of Scope items intentionally not mapped.

**Total active REQ-IDs:** 67 across 7 phases (Phase 9: 18; Phase 10: 11; Phase 11: 17; Phase 12: 11; Phase 13: 9 — TASK-01..06 + ENT-07 + MUT-14 + TEST-16; Phase 14: 13; Phase 15: 8 — STT-01..06 + SPI-11 + TEST-17). SEC-05 + SEC-06 each split-mapped across two phases for entity-specific sub-policies.
