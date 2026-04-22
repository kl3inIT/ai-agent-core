---
slug: list-conversation-per-question
status: resolved
trigger: |
  <!-- DATA_START -->
  can view conversation history và chỗ list conversation đang lưu per question chứ không phải per conversation
  <!-- DATA_END -->
created: 2026-04-23T00:00:00Z
updated: 2026-04-23T02:15:00Z
---

# Debug Session: list-conversation-per-question

## Symptoms

- **Expected:** Conversation list stores and displays one row per conversation/thread, with multiple questions/messages inside that same conversation.
- **Actual:** Conversation list appears to create/store entries per question instead of per conversation.
- **Error:** Functional/data behavior issue (no stack trace provided).
- **Timeline:** Reported on 2026-04-23; prior state unknown.
- **Reproduction:** Open chat UI, send multiple questions in what should be one thread, then check conversation list and observe multiple new entries.

## Current Focus

hypothesis: UI never stores the newly created conversation id from streaming final event.
test: trace `conversationId` from `ChatPanelFragment` submit -> `DefaultChatServiceImpl.stream` -> emitted `StreamingEvent.Final`.
expecting: after first turn, panel state receives generated `conversationId` and reuses it for next turns.
next_action: completed.

## Evidence

- `ChatPanelFragment.onSubmit()` called `chatService.stream(userId, conversationId, text, null)` with `conversationId=null` for new chat.
- `DefaultChatServiceImpl.stream()` created a conversation via `conversationGateway.loadOrCreate(...)` and got `convId`.
- `StreamingEvent.Final` previously emitted only `runId + metrics`, so UI had no way to capture `convId`.
- Because UI `conversationId` stayed null, every next question triggered another `loadOrCreate(..., null, ...)` -> new row per question.

## Eliminated

- JPQL in `ConversationListView` was not the cause; it correctly lists `AiConversation` rows.

## Resolution

**Root cause:** Streaming final event did not carry `conversationId`, so chat UI could not retain the generated conversation id after first message in a new chat.

**Fix:**
1. Extended `StreamingEvent.Final` to include `conversationId`.
2. Updated `DefaultChatServiceImpl.stream()` to emit `new StreamingEvent.Final(runId, convId, latencyMs, ...)`.
3. Updated `ChatPanelFragment` to set its local `conversationId` from `StreamingEvent.Final` when current id is null.
4. Updated tests to new `StreamingEvent.Final` signature.

**Verification:**
1. Ran:
   - `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.RenderStreamEventTest" --tests "com.vn.agent.view.chat.ChatViewStreamTest"`
2. Result: `BUILD SUCCESSFUL`.
3. Expected runtime behavior after fix:
   - first question in new chat creates one conversation;
   - subsequent questions in same chat reuse that conversation id;
   - conversation list is grouped per conversation/thread instead of per question.
