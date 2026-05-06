---
phase: 13-chat-task-input-stt-task-scoped-file
reviewed: 2026-05-06T00:00:00Z
depth: standard
files_reviewed: 38
files_reviewed_list:
  - ai-agent/ai-agent-starter/src/main/resources/default-params.yaml
  - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/UserMessagePersister.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiTaskFile.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileCleanupJob.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileRepository.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/package-info.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiAgentMutationProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiMutationIntent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/DiffSerializer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationCommitCoordinator.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationRequestHasher.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationSaveExecutor.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/090-ai-task-file.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/091-ai-mutation-intent-result-summary.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesComposerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AskTypedRetryTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/AiTaskFileCleanupJobTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/AiTaskFileMediaResolverIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/AiTaskFileNoVectorStoreInvocationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/taskfile/TaskFileNoVectorStoreSourceScannerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsBulkSaveIdempotencyTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsBulkSavePartialFailureTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsBulkSaveTest.java
  - jmix-app/src/main/resources/application.properties
findings:
  blocker: 4
  warning: 11
  total: 15
status: issues_found
---

# Phase 13: Code Review Report

**Reviewed:** 2026-05-06
**Depth:** standard
**Files Reviewed:** 38 (37 source/resources + 1 host app properties)
**Status:** issues_found

## Summary

Reviewed the Phase 13 task-scoped chat file pipeline (`AiTaskFile` entity, repository, resolver, cleanup job), the chat-service plumbing that injects pending media on every turn, the bulk_save_records tool added in Phase 13 D-02, the `AiMutationIntent.RESULT_SUMMARY` migration, and the chat-panel upload UI. The orchestration is mostly disciplined: REQUIRES_NEW transactions on idempotency writes, SHA-256 hashing for sensitive audit fields, exposure-aware replay name resolution, and a guard catch-ladder that distinguishes `ToolVetoedException` / `AccessDeniedException` / `ToolUserError`.

The notable defects are concentrated in the streaming path, the upload UI, and configuration:
- the streaming fallback to `ask(...)` on `UnsupportedOperationException` re-runs the entire pending-media + persist-user-message flow, double-injecting and double-persisting (BLOCKER);
- on a successfully cancelled stream the `markInjected` doOnComplete still fires after a partial reply (WARNING — D-03 contract claims cancel = retry works, but `Reactor doOnComplete` fires only on completion, not cancel — the bigger risk is that doOnError does NOT mark, so a half-stream with content-already-rendered leaves the row pending);
- production-like database credentials checked into `jmix-app/src/main/resources/application.properties` (BLOCKER);
- no `AiTaskFile` policy on `AiAgentAdminRole` for `DELETE` even though `AiTaskFileRepository#deleteRow` runs unconstrained (info — by design but worth pinning);
- the chat-panel temp-dir leaks per fragment instance (WARNING).

## Blockers

