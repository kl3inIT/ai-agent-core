---
slug: ai-chat-always-refuses
status: resolved
trigger: |
  <!-- DATA_START -->
  2026-04-22T23:57:14.074+07:00 WARN i.j.c.s.impl.SystemAuthenticatorSupport :
  Stack does not exist. Check correctness of begin/end invocations.

  Chat transcript shows assistant always replying:
  "Xin lỗi, nhưng tôi không thể trả lời câu hỏi đó..."
  (including for "hello", "khách hàng của ứng dụng tôi có những thuộc tính nào",
  and "bạn có thể trả lời về vấn đề gì"), with user note: "always response this".
  <!-- DATA_END -->
created: 2026-04-22T23:59:05.4918153+07:00
updated: 2026-04-23T00:49:25.8237637+07:00
---

# Debug Session: ai-chat-always-refuses

## Symptoms

- **Expected:** Chat assistant should answer normal prompts and tool-backed domain questions (for example customer attributes visible to the current user).
- **Actual:** Assistant returns the same refusal-style fallback response for every prompt.
- **Error:** `SystemAuthenticatorSupport` warns: `Stack does not exist. Check correctness of begin/end invocations.`
- **Timeline:** First observed on 2026-04-22 around 23:55-23:57 (+07:00).
- **Reproduction:** Open chat UI as `admin`, send `hello` and business-domain prompts; observe identical refusal response each time.

## Current Focus

hypothesis: confirmed — two independent issues existed: (1) Spring AI RAG default empty-context behavior forced refusal-style replies when KB had no documents; (2) reactive stream auth scope used `SystemAuthenticator.begin/end` across scheduler hops, producing stack-mismatch warnings.
test: Queried live DB state, inspected Spring AI 1.1.4 source for `ContextualQueryAugmenter`, and traced `DefaultChatServiceImpl.stream()` lifecycle with scheduler/context propagation.
expecting: RAG no longer forces “cannot answer” on empty KB; streaming auth warning eliminated.
next_action: deploy and runtime-verify chat behavior in `jmix-app`.

## Evidence

- timestamp: 2026-04-23 — live DB (`ai_agent_kb_vector_store`) contained `0` rows and no knowledge documents; repeated assistant refusals were persisted in `ai_agent_message` and `spring_ai_chat_memory`.
- timestamp: 2026-04-23 — active `ai_agent_parameters` profile was valid (`openai/gpt-4o-mini`, non-empty prompt, enabled tools), so refusal was not caused by a missing/blank profile.
- timestamp: 2026-04-23 — Spring AI 1.1.4 source (`ContextualQueryAugmenter`) default behavior: `allowEmptyContext=false`; empty retrieval context emits a synthetic prompt instructing the model to politely say it cannot answer.
- timestamp: 2026-04-23 — `RetrievalAugmentationAdvisorFactory` previously used default `ContextualQueryAugmenter`, so empty KB guaranteed refusal-like responses regardless of user prompt.
- timestamp: 2026-04-23 — `DefaultChatServiceImpl.stream()` used `Flux.using` with `systemAuthenticator.begin(userId)` and disposer `systemAuthenticator.end`; in reactive scheduling this can end on a different thread, matching `SystemAuthenticatorSupport: Stack does not exist`.
- timestamp: 2026-04-23 — targeted tests passed after fixes:
  - `AskTypedRetryTest`
  - `ChatServiceFilterParamContractTest`
  - `RetrievalAugmentationAdvisorFactoryTest`

## Eliminated

- hypothesis: hardcoded UI fallback text causes the refusal. (No matching hardcoded refusal string in UI/backend message bundles.)
- hypothesis: active parameters row missing/corrupted to an empty prompt. (Row present and parseable.)
- hypothesis: KB prompt-injection document causes refusals. (KB/document tables were empty.)

## Resolution

**Root cause:**  
The refusal loop was caused by Spring AI RAG default empty-context behavior, not by Jmix row-level policy. `RetrievalAugmentationAdvisor` was built with default `ContextualQueryAugmenter` (`allowEmptyContext=false`). With an empty vector store, each request was augmented to an instruction equivalent to “query is outside knowledge base; politely say you can’t answer,” producing repeated refusal responses.  
Separately, streaming auth used `SystemAuthenticator.begin/end` across reactive scheduler hops, causing the stack-mismatch warning.

**Fix:**  
1. Configured RAG advisor to allow empty context:
   - `RetrievalAugmentationAdvisorFactory` now sets:
     `queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())`.
2. Removed manual reactive `SystemAuthenticator.begin/end` scope in streaming:
   - `DefaultChatServiceImpl.stream()` now relies on `.contextCapture()` after `.subscribeOn(chatStreamingScheduler)` to propagate caller security context safely across hops.
3. Added regression test:
   - `RetrievalAugmentationAdvisorFactoryTest` asserts advisor is wired with `allowEmptyContext=true`.

**Files changed:**  
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactory.java`  
- `ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java`  
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatStreamingSchedulerConfig.java`  
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/advisor/RetrievalAugmentationAdvisorFactoryTest.java`  
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java`  
- `ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AskTypedRetryTest.java`

**Verification plan:**  
1. `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.guard.AskTypedRetryTest" --tests "com.vn.agent.rag.ChatServiceFilterParamContractTest" --tests "com.vn.agent.rag.advisor.RetrievalAugmentationAdvisorFactoryTest"`  
2. Restart `jmix-app` and re-test prompts (`hello`, capability question, and entity-attribute question).  
3. Confirm no further `SystemAuthenticatorSupport ... Stack does not exist` warning for chat streams.
