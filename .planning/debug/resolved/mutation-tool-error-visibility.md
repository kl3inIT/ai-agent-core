---
status: resolved
trigger: "Mutation create_record logs 'Successful execution of tool' in Spring AI while audit row is ERROR with java.lang.IllegalArgumentException and empty resultSummary"
created: 2026-04-29T16:25:33.3969551+07:00
updated: 2026-04-29T17:49:30+07:00
---

## Current Focus

hypothesis: confirmed - mutation tools catch expected and unexpected failures, return a sanitized error JSON to Spring AI, and previously audited ERROR with null resultSummary. Follow-up live failures were caused by Jmix/EclipseLink paths in agentstore idempotency reservation, not by host Customer/Order entity saving.
test: Trace BuiltInMutationTools create_record catch paths, MutationCommitCoordinator.safeWriteAudit, and MutationToolCallbackBoundaryDecorator behavior against DB audit rows from 2026-04-29 16:21 +07.
expecting: create_record method body returns normally on failures, audit resultSummary is passed as null on error paths, and unexpected Throwable paths do not log a sanitized stacktrace marker.
next_action: fixed and verified
reasoning_checkpoint: null
tdd_checkpoint: null

## Symptoms

expected: When mutation tool fails, operators should see a sanitized reason/error code in audit and enough host-app logs in IntelliJ to debug without exposing PII. Spring AI callback success logs should not be mistaken for mutation success.
actual: IntelliJ shows Spring AI `Successful execution of tool: create_record`, while audit UI shows `outcome=ERROR`, `errorClass=java.lang.IllegalArgumentException`, blank denial reason, and blank result. DB confirms no Customer row or idempotency row was created.
errors: `java.lang.IllegalArgumentException` in `ai_agent_audit_event.error_class`; empty `result_summary`; no stacktrace in host logs.
timeline: Reproduced on 2026-04-29 after enabling mutation tools in host app.
reproduction: Ask AI to create Customer with `entityName=Customer`, attributes `{name,email,phone}`, and fresh UUID idempotencyKey. `describe_entity("Customer")` succeeds, then `create_record("Customer", ...)` audits ERROR.

## Evidence

- timestamp: 2026-04-29T16:25:33.3969551+07:00
  checked: DB agentstore.ai_agent_audit_event
  found: Three `create_record` rows around 2026-04-29T09:21Z have `outcome=ERROR`, `error_class=java.lang.IllegalArgumentException`, and empty `result_summary`.
  implication: The mutation method completed from Spring AI's perspective but recorded failure internally.
- timestamp: 2026-04-29T16:25:33.3969551+07:00
  checked: DB agentstore.ai_mutation_intent and main.customer
  found: `ai_mutation_intent` count was 0 and no `customer` row named `Nguyễn Văn An` existed.
  implication: Failure happens before idempotency reservation and before host save.
- timestamp: 2026-04-29T16:25:33.3969551+07:00
  checked: host app Spring AI logs
  found: `MethodToolCallback` logs `Successful execution of tool: create_record`.
  implication: Tool callback did not throw; failure was returned as a regular tool result.
- timestamp: 2026-04-29T17:02:42.7897160+07:00
  checked: BuiltInMutationTools catch paths and MutationCommitCoordinator audit helpers
  found: Mutation error catch paths now call `safeWriteErrorAudit(...)`, which stores the same sanitized JSON error envelope returned to the LLM. Catch-all paths also emit `AI_AGENT_MUTATION_TOOL_UNEXPECTED_FAILURE` with tool name, outcome, commit state, run/root/conversation ids, exception class, stable error code, and sanitized stack frames.
  implication: Operators can correlate Spring AI callback success with the mutation-level ERROR and see the sanitized failure class/code without raw exception messages or attribute values.
- timestamp: 2026-04-29T17:02:42.7897160+07:00
  checked: CustomerMutationToolIntegrationTest
  found: Direct `create_record("Customer", ...)` succeeds when admin has `AiAgentMutationRole`.
  implication: `Customer` is a valid mutation entity name; the observed live error is not caused by using the wrong entity name.
- timestamp: 2026-04-29T17:02:42.7897160+07:00
  checked: Regression tests
  found: Mutation package tests pass, including pre-reservation unexpected failure and known rollback audit-summary coverage.
  implication: Blank ERROR audit summaries are covered by tests for both no-intent pre-reservation failures and known host-save rollback failures.
- timestamp: 2026-04-29T17:23:57.2599254+07:00
  checked: Live host log and audit UI screenshot
  found: `MutationIntentRepository.findExisting` was the source of the remaining `IllegalArgumentException`; mutation audit rows were also top-level because the tool ran on `boundedElastic` without `RunContext` ThreadLocal even though `toolContext` carried the run/conversation ids.
  implication: The error visibility fix exposed two remaining runtime issues: idempotency lookup needed store-safe query construction, and mutation callback boundary needed to install per-call run context before invoking self-auditing mutation tools.