### BLK-01: Streaming `UnsupportedOperationException` fallback double-persists user message and double-injects media

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:444-531`
**Issue:** In `stream(...)`, when the upstream provider does not support streaming, the code catches `UnsupportedOperationException` and calls `ask(userId, convId, message, effectiveOverrides)` as a graceful fallback (line 526). But the flux body has already executed:
- `taskFileMediaResolver.resolvePending(convId)` (line 449-450) — media list resolved.
- `userMessagePersister.persistUserMessage(convId, message, userId)` (line 454) — a user message row written.

The fallback `ask(...)` call THEN runs the same logic again: it re-resolves pending media (the rows are still pending since the streaming branch never reached `doOnComplete`), persists ANOTHER user message, and calls `markInjected`. Net effect: two `AiMessage` user rows for one user turn, and the `markInjected` from the fallback path stamps the rows with the second user message id, leaving the first orphaned. Additionally the `doOnComplete` markInjected attached to `content` is replaced (line 529-530) but the resolved Resolved record is still closed-over by the fallback flow lambdas — except `content` is now `Flux.just(...)` so `markInjected` doOnComplete never runs from this path; the `ask` call has its own `markInjected`.

The duplication path also breaks token budgeting (TokenBudgetGuard.accumulate runs twice for the same content) and rate-limit accounting if the inner ask trips the rate-limiter.

**Fix:** Either skip the resolver/persister in the streaming defer until *after* the streaming chatClient call has been requested (and only run the resolve/persist once you commit to the streaming path), or short-circuit the inner `ask(...)` by passing already-resolved media + an already-persisted userMessageId so the fallback does not duplicate work. Concretely:
```java
} catch (UnsupportedOperationException nonStreaming) {
    // Skip Plan-13 inject inside the inner ask: the outer Flux.defer already
    // resolved media + persisted user message. Mark injected explicitly here
    // because the inner ask will not see the pending rows.
    if (!resolvedMedia.isEmpty()) {
        try {
            taskFileRepository.markInjected(resolvedMedia.taskFileIds(),
                    userMessageIdRef.get(), java.time.OffsetDateTime.now());
        } catch (Exception stampEx) {
            log.warn("markInjected failed (non-stream fallback) conv={}", convId, stampEx);
        }
    }
    ChatResponseDto blocking = askWithoutTaskFileInject(userId, convId, message, effectiveOverrides);
    titlePublicationHandled.set(true);
    toolSink.tryEmitComplete();
    content = Flux.just(new StreamingEvent.Content(blocking.content() == null ? "" : blocking.content()));
}
```
This requires a private overload of `ask(...)` that does not run the task-file pipeline. Alternatively (simpler), guard the resolve/persist behind a check that the chatClient supports streaming, but Spring AI does not expose that capability up front — so the explicit-skip overload is the cleaner fix.

### BLK-02: Database superuser credentials checked into version control

**File:** `jmix-app/src/main/resources/application.properties:1-3,71-73`
**Issue:** Production-shaped Postgres connection strings with `username=postgres` (the database superuser) and a literal password `admin123` are committed for both the main and `agentstore` datasources:
```
main.datasource.url=jdbc:postgresql://10.123.123.174:5555/ai-agent
main.datasource.username=postgres
main.datasource.password=admin123
...
agentstore.datasource.url=jdbc:postgresql://10.123.123.174:5555/agentstore
agentstore.datasource.username=postgres
agentstore.datasource.password=admin123
```
The IP `10.123.123.174:5555` is an internal-net target that anyone with VPN access can reach. Even if the host is dev-only, this is the canonical "secrets in repo" anti-pattern and project memory `feedback_secrets_in_application_properties` (project guideline `.env` import was added at line 6 specifically to externalise these). The login defaults `ui.login.defaultUsername=admin` / `ui.login.defaultPassword=admin` (lines 18-19) are also explicit. The hardcoded `OPENROUTER_API_KEY` placeholder at line 62 is correctly externalised; the DB credentials should follow the same pattern.

**Fix:** Move the four DB credentials into `.env` (already imported at line 6) or into `${POSTGRES_USERNAME}` / `${POSTGRES_PASSWORD}` placeholders with no defaults, force-rotate the leaked password, and add a `.gitignore` rule for `application-local.properties`. Concretely:
```
main.datasource.username=${MAIN_DB_USERNAME}
main.datasource.password=${MAIN_DB_PASSWORD}
agentstore.datasource.username=${AGENTSTORE_DB_USERNAME}
agentstore.datasource.password=${AGENTSTORE_DB_PASSWORD}
```
After the change, audit `git log -p -- application.properties` to confirm what the rotation needs to cover.

### BLK-03: `markInjected` does not run on stream cancel — pending rows replay on next turn even when content was rendered

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:508-520`
**Issue:** The streaming path stamps `markInjected` from `doOnComplete` only:
```java
.doOnComplete(() -> {
    if (!resolvedMedia.isEmpty()) { taskFileRepository.markInjected(...); }
})
```
The plan comment at lines 504-507 claims "cancelled stream does NOT mark files as injected — D-03 two-phase semantics: cancelled = retry works." That is the intent for a clean cancel before any tokens, BUT:
1. The user pressed Stop AFTER the model started replying. Some content already rendered into the chat (`assistantContentSeen.set(true)`). The conversation turn for the user is effectively complete from their point of view, but the row stays `injectedAt IS NULL`.
2. The user sends a follow-up message. The resolver's predicate `injectedAt IS NULL AND expiresAt > :now` re-injects the SAME blob into the next turn, producing a confusing replay where the file is presented again with new prompt text.
3. The same is true for `doOnError`: if the stream errors after some content streamed, `markInjected` does NOT run; the next turn re-sends the blob.

