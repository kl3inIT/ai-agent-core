---
phase: 04-orchestration-core
plan: 03
subsystem: audit
tags: [audit, advisor, requires-new, spring-ai, tool-callback, after-commit]
dependency-graph:
  requires:
    - 04-01 (AiToolCallAudit runId/kind/phase/promptHash/errorClass columns)
    - 04-02 (RunContext ThreadLocal<UUID>)
    - Phase 2 AuditListener SPI, AiConversation entity, AiToolCallOutcome enum
    - Spring AI 1.1.4 CallAdvisor + ToolCallback interfaces
  provides:
    - com.vn.agent.audit.AuditWriter (sole @Transactional(REQUIRES_NEW) surface)
    - com.vn.agent.audit.AuditListenerDispatcher (per-listener try/catch fan-out)
    - com.vn.agent.audit.AuditAdvisor (outermost CallAdvisor at HIGHEST_PRECEDENCE)
    - com.vn.agent.audit.ToolCallbackAuditDecorator (wraps ToolCallback; PRE/POST rows)
    - com.vn.agent.audit.ToolCallAdvisorBuilderConstants (OQ-1 closure constants)
    - EN/VI i18n key com.vn.agent.audit/AuditWriteFailed
  affects:
    - Plan 04-04 (ChatClientFactory wires AuditAdvisor into defaultAdvisors; AgentToolCallbacks wraps each ToolCallback with ToolCallbackAuditDecorator)
    - Plan 04-05 (integration tests assert pre/post chat rows + per-tool rows correlate by runId; AdvisorOrderStructuralTest reflects on ToolCallAdvisor.conversationHistoryEnabled field)
tech-stack:
  added: []
  patterns:
    - Single-proxy REQUIRES_NEW audit writer (D-11) — advisor + decorator INJECT the writer, never self-invoke transactional methods
    - afterCommit TransactionSynchronization fan-out (D-14) — listeners observe durable rows only
    - Per-listener try/catch(Throwable) suppression (D-13, SPI-06) — one bad listener can't fail the chat
    - Sentinel toolName="<chat>" + sentinel outcome for chat-level rows — satisfies entity @NotNull without DDL change
    - B8 runId handoff via advisor context key "audit.runId" — service pre-allocates; advisor falls back to UUID.randomUUID() only defensively
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallAdvisorBuilderProbe.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditListenerFanOut.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditAdvisor.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java
  modified:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
decisions:
  - AuditWriter is the single @Transactional(REQUIRES_NEW) surface; AuditAdvisor and ToolCallbackAuditDecorator are NOT annotated @Transactional and NEVER call `this.writeXxx(...)` — Pitfall #3 closed by construction
  - Chat-level rows use sentinel toolName="<chat>" and sentinel outcome to satisfy the Phase 2 NOT NULL constraints without a Phase 4 DDL rewrite (additive-only migration discipline)
  - writeChatPost re-resolves conversation via DataManager.load so the audit query in Plan 04-05 can filter by a.conversation.id as well as by runId (Blocker 2 resolved)
  - AuditListenerFanOut catches Throwable (not Exception) per D-13 — Errors from host listeners would otherwise kill the audit-committing thread
  - OQ-1 resolution codified as constants (ToolCallAdvisorBuilderProbe.RESOLVED_BUILDER_METHOD = "disableMemory") so Plan 04-04 consumes a verified symbol rather than a string literal
metrics:
  duration_minutes: ~15
  tasks_completed: 3
  files_created: 5
  files_modified: 2
  commits: 3
  completed_date: 2026-04-20
---

# Phase 04 Plan 03: Audit pipeline Summary

Four production classes under `com.vn.agent.audit` — `AuditWriter` (the sole REQUIRES_NEW surface), `AuditListenerFanOut` (SPI-06 per-listener try/catch fan-out), `AuditAdvisor` (outermost `CallAdvisor` at `Ordered.HIGHEST_PRECEDENCE` writing chat PRE/POST rows), `ToolCallbackAuditDecorator` (wraps any `ToolCallback` to persist PRE/POST tool rows that survive delegate rollback) — plus the `ToolCallAdvisorBuilderProbe` constants that close OQ-1. Compile green.

## Objective recap

