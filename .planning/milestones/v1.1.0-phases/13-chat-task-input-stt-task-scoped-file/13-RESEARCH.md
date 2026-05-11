# Phase 13: Chat Task File — Attach + LLM Read + Bulk Save — Research

**Researched:** 2026-05-05
**Domain:** Multimodal chat input (Spring AI `Media` injection) + bulk-write tool extending Phase 11 mutation chain + transient `AiTaskFile` entity in `agentstore`
**Confidence:** HIGH (every locked decision is supported by either existing in-repo Phase 9–12 code, the verbatim `D:/DTH/jmix-crm` reference implementation, or Spring AI 1.1.4 source; only the precise `UploadHandler` multi-file API surface in jmix-flowui 2.8 is MEDIUM — flagged for planner).

---

## Summary

Phase 13 is a **single-tool extension + multimodal-input grafted onto the existing chat path**. Almost nothing is invented from scratch:

1. **Attach UI** is a chip-strip + hidden `<upload>` driven from a visible `JmixButton`, dropped into the Phase 12 `attachmentsPanel` slot of `ChatPanelFragment` (currently `visible="false"` placeholder). The slot is shared by both `ChatView` and `ChatDialogView` via the Phase 12 `ChatSurfaceMounter` contract — one wiring, two surfaces.
2. **`AiTaskFile`** is a single new `@Store("agentstore")` JPA entity following the pattern of `AiUiSettings` / `AiMutationIntent`: UUID PK, `@Version`, audit columns, `@PropertyDatatype("fileRef")` storage column, dual FK to `AiConversation` (NOT NULL) and `AiMessage` (NULLABLE — two-phase write per D-03). Liquibase changelog `090-ai-task-file.xml` is auto-included by the existing `<includeAll>` in `agentstore-changelog.xml`.
3. **`AiTaskFileMediaResolver`** is a verbatim port of `D:/DTH/jmix-crm` `AiAttachmentMediaResolver` (13-MIME allowlist, FileStorage byte read, `Media.builder()`, name sanitization), adapted to query `messageId IS NULL` rows for the active conversation per D-01 single-turn-inject.
4. **`bulk_save_records`** adds a new `@Tool` method on `BuiltInMutationTools` that reuses every Phase 11 collaborator (`MutationAuthorizationService`, `MutationAttributeBinder`, `MutationRequestHasher`, `MutationIntentRepository`, `MutationGuard`, `MutationErrorTranslator`, `DiffSerializer`, `MutationCommitCoordinator`) and adds **one** new method on `MutationSaveExecutor`: `bulkSave(SaveContext)` with `@Transactional` so the entire batch commits or rolls back atomically.
5. **Default model swap** flips two keys in `application.properties` (`jmix.ai-agent.defaults.model` and `spring.ai.openai.chat.options.model`) from `openai/gpt-4o-mini` to `qwen/qwen3.6-35b-a3b`. Embedding model unchanged. Per-conversation override via existing `AiParametersDetailView` is unaffected.
6. **TTL cleanup job** mirrors the existing `MutationIntentCleanupJob` pattern: `@Component` + `@Scheduled(cron = "0 0 * * * *")` + `UnconstrainedDataManager` for system-internal deletes. Also opportunistically purged on every `ChatService.ask`/`stream` entry.
7. **Security roles**: extend three existing role interfaces (`AiAgentUserRole`, `AiAgentUserRowLevelRole`, `AiAgentAdminRole`) with annotations for `AiTaskFile`. Pattern is identical to how `AiConversation` / `AiMessage` are gated today.

**Primary recommendation:** Plan Phase 13 in **5 waves of 8–10 tasks total**. Wave 0: `AiTaskFile` entity + Liquibase 090 + bilingual messages + role annotations + `AiTaskFileProperties`. Wave 1: `AiTaskFileMediaResolver` + `AiTaskFileRepository` + `AiTaskFileCleanupJob`. Wave 2: `MutationSaveExecutor.bulkSave` + `bulk_save_records` `@Tool` method (+ rich description) + `AgentSystemPromptRules` extension. Wave 3: `ChatPanelFragment` upload + chip-strip wiring + `DefaultChatServiceImpl.ask/stream` Media injection + `markSent` two-phase write. Wave 4: integration tests (TEST-16, idempotency replay, partial-failure rollback, audit shape) + ROADMAP.md update + default-model swap in `application.properties`. The dual-surface invariant (FULL_ROUTE + HEADER_BUTTON) falls out for free because both surfaces mount the same `ChatPanelFragment`.

---

## User Constraints (from CONTEXT.md)

### Locked Decisions

**D-01: Media injection cadence — single-turn inject (jmix-crm pattern)** `[CITED: 13-CONTEXT.md]`

Inject Spring AI `Media` ONLY on the user turn that newly attaches files. Subsequent turns receive an empty `Media` list and rely on the assistant's text response (already persisted in `JdbcChatMemoryRepository`) for follow-up reasoning. Resolver returns only files where `messageId IS NULL`; on send, after `AiMessage` is persisted, `UPDATE AiTaskFile SET messageId = newMessageId WHERE id IN (resolvedIds)`.

**D-02: `bulk_save_records` semantics — mixed batch with id-presence dispatch** `[CITED: 13-CONTEXT.md]`

Single `@Tool` method `bulk_save_records(String entityName, List<Map<String,Object>> records, String idempotencyKey)`. Per-row dispatch: `id != null` → update; `id == null` → create. ONE transaction, ONE `AiAuditEvent` row (`eventName=bulk_save_records`), ONE `AiMutationIntent` row. `requestHash` = SHA-256 over canonical JSON in **submission order**. Per-row failure → entire batch rolls back; audit `outcome=FAILED` with `failedRowIndex`. Replay returns `IDEMPOTENT_REPLAY`. Rich `@Tool` description follows 5-section template with TWO worked examples.

**D-03: `AiTaskFile` data model — both FKs (conversationId required, messageId nullable)** `[CITED: 13-CONTEXT.md]`

Schema: `id` UUID PK, `conversation_id` UUID NOT NULL FK → `AiConversation` (cascade), `message_id` UUID NULL FK → `AiMessage` ON DELETE SET NULL, `user_username`, `filename`, `content_type`, `size_bytes`, `storage_ref` `@PropertyDatatype("fileRef")`, `created_at`, `expires_at` (default `now + 1h`), audit fields.

Two-phase write: (1) upload → INSERT with `messageId = NULL`; (2) on send → after AiMessage persisted, UPDATE messageId.

**D-04: Upload UI affordance — Button + chip list above MessageInput** `[CITED: 13-CONTEXT.md]`

`attachmentsPanel` becomes `<vbox>` with chip-strip `<hbox>` (filename + remove icon, wrap on overflow). `messageInputSlot` extends to host existing `streamProgressBar` + existing `MessageInput` + new attach-button row. Hidden Jmix `<upload>` with **`UploadHandler.toFile`** triggered programmatically by a visible `JmixButton`. Multi-file selection allowed. Per-file size cap 100 MB. MIME allowlist hard-coded (13 entries copied from jmix-crm). Both surfaces share the same fragment — no surface conditional.

### Claude's Discretion

- Exact CSS class names for chip strip + chip element (Lumo-compatible naming convention).
- Whether to show upload progress per-file (researcher recommends yes; planner picks Vaadin progress placement).
- `application.properties` exact key naming for `ai-agent.task-file.ttl-seconds=3600` — match Phase 11 `idempotencyTtl` style.
- Bulk-save error code surface — **researcher recommends reusing the Phase 11 6-code taxonomy with row-index suffix in the error message** (e.g. `"row 6 (update id=...): validation_failed"`); do NOT add a 7th code. Planner verifies `MutationErrorTranslator.translate(...)` API shape.

### Deferred Ideas (OUT OF SCOPE)

- STT (Soniox provider) → Phase 15.
- `prepare_form_draft` tool → Phase 14.
- Continue-on-error bulk save → revisit if a host requests "import 100 dòng, 95 OK".
- Explicit `operation: "CREATE" | "UPDATE"` enum on `bulk_save_records` → D-02 fallback if Qwen3 confuses create vs update during UAT (additive migration).
- `bulk_delete_records` → v1.2 (destructive ops need separate UX).
- Dual-model routing (`ChatModelRouter`) → defer until cost telemetry justifies.
- Apache POI / Tika server-side text extractor → defer until a host runs a text-only chat model.
- Schema-driven xlsx → Entity Inspector import action — explicitly dropped (LLM + `bulk_save_records` covers it).
- Admin list view for `AiTaskFile` → v1.2.
- TTL-extension on file re-reference → telemetry-driven.
- Chip rendering on message bubbles (history replay) → v1.2; `messageId` FK enables it.
- Per-attribute denial verbose error message (with `failedAttribute` echo) → if LLM struggles to recover.
- Opt-in re-hydrate on file-name reference (D-01 escalation) → Phase 13.x telemetry-driven.

---

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| **ENT-07** | `AiTaskFile` entity in `agentstore` | Section "Standard Stack" rows 1, 4 (mirror `AiUiSettings` JPA shape); "Architecture Patterns" Pattern 1 (entity skeleton); "Code Examples" Example 1; Liquibase 090 mirrors `070-ai-mutation-intent.xml` |
| **TASK-01** | Task-scoped attachment UI affordance in `attachmentsPanel`, both surfaces | Section "Architecture Patterns" Pattern 4 (chip strip + hidden upload); D-04 locked; Phase 12 `ChatPanelFragment` already shared by both surfaces |
| **TASK-02** | Transient files; never touch VectorStore/IngesterManager | Section "Don't Hand-Roll"; Pattern 3 (TTL job); TEST-16 invariant verified by static-analysis source scanner over `com.vn.agent.taskfile.**` |
| **TASK-03** | `AiTaskFile` schema with conversationId + messageId + storageRef + TTL | D-03 locked; Section "Code Examples" Example 1 (entity); Liquibase changelog Example 2 |
| **TASK-04** | `Media` injection on send-turn only | D-01 locked; verbatim port of `D:/DTH/jmix-crm` `CrmAnalyticsService.processBusinessQuestionInternal` lines 117–131 |
| **TASK-05** | Three-affordance UI distinction (text / task-file / KB upload) | KB upload lives in `KnowledgeBaseView` (separate route); chat input is `MessageInput`; chip strip is the third affordance — visually distinct via Lumo classes |
| **TASK-06** | `AiTaskFileMediaResolver` + `DefaultChatServiceImpl` integration + default-model swap | Section "Architecture Patterns" Pattern 2 (resolver); Section "Code Examples" Example 3 (DefaultChatServiceImpl insertion points); Section "State of the Art" model-swap row |
| **MUT-14** | `bulk_save_records` `@Tool` extending Phase 11 chain | D-02 locked; Section "Architecture Patterns" Pattern 5 (bulk save executor); "Code Examples" Examples 4 + 5 (tool method + executor) |
| **SEC-06 (partial)** | Role extensions for `AiTaskFile` (CREATE+READ user, ALL admin, row-level by `userUsername`) | Section "Architecture Patterns" Pattern 6 (role annotations); "Code Examples" Example 6 |
| **TEST-16** | Task file isolation — zero VectorStore/IngesterManager touch | Section "Validation Architecture" Wave 0 row; static-source-scanner pattern (extension of existing TEST-08 grep approach) |
| **(default-model swap)** | `application.properties` → `qwen/qwen3.6-35b-a3b` | Section "State of the Art" row "Default chat model"; "Don't Hand-Roll" row "Multimodal text/file processing" |

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| File upload UI affordance + chip render | Frontend (Vaadin Flow + Jmix view layer) | — | Owned by `ChatPanelFragment`; shared across both surfaces by Phase 12 contract. |
| Task-file blob persistence | Backend (Jmix `FileStorage` `local`) | DB (`agentstore.AI_TASK_FILE` row stores `FileRef`) | Bytes go to filesystem; metadata + storageRef go to relational DB — standard Jmix pattern. |
| Task-file metadata persistence | DB (`agentstore`) | — | Multi-store separation enforced by `@Store("agentstore")`. |
| Pending-file resolution + Media build | Backend (Spring `@Component`) | DB (read of `AiTaskFile`) + `FileStorage` (read of bytes) | Resolver runs synchronously inside the chat request thread. |
| LLM `Media` injection | Backend (Spring AI `ChatClient` builder) | Model provider (OpenAI-compat transport → Qwen3.6-35B-A3B) | Single integration point inside `DefaultChatServiceImpl.ask` and `.stream`. |
| `bulk_save_records` orchestration | Backend (`BuiltInMutationTools` `@Tool`) | DB (host entity store via `DataManager`) + `agentstore` (intent + audit) | Phase 11 chain reused; one transaction wraps all per-row saves. |
| Transaction boundary | Backend (`MutationSaveExecutor.bulkSave` `@Transactional`) | — | Critical: must be on the executor bean (separate from the tool class) to cross the Spring AOP proxy. |
| TTL cleanup | Backend (`@Scheduled` job + `UnconstrainedDataManager`) | DB + `FileStorage` | System-internal deletes; bypass user policies safely (memory `feedback_jmix_unconstrained_for_system_writes`). |
| Authorization (per-row) | Backend (`AccessManager` + `LlmExposurePolicy`) | — | Phase 11 chain re-applied per-row; entity-level checks once per batch. |
| Audit | Backend (`AuditWriter.writeToolCall` REQUIRES_NEW) | DB (`agentstore.AI_AUDIT_EVENT`) | One row per batch; `argumentsJson` carries hashes only. |

