---
phase: 13
reviewers: [codex, opencode]
reviewed_at: 2026-05-06T03:51:02Z
plans_reviewed:
  - 13-01-PLAN.md
  - 13-02-PLAN.md
  - 13-03-PLAN.md
  - 13-04-PLAN.md
  - 13-05-PLAN.md
cycle: 1
---

# Cross-AI Plan Review — Phase 13

> Reviewers were given PROJECT.md (80 lines), the Phase 13 ROADMAP section, REQUIREMENTS.md, 13-CONTEXT.md, 13-SPEC.md, and all five 13-0*-PLAN.md files. Codex additionally explored the live repo (source files for `AiToolCallOutcome`, `MutationTestFixture`, `ProjectingChatMemoryRepository`, `default-params.yaml`, `MutationCommitCoordinator`).

## Codex Review (codex-cli 0.128.0, gpt-5.3-codex default)

## Overall Assessment

The phase decomposition is strong and mostly aligned with ENT-07, TASK-01..06, MUT-14, TEST-16, and D-01/D-02/D-03. The biggest issue is that several plans assume stable `AiMessage` rows, but the current `ProjectingChatMemoryRepository` deletes and reinserts projected `AiMessage` rows on every `saveAll()` (`ProjectingChatMemoryRepository.java:82`). That can either break the `AiTaskFile.message` FK or unset it, causing old attachments to become "pending" again. This must be fixed before execution.

Docs checked via Context7: Jmix row-level policy `:current_user_username` is valid; Spring AI `user(u -> u.text(...).media(...))` is valid. Jmix Upload docs are less supportive of the hard-coded `receiverType="MULTI_FILE_MEMORY_BUFFER"` claim: docs emphasize `receiverFqn`, while samples show `MultiFileMemoryBuffer`.

## Cross-Plan Blockers

- **HIGH:** D-01 single-turn injection is not safe with current chat-memory projection. Projected messages are delete/reinserted each turn, so `AiTaskFile.message_id` can be broken or reset. Plans 02/04 rely on `messageId IS NULL` (`13-02-PLAN.md:155`, `13-04-PLAN.md:525`). Add a stable `sentAt` / `injectedAt` / `consumed` flag and use that for pending resolution; keep `messageId` only for optional display linkage.
- **HIGH:** Default model swap is incomplete. Plan 01 changes only `jmix-app/application.properties` (`13-01-PLAN.md:333`), but clean boot seeds active `AiParameters` from `default-params.yaml` (`ai-agent-starter/src/main/resources/default-params.yaml:1`), which still says `openai/gpt-4o-mini`. Update starter seed + tests/docs.
- **HIGH:** User attachment removal will fail. Plan 01 grants users READ+CREATE only (`13-01-PLAN.md:257`), while Plan 04 removes rows with `dataManager.remove(row)`. Grant DELETE on own pending rows or route removal through a service that checks ownership then uses `UnconstrainedDataManager`.
- **HIGH:** `AiToolCallOutcome.FAILED` is assumed but does not exist. Plan 03 says `SUCCESS / FAILED / IDEMPOTENT_REPLAY / COMMIT_FAILED` already exists (`13-03-PLAN.md:102`); actual enum has `SUCCESS`, `BLOCKED`, `ERROR`, `FLAGGED`, `IDEMPOTENT_REPLAY`, `COMMIT_FAILED` (`AiToolCallOutcome.java:7`).

## 13-01 Plan

**Summary:** Good foundation plan for `AiTaskFile`, Liquibase, properties, roles, i18n, and model defaults. It follows Jmix entity conventions well, but misses two important runtime interactions: seeded `AiParameters` and attachment deletion permissions.

**Strengths**
- Uses `@JmixEntity`, UUID, `@Version`, `@InstanceName`, no Lombok, and `@PropertyDatatype("fileRef")`.
- Correctly uses `messages_en.properties` + `messages_vi.properties` only (`13-01-PLAN.md:111`).
- Correctly names existing FK tables `AI_AGENT_CONVERSATION` and `AI_AGENT_MESSAGE` (`13-01-PLAN.md:172`).
- Row-level policy uses valid Jmix `:current_user_username` syntax.

**Concerns**
- **HIGH:** `MESSAGE_ID` Liquibase says `deleteCascade="false"` (`13-01-PLAN.md:173`), not `ON DELETE SET NULL`; this can block `AiMessage` pruning.
- **HIGH:** Active seeded params override the application property model, so REQ-1 acceptance may fail.
- **HIGH:** READ+CREATE user policy conflicts with Plan 04 chip removal.
- **LOW:** Property name drifts from `ttl-seconds` wording in spec to `ai-agent.task-file.ttl=PT1H`; acceptable if docs/tests lock the Duration style.

**Suggestions**
- Add/update `ai-agent-starter/src/main/resources/default-params.yaml` to `qwen/qwen3.6-35b-a3b`.
- Add a stable pending marker column, e.g. `INJECTED_AT` or `SENT_AT`; do not rely on `message_id IS NULL`.
- Either grant `DELETE` for own pending `AiTaskFile` rows or implement an ownership-checking removal service.
- If keeping `message_id`, ensure DB FK is `ON DELETE SET NULL`, not restrictive.

