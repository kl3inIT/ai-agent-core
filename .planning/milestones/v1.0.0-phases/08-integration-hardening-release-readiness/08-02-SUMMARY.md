---
phase: 08
plan: 02
subsystem: security/test
tags: [test-only, prompt-injection, audit, durability, decorator, TEST-02, TEST-03, R-02a, R-02b, R-02c, R-02d]
requires:
  - com.vn.agent.tools.ToolResultFormatter#record
  - com.vn.agent.audit.AuditWriter (REQUIRES_NEW writer proxy)
  - com.vn.agent.audit.ToolCallbackAuditDecorator
  - com.vn.agent.orchestration.RunContext (root-audit-id ThreadLocal)
  - com.vn.agent.entity.AiAuditEvent (parent self-FK + runId + outcome + errorClass)
  - com.vn.agent.entity.AiToolCallOutcome.{SUCCESS,ERROR}
  - com.vn.agent.spi.AuditKind.TOOL
provides:
  - PromptInjectionHarnessTest +2 @Test methods (SYSTEM-prefix payload + delimiter-token payload)
  - AuditDurabilityTest +1 @Test method exercising the production decorator path on the ERROR branch
  - parent_id + runId field assertions on the surviving child audit row (R-02d)
affects:
  - none (test-only — no production sources touched)
tech-stack:
  added: []
  patterns:
    - "Stub @code{ToolCallback} (anonymous inner) wrapping the real REQUIRES_NEW AuditWriter via ToolCallbackAuditDecorator — proves production wiring, not just writer in isolation"
    - "RunContext.set/setRootAuditId/clear handshake mirrored from AuditAdvisor → decorator (D-10)"
    - "TransactionTemplate.executeWithoutResult outer rollback + try/catch around expected RuntimeException, then assert child audit row survived in agentstore"
    - "Loading the surviving child via fetchPlan(BASE + parent BASE) to materialize the lazy ManyToOne safely outside the original tx"
key-files:
  created: []
  modified:
    - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/PromptInjectionHarnessTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/audit/AuditDurabilityTest.java
key-decisions:
  - "Implemented the decorator-path test by constructing a stub ToolCallback inline (anonymous inner class) rather than autowiring a real built-in tool callback — keeps the failure deterministic (no DB row dependence) and the throw class predictable (IllegalStateException)"
  - "Wrote the CHAT root via auditWriter.writeChatStart BEFORE entering the outer-rollback tx, so the parent_id assertion (R-02d) has a stable target id regardless of outer-tx fate"
  - "Reloaded the surviving child with fetchPlan('parent', BASE) before asserting parent.getId() — defensive against LazyInitializationException across the @Transactional boundaries"
  - "Used AiToolCallOutcome.ERROR + IllegalStateException.class.getName() as the precise errorClass assertion — the decorator records the throw class FQN in the finally block"
patterns-established:
  - "Decorator-path durability proof pattern: writeChatStart → RunContext.set(rootAuditId) → outer tx { decorator.call } catch → assert TOOL row by (runId, kind=TOOL) survives + parent + runId match"
requirements-completed:
  - TEST-02
  - TEST-03
duration: ~30min
completed: 2026-04-26
---

# Phase 8 Plan 02: TEST-02 + TEST-03 Test Suite Extensions Summary

Extended two existing harness classes (per CONTEXT D-06: no new test files) with three additional @Test methods that close the residual TEST-02 (poisoned-tool-result coverage) and TEST-03 (decorator-path rollback durability + field-level audit assertions) acceptance gaps from 08-REVIEWS.md (R-02a/b/c/d).

## Outcome

- **All three new tests PASS** (5/5 in PromptInjectionHarnessTest, 3/3 in AuditDurabilityTest).
- No production source changed. Existing test methods untouched. Class-level `@SpringBootTest` / `@ImportAutoConfiguration` annotations preserved.

| Test class | Existing | Added | Total | Pass |
|---|---:|---:|---:|---:|
| PromptInjectionHarnessTest | 3 | 2 | 5 | 5 |
| AuditDurabilityTest | 2 | 1 | 3 | 3 |

## Tasks Executed

| Task | Name | Notes |
|---|---|---|
| 1 | Extend PromptInjectionHarnessTest with TWO poisoned-tool-result fixtures (R-02a) | Found in-tree on session start — verified to match plan-script verbatim and acceptance grep counts |
| 2 | Extend AuditDurabilityTest with decorator-path tool-throw + outer-rollback case (R-02b/c/d) | Newly written this session — uses anonymous-inner stub ToolCallback wrapped in the production decorator |
| 3 | JetBrains MCP get_file_problems on both files | Zero ERROR-severity findings on either file (4 pre-existing `getFirst()` warnings + 1 weak warning unchanged — out of scope) |

## Verification Results

- `./gradlew :ai-agent:ai-agent:compileTestJava` — **PASS**
- `./gradlew :ai-agent:ai-agent:test --tests "com.vn.agent.tools.PromptInjectionHarnessTest" --tests "com.vn.agent.audit.AuditDurabilityTest"` — **PASS** (8 tests, 0 skipped, 0 failures, 0 errors)
- `mcp__jetbrains__get_file_problems` on both files — zero errors

### Acceptance criteria greps (all satisfied)

| Pattern | File | Expected | Actual |
|---|---|---:|---:|
| `poisonedSystemPrefixFromToolResult_isWrappedAndEscaped` | PromptInjectionHarnessTest | 1 | 1 |
| `poisonedDelimiterTokensFromToolResult_areEscapedNotInterpreted` | PromptInjectionHarnessTest | 1 | 1 |
| `@Test` count | PromptInjectionHarnessTest | 5 (3+2) | 5 |
| `errorPathToolAuditAlsoSurvivesOuterRollback_viaDecorator` | AuditDurabilityTest | 1 | 1 |
| `ToolCallbackAuditDecorator` | AuditDurabilityTest | ≥ 2 | 5 |
| `TransactionTemplate` | AuditDurabilityTest | ≥ 1 | 4 |
| `AiToolCallOutcome.ERROR` | AuditDurabilityTest | ≥ 1 | 1 |
| `AuditKind.TOOL` | AuditDurabilityTest | ≥ 1 | 3 |
| `getParent()` | AuditDurabilityTest | ≥ 1 | 2 |
| `getRunId()` | AuditDurabilityTest | ≥ 1 | 1 |
| `throw new UnsupportedOperationException` | AuditDurabilityTest | 0 | 0 |
| `@Test` count | AuditDurabilityTest | +1 | 3 (2+1) |

## Self-Check: PASSED

All success criteria met:
- Two existing test files extended; zero new test files (D-06).
- TEST-02 backstopped by SYSTEM-prefix AND delimiter-token poisoned payloads (R-02a).
- TEST-03 backstopped by ERROR-path tool-callback decorator-routed rollback test (R-02b/c) with parent + runId field verification (R-02d).
- Compile + JetBrains-MCP gates clean.
- All eight tests in the two classes pass.

## Notes for downstream waves

- The decorator-path test does **not** depend on any specific tool implementation (uses an anonymous-inner stub callback), so it remains stable as built-in tools evolve.
- Plan 08-07 will not need to touch either of these test files; release wiring is orthogonal.