---

## Standard Stack

### Core (already in build, not adding)

| Library | Version | Purpose | Why Standard | Source |
|---------|---------|---------|--------------|--------|
| Spring AI Client Chat | 1.1.4 | `ChatClient.prompt().user(u -> u.media(...))`, `org.springframework.ai.content.Media`, `Media.Format.*`, `@Tool`, `@ToolParam`, `JsonSchemaGenerator` | Phase 4–12 already shipped on this version; no Phase 13 net-new artifact | `[VERIFIED: ai-agent/ai-agent/build.gradle springAiVersion = "1.1.4"]` |
| Spring AI OpenAI | 1.1.4 | OpenAI-compat transport for OpenRouter (dev) and vLLM/Ollama (prod self-host) | Stack constraint — Qwen3.6 served behind OpenAI-compat endpoint | `[CITED: 13-AI-SPEC.md §3]` |
| Spring AI JDBC ChatMemory | 1.1.4 | Persists `content TEXT` only (Media bytes NOT serialized — foundational for D-01) | Phase 12 chat memory store | `[VERIFIED: 13-AI-SPEC.md cites schema-postgresql.sql; D-01 rationale]` |
| Jmix Core 2.8 | 2.8 | `FileStorage`, `FileStorageLocator`, `FileRef`, `@PropertyDatatype("fileRef")`, `Metadata`, `DataManager`, `UnconstrainedDataManager`, `AccessManager`, `MetadataTools` | Stack baseline | `[VERIFIED: CLAUDE.md]` |
| Jmix Flow UI 2.8 | 2.8 | Jmix `<upload>` + `UploadHandler.toFile`, `JmixButton`, fragment descriptor schema | Stack baseline | `[VERIFIED: project memory `feedback_jmix_upload_receiver_deprecated`]` |
| Spring Boot 3 | 3.x | `@Scheduled`, `@ConfigurationProperties`, `@ConditionalOnProperty` | Stack baseline | `[VERIFIED: CLAUDE.md]` |
| Liquibase | (Jmix-managed) | `agentstore-changelog/090-ai-task-file.xml` auto-included via `<includeAll>` | Stack baseline | `[VERIFIED: agentstore-changelog.xml `<includeAll>` confirmed]` |
| Jakarta Validation | 3.x | `@NotNull` on entity columns; runtime validation of bound entities | Stack baseline | `[VERIFIED: existing entity patterns]` |
| Jackson | (Spring-managed) | Canonical JSON serialization for `requestHash`; `MutationRequestHasher.canonicalMapper` | Phase 11 already uses; sorted keys via `ORDER_MAP_ENTRIES_BY_KEYS` | `[VERIFIED: MutationRequestHasher.java line 33–34]` |

### Supporting (Phase 13 net-new code, no new dependency)

| Component | Purpose | When to Use |
|-----------|---------|-------------|
| `AiTaskFile` entity | Persistent metadata + FileRef for one attached file | Always; stored in `agentstore` |
| `AiTaskFileRepository` | `loadPending(conversationId)` / `markSent(ids, msgId)` / `loadExpired(now)` / `deleteRow(id)` | Used by resolver, chat service post-send hook, cleanup job |
| `AiTaskFileMediaResolver` | Build `List<Media>` for files where `messageId IS NULL` | Once per `.ask`/`.stream` call (single-turn inject) |
| `AiTaskFileCleanupJob` | `@Scheduled` hourly purge of expired rows + blobs | Cron `"0 0 * * * *"` |
| `AiTaskFileProperties` | `@ConfigurationProperties("ai-agent.task-file")` — `ttlSeconds` (3600), `maxFileSizeBytes` (104857600) | Bound at boot; consulted by upload handler + cleanup job |
| `MutationSaveExecutor.bulkSave(SaveContext ctx)` | New `@Transactional` method, single proxy-crossed boundary | Called once per `bulk_save_records` invocation |
| `BuiltInMutationTools.bulkSaveRecords(...)` | New `@Tool` method on existing class — orchestration only, delegates to existing collaborators | Conditional on `ai-agent.tools.mutation.enabled=true` (default OFF) |

### Alternatives Considered (rejected — explain why)

| Instead of | Could Use | Tradeoff | Why Rejected |
|------------|-----------|----------|--------------|
| Single multimodal Qwen3.6-35B-A3B for all turns | Dual-model routing (text-cheap + vision-strong) | Lower per-turn cost on text turns | User locked single-model in CONTEXT (D-01); ~17 GB INT4 vs ~57 GB dual; Phase 13 simplification. |
| `UploadHandler.toFile` | `setReceiver(MultiFileTemporaryStorageBuffer)` (jmix-crm pattern) | Familiar to jmix-crm devs | `Upload.getReceiver/setReceiver` is `forRemoval` in Vaadin 24.8 (memory `feedback_jmix_upload_receiver_deprecated`). |
| Mixed batch (id-presence dispatch) | Two tools (`bulk_create_records` + `bulk_update_records`) | Per-tool semantic clarity | Locked D-02; violates 1-audit/1-intent invariant; Qwen3 tool-call reliability degrades with tool count. |
| Conversation-FK + nullable message-FK (locked D-03) | Conversation-FK only + `@VaadinSessionScope` pending list | Smaller schema | `@VaadinSessionScope` lost on tab close → orphan rows in DB; D-01 single-turn-inject requires DB-side pending state. |
| `List<Map<String,Object>>` rows | Typed `record` per host entity | JSON Schema would carry per-attribute type info | Entity-agnostic — must work for any host entity. AI-SPEC §4 examples confirm; typed record reserved for future migration target. |
| Phoenix / Arize OpenTelemetry export | Built-in Spring AI logging only | Free, no extra infra | Phase 13 ships observability properties (PII-safe defaults) but planner does NOT have to install Phoenix; AI-SPEC §5 cites it as the *recommended* tracing backend, not a Phase 13 deliverable. |

**Installation:** none. Phase 13 adds **zero new dependency JARs**. Only application property additions and one new package (`com.vn.agent.taskfile`) plus one new Liquibase changelog file.

**Version verification:** `[VERIFIED: codebase grep]` Spring AI 1.1.4 pinned in `ai-agent/ai-agent/build.gradle`. No `npm view`-equivalent applies (Java/Gradle).

---

## Architecture Patterns

### System Architecture Diagram

```
                  ┌────────────────────────────────────────────┐
                  │  Vaadin Browser (FULL_ROUTE or HEADER_BUTTON) │
                  │  ChatPanelFragment (single, both surfaces)  │
                  └─────────────────────┬──────────────────────┘
                                        │ multipart upload
                                        ▼
                ┌──────────────────────────────────────────────┐
                │  Jmix <upload> + UploadHandler.toFile        │
                │  (server-side MIME + size validation)        │
                └─────────────────────┬────────────────────────┘
                                      │  bytes
                ┌─────────────────────┴───────────────────────────┐
                ▼                                                 ▼
    ┌──────────────────────┐                     ┌────────────────────────┐
    │ FileStorage "local"  │                     │ AiTaskFileRepository   │
    │ saveStream → FileRef │                     │ insert(messageId=NULL) │
    └──────────────────────┘                     └────────────────────────┘
                                                                │
                                            (chip rendered in attachmentsPanel)
                                                                │
                                              user clicks Send  ▼
                ┌──────────────────────────────────────────────────────────┐
                │  ChatService.ask(...) / .stream(...) — DefaultChatServiceImpl │
                │  ┌────────────────────────────────────────────────────┐  │
                │  │ AiTaskFileMediaResolver.resolvePending(convId)     │  │
                │  │   → List<Media> + List<UUID> taskFileIds           │  │
                │  └────────────────────────────────────────────────────┘  │
                │  ┌────────────────────────────────────────────────────┐  │
                │  │ chatClient.prompt()                                │  │
                │  │   .system(composed)                                │  │
                │  │   .user(u -> { u.text(msg);                        │  │
                │  │                 if (!media.isEmpty()) u.media(...);})│  │
                │  │   .toolCallbacks(...) // includes bulk_save_records  │  │
                │  │   .options(model = qwen/qwen3.6-35b-a3b, ...)        │  │
                │  │   .call() / .stream()                              │  │
                │  └────────────────────────────────────────────────────┘  │
                │           │                                              │
                │           ▼                                              │
                │  Qwen3.6-35B-A3B (multimodal, 262K ctx)                  │
                │           │                                              │
                │  ┌────────┴────────────────────────────────────────────┐ │
                │  │ Tool calls (any of: list_entities, describe_entity, │ │
                │  │ find_records, create_record, update_record,         │ │
                │  │ add/remove_related, bulk_save_records, …)           │ │
                │  └─────────────────────────────────────────────────────┘ │
                │           │                                              │
                │  After AiMessage row is persisted (chat-memory advisor): │
                │  taskFileRepository.markSent(taskFileIds, newMessageId)  │
                └──────────────────────────────────────────────────────────┘
                                      │
                                      │ (when LLM emits bulk_save_records)
                                      ▼
       ┌──────────────────────────────────────────────────────────────────┐
       │  BuiltInMutationTools.bulkSaveRecords(entityName, records, key)  │
       │  ─ enforceMutationRole (SEC-07)                                   │
       │  ─ resolveCreatableEntityOrThrow & resolveUpdatableEntityOrThrow  │
       │     (per-row dispatch by id-presence)                             │
       │  ─ enforceCreatePermission + enforceUpdatePermission (entity once)│
       │  ─ enforceAttributeWriteAccess (per row, union of attribute keys) │
       │  ─ MutationRequestHasher.hash(records in submission order)        │
       │  ─ MutationIntentRepository.reserveOrReplay (1 row per batch)     │
       │  ─ For each row: coerce + MutationGuard.check                     │
       │  ─ MutationSaveExecutor.bulkSave(saveContext) ── @Transactional   │
       │      └─ DataManager.save(saveContext) atomic; rollback-all on RTE │
       │  ─ markCommitted (1 update per batch)                             │
       │  ─ AuditWriter.writeToolCall (1 audit row, args = sample hashes)  │
       └──────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
       ┌──────────────────────────────────────────────────────────────────┐
       │  AiTaskFileCleanupJob @Scheduled cron "0 0 * * * *"               │
       │  ─ UnconstrainedDataManager.load(AiTaskFile … expiresAt < now)    │
       │  ─ FileStorage.removeFile(fileRef)                                │
       │  ─ UnconstrainedDataManager.remove(row)                           │
       │  Plus opportunistic cleanup at start of every ChatService entry.  │
       └──────────────────────────────────────────────────────────────────┘
```

### Recommended Project Structure (Phase 13 additions only)

```
ai-agent/ai-agent/src/main/java/com/vn/agent/
├── entity/
│   └── AiTaskFile.java                          # NEW @JmixEntity @Store("agentstore")
├── taskfile/                                    # NEW package — TEST-16 source-scanner target
│   ├── AiTaskFileMediaResolver.java             #   (verbatim port of jmix-crm)
│   ├── AiTaskFileRepository.java                #   loadPending / markSent / loadExpired / delete
│   ├── AiTaskFileCleanupJob.java                #   @Scheduled hourly + UnconstrainedDataManager
│   └── AiTaskFileProperties.java                #   ai-agent.task-file.* binding
├── tools/mutation/
│   ├── BuiltInMutationTools.java                # MODIFIED — add bulkSaveRecords @Tool method
│   └── MutationSaveExecutor.java                # MODIFIED — add bulkSave(SaveContext) @Transactional
├── security/
│   ├── AiAgentUserRole.java                     # MODIFIED — add @EntityPolicy(AiTaskFile, READ+CREATE)
│   ├── AiAgentUserRowLevelRole.java             # MODIFIED — add @JpqlRowLevelPolicy on userUsername
│   └── AiAgentAdminRole.java                    # MODIFIED — add @EntityPolicy(AiTaskFile, ALL)
├── view/chat/fragment/
│   ├── ChatPanelFragment.java                   # MODIFIED — onReady wires upload + chip strip
│   └── ChatPanelFragmentChipStrip.java          # NEW (optional small helper for chip render)
└── DefaultChatServiceImpl.java                  # MODIFIED — inject taskFileMediaResolver,
                                                 #            two-line .user lambda swap,
                                                 #            post-send markSent
ai-agent/ai-agent/src/main/resources/com/vn/agent/
├── liquibase/agentstore-changelog/
│   └── 090-ai-task-file.xml                     # NEW (auto-included by <includeAll>)
├── view/chat/fragment/
│   └── chat-panel-fragment.xml                  # MODIFIED — attachmentsPanel becomes vbox
│                                                #            + add attach button row in
│                                                #            messageInputSlot or above
├── messages.properties                           # MODIFIED — entity captions + chip + error keys
└── messages_vi.properties                        # MODIFIED — vi parity
jmix-app/src/main/resources/
└── application.properties                        # MODIFIED — model swap + ttl + max-file-size
```

