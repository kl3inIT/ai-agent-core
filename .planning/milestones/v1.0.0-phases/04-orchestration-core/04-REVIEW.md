---
phase: 04
status: issues_found
depth: standard
files_reviewed: 20
findings:
  critical: 0
  high: 0
  medium: 4
  low: 4
reviewed_at: 2026-04-20T00:00:00Z
---

# Phase 04: Code Review Report

**Reviewed:** 2026-04-20
**Depth:** standard
**Files Reviewed:** 20 (19 production Java + 1 Liquibase + 1 autoconfig; tests spot-checked)
**Status:** issues_found

## Summary

The Phase 4 orchestration core implements the advisor-ordering, audit-durability, ownership-opacity, and dual-layer-parity contracts from `04-CONTEXT.md` correctly. The high-risk invariants all check out:

- **Advisor ordering (D-02):** `AuditAdvisor` at `HIGHEST_PRECEDENCE`, `MessageChatMemoryAdvisor` at `+200`, `ToolCallAdvisor` at `+300` with `.disableMemory()` set — matches the spec.
- **Audit durability (D-11, Pitfall #3):** `AuditWriter` is the sole `@Transactional(REQUIRES_NEW)` surface; `AuditAdvisor` and `ToolCallbackAuditDecorator` inject the proxy and never self-invoke. No self-invocation bug present.
- **Ownership opacity (D-09):** `ConversationGateway` combines `c.id = :id AND c.createdBy = :owner` in one JPQL round-trip and throws the same `ConversationNotFoundException` with the same literal message for both missing and not-yours. Opacity preserved.
- **Fan-out fault isolation (D-13/D-14):** `AuditListenerFanOut` wraps each listener in `try/catch(Throwable)`; firing is registered via `TransactionSynchronization.afterCommit` inside the REQUIRES_NEW boundary.
- **RunId pre-allocation (B8):** `DefaultChatServiceImpl` allocates `runId` before `chatClient.prompt()` and hands it to `AuditAdvisor` via the `"audit.runId"` advisor context key; advisor falls back to `UUID.randomUUID()` only when absent.
- **Dual-layer parity (D-08):** `ProjectingChatMemoryRepository` is `@Primary`, delegates to `JdbcChatMemoryRepository`, and mirrors JDBC delete-then-insert semantics inside the same REQUIRED transaction.
- **Jmix conventions:** constructor injection everywhere; `Metadata.create()` used for all entity instantiation; no Lombok on entities; no `EntityManager`; i18n keys present in both `messages.properties` and `messages_vi.properties`.

Findings below are maintainability and edge-case polish items. Zero critical / zero high — Phase 4 is ready to consume downstream.

## Medium

### MD-01: `ConversationGateway` explicitly overwrites `createdDate`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ConversationGateway.java:64`
**Issue:** On auto-create, the gateway sets `fresh.setCreatedDate(OffsetDateTime.now())` explicitly. If `AiConversation` uses Jmix's `@CreatedDate` auditing (the entity exposes a `createdDate` property, which is the standard Jmix audit field name), the manual set either duplicates or competes with the audit infrastructure, and test-time authentication-dependent auditing may be bypassed. The `createdBy` below it is also the audit field name (rather than a custom `userId`), which works but reuses Jmix audit plumbing.
**Fix:** Drop the explicit `setCreatedDate` call and let `@CreatedDate` / `@CreatedBy` populate both fields via Jmix auditing (the test harness already runs under `SystemAuthenticator.runWithSystem`). If deterministic clocking is needed for tests, inject a `TimeSource` instead of calling `OffsetDateTime.now()` directly.

```java
// Before
fresh.setCreatedBy(userId);
fresh.setCreatedDate(OffsetDateTime.now());
// After
fresh.setCreatedBy(userId);
// createdDate populated by Jmix @CreatedDate
```

### MD-02: `AiParametersResolver.buildFallback` builds YAML via unescaped string concatenation

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/AiParametersResolver.java:54-60`
**Issue:** Defaults are concatenated verbatim into a synthetic YAML blob — `yaml.append("systemPrompt: ").append(defaults.systemPrompt())`. If an operator sets `jmix.ai-agent.defaults.system-prompt` to any string containing YAML-significant characters (`:` inside, `#`, leading `-`, multiline), the re-parse in `parseBody()` either (a) throws `YAMLException` on the next request, (b) silently truncates at the first `:` / `#`, or (c) returns a `Map` instead of a `String` for that key. The current single-line default is safe, but this is a booby-trap for hosts. The shape also violates the "defaults path is symmetric with the accessor path" comment — the resolver would be simpler and safer if `effective*` consulted `defaults.xxx()` directly when `parseBody` lookup misses, bypassing the YAML round-trip entirely for the fallback case.
**Fix:** Either quote strings properly (`Yaml().dump(...)` or `DumperOptions`) or — better — drop the synthetic YAML and have `effectiveXxx` fall through to `defaults.xxx()` when the entity has no `bodyYaml`:

```java
public String effectiveSystemPrompt(AiParameters params) {
    String body = params.getBodyYaml();
    if (body != null && !body.isBlank()) {
        Object v = parseBody(params).get("systemPrompt");
        if (v != null) return String.valueOf(v);
    }
    return defaults.systemPrompt();
}
```

### MD-03: `AuditWriter.writeToolCall` silently drops conversation FK on stale ids

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java:136-141`
**Issue:** When `conversationId != null` but `dataManager.load(...).optional()` returns empty (e.g., the conversation was deleted between turns, or the id is bogus), the audit row is written with a `null` conversation FK and no WARN log. Correlation queries that join by `a.conversation.id` will silently miss the row. Same branch exists in `writeChatPre` / `writeChatPost`.
**Fix:** Log at WARN when the conversation id is non-null but the lookup is empty, so operations can distinguish "chat never had a conversation" (normal for brand-new chats that failed before `loadOrCreate`) from "conversation vanished mid-flight":

```java
if (conversationId != null) {
    AiConversation conv = dataManager.load(AiConversation.class).id(conversationId).optional().orElse(null);
    if (conv != null) {
        row.setConversation(conv);
    } else {
        log.warn("writeToolCall: conversation {} not found; row will have null FK (runId={})", conversationId, runId);
    }
}
```

### MD-04: `ToolCallbackAuditDecorator` writes uncapped `argumentsJson`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java:68-71, 97-99`
**Issue:** `resultSummary` is truncated to `RESULT_SUMMARY_MAX_CHARS = 4096`, but `argumentsJson` (the model-supplied tool input) is persisted as-is. The `ARGUMENTS_JSON` column is `@Lob`, so this is not a hard failure, but: (a) a model that hallucinates a gigabyte of JSON balloons the audit table; (b) there is no symmetric cap making "what we store" observable at the reader's end; (c) prompt-injection payloads stored verbatim make subsequent operator inspection awkward.
**Fix:** Apply the same `RESULT_SUMMARY_MAX_CHARS` cap (or a dedicated `ARGUMENTS_JSON_MAX_CHARS`) to `toolInput` at both PRE and POST call sites, with a suffix like `"…[truncated]"` to make the truncation explicit.

## Low

### LO-01: `ChatClientFactory` hardcodes English fallback system prompt

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatClientFactory.java:57`
**Issue:** `defaultSystem(systemPrompt != null ? systemPrompt : "You are a helpful assistant.")` — the fallback literal is not an i18n key. This is the default that the LLM receives when both the active profile and `jmix.ai-agent.defaults.system-prompt` are null. Unlikely in practice (application.properties ships a default), but CLAUDE.md's "ALL labels, titles, buttons MUST use `msg://` keys" rule was written for UI text; this is a model-directed system prompt, so the rule arguably doesn't apply. Calling it out so the decision is explicit.
**Fix:** Either (a) remove the fallback entirely and fail fast if `systemPrompt == null` (it indicates misconfiguration), or (b) sink the literal into `AiAgentDefaultsProperties` and keep it non-i18n on the grounds that it is a model instruction, not a UI string.

### LO-02: `ProjectingChatMemoryRepository.findByConversationId` declares `@Transactional` without `readOnly=true`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ProjectingChatMemoryRepository.java:48-51`
**Issue:** The read path is annotated `@Transactional` (read-write) even though it only delegates a SELECT. No correctness issue — Spring allows this — but the connection-pool hint and JDBC driver's ability to skip write-mode setup are missed.
**Fix:** `@Transactional(readOnly = true)` on `findByConversationId` (and consider the same for `findConversationIds` if it is ever promoted to `@Transactional`).

### LO-03: `AuditAdvisor.hashPrompt` may emit inconsistent hashes for prompt objects

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditAdvisor.java:137-145`
**Issue:** The fallback `text = request.prompt().toString()` hashes the `Prompt`'s default `toString()`, which typically embeds metadata (options, tool-call results) not just the user text. Two identical user prompts with different option objects would hash differently. The primary path (scan for last `MessageType.USER`) usually succeeds, so the fallback is cold, but when it fires the hash is no longer stable against the invariant "same user text ⇒ same promptHash".
**Fix:** When the USER-message scan misses, prefer `instructions.get(last).getText()` (already done) and drop the `prompt().toString()` branch — if there are zero instructions, set `text = ""` and proceed. That way the hash is always over message text, never over framework metadata.

### LO-04: `BaselineContextProvider.extractUserKey` reflects on any method named `getKey`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java:103-110`
**Issue:** `user.getClass().getMethod("getKey")` returns whatever the user class exposes under that name, regardless of return type. If a host replaces Jmix's `User` with a `UserDetails` that happens to define `getKey()` returning an AES key / API token / internal handle, that value will be published into the `agent.userId` context slot and thus into the LLM-facing system prompt.
**Fix:** Tighten the reflection — require the method to return `UUID` or `String`, or prefer a known-good type check:

```java
var m = user.getClass().getMethod("getKey");
Object result = m.invoke(user);
return (result instanceof UUID || result instanceof String) ? result : user.getUsername();
```

---

_Reviewed: 2026-04-20_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