**Risk Assessment:** **MEDIUM-HIGH**. Entity and i18n shape is solid, but the model-seeding and FK/delete-policy issues will cause acceptance failures.

## 13-02 Plan

**Summary:** The resolver/repository/cleanup design is mostly coherent and correctly separates task files from RAG, but the `messageId IS NULL` predicate is brittle because projected messages are not stable. The package-level no-RAG invariant is good, but the verification contradicts itself.

**Strengths**
- Resolver uses regular `DataManager`, preserving user row-level security.
- Cleanup uses `UnconstrainedDataManager`, appropriate for trusted system cleanup.
- Blob-before-row deletion is correctly called out (`13-02-PLAN.md:175`).
- MIME allowlist is centralized in resolver.

**Concerns**
- **HIGH:** `loadPending` uses `e.message is null` (`13-02-PLAN.md:155`); this breaks after chat-memory projection deletes/reinserts messages.
- **MEDIUM:** Plan requires package-info to contain forbidden tokens (`13-02-PLAN.md:43`) but its grep gate expects zero matches across the package (`13-02-PLAN.md:335`).
- **MEDIUM:** `markSent` loads `AiMessage` by ID in REQUIRES_NEW; if message rows are regenerated later, the stamp is not durable.
- **LOW:** `readAllBytes()` on 100 MB files is accepted, but combined with 10 files can be expensive.

**Suggestions**
- Change resolver to `where e.injectedAt is null and e.expiresAt > :now`.
- Update `markSent` to `markInjected(taskFileIds, messageId, injectedAt)` and make `injectedAt` authoritative.
- Adjust grep gate to strip/allow package-info JavaDoc, as Plan 05 already intends.
- Consider max total pending bytes per conversation.

**Risk Assessment:** **MEDIUM-HIGH**. The no-RAG separation is good, but the pending-state model needs correction.

## 13-03 Plan

**Summary:** The plan preserves the Phase 11 architecture well: no tool-level transaction, proxy-crossed `MutationSaveExecutor.bulkSave`, one idempotency reservation, one audit row. However, it has mismatches with existing enum/replay infrastructure and with MUT-14 failure metadata.

**Strengths**
- Keeps `@Transactional` on `MutationSaveExecutor`, not `BuiltInMutationTools` (`13-03-PLAN.md:27`).
- Uses one reservation + one transaction + one audit per batch.
- Request hash preserves submission order through list order.
- Rich tool description is appropriate for Qwen tool reliability.

**Concerns**
- **HIGH:** `FAILED` outcome is assumed but absent.
- **HIGH:** Generic `MutationCommitCoordinator.handleReservationResult()` returns one `entityId` on replay (`MutationCommitCoordinator.java:191`), not bulk `savedIds`.
- **MEDIUM:** MUT-14 asks for `failedAttribute` when available; plan explicitly forbids it (`13-03-PLAN.md:430`).
- **MEDIUM:** Contract says never include `id: null`, but implementation treats `row.get("id") == null` as create (`13-03-PLAN.md:316`).
- **MEDIUM:** DoS risk for huge batches is accepted, but no max-row config is planned (`13-03-PLAN.md:479`).

**Suggestions**
- Either add `FAILED` to `AiToolCallOutcome` with Liquibase/messages/tests, or change all Phase 13 acceptance/tests to `ERROR`/`BLOCKED`.
- Add a bulk-specific replay handler that returns `{outcome, count, savedIds}` or store bulk result summary on `AiMutationIntent`.
- Reject `records[i].containsKey("id") && records[i].get("id") == null` as `validation_failed`.
- Include safe `failedAttribute` when it is a known metamodel attribute; otherwise null.
- Add `ai-agent.tools.mutation.bulk-max-rows` default, e.g. 100.

**Risk Assessment:** **HIGH**. The shape is right, but enum/replay/failure semantics will fail tests or underdeliver MUT-14.

## 13-04 Plan

**Summary:** This is the highest-risk plan. It correctly identifies the Phase 12 fragment as the only UI integration point and uses Spring AI media injection correctly, but the Upload API is hard-locked on a questionable XML attribute, MIME rejection is incomplete, and D-01 is undermined by the message FK model.

**Strengths**
- Fixes the rejected right-pane split by moving `attachmentsPanel` above `messageInputSlot` (`13-04-PLAN.md:63`).
- Uses Spring AI lambda form for media injection, consistent with current docs (`13-04-PLAN.md:460`).
- Hoists resolver inside `Flux.defer`, avoiding eager stream-thread blocking (`13-04-PLAN.md:492`).
- Marks sent on stream completion, not subscription.

**Concerns**
- **HIGH:** Context7 Jmix Upload docs emphasize `receiverFqn`; the plan hard-locks `receiverType="MULTI_FILE_MEMORY_BUFFER"` (`13-04-PLAN.md:65`). This may not compile in Jmix 2.8.
- **HIGH:** Project memory says use `UploadHandler.toFile`; plan uses `getReceiver()` despite acknowledging receiver deprecation (`13-04-PLAN.md:145`).
- **HIGH:** Upload handler does not actually reject unsupported MIME before persisting; resolver rejection later does not satisfy "upload `.exe` rejected".
- **HIGH:** `messageId` stamping inherits the D-01 projection problem.
- **MEDIUM:** Visible `attachButton` plus visible `<upload>` creates two affordances, conflicting with TASK-01's single Attach button.