### Pattern 1: `AiTaskFile` JPA entity in `agentstore`

**What:** Standard Jmix entity in the secondary store, mirroring shape of `AiUiSettings` / `AiMutationIntent` / `AiMessage` already in `entity/`.

**When to use:** Always. Single new entity for Phase 13.

**Example:** see Code Examples Example 1 below.

### Pattern 2: Spring AI `Media` resolver — single-turn inject

**What:** A `@Component` that loads pending-attachment rows for the active conversation and builds `List<Media>`. Returns also the `List<UUID>` of resolved task-file ids so the caller can two-phase-write `messageId` after the chat memory advisor persists the user message.

**When to use:** Once per `ChatService.ask` / `.stream` invocation. The result MUST be hoisted into a final variable BEFORE the `chatClient.prompt()` builder so the lambda inside `.user(u -> ...)` can capture it.

**Example:** see Code Examples Example 2.

**Key invariants:**
- Query predicate: `where e.conversationId = :cid and e.messageId is null and e.expiresAt > :now order by e.createdAt asc`.
- Use `UnconstrainedDataManager` so the system-internal resolver does NOT depend on user-row policy (the `AiTaskFile` row WAS created under the user's row policy at upload-time; the resolver is reading on behalf of the chat path).
- Per memory `feedback_jmix_loadvalue_store`, raw JPQL on `agentstore` requires the entity name to be qualified — verified in jmix-crm: `from AiConversationAttachment e where e.id = :id and e.conversation.id = :conversationId` works because the `@Entity(name="…")` is in the same JPA metamodel store. **For `loadValue/loadValues` with raw JPQL, `.store("agentstore")` IS required** (memory line). For `dataManager.load(AiTaskFile.class).query(...)` Jmix infers the store from the entity's `@Store("agentstore")` annotation — confirmed by existing `dataManager.load(AiMutationIntent.class).query(...)` calls in the codebase. **Planner: use `dataManager.load(...).query(...)`, NOT `loadValues(...)`.**

### Pattern 3: TTL cleanup `@Scheduled` job (mirrors `MutationIntentCleanupJob`)

**What:** Hourly cron-driven job that loads expired rows via `UnconstrainedDataManager`, removes the `FileStorage` blob, then deletes the metadata row. Plus opportunistic per-conversation purge at the start of every `ChatService.ask`/`stream` for fast cleanup.

**Example:**
```java
@Component
public class AiTaskFileCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(AiTaskFileCleanupJob.class);
    private final AiTaskFileRepository repository;

    public AiTaskFileCleanupJob(AiTaskFileRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 0 * * * *")  // hourly at minute 0 — same as MutationIntentCleanupJob
    public void runHourly() {
        int deleted = repository.deleteAllExpired(OffsetDateTime.now());
        log.debug("AiTaskFileCleanupJob deleted {} expired rows", deleted);
    }
}
```

### Pattern 4: Upload affordance in `attachmentsPanel` slot

**What:** XML-first per memory `feedback_jmix_first_ui` — modify `chat-panel-fragment.xml` to turn the `attachmentsPanel` `<vbox>` into a chip-strip container. Add an attach-button row programmatically in `ChatPanelFragment.onReady` (Vaadin `Upload` does NOT have a fluent XML schema in jmix-flowui at parity with `MessageInput`, so wiring is partly programmatic — same as Phase 12 did for `MessageInput`).

**When to use:** Always; both `ChatView` and `ChatDialogView` mount the same fragment so a single change covers both surfaces.

**Wiring:** `@Subscribe` for the `Upload` events per memory `feedback_jmix_view_listeners`. The Vaadin `Upload` (jmix-flowui-derived) emits typed events such as `SucceededEvent`, `FailedEvent`, `FileRejectedEvent` — verify event class names in jmix-flowui 2.8 (planner consults Context7 with `jmix-framework/jmix-context7` for "upload component flow events"). The newer `UploadHandler.toFile` API is documented in project memory as the way to receive bytes; **planner verifies that `UploadHandler.toFile` is compatible with `multiple = true` selection in jmix-flowui 2.8** — earlier-phase memory only covered single-file `toFile` usage.

**`MessageInput` constraint:** Vaadin `MessageInput` exposes no prefix/suffix slot (CONTEXT D-04 rationale); the attach button MUST live in a sibling row inside `messageInputSlot` (above the existing `streamProgressBar` + `messageInput`), NOT inside `MessageInput` itself.

### Pattern 5: Bulk-save extension to Phase 11 chain

**What:** Reuse every existing collaborator. Add **one** new method on `MutationSaveExecutor` and **one** new `@Tool` method on `BuiltInMutationTools`. The new tool method follows the same try/catch/finally skeleton as `createRecord` / `updateRecord` — but with per-row inner loops where appropriate.

**Critical chain order (locked invariants from CONTEXT D-02 + Phase 11):**

| Step | Scope | Notes |
|------|-------|-------|
| 1. `enforceMutationRole(AiAgentMutationRole.CODE)` | once | First gate — exact authority equality |
| 2. `resolveCreatableEntityOrThrow(entityName)` AND `resolveUpdatableEntityOrThrow(entityName)` | once for batch | Whichever is needed by the rows present; if rows include an update, both checks must pass entity-level |
| 3. `enforceCreatePermission(metaClass)` AND/OR `enforceUpdatePermission(metaClass)` | once for batch | Skip the create gate if no row has `id == null`; skip the update gate if all rows have `id == null` |
| 4. `enforceAttributeWriteAccess(metaClass, allWrittenAttributeNames)` | once with the **union** of attribute keys across all rows | Cheaper than per-row, fail-closed equivalent |
| 5. `mutationRequestHasher.hash("bulk_save_records", entityName, null, null, null, batchEnvelope)` | once | `batchEnvelope` = `Map.of("records", recordsInSubmissionOrder, "idempotencyKey", key)`; **submission-order-preserving** (use `LinkedHashMap` or `List`) |
| 6. `mutationIntentRepository.reserveOrReplay("bulk_save_records", idempotencyKey, …, requestHash, ttl)` | once | Same Stripe-style replay/violation/pending semantics as Phase 11 |
| 7. **Per-row** type-coerce + `MutationGuard.check(MutationIntent)` | per row (inside loop) | Build a per-row `MutationIntent` so the host SPI can veto specific rows |
| 8. `mutationSaveExecutor.bulkSave(saveContext)` | once — single proxy crossing | All N rows go into one `SaveContext`; one call to `dataManager.save(saveContext)`; one transaction; one rollback span |
| 9. `mutationIntentRepository.markCommitted(intent, firstSavedId, metaClass.getName())` | once | `resultEntityId` = first-row id (planner picks; alternative: NULL with `result_entity_name = entityName + ":bulk:" + count`) |
| 10. `auditWriter.writeToolCall(parentId, runId, user, convId, "bulk_save_records", argumentsJson, resultSummary, latencyMs, outcome, denialReason, errorClass)` | once | `argumentsJson` = `{count, entityName, sampleHashes:[sha256(row0..min(2))], idempotencyKey}` — NEVER raw values; `resultSummary` = `{savedIds:[...], count}` |

**Per-row failure → rollback-all:** Any row throwing `RuntimeException` from coerce/guard/save aborts the whole `@Transactional` span. Capture `failedRowIndex` + `failedAttribute` (when available — Phase 11 `MutationErrorTranslator.translate` may surface it via the converter chain) and put them in the audit row's `result_summary` or `denial_reason`. Per CONTEXT D-02: error message format is `"row N (create/update id=...): <stable-error-code>"` — never echo user-supplied values.

**Code Examples 4 + 5 below show the skeleton.**

### Pattern 6: Security role extensions

**What:** Annotation-only edits to the three existing role interfaces. No new role class.

**Example:** see Code Examples Example 6.

### Anti-Patterns to Avoid

- **Calling `Media.builder()` outside `Flux.defer(...)` on the streaming path** — would block the calling thread before subscription. Hoist `taskFileMediaResolver.resolvePending(convId)` INSIDE the `Flux.defer(() -> { ... })` body, store as a final local, then reference in the `chatClient.prompt()` chain. Mirror existing `DefaultChatServiceImpl.stream` pattern (line 330–365).
- **Annotating `bulkSaveRecords` itself with `@Transactional`** — Spring self-invocation pitfall. Same reason `MutationSaveExecutor` is a separate `@Component` (see its class JavaDoc lines 13–21). The `@Transactional` MUST live on the `MutationSaveExecutor.bulkSave` method.
- **Calling `dataManager.save(entity)` per row in the bulk path** — would create N separate transactions. Use `dataManager.save(SaveContext)` once with all entities in the SaveContext to ensure atomic commit and minimize round-trips.
- **Using `setReceiver(MultiFileTemporaryStorageBuffer)` on `Upload`** — `Upload.getReceiver/setReceiver` is `forRemoval` in Vaadin 24.8 (memory `feedback_jmix_upload_receiver_deprecated`). Use `UploadHandler.toFile`. Note: `FileRejectedEvent` is the only event safe to wire via `@Subscribe` per the same memory.
- **Setting MIME validation only client-side** — defence-in-depth requires server-side validation in the upload handler AND extension-vs-content-sniff cross-check in `AiTaskFileMediaResolver` (covered by `resolveSupportedMimeType` in the verbatim port).
- **Echoing per-row error message text from JPA / Bean Validation into the audit row or chat reply** — violates Phase 11 P-22 PII invariant. `MutationErrorTranslator` already handles this for create/update; planner ensures the bulk path goes through the same translator and that `failedRowIndex` ONLY surfaces a 0-based index + the stable error code, not the offending value.
- **Adding `bulk_save_records` to the LLM tool callback list when `ai-agent.tools.mutation.enabled=false`** — would defeat the Phase 11 default-OFF gate. The `@Tool` method lives on `BuiltInMutationTools` which is already `@ConditionalOnProperty`-gated; no extra wiring needed, just add the method to the existing class.
- **Loading `AiTaskFile` rows through `dataManager.load(AiTaskFile.class)` in the chat path WITHOUT `UnconstrainedDataManager`** — the row IS created with the user's policy at upload-time, but at resolve-time the chat path runs in the user's request thread; `dataManager` works here too. Use `UnconstrainedDataManager` for cleanup/system writes per memory; use regular `DataManager` for resolver reads inside the user's chat request thread (consistent with how `BuiltInDataTools` reads host data).
- **Creating the `AiTaskFile` row inside `FileStorage.saveStream` callback** — race against the upload completion event. Standard pattern: complete the upload (file fully written), then in the `SucceededEvent` (or jmix-flowui 2.8 equivalent) handler, run `dataManager.create` + `setStorageRef(fileRef)` + `dataManager.save`.

---

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Multimodal text / image / document parsing | Apache POI / Tika server-side extractor | Multimodal Qwen3.6-35B-A3B reading raw bytes via Spring AI `Media` | Locked: user explicitly rejected POI dependency. Native multimodal handles xlsx/pdf/docx/images uniformly; Apache POI cap at 100K row xlsx with OOM risk; Tika doesn't read images. Dropping POI also avoids a CVE patch surface. |
| Custom multipart upload handling | Raw `HttpServletRequest.getParts()` | Jmix `<upload>` + `UploadHandler.toFile` | Jmix component wires Vaadin's tested upload pipeline + size cap + temp-file lifecycle; rolling your own loses CSRF + memory-bounded streaming. |
| Idempotency dedup table | Custom (toolName, key, hash) UNIQUE table | Reuse Phase 11 `AiMutationIntent` + `MutationIntentRepository.reserveOrReplay` | Already supports `bulk_save_records` semantics with zero schema change. |
| Request-shape canonical hashing | Hand-write JSON sort + SHA-256 | Reuse `MutationRequestHasher` (canonical Jackson + `AuditFieldHasher.sha256Hex`) | Phase 11 already locked the "canonical JSON in submission order" semantics. Re-implementing risks producing a different byte stream for the same logical input. |
| Per-row save inside `bulk_save_records` | Loop `dataManager.save(entity)` N times | One `dataManager.save(SaveContext)` with N entities added | Atomic commit + N rows in one round-trip; Jmix `SaveContext` is the only way to get this. |
| Audit row writer | Bespoke `JdbcTemplate` insert | Reuse `AuditWriter.writeToolCall(...)` (REQUIRES_NEW propagation) | Already durable across mutation rollback (Phase 11 invariant), already `@Transactional(REQUIRES_NEW)`. |
| Locale message lookup in views | Spring `MessageSource` | Inject Jmix `Messages` (auto-locale, @NonNull) | Memory `feedback_jmix_messages_over_spring`. |
| File metadata persistence | Hand-roll `varchar(1024)` storage path column | `@PropertyDatatype("fileRef")` + Jmix `FileRef` round-trip | Jmix handles serialization of storage name + path + filename + size in one column. |
| TTL purge query | `JdbcTemplate.update("delete from AI_TASK_FILE …")` | `UnconstrainedDataManager.load(...).query("e.expiresAt < :now").list()` then per-row `removeFile(fileRef) + remove(row)` | Need both blob deletion AND row deletion atomically — pure SQL skips the FileStorage cleanup. |
| Chip rendering primitive | Vaadin `Span` per chip with custom CSS | Same — there is no "Jmix chip" primitive | This IS hand-rolled, but it's the standard pattern; jmix-crm `AiAttachmentCardFragmentRenderer` builds its own card components too. Unavoidable. |
| Cron scheduler | Quartz job | `@Scheduled(cron = "0 0 * * * *")` (Spring) — same as `MutationIntentCleanupJob` | Phase 11 already enabled `@EnableScheduling`. |

**Key insight:** Phase 13 should add **the smallest possible new code** — the entire `bulk_save_records` orchestration is ~60 LOC reusing 8 existing collaborators. The resolver is ~80 LOC verbatim ported from jmix-crm. The biggest write is the rich `@Tool` description (~120 LOC of strings) which is a deliberate token-cost / safety trade per memory `feedback_rich_tool_descriptions`.

---

## Runtime State Inventory

> **Not applicable** — Phase 13 is a forward-additive feature (new entity, new tool, new resolver). No rename, refactor, or migration. No prior state to migrate.

Explicit category checks:

| Category | Found | Action |
|----------|-------|--------|
| Stored data referenced by old name | None — no rename in scope | None |
| Live service config | None — model swap is a property change, OpenRouter accepts both old + new model strings | None for OpenRouter; for self-host vLLM the operator deploys the new weights |
| OS-registered state | None — no cron/Task Scheduler entries change | None |
| Secrets / env vars | None — `OPENROUTER_API_KEY` env unchanged | None |
| Build artifacts / installed packages | None | None |

---

## Common Pitfalls

### Pitfall 1: Spring self-invocation of `@Transactional` on `bulkSaveRecords`

**What goes wrong:** Annotating `bulkSaveRecords` directly with `@Transactional` produces a silent no-op if the caller is the same bean (Spring AOP proxy is bypassed on self-invocation).

**Why it happens:** Spring's `@Transactional` is woven by an AOP proxy; calls THROUGH the proxy work, calls FROM the bean to itself bypass the proxy.

**How to avoid:** Put `@Transactional` on `MutationSaveExecutor.bulkSave(...)` (already a separate `@Component`) and call it from `BuiltInMutationTools.bulkSaveRecords`. Phase 11 already follows this pattern — see `MutationSaveExecutor.java` JavaDoc lines 9–24.

**Warning signs:** Test that asserts rollback on partial failure shows partial-commit behavior (e.g. 6 of 10 rows persisted). Run TEST that simulates a row-7 validation failure and assert pre/post `count(*)` delta = 0.

### Pitfall 2: `Media` not re-injected on follow-up turns (intentional D-01)

**What goes wrong:** A test or operator expects file bytes to flow on every turn for the duration of the conversation; observes empty `Media` list on turn 2; reports as bug.

**Why it happens:** D-01 single-turn-inject is intentional. `JdbcChatMemoryRepository` persists `content TEXT` only; bytes are not stored. The assistant's first-turn paraphrase is the persistent textual record.

**How to avoid:** Document in `AiTaskFileMediaResolver` JavaDoc and inline comment at the `markSent` call in `DefaultChatServiceImpl`. Add an integration test that explicitly asserts: "turn 1 with attachment → Media in outbound prompt; turn 2 (same conversation) → empty Media in outbound prompt". Both behaviors are correct.

**Warning signs:** Resolver always returns the same Media on every turn → indicates `markSent` is failing (race or transactional boundary issue) — investigate the post-`.call()` write order.

### Pitfall 3: TTL cleanup deleting blob WITHOUT deleting row, or vice versa

**What goes wrong:** Half-deleted state — blob removed from `FileStorage` but `AiTaskFile` row still present (chip silently appears to render but resolver throws on byte read), or row removed but blob orphaned (disk fills up).

**Why it happens:** Two-step delete (`FileStorage.removeFile` then `dataManager.remove`) is not atomic across the FileStorage and DB stores. A crash between the two leaves orphan state.

**How to avoid:** Order matters — **delete blob first, then row**. If blob delete fails, log + skip the row delete (retry next hour). If row delete fails after blob delete succeeded, the next hour's job sees an `AiTaskFile` row with a missing `FileRef`; resolver guards against missing blob by catching `IOException` from `FileStorage.openStream` and skipping the file (do NOT throw — chat path must remain alive). Cleanup job should also detect "row exists, blob gone" rows and remove them eagerly.

**Warning signs:** Disk usage keeps growing despite the cleanup job running (orphan blobs); resolver throws `IllegalStateException` on `FileStorage read failed` (orphan rows).

### Pitfall 4: `ai-agent.tools.mutation.enabled` default change drift

**What goes wrong:** Phase 13 needs the property `=true` to register `bulk_save_records` (gates the entire `BuiltInMutationTools` bean). Default in code is OFF (per Phase 11 invariant + TEST-13 boot test). The current `application.properties` already has `ai-agent.tools.mutation.enabled=true` for development convenience.

**Why it happens:** Operator confusion between dev defaults and shipped library defaults.

**How to avoid:** Phase 13 leaves the `@ConditionalOnProperty` semantics unchanged — host opt-in remains required. Include in operator docs: "to enable `bulk_save_records`, set `ai-agent.tools.mutation.enabled=true` (this also enables `create_record`/`update_record`/related-write tools — no separate gate)." TEST-13 (zero mutation callbacks under default config) MUST stay green; planner extends it to also assert `bulk_save_records` is absent under default config.

**Warning signs:** TEST-13 fails with the new tool present in the callback list under default config → planner missed the conditional inheritance.

### Pitfall 5: `requestHash` order-sensitivity vs LLM non-determinism

**What goes wrong:** LLM emits the same logical batch but with rows in a different order on a retry. `requestHash` differs → reservation returns `IDEMPOTENCY_VIOLATION` → user sees an error on what should be a clean retry.

**Why it happens:** D-02 locks submission-order hashing (Stripe-style byte-identical retry). Order-independent hashing was rejected as a footgun (row 4 referencing row 1's create breaks if reordered).

**How to avoid:** Document in the rich `@Tool` description STRICTNESS section (already in AI-SPEC §4 example): "Same key + ANY changed bytes (including row order) → `idempotency_violation`. For a corrected retry, generate a fresh `idempotencyKey`." LLM observably handles this in practice for Phase 11 mutation tools.

**Warning signs:** Production telemetry shows >30% `idempotency_violation` rate for `bulk_save_records` (vs ~5% for `create_record`) → indicates LLM is over-aggressively reusing keys; tighten the EXAMPLES section of the description.

### Pitfall 6: `loadValue/loadValues` raw JPQL on `agentstore` without `.store("agentstore")`

**What goes wrong:** Memory `feedback_jmix_loadvalue_store` documents that raw-JPQL `loadValue/loadValues` does NOT infer the store from the entity name. Throws an `IllegalStateException` or hits the wrong datasource.

**Why it happens:** Jmix infers the store for `dataManager.load(EntityClass.class).query(...)` but NOT for raw-projection `loadValue(...)`.

**How to avoid:** Phase 13 should NOT need raw `loadValue` queries — the resolver returns whole entities (`load(AiTaskFile.class)`), the cleanup job loads whole rows, the repository returns whole rows. If the planner adds a count or projection (e.g. cleanup-job metric), it MUST include `.store("agentstore")` on the call.

**Warning signs:** Test against PostgreSQL agentstore passes but throws against the host-app primary datasource (or vice versa).

### Pitfall 7: Multi-file `UploadHandler.toFile` API drift in jmix-flowui 2.8

**What goes wrong:** Existing project memory `feedback_jmix_upload_receiver_deprecated` documented `UploadHandler.toFile` for the SINGLE-file case in an earlier phase. Phase 13 wants multi-file selection. The multi-file API surface in jmix-flowui 2.8 may differ (may be a separate `UploadHandler.toFiles(...)` factory, or may require setting `multiple = true` on the `<upload>` plus per-file `toFile` callbacks).

**Why it happens:** Jmix flow-ui evolves; Vaadin 24 `Upload` API changed under it; not all variants are documented in the in-repo memory.

**How to avoid:** **Planner MUST verify the exact jmix-flowui 2.8 API for multi-file `UploadHandler` BEFORE writing the upload wiring.** Use Context7 with `jmix-framework/jmix-context7` library: `mcp__context7__query-docs` with topic "Upload component multiple files UploadHandler". If Context7 returns single-file only docs, fall back to GitHub: `https://github.com/jmix-framework/jmix/tree/2.8.x/jmix-flowui-flowui` and grep for `UploadHandler` usage. **If multi-file via `UploadHandler.toFile` is not supported in 2.8, the fallback is to set `multiple = true` and let each file fire a separate upload event handled by the same `@Subscribe` method**.

**Warning signs:** Compile error on `.toFile(...)` with multi-file lambda; runtime error that only the first file is received.

### Pitfall 8: Resolver hot-reads bytes inside the request thread for large files

**What goes wrong:** `FileStorage.openStream(fileRef).readAllBytes()` is blocking. For a 100 MB upload, this can take several hundred ms. On the streaming path inside `Flux.defer`, this runs on the calling thread before subscription if not properly hoisted.

**Why it happens:** `Media.builder().data(byte[])` requires the byte array up front. Spring AI does NOT support lazy / streaming `Media` payloads.

**How to avoid:** AI-SPEC §4b "Async-First Design" already calls this out. Run the resolver synchronously inside the request thread for `.ask(...)` (acceptable — single user, bounded by 100 MB cap). On `.stream(...)`, hoist the resolver call into the `Flux.defer(...)` body so it runs on the bounded `chatStreamingScheduler` (line 426 of `DefaultChatServiceImpl`). Do NOT wrap in `Mono.fromCallable(...).subscribeOn(boundedElastic())` for sync — defeats the point. Plan to add a debug log at the resolver entry/exit measuring `readAllBytes` duration; if production telemetry shows >2s p99, planner adds a chunked-Media variant in v1.2.

**Warning signs:** Slow chat-stream startup with attached file vs without; thread dump showing chat thread blocked on `readAllBytes`.

### Pitfall 9: `AiTaskFile` not reaching VectorStore is an INVARIANT, not a "be careful"

**What goes wrong:** Future contributor adds an `IngesterManager.ingestAsync(...)` call to `AiTaskFileMediaResolver` "to also populate the KB" — silently leaks task-file content into pgvector across users.

**Why it happens:** The two pathways look superficially similar (file upload → bytes); the structural difference is policy, not API.

**How to avoid:** TEST-16 is enforced as a **source-scanner test** — any source file under `com.vn.agent.taskfile.**` containing the literal string `IngesterManager`, `VectorStore`, `RetrievalAugmentationAdvisor`, or any RAG splitter class name fails the test. Pattern reused from Phase 9 grep-based test (e.g. `TEST-15` intent-extraction navigation grep). Add to `package-info.java` JavaDoc: "DO NOT REFERENCE: IngesterManager, VectorStore, RetrievalAugmentationAdvisor — task-file pathway is structurally disjoint from KB ingestion (TEST-16)".

**Warning signs:** TEST-16 source scanner fails CI on a new contributor's PR.

---

## Code Examples

### Example 1 — `AiTaskFile` entity skeleton

```java
// ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiTaskFile.java
// Source pattern: AiUiSettings.java, AiMessage.java, AiMutationIntent.java
package com.vn.agent.entity;

import io.jmix.core.FileRef;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDelete;
import io.jmix.core.DeletePolicy;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.PropertyDatatype;
import io.jmix.core.metamodel.annotation.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

@Store(name = "agentstore")
@JmixEntity
@Entity(name = "ai_AiTaskFile")
@Table(name = "AI_TASK_FILE", indexes = {
        @Index(name = "IDX_AI_TASK_FILE__ON_CONVERSATION", columnList = "CONVERSATION_ID"),
        @Index(name = "IDX_AI_TASK_FILE__ON_MESSAGE", columnList = "MESSAGE_ID"),
        @Index(name = "IDX_AI_TASK_FILE__EXPIRES_AT", columnList = "EXPIRES_AT")
})
public class AiTaskFile {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(DeletePolicy.CASCADE)
    @JoinColumn(name = "CONVERSATION_ID", nullable = false)
    private AiConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(DeletePolicy.UNLINK)            // ON DELETE SET NULL on host AiMessage delete
    @JoinColumn(name = "MESSAGE_ID")
    private AiMessage message;

    @NotNull
    @Column(name = "USER_USERNAME", nullable = false, length = 255)
    private String userUsername;

    @NotNull
    @Column(name = "FILENAME", nullable = false, length = 1024)
    private String filename;

    @Column(name = "CONTENT_TYPE", length = 255)
    private String contentType;

    @Column(name = "SIZE_BYTES")
    private Long sizeBytes;

    @PropertyDatatype("fileRef")
    @Column(name = "STORAGE_REF", length = 1024)
    private FileRef storageRef;

    @CreatedDate
    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @NotNull
    @Column(name = "EXPIRES_AT", nullable = false)
    private OffsetDateTime expiresAt;

    @InstanceName
    public String getInstanceName() {
        return filename != null ? filename : "AiTaskFile";
    }

    // … getters / setters omitted for brevity (no Lombok per CLAUDE.md)
}
```

### Example 2 — Liquibase changelog `090-ai-task-file.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/090-ai-task-file.xml -->
<!-- Auto-included by /com/vn/agent/liquibase/agentstore-changelog.xml <includeAll>. -->
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                      http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">

    <changeSet id="1" author="ai-agent">
        <createTable tableName="AI_TASK_FILE">
            <column name="ID" type="${uuid.type}">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="VERSION" type="int" defaultValueNumeric="1">
                <constraints nullable="false"/>
            </column>
            <column name="CONVERSATION_ID" type="${uuid.type}">
                <constraints nullable="false"
                             foreignKeyName="FK_AI_TASK_FILE__CONVERSATION"
                             references="AI_CONVERSATION(ID)"
                             deleteCascade="true"/>
            </column>
            <column name="MESSAGE_ID" type="${uuid.type}">
                <constraints foreignKeyName="FK_AI_TASK_FILE__MESSAGE"
                             references="AI_AGENT_MESSAGE(ID)"/>
            </column>
            <column name="USER_USERNAME" type="varchar(255)">
                <constraints nullable="false"/>
            </column>
            <column name="FILENAME" type="varchar(1024)">
                <constraints nullable="false"/>
            </column>
            <column name="CONTENT_TYPE" type="varchar(255)"/>
            <column name="SIZE_BYTES" type="bigint"/>
            <column name="STORAGE_REF" type="varchar(1024)"/>
            <column name="CREATED_AT" type="${offsetDateTime.type}">
                <constraints nullable="false"/>
            </column>
            <column name="EXPIRES_AT" type="${offsetDateTime.type}">
                <constraints nullable="false"/>
            </column>
        </createTable>
    </changeSet>

    <changeSet id="2" author="ai-agent">
        <createIndex indexName="IDX_AI_TASK_FILE__ON_CONVERSATION" tableName="AI_TASK_FILE">
            <column name="CONVERSATION_ID"/>
        </createIndex>
        <createIndex indexName="IDX_AI_TASK_FILE__ON_MESSAGE" tableName="AI_TASK_FILE">
            <column name="MESSAGE_ID"/>
        </createIndex>
        <createIndex indexName="IDX_AI_TASK_FILE__EXPIRES_AT" tableName="AI_TASK_FILE">
            <column name="EXPIRES_AT"/>
        </createIndex>
    </changeSet>
</databaseChangeLog>
```

> Note: per `agentstore-changelog/061-ai-knowledge-document-source-entity.xml` and other in-repo examples, the `${uuid.type}` and `${offsetDateTime.type}` properties are defined in the root `agentstore-changelog.xml` — re-use them automatically.

### Example 3 — `AiTaskFileMediaResolver` (port from jmix-crm)

`[CITED: D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/service/AiAttachmentMediaResolver.java]`

```java
// ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java
package com.vn.agent.taskfile;

import com.vn.agent.entity.AiTaskFile;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.*;

@Component
public class AiTaskFileMediaResolver {

    private static final int MAX_MEDIA_NAME_LENGTH = 96;

    // Verbatim port of jmix-crm AiAttachmentMediaResolver lines 29-43.
    private static final Set<MimeType> SUPPORTED_MEDIA_TYPES = Set.of(
            Media.Format.DOC_PDF, Media.Format.DOC_CSV, Media.Format.DOC_DOC, Media.Format.DOC_DOCX,
            Media.Format.DOC_XLS, Media.Format.DOC_XLSX, Media.Format.DOC_HTML, Media.Format.DOC_TXT,
            Media.Format.DOC_MD,
            Media.Format.IMAGE_PNG, Media.Format.IMAGE_JPEG, Media.Format.IMAGE_GIF, Media.Format.IMAGE_WEBP);

    private static final Map<String, MimeType> EXTENSION_MIME_TYPES = Map.ofEntries(
            Map.entry(".pdf", Media.Format.DOC_PDF),
            Map.entry(".csv", Media.Format.DOC_CSV),
            Map.entry(".doc", Media.Format.DOC_DOC),
            Map.entry(".docx", Media.Format.DOC_DOCX),
            Map.entry(".xls", Media.Format.DOC_XLS),
            Map.entry(".xlsx", Media.Format.DOC_XLSX),
            Map.entry(".html", Media.Format.DOC_HTML), Map.entry(".htm", Media.Format.DOC_HTML),
            Map.entry(".txt", Media.Format.DOC_TXT),   Map.entry(".md", Media.Format.DOC_MD),
            Map.entry(".png", Media.Format.IMAGE_PNG),
            Map.entry(".jpg", Media.Format.IMAGE_JPEG), Map.entry(".jpeg", Media.Format.IMAGE_JPEG),
            Map.entry(".gif", Media.Format.IMAGE_GIF),  Map.entry(".webp", Media.Format.IMAGE_WEBP));

    private final DataManager dataManager;
    private final FileStorageLocator fileStorageLocator;

    public AiTaskFileMediaResolver(DataManager dataManager, FileStorageLocator fileStorageLocator) {
        this.dataManager = dataManager;
        this.fileStorageLocator = fileStorageLocator;
    }

    /** D-01: returns Media for files attached to {@code conversationId} that have not yet been
     *  sent (messageId IS NULL). Caller stamps messageId after the AiMessage row is persisted. */
    public Resolved resolvePending(UUID conversationId) {
        if (conversationId == null) return Resolved.empty();

        // Jmix infers @Store("agentstore") from AiTaskFile annotation — no .store(...) needed
        // because we use load(EntityClass).query(...), NOT loadValues(...).
        List<AiTaskFile> pending = dataManager.load(AiTaskFile.class)
                .query("select e from ai_AiTaskFile e " +
                       "where e.conversation.id = :cid and e.message is null and e.expiresAt > :now " +
                       "order by e.createdAt asc")
                .parameter("cid", conversationId)
                .parameter("now", OffsetDateTime.now())
                .list();

        List<Media> media = pending.stream().map(this::buildMedia).toList();
        List<UUID> ids   = pending.stream().map(AiTaskFile::getId).toList();
        return new Resolved(media, ids);
    }

    private Media buildMedia(AiTaskFile row) {
        FileRef ref = row.getStorageRef();
        if (ref == null) {
            throw new IllegalStateException("AiTaskFile " + row.getId() + " has no storageRef");
        }
        return Media.builder()
                .mimeType(resolveSupportedMimeType(row.getContentType(), row.getFilename()))
                .data(readBytes(ref))
                .name(sanitizeMediaName(row.getFilename()))
                .build();
    }

    // readBytes / resolveSupportedMimeType / tryParseMimeType / mimeTypeFromExtension /
    // sanitizeMediaName: VERBATIM port of AiAttachmentMediaResolver lines 108-164.

    public record Resolved(List<Media> media, List<UUID> taskFileIds) {
        public static Resolved empty() { return new Resolved(List.of(), List.of()); }
        public boolean isEmpty() { return media.isEmpty(); }
    }
}
```

### Example 4 — `bulk_save_records` `@Tool` skeleton on `BuiltInMutationTools`

```java
// ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java
// Add as new method below existing remove_related_record (~line 538).

@Tool(name = "bulk_save_records", description = """
        MANDATORY WORKFLOW:
        1. Call list_entities to confirm the entity name exists and is visible.
        2. Call describe_entity to learn writable attributes and their types.
        3. Echo to the user the row count + first 3 sample rows BEFORE invoking this tool.
        4. Generate ONE fresh random UUID v4 idempotencyKey for this entire batch.
        5. Call this tool with all rows in a single invocation.
        6. After SUCCESS, call generate_entity_detail_link for the first 3 saved ids.

        INPUT CONTRACT:
        - entityName: exact internal name from list_entities, NEVER a label.
        - records: array of objects. For each row:
            * include `id` (UUID string) to UPDATE that row
            * omit `id` to CREATE a new row
            * NEVER include `id: null` — omit the key entirely
        - idempotencyKey: UUID v4. Same key + byte-identical canonical-JSON of records in the
          SAME submission order -> IDEMPOTENT_REPLAY. Same key + ANY changed bytes -> idempotency_violation.

        FORMATS:
        - UUID: 8-4-4-4-12 hex with v4 marker.
        - Dates: ISO-8601. Decimals: dot separator. Booleans: true/false.
        - To-one relationship: foreign-key UUID string in the same row object.
        - Collections: do NOT assign through attributes.

        ERROR HANDLING:
        - Per-row failure rolls back the ENTIRE batch (zero rows persisted).
          Result carries: {outcome: FAILED, failedRowIndex: N, errorCode: <one-of-6-stable-codes>}.
        - Stable error codes: unknown_entity, access_denied, validation_failed,
          parameter_conversion_error, idempotency_violation, concurrent_modification.
        - Do NOT retry on access_denied or idempotency_violation.

        STRICTNESS + EXAMPLES:
        - Use bulk_save_records ONLY when records >= 2 of the SAME entity. For one record,
          call create_record / update_record.
        - NEVER include user-supplied text values inside argumentsJson — audit stores hashes only.

        Example 1 — xlsx onboarding (3 new customers):
            entityName="Customer"
            records=[
              {"email":"a@x.com","fullName":"An Nguyen","phone":"+84..."},
              {"email":"b@x.com","fullName":"Binh Tran","phone":"+84..."},
              {"email":"c@x.com","fullName":"Cuong Le","phone":"+84..."}
            ]
            idempotencyKey="4f3e1c8a-9e8d-4a73-8c5b-1a2b3c4d5e6f"

        Example 2 — pdf-driven mixed batch (2 updates + 1 create):
            entityName="Order"
            records=[
              {"id":"...uuid...","status":"SHIPPED","trackingNumber":"VN1234"},
              {"id":"...uuid...","status":"CANCELLED"},
              {"customerId":"...uuid...","total":1500000,"status":"DRAFT"}
            ]
            idempotencyKey="..."
        """)
public String bulkSaveRecords(
        @ToolParam(description = "Exact entity name from list_entities") String entityName,
        @ToolParam(description = "Array of row objects. Omit `id` to create; include `id` to update.")
            List<Map<String, Object>> records,
        @ToolParam(description = "Fresh UUID v4 per logical batch") String idempotencyKey) {

    long startedAt = System.currentTimeMillis();
    String userUsername = currentAuthentication.getUser().getUsername();
    MetaClass metaClass = null;
    MutationIntentRepository.ReservationResult reservation = null;
    MutationCommitState commitState = MutationCommitState.NO_HOST_WRITE;

    try {
        mutationAuthorizationService.enforceMutationRole(AiAgentMutationRole.CODE);

        // 1. Resolve entity once. If batch contains both creates and updates, both gates run.
        boolean hasCreates = records.stream().anyMatch(r -> r.get("id") == null);
        boolean hasUpdates = records.stream().anyMatch(r -> r.get("id") != null);
        metaClass = hasCreates
                ? toolEntityResolver.resolveCreatableEntityOrThrow(entityName)
                : toolEntityResolver.resolveUpdatableEntityOrThrow(entityName);
        if (hasUpdates) toolEntityResolver.resolveUpdatableEntityOrThrow(entityName);

        // 2. Entity-level CRUD gates (once per batch).
        if (hasCreates) mutationAuthorizationService.enforceCreatePermission(metaClass);
        if (hasUpdates) mutationAuthorizationService.enforceUpdatePermission(metaClass);

        // 3. Per-attribute write check (union of attribute keys across all rows).
        Set<String> allAttributes = records.stream()
                .flatMap(r -> r.keySet().stream())
                .filter(k -> !"id".equals(k))
                .collect(Collectors.toSet());
        mutationAuthorizationService.enforceAttributeWriteAccess(metaClass, allAttributes);

        // 4. Canonical request hash (submission order preserved).
        String requestHash = mutationRequestHasher.hash(
                "bulk_save_records", entityName, null, null, null,
                Map.of("records", records, "idempotencyKey", idempotencyKey));

        // 5. Reserve once per batch.
        reservation = mutationIntentRepository.reserveOrReplay(
                "bulk_save_records", idempotencyKey, userUsername,
                RunContext.getConversationId(), requestHash,
                mutationProperties.resolvedIdempotencyTtl());
        if (reservation.state() != MutationIntentRepository.ReservationState.RESERVED) {
            return mutationCommitCoordinator.handleReservationResult(
                    reservation, "bulk_save_records", startedAt, userUsername,
                    diffSerializer.serializeBulkArgumentsJson(entityName, records, idempotencyKey));
        }

        // 6. Per-row coerce + guard + bind into entities (in-memory).
        List<Object> entitiesToSave = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            try {
                Map<String, Object> row = records.get(i);
                Object entity;
                if (row.get("id") == null) {
                    Map<String, Object> coerced = mutationAttributeBinder.coerceAttributes(metaClass, row);
                    mutationGuard.check(new MutationIntent("bulk_save_records", metaClass, null, coerced));
                    entity = dataManager.create(metaClass.getJavaClass());
                    mutationAttributeBinder.applyAttributes(metaClass, entity, coerced);
                } else {
                    UUID parsedId = mutationAttributeBinder.requireUuidId(
                            toolEntityResolver.parseEntityId(row.get("id").toString(), metaClass));
                    Map<String, Object> coerced = mutationAttributeBinder.coerceAttributes(
                            metaClass, withoutId(row));
                    mutationGuard.check(new MutationIntent(
                            "bulk_save_records", metaClass, parsedId, coerced));
                    Object existing = dataManager.load(metaClass.getJavaClass()).id(parsedId)
                            .optional()
                            .orElseThrow(() -> mutationErrorTranslator.notFound(metaClass, row.get("id").toString()));
                    mutationAttributeBinder.applyAttributes(metaClass, existing, coerced);
                    entity = existing;
                }
                entitiesToSave.add(entity);
            } catch (Throwable rowErr) {
                // Tag the row index so the audit can carry it. Re-throw as RuntimeException to
                // ensure rollback (but the executor's @Transactional has not opened yet — this
                // exception is thrown BEFORE save, so no rollback needed; we just abort).
                throw new BulkRowFailure(i, rowErr);
            }
        }

        // 7. ONE @Transactional save for all entities (atomic commit / rollback-all).
        EntitySet saved = mutationSaveExecutor.bulkSave(entitiesToSave.toArray());
        commitState = MutationCommitState.HOST_SAVE_RETURNED;
        List<UUID> savedIds = saved.stream()
                .map(e -> mutationAttributeBinder.requireUuidId(EntityValues.getId(e)))
                .toList();

        // 8. Audit args = sample hashes only (PII-safe per Phase 11 P-22 + AI-SPEC G13-05).
        String argumentsJson = diffSerializer.serializeBulkArgumentsJson(
                entityName, records, idempotencyKey);
        String resultSummary = diffSerializer.serializeBulkResultSummary(
                entityName, savedIds.size(), savedIds);

        // 9. Mark intent committed once per batch.
        mutationIntentRepository.markCommitted(
                reservation.intent(), savedIds.get(0), metaClass.getName());
        commitState = MutationCommitState.INTENT_COMMITTED;

        // 10. ONE audit row per batch.
        mutationCommitCoordinator.safeWriteAudit("bulk_save_records",
                argumentsJson, resultSummary,
                System.currentTimeMillis() - startedAt,
                AiToolCallOutcome.SUCCESS, null, null, userUsername);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", AiToolCallOutcome.SUCCESS.getId());
        result.put("entityName", entityName);
        result.put("count", savedIds.size());
        result.put("savedIds", savedIds);
        return toolResultFormatter.toJson(result);

    } catch (BulkRowFailure rowFail) {
        // Per-row pre-save failure: no rollback needed (executor not invoked), failedRowIndex carried
        // into the audit row's denial_reason / errorClass.
        ToolUserError translated = mutationErrorTranslator.translate(
                wrapAsToolUserError(rowFail.cause()), "bulk_save_records", metaClass);
        mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
        mutationCommitCoordinator.safeWriteErrorAudit("bulk_save_records",
                diffSerializer.serializeBulkArgumentsJson(entityName, records, idempotencyKey),
                translated, System.currentTimeMillis() - startedAt,
                AiToolCallOutcome.ERROR,
                "row=" + rowFail.rowIndex(),
                rowFail.cause().getClass().getName(), userUsername);
        return toolResultFormatter.error(translated);
    } catch (Throwable t) {
        // Same generic catch as createRecord/updateRecord — Phase 11 chain.
        ToolUserError translated = mutationCommitCoordinator
                .translateThrowableAfterReservation(t, commitState, "bulk_save_records", metaClass);
        AiToolCallOutcome outcome = mutationCommitCoordinator.auditOutcome(commitState);
        mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
        mutationCommitCoordinator.safeWriteErrorAudit("bulk_save_records",
                diffSerializer.serializeBulkArgumentsJson(entityName, records, idempotencyKey),
                translated, System.currentTimeMillis() - startedAt,
                outcome, null, t.getClass().getName(), userUsername);
        return toolResultFormatter.error(translated);
    }
}

private record BulkRowFailure(int rowIndex, Throwable cause) extends RuntimeException { /* ... */ }
```

> **Planner notes on Example 4:**
> - The `DiffSerializer` needs **two new methods**: `serializeBulkArgumentsJson(entityName, records, idempotencyKey)` returning `{count:N, entityName:str, sampleHashes:[sha256(rows[0..min(2)])], idempotencyKey:str}` and `serializeBulkResultSummary(entityName, count, savedIds)` returning a small JSON. Both use the existing `Jackson ObjectMapper` + `AuditFieldHasher.sha256Hex` helpers — no PII echo.
> - `MutationAttributeBinder.coerceAttributes` already exists for create/update — confirm it handles a `Map<String,Object>` row uniformly.
> - The `BulkRowFailure` handling is conservative: per-row failures happen pre-save, so rollback-all happens automatically (no save was issued). If a per-row failure happens DURING `bulkSave` (e.g. JPA constraint violation surfacing only at flush), the `@Transactional` span on `MutationSaveExecutor.bulkSave` rolls back; the catch block on `Throwable t` handles it.

### Example 5 — `MutationSaveExecutor.bulkSave` extension

```java
// ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationSaveExecutor.java
// Add below existing saveAll(Object... entities) method (~line 47).

/**
 * Bulk save for {@code bulk_save_records}. Single @Transactional span; all entities commit
 * or roll back together. Same regular {@link DataManager} (NOT Unconstrained) so user-level
 * row policies and entity listeners run uniformly with single-row create/update.
 *
 * <p>Caller is responsible for entity construction + attribute binding BEFORE invoking this
 * method — by the time we cross the proxy, the entities are bound and ready to flush.
 */
@Transactional
public EntitySet bulkSave(Object... entities) {
    return dataManager.save(entities);
}
```

> **Note:** `dataManager.save(Object...)` and `dataManager.save(SaveContext)` both exist in Jmix 2.8. The vararg form delegates to a SaveContext internally — confirm whether the planner needs SaveContext-based form to set special commit options (e.g. cascade behavior). For Phase 13's atomic commit goal, the vararg form is sufficient.

### Example 6 — Security role extensions

```java
// ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java
// ADD inside @ResourceRole interface, alongside existing @EntityPolicy annotations on userAccess():

@EntityPolicy(entityClass = AiTaskFile.class,
        actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE})
