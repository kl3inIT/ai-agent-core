---
slug: openrouter-400-bad-request
status: resolved
trigger: |
  <!-- DATA_START -->
  org.springframework.web.reactive.function.client.WebClientResponseException$BadRequest: 400 Bad Request from POST https://openrouter.ai/api/v1/chat/completions
  at org.springframework.web.reactive.function.client.WebClientResponseException.create(WebClientResponseException.java:321)
  Suppressed: The stacktrace has been enhanced by Reactor
  *__checkpoint  400 BAD_REQUEST from POST https://openrouter.ai/api/v1/chat/completions [DefaultWebClient]

  Chat transcript:
  0:58 23 thg 4, 2026 User: hello
  0:58 23 thg 4, 2026 Assistant: Hello! How can I assist you today?
  0:58 23 thg 4, 2026 User: bạn có thể liệt kê các sản phẩm của ứng dụng tôi không ?
  0:58 23 thg 4, 2026 Assistant: error: chatView.error.generi
  <!-- DATA_END -->
created: 2026-04-23T01:00:10.0928420+07:00
updated: 2026-04-23T01:23:30.0000000+07:00
---

# Debug Session: openrouter-400-bad-request

## Symptoms

- **Expected:** Chat should answer the second prompt (or return a valid tool/policy response) instead of failing the turn.
- **Actual:** First prompt succeeds, second prompt fails and UI shows `chatView.error.generic`.
- **Error:** `WebClientResponseException$BadRequest: 400 Bad Request` from `POST https://openrouter.ai/api/v1/chat/completions`.
- **Timeline:** Observed on 2026-04-23 around 00:58 (+07:00).
- **Reproduction:** Open chat, send `hello` (success), then ask to list products in app; observe 400 + generic error.

## Root Cause

- `ToolCallAdvisor` was configured with `.disableMemory()` (equivalent to `conversationHistoryEnabled=false` in Spring AI 1.1.4).
- In that mode, recursive tool-call follow-up requests are built as `[system, lastMessage]`.
- When the model decides to call a tool, `lastMessage` is the `tool` response, producing an orphan `role=tool` message without a preceding assistant `tool_calls` message.
- OpenRouter/OpenAI rejects that payload with:
  `Invalid parameter: messages with role 'tool' must be a response to a preceeding message with 'tool_calls'. param=messages.[1].role`

## Evidence

- Live repro test (`OpenRouterSecondTurnLiveDebugTest`) captured provider raw error body:
  `messages with role 'tool' ... param: messages.[1].role`.
- Memory dump after first turn showed only:
  1) `USER hello`
  2) `ASSISTANT Hello! ...`
  This ruled out persisted orphan tool messages and localized the fault to second-turn tool recursion request assembly.
- `javap -c -p` inspection of `spring-ai-client-chat-1.1.4` confirmed
  `ToolCallAdvisor#doGetNextInstructionsForToolCall` returns `[system, lastMessage]`
  when `conversationHistoryEnabled=false`.

## Fix

- Changed `ChatClientFactory` to configure:
  `ToolCallAdvisor.builder().conversationHistoryEnabled(true)...`
  (removed `.disableMemory()` behavior).
- Updated structural advisor test to assert `conversationHistoryEnabled == true`.
- Updated `ToolCallAdvisorBuilderConstants` symbolic method constant to
  `conversationHistoryEnabled`.

## Verification

- `ai-agent` targeted tests passed:
  - `AdvisorOrderStructuralTest`
  - `ChatPanelFragmentConversationIdTest`
- Live OpenRouter regression test passed:
  - `com.vn.jmixapp.ai.OpenRouterSecondTurnLiveDebugTest`
  - first turn + second turn (tool-eligible question) completes without 400.

## Eliminated

- Hypothesis: first-turn memory persisted an orphan tool message.
  - Eliminated by memory dump (`USER`, `ASSISTANT` only after first turn).
