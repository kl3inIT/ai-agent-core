# Phase 13: Chat Task File — Attach + LLM Read + Bulk Save — Specification

**Created:** 2026-05-05
**Ambiguity score:** 0.094
**Requirements:** 11 locked

> **Phase scope rewritten 2026-05-05.** Original Phase 13 scope (STT + Task-Scoped File) was split during spec interview. STT moves to a new **Phase 15** (Soniox provider + browser MediaRecorder + STT audit). Phase 13 now centers on **attach file + LLM-driven processing** with `bulk_save_records` as the headline new tool. ROADMAP.md must be updated to reflect this split (new Phase 15, retitled Phase 13, REQ-IDs STT-* + SPI-11 + TEST-17 reassigned to Phase 15).

## Goal

Users can attach files (xlsx, pdf, docx, csv, png, jpg, …) to a chat turn; the LLM reads the file content directly via Spring AI `Media` (multimodal model), and can act on it through the existing Phase 9–11 tool surface plus a new `bulk_save_records` tool that persists multiple entities in a single audited transaction.

## Background

Phase 12 shipped `ChatPanelFragment` with a hidden `<vbox id="attachmentsPanel" visible="false"/>` placeholder slot in `chat-panel-fragment.xml:41`. There is no entity, no UI affordance, and no chat-time file consumption today. The closest existing pattern is `D:/DTH/jmix-crm` (`AiConversationAttachment` entity + `AiAttachmentMediaResolver`), which feeds attachments to a multimodal `gpt-5.4` ChatClient via `chatClient.prompt().user(u -> u.media(...))`. Default chat model in this repo is currently `openai/gpt-4o-mini` (text+image only, not document-aware) — Phase 13 swaps it to `qwen/qwen3.6-35b-a3b` (Apache 2.0, 35B/3B MoE, 262K context, multimodal text+image+video, tool-calling stable, ~17GB INT4 self-host footprint).

Phase 11 ships `MutationSaveExecutor` for single-entity `create_record` / `update_record` with full Phase 11 gating chain (`LlmExposurePolicy.canCreate` → `AccessManager` per-attribute → idempotency reservation → `MutationGuard` → `@Transactional DataManager.save` → audit). Phase 13 needs a batch variant because LLM looping `create_record` 10× costs ~10 LLM round-trips (~20–50s) plus 10 audit rows + 10 idempotency rows for a typical xlsx import.

The host has explicitly required: (1) Qwen3.6-35B-A3B Apache 2.0 self-hostable model, (2) admin can override per-conversation via existing `AiParametersDetailView` (Phase 6), (3) attach + LLM-read is the productive UX, (4) bulk-import is a *capability* of an "AI agent thực thụ" — not a separate UI flow.

## Requirements

1. **Default model swap to Qwen3.6-35B-A3B**: The shipped default chat model is updated to a multimodal model that reads attached file bytes natively.
   - Current: `jmix.ai-agent.defaults.model=openai/gpt-4o-mini` and `spring.ai.openai.chat.options.model=openai/gpt-4o-mini` in `jmix-app/src/main/resources/application.properties:54,63`. Embedding model already `qwen/qwen3-embedding-4b`.
   - Target: both keys set to `qwen/qwen3.6-35b-a3b`. Embedding model unchanged. Operator docs note: model is Apache 2.0; OpenRouter API path keeps `https://openrouter.ai/api`; for self-host swap `spring.ai.openai.base-url` to internal vLLM/Ollama endpoint.
   - Acceptance: `jmix-app` boots with the new default; an integration test asserts `chatClient` resolves with `model=qwen/qwen3.6-35b-a3b` when no `AiParameters.model` override is set.

2. **`AiTaskFile` entity in `agentstore`** (ENT-07): Persistent metadata for an attached file scoped to a single conversation, separate from `AiKnowledgeDocument`.
   - Current: no `AiTaskFile` class, no Liquibase changelog, no role policy.
   - Target: JPA entity in `com.vn.agent.entity` annotated `@Store("agentstore")`, fields: `id` (UUID), `conversationId` (FK to `AiConversation`), `userUsername`, `filename`, `contentType`, `sizeBytes`, `storageRef` (`@PropertyDatatype("fileRef")`), `createdAt`, `expiresAt` (default `now + 1h`). Liquibase changelog `090-ai-task-file.xml` included from root `agentstore-changelog.xml`. Bilingual messages added.
   - Acceptance: entity passes `@SpringBootTest` Jmix bootstrap; row insert+load roundtrip via `DataManager` succeeds against `agentstore`; `Liquibase` migration on test schema creates `AI_TASK_FILE` table.

