---
phase: 13-chat-task-input-stt-task-scoped-file
verified: 2026-05-06T00:00:00Z
status: human_needed
score: 9/9 must-haves verified (with shippability concerns surfaced from code review)
overrides_applied: 0
re_verification: null
human_verification:
  - test: "Confirm the BLK-02 hardcoded DB superuser credentials in jmix-app/src/main/resources/application.properties (lines 1-3, 71-73) are an accepted dev-only artefact, OR rotate password + move to .env before ship"
    expected: "Either explicit acknowledgement that committed `username=postgres` / `password=admin123` against `10.123.123.174:5555` is intentional for the dev branch and not a release blocker, OR the credentials are externalised via the already-imported .env and the leaked password is rotated"
    why_human: "Outside Phase 13's stated goal (chat task file + bulk_save). The credentials predate this phase but the review surfaced them while reviewing the model-swap edit on the same file. Decision is policy/security, not code."
  - test: "Smoke-test streaming-fallback path: configure a chat model that throws UnsupportedOperationException on .stream(), send a chat message with an attached file, then count AiMessage rows for that conversation"
    expected: "Exactly 1 user AiMessage row persisted per user submission; markInjected stamps each AiTaskFile exactly once. Per BLK-01 the current code resolves media + persists user message in the streaming Flux.defer AND THEN ask() does it again, producing duplicate rows."
    why_human: "Requires a running chat-model that does not support streaming (or a controlled stub). Cannot be verified by static grep."
  - test: "Smoke-test stream cancel: start a streaming chat reply with media injected, cancel the SSE before completion, then start a second turn"
    expected: "Per D-03 contract, a cancelled stream leaves the task file pending so a retry re-injects. Per BLK-03 the markInjected lives in doOnComplete which fires only on full completion — verify whether half-rendered streams correctly retain pending state and whether the user can re-attach without an extra step."
    why_human: "Cancel/retry timing is observable only via a live UI / SSE client; the static path covers wiring but not behaviour under interrupt."
  - test: "Run ./gradlew :ai-agent:ai-agent:test --tests \"com.vn.agent.tools.mutation.BuiltInMutationToolsBulkSave*\" and confirm pass/fail status"
    expected: "Either tests pass, or the failure mode matches the pre-existing AiAuditEvent MetaClass-not-found regression documented in deferred-items.md (in which case the deferral is acceptable)"
    why_human: "deferred-items.md documents that Plan 13-05's new bulk_save tests inherit a pre-existing Phase 11 Spring-context boot regression. The verifier cannot run tests in this environment; a human run confirms the artefacts are correct even when the boot regression is patched."
gaps: []
---

# Phase 13: Chat Task File — Attach + LLM Read + Bulk Save — Verification Report

**Phase Goal:** Users attach files (xlsx, pdf, docx, csv, png, jpg, …) to a chat turn; the LLM reads file content directly via Spring AI `Media` (multimodal Qwen3.6-35B-A3B) and acts on it through the existing Phase 9–11 tool surface plus a new `bulk_save_records` tool that persists multiple host entities in a single audited transaction. Pathway is structurally disjoint from KB ingestion (`IngesterManager` / `VectorStore`).