**Suggestions**
- Reverify Upload with actual dependency/API before execution; prefer Jmix-supported `receiverFqn` or `UploadHandler.toFile` if available.
- Move upload persistence into a service: validate MIME + size, save blob, create row, and own delete behavior outside the view controller.
- Use a real server-side MIME allowlist in the upload success path.
- Hide the native upload button or make it the only Attach button; avoid duplicate controls.
- Replace `messageId IS NULL` flow with `injectedAt IS NULL`.

**Risk Assessment:** **HIGH**. UI integration and media injection are feasible, but API and lifecycle assumptions need correction first.

## 13-05 Plan

**Summary:** The verification intent is strong: static + runtime TEST-16, resolver behavior, cleanup, bulk success/failure/idempotency. The tests as written are likely to fail because they reference nonexistent fixture names/fields and encode some questionable acceptance assumptions from earlier plans.

**Strengths**
- TEST-16 dual enforcement is the right approach.
- Covers D-01 turn-1 vs turn-2 resolver behavior.
- Covers rollback-all and idempotency-order semantics.
- Roadmap update is scoped to the phase rewrite and plan list.

**Concerns**
- **HIGH:** Tests use `AiTestCustomer`, `email`, `fullName`, `phone` (`13-05-PLAN.md:295`); existing fixture is `mutationTest_MutationTestFixture` with `name`, `secret`, `priority` (`MutationTestFixture.java:32`).
- **HIGH:** Tests expect audit outcome `FAILED`, but enum lacks it.
- **MEDIUM:** Source scanner allows forbidden tokens only via stripping package-info; align this with Plan 02's grep gate.
- **MEDIUM:** Runtime VectorStore test should assert no ingestion/add, not no retrieval; chat still legitimately uses RAG retrieval.
- **LOW:** Output requires updating `STATE.md`, but files_modified omits it (`13-05-PLAN.md:438`).

**Suggestions**
- Rewrite mutation tests against `mutationTest_MutationTestFixture` and fields `name`, `secret`, `priority`.
- Add one regression test specifically for "old attached file is not re-injected after next chat-memory saveAll".
- Make outcome assertions match the chosen enum decision.
- Include `STATE.md` in files_modified or remove it from output.

**Risk Assessment:** **HIGH** until test fixtures and outcome semantics are fixed; **MEDIUM** after those corrections.

## Final Recommendation (Codex)

Do not execute as-is. First patch the plans around four decisions: stable injected-state marker instead of `messageId IS NULL`, complete default-model seed update, upload API re-verification/server-side MIME validation, and bulk audit/replay outcome semantics. After that, the wave ordering is sound and the phase should achieve TASK-01..06, ENT-07, MUT-14, and TEST-16.

---

## OpenCode Review (opencode 1.14.39, openai/gpt-5.5)

## 13-01 Plan Review

**Summary:** Strong foundation plan for `AiTaskFile`, Liquibase, config, roles, i18n, and model swap. It is detailed and mostly aligned with ENT-07, TASK-03, SEC-06 partial, and default-model criteria. Main risks are FK delete semantics, security policy completeness for deletes/updates done by UI later, and minor property/spec drift.

**Strengths**
- Correctly anchors `AiTaskFile` in `agentstore` with UUID, `@Version`, `@InstanceName`, `@PropertyDatatype("fileRef")`, no Lombok.
- Explicitly fixes known table-name drift: `AI_AGENT_CONVERSATION`, not `AI_CONVERSATION`.
- Locale parity is enforced against actual repo bundles: `messages_en.properties` + `messages_vi.properties`.
- Good dependency ordering: downstream resolver/UI plans depend on this.
- Default model swap is scoped and preserves embedding model.

**Concerns**
- **HIGH:** `@OnDelete(DeletePolicy.UNLINK)` on `message` needs Liquibase `ON DELETE SET NULL` semantics. The plan says FK `deleteCascade="false"`, which usually means restrict/no action, not set null. This can break message deletion or leave JPA/Liquibase mismatch.
- **MEDIUM:** `AiAgentUserRole` grants only READ + CREATE on `AiTaskFile`, but Plan 13-04 chip removal uses `dataManager.remove(row)`. Regular users will not be allowed to delete their own rows unless DELETE is granted or removal is delegated to a system-internal repository method.
- **MEDIUM:** `AiTaskFileProperties` uses `ttl`, while SPEC mentions `ttl-seconds` in places and later plan writes `ai-agent.task-file.ttl=PT1H`. That is acceptable if intentional, but docs/tests must consistently use `ttl`.
- **LOW:** `CREATED_DATE nullable=false` can be safe with Jmix auditing, but bootstrap tests should verify inserts work before audit listeners populate it.
- **LOW:** Messages include Wave 3 UI keys early, which is fine, but this plan's requirement list omits TASK-01/TASK-05 even though it creates UI message keys.