Phase 4 needs a pipeline where (a) every chat call produces a pre/post audit row pair correlated by `runId`; (b) every tool invocation inside the call produces a pre/post tool row pair that survives tool rollback; (c) rows commit independently of the caller's transaction (AUD-02); (d) host `AuditListener` beans fire exactly once per committed tool row (SPI-06, AUD-05); (e) the Plan 04-04 `ChatClientFactory` has verified `ToolCallAdvisor.Builder` API constants to reference (OQ-1).

## Work completed

### Task 1 — OQ-1 closure (commit `463115a`)

Created `ToolCallAdvisorBuilderProbe` with three verified constants extracted from `javap -p` on `spring-ai-client-chat-1.1.4.jar`:

- `TOOL_CALL_ADVISOR_FQN = "org.springframework.ai.chat.client.advisor.ToolCallAdvisor"`
- `RESOLVED_BUILDER_METHOD = "disableMemory"` — the canonical builder method for disabling internal conversation history
- `ORDER_SETTER_METHOD = "advisorOrder"` — the order setter is NOT named `order(int)`
- `INTERNAL_FLAG_FIELD = "conversationHistoryEnabled"` — private field Plan 04-05's AdvisorOrderStructuralTest reflects on

No runtime behavior; pure documentation contract so Plan 04-04's `ChatClientFactory` symbolically references the verified method name.

### Task 2 — AuditWriter + AuditListenerFanOut + i18n (commit `13939cd`)

`AuditWriter` (`@Component`, constructor-injected `DataManager`/`Metadata`/`AuditListenerFanOut`):

- `writeChatPre(runId, userUsername, conversationId, promptHash) : UUID` — `@Transactional(REQUIRES_NEW)`; sentinel `toolName="<chat>"`, sentinel outcome `SUCCESS`.
- `writeChatPost(runId, userUsername, conversationId, latencyMs, errorClass) : void` — `@Transactional(REQUIRES_NEW)`; outcome = `SUCCESS` if errorClass null else `ERROR`. Re-resolves conversation so Plan 04-05's `a.conversation.id` JPQL filter works.
- `writeToolCall(runId, userUsername, conversationId, toolName, argumentsJson, resultSummary, latencyMs, outcome, denialReason, errorClass, phase) : UUID` — `@Transactional(REQUIRES_NEW)`; registers `TransactionSynchronization.afterCommit → fanOut.fireToolCallAudited(auditId)` (D-14). Defensive inline-fire fallback when no sync active (logged WARN).

NO class-level `@Transactional`; NO self-invocation of `this.writeXxx(...)` — Pitfall #3 closed by construction.

`AuditListenerFanOut` (`@Component`): constructor-injected `List<AuditListener>` (Spring auto-collects; empty list when none registered). `fireToolCallAudited(auditId)` iterates with `try { listener.onToolCallAudited(auditId); } catch (Throwable t) { log.warn(...); }` — one throwing listener cannot block others or fail the chat thread (D-13, SPI-06).

Added EN/VI key `com.vn.agent.audit/AuditWriteFailed` (reserved for future use; locale parity enforced).

### Task 3 — AuditAdvisor + ToolCallbackAuditDecorator (commit `70eb934`)

`AuditAdvisor` implements `org.springframework.ai.chat.client.advisor.api.CallAdvisor`:

- `getName()` → `"AuditAdvisor"`; `getOrder()` → `Ordered.HIGHEST_PRECEDENCE` (outermost; wraps memory + tool advisors per D-02).
- `adviseCall(ChatClientRequest, CallAdvisorChain)`:
  - reads runId from advisor context key `"audit.runId"` (B8) — falls back to `UUID.randomUUID()` only when absent/malformed
  - `RunContext.set(runId)` before `chain.nextCall(...)`; `RunContext.clear()` in `finally`
  - `auditWriter.writeChatPre(...)` before chain call; `auditWriter.writeChatPost(...)` in `finally` with latency (ns→ms) + errorClass (caught Throwable's class name)
  - `hashPrompt` SHA-256 hex (first 64 chars) of the last USER message text, falling back to last instruction / prompt toString
  - `resolveUserUsername()` via Jmix `CurrentAuthentication.getUser().getUsername()` with `"anonymous"` fallback on `RuntimeException`
- No `@Transactional` annotation; no `this.writeXxx` self-call — durability comes exclusively from the injected `AuditWriter` proxy.

`ToolCallbackAuditDecorator` implements `org.springframework.ai.tool.ToolCallback` (plain class — instantiated by `AgentToolCallbacks` in Plan 04-04, NOT a `@Component`):

- Overrides both `call(String)` and the default `call(String, ToolContext)` — internal `callInternal` branches on the overload used.
- PRE row written eagerly via `auditWriter.writeToolCall(..., "PRE")`.
- Delegate invocation in try / catch (Throwable) / finally — POST row written in `finally` with real outcome (SUCCESS/ERROR), latency, and errorClass.
- `resultSummary` is the delegate's output truncated to `RESULT_SUMMARY_MAX_CHARS = 4096`; on error it's the exception's `getMessage()`.
- PRE + POST audit write exceptions are caught and logged at WARN — they never mask the delegate's return value or exception.

## OQ-1 resolution

OQ-1 ("Which `ToolCallAdvisor.Builder` method disables internal conversation history?") — **RESOLVED** 2026-04-20 via `javap -p` on `spring-ai-client-chat-1.1.4.jar`:

```
public T disableMemory();                     ← canonical method
public T conversationHistoryEnabled(boolean); ← alias
public T advisorOrder(int);                   ← order setter
public T toolCallingManager(ToolCallingManager);
public T streamToolCallResponses(boolean);
public T suppressToolCallStreaming();
public ToolCallAdvisor build();
```

Codified as `ToolCallAdvisorBuilderProbe.RESOLVED_BUILDER_METHOD = "disableMemory"`.

## Deviations from Plan

None — plan executed exactly as written. All three tasks compiled on first try; all acceptance-criteria greps pass (see Verification).

## Threat surface scan

No new network endpoints, auth paths, or trust-boundary crossings were introduced beyond the plan's `<threat_model>` (which already enumerated the hashPrompt / REQUIRES_NEW durability / listener fan-out / user resolution mitigations). All five register entries are implemented as specified.

## Verification

- `./gradlew :ai-agent:ai-agent:compileJava` → BUILD SUCCESSFUL.
- Grep: `@Transactional` in `com.vn.agent.audit` — 3 hits on AuditWriter method-level only; zero class-level; zero on AuditAdvisor or ToolCallbackAuditDecorator (documentation-only mentions in Javadoc).
- Grep: `Propagation.REQUIRES_NEW` in `AuditWriter.java` → exactly 8 textual hits (3 method annotations + 5 import/doc references); 3 annotations on 3 public methods.
- Grep: `auditWriter.writeChatPre(` / `auditWriter.writeChatPost(` in `AuditAdvisor.java` — both present; no `this.write...` self-calls.
- Grep: `registerSynchronization` in `AuditWriter.java` — present inside `writeToolCall` with `afterCommit` override calling `fanOut.fireToolCallAudited(auditId)`.
- Grep: `List<AuditListener>` in `AuditListenerFanOut.java` — present (constructor injection).
- Grep: `RESOLVED_BUILDER_METHOD = "disableMemory"` + `INTERNAL_FLAG_FIELD = "conversationHistoryEnabled"` + `ORDER_SETTER_METHOD = "advisorOrder"` in `ToolCallAdvisorBuilderProbe.java` — all three constants present.
- i18n: `com.vn.agent.audit/AuditWriteFailed` present in both EN and VI message files.
- Entity setters used: `setConversation`, `setUserUsername`, `setToolName`, `setArgumentsJson`, `setResultSummary`, `setOutcome`, `setDenialReason`, `setLatencyMs`, `setStartedAt`, `setFinishedAt`, `setRunId`, `setKind`, `setPhase`, `setPromptHash`, `setErrorClass`. NO imaginary setters (setUserId / setCreatedAt / setInputJson / setOutputJson / setSuccess / setErrorMessage / setConversationId(UUID)).

## Self-Check: PASSED

- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallAdvisorBuilderProbe.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditListenerFanOut.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditAdvisor.java
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java
- FOUND: commit 463115a (ToolCallAdvisorBuilderProbe — OQ-1 closure)
- FOUND: commit 13939cd (AuditWriter + AuditListenerFanOut + i18n)
- FOUND: commit 70eb934 (AuditAdvisor + ToolCallbackAuditDecorator)
- FOUND: compileJava exit 0 (BUILD SUCCESSFUL)
