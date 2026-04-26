---
name: 260420-se6 — fix JetBrains file problems project-wide
status: complete
completed: 2026-04-20
---

# Summary

Scanned modified Java main-source files on branch `gsd/phase-05-rag-layer` via JetBrains MCP `get_file_problems` and applied triaged fixes.

## Fixes applied

- `AsyncIngestionWorker` — diamond operator on `TypeReference<>`.
- `MdcPropagatingTaskDecorator` — `@NonNull` on `decorate` return + param (package is `@NonNullApi`).
- `DefaultChatServiceImpl` — javadoc ERROR: `{@link}` to private method → `{@code}`.
- `AuditAdvisor` — NonNull on override return/params; `getLast()` replacing `get(size-1)`.
- `ToolCallbackAuditDecorator` — NonNull on `getToolDefinition` + both `call(...)` overloads.
- `AiKnowledgeDocumentStatus`, `AiMessageRole`, `AiToolCallOutcome` — NonNull on `getId()`.
- `ProjectingChatMemoryRepository` — NonNull on all `ChatMemoryRepository` overrides.
- `StructuredFilterConditionMapper` — `negated ? !b : b` → `negated != b` (boolean XOR).
- `OrderSummaryToolContributor` (jmix-app) — `Objects::nonNull` reference instead of lambda.

## Intentional skips

- Defensive null checks flagged "always true/false" in `AuditAdvisor`, `ProjectingChatMemoryRepository`, `AsyncIngestionWorker` — kept as belt-and-suspenders guards at framework contract boundaries.
- `@Tool` method `summarizeCustomerOrders` flagged "never used" — invoked via Spring AI reflection.
- Stylistic "if → switch" rewrites — no correctness gain.

## Verification

- JetBrains MCP re-scan on each fixed file: no new warnings introduced; only intentional-skip warnings remain.
- `./gradlew :ai-agent:ai-agent:classes :ai-agent:ai-agent:testClasses` — clean.
- `./gradlew :ai-agent:ai-agent:test` — **BUILD SUCCESSFUL** in 1m 10s (all tests pass).

## Scope note

`jmix-app` module has a pre-existing missing `spring-ai-rag` dependency unrelated to these edits, so root `./gradlew test` is not runnable; verification was module-scoped on `ai-agent`.
