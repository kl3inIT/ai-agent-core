---
phase: 14-intent-driven-extraction-form-prefill
plan: 03
subsystem: extraction
tags: [extraction, spring-ai-tools, audit, security, streaming]
requires:
  - phase: 14-01
    provides: AiExtractionDraft entity, TTL config, row-level ownership
  - phase: 14-02
    provides: IntentExtractor SPI, IntentRegistry, ExtractionInput, schema synthesis
provides:
  - prepare_form_draft Spring AI tool bridge with locked open_form_with_draft payload
  - ExtractionService orchestration for draft persistence and safe audit rows
  - StreamingEvent.ToolResult structured payload channel
  - Unit coverage for success, denial, schema failure, callback registration, and structured streaming payloads
affects: [chat-rendering, named-intent-gating, draft-navigation, audit-ui]
tech-stack:
  added: []
  patterns:
    - Service-owned prepare_form_draft audit rows avoid duplicate generic callback audits
    - LinkedHashMap payloads for stable tool output and audit JSON
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionToolBridge.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/ExtractionServiceTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/ExtractionToolBridgeTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/ExtractionAuditTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionService.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/StreamingEvent.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
key-decisions:
  - "prepare_form_draft audit is owned by ExtractionService; the generic callback decorator emits streaming payloads but skips duplicate audit rows for that tool."
  - "ExtractionToolBridge is payload-only and contains no Jmix/Vaadin navigation dependency."
patterns-established:
  - "ToolResult payloadJson is populated only for prepare_form_draft outputs whose action is open_form_with_draft."
  - "Extraction audit summaries carry ids, entity names, failure codes, and counts only; raw extracted values remain out of audit summaries."
requirements-completed: [EXTRACT-03, EXTRACT-05, EXTRACT-06, EXTRACT-10]
duration: 34min
completed: 2026-05-08
---

# Phase 14 Plan 03: Core Extraction Tool Summary

**Named-intent extraction now prepares persisted drafts through a payload-only Spring AI tool with safe audit rows and structured streaming output.**

## Performance

- **Duration:** 34 min
- **Started:** 2026-05-08T02:34:25Z
- **Completed:** 2026-05-08T03:08:22Z
- **Tasks:** 4
- **Files modified:** 19

## Accomplishments

- Added stable extraction exceptions plus deterministic JSON helpers for safe payload/audit serialization.
- Implemented `ExtractionService.prepare(...)` without a service-level transaction; it resolves the intent, checks exposure before extraction, persists one `AiExtractionDraft`, and writes success/denial/failure audit rows.
- Added `ExtractionToolBridge.prepare_form_draft` returning exactly `{action, draftId, entityName, instanceName}` and no navigation primitive.
- Extended `StreamingEvent.ToolResult` with `toolName` and nullable `payloadJson` so the future renderer can consume the raw locked payload without parsing human summary text.
- Added tests covering success draft persistence, owner/TTL assignment, denial with no draft, schema failure with no raw-value leak, rich tool description, callback registration, and structured streaming payload propagation.

## Task Commits

1. **Task 1: Implement extraction exceptions and result-safe JSON helpers** - `c3b0ce8` (`feat`)
2. **Task 2: Implement ExtractionService.prepare(...)** - `f2f2253` (`feat`)
3. **Task 3: Implement ExtractionToolBridge and structured ToolResult propagation** - `fb063b0` (`feat`)
4. **Task 4: Add extraction service/tool tests** - `1d87e0d` (`test`)

## Service Flow

`ExtractionService.prepare(...)` builds/normalizes `ExtractionInput`, resolves the named `IntentExtractor`, resolves its target `MetaClass`, and checks `LlmExposurePolicy.canReadEntity(...)` plus `canCreate(...)` before calling `extractor.extract(...)`. It converts the extractor result through Jackson into a deterministic `LinkedHashMap`, serializes `payloadJson`, creates `AiExtractionDraft` through secured `DataManager.create(...)`, assigns owner/current conversation/task-file context, and saves through secured `DataManager`.

The service is intentionally not annotated with `@Transactional`, so no database transaction is held across `extractor.extract(...)`.

## Audit Shapes

