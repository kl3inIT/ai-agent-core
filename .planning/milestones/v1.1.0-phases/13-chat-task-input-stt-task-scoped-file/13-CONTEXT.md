# Phase 13: Chat Task File — Attach + LLM Read + Bulk Save — Context

**Gathered:** 2026-05-05
**Status:** Ready for planning

<domain>
## Phase Boundary

UI affordance + storage + LLM-driven processing for files attached during a chat turn — file bytes flow into the LLM via Spring AI `Media` (multimodal Qwen3.6-35B-A3B); a new `bulk_save_records` tool extends the Phase 11 mutation chain to persist multiple host entities in a single audited transaction. Attach pathway is structurally disjoint from KB ingestion (`IngesterManager` / `VectorStore`).

**Scope rewrite vs ROADMAP/REQUIREMENTS as written:** original Phase 13 (STT + task-scoped file) was split during spec interview. STT (`STT-01..06`, `SPI-11`, `TEST-17`) moves to a new **Phase 15** (Soniox provider via custom Spring `RestClient` + browser `MediaRecorder` + STT audit). Phase 13 now centers on **attach file + LLM-driven processing** with `bulk_save_records` as the headline new tool. ROADMAP.md must be updated after CONTEXT approval — add Phase 15, retitle Phase 13, reassign STT REQ-IDs.

</domain>

<spec_lock>
## Requirements (locked via SPEC.md)

**11 requirements are locked.** See `13-SPEC.md` for full requirements, boundaries, and acceptance criteria.

Downstream agents MUST read `13-SPEC.md` before planning or implementing. Requirements are not duplicated here.

**In scope (from SPEC.md):**
- `AiTaskFile` entity + Liquibase 090 + bilingual messages
- UI attach affordance in `attachmentsPanel` (chip list + remove)
- `AiTaskFileMediaResolver` + `Media` injection into `DefaultChatServiceImpl`
- `bulk_save_records` tool extending `MutationSaveExecutor` for atomic batch save (rollback-all)
- TTL cleanup hourly + opportunistic on chat-send
- `AiAgentUserRole` + `AiAgentUserRowLevelRole` + `AiAgentAdminRole` extensions for `AiTaskFile`
- Default model swap to `qwen/qwen3.6-35b-a3b` (multimodal, Apache 2.0)
- TEST-16 (no VectorStore touch) + integration tests for `bulk_save_records` (idempotency, partial-failure rollback, audit/intent row count)
- ROADMAP.md update note that STT moves to a new Phase 15

**Out of scope (from SPEC.md):**
- STT (browser MediaRecorder + Soniox transcription) — moved to Phase 15
- `ChatModelRouter` / dual-model routing — single model covers text and file turns
- `TaskFileContentExtractor` SPI / Apache POI / Tika server-side parser — model reads binaries natively
- Admin list view for `AiTaskFile` rows — task files are transient
- `delete_record` mutation tool — reserved for v1.2
- Continue-on-error bulk save — Phase 13 ships rollback-all only
- Soniox key in `application.properties` — Phase 15 owns Soniox config
- Schema-driven xlsx → Entity Inspector import — explicitly dropped
- `prepare_form_draft` tool / single-record extraction — Phase 14

</spec_lock>

<decisions>
## Implementation Decisions

### D-01: Media injection cadence — single-turn inject (jmix-crm pattern)

Inject Spring AI `Media` ONLY on the user turn that newly attaches files. Subsequent turns receive an empty `Media` list and rely on the assistant's text response (already persisted in `JdbcChatMemoryRepository`) for follow-up reasoning.

**Why:** `JdbcChatMemoryRepository` (Phase 12 chat-memory store) persists only `content TEXT` — `Media` bytes are NOT serialized. Re-injecting every turn would force resolver to re-read `AiTaskFile` bytes from `FileStorage` and re-encode for every model call (5–15× token cost over a typical 5–15-turn enterprise CRUD conversation; xlsx 5–20K tokens × 10 turns = 50–200K redundant tokens). `D:/DTH/jmix-crm` `CrmAnalyticsService.processBusinessQuestionInternal` confirms: Media injected only on upload-event turn, subsequent calls pass `List.of()`. With Qwen3.6-35B-A3B 262K context, the assistant's first-turn paraphrase covers fine-grained recall for the typical use case.

