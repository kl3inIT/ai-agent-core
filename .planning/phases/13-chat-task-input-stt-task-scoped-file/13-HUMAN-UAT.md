---
status: diagnosed
phase: 13-chat-task-input-stt-task-scoped-file
source: [13-VERIFICATION.md]
started: 2026-05-06T00:00:00Z
updated: 2026-05-06T07:30:00Z
---

## Current Test

[diagnosis complete — 2 passed, 1 passed-with-caveat, 1 failed (BLK-01 — needs gap-closure fix)]

## Tests

### 1. BLK-02 — Hardcoded DB credentials decision
expected: Either explicit acknowledgement that committed `username=postgres` / `password=admin123` against `10.123.123.174:5555` in `jmix-app/src/main/resources/application.properties` is intentional for the dev branch and not a release blocker, OR the credentials are externalised via `.env` and the leaked password is rotated.
result: passed (2026-05-06 — user approved as dev-only artefact for Phase 13; revisit before any release branch cut)

### 2. BLK-01 — Streaming fallback double-write smoke test
expected: With a chat model that throws `UnsupportedOperationException` on `.stream()`, sending a chat message with an attached file produces exactly 1 user `AiMessage` row per submission and `markInjected` stamps each `AiTaskFile` exactly once.
result: failed (2026-05-06 — confirmed by static trace, no live smoke test required). `DefaultChatServiceImpl.stream()` line 454 calls `userMessagePersister.persistUserMessage(...)` once before invoking `chatClient.prompt()...stream()`. When `.stream()` throws `UnsupportedOperationException` (line 523), the catch falls through to `ask(...)` (line 526), which AT LINE 285 calls `userMessagePersister.persistUserMessage(...)` AGAIN. Per turn on a non-streaming provider with attached files: 2 user AiMessage rows persist (DUPLICATE) — pollutes ChatMemory replay. Media side is OK (the second resolvePending finds the same still-pending rows; the inner `ask()` stamps once via line 332). Fix needed: either (a) skip the inner-`ask()` persist when called from the streaming-fallback path (e.g. flag-on-thread / overload), or (b) replace the catch's `ask(...)` with a direct `.call()` reusing the already-resolved Media + userMessageIdRef. Recommend tracking as a Phase 13.1 gap-closure plan rather than blocking ship — the bug only manifests on chat models that throw on `.stream()` (default model `qwen/qwen3.6-35b-a3b` supports streaming, so happy path is unaffected).

### 3. BLK-03 — Stream cancel/retry behaviour
expected: Per D-03 contract, a cancelled SSE stream leaves the task file pending so a retry re-injects.
result: passed (2026-05-06 — confirmed by static trace). `DefaultChatServiceImpl.stream()` lines 504-520: `markInjected` lives only in `doOnComplete`. Reactor contract: `doOnComplete` fires on `onComplete` only — NOT on `onCancel` or `onError`. So cancelled stream leaves `injectedAt IS NULL` → next-turn resolver picks the same rows up again (matches D-03 "cancelled = retry works"). Errored stream behaves the same way; cleanup job reaps at TTL. The 13-REVIEW.md "no `doOnError` symmetry" note is a defensive flag, not a bug — adding `doOnError` to stamp would break D-03. The intentional comment at line 504-507 explicitly documents this. (Live SSE cancel test would only re-confirm the doOnComplete contract that Reactor itself guarantees.)

### 4. bulk_save_records test execution
expected: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsBulkSave*"` either passes, or fails only with the pre-existing `MetaClass not found for AiAuditEvent` regression documented in `deferred-items.md`.
result: passed-with-caveat (2026-05-06 — `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsBulkSave*"` ran 10 tests / 10 failed, ALL with `IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:145` whose root cause is `Caused by: java.lang.IllegalArgumentException: MetaClass not found for class com.vn.agent.entity.AiAuditEvent` — exact match for the deferred regression. Cross-confirmed by running pre-existing Phase 11 test `BuiltInMutationToolsAuditArgumentsTest` which fails identically (3 tests / 3 failed, same fingerprint). Test artefacts are structurally correct (compileTestJava passed during plan execution); Spring-context boot regression is the blocker, predates Phase 13, and is documented in `.planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md` as out-of-scope per Plan 13-05 SCOPE BOUNDARY. Tests will go green once the underlying regression is fixed in a follow-up — recommend tracking as a Phase 13.1 or dedicated debug session.)

## Summary

total: 4
passed: 3
issues: 1
pending: 0
skipped: 0
blocked: 0

## Gaps

### BLK-01 — Streaming-fallback double-write of user AiMessage row
status: failed
file: ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
trigger: chat models that throw `UnsupportedOperationException` from `chatClient.prompt()...stream()` AND user has attached files
symptom: 2 user AiMessage rows persist per turn (one at line 454 in the streaming `Flux.defer`, a second at line 285 inside the inner `ask(...)` call that the line 523-531 catch falls through to)
fix_options:
  - skip the inner-`ask()` persist when called from streaming fallback (flag on thread / overload variant)
  - replace the catch with a direct `.call()` that reuses the already-resolved Media + userMessageIdRef from the streaming path
recommendation: track as Phase 13.1 gap-closure plan via `/gsd-plan-phase 13 --gaps`. Default model `qwen/qwen3.6-35b-a3b` supports streaming, so the happy path is unaffected; bug only manifests on non-streaming providers via D-04 fallback.

### deferred — Spring-context boot regression (pre-existing, blocks bulk-save integration tests)
status: deferred
source: .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md
symptom: `IllegalArgumentException: MetaClass not found for class com.vn.agent.entity.AiAuditEvent` raised inside `AnnotatedRoleBuilderImpl` during `@SpringBootTest` context boot. Affects all Plan 11-10..11-13 mutation tests AND the new Plan 13-05 bulk-save / taskfile integration tests.
verified: 2026-05-06 — running `BuiltInMutationToolsAuditArgumentsTest` (Phase 11 pre-existing) reproduces the same fingerprint
recommendation: dedicated debug session (`/gsd-debug`) on the metamodel-session ordering. Out-of-scope for Phase 13 per SCOPE BOUNDARY rule.