**Suggestions**
- Change Liquibase FK for `MESSAGE_ID` to explicit `onDelete="SET NULL"` or the Liquibase-supported equivalent.
- Decide now whether user-owned task files are user-deletable. If yes, add DELETE to `AiAgentUserRole`; if no, make chip removal call a trusted repository using `UnconstrainedDataManager` after validating ownership.
- Add a boot/roundtrip test in Wave 5 that creates `AiTaskFile` through `Metadata.create()` and verifies audit fields populate.
- Keep property naming consistent in ROADMAP, SPEC, properties, and tests: prefer `ai-agent.task-file.ttl=PT1H`.

**Risk Assessment:** **MEDIUM**. The entity/config work is straightforward, but delete semantics and user deletion permissions can break later UI behavior.

## 13-02 Plan Review

**Summary:** Good separation of resolver/repository/cleanup responsibilities and strong adherence to D-01 single-turn pending-file semantics. However, some verification and transaction details are internally inconsistent, and `markSent` behavior may stamp files even when chat persistence fails depending on Plan 13-04 timing.

**Strengths**
- Correctly resolves only `message IS NULL` and `expiresAt > now`, matching D-01.
- Uses regular `DataManager` for user-scoped resolver reads and `UnconstrainedDataManager` for system-internal cleanup/stamping.
- Blob-before-row cleanup ordering is well thought through.
- The package-level TEST-16 invariant is useful and concrete.
- Good reuse of Phase 11 `agentstoreTransactionManager` pattern.

**Concerns**
- **HIGH:** The plan's grep verification for forbidden tokens across `taskfile/` will fail because `package-info.java` intentionally contains `IngesterManager`, `VectorStore`, etc. Wave 5 scanner strips the JavaDoc, but Plan 13-02 grep does not.
- **HIGH:** `markSent` in `REQUIRES_NEW` is justified, but if Plan 13-04 calls it after a failed or partially failed chat-memory write, it can permanently stamp files as sent without a valid `AiMessage`. The timing contract must be verified against actual `DefaultChatServiceImpl` persistence flow.
- **MEDIUM:** `deleteAllExpired` says one corrupt row should not roll back the entire batch, but wraps the whole iteration in one `REQUIRES_NEW` transaction. Because `deleteRow` catches blob failures, this may be fine, but any `unconstrainedDataManager.remove(row)` failure can still roll back prior deletions.
- **MEDIUM:** `FileStorageLocator.getByName(ref.getStorageName())` may fail if `storageName` is null or default storage refs encode differently. The CRM pattern should be verified.
- **LOW:** Resolver does synchronous `readAllBytes()` up to 100 MB. Accepted in threat model, but still risky for concurrent streaming/chat load.

**Suggestions**
- Adjust grep verification to exclude `package-info.java` or strip the invariant JavaDoc the same way the test does.
- Consider `deleteAllExpired` per-row `REQUIRES_NEW` or catch row deletion failures so one DB remove issue does not roll back successful blob removals.
- Add explicit tests for `markSent` no-op/missing message behavior.
- Put a clear warning in `AiTaskFileMediaResolver` that files are read into memory and bounded by upload cap.

**Risk Assessment:** **MEDIUM**. Core design is solid, but static verification inconsistency and transactional edge cases need cleanup.

## 13-03 Plan Review

**Summary:** The plan captures the headline `bulk_save_records` capability and correctly emphasizes one transaction, one intent, one audit, default-off mutation gate, and rich tool description. The biggest risk is that the proposed implementation does not fully preserve Phase 11 mutation semantics as specified, especially around per-row CRUD gating, idempotent replay result fidelity, and audit/error shaping.

**Strengths**
- Correctly keeps `@Transactional` on `MutationSaveExecutor.bulkSave`, not the tool method.
- Good D-02 choice: one mixed create/update tool with id-presence dispatch.
- Strong PII safety intent for `argumentsJson`.
- Rich tool description is appropriately detailed for LLM tool reliability.
- Prompt rule addition is scoped and additive.

**Concerns**
- **HIGH:** Requirement MUT-14 says `AccessManager.applyRegisteredConstraints(CrudEntityContext)` per row. The plan does entity-level `enforceCreatePermission`/`enforceUpdatePermission` once per batch. That may miss row/context-specific constraints and deviates from the locked chain.
- **HIGH:** Requirement says explicit `id: null` is invalid: "never include `id: null`". The plan treats `row.get("id") == null` as create, which makes explicit null indistinguishable from omitted id. This should be rejected.
- **HIGH:** Replay with same idempotency key must return `IDEMPOTENT_REPLAY` and original result. If `AiMutationIntent` only stores first result entity id, Phase 11 `handleReservationResult` may not be able to replay all `savedIds` for a bulk batch.
- **HIGH:** `DiffSerializer.serializeBulkFailureSummary` is described as `resultSummary`, but Plan 13-03 later says audit `argumentsJson` or `resultSummary` inconsistently. Acceptance wants audit outcome FAILED with `failedRowIndex`; shape must be fixed.
- **MEDIUM:** `records.stream().anyMatch(r -> r.get("id") == null)` will also classify rows with no id and rows with id:null together. Need `containsKey`.
- **MEDIUM:** Per-attribute access is checked as a union once. This is probably sufficient for attribute policy, but the requirement says per-row per-attribute. If an attribute policy depends on row state, union-only is insufficient.
- **MEDIUM:** `mutationGuard.check` before `applyAttributes` may not let guards inspect the actual post-bind entity state. Phase 11 behavior should be mirrored exactly.
- **MEDIUM:** `failedAttribute` is required in MUT-14 "when available"; plan explicitly says do not add `failedAttribute`.
- **LOW:** Plan requirements list includes TASK-04 and SEC-06, but this plan really implements MUT-14. This traceability drift can confuse phase accounting.
- **LOW:** `sampleHashes` implementation via "same canonicalMapper" may require exposing internals from `MutationRequestHasher`; avoid broad refactors.

