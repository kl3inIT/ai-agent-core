---
phase: 14-intent-driven-extraction-form-prefill
plan: 04
subsystem: chat-service
tags: [chat-service, tool-gating, prompt-contract, streaming, extraction]
requires:
  - phase: 14-02
    provides: IntentRegistry, IntentOption, ExtractionInput SPI shape
  - phase: 14-03
    provides: prepare_form_draft tool bridge and structured extraction payloads
provides:
  - Intent-aware ChatService blocking and streaming overloads
  - Per-turn callback gating to prepare_form_draft for named intents
  - Named-intent prompt suffix that instructs one draft-preparation call
  - Chat run context carrying already-resolved task-file ids/media into extraction
  - Regression coverage for stale intents, callback misconfiguration, prompt routing, and streaming fallback
affects: [chat-ui-send-path, extraction-ui-plan, prompt-contract-tests, task-file-media-context]
tech-stack:
  added: []
  patterns:
    - Backward-compatible overloads for per-turn intent routing
    - ToolDefinition-name filtering for Spring AI ToolCallback gating
    - RunContext extraction turn state cleared by existing chat finally/doFinally lifecycle
key-files:
  created:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/DefaultChatServiceIntentRoutingTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesComposerIntentTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/RunContext.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionToolBridge.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/taskfile/AiTaskFileMediaResolver.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
key-decisions:
  - "Keep existing chat callers compatible by adding overloads and treating blank or Auto intent ids as null."
  - "Resolve selected named intents through IntentRegistry per turn before prompt/tool setup; stale ids fail closed before LLM invocation."
  - "Named-intent callback gating filters by Spring AI ToolCallback ToolDefinition name and requires exactly one prepare_form_draft callback."
  - "ExtractionToolBridge uses chat-run scoped ExtractionInput only when the chat service populated extraction-turn state, not merely because audit context has a conversation id."
patterns-established:
  - "Named-intent turns use one prompt seam and one callback seam across blocking, streaming, and streaming fallback paths."
  - "Task-file ids are carried alongside already-resolved Media so extraction does not re-query or re-resolve active attachments."
requirements-completed: [EXTRACT-01, EXTRACT-03, EXTRACT-05, EXTRACT-06]
duration: 29min
completed: 2026-05-08
---

# Phase 14 Plan 04: Chat-Service Intent Plumbing Summary

**Named-intent chat turns now route through a fail-closed prompt and tool surface that exposes only prepare_form_draft while preserving Auto chat behavior.**

## Performance

- **Duration:** 29 min
- **Started:** 2026-05-08T03:17:25Z
- **Completed:** 2026-05-08T03:45:39Z
- **Tasks:** 5
- **Files modified:** 16

## Accomplishments

- Added intent-aware `ChatService.ask(...)` and `stream(...)` overloads while preserving existing callers.
- Added structural callback filtering: Auto/null intent returns the existing full callback surface, while named intents get exactly one audited `prepare_form_draft` callback.
- Added a named-intent prompt suffix that tells the assistant to call `prepare_form_draft("<intentId>", contextRefs)` at most once and ask for missing information instead of inventing values.
- Threaded selected intent ids through blocking, streaming, and streaming-unsupported fallback paths.
- Carried already-resolved active task-file ids and media through `RunContext` into `ExtractionToolBridge`, preserving the Phase 13.1 resolve-once-per-turn invariant.
- Added fail-closed localized errors for stale intent ids and callback misconfiguration in both locale bundles.

## Task Commits

1. **Task 1: Add intent-aware ChatService overloads** - `1b8e1fd` (`feat`)
2. **Task 2: Filter callbacks to prepare_form_draft for named intents** - `becab6f` (`feat`)
3. **Task 3: Add named-intent prompt rule composition** - `0021145` (`feat`)
4. **Task 4: Wire intent into blocking and streaming model calls** - `5824686` (`feat`)
5. **Task 5: Add chat plumbing tests** - `e5475ca` (`test`)

## Callback Counts

- **Auto/null intent:** preserves the existing full callback surface. Existing default config tests assert the baseline contains 6 read tools, 2 link tools, and `prepare_form_draft`; mutation-enabled tests keep the mutation callbacks in that full surface.
- **Named intent:** `AgentToolCallbacks.callbacksFor(userId, conversationId, intentId)` returns exactly one callback named `prepare_form_draft`.
- **Misconfigured named intent:** zero or duplicate `prepare_form_draft` callbacks throw `ToolConfigurationException`, mapped by chat service paths to `chatView.intent.configurationError`.

## Prompt Suffix

Named-intent turns append a hardcoded model-facing suffix only after `IntentRegistry.eligibleForCurrentUser()` resolves the selected id:

```text
Named extraction intent rules:
- The user selected the named extraction intent '<label>'.
- To fulfill this named-intent turn, you MUST call prepare_form_draft("<intentId>", contextRefs).
- Call prepare_form_draft at most once for this turn.
- If extracted or generated values are incomplete or ambiguous, ask the user for the missing information instead of inventing values.
- Draft promotion happens only after the user opens the Jmix detail view and clicks Save.
```

The suffix contains no raw file content or `payloadJson`.

## Verification Results