- timestamp: 2026-04-29T17:49:30+07:00
  checked: Live host log and regression test for `create_record("jmixapp_Order", ...)`
  found: The latest live stack moved from `FluentLoader$ByCondition.optional` to `UnitOfWorkImpl.registerNewObjectForPersist` through `UnconstrainedDataManagerImpl.save`, still with `commitState=NO_HOST_WRITE`. A direct integration test successfully created `jmixapp_Order` with an existing `Customer` reference, so the remaining failure was in the internal idempotency reservation row insert before host save, not the host order save.
  implication: Avoid Jmix/EclipseLink persist for the initial `AI_MUTATION_INTENT` reservation row in the mutation hot path.

## Eliminated

- hypothesis: `Customer` is not the correct tool entityName.
  evidence: `list_entities` DB audit includes `{"name":"Customer","label":"Khách hàng"}` and `describe_entity("Customer")` succeeds.
  timestamp: 2026-04-29T16:25:33.3969551+07:00

## Resolution

root_cause:
Mutation tools intentionally catch failures and return sanitized JSON error results, so Spring AI logs `Successful execution of tool` when the Java callback returns normally. The defect was in the mutation audit/error observability path: ERROR/COMMIT_FAILED audit rows used `resultSummary=null`, and unexpected catch-all failures did not emit a sanitized host log marker with enough correlation data.
fix:
Added `MutationCommitCoordinator.safeWriteErrorAudit(...)` to persist the sanitized error JSON envelope in audit `resultSummary`, routed mutation error catch paths through it, and added `AI_AGENT_MUTATION_TOOL_UNEXPECTED_FAILURE` logging for catch-all failures without logging raw exception messages, raw arguments, idempotency keys, or attribute values. Added/updated regression tests and a host-app Customer integration test.
Follow-up fix after live log: changed `MutationIntentRepository` idempotency/cleanup lookups from raw JPQL strings to Jmix `PropertyCondition` API, and changed `MutationToolCallbackBoundaryDecorator` to restore `RunContext` from Spring AI `ToolContext` around delegate execution so `BuiltInMutationTools` self-audit rows get `runId`, `conversationId`, and root parent fallback.
Second follow-up after the 17:34 live log: changed `MutationIntentRepository` to use agentstore JDBC for initial `AI_MUTATION_INTENT` reservation insert as well as lookup/cleanup. This removes `UnconstrainedDataManager.save()` / `EntityManager.persist()` from the pre-host-write reservation path while preserving the unique idempotency index and existing JPA-based state transitions for loaded rows.
verification:
`.\gradlew :jmix-app:test --tests "com.vn.jmixapp.ai.CustomerMutationToolIntegrationTest" --no-daemon` passed.
`.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsPreReservationFailureAuditTest" --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsKnownRollbackTest" --no-daemon` passed.
`.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.*" --no-daemon` passed.
`.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.audit.MutationToolCallbackBoundaryDecoratorSanitizerTest" --tests "com.vn.agent.tools.mutation.MutationIntentRepositoryReservationTest" --tests "com.vn.agent.tools.mutation.MutationIntentRepositoryStateTransitionTest" --no-daemon` passed.
JetBrains `get_file_problems` returned no problems for `MutationCommitCoordinator`, `BuiltInMutationToolsKnownRollbackTest`, `BuiltInMutationToolsPreReservationFailureAuditTest`, and `CustomerMutationToolIntegrationTest`. `BuiltInMutationTools` has weak duplication warnings only; no errors.
JetBrains follow-up inspection returned no errors on `MutationIntentRepository`, `MutationToolCallbackBoundaryDecorator`, `AuditWriter`, and `MutationToolCallbackBoundaryDecoratorSanitizerTest`; remaining findings are weak/style warnings only.
Additional verification after JDBC reservation insert:
`.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.MutationIntentRepositoryReservationTest" --tests "com.vn.agent.tools.mutation.MutationIntentRepositoryStateTransitionTest" --no-daemon` passed.
`.\gradlew :jmix-app:test --tests "com.vn.jmixapp.ai.CustomerMutationToolIntegrationTest.createRecord_canCreateOrderWithExistingCustomerReference" --no-daemon` passed.
`.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.tools.mutation.*" --no-daemon` passed.
`.\gradlew :jmix-app:test --tests "com.vn.jmixapp.ai.CustomerMutationToolIntegrationTest" --no-daemon` passed.
`.\gradlew -p ai-agent :ai-agent:test --tests "com.vn.agent.audit.MutationToolCallbackBoundaryDecoratorSanitizerTest" --no-daemon` passed.
files_changed:
`ai-agent/ai-agent/src/main/java/com/vn/agent/audit/AuditWriter.java`
`ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java`
`ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java`
`ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationCommitCoordinator.java`
`ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java`
`ai-agent/ai-agent/src/test/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecoratorSanitizerTest.java`
`ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsKnownRollbackTest.java`
`ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsPreReservationFailureAuditTest.java`
`jmix-app/src/test/java/com/vn/jmixapp/ai/CustomerMutationToolIntegrationTest.java`