void userAccess();   // (existing method — add the annotation above to the same method)
```

```java
// ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java
// ADD a new method:

@JpqlRowLevelPolicy(
        entityClass = AiTaskFile.class,
        where = "{E}.userUsername = :current_user_username")
void taskFile();
```

```java
// ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
// ADD inside @ResourceRole interface alongside existing @EntityPolicy annotations on adminAccess():

@EntityPolicy(entityClass = AiTaskFile.class, actions = EntityPolicyAction.ALL)
void adminAccess();   // (existing method — add the annotation above to the same method)
```

### Example 7 — `DefaultChatServiceImpl.ask` insertion points

```java
// ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
// In ask(...) — line ~247 currently builds the chatClient.prompt() chain:

// INSERT BEFORE the chatClient.prompt() builder:
AiTaskFileMediaResolver.Resolved resolved = taskFileMediaResolver.resolvePending(convId);

// REPLACE existing `.user(message)` (line 249) with:
ChatClientResponse clientResp = chatClient.prompt()
        .system(composedSystemPrompt)
        .user(u -> {
            u.text(message);
            if (!resolved.isEmpty()) {
                u.media(resolved.media().toArray(new Media[0]));
            }
        })
        .toolCallbacks(toolCallbacks.callbacksFor(userId, convId))
        // … rest unchanged …
        .call()
        .chatClientResponse();