**Suggestions**
- Implement explicit id validation: `if (row.containsKey("id") && row.get("id") == null) return validation_failed`.
- Run CRUD context per row, even if the entity class is the same, to satisfy MUT-14 literally.
- Extend idempotency storage or replay response handling for batch saved IDs, not just first ID.
- Define audit shape once: `argumentsJson={count, entityName, sampleHashes, idempotencyKey}` and `resultSummary={failedRowIndex, failedAttribute?, errorCode, operation}` for failures.
- Add a max batch size property now or at least a hardcoded conservative cap; 10,000-row tool calls are a realistic DoS vector.
- Update requirement traceability to `MUT-14`.

**Risk Assessment:** **HIGH**. The tool is central and security-sensitive; the plan is close but needs semantic tightening to preserve Phase 11 guarantees.

## 13-04 Plan Review

**Summary:** This plan addresses the UI and chat-service integration but has the most drift from locked project conventions. It correctly implements D-01 single-turn injection conceptually, but the upload approach contradicts the project memory and earlier context requiring `UploadHandler.toFile`, and the `messageId` stamping strategy is fragile.

**Strengths**
- Correctly fixes the rejected split-pane layout and places attachments above `MessageInput`.
- Applies the shared `ChatPanelFragment` contract, so both Phase 12 surfaces benefit.
- Uses lambda `.user(u -> u.media(...))`, avoiding the Spring AI media-dropping pitfall.
- Streaming path uses `doOnComplete`, preserving "cancelled stream does not stamp".
- Explicitly orders query by `createdDate`, not nonexistent `createdAt`.

**Concerns**
- **HIGH:** Upload API contradicts locked context and memory. Phase context says use `UploadHandler.toFile` and avoid deprecated receiver APIs. Plan 13-04 uses XML `receiverType="MULTI_FILE_MEMORY_BUFFER"` and `event.getUpload().getReceiver()`, which the project memory says Vaadin 24.8 marks for removal. This is a direct convention violation unless current Jmix 2.8 docs prove there is no `UploadHandler.toFile` multi-file path.
- **HIGH:** `resolveLatestUserMessageId(convId)` selects the latest USER message after `.call()`. In concurrent tabs or rapid sends within the same conversation, this can stamp files to the wrong user message. The safer design is to have the chat persistence path return the user message id or persist the user `AiMessage` explicitly before invoking the model.
- **HIGH:** Plan 13-01 grants only READ+CREATE, but `handleChipRemove` uses `dataManager.remove(row)`. This will fail for normal users unless DELETE is granted or removal is done by trusted service after ownership check.
- **MEDIUM:** The plan says "SucceededEvent handler ALSO re-checks MIME before persistence" in threat model, but action text does not implement server-side MIME allowlist before saving. Resolver rejects later, but upload acceptance criteria require unsupported MIME rejected at upload.
- **MEDIUM:** `MultiFileMemoryBuffer` with `maxFiles=10` and 100 MB allows ~1 GB heap pressure. Threat model accepts this, but for a chat add-on this is risky.
- **MEDIUM:** Keeping both `attachButton` and the visible Upload component may produce duplicate/confusing UI affordances, violating TASK-05 clarity.
- **MEDIUM:** Opportunistic cleanup calls `deleteAllExpired(now)` globally, not current-conversation only. This is okay but can make every chat send perform global cleanup work.
- **LOW:** `onUploadFailed` concatenates provider exception text into a notification. This may expose technical detail; use localized generic message.
- **LOW:** `FileRejectedEvent` always maps to "too large", but rejection may be MIME/type/count related.

**Suggestions**
- Reconcile upload API before execution. Prefer the project-locked `UploadHandler.toFile(...)`; only use receiverType if verified Jmix 2.8 has no supported handler-based multi-file option, and document the deviation.
- Avoid latest-message SELECT-back. Change the chat service persistence seam to expose the newly persisted USER `AiMessage.id`, or persist the user message before model invocation in a controlled transaction.
- Add server-side MIME + size validation in `handleSucceededFile` before `FileStorage.saveStream`.
- Move chip removal into `AiTaskFileRepository.removeOwnPending(rowId, username)` or grant DELETE explicitly.
- Use temp-file upload buffer if available to avoid heap pressure.
- Keep a single visible upload affordance; style the Jmix upload button with the desired "Attach" label rather than adding a second button.