3. **UI attach affordance in `attachmentsPanel`**: Both Phase 12 surfaces (`FULL_ROUTE` + `HEADER_BUTTON` dialog) gain a single attach button + chip list of attached files.
   - Current: `chat-panel-fragment.xml:41` has `<vbox id="attachmentsPanel" visible="false"/>` placeholder; no controller wiring.
   - Target: `attachmentsPanel` shows when conversation active; embeds Jmix `Upload` component (max 100MB, server-side `UploadHandler.toFile`); on successful upload, persists `AiTaskFile` row + `FileStorage` blob (default `local`); renders chips listing filename + type + remove icon. Visible identically in `ChatView` and `ChatDialogView` because both mount the same `ChatPanelFragment`.
   - Acceptance: in `@UiTest`, attach button appears in both surfaces; uploading a 1KB `.txt` adds 1 chip and creates 1 `AiTaskFile` row; `IngesterManager` is NOT called (TEST-16).

4. **`AiTaskFileMediaResolver` + `Media` injection** (TASK-04 reinterpretation): The LLM reads attached file content **directly** in the same chat turn — file bytes flow into the user message as Spring AI `Media`, modelled on `AiAttachmentMediaResolver` from `jmix-crm`.
   - Current: `DefaultChatServiceImpl.ask(...)` and `.stream(...)` build a `chatClient.prompt().user(text)` with no media path; `AiTaskFile` does not exist.
   - Target: `AiTaskFileMediaResolver` (`@Component`) loads `AiTaskFile` rows for the current conversation that are still active (not expired) and returns `List<Media>` populated from `FileStorage`. `DefaultChatServiceImpl` invokes the resolver per turn and injects `.user(u -> u.media(media.toArray(new Media[0])))` whenever the list is non-empty. Resolver follows the CRM MIME map verbatim: pdf, csv, doc, docx, xls, xlsx, html, txt, md, png, jpg/jpeg, gif, webp.
   - Acceptance: integration test attaches 1 file, sends a chat message, asserts the outbound `Prompt` contains a `UserMessage` with at least one `Media` whose bytes equal the original upload; assistant turn returns 200 OK; `IngesterManager` invocation count remains zero (TEST-16).

5. **`bulk_save_records` tool**: New `@Tool`-annotated method on a built-in mutation tool class that creates multiple entities of the same type in a single transaction, reusing the Phase 11 gating chain.
   - Current: Phase 11 ships single-record `create_record`, `update_record`, `add_related_record`, `remove_related_record` only.
   - Target: `BuiltInMutationTools.bulkSaveRecords(String entityName, List<Map<String,Object>> records, String idempotencyKey)`. For each record: per-attribute `EntityAttributeContext.canModify` check, type coercion, optional `MutationGuard.veto`. All saves run inside one `@Transactional` span via an extended `MutationSaveExecutor.bulkSave(SaveContext)`. One `AiMutationIntent` row covers the whole batch (`requestHash` over all records). Audit row event=`bulk_save_records`, `argumentsJson` = `{count, entityName, sampleHashes}` (no raw record values), `outcome` = `SUCCESS` or `FAILED` with `failedRowIndex`. Per-row failure (validation, AccessDenied, MutationGuard veto) **rolls back the entire batch** — no partial commit.
   - Acceptance: integration test calls `bulkSaveRecords("Customer", [10 valid records], "k1")` → 10 rows created, 1 audit row, 1 `AiMutationIntent` row; replay with `"k1"` returns `IDEMPOTENT_REPLAY`; calling with 9 valid + 1 with denied attribute → 0 rows created, 1 audit row outcome=FAILED with failedRowIndex=9.

6. **TTL cleanup job**: `AiTaskFile` rows + their `FileStorage` blobs are deleted automatically when expired.
   - Current: no scheduled cleanup; no `AiTaskFile` exists.
   - Target: `AiTaskFileCleanupJob` (`@Scheduled(cron = "0 0 * * * *")` — hourly) loads expired rows via `UnconstrainedDataManager`, deletes blob via `FileStorage.removeFile(fileRef)`, then deletes the row. Opportunistic cleanup: `DefaultChatServiceImpl` purges current-conversation expired rows on each chat turn entry. Default TTL = 1 hour; configurable via `ai-agent.task-file.ttl-seconds=3600`.
   - Acceptance: integration test inserts a row with `expiresAt = now() - 1m`, runs the job manually, asserts row + blob both deleted.