// AFTER the assistant message has been persisted by the chat-memory advisor (which fires
// inside .call()), stamp the just-sent task files. The user-message id can be retrieved by
// loading the latest USER row for this conversation:
if (!resolved.isEmpty()) {
    UUID persistedUserMessageId = loadLatestUserMessageId(convId);
    taskFileRepository.markSent(resolved.taskFileIds(), persistedUserMessageId);
}
```

For `.stream(...)` (line 325–): hoist `resolvePending(convId)` INSIDE the `Flux.defer(...)` body before the `chatClient.prompt()` chain. Run `markSent` in the `doOnComplete` terminal callback so a cancelled stream does NOT mark files as sent (re-attach on retry).

> **Planner verification:** The exact mechanism to retrieve the just-persisted user message id is NOT directly exposed by Spring AI's chat-memory advisor. Two options:
> 1. **Query approach (simpler):** `dataManager.load(AiMessage.class).query("from ai_AiMessage m where m.conversation.id = :cid and m.role = 'USER' order by m.createdDate desc, m.seq desc").maxResults(1).list()` — race-free if the chat-memory advisor commits BEFORE returning to the caller (verify this is the case in Spring AI 1.1.4).
> 2. **Context-key approach:** Spring AI's `ChatClientResponse.context()` may carry a `chat.memory.lastUserMessageId` key — verify via Context7 with `mcp__context7__query-docs` topic "ChatMemory advisor user message id context key".
>
> Approach 1 is safer because it does not depend on Spring AI internal context conventions. Plan should pick approach 1 unless Context7 confirms the context-key option.

---

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Default chat model `openai/gpt-4o-mini` (text+image only, proprietary) | `qwen/qwen3.6-35b-a3b` (multimodal text+image+document, Apache 2.0, 35B/3B MoE, 262K context, ~17 GB INT4 self-host) | Phase 13 swap | Single-model coverage of text + xlsx/pdf/docx; dev uses OpenRouter ($0.15/$1 per M tokens), prod self-hosts via vLLM/Ollama. Per-conversation override unchanged. |
| `Upload.setReceiver(MultiFileTemporaryStorageBuffer)` | `UploadHandler.toFile` | Vaadin 24.8 | `setReceiver` `forRemoval` per project memory; new API is callback-based and can write directly to a temp dir. |
| `org.springframework.ai.model.Media` | `org.springframework.ai.content.Media` | Spring AI 1.0 GA | Pre-1.0 sample code on the web imports the old package. Spring AI 1.1.4 confirmed in repo. |
| Per-row `create_record` looping for bulk import | `bulk_save_records` single tool call | Phase 13 | Avoids 10× round-trip latency + 10 audit rows + 10 idempotency rows for an xlsx onboarding batch. |
| File ingestion via `IngesterManager` (KB / RAG path) | `Media` injection in user turn (chat / task path) | Phase 13 | Two structurally disjoint pathways. KB = persistent, embedded, queried by `find_records` over RAG. Task = transient (1h TTL), inlined in user message, only the LLM sees. |

**Deprecated/outdated:**
- `Upload.getReceiver/setReceiver` — Vaadin 24.8 marked `forRemoval`. Use `UploadHandler.toFile`.
- `openai/gpt-4o-mini` as default — proprietary, not multimodal-document-aware, fails self-host requirement. Replaced by `qwen/qwen3.6-35b-a3b`.

---

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `dataManager.save(SaveContext)` (or `save(Object...)`) commits all entities atomically inside one `@Transactional` span; per-row JPA failure rolls back the whole batch | Pattern 5 / Example 5 | Per-row failure produces partial commit → violates Phase 13 acceptance criterion "rollback-all". Mitigation: integration test E13-08 explicitly asserts pre/post `count(*)` delta = 0 on a row-7-failure fixture. |
| A2 | Spring AI 1.1.4 `JdbcChatMemoryRepository` persists `content TEXT` only (no Media bytes serialized) | D-01 rationale | If Media WAS persisted by chat memory, D-01 single-turn-inject would over-truncate context. Mitigation: AI-SPEC §3 cites the schema-postgresql.sql; planner can verify by inspecting the artifact JAR. |
| A3 | `dataManager.load(AiTaskFile.class).query(...)` infers `@Store("agentstore")` correctly (no `.store(...)` call needed) | Example 3 | If inference fails, query targets the wrong datasource. Mitigation: smoke-test the resolver against agentstore in Wave 0; existing `dataManager.load(AiMutationIntent.class).query(...)` calls in the codebase succeed, so inference is established for `agentstore`. |
| A4 | jmix-flowui 2.8 `UploadHandler.toFile` supports multi-file selection | Pitfall 7 | If only single-file is supported via `toFile`, planner wires per-file event handlers instead. Mitigation: Context7 lookup before writing the upload code. |
| A5 | `Media.builder().data(byte[]).mimeType(MimeType).name(String).build()` is the correct Spring AI 1.1.4 API surface | Example 3 | If the builder signature changed, port fails to compile. Mitigation: AI-SPEC §3 cites Spring AI 1.1.4 multimodality docs; the in-repo jmix-crm reference uses exactly this signature. Verify by `grep "Media.builder" $(find ~/.gradle -name 'spring-ai-client-chat-1.1.4-sources.jar')`. |
| A6 | The chat-memory advisor commits the just-persisted USER message BEFORE `.call()` returns (so a follow-up SELECT in the same chat thread finds it) | Example 7 / planner verification note | If the advisor commits asynchronously, `markSent` may fire before the message exists. Mitigation: planner's verification note already calls this out; use the context-key approach if it exists, else add a small retry on the SELECT. |
| A7 | The MIME allowlist of 13 entries (jmix-crm pattern) is sufficient for Phase 13 (no new types like `.zip`, `.json`, `.xml`) | Pattern 4 | If a host expects `.json`/`.xml` extraction, they hit the unsupported-MIME error. Mitigation: this is the locked allowlist per CONTEXT D-04; future expansion is a config change. |
| A8 | The default chat model swap to Qwen3.6-35B-A3B does NOT regress Phase 9 prompt-contract tests (TEST-08) or Phase 10 exposure-policy tests (TEST-09) on the new model's tool-calling behavior | Wave 4 risk | If Qwen3 emits subtly different tool-call JSON, regressions may fire. Mitigation: AI-SPEC E13-11 explicitly tests Phase 9 prompt-contract regression with attached files; planner runs the existing TEST-08/TEST-09 suites as a baseline before merging the model swap. |
| A9 | A row's `id` field carrying a non-UUID string (LLM hallucination) surfaces via `mutationAttributeBinder.requireUuidId` as `parameter_conversion_error` | Example 4 | If binder accepts non-UUID and downstream JPA fails, audit may carry confusing `internal_error` instead of `parameter_conversion_error`. Mitigation: existing `update_record` tests cover this; same code path applies. |

**Risk if any A1–A9 wrong:** Mostly contained — each has a remediation. The critical ones to verify FIRST are A1 (rollback-all is a locked acceptance criterion), A4 (drives upload code shape), and A6 (drives `markSent` placement).

---

## Open Questions

1. **Multi-file upload API in jmix-flowui 2.8 (`UploadHandler.toFile` vs `toFiles` vs receiver)**
   - What we know: `setReceiver` is `forRemoval` (memory). `UploadHandler.toFile` is the project-blessed replacement for single-file.
   - What's unclear: Whether `toFile` accepts multi-file or requires a different factory.
   - Recommendation: Planner uses Context7 `mcp__context7__query-docs` with library `jmix-framework/jmix-context7` and topic "Upload component multiple files UploadHandler toFile". If still ambiguous, fall back to GitHub source under `https://github.com/jmix-framework/jmix/tree/2.8.x` (grep the test sources for `UploadHandler` examples).

