---
phase: 11-mutation-capable-built-in-tools
plan: 13
subsystem: audit
tags: [jmix, mutation-tools, audit, streaming, pii]

requires:
  - phase: 11-mutation-capable-built-in-tools
    provides: Mutation callback boundary and mutation diff audit hashing policy
provides:
  - Boundary sanitizer for mutation tool arguments
  - Sanitized streaming ToolCall args for mutation callbacks
  - Sanitized fallback ERROR audit rows when delegate binding fails before method body
affects: [phase-11, audit, mutation-tools, streaming]

tech-stack:
  added: []
  patterns: [boundary sanitization before streaming/audit, fail-closed mutation argument placeholder]

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationArgumentSanitizer.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecoratorSanitizerTest.java
  modified:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java

key-decisions:
  - "Sanitize only streaming/fallback-audit boundary arguments; delegate calls still receive original raw toolInput."
  - "Known mutation tools with invalid/non-object JSON fail closed to a canned placeholder without raw snippets or parse details."
  - "Unknown mutation tool names fail closed to a separate canned placeholder because the decorator is mutation-only."

patterns-established:
  - "Mutation boundary wrappers must sanitize model-supplied arguments before emitting streaming ToolCall events."
  - "Pre-method fallback audit rows reuse the same sensitive-field hashing policy as mutation diff audit payloads."

requirements-completed: [MUT-07, MUT-08, AUD-07, TEST-10, TEST-12]

duration: 12min
completed: 2026-04-29
---

# Phase 11 Plan 13: Mutation Boundary Sanitizer Summary

**Mutation callback streaming and fallback audit paths now hash configured sensitive fields before raw arguments leave the boundary.**

## Performance

- **Duration:** 12 min
- **Started:** 2026-04-29T07:09:00Z
- **Completed:** 2026-04-29T07:21:18Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- Added `MutationArgumentSanitizer` for the four mutation tool input shapes.
- Wired `MutationToolCallbackBoundaryDecorator` to use sanitized args for `StreamingEvent.ToolCall` and delegate-thrown fallback `AuditWriter.writeToolCall`.
- Preserved raw `toolInput` for the delegate so Spring AI binding and mutation method semantics are unchanged.
- Added regression coverage for valid JSON sensitive-field hashing and invalid non-JSON fail-closed placeholder behavior.

## Task Commits

1. **Task 1: Add boundary mutation argument sanitizer and wire it into callbacks** - `9c6e65f` (fix)
2. **Task 2: Boundary streaming and fallback audit sanitizer regressions** - `9c6e65f` (fix)

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationArgumentSanitizer.java` - parses known mutation inputs, hashes configured sensitive scalar fields, and returns canned placeholders on unsafe input.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java` - emits/writes sanitized arguments while still delegating raw input.
- `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java` - injects and passes the sanitizer into mutation boundary wrappers.
- `ai-agent/ai-agent/src/test/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecoratorSanitizerTest.java` - proves streaming and fallback audit args never contain `raw-secret-value`.

## Decisions Made

- Reused `AiAgentAuditProperties` and `AuditFieldHasher.sha256Hex` directly so boundary sanitization follows the same host-configured policy as `DiffSerializer`.
- Returned exact canned JSON placeholders for unparseable and unknown-tool inputs to avoid leaking input length, snippets, or parse exception details.

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered

- JetBrains suggested `List.getFirst()` in tests, but the project explicitly keeps Java 17-compatible `get(0)` assertions.
- JetBrains also reported pre-existing/non-blocking style warnings in touched files: defensive null handling, documented future parameters, duplicate helper shape with the generic decorator, and redundant default column lengths.

## Validation

- `./gradlew -p ai-agent :ai-agent:compileJava` - passed.
- `./gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.audit.MutationToolCallbackBoundaryDecoratorSanitizerTest"` - passed.
- `./gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.AgentToolCallbacksMutationAuditOwnershipTest"` - passed.
- `./gradlew -p ai-agent :ai-agent:test` - passed.
- JetBrains `get_file_problems(errorsOnly=false)` on touched Java files - no blocking findings.
- JetBrains `build_project` - passed.

## User Setup Required

None - no external service configuration required.

## Next Phase Readiness

Both Phase 11 verification gaps are closed. Phase 11 is ready for verifier re-run against the original must-haves.

---
*Phase: 11-mutation-capable-built-in-tools*
*Completed: 2026-04-29*