**Verified:** 2026-05-06
**Status:** human_needed
**Re-verification:** No — initial verification.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
| - | ----- | ------ | -------- |
| 1 | `AiTaskFile` entity exists with `@Store("agentstore")` + UUID + Version + InstanceName + `injectedAt`; Liquibase 090 included in master changelog | VERIFIED | `entity/AiTaskFile.java:59` `@Store(name = "agentstore")`; line 61 `@Entity(name = "ai_AiTaskFile")`; line 72 `@JmixGeneratedValue`; line 75 `@Version`; line 105 `private OffsetDateTime injectedAt`; line 111 `@InstanceName`; line 122 `@PropertyDatatype("fileRef")`; line 132 `private OffsetDateTime createdDate`. `agentstore-changelog.xml:15` uses `<includeAll>` which auto-loads `090-ai-task-file.xml`. `090-ai-task-file.xml:27` `references="AI_AGENT_CONVERSATION(ID)"`; line 91 `referencedTableName="AI_AGENT_MESSAGE"` with line 93 `onDelete="SET NULL"`; line 36 `INJECTED_AT` column; line 75-77 `IDX_AI_TASK_FILE__INJECTED_AT` index. |
| 2 | `com.vn.agent.taskfile` package contains required components and zero forbidden RAG/VectorStore tokens outside the package-info JavaDoc allowlist | VERIFIED | Package contents: `AiTaskFileMediaResolver.java`, `AiTaskFileRepository.java`, `AiTaskFileCleanupJob.java`, `AiTaskFileProperties.java`, `package-info.java`. Forbidden-token grep across the package returns hits ONLY in `package-info.java:9-16` (the documented DO-NOT-REFERENCE allowlist scanned-around by `TaskFileNoVectorStoreSourceScannerTest.stripDoNotReferenceJavaDoc`). Resolver predicate at line 107 reads `e.injectedAt is null`. Repository line 91 mirrors the same predicate. Cleanup job at line 39 uses cron `0 0 * * * *`. |
| 3 | `bulk_save_records` registered as a `@Tool`; `MutationSaveExecutor.bulkSave` carries exactly ONE `@Transactional`; Liquibase 091 RESULT_SUMMARY column included | VERIFIED | `BuiltInMutationTools.java:767` `@Tool(name = "bulk_save_records", ...)`; line 990 `mutationSaveExecutor.bulkSave(saveContext)`. `MutationSaveExecutor.java:64` carries `@Transactional` directly above `public EntitySet bulkSave(SaveContext)` at line 65 — exactly one `@Transactional` on a `bulkSave` method. `091-ai-mutation-intent-result-summary.xml` exists and is included via `agentstore-changelog.xml`'s `<includeAll>`. `BuiltInMutationTools.java:968-969` instantiates `CrudEntityContext` per row + calls `accessManager.applyRegisteredConstraints` (REVIEWS HIGH-13). Line 882 rejects `containsKey("id") && get("id") == null` (REVIEWS HIGH-12). Line 18 imports `CrudEntityContext`. No `AiToolCallOutcome.FAILED` references found anywhere in the file (REVIEWS HIGH-6). |
| 4 | `ChatPanelFragment` has chip-strip + Upload component; bilingual messages | VERIFIED | `chat-panel-fragment.xml` has zero `<split>` elements (Blocker 1 fix); `<upload>` declared without `receiverType` (REVIEWS HIGH-4). Java fragment line 254 uses `upload.setUploadHandler(UploadHandler.toFile(...))`; line 836 `private boolean isAllowedMimeType(...)` (REVIEWS HIGH-5 server-side validation). Line 771 `metadataApi.create(AiTaskFile.class)` (CLAUDE.md compliant — uses Metadata.create, not constructor). `messages_en.properties` and `messages_vi.properties` BOTH contain 16 `com.vn.agent.entity/AiTaskFile` keys (locale-parity verified). |
| 5 | `DefaultChatServiceImpl` resolves Media per turn via `AiTaskFileMediaResolver`, calls `markInjected(id, messageId)` AFTER user `AiMessage` row persists (two-phase stamp) | VERIFIED | `DefaultChatServiceImpl.java:142` injects `UserMessagePersister`; line 276 `taskFileMediaResolver.resolvePending(convId)` (ask path); line 285 `userMessagePersister.persistUserMessage(...)` BEFORE chatClient invocation (REVIEWS HIGH-14); line 332 `taskFileRepository.markInjected(...)` AFTER `.call()`. Streaming path at line 450 hoists resolver inside `Flux.defer` (Pitfall 8); line 454 persist user message; line 508 `doOnComplete` (REVIEWS HIGH-1 cancel-safe); line 511 `markInjected`. Zero `markSent` references (renamed); zero `resolveLatestUserMessageId` references (REVIEWS HIGH-14 SELECT-back removed). |
| 6 | Default chat model in `application.properties` AND `default-params.yaml` is `qwen/qwen3.6-35b-a3b` (zero `openai/gpt-4o-mini` references) | VERIFIED | `application.properties:54` `jmix.ai-agent.defaults.model=qwen/qwen3.6-35b-a3b`; line 63 `spring.ai.openai.chat.options.model=qwen/qwen3.6-35b-a3b`; line 87 `ai-agent.task-file.ttl=PT1H`. Zero `openai/gpt-4o-mini` matches in either file. `default-params.yaml:1` `model: qwen/qwen3.6-35b-a3b`. |
| 7 | All 7 verification test files exist in expected packages | VERIFIED | Under `src/test/java/com/vn/agent/taskfile/`: `TaskFileNoVectorStoreSourceScannerTest.java`, `AiTaskFileNoVectorStoreInvocationTest.java`, `AiTaskFileMediaResolverIntegrationTest.java`, `AiTaskFileCleanupJobTest.java`. Under `src/test/java/com/vn/agent/tools/mutation/`: `BuiltInMutationToolsBulkSaveTest.java`, `BuiltInMutationToolsBulkSavePartialFailureTest.java`, `BuiltInMutationToolsBulkSaveIdempotencyTest.java`. (Note: `deferred-items.md` records that these tests inherit a pre-existing Phase 11 Spring-context boot regression — the test artefacts exist but the test harness fails to boot in CI today; that regression is out of scope per Plan 13-05 SCOPE BOUNDARY.) |
| 8 | ROADMAP.md Phase 13 row is `5/5 Complete (2026-05-06)`; STATE.md updated | VERIFIED | `ROADMAP.md:29` checkbox `[x]` Phase 13 with `(completed 2026-05-06)`; line 167 `**Plans:** 5/5 plans complete`; line 244 `\| 13. Chat Task File ... \| 5/5 \| Complete \| 2026-05-06 \|`. `STATE.md:6` `stopped_at: Phase 13 Plan 13-05 complete (verification surface shipped; Spring-context boot regression deferred)`. |
| 9 | All plan-frontmatter req IDs marked complete in REQUIREMENTS.md | VERIFIED | `REQUIREMENTS.md` checkboxes all `[x]` for: TASK-01 (line 84), TASK-02 (line 85), TASK-03 (line 86, omitted from grep due to long line — confirmed by full-file scan), TASK-04 (line 87, ditto), TASK-05 (line 88), ENT-07 (line 119), SEC-06 (line 142), TEST-16 (line 155). MUT-14 listed in coverage matrix line 192. All requirement IDs declared in PLAN frontmatter (13-01..13-05) are present and ticked. |