**Implication for SPEC.md REQ-4:** SPEC currently says "DefaultChatServiceImpl invokes the resolver per turn and injects `.user(u -> u.media(...))` whenever the list is non-empty". Tighten to: "Resolver returns only files where `messageId IS NULL` (newly attached, not yet sent). On send, injection happens once and the resolver clears the pending set by setting `messageId` to the just-persisted user message."

**TTL-expiry edge case:** non-issue under D-01 — file was never going to be re-injected after turn 1 anyway; assistant text summary in chat memory survives expiry.

**Future escalation path (Phase 13.x):** Option D from advisor research — opt-in re-hydrate when user message references the file by name/keyword (regex match on filename), only if telemetry shows recall complaints.

### D-02: `bulk_save_records` semantics — mixed batch with id-presence dispatch

Single `@Tool` method `bulk_save_records(String entityName, List<Map<String,Object>> records, String idempotencyKey)`. Dispatch per-row: `id != null` → update path (load existing, apply changes); `id == null` → create path (new entity instance). One transaction, one `AiAuditEvent` row (`eventName=bulk_save_records`), one `AiMutationIntent` row.

**Why:** Phase 13 must support both stated use cases (xlsx-onboarding creates AND PDF-driven updates) under a single tool because (a) Qwen3 small-model tool-call reliability degrades as tool count grows ([QwenLM/Qwen3-Coder #475](https://github.com/QwenLM/Qwen3-Coder/issues/475)); (b) Phase 13 invariants (1 audit + 1 intent + 1 transaction per batch) forbid splitting into `bulk_create_records` + `bulk_update_records`; (c) Phase 11 chain (`canCreate/canUpdate`, `EntityAttributeContext.canModify`, `MutationGuard`, `MutationErrorTranslator`) handles both modes uniformly.

**`requestHash` strategy:** SHA-256 over canonical JSON of records in **submission order** (NOT order-independent). Stripe-style idempotency expects byte-identical retries; order-independent hash is a footgun if row 4 references row 1's create.

**Per-row failure → rollback-all:** validation, AccessDenied, MutationGuard veto on any row → entire batch rolls back; audit `outcome=FAILED` with `failedRowIndex` + `failedAttribute` (when available). Error message format: `"row 4 (update id=...): <stable-error-code>"` or `"row 7 (create): <stable-error-code>"` — never echoes user-supplied PII.

**Rich `@Tool` description (per memory `feedback_rich_tool_descriptions`):** 5-section MANDATORY/INPUT/FORMATS/ERROR/STRICTNESS+EXAMPLES with TWO worked examples (xlsx-create-only batch + mixed PDF-update batch). FORMATS section explicitly states: "omit `id` to create; include `id` to update; never include `id: null`".

**Migration path B → E:** if UAT shows Qwen3 confuses create vs update on rows with partially-extracted ids, escalate to explicit `operation: "CREATE" | "UPDATE"` enum field. Migration is additive (add required enum) and does NOT break audit/intent shape.

### D-03: `AiTaskFile` data model — both FKs (conversationId required, messageId nullable)

Schema:
- `id` UUID PK
- `conversation_id` UUID NOT NULL FK → `AI_CONVERSATION` (cascade)
- `message_id` UUID NULL FK → `AI_MESSAGE` ON DELETE SET NULL
- `user_username` VARCHAR
- `filename` VARCHAR
- `content_type` VARCHAR
- `size_bytes` BIGINT
- `storage_ref` VARCHAR(1024) `@PropertyDatatype("fileRef")`
- `created_at` TIMESTAMP
- `expires_at` TIMESTAMP (default `now + 1h`)
- audit fields

**Why:** D-01 (single-turn inject) requires the resolver to distinguish "newly attached, not yet sent" rows from rows already injected in a prior turn. Persisting this state in DB (via nullable `messageId`) instead of `@VaadinSessionScope` survives session restart / tab close — orphan rows would otherwise accumulate. This adopts research option (c). The conversation-scoped-only option (research recommendation under re-inject-every-turn assumption) becomes inadequate once D-01 locks single-turn inject.

**Two-phase write:**
1. Upload event → `INSERT` row with `conversationId` + `messageId = NULL`
2. User clicks send → `ChatService.ask` resolves Media from rows with `messageId IS NULL`, persists new `AiMessage` row, then `UPDATE AiTaskFile SET messageId = newMessageId WHERE id IN (resolvedIds)`

**UI history replay:** chips render exactly on the owning AiMessage bubble (FK lookup, no fragile timestamp correlation).

**Audit:** `AiAuditEvent.argumentsJson` references `AiTaskFile.id` directly — no AiMessage join needed.

**TTL cleanup:** hourly job ignores `messageId` — only `expiresAt < now()`. Expired files: `FileStorage.removeFile(fileRef)` then `DELETE` row. Chip in old AiMessage rendering: storageRef gone → chip silently absent (no dead-link). Future v1.2 may surface placeholder text if TTL extends to days.

### D-04: Upload UI affordance — Button + chip list above MessageInput

Implementation pattern:
- `attachmentsPanel` (Phase 12 hidden slot in `chat-panel-fragment.xml:41`) becomes a `<vbox>` containing a chip-strip `<hbox>` (filename + remove icon per chip, wrap on overflow)
- `messageInputSlot` extends to host: existing `streamProgressBar`, existing `MessageInput`, plus a new attach-button row
- Hidden Jmix `<upload>` triggered programmatically by visible `JmixButton` ("Attach" icon) — this keeps the affordance compact in 35%-width HEADER_BUTTON dialog
- `<upload>` uses **`UploadHandler.toFile`** (per project memory `feedback_jmix_upload_receiver_deprecated`) — NOT `setReceiver(MultiFileTemporaryStorageBuffer)` despite jmix-crm's pattern using the latter; Vaadin 24.8 marks `Upload.getReceiver/setReceiver` `forRemoval`. **Planner MUST verify** the exact jmix-flowui 2.8 API for multi-file upload via `UploadHandler` — earlier-phase memory only documented single-file `toFile`.
- Chip rendering: programmatic in controller (no Jmix chip primitive); CSS classes `ai-agent-chat-panel__chips` + `ai-agent-chat-panel__chip` for theming
- Multi-file selection allowed; per-file size cap 100MB (mirrors `jmix.ai-agent.rag.upload.max-file-size-bytes`); MIME allowlist hard-coded mirroring jmix-crm `AiAttachmentMediaResolver` (13 types)
- BOTH surfaces (FULL_ROUTE + HEADER_BUTTON dialog) get identical UX because both mount the same `ChatPanelFragment` — no surface conditional

**Why not E (jmix-crm right-pane split):** at HEADER_BUTTON dialog 35%-width × splitterPosition=68 right pane shrinks to ~165px → unusable for card grid. Would require runtime split reconfiguration + fallback chip strip → 2 layouts to maintain.

**Why not B (drop-zone full-width):** 80–120px vertical eats meaningful chat surface in 75%-h dialog.

**Why not C (paperclip in MessageInput):** Vaadin `MessageInput` exposes no prefix/suffix slot; composite layout fights the internal Send button.

### Claude's Discretion

- Exact CSS class names for chip strip + chip element (Lumo-compatible naming convention)
- Whether to show upload progress per-file (research suggests yes; planner picks Vaadin progress placement)
- `application.properties` exact key naming for `ai-agent.task-file.ttl-seconds` (default 3600) — match Phase 11 `idempotencyTtl` style
- Bulk-save error code surface — reuse Phase 11 6-code taxonomy or add a 7th code for `bulk_validation_failed`? Researcher recommends reuse + row-index suffix; planner verifies Phase 11 `MutationErrorTranslator` API surface

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### SPEC + Roadmap
- `.planning/phases/13-chat-task-input-stt-task-scoped-file/13-SPEC.md` — Locked requirements (11), boundaries, acceptance criteria. MUST read first.
- `.planning/ROADMAP.md` §"Phase 13" — original phase goal (must be amended after CONTEXT approval)
- `.planning/REQUIREMENTS.md` ENT-07, TASK-01..05, SEC-06 (partial), TEST-16 — locked REQ-IDs after STT split

### Cross-phase decisions (carrying forward)
- `.planning/phases/12-configurable-chat-surfaces/12-CONTEXT.md` — `attachmentsPanel` slot location, `AiChatSessionState`, `ChatSurfaceMounter`, dual-surface contract
- `.planning/phases/11-mutation-capable-built-in-tools/11-CONTEXT.md` — `MutationSaveExecutor` extension point, `MutationGuard` SPI, `AiMutationIntent` schema, `MutationErrorTranslator` 6-code taxonomy, `AuditWriter.writeToolCall` REQUIRES_NEW pattern, `AiToolCallOutcome` enum
- `.planning/phases/11-mutation-capable-built-in-tools/11-VERIFICATION.md` — invariants verified after gap closure plans
- `.planning/phases/09-tool-layer-foundations-prompt-contract-hardening/` — `LlmExposurePolicy` contract, `agent.entities`/`agent.permissions` baseline, `ToolEntityResolver`

### Reference implementation
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/model/AiConversationAttachment.java` — entity shape with `FileRef` + conversation FK
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/service/AiAttachmentMediaResolver.java` — Spring AI Media resolver pattern (MIME map, FileStorage read, name sanitization)
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/service/CrmAnalyticsService.java` lines 91–159 — `chatClient.prompt().user(u -> u.media(...))` injection sequence (single-turn-only injection per D-01)
- `D:/DTH/jmix-crm/src/main/resources/com/company/crm/ai/view/aiconversation/ai-conversation-detail-view.xml` — split-panel reference layout (rejected for HEADER_BUTTON, but informs FULL_ROUTE optional escalation)
- `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/view/aiconversation/AiConversationDetailView.java` lines 157–217 — upload flow with `MultiFileTemporaryStorageBuffer` + `SucceededEvent` (CAVEAT: receiverType API deprecated — use `UploadHandler.toFile` per project memory)

### Project memories (locked decisions)
- `project_self_hostable_models_only.md` — Apache 2.0 weights only; default model `qwen/qwen3.6-35b-a3b`
- `feedback_ai_as_jmix_client.md` — security via Jmix AccessManager/DataManager; no AI-specific exposure layer
- `feedback_reuse_jmix_builtins.md` — own only thin LLM adapter; reuse Metadata/AccessManager/DataManager/FetchPlan
- `feedback_jmix_unconstrained_for_system_writes.md` — `UnconstrainedDataManager` for system-internal writes (TTL cleanup, audit, idempotency)
- `feedback_jmix_upload_receiver_deprecated.md` — Vaadin 24.8 `Upload.getReceiver/setReceiver` is `forRemoval`; use `UploadHandler.toFile`
- `feedback_jmix_first_ui.md` — Jmix XML view descriptors over raw Vaadin
- `feedback_jmix_view_listeners.md` — `@Subscribe`/`@Install` for event wiring; verify uncertain syntax via Context7 → Jmix docs → GitHub
- `feedback_jmix_messages_over_spring.md` — inject `io.jmix.core.Messages` (auto-locale)
- `feedback_jmix_loadvalue_store.md` — `loadValue/loadValues` for raw JPQL on agentstore needs explicit `.store("agentstore")`
- `feedback_rich_tool_descriptions.md` — 5-section MANDATORY/INPUT/FORMATS/ERROR/STRICTNESS+EXAMPLES (~50–150 lines) for mutation/data tools

### Spring AI / Jmix framework refs
- Spring AI 1.1.4 Chat Memory schema (`spring-ai-model-chat-memory-repository-jdbc/.../schema-postgresql.sql`) — confirms text-only persistence (foundational for D-01)
- Spring AI Multimodality (`spring-ai-docs/.../api/multimodality.adoc`) — `Media` API
- Jmix 2.8 framework: `io.jmix.core.FileStorage`, `io.jmix.core.FileStorageLocator`, `@PropertyDatatype("fileRef")`, `UploadHandler.toFile`
- Jmix `entityinspector` add-on — referenced for context (NOT used in Phase 13 per SPEC out-of-scope)

### External / vendor refs
- [Qwen3.6-35B-A3B on OpenRouter](https://openrouter.ai/qwen/qwen3.6-35b-a3b) — model card, 262K context, $0.15/$1.00 per M tokens, Apache 2.0
- [QwenLM/Qwen3-Coder #475](https://github.com/QwenLM/Qwen3-Coder/issues/475) — tool-calling reliability degrades with tool count (basis for D-02 single-tool decision)
- [Stripe Idempotent requests](https://stripe.com/docs/api/idempotent_requests) — byte-identical retry expectation for requestHash strategy

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- **`MutationSaveExecutor`** (Phase 11, `com.vn.agent.tools.mutation`) — extend with `bulkSave(SaveContext)` that runs N entity saves inside one `@Transactional` span; reuse all existing per-entity AccessManager/MutationGuard hooks.
- **`MutationIntentRepository`** (Phase 11) — `reservePending` / `markCommitted` patterns reused for batch — one row covers entire batch keyed by `requestHash` over canonical JSON of all records in submission order.
- **`MutationErrorTranslator`** (Phase 11) — 6-code stable error taxonomy reused; planner decides whether to add 7th code `bulk_validation_failed` or compose existing codes with `failedRowIndex` suffix.
- **`AuditWriter.writeToolCall`** (Phase 7.2) — single `eventName=bulk_save_records` audit row per batch; `argumentsJson` carries `{count, entityName, sampleHashes, idempotencyKey}` (no raw record values per Phase 11 PII invariant).
- **`AiAttachmentMediaResolver`** in `D:/DTH/jmix-crm` — copy verbatim into `com.vn.agent.taskfile.AiTaskFileMediaResolver` adapting query to `WHERE conversationId AND messageId IS NULL` (per D-03).
- **`ChatPanelFragment.attachmentsPanel`** — Phase 12 placeholder slot at `chat-panel-fragment.xml:41`, currently `visible="false"`. Phase 13 turns it into chip-strip container.
- **`AiChatSessionState`** (Phase 12, `@VaadinSessionScope`) — currently holds `currentConversationId`; Phase 13 does NOT extend it (per D-03, pending state lives in DB nullable `messageId`).
- **Jmix `<upload>` + `UploadHandler.toFile`** — standard Jmix file-upload primitive; multi-file via `UploadHandler` (planner verifies exact API in jmix-flowui 2.8).
- **`FileStorage` default `local`** — Phase 5 already provisions; reuse for task-file blobs.

### Established Patterns

- **Phase 11 mutation chain order (must preserve in `bulk_save_records`)**: `LlmExposurePolicy.canCreate/canUpdate` (entity-level once for batch; per-row would double-count) → `AccessManager.applyRegisteredConstraints(CrudEntityContext)` (per-row) → `EntityAttributeContext.canModify` (per-row, per-attribute) → `MutationIntentRepository.reservePending` (once per batch) → type coercion → `MutationGuard.veto` (host SPI, per-row) → `@Transactional DataManager.save` per-row inside one transaction → `MutationIntentRepository.markCommitted` (once per batch) → `AuditWriter.writeToolCall` (once per batch).
- **Phase 11 idempotency hash**: `MutationRequestHasher` computes SHA-256 over canonical JSON; reused for batch with rows in submission order.
- **Phase 11 PII safety**: `MutationErrorTranslator` never echoes user-supplied attribute names or values; canned safe templates only. Bulk error format must follow same rule.
- **Phase 12 dual-surface contract**: `ChatPanelFragment` is the ONLY UI integration point; do NOT introduce surface-specific upload paths.
- **Phase 11 cleanup job pattern**: `MutationIntentCleanupJob` with `@Scheduled(cron = "0 0 * * * *")` (hourly) using `UnconstrainedDataManager`. `AiTaskFileCleanupJob` mirrors this pattern.
- **Liquibase changelog sequence**: agentstore-changelog files follow `NNN-name.xml` ordering. Phase 13 lands at `090-ai-task-file.xml`; included automatically by root `agentstore-changelog.xml`.
- **Locale parity**: every new message key MUST land in BOTH `messages.properties` and `messages_vi.properties` (per CLAUDE.md).
- **Jmix view event wiring**: `@Subscribe`/`@Install` per memory `feedback_jmix_view_listeners`; do NOT use `addListener(...)` in `onInit`.
- **Tool description verbosity**: per memory `feedback_rich_tool_descriptions`, `bulk_save_records` description is 50–150 lines with 5 sections + 2 EXAMPLES.

### Integration Points

- **`DefaultChatServiceImpl.ask(...)` and `.stream(...)`**: insert `AiTaskFileMediaResolver.resolve(conversationId)` call → if non-empty, `.user(u -> u.media(...))` injection → after assistant message persisted, `UPDATE AiTaskFile SET messageId = ...`.
- **`BuiltInMutationTools`** (Phase 11): add `bulk_save_records` `@Tool` method beside existing `create_record`/`update_record`. Same `@ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled", havingValue="true")` gate (default OFF).
- **`AgentToolCallbacks`** (Phase 11): registers mutation tools per-user-session — `bulk_save_records` joins the same registration path. `MutationToolCallbackBoundaryDecorator` wraps it (single-audit-owner invariant preserved).
- **`AgentSystemPromptRules`** (Phase 9 extension point): add prompt rule for `bulk_save_records` — "use bulk for >2 records of same entity; idempotencyKey must be fresh per batch".
- **`ChatPanelFragment`**: subscribe to upload `SucceededEvent` (or jmix-flowui 2.8 equivalent), persist `AiTaskFile`, render chip into `attachmentsChipStrip`. On `MessageInput.submit`, before calling `ChatService.ask`, resolve Media list from pending rows.
- **`AiAgentUserRole.userAccess()`**: add `@EntityPolicy(entityClass=AiTaskFile, actions={READ, CREATE})`.
- **`AiAgentUserRowLevelRole`**: add JPQL row-level predicate `e.userUsername = :current_user_username` for `AiTaskFile`.
- **`AiAgentAdminRole`**: add `@EntityPolicy(entityClass=AiTaskFile, actions=ALL)` for system support / future admin view.
- **`jmix-app/application.properties`**: update `jmix.ai-agent.defaults.model` and `spring.ai.openai.chat.options.model` to `qwen/qwen3.6-35b-a3b`. Add `ai-agent.task-file.ttl-seconds=3600` (default).

</code_context>

<specifics>
## Specific Ideas

- User explicitly said: "an AI agent thực thụ" — Phase 13 is NOT a dedicated bulk-import code path; it is enabling the LLM to read files + use existing tools (Phase 9–11) plus the new `bulk_save_records` for batch. Bulk-import via xlsx is a *side-effect* of LLM calling `bulk_save_records` 1× — NOT a separate UI flow.
- User explicitly rejected dual-model routing (Qwen3-Next text + Qwen3-VL file) due to hardware footprint (~57 GB GPU vs ~17 GB INT4 single Qwen3.6-35B-A3B) and Phase 13 code simplification. Default model is single multimodal Qwen3.6-35B-A3B for ALL turns.
- User explicitly rejected Apache POI server-side parser — model multimodal đọc file binary native; no `poi-ooxml` dependency added.
- User explicitly rejected Soniox STT in Phase 13 — split out to Phase 15.
- Reference UI pattern: `D:/DTH/jmix-crm/.../AiConversationDetailView.xml` + `AiAttachmentCardFragmentRenderer` chip rendering on message bubbles. Phase 13 simplifies to a chip strip above MessageInput (rendering on message bubbles deferred — `messageId` FK enables it for future v1.2).
- Locale: Vietnamese (vi) + English (en); message keys in BOTH `messages_vi.properties` and `messages.properties`.
- Operator deployment: dev uses OpenRouter API (`spring.ai.openai.base-url=https://openrouter.ai/api`, OPENROUTER_API_KEY env); prod self-hosts via vLLM/Ollama on internal endpoint, swap `spring.ai.openai.base-url` only.

</specifics>

<deferred>
## Deferred Ideas

- **STT (Soniox provider)** — moved to Phase 15. Custom Spring `RestClient` (Soniox has no Java SDK) → `/v1/files` (multipart upload) → `/v1/transcriptions` (`{file_id, model: "stt-async-v4", language_hints: ["vi","en"]}`) → poll `GET /v1/transcriptions/{id}` → `GET /v1/transcriptions/{id}/transcript`. Strategy `TranscriptionService` interface with `SonioxTranscriptionService` (default) + optional `SpringAiTranscriptionService` (OpenAI fallback). Property: `ai-agent.stt.provider=soniox|openai|<custom-bean>`. STT_TRANSCRIPTION audit via `writeToolCall` `eventName=stt_transcription`. TEST-17 (audit privacy: hash by default, opt-in raw text via `ai-agent.stt.audit.storeTranscript=true`).
- **`prepare_form_draft` tool** — Phase 14 (intent extraction → form prefill). Phase 14 may consume `AiTaskFile` by id for single-record extraction.
- **Continue-on-error bulk save** — sub-transaction per row, partial success. Not needed for v1.1; revisit if a host requests "import 100 dòng, 95 OK".
- **Explicit `operation` enum on `bulk_save_records`** — D-02 fallback if Qwen3 confuses create vs update during UAT. Migration is additive.
- **`bulk_delete_records`** — destructive ops deferred to v1.2 with separate UX (confirmation/undo); same gate as `delete_record`.
- **Dual-model routing (`ChatModelRouter`)** — text-cheap + vision-strong split. Defer until cost telemetry justifies.
- **Apache POI / Tika server-side text extractor** — for non-multimodal model fallback. Defer until a host runs a text-only chat model.
- **Schema-driven xlsx → Entity Inspector import action** — original "Path 2" idea. Defer; Phase 13 covers bulk via LLM + `bulk_save_records` instead.
- **Admin list view for `AiTaskFile`** — task files are transient; no curation UI needed in v1.1. `AiAgentAdminRole` policy is provisioned for future addition.
- **TTL-extension on file re-reference** — current decision: single 1h TTL from upload. If telemetry shows recall-needed-after-1h cases, consider extending TTL on assistant reference.
- **Chip rendering on message bubbles (history replay)** — current decision: chip strip in attachmentsPanel only (current pending). `messageId` FK on `AiTaskFile` enables future per-message chip rendering similar to jmix-crm `AiAttachmentCardFragmentRenderer`.
- **Per-attribute denial verbose error message** — bulk error currently `"row N: <stable-error-code>"`. If LLM struggles to recover, expose `failedAttribute` in safe-template error.
- **Opt-in re-hydrate on file reference in user message** — D-01 escalation if recall complaints surface in Phase 13.x telemetry.

### Reviewed Todos (not folded)

None — todo cross-reference returned no Phase-13-relevant matches; STT-related todo (`2026-04-24-add-dedicated-chat-speech-and-file-task-input.md`) is partially folded (file-attach part) and partially deferred (STT part → Phase 15).

</deferred>

---

*Phase: 13-chat-task-input-stt-task-scoped-file (directory name preserved; phase title rewritten — ROADMAP.md to be updated)*
*Context gathered: 2026-05-05*
