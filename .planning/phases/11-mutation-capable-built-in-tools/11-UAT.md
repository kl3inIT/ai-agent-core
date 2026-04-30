---
status: complete
phase: 11-mutation-capable-built-in-tools
source:
  - 11-01-SUMMARY.md
  - 11-02-SUMMARY.md
  - 11-03-SUMMARY.md
  - 11-04-SUMMARY.md
  - 11-05-SUMMARY.md
  - 11-06-SUMMARY.md
  - 11-07-SUMMARY.md
  - 11-07A-SUMMARY.md
  - 11-07B-SUMMARY.md
  - 11-07C-SUMMARY.md
  - 11-08-SUMMARY.md
  - 11-09-SUMMARY.md
  - 11-10-SUMMARY.md
  - 11-11-SUMMARY.md
  - 11-12-SUMMARY.md
  - 11-13-SUMMARY.md
started: 2026-04-30T00:38:15.8939786+07:00
updated: 2026-04-30T16:51:33.9284081+07:00
---

## Current Test

[testing complete]

## Tests

### 1. Mutation Tool Happy Path
expected: With mutation tools enabled and the admin user granted mutation permission, asking the AI to create a new customer should create exactly one Customer row, avoid idempotency errors, and return a normal user-facing success response.
result: pass

### 2. Fresh UUID v4 Idempotency
expected: The first create attempt uses a fresh random UUID v4 idempotencyKey, not a copied sample key such as f47ac10b-58cc-4372-a567-0e02b2c3d479, so the first attempt does not fail with idempotency_violation or UUID format errors.
result: pass

### 3. Detail Link After Create
expected: After create_record succeeds, the AI calls generate_entity_detail_link with the same entityName and returned entityId, then gives the user a clickable detail link for the saved row.
result: pass

### 4. Order Create With Typed Values
expected: Creating an order with an existing customer, LocalDate orderDate, and enum-like status succeeds using values from describe_entity; the row is persisted and the detail link points to the new order.
result: pass

### 5. Safe Idempotency Failure
expected: Reusing an idempotencyKey for a different operation returns a clean idempotency_violation with guidance to use a fresh key, and no second host row is created.
result: pass

### 6. Link Tool Entity Resolution
expected: Link generation uses the exact entityName returned by list_entities or the preceding successful tool call, resolves primary Jmix list/detail views, and does not surface NoSuchViewException to the user.
result: pass

### 7. Chat Output Hygiene
expected: Tool calls, raw JSON arguments, raw tool errors, stack traces, and audit/debug logs are not shown in the user-facing assistant message; the chat response stays business-readable.
result: pass
reported: "The chat response stayed business-readable, but it incorrectly said no customer named Phan Hồng Đạt was found even though the row exists in the database."
resolution: "Fixed find_records/count_records filter normalization so ToolCallback inputs accept both structured filter objects and stringified JSON filters; added regression coverage for Vietnamese customer names through both direct and ToolCallback paths."
artifacts:
  - "jmix-app test --tests com.vn.jmixapp.ai.ChatServiceToolIntegrationTest"
  - "ai-agent:test --tests UnknownEntityRetryHintTest --tests ToolDescriptionInvariantsTest --tests BuiltInDataToolsReadOnlyTest"

### 8. Audit Trail Visibility
expected: The audit list records mutation tool calls with clear success/error outcomes and sanitized arguments/results, so operators can inspect failures without exposing raw sensitive values to the chat user.
result: pass

### 9. Loading Indicator While Waiting
expected: While the AI is processing a request, the chat panel shows the bottom loading/progress bar, disables the input, and hides the loading state again when the stream finishes or is stopped.
result: pass

### 10. Update Existing Record
expected: When asked to change one field on an existing readable row, the AI first loads or finds that row, calls update_record with a fresh UUID v4 idempotencyKey and only the changed attributes, persists the update without creating a duplicate row, then returns a normal user-facing response with the row detail link.
result: pass

### 11. Add Related Record
expected: When asked to link an existing child row to an existing parent through a supported non-composition relationship, the AI verifies both records, calls add_related_record with exact entity and relationship names plus a fresh UUID v4 idempotencyKey, and the child becomes linked to the parent. If the relationship is unsupported, the user sees a clean validation message, not a stack trace.
result: pass

### 12. Remove Related Record
expected: When asked to unlink a currently related child row through a supported nullable relationship, the AI verifies the current link, calls remove_related_record with a fresh UUID v4 idempotencyKey, clears the relationship without deleting the child row, and reports a clean result. Unsupported composition or not-null relationships return clean validation_failed guidance.
result: pass

### 13. Delete Tool Not Exposed
expected: Asking the AI to delete a record does not call any delete_record tool because v1.1 does not expose one; the row remains in the database and the assistant explains that deletion is not available through the mutation tool surface.
result: pass

## Summary

total: 13
passed: 13
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps

- truth: "Tool-backed chat responses should reflect saved records when find_records is used against an existing readable customer."
  status: fixed
  reason: "Regression reproduced that direct and object ToolCallback filters find Vietnamese names; added stringified-filter callback coverage matching the UI audit argument shape."
  severity: major
  test: 7
  artifacts:
    - "jmix-app/src/test/java/com/vn/jmixapp/ai/ChatServiceToolIntegrationTest.java"
    - "ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java"
  missing: []