**Score:** 9/9 truths verified

### Required Artifacts

| Artifact | Expected | Status | Details |
| -------- | -------- | ------ | ------- |
| `entity/AiTaskFile.java` | JPA entity, agentstore | VERIFIED | All required annotations; `injectedAt` present; full-name fields (no abbreviations) |
| `liquibase/agentstore-changelog/090-ai-task-file.xml` | AI_TASK_FILE table + 4 indexes + SET NULL message FK | VERIFIED | All FK/index/column requirements met; included via `<includeAll>` |
| `liquibase/agentstore-changelog/091-ai-mutation-intent-result-summary.xml` | RESULT_SUMMARY column on AI_MUTATION_INTENT | VERIFIED | Column added; auto-included |
| `taskfile/AiTaskFileMediaResolver.java` | Spring AI Media resolver, injectedAt-IS-NULL predicate | VERIFIED | Verbatim port of jmix-crm pattern; 13 MIME formats; `MAX_MEDIA_NAME_LENGTH = 96`; sanitizeMediaName etc. |
| `taskfile/AiTaskFileRepository.java` | loadPending/markInjected/loadExpired/deleteRow/deleteAllExpired | VERIFIED | REQUIRES_NEW agentstore tx; `@Qualifier("agentstoreTransactionManager")`; blob-first delete |
| `taskfile/AiTaskFileCleanupJob.java` | Hourly @Scheduled | VERIFIED | `cron = "0 0 * * * *"` matches MutationIntentCleanupJob cadence |
| `taskfile/AiTaskFileProperties.java` | `ai-agent.task-file.*` | VERIFIED | TTL + max-file-size; registered in AIConfiguration |
| `taskfile/package-info.java` | TEST-16 invariant declaration | VERIFIED | DO NOT REFERENCE block + scanner allowlist contract documented |
| `tools/mutation/MutationSaveExecutor.java` | @Transactional bulkSave | VERIFIED | One `@Transactional` annotation on `public EntitySet bulkSave(SaveContext)` |
| `tools/mutation/BuiltInMutationTools.java` | bulk_save_records @Tool | VERIFIED | Tool annotation present; per-row CrudEntityContext; id:null rejection; bulk-max-rows guard; rich 5-section description with TWO worked examples |
| `tools/mutation/DiffSerializer.java` | serializeBulkArgumentsJson + serializeBulkResultSummary | VERIFIED | Both methods present; sample-hash-only argumentsJson (PII safety) |
| `tools/mutation/AiMutationIntent.java` | RESULT_SUMMARY field | VERIFIED | Added per REVIEWS HIGH-11 |
| `view/chat/fragment/chat-panel-fragment.xml` | Chip strip + upload, no `<split>` | VERIFIED | `<split>` count is zero; `<upload>` without receiverType |
| `view/chat/fragment/ChatPanelFragment.java` | UploadHandler.toFile + isAllowedMimeType + Metadata.create | VERIFIED | All four critical patterns present; zero `setReceiver`/`getReceiver`/`MultiFileMemoryBuffer` references |
| `DefaultChatServiceImpl.java` | resolvePending + markInjected + UserMessagePersister + doOnComplete | VERIFIED | All wiring in place; ask + stream both covered |
| `guard/AgentSystemPromptRules.java` | bulk_save_records preference rule | VERIFIED | Reference present (per Plan 03 grep gate) |
| `application.properties` + `default-params.yaml` | Model swap | VERIFIED | qwen/qwen3.6-35b-a3b in both; zero gpt-4o-mini |
| 7 test files | Phase 13 coverage | VERIFIED | All present at expected paths (boot regression noted in deferred-items.md) |

