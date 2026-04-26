---
status: diagnosed
trigger: "Issue 2 of 5 (UAT Test 2) - Phase 07.1 tool-using responses should render inline tool-call markdown, but current output is incorrect. Goal: find_root_cause_only."
created: 2026-04-24T09:33:15.2894754+07:00
updated: 2026-04-24T09:40:18.5087662+07:00
---

## Current Focus

hypothesis: Confirmed — tool-stream events are dropped because emission depends on `RunContext` ThreadLocal, which is not propagated across reactive streaming/tool-callback threads.
test: Correlate event producer requirements (`StreamingSinkHolder.current()` needs `RunContext.get()`) with stream threading/context propagation implementation.
expecting: If true, code should show sink lookup by ThreadLocal runId, but context propagation configured only for SecurityContext (not RunContext), causing empty sink lookup and no tool markdown emission.
next_action: Return diagnosis (goal is root-cause-only) with file-level evidence and suggested fix direction.

## Symptoms

expected: Tool-using responses render inline tool-call markdown (bold tool name, italic outcome line, separator) without custom tool card UI.
actual: "@C:\\Users\\admin\\Pictures\\Screenshots\\Screenshot\\ 2026-04-23\\ 094540.png chưa đúng đúng kh"
errors: None explicitly reported; UI output is incorrect for Test 2.
reproduction: Test 2 in 07.1-UAT.md
started: During UAT for phase 07.1 (reported 2026-04-24)

## Eliminated

- hypothesis: UAT failure is primarily caused by the tool-result delimiter (`—` instead of `-`) in StreamEventRenderer.
  evidence: Screenshot artifact shows no tool activity block at all in assistant output; the observed failure is missing tool markdown, not just a delimiter variant.
  timestamp: 2026-04-24T09:36:51.5732058+07:00

## Evidence

- timestamp: 2026-04-24T09:33:55.6055189+07:00
  checked: .planning/debug/knowledge-base.md
  found: Knowledge base file does not exist yet, so no prior pattern match is available.
  implication: Investigation proceeds with fresh hypothesis formation from repository evidence.
- timestamp: 2026-04-24T09:34:23.3615273+07:00
  checked: .planning/phases/07.1-adopt-vaadin-messagelist-messageinput-for-chat-view/07.1-UAT.md
  found: Test 2 explicitly requires inline markdown tool activity in assistant message and explicitly forbids custom expand/collapse tool card UI.
  implication: Any branch still rendering tool activity outside assistant inline markdown is a direct requirement violation.
- timestamp: 2026-04-24T09:34:23.3615273+07:00
  checked: .planning/STATE.md
  found: Phase 07.1 status is "awaiting human UAT" with MessageList/MessageInput migration and StreamEventRenderer-based path already recorded as implemented.
  implication: Root cause is likely in the new rendering path behavior, not in missing phase execution.
- timestamp: 2026-04-24T09:35:43.4598496+07:00
  checked: chat rendering symbol search in ai-agent/ai-agent/src
  found: ChatPanelFragment invokes StreamEventRenderer.renderStreamEvent(...); no active ToolCallCard component usage remains (only one explanatory comment in ConversationDetailView).
  implication: UAT Test 2 behavior is controlled by StreamEventRenderer output plus ChatPanelFragment append logic.
- timestamp: 2026-04-24T09:36:17.0800713+07:00
  checked: ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
  found: For every streaming event, ChatPanelFragment appends the exact `StreamEventRenderer.renderStreamEvent(...)` string into the assistant message (`botMsg.appendText(md)`), with MessageList markdown enabled.
  implication: Any formatting defect visible in UAT must originate in StreamEventRenderer output, not downstream UI transformation.
- timestamp: 2026-04-24T09:36:17.0800713+07:00
  checked: ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java
  found: Tool result template is `\"  \\n_%s — %s_\\n\\n---\\n\"` (em dash separator), not the UAT example `_done - <summary>_` (hyphen-minus separator).
  implication: Rendered tool-activity markdown can be judged incorrect by UAT even though inline rendering exists, because the enforced literal format differs.
- timestamp: 2026-04-24T09:36:17.0800713+07:00
  checked: ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/RenderStreamEventTest.java and ChatViewStreamTest.java
  found: Tests only assert presence of italics/outcome/separator generally; they do not lock exact delimiter shape (`-` vs `—`) required by UAT text.
  implication: Regression escaped because test contract is looser than UAT contract.
- timestamp: 2026-04-24T09:36:51.5732058+07:00
  checked: C:/Users/admin/Pictures/Screenshots/Screenshot 2026-04-23 094540.png
  found: Assistant output contains final answer content only; no visible inline `**tool**`, italic outcome line, or `---` separator block for tool activity.
  implication: The dominant defect is likely absent tool-event emission into stream rather than minor markdown punctuation differences.
- timestamp: 2026-04-24T09:40:18.5087662+07:00
  checked: ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java and ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingSinkHolder.java
  found: Tool events are emitted only via `streamingSinkHolder.current()`, and `current()` resolves sink strictly from `RunContext.get()` (ThreadLocal runId).
  implication: If RunContext is absent on the tool-callback execution thread, ToolCall/ToolResult events are silently skipped, so UI receives no inline tool markdown.
- timestamp: 2026-04-24T09:40:18.5087662+07:00
  checked: ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/ChatStreamingSchedulerConfig.java and repository search for ThreadLocalAccessor registration
  found: Streaming context propagation is explicitly configured for Spring Security context only; no RunContext ThreadLocalAccessor/registration exists in project resources.
  implication: RunContext is not reliably propagated across Reactor scheduler hops in streaming mode, breaking sink correlation for tool events.
- timestamp: 2026-04-24T09:40:18.5087662+07:00
  checked: ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  found: stream path uses reactive scheduling (`subscribeOn` + `contextCapture`) and merges `toolSink.asFlux()` with content flux; tool sink population depends on decorator emission path above.
  implication: Missing RunContext propagation directly results in merged stream lacking ToolCall/ToolResult fragments, which matches UAT screenshot symptoms.
- timestamp: 2026-04-24T09:40:18.5087662+07:00
  checked: C:/Users/admin/.codex/get-shit-done/references/common-bug-patterns.md
  found: Symptom category aligns with Async/Timing pattern (context/state not preserved across async execution boundaries).
  implication: Reinforces the ThreadLocal context-propagation failure hypothesis rather than UI markdown rendering defects.

## Resolution

root_cause:
  Tool activity markdown is missing because `StreamingEvent.ToolCall`/`ToolResult` emission is keyed off `RunContext` ThreadLocal (`StreamingSinkHolder.current()`), but the streaming pipeline propagates only SecurityContext and does not propagate/register `RunContext` across reactive thread hops. As a result, tool callbacks often execute without a visible runId, sink lookup returns empty, and no tool markdown events reach ChatPanelFragment.
fix:
verification:
files_changed: []
