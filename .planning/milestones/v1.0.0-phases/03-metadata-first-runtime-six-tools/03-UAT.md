---
status: complete
phase: 03-metadata-first-runtime-six-tools
source: [03-01-SUMMARY.md, 03-02-SUMMARY.md, 03-03-SUMMARY.md, 03-04-SUMMARY.md, 03-05-SUMMARY.md]
started: 2026-04-20T00:35:00+07:00
updated: 2026-04-20T00:44:30+07:00
---

## Current Test
<!-- OVERWRITE each test - shows where we are -->

[testing complete]

## Tests

### 1. Host Integration Smoke
expected: Run `./gradlew :jmix-app:test --tests "com.vn.jmixapp.ai.ChatServiceToolIntegrationTest"`. The build should succeed, the callback list should include the six built-ins plus `summarize_customer_orders`, `find_records("jmixapp_Order", ...)` should return the seeded order with `"truncated":false`, and `describe_entity("jmixapp_Order")` should return structured JSON with expected attributes.
result: pass

### 2. Metadata Access Smoke
expected: Run `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.metadata.CurrentUserSchemaAccessTest"`. The build should succeed, `@SystemLevel` classes should stay hidden, framework-managed fields should stay out of user-editable string tracking, and denied entities/attributes should stay absent from the readable schema.
result: pass

### 3. Structured Filter Smoke
expected: Run `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.filter.StructuredFilterConditionMapperTest" --tests "com.vn.agent.filter.FilterLiteralValueConverterTest"`. The build should succeed, supported filter operators should map correctly, invalid operators should be rejected, and bad literals or denied paths should fail closed with structured errors.
result: pass

### 4. Prompt Safety Smoke
expected: Run `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.ToolResultFormatterTest" --tests "com.vn.agent.tools.PromptInjectionHarnessTest"`. The build should succeed, user-editable string values should be wrapped in `<data>...</data>`, and literal delimiter text inside stored values should be escaped instead of breaking the boundary.
result: pass

### 5. Read-Only Enforcement Smoke
expected: Run `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.BuiltInDataToolsReadOnlyTest"`. The build should succeed, and the ASM enforcement test should keep failing any future mutation path or LLM-parameter JPQL concat inside built-in tool bodies.
result: pass

### 6. Full Phase Regression Smoke
expected: Run `./gradlew :ai-agent:ai-agent:test :jmix-app:test`. The full Phase 3 regression surface should stay green with no new failures in the add-on or host app test suites.
result: pass

## Summary

total: 6
passed: 6
issues: 0
pending: 0
skipped: 0
blocked: 0

## Gaps