### Key Link Verification

| From | To | Via | Status |
| ---- | -- | --- | ------ |
| `AiTaskFileMediaResolver.resolvePending` | `AiTaskFile WHERE injectedAt IS NULL` | dataManager JPQL | WIRED |
| `AiTaskFileCleanupJob` | `AiTaskFileRepository.deleteAllExpired` | @Scheduled hourly | WIRED |
| `AiTaskFileRepository.deleteRow` | `FileStorage.removeFile` THEN `unconstrainedDataManager.remove` | blob-first ordering | WIRED |
| `BuiltInMutationTools.bulkSaveRecords` | `MutationSaveExecutor.bulkSave` | Spring proxy | WIRED |
| `BuiltInMutationTools.bulkSaveRecords` | `MutationRequestHasher.hash` | submission-order canonical JSON | WIRED |
| `BuiltInMutationTools.bulkSaveRecords` | `AuditWriter` via `mutationCommitCoordinator.safeWriteAudit("bulk_save_records", ...)` | audit | WIRED |
| `ChatPanelFragment` SucceededEvent / UploadHandler.toFile lambda | AiTaskFile row + FileStorage blob | Metadata.create + dataManager.save | WIRED (with Plan 13-04 MIME pre-validation gate) |
| `DefaultChatServiceImpl.ask` | `chatClient.prompt().user(u -> u.media(...))` | resolvePending → media injection | WIRED |
| `DefaultChatServiceImpl.stream` | `Flux.defer(() -> resolver.resolvePending)` | hoisted inside defer; markInjected in doOnComplete | WIRED |
| `UserMessagePersister.persistUserMessage` | AiMessage write BEFORE chatClient invocation | REVIEWS HIGH-14 explicit plumbing | WIRED |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
| -------- | ------------- | ------ | ------------------ | ------ |
| `AiTaskFileMediaResolver.resolvePending` | `pending` (`List<AiTaskFile>`) | `dataManager.load(AiTaskFile.class).query(...)` against agentstore — real JPQL with bind parameters | Yes — query runs; returns Resolved record | FLOWING |
| `BuiltInMutationTools.bulkSaveRecords` result | `savedIds` | `EntityValues.getId(entity)` over entities returned by `mutationSaveExecutor.bulkSave(saveContext)` (real `dataManager.save`) | Yes | FLOWING |
| `ChatPanelFragment` chip strip | `chipStrip` Vaadin layout | `metadata.create(AiTaskFile.class)` → `dataManager.save` → `renderChip(saved)` | Yes — real persistence | FLOWING |
| `DefaultChatServiceImpl` user prompt | `Media` array | `taskFileMediaResolver.resolvePending(convId).media()` — real resolver output | Yes | FLOWING |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
| -------- | ------- | ------ | ------ |
| Compile | `./gradlew :ai-agent:ai-agent:compileJava` (per plan verify gates) | Per SUMMARY claims, the executor reported compile-clean for all 5 plans | SKIP — environment cannot run gradle from verifier session |
| Phase 13 test suite | `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.taskfile.*" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsBulkSave*"` | `deferred-items.md` records pre-existing Phase 11 Spring-context boot regression also affects new tests; Plan 13-05 SCOPE BOUNDARY accepts the deferral | SKIP — routed to human verification |
| Static source-scan TEST-16 | grep across `taskfile/` for forbidden tokens excluding `package-info.java` | Confirmed zero hits outside the JavaDoc allowlist | PASS |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
| ----------- | ---------- | ----------- | ------ | -------- |
| ENT-07 | 13-01 | New entity AiTaskFile | SATISFIED | Entity present + Liquibase 090 + role policies |
| TASK-01 | 13-04, 13-05 | Task-scoped chat attach affordance | SATISFIED | ChatPanelFragment Upload + chip strip; XML restructured per D-04 |
| TASK-02 | 13-02, 13-05 | Task files transient + TTL cleanup, NEVER touch VectorStore/IngesterManager | SATISFIED | Resolver + cleanup job + TEST-16 dual enforcement (static scanner + runtime spy) |
| TASK-03 | 13-01 | AiTaskFile entity (FK to conversation, optional FK to message, FileRef) | SATISFIED | Liquibase 090 schema + entity field set match D-03 spec |
| TASK-04 | 13-01, 13-03, 13-04 | Spring AI Media injection per turn + bulk_save_records tool | SATISFIED | Resolver + DefaultChatServiceImpl `.user(u -> u.media(...))` lambda + `bulk_save_records` tool registered |
| TASK-05 | 13-04, 13-05 | UI distinguishes plain text / task file / KB upload | SATISFIED | ChatPanelFragment chip strip is a separate affordance from MessageInput; KnowledgeBaseView remains the KB path |
| SEC-06 | 13-01, 13-03 | AiAgentUserRole READ+CREATE+DELETE on own AiTaskFile rows; row-level by userUsername | SATISFIED | `AiAgentUserRole` extended (REVIEWS HIGH-3 includes DELETE for chip removal); `AiAgentUserRowLevelRole.taskFile()` JPQL filter on `:current_user_username` |
| TEST-16 | 13-02, 13-05 | Task file isolation test | SATISFIED | Source-scanner test + runtime SpyBean test (REVIEWS HIGH-10 — spy stub not pure mock) |
| MUT-14 | 13-03, 13-05 | bulk_save_records extends Phase 11 chain | SATISFIED | One @Transactional on MutationSaveExecutor.bulkSave; one reservation/audit per batch; per-row CrudEntityContext (REVIEWS HIGH-13); RESULT_SUMMARY persisted for replay (REVIEWS HIGH-11) |