**Risk Assessment:** **HIGH**. D-01 is conceptually right, but upload API drift and message stamping fragility are significant implementation risks.

## 13-05 Plan Review

**Summary:** The verification plan is ambitious and covers the right invariants: TEST-16, D-01, cleanup, bulk success/failure/idempotency, and roadmap update. However, some tests are likely brittle or misaligned with earlier implementation details, and the plan omits full build/regression coverage for Phase 9/11/12 sensitive areas.

**Strengths**
- TEST-16 is enforced both statically and at runtime.
- Bulk tests cover happy path, mixed create/update, rollback-all, replay, changed-bytes, and changed-order.
- Resolver tests directly validate single-turn semantics.
- Cleanup tests validate blob deletion and non-expired retention.
- Roadmap update keeps phase-title rewrite and STT split visible.

**Concerns**
- **HIGH:** Static scanner test strips JavaDoc starting at `DO NOT REFERENCE`, but Plan 13-02's package-info includes forbidden tokens in multiple bullet lines. The scanner logic may strip too much or too little; Plan 13-02 grep definitely conflicts.
- **HIGH:** Runtime TEST-16 with `@MockitoBean VectorStore` may break application boot if the real RAG advisor requires a concrete `VectorStore` bean behavior. Use existing `StubVectorStoreConfiguration` carefully, or spy the stub rather than replace with a pure mock.
- **HIGH:** Bulk tests assume fixture entity names/fields like `AiTestCustomer`, `email`, `fullName`, `phone`. This must match actual Phase 11 fixture classes. If not, tests become plan fiction.
- **MEDIUM:** Testing private `resolveLatestUserMessageId` via reflection is brittle. The plan allows indirect testing, which is better, but should mandate indirect behavior instead.
- **MEDIUM:** Partial failure via `MutationGuard` may not exercise `AccessManager`/attribute-denial path. Need at least one real access-denied test to preserve MUT-14 security semantics.
- **MEDIUM:** "Upload appears in both surfaces" is not actually tested here despite TASK-01/TASK-05/TASK-09 style acceptance. There is no `@UiTest` for `ChatView` + `ChatDialogView`.
- **MEDIUM:** Roadmap status update says `0/5 In progress` while this plan's output says update STATE phase row. Execution completion should move to complete; planning-time update should not happen from a test plan unless this plan is actually executed.
- **LOW:** `VectorStore count unchanged` acceptance is only indirectly covered by no interactions. If a stub has state, count-before/count-after would be closer to SPEC.
- **LOW:** No explicit test for unsupported MIME upload rejection, >100 MB rejection, or localized error keys.

**Suggestions**
- Make source scanner ignore only `package-info.java` or only comment ranges robustly; align Plan 13-02 verification with the same rule.
- Prefer integration tests through public chat service behavior over reflection.
- Add a focused mutation test where AccessManager denies an attribute, not only MutationGuard veto.
- Add at least one `@UiTest` or XML descriptor test confirming `attachmentsPanel`/`upload` exists in shared fragment and mounts in both surfaces.
- Run broader regression: `./gradlew :ai-agent:ai-agent:test` after targeted tests, because Phase 13 touches prompt rules, mutation tools, chat service, security roles, and Liquibase.
- Add tests for `id: null` rejection once Plan 13-03 is fixed.

**Risk Assessment:** **MEDIUM**. Verification intent is strong, but brittleness and missing UI/access-denied coverage could let important regressions slip.

## Cross-Plan Assessment (OpenCode)

**Overall:** The phase is well decomposed into sensible waves, and the five plans cover the major deliverables: ENT-07, TASK-01..06, MUT-14, TEST-16, and model swap. The most important issues to fix before execution are `bulk_save_records` semantics, upload API alignment, and `messageId` stamping reliability.

**Key Cross-Cutting Concerns**
- **HIGH:** UploadHandler vs `receiverType/MultiFileMemoryBuffer` conflict. The project's locked memory says use `UploadHandler.toFile`; Plan 13-04 pivots to receiver APIs. Resolve before coding.
- **HIGH:** `bulk_save_records` does not yet fully satisfy MUT-14's per-row AccessManager chain and idempotent replay contract.
- **HIGH:** Selecting latest USER message by query is race-prone. D-03 requires stamping "the just-persisted user message"; the plan approximates that but does not guarantee it.
- **MEDIUM:** User deletion permission mismatch spans Plan 13-01 and Plan 13-04.
- **MEDIUM:** Static forbidden-token checks are inconsistent between Plan 13-02 and Plan 13-05.
- **MEDIUM:** Directory name remains `13-chat-task-input-stt-task-scoped-file`; plans consistently use it, so artifact drift is mostly controlled. ROADMAP/STATE updates must keep title rewritten and STT Phase 15 separate.
- **LOW:** Locale parity is handled well in Plan 13-01, but later upload error branches must use only those keys or add new keys in both locales.