**Fix:** Stamp `markInjected` whenever any assistant content was actually emitted, regardless of terminal signal. Use `doFinally` (or `doOnTerminate` with an explicit cancel guard) and gate on `assistantContentSeen.get()`:
```java
.doFinally(signalType -> {
    if (resolvedMedia.isEmpty()) return;
    // Only stamp if the model actually consumed the media (any content emitted).
    // Pure cancel-before-content keeps pending so the user retry re-injects.
    if (!assistantContentSeen.get()) return;
    try {
        taskFileRepository.markInjected(resolvedMedia.taskFileIds(),
                userMessageIdRef.get(), java.time.OffsetDateTime.now());
    } catch (Exception stampEx) {
        log.warn("markInjected failed (terminal signal={}) conv={}", signalType, convId, stampEx);
    }
})
```
This narrows the "retry works" contract to cancel-before-any-content, which matches D-03 intent and avoids the replay surprise. The blocking `ask(...)` path is unaffected because it has no cancel.

### BLK-04: Streaming `userMessagePersister` writes a user message even when the chatClient.prompt build throws

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:451-461,463-491`
**Issue:** The user message is persisted at line 454 inside `Flux.defer(...)` BEFORE the chatClient prompt is built. If anything between line 463 and `.stream()` throws synchronously (e.g. the options builder, the prompt builder, an advisor consumer), the `try/catch UnsupportedOperationException` at line 523 will rethrow as a non-streaming-related RuntimeException, the outer `.onErrorResume` at line 554 maps it to `chatView.error.generic`, and the orphan `AiMessage` row stays in the conversation with NO assistant reply. The `markInjected` does not run (good — pending stays pending). But the user sees their text displayed in the chat, then a generic error notification, and on retry the *new* user message persistence creates a SECOND copy of the same text in agentstore.

The blocking `ask(...)` has the same flaw at line 285 — but the persist is inside the same try block, and the outer catch returns a `denied` DTO, so the agent UI does not retry transparently. In streaming, the user can immediately retype and re-submit; the `chat-memory` projection then surfaces both copies.

**Fix:** Move `persistUserMessage` AFTER the chatClient prompt-build succeeds, OR roll back the user message in the catch block if the chatClient.prompt() chain throws. Safer change is the latter because it keeps the userMessageId available for `markInjected`:
```java
try {
    content = chatClient.prompt() ... .stream() ...;
} catch (UnsupportedOperationException nonStreaming) {
    ...
} catch (RuntimeException promptBuildFailure) {
    if (userMessageIdRef.get() != null) {
        try { unconstrainedDataManager.remove(/* load AiMessage by id */); } catch (...) { }
    }
    throw promptBuildFailure;
}
```
This is also defensible by adding a unit test mirroring `AiTaskFileMediaResolverIntegrationTest` that injects a chat-client mock throwing on `.options(...)`.

## Warnings

### WR-01: Streaming defer holds the user message persist on the calling thread

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java:444-461`
**Issue:** `Flux.defer(...)` runs at subscribe time, but the `subscribeOn(chatStreamingScheduler)` at line 544 only relocates the SUBSCRIPTION work to the streaming scheduler. Per the comment at line 446 ("Pitfall 8: hoisted INSIDE Flux.defer so readAllBytes does not run on the calling thread"), the resolver call is now on the scheduler — good — BUT `userMessagePersister.persistUserMessage(...)` opens a REQUIRES_NEW DB transaction, which now blocks a streaming-scheduler thread for the duration of the agentstore round-trip. Under burst load with multiple concurrent uploads + sends, the streaming pool can exhaust before the actual LLM-streaming work starts.
**Fix:** Move `persistUserMessage` to a bounded blocking scheduler (e.g. `Schedulers.boundedElastic()`) via `.subscribeOn` only for that call, or accept the blocking but document the scheduler sizing implication. The simpler cure is to increase the streaming-scheduler pool size to compensate, but a transactional write inside a Reactor pipeline is generally an anti-pattern.