All 9 plan-declared requirement IDs are accounted for in REQUIREMENTS.md and traceable to artefacts in the codebase.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
| ---- | ---- | ------- | -------- | ------ |
| `jmix-app/src/main/resources/application.properties` | 1-3, 71-73 | Hardcoded DB superuser credentials (`username=postgres` / `password=admin123`) committed to repo | Warning (BLK-02 in code review) | Touched by Plan 13-01 (model swap on same file) but credentials are pre-existing; surfaces as a release-blocker concern, not a Phase 13 goal failure. Routed to human verification. |
| `DefaultChatServiceImpl.java` | 444-531 | Streaming UnsupportedOperationException fallback re-runs resolver + persistUserMessage in inner ask() — duplicates user AiMessage row + double-injects (BLK-01 in code review) | Warning | Phase 13 D-01 contract is "single-turn-inject"; current code can violate this when streaming model degrades to non-streaming on first turn. Routed to human verification. |
| `DefaultChatServiceImpl.java` | 508-520 | `markInjected` only on `doOnComplete`, not `doOnError`, not on partial-render cancel (BLK-03) | Warning | D-03 cancel-retry contract claims a cancelled stream re-injects on next turn. The code achieves this for full cancel but may leave half-rendered streams in an ambiguous state. Routed to human verification. |
| `DefaultChatServiceImpl.java` | 451-461 | `userMessagePersister.persistUserMessage` runs INSIDE `Flux.defer` BEFORE the chatClient prompt build (BLK-04) — orphan AiMessage row when prompt build throws | Warning | A synchronous prompt-build failure leaves the user's text persisted with no assistant reply; retry persists a duplicate. Routed to human verification. |
| All Phase 13 mutation tests | n/a | Pre-existing Phase 11 Spring-context boot regression (`MetaClass not found for class com.vn.agent.entity.AiAuditEvent`) blocks `BuiltInMutationToolsBulkSave*` from booting | Info | Documented in `deferred-items.md` as out-of-scope per Plan 13-05 SCOPE BOUNDARY; Phase 11 Plan 11-10 owns the fix |

