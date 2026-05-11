# Phase 13: Chat Task File — Attach + LLM Read + Bulk Save — Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in 13-CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-05
**Phase:** 13-chat-task-input-stt-task-scoped-file (title rewritten; original "STT + Task-Scoped File" split during spec — STT moves to new Phase 15)
**Areas discussed:** Media injection cadence, bulk_save_records semantics, AiTaskFile ↔ AiMessage relationship, Upload UI affordance shape

---

## Media injection cadence

| Option | Description | Selected |
|--------|-------------|----------|
| A. Single-turn inject (jmix-crm pattern) | Resolver inject Media chỉ ở turn upload-trigger; turn sau dựa text response. Match Spring AI persistence (JdbcChatMemoryRepository chỉ persist text); cost thấp; đúng như jmix-crm. | ✓ |
| B. Re-inject every turn while active | Inject mỗi turn cho đến khi expire. Fidelity cao nhưng cost tăng 5–15× (xlsx 5–20K tokens × 10 turns). | |
| D. A + opt-in re-hydrate khi user reference file | Default A, regex match filename → re-inject. Cost thấp + cứu được "xem lại xlsx". Heuristic fragile. | |

**User's choice:** A. Single-turn inject (jmix-crm pattern)
**Notes:** Critical technical fact uncovered during research — `JdbcChatMemoryRepository` (Phase 12 chat memory) persists only `content TEXT`; Media bytes are NOT serialized. Re-injecting every turn forces resolver to re-read FileStorage bytes for every model call → 5–15× cost over typical 5–15-turn enterprise CRUD conversation. jmix-crm `CrmAnalyticsService.processBusinessQuestionInternal` confirms single-turn-only injection. Option D kept as Phase 13.x escalation if recall telemetry surfaces complaints.

---

## bulk_save_records semantics

| Option | Description | Selected |
|--------|-------------|----------|
| A. Create-only batch | Chỉ create; xlsx-onboarding only. Không cover PDF→update use case. | |
| B. Mixed batch — id-presence dispatch | `id != null` → update, `id == null` → create. 1 tool, 1 audit, 1 intent, 1 transaction. | ✓ |
| C. Upsert by natural key | Salesforce-style; tool dedupes by `uniqueKeyAttribute`. Premature — no concrete natural-key use case. | |
| D. Tách 2 tool (`bulk_create_records` + `bulk_update_records`) | Mỗi tool semantic rõ. 2 audit rows cho 1 xlsx hỗn hợp → vi phạm "1 audit/batch" invariant. | |
| E. Mixed + explicit `operation` enum (CREATE/UPDATE) | LLM signal explicit; audit hiển thị [7 CREATE, 3 UPDATE]. Thừa field khi 100% creates. | |