### WR-02: `MutationIntentRepository.classifyExisting` ignores `expiresAt` when returning REPLAY

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java:196-216`
**Issue:** `classifyExisting` returns `ReservationResult.replay(existing)` for any COMMITTED row whose `requestHash` matches, with no check on `expiresAt`. Since the cleanup job (`deleteExpired`) runs hourly and only reaps `EXPIRES_AT < now AND STATUS_ in (COMMITTED, FAILED)`, there is up to a 1-hour window after TTL where a re-played idempotency key returns IDEMPOTENT_REPLAY for what should be a new, fresh operation. The contract says "after TTL the key is reusable for a new logical operation", but a same-shape replay can still trip after TTL until the cleanup tick.
**Fix:** Add an `expiresAt < now` short-circuit in `classifyExisting` that treats expired COMMITTED rows as if absent (delete-and-reinsert), or document the up-to-1h replay window as intentional. If the latter, reduce the cleanup cadence or add an opportunistic `delete-and-recreate` inside `reserveOrReplayInTransaction` when the existing row is expired.

### WR-03: `BuiltInMutationTools.bulkSaveRecords` per-row dispatch detection accepts the same metaClass for two non-equal `resolveCreatable`/`resolveUpdatable` calls

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java:894-900`
**Issue:** Mixed-create-and-update batches call `resolveCreatableEntityOrThrow` then `resolveUpdatableEntityOrThrow`. Both calls return a `MetaClass`; the second overwrites the first. If the resolvers can disagree on returned metaclass (e.g. side effects on internal caches, or future polymorphic variants), the per-attribute write check on line 915 runs only against the second-resolved metaclass. Today both resolvers go through the same `ToolEntityResolver` and resolve to the same `MetaClass`, so the bug is latent — but the two-call pattern is fragile.
**Fix:** Resolve once, then run the create-side and update-side gate on the same metaclass:
```java
metaClass = anyUpdate
        ? toolEntityResolver.resolveUpdatableEntityOrThrow(entityName)
        : toolEntityResolver.resolveCreatableEntityOrThrow(entityName);
if (anyCreate) toolEntityResolver.checkCreatable(metaClass); // new helper
```

### WR-04: `BuiltInMutationTools.bulkSaveRecords` reads `id` via `rowId.toString()` — silently coerces non-string ids

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java:953-956`
**Issue:** `final String idForError = rowId.toString();` accepts any object the LLM placed under `"id"`. If the LLM emitted a JSON number or boolean by mistake, `rowId.toString()` produces a non-UUID string that surfaces as `parameter_conversion_error` — but the audit row's `argumentsJson` stored in `serializeBulkArgumentsJson` only carries hashes, not the row content, so operators cannot debug what bad shape the LLM emitted.
**Fix:** Tighten the type check before calling `parseEntityId`: if `rowId` is not a `String`, raise a typed `parameter_conversion_error` immediately with a hint that `id` must be a JSON string. This also pins the contract documented in the @Tool description ("id: 36-char UUID string").

### WR-05: `AiTaskFileMediaResolver.resolveSupportedMimeType` accepts MIME-type-from-extension over a non-supported declared type

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java:142-154`
**Issue:** When the browser sends `application/octet-stream` (parsed but not in `SUPPORTED_MEDIA_TYPES`), the method falls through to the extension lookup. For `evil.exe.png` the lookup returns `image/png` and the resolver uploads the file as image to the LLM. The defence-in-depth is in the chat panel (`resolveAllowedContentType` server-side, line 811-832 of `ChatPanelFragment.java`), which DOES the same extension-fallback — so an `evil.exe.png` slips past both: client-side allowedFileTypes (extension match), server-side fragment (extension match), and resolver (extension match). Only file content sniffing would catch this; without that, the LLM sees the raw bytes labelled as PNG.

For a multimodal LLM this is largely a transport concern (the model treats the bytes as image, fails to render, replies "could not interpret"). It becomes a security concern if downstream tools or audit UI render the blob inline. Today nothing does, so it is a hardening WARNING, not a BLOCKER.
**Fix:** Add a magic-bytes check to `buildMedia(...)` (e.g. Apache Tika `detect()` over the first 8 bytes) that rejects when the detected MIME does not match the resolved type. Alternative: tighten the upload allowlist to require BOTH a declared MIME match AND a matching extension.