2. **`Just-persisted-user-message id` retrieval for two-phase `markSent`**
   - What we know: The chat-memory advisor persists the USER message during `.call()`. The exact persistence boundary is not directly exposed.
   - What's unclear: Whether `ChatClientResponse.context()` carries a key for the persisted message id.
   - Recommendation: Plan uses the SELECT-back approach (Example 7 option 1) by default. If telemetry or planner research confirms the context-key option, switch.

3. **Chip rendering — Lumo CSS classes vs custom theme**
   - What we know: Jmix has no chip primitive; jmix-crm builds its own card components.
   - What's unclear: Whether existing Lumo classes (`badge` / `badge-pill`) are sufficient, or a Phase 13 net-new CSS file is needed.
   - Recommendation: Planner's discretion (CONTEXT). Researcher recommends starting with Lumo `badge contrast` + a small `ai-agent-chat-panel__chip` class for layout (gap, padding, remove-icon alignment). Add to Phase 12's existing chat-panel CSS file if one exists.

4. **`bulk_save_records` per-row transaction scope when saved via `dataManager.save(SaveContext)`**
   - What we know: `dataManager.save(Object...)` runs inside one `@Transactional` span when called from `MutationSaveExecutor.bulkSave`. JPA flushes all entities together; constraint violations surface at flush time.
   - What's unclear: Order in which entity listeners (`@PrePersist`, `@PreUpdate`) fire across a mixed batch — can a listener observe a half-bound state?
   - Recommendation: Treat the whole-batch save as opaque; per-row entity listeners on the host side run uniformly with single-row `create_record`/`update_record`. Document in JavaDoc that listeners may fire in any order across the batch but always within the same transaction. No special handling needed in Phase 13; integration test E13-08 catches partial-commit regressions.