**Recommended Pre-Execution Fix List (OpenCode)**
- Fix Liquibase `MESSAGE_ID` FK to `ON DELETE SET NULL`.
- Decide `AiTaskFile` delete policy for user chip removal.
- Replace or explicitly justify `receiverType="MULTI_FILE_MEMORY_BUFFER"` against `UploadHandler.toFile`.
- Redesign `markSent` to use the actual persisted USER message id, not latest-message lookup.
- Tighten `bulk_save_records`: reject explicit `id:null`, run per-row CRUD context, support true batch replay result, define failure audit shape.
- Align TEST-16 static scanner and plan grep gates.
- Add UI and access-denied tests in Wave 4.

---

## Consensus Summary

### Agreed Strengths (raised by both reviewers)
- Wave decomposition (1 → 2 → 3 → 4) is logical; each wave's dependency on the prior is explicit.
- `AiTaskFile` entity follows Jmix conventions (`@JmixEntity`, UUID, `@Version`, `@InstanceName`, `@PropertyDatatype("fileRef")`, no Lombok).
- Locale parity is enforced against the actual `messages_en.properties` + `messages_vi.properties` bundles (no spurious `messages.properties`).
- D-01 single-turn injection intent is correctly identified and implemented at the resolver layer.
- TEST-16 dual enforcement (static + runtime) is the right approach for the IngesterManager / VectorStore non-touch invariant.
- Cleanup job uses `UnconstrainedDataManager` (matches project memory `feedback_jmix_unconstrained_for_system_writes`).

### Agreed HIGH Concerns (raised by both reviewers — must be addressed)

1. **`messageId IS NULL` pending-marker is brittle** (Plans 02, 04 — D-01 implementation). `ProjectingChatMemoryRepository.saveAll(...)` deletes and re-inserts projected `AiMessage` rows on every turn (codex cited `ProjectingChatMemoryRepository.java:82`). After the next chat turn, previously-stamped `messageId`s are gone or re-pointed; old "consumed" task files re-appear as "pending" and re-inject. **Fix:** add a stable `injectedAt` (or `consumed`) column; resolver predicate becomes `injectedAt IS NULL AND expiresAt > :now`. Keep `messageId` only for optional UI display linkage.

2. **Liquibase `MESSAGE_ID` FK delete semantics wrong** (Plan 01). `deleteCascade="false"` is restrict/no-action, NOT `ON DELETE SET NULL`. With JPA `@OnDelete(DeletePolicy.UNLINK)` on the `message` field, the schema and JPA disagree → `AiMessage` deletion will fail at the DB. **Fix:** Use Liquibase `<column constraints foreignKeyName="..." references="..." onDelete="SET NULL"/>` (or equivalent) on `MESSAGE_ID`.

3. **User cannot delete own `AiTaskFile`** (Plan 01 vs Plan 04 contradiction). Plan 01 grants `READ + CREATE` on `AiTaskFile` for `AiAgentUserRole`. Plan 04 calls `dataManager.remove(row)` from chip-removal — secured `DataManager` will refuse without DELETE policy. **Fix:** either grant `DELETE` for own pending rows on `AiAgentUserRole`, OR route chip removal through `AiTaskFileRepository.removeOwnPending(rowId, username)` using `UnconstrainedDataManager` after explicit ownership check.

4. **Upload XML attribute conflicts with project memory** (Plan 04). Plan 04 hard-locks `<upload receiverType="MULTI_FILE_MEMORY_BUFFER" .../>`. Project memory `feedback_jmix_upload_receiver_deprecated` says use `UploadHandler.toFile`; CONTEXT.md D-04 explicitly tells the planner to verify the exact jmix-flowui 2.8 API. Codex reports Context7 Jmix Upload docs emphasize `receiverFqn`, NOT `receiverType`. **Fix:** verify the API against actual `io.jmix.flowui.kit.component.upload` in jmix-flowui 2.8 and either confirm `receiverType` exists (with citation) or pivot to `UploadHandler.toFile` per memory.

5. **Server-side MIME validation happens AFTER persistence** (Plan 04). Upload handler persists the row, and only the resolver later filters MIME. A user uploading `.exe` lands a row + `FileStorage` blob; resolver just ignores it — TASK-03 acceptance ("upload `.exe` rejected") fails. **Fix:** validate MIME (and size) inside `handleSucceededFile` BEFORE `FileStorage.saveStream(...)` and `metadata.create(AiTaskFile)`.

6. **`AiToolCallOutcome.FAILED` referenced in plans does not exist** (Plans 03, 05). Codex verified the enum: `SUCCESS`, `BLOCKED`, `ERROR`, `FLAGGED`, `IDEMPOTENT_REPLAY`, `COMMIT_FAILED` (`AiToolCallOutcome.java:7`). Plan 03 success criterion + Plan 05 audit assertion both write `outcome=FAILED`. **Fix:** either ADD `FAILED` to the enum (Liquibase enum/string column update + messages + tests) OR change all Phase 13 references to `ERROR` / `BLOCKED` (decision must be explicit in REQUIREMENTS / SPEC).