### WR-06: `ChatPanelFragment.handleChipRemove` removes the row before the blob — orphans the blob if blob delete fails

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:911-936`
**Issue:** The chip-remove handler removes the agentstore row first, then the blob. If `dataManager.remove(row)` succeeds but `storage.removeFile(ref)` throws, the comment says "cleanup-job will retry" — but the cleanup job iterates `loadExpired(now)` (rows where `expiresAt < now`), and the row is now gone. The blob is orphaned permanently.

This is the OPPOSITE of the blob-first ordering invariant that `AiTaskFileRepository.deleteRow` (line 174-187) carefully encodes. Two mutation paths to the same data with different ordering invariants is a maintenance hazard.
**Fix:** Mirror the repository's blob-first ordering in the chip-remove handler:
```java
private void handleChipRemove(UUID rowId, FileRef ref, Component chipBox) {
    AiTaskFile row = dataManager.load(AiTaskFile.class).id(rowId).optional().orElse(null);
    if (row == null) { chipStrip.remove(chipBox); chipById.remove(rowId); return; }
    // Blob first — same invariant as AiTaskFileRepository.deleteRow.
    if (ref != null) {
        try { fileStorageLocator.getByName(ref.getStorageName()).removeFile(ref); }
        catch (RuntimeException blobEx) {
            log.warn("Blob delete failed; leaving row in place for TTL reaper", blobEx);
            notifications.create(messages.getMessage("chatView.attachments.upload.failed"))
                    .withThemeVariant(NotificationVariant.LUMO_WARNING).show();
            return;
        }
    }
    dataManager.remove(row);
    chipStrip.remove(chipBox); chipById.remove(rowId);
    refreshAttachmentsVisibility();
}
```

### WR-07: `ChatPanelFragment.uploadTempDir` is per fragment instance, never deleted on detach

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:127,242-247,278-304`
**Issue:** `Files.createTempDirectory("ai-agent-task-file-upload-")` runs once per fragment in `initAttachmentsAndUpload`. The directory is per Vaadin session: every UI window/tab gets its own empty `ai-agent-task-file-upload-XXXX` directory under `java.io.tmpdir`. On Windows, default temp is `%LOCALAPPDATA%\Temp`. The `onDetach` handler at line 278-304 disposes the active stream but never deletes the temp directory. Per-file cleanup (`tryDeleteTemp` at line 840-849) handles staged files but the parent dir leaks.

Over a long-running app this fills the OS temp dir with empty directories.
**Fix:** Add `Files.deleteIfExists(uploadTempDir)` (best-effort) inside `onDetach` after the active-stream teardown. Wrap in try/catch so a non-empty dir does not throw. Better: use `Files.createTempDirectory(...)` only when the first upload arrives, and delete in onDetach using `Files.walkFileTree` to remove residual stagings.

### WR-08: `MutationIntentRepository.deleteExpired` retains COMMIT_UNKNOWN forever; no surfacing of in-flight expired rows

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java:333-351`
**Issue:** `deleteExpired` excludes `PENDING` and `COMMIT_UNKNOWN` rows by design (correct — they may still be in flight or undecided). `countExpiredInFlight` reports the count for monitoring (line 343-351), but no caller in the changed scope consumes that count. Without a cleanup-job-side log/metric, COMMIT_UNKNOWN rows accumulate indefinitely and there is no operator surface to investigate them.
**Fix:** Wire `countExpiredInFlight` into the hourly cleanup-job log line so operators see "removed=N expiredInFlight=M" each tick. If M > 0 grows monotonically, the operator knows a manual reconciliation is needed.

### WR-09: `DiffSerializer.serializeBulkResultSummary` may exceed the 4000-char column for batches near the 100-row cap

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/DiffSerializer.java:189-200` and `091-ai-mutation-intent-result-summary.xml:20`
**Issue:** `RESULT_SUMMARY` is `varchar(4000)`. A bulk_save_records replay with 100 saved UUIDs serializes to roughly `{"count":100,"savedIds":[<36-char>",...100x]}` ≈ 100 × 39 (`"<uuid>",`) + envelope ≈ 3930 bytes. Whitespace, Jackson formatting, or an LLM that trips the 100-row cap exactly will tip past 4000 and the JDBC update fails with a column-truncation error in `markCommitted`. The current default `bulk-max-rows` is 100; if a host bumps this without resizing the column, all bulk replays in that range crash.

The repository ignores the truncation possibility — `markCommitted` runs the update straight, and a truncation would surface as DataAccessException, which is then logged by `MutationIntentRepository.markCommitted` only as `failureProbe` callback — actually NO, the truncation throws a DataIntegrityViolationException out of the update, the surrounding @Transactional rolls back, the host save row is committed (separate tx, already returned), the LLM gets COMMIT_UNKNOWN, and the audit reflects COMMIT_FAILED. So data is not lost but the LLM's idempotent-replay path is degraded.
**Fix:** Either truncate the saved-id list in `serializeBulkResultSummary` to the first 50 ids and add a `truncated:true` marker, or widen the column to `varchar(8000)` / `text` in a follow-up changelog. Prefer the truncation since the LLM doesn't need all 100 ids on replay — typically it only needs to see the first few to confirm shape parity.

