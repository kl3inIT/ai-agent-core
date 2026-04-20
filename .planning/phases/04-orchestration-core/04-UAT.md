---
status: partial
phase: 04-orchestration-core
source:
  - 04-01-SUMMARY.md
  - 04-02-SUMMARY.md
  - 04-03-SUMMARY.md
  - 04-04-SUMMARY.md
  - 04-05-SUMMARY.md
started: 2026-04-20T14:39:32.1182234+07:00
updated: 2026-04-20T14:42:57.7629586+07:00
---

## Current Test

[testing paused — 1 items outstanding]

## Tests

### 1. Start New Conversation
expected: Call `ChatService.ask("user-A", null, "hello")`; response returns non-null `conversationId` and `runId`, non-blank assistant content, and creates `AiConversation` owned by `user-A` with title seeded from the first message.
result: pass

### 2. Continue Existing Conversation
expected: Call `ChatService.ask("user-A", existingConversationId, "follow-up")`; the second response keeps the same `conversationId` but generates a new `runId` for the new turn.
result: pass

### 3. Ownership Opacity
expected: If `user-B` tries `ChatService.ask("user-B", userAConversationId, "probe")`, the rejection is the same `ConversationNotFoundException` message you get for a random nonexistent conversation ID; it must not reveal whether the conversation exists for another user.
result: pass

### 4. Chat Audit Pairing
expected: After two `ask(...)` calls on the same conversation, `AiToolCallAudit` contains exactly one `PRE` and one `POST` `kind=CHAT` row per `runId`, and those chat rows link back to the conversation FK.
result: pass

### 5. Dual-Layer Conversation Persistence
expected: After an `ask(...)` round-trip, Spring AI chat memory (`JdbcChatMemoryRepository`) and the Jmix projection (`AiMessage`) contain the same ordered messages with matching roles and content.
result: pass

### 6. Live Provider Smoke Test
expected: With a real `OPENROUTER_API_KEY` configured and the live path enabled, a real `ChatService.ask(...)` call returns non-blank assistant content plus valid `conversationId` and `runId`.
result: blocked
blocked_by: third-party
reason: "OPENROUTER_API_KEY is not set in the current environment, so the real provider smoke test could not be executed."

## Summary

total: 6
passed: 5
issues: 0
pending: 0
skipped: 0
blocked: 1

## Gaps