7. **Role extensions** (SEC-06 partial): End users can read+create their own `AiTaskFile` rows; admins manage all rows.
   - Current: `AiAgentUserRole` covers `AiConversation` + `AiMessage` only; `AiAgentAdminRole` covers admin entities.
   - Target: `AiAgentUserRole.userAccess()` adds `@EntityPolicy(entityClass=AiTaskFile, actions={READ, CREATE})`. `AiAgentUserRowLevelRole` adds JPQL row-level predicate restricting `AiTaskFile` to `userUsername = :current_user_username`. `AiAgentAdminRole` adds `@EntityPolicy(entityClass=AiTaskFile, actions=ALL)` + `@MenuPolicy`/`@ViewPolicy` if a future admin list view is added (Phase 13 ships no admin view; deferred).
   - Acceptance: `@SpringBootTest` with two test users — userA can read/create only own rows; userB cannot see userA's rows; admin reads both.

8. **Files NEVER reach `VectorStore` / `IngesterManager`** (TEST-16): Task-file attach is structurally disjoint from KB ingestion.
   - Current: KB upload path runs `IngesterManager.ingestAsync(...)`; no task-file path exists.
   - Target: code review enforced — task-file upload pathway has zero references to `IngesterManager`, `VectorStore`, `RetrievalAugmentationAdvisor`, or any RAG splitter; task-file `FileStorage` writes use a separate code path from the RAG ingestion executor.
   - Acceptance: TEST-16 runs an end-to-end chat turn with an attached `.pdf`, asserts pgvector `count(*)` is unchanged before vs after; static analysis test asserts `BuiltInTaskFileTools` / `AiTaskFileMediaResolver` source files contain no `IngesterManager` or `VectorStore` references.

9. **Both Phase 12 surfaces support attach**: Mounted attach affordance is identical across `FULL_ROUTE` (`ChatView`) and `HEADER_BUTTON` (`ChatDialogView`).
   - Current: `attachmentsPanel` slot exists in `ChatPanelFragment` but is `visible="false"`; both surfaces share the same fragment via Phase 12 `ChatSurfaceMounter`.
   - Target: enabling `attachmentsPanel` in the fragment automatically lights up both surfaces. No surface-specific override.
   - Acceptance: `@UiTest` `CrossSurfaceTaskFileTest` opens `ChatView`, attaches a file, switches surface to `ChatDialogView`, asserts the attached file chip is still present and its `AiTaskFile` row still active (uses the same Phase 12 `AiChatSessionState.currentConversationId`).

10. **MIME allowlist + file-size cap reuse RAG defaults**: No new config knobs for size; MIME list mirrors `jmix-crm` exactly.
    - Current: `jmix.ai-agent.rag.upload.max-file-size-bytes=104857600` (100 MB) governs KB upload.
    - Target: task-file upload reuses the same byte cap (`jmix.ai-agent.task-file.max-file-size-bytes=104857600` default, but documented as deliberately equal to the RAG cap). MIME allowlist hard-coded in `AiTaskFileMediaResolver` matches `AiAttachmentMediaResolver` from jmix-crm: `pdf, csv, doc, docx, xls, xlsx, html, txt, md, png, jpg/jpeg, gif, webp`. Unsupported MIME → upload rejected at server side with localized error message.
    - Acceptance: upload of 101 MB file → rejected with file-size error; upload of `.exe` → rejected with unsupported-MIME error; upload of `.xlsx` 5 MB → succeeds.

11. **`AiParameters.model` override surface unchanged**: Per-conversation model override continues to work via existing Phase 6 admin UI.
    - Current: `AiParametersDetailView` (`parameters-detail-view.xml`) exposes `model` field; `AiParametersResolver` loads per-conversation override or falls back to `jmix.ai-agent.defaults.model`.
    - Target: zero changes. Admin can override `qwen/qwen3.6-35b-a3b` for a specific conversation by editing the AiParameters row.
    - Acceptance: existing Phase 6 tests continue to pass; manual smoke: change model in admin UI → next chat turn uses the override.

## Boundaries

