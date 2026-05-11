---
created: 2026-04-28T00:00:00+07:00
title: Add LLM auto-generated conversation titles
area: ui
files:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/AiConversationTitleService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/conversation/AiConversationTitleProperties.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/prompts/ai-conversation-title-system-prompt.st
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiConversation.java
---

## Problem

`AiConversation.title` is currently set to a static default (e.g. "New conversation") on first message and never refreshed. When Phase 12 ships configurable chat surfaces with a sidebar conversation list, every entry will read "New conversation" — UX gap that becomes painful at >5 conversations.

## Solution

Mirror the `D:/DTH/jmix-crm` `AiConversationTitleService` pattern:

- Trigger: after the first ASSISTANT reply in a conversation whose title is still the default.
- Use a **separate small-model `ChatClient`** (temperature=0.0, max-tokens=32, no tools, no RAG advisor, separate system prompt loaded from `prompts/ai-conversation-title-system-prompt.st`).
- Input: last 6 messages of the conversation as context snippet, capped to ~240 chars per message.
- Output: sanitize (strip quotes, trailing period, length-cap 80 chars, reject `NEW_CONVERSATION` marker), persist via `UnconstrainedDataManager` (system-internal write).
- Idempotency: skip if title is already non-default at save time (re-load before save to avoid clobbering manual edits).

### Scope

1. New `AiConversationTitleService` `@Component` in `com.vn.agent.conversation`.
2. New typed properties record `AiConversationTitleProperties` (`jmix.ai-agent.conversation-title.*`): `enabled` (default true), `model-id` (optional override), `max-context-messages` (default 6), `min-user-messages-trigger` (default 1).
3. Hook into `DefaultChatServiceImpl` after the ChatClient response commits — fire title generation async (separate thread / `@Async`) so the user reply latency is unaffected.
4. New prompt template under `src/main/resources/com/vn/agent/prompts/ai-conversation-title-system-prompt.st`. Bilingual: produce title in user's locale (Vietnamese if `vi`, English otherwise).
5. Tests: title generation respects locale, sanitization strips reserved characters, manual edits not overwritten, async failure is logged but does not break chat flow.

### Decisions to make during planning

- **Separate model vs same model?** Reference uses same `OpenAiChatOptions.model(modelId)` override on a cloned `ChatClient.Builder`. Cheaper to reuse the same provider; per-request override is enough.
- **Sync vs async?** Async preferred so user reply latency is unaffected. Use Spring `@Async` or `TaskExecutor`.
- **Trigger after Nth message?** Reference triggers after the first user message + first assistant reply. Tune later if titles drift on early-conversation turns.
- **Audit?** Title generation is a separate ChatClient call — should it write an `AiAuditEvent` row? Probably yes (kind=CHAT, eventName=conversation_title, parentId=null) for cost tracking.

### Pairs with

- Phase 12 (Configurable Chat Surfaces) — sidebar conversation list will be primary consumer.
- MEMORY `feedback_jmix_unconstrained_for_system_writes` — title save is a system-internal write under `jmix-security-data`.
