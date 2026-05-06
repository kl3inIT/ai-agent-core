---
status: partial
phase: 13-chat-task-input-stt-task-scoped-file
source: [13-VERIFICATION.md]
started: 2026-05-06T00:00:00Z
updated: 2026-05-06T00:00:00Z
---

## Current Test

[awaiting human testing]

## Tests

### 1. BLK-02 — Hardcoded DB credentials decision
expected: Either explicit acknowledgement that committed `username=postgres` / `password=admin123` against `10.123.123.174:5555` in `jmix-app/src/main/resources/application.properties` is intentional for the dev branch and not a release blocker, OR the credentials are externalised via `.env` and the leaked password is rotated.
result: [pending]

### 2. BLK-01 — Streaming fallback double-write smoke test
expected: With a chat model that throws `UnsupportedOperationException` on `.stream()`, sending a chat message with an attached file produces exactly 1 user `AiMessage` row per submission and `markInjected` stamps each `AiTaskFile` exactly once. (Currently the streaming `Flux.defer` AND the `ask()` fallback both resolve media + persist user message.)
result: [pending]

### 3. BLK-03 — Stream cancel/retry behaviour
expected: Per D-03 contract, a cancelled SSE stream leaves the task file pending so a retry re-injects. Verify by starting a streaming chat reply with media injected, cancelling the SSE mid-render, then starting a second turn — confirm pending state is preserved correctly.
result: [pending]

### 4. bulk_save_records test execution
expected: `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsBulkSave*"` either passes, or fails only with the pre-existing `MetaClass not found for AiAuditEvent` regression documented in `deferred-items.md`.
result: [pending]

## Summary

total: 4
passed: 0
issues: 0
pending: 4
skipped: 0
blocked: 0

## Gaps