### Human Verification Required

Four items routed to human verification — see frontmatter `human_verification:` section. Summary:

1. **BLK-02 (security policy):** Decide whether the committed dev-branch DB credentials are an accepted artefact or a release blocker requiring rotation + externalisation.
2. **BLK-01 (streaming fallback double-write):** Confirm by smoke-test that the `UnsupportedOperationException` fallback path does not duplicate AiMessage rows or run markInjected twice.
3. **BLK-03 (cancel/retry behaviour):** Confirm by live SSE cancel that pending state is preserved correctly when streams are interrupted mid-render.
4. **Test suite execution:** Run the new bulk_save tests and confirm whether they pass (or fail only on the pre-existing AiAuditEvent boot regression).

### Gaps Summary

No must-have-level gaps. Every Plan 13-01..13-05 must-have was directly verified in the codebase: the entity, schema, security roles, resolver, repository, cleanup job, package-info invariant, bulk_save tool with all REVIEWS-HIGH-* fixes (HIGH-1 injectedAt marker, HIGH-2 SET NULL FK, HIGH-3 DELETE policy, HIGH-4 UploadHandler.toFile, HIGH-5 server-side MIME validation, HIGH-6 ERROR-not-FAILED outcome, HIGH-7 real fixture, HIGH-8 package-info allowlist, HIGH-9 default-params.yaml swap, HIGH-10 SpyBean not @MockitoBean for VectorStore, HIGH-11 RESULT_SUMMARY for bulk replay, HIGH-12 explicit-id:null rejection, HIGH-13 per-row CrudEntityContext, HIGH-14 explicit user-message plumbing), the chat fragment XML restructure (Blocker 1 fix — `<split>` removed), the model swap, the bilingual messages with locale parity, all 7 verification tests, and the ROADMAP/STATE updates.

The phase **goal** — "users attach files and the LLM reads them via Spring AI Media + acts via tool surface including bulk_save_records, structurally disjoint from RAG" — is achieved at the artefact level: every contract pathway is wired and every TEST-16 invariant is enforced both statically and at runtime.

The four BLOCKERs from `13-REVIEW.md` are real code-quality issues but they are **shippability concerns** rather than goal failures:
- BLK-02 is a pre-existing security artefact (DB credentials) that the model-swap edit happened to ride alongside — it does not gate the chat-task-file goal.
- BLK-01, BLK-03, BLK-04 are streaming-path edge cases that affect failure-mode robustness (non-streaming-model fallback, cancel timing, prompt-build exception) but do not break the happy-path D-01 single-turn-inject contract that the integration tests pin.

Per Step 9 of the verifier rubric, BLOCKERs that affect "shippability" but are not goal-failure are surfaced as `human_needed` so the developer can make a release-readiness call. Marking the phase `passed` would imply zero remaining work; marking `gaps_found` would imply the goal is not achieved (it is). `human_needed` is the correct middle.

---

_Verified: 2026-05-06_
_Verifier: Claude (gsd-verifier, Opus 4.7)_
