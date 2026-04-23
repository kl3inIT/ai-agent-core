---
slug: list-conversation-per-question
status: resolved
trigger: |
  <!-- DATA_START -->
  can view conversation history và chỗ list conversation đang lưu per question chứ không phải per conversation
  <!-- DATA_END -->
created: 2026-04-23T00:00:00Z
updated: 2026-04-23T03:20:00Z
---

# Debug Session: list-conversation-per-question

## Symptoms

- **Expected:** Conversation list stores and displays one row per conversation/thread, with multiple questions/messages inside that same conversation.
- **Actual:** Conversation list appears to create/store entries per question instead of per conversation.
- **Error:** Functional/data behavior issue (no stack trace provided).
- **Timeline:** Reported on 2026-04-23; prior state unknown.
- **Reproduction:** Open chat UI, send multiple questions in what should be one thread, then check conversation list and observe multiple new entries.

## Current Focus

hypothesis: UI did not guarantee a stable `conversationId` before sending a turn; if the stream handoff is missed, next submit still uses `null`.
test: reserve/reuse conversation id in `ChatPanelFragment` before starting stream.
expecting: all turns in one chat route to the same `AiConversation`.
next_action: completed.

## Evidence

- `ChatPanelFragment.onSubmit()` previously called `chatService.stream(userId, conversationId, text, null)` directly.
- New chats therefore depended on downstream stream-event handoff to backfill local `conversationId`.
- If the handoff is missed, local `conversationId` remains `null`, and next question creates a fresh `AiConversation`.

## Eliminated

- JPQL in `ConversationListView` was not the cause; it correctly lists `AiConversation` rows.

## Resolution

**Root cause:** Chat UI did not guarantee local conversation-id continuity before each submit; it depended on post-start stream handoff, so missed handoff produced per-question conversation creation.

**Fix:**
1. Added `ensureConversationIdForSubmit(userId, firstMessage)` in `ChatPanelFragment`.
2. `onSubmit()` now resolves/stores conversation id before calling `chatService.stream(...)`.
3. Added `ChatPanelFragmentConversationIdTest` to guard create-once/reuse semantics.

**Verification:**
1. Ran:
   - `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.view.chat.fragment.ChatPanelFragmentConversationIdTest" --tests "com.vn.agent.view.chat.ChatViewStreamTest" --tests "com.vn.agent.view.chat.ChatViewStopTest"`
2. Result: `BUILD SUCCESSFUL`.
3. Expected runtime behavior after fix:
   - first question in new chat creates one conversation;
   - subsequent questions in same chat reuse that conversation id;
   - conversation list is grouped per conversation/thread instead of per question.