### WR-10: `AgentSystemPromptRules.MUTATION_PROMPT_RULES` claims `bulk_save_records` exists but the chat panel cannot show >3 sample rows; LLM may still call without preflight echo

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java:116-120`
**Issue:** The mutation rule paragraph instructs the LLM to "always echo the row count and first 3 sample rows back to the user before invoking bulk_save_records". Nothing in the system prompt enforces this — it is an advisory. There is no scanner advisor pattern that detects "bulk_save_records called without prior assistant echo". The guidance therefore relies entirely on LLM compliance. For an enterprise-grade tool that mass-mutates entity rows (default cap 100), a soft-only advisory is below the contract grade of the rest of the mutation surface (which uses Jmix CRUD checks, marker-role gates, etc.).
**Fix:** Optional follow-up: either downgrade the guidance to "should" and accept the gap, or add a `MutationGuard` decorator that vetoes `bulk_save_records` if `RunContext` lacks an associated assistant message in the same turn that contains the row-count + sample echo. The latter is non-trivial; for now, document the gap in the `13-deferred-items.md` so it is not lost.

### WR-11: `UserMessagePersister.persistUserMessage` uses `@Qualifier("agentstoreTransactionManager")` but `Metadata.create` returns an unmanaged entity that may not be from agentstore

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/UserMessagePersister.java:74-89`
**Issue:** Inside the REQUIRES_NEW agentstore tx, the code calls `unconstrainedDataManager.load(AiConversation.class).id(conversationId).one()` and `unconstrainedDataManager.save(row)`. The `AiMessage` entity is annotated `@Store(name = "agentstore")` (verified at the symmetric `AiTaskFile` definition; AiMessage is not in scope but the contract is enforced), and the tx manager is correctly the agentstore one. However, the conversation load executes inside the agentstore tx — Jmix routes the load by entity store, so the load runs against agentstore as well. Good.

The latent issue: if `unconstrainedDataManager.save(row)` returns a different entity (Jmix returns the merged saved entity), the caller ID may differ from the in-memory entity's pre-save ID. Today `JmixGeneratedValue` on `AiMessage.id` populates the ID at create-time, not at save-time, so `saved.getId()` equals `row.getId()`. This invariant is implicit; if `AiMessage` ever switches to DB-generated id, the line `return saved.getId();` is the correct call but the comment "the just-assigned id" hints at confusion.
**Fix:** Add a clarifying comment and a defensive check that asserts the saved id is non-null (already done by the IllegalStateException at line 86-87, but only for the transaction return value). Pin this with a unit test that asserts `persistUserMessage` returns the same id as the entity created via `metadata.create` BEFORE the save call. This locks down the assumption against future entity changes.

## Info / Style

(Non-blocking observations)

- **`DefaultChatServiceImpl.usageTokens`** catches bare `RuntimeException` (line 667). Acceptable here because the metadata path is provider-specific and partial responses must not poison budget accumulation, but worth a comment that this is intentional.
- **`AgentSystemPromptRules.PROMPT_RULES`** literal English in Java is consistent with project memory `feedback_jmix_messages_over_spring` for tool-protocol strings — confirmed correct.
- **`AiTaskFileRepository.markInjected`** loads each row by id one at a time inside the loop (line 134-147). For a chat with 10 attached files this is 10 round-trips. Plan-13 D-02 caps batches differently; not a hot path. No fix needed but worth pinning if upload concurrency rises.
- **`MutationCommitCoordinator.replayResult`** line 207: `objectMapper.readValue(... Map.class)` produces an unchecked-warning that is `@SuppressWarnings`-acknowledged. Acceptable.
- **`ChatPanelFragment` + `chat-panel-fragment.xml`** use `acceptedFileTypes=` enumerating both MIME and extensions — defence-in-depth posture documented in REVIEWS HIGH-5 comment block. Good.
- **Liquibase changelog 090** correctly uses `addForeignKeyConstraint` separately for the MESSAGE_ID FK with `onDelete="SET NULL"` per REVIEWS HIGH-2 — agrees with `@OnDelete(DeletePolicy.UNLINK)` on the entity.

---

_Reviewed: 2026-05-06_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