---

## Environment Availability

> Phase 13 has no net-new external dependencies. All runtime infrastructure (Postgres + pgvector, OpenRouter API key, Jmix FileStorage `local`) is already in use across Phases 4–12.

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Java 21 | All Java code | ✓ | 21 | — |
| Gradle wrapper | Build | ✓ | repo-bundled | — |
| PostgreSQL agentstore | `AiTaskFile` Liquibase 090 + idempotency reservation | ✓ | repo-bundled config (`10.123.123.174:5555/agentstore`) | HSQLDB profile (`./gradlew bootRun --args='--spring.profiles.active=hsqldb'`) for local dev |
| Jmix `FileStorage` `local` | Task-file blob persistence | ✓ | shipped by Jmix 2.8 | — |
| OpenRouter API key (`OPENROUTER_API_KEY` env / `.env`) | Dev model invocation | Operator-provided | n/a | vLLM/Ollama self-host (swap `spring.ai.openai.base-url`); offline tests use mock `ChatClient` |
| Qwen3.6-35B-A3B model availability | Default model post-swap | ✓ via OpenRouter | Apache 2.0 weights for self-host | Fallback chain: keep `openai/gpt-4o-mini` as the override surface for any conversation that fails on Qwen via per-conversation `AiParameters.model` |
| `@EnableScheduling` | TTL cleanup job | ✓ | Phase 11 enabled it | — |
| Servlet multipart 100 MB | Upload | ✓ | `application.properties:79-80` already at 100 MB / 110 MB | — |

**No missing dependencies. No fallbacks required.**

---

## Validation Architecture

> Project's `nyquist_validation` setting is not explicitly disabled in `.planning/config.json` — treating as enabled.

### Test Framework
| Property | Value |
|----------|-------|
| Framework | Spring Boot Test (`@SpringBootTest`) + JUnit 5 + Mockito; existing `@Tag("eval")` and `@Tag("live")` and `@Tag("rag-it")` taxonomy from Phase 6 |
| Config file | `ai-agent/ai-agent/build.gradle` (`evalTest`, `liveTest`, `integrationTest` tasks at lines 147–156) |
| Quick run command | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.taskfile.*"` |
| Full suite command | `./gradlew :ai-agent:ai-agent:test :ai-agent:ai-agent:evalTest :ai-agent:ai-agent-starter:test` |

### Phase Requirements → Test Map

| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| ENT-07 | `AiTaskFile` boots cleanly under agentstore + Liquibase 090 creates table | Spring Boot integration | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.taskfile.AiTaskFileBootTest"` | ❌ Wave 0 |
| TASK-01 | Attach button + chip strip appear in BOTH `ChatView` and `ChatDialogView` | `@UiTest` (Vaadin TestBench / Karibu-style) | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.AttachAffordanceUiTest"` | ❌ Wave 0 |
| TASK-02 | Task file isolation — `IngesterManager` invocation count = 0; pgvector count unchanged | Integration + static-source-scanner | `./gradlew :ai-agent:ai-agent:integrationTest --tests "com.vn.agent.taskfile.TaskFileIsolationTest"` | ❌ Wave 0 |
| TASK-03 | Two-phase write — pending row becomes sent row after `markSent` | Integration | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.taskfile.TaskFileTwoPhaseWriteTest"` | ❌ Wave 0 |
| TASK-04 | Outbound `Prompt.UserMessage` contains `Media` with original bytes; subsequent turn empty | Integration with mock ChatModel | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.taskfile.MediaInjectionSingleTurnTest"` | ❌ Wave 0 |
| TASK-05 | UI distinguishes 3 affordances (text / chip strip / KB upload) | `@UiTest` | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.ThreeAffordanceUiTest"` | ❌ Wave 0 |
| TASK-06 | Default model swap end-to-end | Integration smoke (mock or live) | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.DefaultModelResolutionTest"` | ❌ Wave 0 |
| MUT-14 (success) | `bulk_save_records("Customer", [10 valid], "k1")` → 10 rows, 1 audit, 1 intent | Integration | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.BulkSaveRecordsHappyPathTest"` | ❌ Wave 0 |
| MUT-14 (idempotency) | Re-call same key → IDEMPOTENT_REPLAY, no new rows | Integration | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.BulkSaveRecordsIdempotencyTest"` | ❌ Wave 0 |
| MUT-14 (rollback) | 9 valid + 1 denied → 0 rows persisted, audit FAILED with failedRowIndex | Integration | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.BulkSaveRecordsRollbackTest"` | ❌ Wave 0 |
| MUT-14 (mixed) | mixed create+update batch → correct dispatch per id-presence | Integration | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.BulkSaveRecordsMixedDispatchTest"` | ❌ Wave 0 |
| SEC-06 (partial) | userA cannot see userB's `AiTaskFile` rows; admin sees both | Integration with two test users | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.security.AiTaskFileRowLevelTest"` | ❌ Wave 0 |
| TEST-16 | Static-source-scanner: zero IngesterManager / VectorStore references in `taskfile/` package | ArchUnit-style or grep test | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.taskfile.TaskFileSourceIsolationScannerTest"` | ❌ Wave 0 |
| (TTL cleanup) | Job deletes expired row + blob | Integration | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.taskfile.AiTaskFileCleanupJobTest"` | ❌ Wave 0 |
| (MIME guard) | `.exe` rejected, 101 MB rejected, `.xlsx` 5 MB succeeds | Integration | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.taskfile.MimeAndSizeGuardTest"` | ❌ Wave 0 |
| (Phase 9 regression) | TEST-08 prompt-contract still passes with attachment present | Existing test extension | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.PromptContractMockTest"` | ✓ existing — extend with `attachment_present=true` variant |
| (Phase 11 regression) | TEST-13 zero-mutation-callbacks under default config still passes; bulk_save_records also absent | Existing test extension | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.BootMutationDefaultsTest"` | ✓ existing — add bulk_save_records assertion |

### Sampling Rate
- **Per task commit:** `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.taskfile.*" --tests "com.vn.agent.tools.mutation.BulkSaveRecords*"`
- **Per wave merge:** Full suite above + `:ai-agent:ai-agent-starter:test`
- **Phase gate:** Full suite green + `evalTest` (E13-* eval rubrics) before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `tests/com/vn/agent/taskfile/AiTaskFileBootTest.java` — Spring Boot bootstrap + Liquibase migration check (covers ENT-07)
- [ ] `tests/com/vn/agent/taskfile/AiTaskFileTwoPhaseWriteTest.java` — pending → sent message-id stamping (covers TASK-03)
- [ ] `tests/com/vn/agent/taskfile/MediaInjectionSingleTurnTest.java` — D-01 single-turn invariant + Media bytes equality (covers TASK-04)
- [ ] `tests/com/vn/agent/taskfile/TaskFileIsolationTest.java` — IngesterManager spy + pgvector count delta (covers TASK-02 / TEST-16 runtime portion)
- [ ] `tests/com/vn/agent/taskfile/TaskFileSourceIsolationScannerTest.java` — static source scanner over `com.vn.agent.taskfile.**` (covers TEST-16 source portion)
- [ ] `tests/com/vn/agent/taskfile/AiTaskFileCleanupJobTest.java` — expired row + blob deletion (covers TTL cleanup)
- [ ] `tests/com/vn/agent/taskfile/MimeAndSizeGuardTest.java` — `.exe` reject, 101 MB reject (covers REQ-10 in SPEC)
- [ ] `tests/com/vn/agent/tools/mutation/BulkSaveRecordsHappyPathTest.java` — 10 valid creates → 10 rows + 1 audit + 1 intent (covers MUT-14)
- [ ] `tests/com/vn/agent/tools/mutation/BulkSaveRecordsIdempotencyTest.java` — replay returns IDEMPOTENT_REPLAY (covers MUT-14)
- [ ] `tests/com/vn/agent/tools/mutation/BulkSaveRecordsRollbackTest.java` — partial-failure rollback (covers MUT-14)
- [ ] `tests/com/vn/agent/tools/mutation/BulkSaveRecordsMixedDispatchTest.java` — id-presence dispatch (covers D-02)
- [ ] `tests/com/vn/agent/security/AiTaskFileRowLevelTest.java` — userA cannot see userB rows (covers SEC-06)
- [ ] `tests/com/vn/agent/view/chat/AttachAffordanceUiTest.java` — `@UiTest` for both surfaces (covers TASK-01 / REQ-3)
- [ ] `tests/com/vn/agent/DefaultModelResolutionTest.java` — `chatClient` resolves with `model=qwen/qwen3.6-35b-a3b` under default config (covers REQ-1)
- [ ] No new framework install needed — JUnit 5 + Mockito + Spring Boot Test all present.
- [ ] Existing tests to extend (NOT new files): `PromptContractMockTest` (Phase 9 — add attachment variant), `BootMutationDefaultsTest` (Phase 11 — assert bulk_save_records also absent).

---

## Security Domain