- `powershell -Command "(Select-String -Path 'ai-agent/ai-agent/src/main/java/com/vn/agent/ChatService.java' -Pattern 'intentId').Count -ge 2"` - PASS
- `powershell -Command "(Select-String -Path 'ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java' -Pattern 'prepare_form_draft','intentId','callbacksFor').Count -ge 3"` - PASS
- `powershell -Command "(Select-String -Path 'ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java' -Pattern 'prepare_form_draft','intentId','effectiveRules').Count -ge 3"` - PASS
- `powershell -Command "(Select-String -Path 'ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java' -Pattern 'callbacksFor\\(userId, convId, intentId\\)|effectiveRules\\(.*intent|executeBlockingTurn\\(.*intent').Count -ge 2"` - PASS
- i18n key parity check for `chatView.intent.configurationError` and `chatView.intent.unknownIntent` in EN/VI bundles - PASS
- `./gradlew :ai-agent:ai-agent:compileJava` - PASS
- `./gradlew :ai-agent:ai-agent:test --tests "*IntentGatingTest" --tests "*ComposerIntentTest" --tests "*ChatServiceIntentRoutingTest"` - PASS
- `./gradlew :ai-agent:ai-agent:test --tests "*Intent*"` - PASS
- `./gradlew :ai-agent:ai-agent:test --tests "*DefaultChatServiceImplStreamFallbackTest" --tests "*ChatServiceFilterParamContractTest" --tests "*AskTypedRetryTest" --tests "*AgentToolCallbacksDefaultConfigTest" --tests "*AgentToolCallbacksMutationEnabledTest"` - PASS
- JetBrains MCP file-problem checks ran on touched Java files. No errors were reported. Remaining warnings were triaged as existing defensive null checks, Java-17-compatible `List.get(0)` suggestions, unused compatibility parameters, or test-shape/style warnings.

## Decisions Made

- Backward-compatible overloads were used instead of changing existing chat call sites.
- Intent id normalization lives at the chat service boundary: null, blank, and `auto` all map to the default chat path.
- Named-intent resolution is per turn and user-sensitive through `IntentRegistry.eligibleForCurrentUser()`.
- Callback gating is structural and fail-closed; it does not rely on prompt-only instructions to hide tools.
- `RunContext` carries extraction-turn data into the tool body so `ExtractionToolBridge` can build `ExtractionInput` from the already-resolved chat context.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Carried task-file ids with resolved media**
- **Found during:** Task 4
- **Issue:** `AiTaskFileMediaResolver.Resolved` carried media and document text but not the active task-file ids required by Phase 14 D-18 `ExtractionInput`.
- **Fix:** Extended `Resolved` with `taskFileIds`, populated it from the kept active rows, and updated tests using the record constructor.
- **Files modified:** `AiTaskFileMediaResolver.java`, `DefaultChatServiceImplStreamFallbackTest.java`
- **Verification:** `./gradlew :ai-agent:ai-agent:compileJava`; focused stream fallback tests
- **Committed in:** `5824686`

**2. [Rule 1 - Bug] Prevented audit-only RunContext from shadowing explicit tool arguments**
- **Found during:** Task 4
- **Issue:** `ExtractionToolBridge` originally treated any `RunContext` conversation id as extraction-turn context, but `AuditAdvisor` can set a conversation id for ordinary tool calls too. That could make direct/programmatic tool calls ignore explicit `contextRefs`.
- **Fix:** `ExtractionToolBridge` now uses run-scoped `ExtractionInput` only when extraction-turn fields are present: user message, intent id, task-file ids, or task-file media.
- **Files modified:** `ExtractionToolBridge.java`
- **Verification:** `DefaultChatServiceIntentRoutingTest.namedExtractionTurnRejectsSecondPrepareFormDraftInvocation`; Task 5 test selector
- **Committed in:** `5824686`, covered by `e5475ca`

---

**Total deviations:** 2 auto-fixed (1 missing critical, 1 bug)
**Impact on plan:** Both fixes are within the named-intent plumbing scope and preserve the intended resolve-once media path and tool argument behavior.

## Known Stubs

None. Stub scan found only false positives in tool-description error text and an existing documentation comment about placeholder text fallback; no runtime/UI stubs were introduced.

## Issues Encountered

- The Context7 CLI fallback did not return usable output during continuation. The Spring AI `ToolCallback#getToolDefinition().name()` accessor was still verified through compilation and callback-routing tests, and prior Phase 14 research already documented the Spring AI tool callback API.
- PowerShell quoting for the i18n acceptance script required single-quoted `-Command` content so `$keys` and loop variables were not expanded by the outer shell.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Ready for Plan 14-05. The chat service now passes selected named intents through prompt composition, callback gating, run context, and streaming fallback, so the UI send path can call the new overloads without changing the tool/security contracts.

## Self-Check: PASSED

- Summary file exists at `.planning/phases/14-intent-driven-extraction-form-prefill/14-04-SUMMARY.md`.
- Created test files exist: `DefaultChatServiceIntentRoutingTest.java` and `AgentSystemPromptRulesComposerIntentTest.java`.
- Task commits found: `1b8e1fd`, `becab6f`, `0021145`, `5824686`, `e5475ca`.
- No tracked-file deletions were introduced by task commits.

---
*Phase: 14-intent-driven-extraction-form-prefill*
*Completed: 2026-05-08*
