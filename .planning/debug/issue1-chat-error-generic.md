---
status: diagnosed
trigger: "Issue 1 of 5 (UAT Test 1) - /ai-agent/chat shows error: chatView.error.generic after Send; heading/new button show raw i18n keys."
created: 2026-04-24T00:00:00Z
updated: 2026-04-24T02:38:14Z
---

## Current Focus
hypothesis: "The blocking send failure is caused by missing `SPRING_AI_CHAT_MEMORY` in the agentstore DB while Liquibase considers `060-ai-chat-memory.xml::1-postgres` already applied; raw i18n keys came from an older UI revision and are not present in current source."
test: "Correlate stream error mapping + Liquibase/chat-memory DDL + runtime wiring to datasource + historical reproducible log evidence."
expecting: "A falsifiable mechanism showing why send degrades to `chatView.error.generic` even though translation keys exist in source."
next_action: "Finalize root-cause diagnosis report (find_root_cause_only mode)."

## Symptoms
expected: "Open /ai-agent/chat, submit prompt; user message appears, assistant message appears, streamed markdown accumulates."
actual: "MessageList UI appears and adds two messages; after Send assistant renders `error: chatView.error.generic` instead of streamed answer. Chat heading and New chat button show raw i18n keys (`chatView.title`, `chatView.action.newChat`)."
errors: "assistant rendered `error: chatView.error.generic`; untranslated i18n keys in heading and button."
reproduction: "Run Test 1 in .planning/phases/07.1-adopt-vaadin-messagelist-messageinput-for-chat-view/07.1-UAT.md."
started: "Observed during Phase 07.1 UAT Test 1."

## Eliminated
- hypothesis: "Raw keys are caused by missing chat keys in message bundles."
  evidence: "Both `messages_en.properties` and `messages_vi.properties` contain `chatView.title`, `chatView.action.newChat`, `chatView.newChat.label`, and `chatView.error.generic`."
  timestamp: 2026-04-24T02:38:14Z
- hypothesis: "Current ChatView still renders `chatView.action.newChat` from source."
  evidence: "`chat-view.xml` uses `text=\"msg:///chatView.newChat.label\"` (not `chatView.action.newChat`), so the reported raw key string does not match current source."
  timestamp: 2026-04-24T02:38:14Z
- hypothesis: "Current blocker is still the async authentication-loss bug."
  evidence: "Current `DefaultChatServiceImpl.stream(...)` includes `.contextCapture()` (the previously missing propagation step documented in resolved session `authentication-is-not-set.md`)."
  timestamp: 2026-04-24T02:38:14Z

## Evidence
- timestamp: 2026-04-24T02:33:43Z
  checked: ".planning/debug/knowledge-base.md"
  found: "Knowledge base file does not exist."
  implication: "No known-pattern candidate available; continue with direct investigation."
- timestamp: 2026-04-24T02:34:26Z
  checked: ".planning/phases/07.1-adopt-vaadin-messagelist-messageinput-for-chat-view/07.1-UAT.md"
  found: "Test 1 explicitly reports assistant bubble `error: chatView.error.generic` plus raw keys for `chatView.title` and `chatView.action.newChat`."
  implication: "Root cause must explain both send-path failure and i18n key non-resolution on the same screen."
- timestamp: 2026-04-24T02:34:26Z
  checked: ".planning/STATE.md"
  found: "Phase 07.1 work was marked complete previously, but current UAT still records Test 1 failure."
  implication: "Likely runtime/configuration mismatch or regression versus planned implementation."
- timestamp: 2026-04-24T02:34:26Z
  checked: "repo-wide string search for Test 1 keys"
  found: "A prior resolved debug session exists at .planning/debug/resolved/chat-memory-table-missing.md with the same chat error symptom."
  implication: "Previous root cause candidate exists and should be re-validated against current code/runtime state."
- timestamp: 2026-04-24T02:38:14Z
  checked: "ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-view.xml"
  found: "View title/button use absolute `msg:///...` keys; New chat button key is `chatView.newChat.label`."
  implication: "UAT's raw `chatView.action.newChat` text likely came from an older runtime revision, not the current source."
- timestamp: 2026-04-24T02:38:14Z
  checked: "ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties and messages_vi.properties"
  found: "Chat keys required by Test 1 (`chatView.title`, `chatView.newChat.label`, `chatView.error.generic`) are present in both locales."
  implication: "Missing-key hypothesis is disproven in current codebase."
- timestamp: 2026-04-24T02:38:14Z
  checked: "ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java"
  found: "`stream()` maps all unclassified runtime exceptions to `StreamingEvent.Error(\"chatView.error.generic\")` via `mapToStreamingError`."
  implication: "Any backend failure in the stream path surfaces exactly the observed assistant bubble text/key."
- timestamp: 2026-04-24T02:38:14Z
  checked: "ai-agent/ai-agent-starter/src/main/java/com/vn/autoconfigure/agent/AIAutoConfiguration.java + AgentstoreStoreConfiguration.java + jmix-app application properties"
  found: "JdbcChatMemoryRepository uses `pgvectorJdbcTemplate` backed by `agentstore.datasource`; schema auto-init is disabled (`spring.ai.chat.memory.repository.jdbc.initialize-schema=never`)."
  implication: "Chat-memory table must exist in agentstore via Liquibase; if absent, streaming fails before a normal response."
- timestamp: 2026-04-24T02:38:14Z
  checked: "ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/060-ai-chat-memory.xml"
  found: "Only one postgres changeset exists (`id=1-postgres`) with `CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY`."
  implication: "If table was dropped after the first run but changelog row remained, Liquibase will not recreate it on later boots."
- timestamp: 2026-04-24T02:38:14Z
  checked: ".planning/debug/resolved/chat-memory-table-missing.md"
  found: "Previous live repro logged `BadSqlGrammarException` caused by `ERROR: relation \"spring_ai_chat_memory\" does not exist` for the same UI symptom."
  implication: "Observed blocker mechanism is already demonstrated and remains unremediated in current changelog design."

## Resolution
root_cause: "Streaming chat fails because Spring AI's JdbcChatMemoryRepository targets table `SPRING_AI_CHAT_MEMORY` in the `agentstore` datasource, but that table can be missing while Liquibase still marks `060-ai-chat-memory.xml::1-postgres` as already executed. With schema auto-init disabled, the missing table triggers backend SQL failure; `DefaultChatServiceImpl.mapToStreamingError` then emits `chatView.error.generic`, which appears in the assistant bubble."
fix: ""
verification: ""
files_changed: []