`security_enforcement` is enabled (default). Phase 13 inherits Phase 9 / 10 / 11 controls and adds task-file-specific surface.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | Inherited from Jmix login + `CurrentAuthentication`; chat path runs as the user, never as system. |
| V3 Session Management | yes | `AiChatSessionState` `@VaadinSessionScope`; no Phase 13 changes (per CONTEXT D-03 pending state lives in DB). |
| V4 Access Control | yes | `AccessManager` per-CRUD + per-attribute (Phase 11 chain reused for `bulk_save_records`); `JpqlRowLevelPolicy` for `AiTaskFile` (`userUsername = :current_user_username`); `LlmExposurePolicy` for entity opacity. |
| V5 Input Validation | yes | MIME allowlist (server-side, 13 entries) + size cap (100 MB) + Bean Validation `@NotNull`; `MutationAttributeBinder.coerceAttributes` for type-safe LLM-input binding; UUID v4 idempotency-key validation (existing). |
| V6 Cryptography | yes | SHA-256 via existing `AuditFieldHasher.sha256Hex` for `requestHash` and `sampleHashes` in audit `argumentsJson`. Never hand-roll. |
| V7 Error Handling | yes | `MutationErrorTranslator` — 6 stable error codes, never echoes user-supplied PII (Phase 11 P-22 invariant); bulk path adds `failedRowIndex` only (no value echo). |
| V8 Data Protection | yes | `AiTaskFile` 1-hour TTL with hourly purge → Decree 13/2023/ND-CP retention alignment; `argumentsJson` carries hashes only — no raw PII in audit log. |
| V9 Communications | yes | Inherited — Spring AI uses HTTPS to OpenRouter (`https://openrouter.ai/api`); for self-host, the operator's intra-VPC URL governs. |
| V10 Malicious Code | partial | MIME allowlist drops executables (`.exe` rejected); MIME content-sniff cross-checks extension. No AV scan in v1.1 (defer until host requests). |
| V12 Files & Resources | yes | Jmix `FileStorage` handles temp file lifecycle; `UploadHandler.toFile` writes to a controlled location; max-file-size enforced both server- and client-side. |
| V13 API & Web Service | partial | Tool callbacks are not user-callable HTTP endpoints — they're invoked only by the LLM through the Spring AI `ChatClient`, gated by `@ConditionalOnProperty`. |

### Known Threat Patterns for Stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Cross-user task-file leak via foreign UUID | Information Disclosure | `JpqlRowLevelPolicy` on `AiTaskFile` (userUsername predicate); `AiTaskFileMediaResolver` query is conversation-scoped + the conversation row is itself row-policy-gated. |
| Unauthorized bulk write via crafted LLM tool call | Tampering / Elevation of Privilege | Phase 11 chain unchanged: marker role → `LlmExposurePolicy` → `AccessManager` per-CRUD + per-attribute → `MutationGuard` SPI. |
| Idempotency replay attack (different bytes, same key) | Tampering | `MutationRequestHasher` SHA-256 + `MutationIntentRepository.classifyExisting` returns `VIOLATION` for hash mismatch. |
| PII leak in audit log via raw record values | Information Disclosure | `argumentsJson` schema enforced as `{count, entityName, sampleHashes, idempotencyKey}` only; planner adds JSON-schema validator in `MutationAuditPayloadBuilder` (G13-05). |
| File-content exfiltration via VectorStore contamination | Information Disclosure | TEST-16 source-scanner asserts no `IngesterManager` / `VectorStore` references in `taskfile/`; runtime test asserts pgvector `count(*)` unchanged. |
| Multimodal hallucination committing wrong rows | Tampering / Repudiation | Rich `@Tool` description requires LLM to echo extracted rows BEFORE calling `bulk_save_records`; rollback-all on per-row failure prevents silent partial commits. |
| MIME-type spoof (`.exe` renamed `.pdf`) | Tampering | Defence-in-depth: server-side validation by extension AND content-sniff via `MimeTypeUtils`; resolver validates again before building Media. |
| File-size DoS (1 GB upload) | Denial of Service | Vaadin `Upload.setMaxFileSize(...)` client-side + servlet `spring.servlet.multipart.max-file-size=100MB` server-side + `ai-agent.task-file.max-file-size-bytes` resolver gate. |
| TTL bypass leaving stale PII | Information Disclosure / non-compliance | Hourly `@Scheduled` cron job + opportunistic cleanup at every chat entry; integration test asserts deletion after `expiresAt < now`. |

### Compliance Posture

`[CITED: 13-AI-SPEC.md §1b]` Vietnam Decree 13/2023/ND-CP + Law 91/2025/QH15 are the dominant regulatory frame. Phase 13's net-new compliance work is the PII-safe audit trail (sample-hash only) and the 1-hour TTL with auto-purge. SOC 2 Processing Integrity maps cleanly to the Phase 11 audit-row design. GDPR / PCI-DSS / SOX / HIPAA are out of scope for v1.1.

---

## Project Constraints (from CLAUDE.md)

These are non-negotiable directives that the planner MUST honor for every task in Phase 13:

- **Stack:** Java 21 / Jmix 2.8 / Spring Boot 3 / Vaadin Flow / PostgreSQL / Gradle.
- **Skills lookup:** Use Skill tool + `jmix-framework/jmix-context7` Context7 library for any uncertain Jmix API.
- **JetBrains MCP:** Use `get_file_problems("path/to/file.ext", onlyErrors=false)` after Java edits (per memory `feedback_jetbrains_mcp_in_workflow`).
- **Entities:** `@JmixEntity`, UUID + `@JmixGeneratedValue`, `@Version`, `@InstanceName`, NO Lombok.
- **Entity instantiation:** Use `Metadata.create()` or `DataManager.create()` — never the constructor.
- **Data access:** `DataManager` only (NEVER `EntityManager`); fluent loaders; build optimized fetch plans.
- **Services:** Constructor injection only.
- **Views:** XML descriptor + Java controller pair; `@Subscribe`/`@Install` event wiring; menu entry; messages in BOTH locale files.
- **Security:** Resource roles as interfaces with `@ResourceRole`; `@EntityPolicy` / `@EntityAttributePolicy` / `@ViewPolicy` / `@MenuPolicy`.
- **Liquibase:** new changelog file in `liquibase/agentstore-changelog/090-…xml`; auto-included by root `<includeAll>`.
- **Testing:** prefer `@SpringBootTest` integration tests for business logic; `@UiTest` for UI; auto-Liquibase test schema.
- **Forbidden:** Lombok on entities; entity constructor; `EntityManager`; business logic in views; hardcoded UI text (use `msg://`); single-locale messages; edits in `frontend/generated/`.
- **Validation checklist (per CLAUDE.md):** entity = UUID + Version + InstanceName; changelog included; messages in all locales; view XML+Java pair + menu update; security covers entity/view/menu.
- **Workflow after Java work:** (1) JetBrains `get_file_problems`; (2) write tests; (3) `./gradlew test`; (4) UI verification via Playwright if running.

Plus from MEMORY.md:
- **No abbreviated identifiers** — `feedback_no_abbreviations`. Spell out fully.
- **Reuse Jmix built-ins** before owning anything new — `feedback_reuse_jmix_builtins`.
- **AI is just another Jmix client** — security via `AccessManager` / `DataManager`, no AI-specific layer.
- **Jmix-first UI over raw Vaadin** — XML descriptors + Jmix components.
- **`@Subscribe`/`@Install` for event wiring** — verify uncertain syntax via Context7 → Jmix docs → GitHub.
- **`@Supply(to=, subject="renderer")` for grid renderers** — but Phase 13 has no DataGrid, so N/A.
- **Jmix `Messages` over Spring `MessageSource`** — `@NonNull`, auto-locale.
- **`UnconstrainedDataManager` for system-internal writes** — TTL cleanup uses it; user-thread reads use regular `DataManager`.
- **Rich `@Tool` descriptions for enterprise tools** — 5-section MANDATORY/INPUT/FORMATS/ERROR/STRICTNESS+EXAMPLES with TWO worked examples.
- **Self-hostable models only** — Apache 2.0+; default = `qwen/qwen3.6-35b-a3b`.
- **`loadValue` needs explicit `.store("agentstore")`** — but Phase 13 uses `dataManager.load(EntityClass).query(...)`, so inferred — only matters if planner adds projection queries.
- **JPQL row-level policy on user-owned entities** — `{E}.userUsername = :current_user_username` is the established pattern.

---

## Sources

### Primary (HIGH confidence)
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java` — Phase 11 mutation tool pattern (line-level reference for `bulk_save_records` skeleton)
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationSaveExecutor.java` — single proxy-crossed transactional save boundary
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java` — idempotency reservation/replay state machine
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationRequestHasher.java` — canonical-JSON SHA-256 hash for batch
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java` lines 140–189 — `writeToolCall` REQUIRES_NEW signature
- `[VERIFIED: codebase]` `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/service/AiAttachmentMediaResolver.java` — verbatim port target for `AiTaskFileMediaResolver`
- `[VERIFIED: codebase]` `D:/DTH/jmix-crm/src/main/java/com/company/crm/ai/service/CrmAnalyticsService.java` lines 117–131 — `chatClient.prompt().user(u -> { u.text(); u.media(); })` injection pattern
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java` + `AiMessage.java` — Jmix entity shape pattern for `AiTaskFile`
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/070-ai-mutation-intent.xml` + `080-ai-ui-settings.xml` — Liquibase changelog shape for `090-ai-task-file.xml`
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml` line 41 — `attachmentsPanel` placeholder slot
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` lines 86–115 — `messageInputSlot` programmatic wiring pattern (mirror for the attach button)
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java` lines 247–270 (ask) + 369–380 (stream) — `chatClient.prompt()` insertion points
- `[VERIFIED: codebase]` `D:/DTH/ai-agent-core/ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java` + `AiAgentUserRowLevelRole.java` + `AiAgentAdminRole.java` — role-extension pattern
- `[CITED: 13-CONTEXT.md]` All decisions D-01 through D-04 + Claude's Discretion + Deferred Ideas — locked
- `[CITED: 13-SPEC.md]` 11 locked requirements — bound for the planner
- `[CITED: 13-AI-SPEC.md]` Sections 1, 1b, 3, 4, 4b, 5, 6 — Spring AI 1.1.4 Media + tool annotations + observability + guardrails

### Secondary (MEDIUM confidence)
- `[CITED: project memory feedback_jmix_upload_receiver_deprecated]` — `Upload.getReceiver/setReceiver` is `forRemoval` in Vaadin 24.8; use `UploadHandler.toFile`. Multi-file API not directly verified — planner-must-verify (Pitfall 7 + Open Question 1).
- `[CITED: Spring AI 1.1.4 docs]` — `org.springframework.ai.content.Media`, `Media.Format.*`, `chatClient.prompt().user(u -> u.media(...))`, `JdbcChatMemoryRepository` text-only schema (cited via AI-SPEC §3 but not directly fetched in this research session — verify before final wiring)
- `[CITED: project memory feedback_jmix_loadvalue_store]` — `.store("agentstore")` required for raw `loadValue/loadValues` JPQL but NOT for `dataManager.load(EntityClass).query(...)` (verified by inspection of Phase 11 `MutationIntentRepository` raw `JdbcTemplate` usage)
- `[CITED: project memory feedback_jmix_unconstrained_for_system_writes]` — `UnconstrainedDataManager` for TTL cleanup writes
- `[CITED: project memory feedback_rich_tool_descriptions]` — 5-section template for `bulk_save_records` `@Tool` description

### Tertiary (LOW confidence — flagged for validation)
- `[ASSUMED]` jmix-flowui 2.8 supports `<upload multiple="true">` with `UploadHandler.toFile` lambda capturing each file. Open Question 1 — planner verifies via Context7.
- `[ASSUMED]` `dataManager.save(SaveContext)` with N entities commits atomically inside `MutationSaveExecutor.bulkSave`'s `@Transactional` span. A1 — verified by Wave 0 integration test E13-08.
- `[ASSUMED]` Spring AI's chat-memory advisor commits the just-persisted USER message synchronously before `.call()` returns. A6 — planner verifies via Context7 docs lookup or in-test SELECT timing.

---

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — 100% reuse of existing libraries; zero new dependencies; verified versions in `build.gradle`.
- Architecture: HIGH — direct extension of Phase 11 chain (`MutationSaveExecutor`/`MutationRequestHasher`/`MutationIntentRepository`/`AuditWriter`) and Phase 12 fragment slot (`attachmentsPanel`); verbatim port from in-tree jmix-crm reference; locked decisions D-01..D-04 in CONTEXT.md.
- Pitfalls: HIGH — every pitfall is grounded in either Phase 11 in-repo experience (Pitfalls 1, 4) or project memories (Pitfalls 6, 7) or AI-SPEC §1b domain research (Pitfalls 5, 9) or AI-SPEC §3 framework gotchas (Pitfalls 2, 8).
- UI affordance shape: MEDIUM-HIGH — locked D-04, but multi-file `UploadHandler.toFile` API surface in jmix-flowui 2.8 is the one MEDIUM-confidence item — flagged in Open Question 1 + Pitfall 7 + A4 for planner verification BEFORE writing the upload code.

**Research date:** 2026-05-05
**Valid until:** 2026-06-05 (30 days; Spring AI 1.1.4 + Jmix 2.8 are stable; Qwen3 model availability on OpenRouter may shift faster — re-verify model card before final ship if ship date > 14 days from now)