**In scope:**
- `AiTaskFile` entity + Liquibase 090 + bilingual messages
- UI attach affordance in `attachmentsPanel` (chip list + remove)
- `AiTaskFileMediaResolver` + `Media` injection into `DefaultChatServiceImpl`
- `bulk_save_records` tool extending `MutationSaveExecutor` for atomic batch save (rollback-all)
- TTL cleanup hourly + opportunistic on chat-send
- `AiAgentUserRole` + `AiAgentUserRowLevelRole` + `AiAgentAdminRole` extensions for `AiTaskFile`
- Default model swap to `qwen/qwen3.6-35b-a3b` (multimodal, Apache 2.0)
- TEST-16 (no VectorStore touch) + integration tests for `bulk_save_records` (idempotency, partial-failure rollback, audit/intent row count)
- ROADMAP.md update note that STT moves to a new Phase 15

**Out of scope:**
- **STT (browser MediaRecorder + Soniox transcription)** — moved to a new Phase 15 (`STT-01..06`, `SPI-11`, `TEST-17`); Phase 13 ships no audio path
- **`ChatModelRouter` / dual-model routing** — single model `qwen/qwen3.6-35b-a3b` covers both text and file turns; defer dual-model split to Phase 15+ if hardware/cost data justifies
- **`TaskFileContentExtractor` SPI / Apache POI / Tika server-side parser** — not needed when the model reads binaries natively; do NOT add `poi-ooxml` dependency
- **Admin list view for `AiTaskFile` rows** — task files are transient; no curation UI in v1.1
- **`delete_record` mutation tool** — explicitly reserved for v1.2 per Phase 11 cross-cutting constraint
- **Continue-on-error bulk save** — Phase 13 ships rollback-all only; per-row sub-transaction mode deferred until a host requests it
- **Soniox key in `application.properties`** — Phase 15 owns Soniox config
- **Schema-driven xlsx → Entity Inspector import** — original "Path 2" idea explicitly dropped; LLM + `bulk_save_records` covers the bulk-create UX without coupling to Entity Inspector internals
- **`prepare_form_draft` tool / single-record extraction → form prefill** — owned by Phase 14; Phase 13 ships no extraction primitive

## Constraints

- **Self-host requirement**: every recommended model MUST be Apache 2.0 / similar open-weights. `qwen/qwen3.6-35b-a3b` is Apache 2.0 (~17 GB GPU INT4 / ~35 GB INT8). Proprietary models (Qwen3.6 Plus/Flash, GPT-4o, Claude) are excluded from defaults.
- **Phase 11 mutation chain integrity**: `bulk_save_records` MUST run through the same gating order — `LlmExposurePolicy.canCreate/canUpdate` → `AccessManager` `CrudEntityContext` + per-attribute `EntityAttributeContext.canModify` → idempotency reservation (one row per batch) → type coercion → optional `MutationGuard` → `@Transactional DataManager.save` (regular `DataManager`, never `Unconstrained`). System-internal idempotency rows continue to use `UnconstrainedDataManager` per existing Phase 11 invariant.
- **No cross-store schema changes outside `agentstore`**: `AiTaskFile` lives in `agentstore` only; `FileStorage` blob storage uses the default `local` storage shipped by Jmix.
- **Phase 12 fragment contract**: `attachmentsPanel` slot is the integration point; do NOT add raw Vaadin overlays or modify `messageInputSlot` to host attach UI.
- **Servlet multipart**: `spring.servlet.multipart.max-file-size=100MB` and `max-request-size=110MB` in `application.properties:79-80` already accommodate the 100 MB task-file cap; no changes required.
- **Audit reuse**: `bulk_save_records` audits via `AuditWriter.writeToolCall` only (`eventName=bulk_save_records`, single row per batch). No new `AuditKind`.
- **Locale parity**: every new message key (entity attribute label, upload error, audit caption) MUST land in BOTH `messages.properties` and `messages_vi.properties`.

## Acceptance Criteria