- **Success:** `eventName=prepare_form_draft`, `outcome=SUCCESS`, arguments contain intent/conversation/task-file/media counts only, result summary contains `draftId`, `entityName`, `extractedFieldCount`, bounded `extractedAttributes`, and `truncated`.
- **Exposure denial:** one `DENIED` `prepare_form_draft` row, no extractor invocation, no draft row, no second audit row.
- **Schema/validation failure:** one `FAILED` `prepare_form_draft` row with `failureCode`, `entityName`, and `extractedFieldCount`; no raw model output, raw extracted values, or file contents are written.

## Tool Output Sample

```json
{
  "action": "open_form_with_draft",
  "draftId": "<uuid>",
  "entityName": "jmixapp_Customer",
  "instanceName": "Customer draft <uuid>"
}
```

## Verification Results

- `./gradlew :ai-agent:ai-agent:compileJava` - PASS
- `./gradlew :ai-agent:ai-agent:test --tests "*ExtractionServiceTest" --tests "*ExtractionToolBridgeTest" --tests "*ExtractionAuditTest"` - PASS
- `./gradlew :ai-agent:ai-agent:test --tests "*Extraction*"` - PASS
- Source grep: no `ViewNavigators` or `.navigate(` in `ExtractionToolBridge` - PASS
- Task greps for `@Tool(name = "prepare_form_draft")`, `open_form_with_draft`, `payloadJson`, and audit structured-payload handling - PASS
- JetBrains MCP file-problem checks ran on touched Java files. Actionable warnings were fixed. Remaining warnings were intentionally skipped: Java-17-compatible `List.get(0)` suggestion in `ExtractionService`, defensive null guard, duplicate existing UUID-context helper shape, and local-variable style suggestions in tests.

## Decisions Made

- `prepare_form_draft` is registered as an always-present callback in this plan; named-intent gating remains owned by Plan 04.
- Generic `ToolCallbackAuditDecorator` skips audit writes for `prepare_form_draft` because `ExtractionService` owns the plan-defined success/denial/failure rows. The decorator still emits `ToolResult(toolName, payloadJson)` for streaming.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Prevented duplicate prepare_form_draft audit rows**
- **Found during:** Task 3
- **Issue:** Registering `ExtractionToolBridge` through the generic callback decorator would have added a second generic audit row for every successful, denied, or failed `prepare_form_draft` call.
- **Fix:** `ToolCallbackAuditDecorator` now skips generic audit writes for `prepare_form_draft`; `ExtractionService` remains the single audit owner for this tool while streaming events still receive the structured payload.
- **Files modified:** `ToolCallbackAuditDecorator.java`
- **Verification:** `ExtractionAuditTest.prepareFormDraftToolResultCarriesStructuredPayloadWithoutDuplicateGenericAudit`
- **Committed in:** `fb063b0` and covered by `1d87e0d`

**2. [Rule 1 - Test Regression] Updated existing callback-count tests for the new default tool**
- **Found during:** Task 4
- **Issue:** Existing AgentToolCallbacks tests asserted exact callback counts from before the extraction tool existed.
- **Fix:** Updated default and mutation-enabled callback expectations to include `prepare_form_draft`.
- **Files modified:** `AgentToolCallbacksDefaultConfigTest.java`, `AgentToolCallbacksMutationEnabledTest.java`
- **Verification:** `./gradlew :ai-agent:ai-agent:test --tests "*Extraction*"` plus compile of all tests
- **Committed in:** `1d87e0d`

---

**Total deviations:** 2 auto-fixed (1 missing critical, 1 test regression)
**Impact on plan:** Both keep the plan's audit and callback contracts correct. No scope expansion beyond direct effects of adding the extraction tool.

## Issues Encountered

- The plan referenced `com.vn.agent.audit.DiffSerializer`; the actual class is `com.vn.agent.tools.mutation.DiffSerializer`. The correct file was read before implementation.
- First JetBrains MCP inspection attempt timed out while the IDE was busy. Re-runs completed for the touched files and remaining warnings were triaged as non-blocking.

## Known Stubs

None. Stub-pattern scan found only legitimate null checks and generic safe error wording, not placeholder behavior or unwired UI data.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Ready for Plan 14-04 to add named-intent chat/tool gating. The core tool is registered, draft preparation is service-owned, and streaming has the structured payload channel the renderer will consume.

## Self-Check: PASSED

- Summary file exists.
- Task commits found: `c3b0ce8`, `f2f2253`, `fb063b0`, `1d87e0d`.
- Created test files exist under `ai-agent/ai-agent/src/test/java/com/vn/agent/extraction/`.

---
*Phase: 14-intent-driven-extraction-form-prefill*
*Completed: 2026-05-08*