**User's choice:** B. Mixed batch — id-presence dispatch
**Notes:** [QwenLM/Qwen3-Coder #475](https://github.com/QwenLM/Qwen3-Coder/issues/475) documents tool-call reliability degrading as tool count grows → single tool preferred over D. Phase 13 invariants (1 audit + 1 intent + 1 transaction per batch) ruled out D. Phase 11 chain handles both create and update modes uniformly via `EntityAttributeContext.canModify` per-row. requestHash strategy: SHA-256 over canonical JSON in **submission order** (NOT order-independent — Stripe-style byte-identical retry). Migration path B → E is additive if UAT shows confusion.

---

## AiTaskFile ↔ AiMessage relationship

| Option | Description | Selected |
|--------|-------------|----------|
| (a) Conversation-scoped only + AiChatSessionState pendingTaskFileIds | Schema: chỉ conversationId FK. Pending list trong @VaadinSessionScope, on send consume+clear. Lost-on-restart → DB orphan. | |
| (b) Message-attached only | FK to AiMessage; turn upload + replay thấy. Upload-before-send race; breaks "real agent" lookback. | |
| (c) Both FKs — conversationId required + messageId nullable | conversationId NOT NULL FK; messageId NULLABLE FK ON DELETE SET NULL. 2-phase write. | ✓ |
| (a-bis) Conversation-scoped + AiTaskFile.injected boolean | conversationId + boolean flag. Persistent như (c) nhưng không có messageId → chip-on-message timestamp correlation. | |
| (d) Ephemeral cache + replay placeholder | Expired files render `[file uploaded: name.xlsx]` text. LLM hallucinate placeholder; UI dead chips. | |

**User's choice:** (c) Both FKs — conversationId required + messageId nullable
**Notes:** Re-frame trong context Area 1 = single-turn inject — schema cần biết "newly attached, not yet sent". `messageId IS NULL` predicate vs persistent state in DB > VaadinSession (orphan-resistant). UI history replay chip render exact via FK lookup (no fragile timestamp correlation). Audit reference `AiTaskFile.id` direct.

---

## Upload UI affordance shape

| Option | Description | Selected |
|--------|-------------|----------|
| A. Button + chip list above MessageInput | Hidden Jmix `<upload>` (UploadHandler.toFile, NOT deprecated setReceiver) + visible JmixButton. Chip strip wrap. Both surfaces work. | ✓ |
| B. Drop-zone full-width above MessageInput | Native Jmix <upload> với dropLabel. 80–120px vertical eats 75%-h dialog. | |
| C. Floating paperclip in MessageInput row | ChatGPT/Claude UX. MessageInput không expose prefix/suffix; alignment fragility. | |
| D. Modal multi-file uploader | Two-step UX; modal-on-modal cho HEADER_BUTTON. | |
| E. jmix-crm right-pane split (FULL_ROUTE only) + A fallback (HEADER_BUTTON) | Match reference closest. 35%-dialog → 165px right pane unusable. +1 layout to maintain. | |

**User's choice:** A. Button + chip list above MessageInput
**Notes:** Both Phase 12 surfaces share `ChatPanelFragment` → single layout must fit FULL_ROUTE và 35%-width HEADER_BUTTON dialog. Option A only single-affordance shape that degrades gracefully into ~520px dialog. Per project memory `feedback_jmix_upload_receiver_deprecated`, use `UploadHandler.toFile` — NOT `setReceiver(MultiFileTemporaryStorageBuffer)` despite jmix-crm's pattern (Vaadin 24.8 marks `Upload.getReceiver/setReceiver` `forRemoval`). Researcher's output had drift on this point — flagged as planner-must-verify.

---

## Claude's Discretion

- Exact CSS class names cho chip strip + chip element (Lumo-compatible naming)
- Whether per-file upload progress shown (research suggests yes; planner picks Vaadin progress placement)
- `application.properties` exact key naming `ai-agent.task-file.ttl-seconds=3600` — match Phase 11 `idempotencyTtl` style
- Bulk-save error code surface — reuse Phase 11 6-code taxonomy + row-index suffix vs add 7th code `bulk_validation_failed` — planner verifies `MutationErrorTranslator` API

## Deferred Ideas

- STT (Soniox provider) → Phase 15
- `prepare_form_draft` tool → Phase 14
- Continue-on-error bulk save → revisit if host requests
- Explicit `operation` enum on `bulk_save_records` → D-02 fallback if Qwen3 confuses
- `bulk_delete_records` → v1.2
- Dual-model routing (`ChatModelRouter`) → defer until cost telemetry justifies
- Apache POI / Tika server-side text extractor → defer until host runs text-only model
- Schema-driven xlsx → Entity Inspector import → dropped (replaced by LLM + `bulk_save_records`)
- Admin list view for `AiTaskFile` → v1.2
- TTL-extension on file re-reference → telemetry-driven
- Chip rendering on message bubbles (history replay) → enabled by `messageId` FK; UI in v1.2
- Per-attribute denial verbose error message → if LLM struggles to recover
- Opt-in re-hydrate on file-name reference → D-01 Phase 13.x escalation