7. **Test fixture `AiTestCustomer` does not exist** (Plan 05). Plan 05 builds `bulk_save_records` tests against `AiTestCustomer` with `email`, `fullName`, `phone`. Real Phase 11 fixture is `mutationTest_MutationTestFixture` with `name`, `secret`, `priority` (codex cited `MutationTestFixture.java:32`). Tests will not compile. **Fix:** rewrite test bodies against `mutationTest_MutationTestFixture` fields, OR create a new `AiTestCustomer` entity + Liquibase + role + messages (much larger scope; not currently planned).

8. **Static forbidden-token scanner contradicts Plan 02 grep gate** (Plans 02, 05). Plan 02's `package-info.java` text contains the words `IngesterManager` and `VectorStore` (in TASK-02 invariant docstring). Plan 05's TEST-16 static scanner asserts these tokens never appear in any source file. Plan 02's own grep gate also asserts zero matches across the package. The two plans cannot both be true as written. **Fix:** scanner must skip `package-info.java` (or all comment ranges robustly) AND Plan 02 grep gate must mirror the same exclusion.

### Single-Reviewer HIGH Concerns (still unresolved)

9. **Default-model swap incomplete** (Codex only — Plan 01). `default-params.yaml` (`ai-agent-starter/src/main/resources/default-params.yaml`) seeds `AiParameters` with `openai/gpt-4o-mini` and is loaded on clean boot, OVERRIDING the `application.properties` swap. REQ-1 acceptance fails. **Fix:** update `default-params.yaml` to `qwen/qwen3.6-35b-a3b` in Plan 01 alongside the application.properties change.

10. **`@MockitoBean VectorStore` breaks RAG advisor boot** (Opencode only — Plan 05). Replacing the real bean with a pure mock can break Spring AI `VectorStoreChatMemoryAdvisor` initialization. **Fix:** use existing `StubVectorStoreConfiguration` and spy/observe its calls instead of replacing the bean.

11. **`bulk_save_records` does not satisfy MUT-14 replay-result contract** (Codex — Plan 03). `IDEMPOTENT_REPLAY` returns the original audit row but does not surface the original `savedIds`. Plan should either return `{outcome, count, savedIds}` payload or store bulk result summary on `AiMutationIntent`.

12. **`bulk_save_records` accepts `id: null` as create instead of rejecting** (Codex — Plan 03). CONTEXT.md D-02 FORMATS section says "never include `id: null`"; current dispatch is `id == null → create`. Without rejection, malformed LLM output silently creates rows. **Fix:** treat `records[i].containsKey("id") && records[i].get("id") == null` as `validation_failed`.

13. **Per-row `AccessManager.applyRegisteredConstraints(CrudEntityContext)` not run** (OpenCode — Plan 03). MUT-14 specifies per-row CRUD context, but Plan 03 does entity-level checks once per batch. Row/context-specific constraints (e.g. row-level policies that depend on row state) are missed.

14. **`markSent` / `resolveLatestUserMessageId(convId)` race** (OpenCode HIGH, Codex MEDIUM — Plan 04). Picking "latest USER message" by query after `.call()` can stamp files to the wrong message under concurrent tabs / rapid sends. **Fix:** thread the just-persisted USER `AiMessage.id` through the chat service seam, or persist the user message in a controlled transaction before invoking the model.

### Divergent / Lower-priority Views
- **OpenCode** raised "no UI test for `ChatView` + `ChatDialogView` upload affordance" as MEDIUM (TASK-01/05 acceptance gap); Codex did not flag.
- **Codex** added an `ai-agent.tools.mutation.bulk-max-rows` DoS guard suggestion (MEDIUM); OpenCode same suggestion (MEDIUM).
- Both agree directory-name vs phase-title rewrite is controlled (artifacts use the directory name consistently); ROADMAP/STATE updates carry the rewritten title — no drift risk identified.

### Recommended Pre-Execution Fix List (consensus — 14 items)

1. Add `injectedAt` column on `AiTaskFile`; switch resolver predicate to `injectedAt IS NULL`.
2. Liquibase `MESSAGE_ID` FK → `ON DELETE SET NULL`.
3. Resolve user delete-permission contradiction (grant DELETE or use repository service).
4. Verify Jmix 2.8 Upload API; pick `UploadHandler.toFile` or `receiverFqn` per actual class.
5. Move MIME + size validation BEFORE blob persistence.
6. Decide `AiToolCallOutcome.FAILED` strategy (add enum value vs use `ERROR`/`BLOCKED`).
7. Rewrite Plan 05 tests against `mutationTest_MutationTestFixture` fields.
8. Reconcile Plan 02 + Plan 05 forbidden-token scanner / grep gate.
9. Update `default-params.yaml` to `qwen/qwen3.6-35b-a3b`.
10. Replace `@MockitoBean VectorStore` with `StubVectorStoreConfiguration` spy.
11. Bulk replay returns `savedIds` (or store summary on `AiMutationIntent`).
12. Reject explicit `id:null` rows as `validation_failed`.
13. Run per-row `AccessManager.applyRegisteredConstraints(CrudEntityContext)` instead of entity-level once per batch.
14. Replace `resolveLatestUserMessageId(convId)` with explicit just-persisted USER `AiMessage.id` plumbing.