- [ ] `jmix.ai-agent.defaults.model` and `spring.ai.openai.chat.options.model` in `application.properties` both equal `qwen/qwen3.6-35b-a3b`
- [ ] `AiTaskFile` entity boots cleanly under `agentstore`; `AI_TASK_FILE` table created by Liquibase 090
- [ ] Attach UI affordance is visible in BOTH `ChatView` (FULL_ROUTE) and `ChatDialogView` (HEADER_BUTTON) for the same conversation
- [ ] Uploading a file creates exactly one `AiTaskFile` row + one `FileStorage` blob; zero `IngesterManager` invocations; `VectorStore` count unchanged (TEST-16)
- [ ] Sending a chat turn with attached file produces a `Prompt` whose `UserMessage` contains at least one `Media` with non-empty bytes equal to the uploaded file
- [ ] `bulk_save_records("Customer", [10 valid], "k1")` creates 10 rows, 1 audit row event=`bulk_save_records`, 1 `AiMutationIntent` row
- [ ] Re-calling `bulk_save_records` with the same `idempotencyKey` returns `IDEMPOTENT_REPLAY` and creates no additional rows
- [ ] `bulk_save_records` with one denied or invalid record rolls back the entire batch; audit row outcome=`FAILED` with `failedRowIndex`; zero rows persisted
- [ ] User A cannot read/list user B's `AiTaskFile` rows (row-level policy enforced)
- [ ] `AiTaskFileCleanupJob` deletes rows + blobs whose `expiresAt < now()` when run hourly
- [ ] Upload of `.exe` rejected with localized unsupported-MIME error; upload > 100 MB rejected with localized size error
- [ ] No source file under `com.vn.agent.taskfile` references `IngesterManager`, `VectorStore`, `RetrievalAugmentationAdvisor`, or any RAG splitter

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                                                       |
|--------------------|-------|------|--------|---------------------------------------------------------------------------------------------|
| Goal Clarity       | 0.93  | 0.75 | ✓      | 7 deliverables explicit; rewritten goal reflects locked scope                               |
| Boundary Clarity   | 0.92  | 0.70 | ✓      | STT split out, dual-model deferred, server-parser out, Entity Inspector path explicitly dropped |
| Constraint Clarity | 0.90  | 0.65 | ✓      | Self-host Apache 2.0, hardware footprint, Phase 11 gating order, locale parity all locked    |
| Acceptance Criteria| 0.85  | 0.70 | ✓      | 11 pass/fail checks; per-row failure semantics + idempotency replay covered                  |
| **Ambiguity**      | 0.094 | ≤0.20| ✓      |                                                                                             |

## Interview Log

| Round | Perspective       | Question summary                                                                 | Decision locked                                                                              |
|-------|-------------------|---------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| 0     | Researcher (auto) | Initial REQ-IDs + roadmap parse                                                  | 11 REQ-IDs (STT-01..06, TASK-01..05, ENT-07, SPI-11, SEC-06 partial, TEST-16/17) anchor      |
| 1     | Boundary+Failure  | File cap, TTL cleanup, STT audit row shape, surface availability                 | 100 MB reuse RAG cap, hourly job + opportunistic, STT audit deferred (split), both surfaces  |
| 2     | User pivot        | STT vendor (OpenAI → Soniox), attach behavior, bulk-import question              | Soniox docs verified (no Java SDK, REST 4 endpoints); attach + LLM-read CRM pattern adopted |
| 3     | User reframe      | "AI agent thực thụ" — Phase 13 không phải bulk-import code path mà là agent     | Drop dedicated bulk-import path; LLM uses existing Phase 9–11 tools                          |
| 4     | Performance       | Mutation 10× → too slow; Jmix bulk pattern?                                     | Add `bulk_save_records` tool extending `MutationSaveExecutor` (atomic, rollback-all)         |
| 5     | Model selection   | Default model swap to support multimodal file reads                              | Initial: dual Qwen3-Next + Qwen3-VL                                                          |
| 6     | Self-host gate    | User constraint: model phải self-host được (Apache 2.0)                          | Single Qwen3.6-35B-A3B (Apache 2.0, multimodal, tool-calling stable, ~17 GB INT4)            |
| 7     | Routing decision  | Chat thường Qwen3-Next + file Qwen3.6 routing — có nên không?                    | KHÔNG — single Qwen3.6-35B-A3B; hardware cost > token saving; defer dual to Phase 15+       |
| 8     | Phase split       | STT vs Attach — ship gì trước                                                   | Phase 13 = Attach + bulk_save_records; Phase 15 (mới) = STT (Soniox)                         |
| 9     | Final lock        | "chốt đi"                                                                        | Single-model Qwen3.6-35B-A3B, no dual routing, no server parser, copy CRM Media pattern      |

---

*Phase: 13-chat-task-input-stt-task-scoped-file (directory name preserved from `init phase-op`; phase title rewritten — ROADMAP.md to be updated)*
*Spec created: 2026-05-05*
*Next step: `/gsd-discuss-phase 13` — implementation decisions (entity field types, FileStorage layout, exact tool method signature, role-policy XML, audit `argumentsJson` shape, message keys)*
