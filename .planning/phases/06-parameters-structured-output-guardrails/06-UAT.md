---
status: complete
phase: 06-parameters-structured-output-guardrails
source:
  - 06-01-SUMMARY.md
  - 06-02-SUMMARY.md
  - 06-03-SUMMARY.md
  - 06-04-SUMMARY.md
  - 06-05-SUMMARY.md
started: 2026-04-21T12:53:12.9094689+07:00
updated: 2026-04-21T13:29:00+07:00
---

## Current Test

[testing complete]

## Tests

### 1. Default Profile Seeds on First Boot
expected: Boot a host app with `ai-agent-starter` and an empty `AiParameters` table; the bundled `default-params.yaml` seeds exactly one active `default` profile and the first `ChatService.ask(...)` works without manual parameter setup.
result: pass

### 2. Active Profile Switch and Per-Conversation Override
expected: After creating another profile and marking it active, the next `ChatService.ask(...)` uses that profile on the next request, while `ChatService.ask(..., Overrides)` changes only the single conversation call instead of replacing the active profile globally.
result: pass

### 3. Invalid Parameter YAML Is Rejected
expected: Creating or updating a profile with an unknown YAML key or invalid numeric value throws `ParametersValidationException`, and the malformed profile body is not persisted as an active configuration.
result: pass

### 4. Rate Limit and Token Budget Denials Stop the Turn
expected: Repeated chat submissions from the same user eventually hit the configured rate limit with a user-friendly denial, and a conversation that crosses the token ceiling is denied on the next turn instead of burning more provider tokens.
result: pass

### 5. Tool Guard and Iteration Cap Bound Tool Execution
expected: If `ToolGuard` vetoes a tool call, the request returns a denial and the audit row records the denial reason; if a malicious prompt forces tool-call looping, execution stops at the configured max iterations with a bounded error instead of running unbounded.
result: pass

### 6. Output Scanner Flags Echoed Injection Patterns
expected: If the final assistant text echoes an injection-like pattern, the response still returns content but is marked `flagged=true` with a stable `flaggedPatternKey`, and the audit surface records only the pattern key rather than the matched payload.
result: pass

### 7. askTyped Returns Parsed Objects or a Bounded Typed Error
expected: `ChatService.askTyped(..., Answer.class)` returns a parsed, validated object on well-formed output; malformed output gets one corrective retry, then throws `StructuredOutputException` if the second attempt is still invalid.
result: pass

## Summary

total: 7
passed: 7
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
