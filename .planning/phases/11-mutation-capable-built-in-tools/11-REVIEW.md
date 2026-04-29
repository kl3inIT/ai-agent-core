---
phase: 11-mutation-capable-built-in-tools
reviewed: 2026-04-29T06:33:00Z
depth: standard
files_reviewed: 78
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/AIConfiguration.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiInternalEntityNames.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRules.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/ToolNamePatternProvider.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/SystemPromptComposer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentMutationRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/MutationGuard.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/spi/MutationIntent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/link/BuiltInLinkTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiAgentMutationProperties.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiMutationIntent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/AiMutationIntentStatus.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/DiffSerializer.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationAttributeBinder.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationAuthorizationService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationCommitCoordinator.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationCommitState.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationErrorTranslator.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentCleanupJob.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationRequestHasher.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationSaveExecutor.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/ToolEntityResolver.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/audit/AiAuditEventDetailDialog.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/070-ai-mutation-intent.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AgentSystemPromptRulesComposerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/AskTypedRetryTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNameLeakScannerTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/guard/ToolNamePatternProviderMutationToolsTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/ChatServiceFilterParamContractTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/link/BuiltInLinkToolsOpacityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/AgentToolCallbacksDefaultConfigTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/AgentToolCallbacksMutationAuditOwnershipTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/AgentToolCallbacksMutationEnabledTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsAccessGatingTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsAuditArgumentsTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsCommitUnknownTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsGuardReceivesCoercedAttributesTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsIdempotencyReplayTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsIdempotencyViolationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsKnownRollbackTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsMassAssignmentTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsPostCommitAuditFailureTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsRelatedWriteSecurityTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsRelationshipExposureTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/BuiltInMutationToolsReplayPermissionTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationChildFixture.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationLinkedChildFixture.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationLinkedParentFixture.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationParentFixture.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationTestFixture.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/fixture/MutationTestFixtureTestRole.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationErrorTranslatorTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationFixturePersistenceTestConfiguration.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationIntentRepositoryReservationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationIntentRepositoryStateTransitionTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationRequestHashCanonicalizationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationToolInvariantsTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationToolTestContext.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/MutationToolTestUsersConfiguration.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/mutation/RelatedWriteMetadataResolverTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java
  - ai-agent/ai-agent/src/test/resources/com/vn/agent/test_liquibase/010-mutation-test-fixture.xml
  - ai-agent/ai-agent/src/test/resources/com/vn/agent/test_liquibase/test-main-changelog.xml
  - ai-agent/ai-agent/src/test/resources/com/vn/agent/test-app.properties
  - ai-agent/ai-agent/src/test/resources/com/vn/agent/tools/mutation/persistence.xml
findings:
  critical: 2
  warning: 2
  info: 0
  total: 4
status: issues_found
---

# Phase 11: Code Review Report

**Reviewed:** 2026-04-29T06:33:00Z
**Depth:** standard
**Files Reviewed:** 78
**Status:** issues_found

## Summary

Reviewed the Phase 11 mutation-capable tool changes across callback wiring, mutation orchestration, idempotency, authorization, audit, Liquibase, messages, and tests. The implementation has two blockers: replay audit outcomes do not fit the existing audit schema, and raw mutation arguments can leak through streaming and fallback audit paths. Two additional warnings cover idempotency canonicalization and validation ordering.

Targeted validation run: `..\..\gradlew test --tests "com.vn.agent.tools.mutation.BuiltInMutationToolsIdempotencyReplayTest"` from `ai-agent/ai-agent` passed. A root-level `.\gradlew test` command is not available in this repository root.

## Critical Issues

### CR-01: BLOCKER - Replay Audit Outcome Exceeds Persisted Column Length

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiToolCallOutcome.java:13`

**Issue:** Phase 11 adds `IDEMPOTENT_REPLAY`, which is 17 characters, but audit outcomes are still persisted through `AiAuditEvent.outcome` with `@Column(length = 16)` and the existing Liquibase column is `OUTCOME varchar(16)` (`ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiAuditEvent.java:92`, `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/030-ai-audit-event.xml:31`). `MutationCommitCoordinator.replayResult()` writes `AiToolCallOutcome.IDEMPOTENT_REPLAY`, so replay audit rows can fail or truncate on databases that enforce the column length. Because mutation callbacks are intentionally not wrapped by the generic audit decorator, this can silently remove the replay audit record.

**Fix:**
```xml
<changeSet id="071-widen-ai-audit-outcome" author="ai-agent">
    <modifyDataType tableName="AI_AUDIT_EVENT"
                    columnName="OUTCOME"
                    newDataType="varchar(32)"/>
</changeSet>
```

Also update `AiAuditEvent` to `@Column(name = "OUTCOME", length = 32)` and add a test that performs an idempotent replay and asserts a persisted `AiAuditEvent` row with outcome `IDEMPOTENT_REPLAY`.

### CR-02: BLOCKER - Mutation Boundary Leaks Raw Sensitive Arguments

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/MutationToolCallbackBoundaryDecorator.java:115`

**Issue:** The mutation callback boundary stores raw `toolInput` in `cappedInput`, emits it to streaming `ToolCall` events at line 119, and writes it directly to `AuditWriter.writeToolCall()` on delegate-thrown binding failures at lines 138-140. This bypasses the `DiffSerializer` sensitive-field hashing used by normal mutation self-audit. A streaming `create_record` / `update_record` call containing a configured sensitive field such as `secret` will expose the raw value to the chat UI, and a pre-method binding failure will persist the raw value in `AiAuditEvent.argumentsJson`.

**Fix:**
```java
String safeInput = mutationArgumentSanitizer.sanitize(toolName, toolInput);
emitToolEvent(sink -> sink.tryEmitNext(new StreamingEvent.ToolCall(toolCallId, toolName, safeInput)));
...
auditWriter.writeToolCall(parentId, runId, userUsername, conversationId, toolName,
        safeInput, null, latencyMs, AiToolCallOutcome.ERROR, null, t.getClass().getName());
```

The sanitizer should parse known mutation tool JSON and hash or remove configured sensitive attribute values using the same policy as `DiffSerializer`. Add regression coverage for both streaming `ToolCall.argsJson` and the boundary error audit path with `jmix.ai-agent.audit.sensitive-fields=secret`.

## Warnings

### WR-01: WARNING - Attribute Validation Runs After AccessManager Context Construction

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/BuiltInMutationTools.java:191`

**Issue:** `create_record`, `update_record`, and related writes call `MutationAuthorizationService.enforceAttributeWriteAccess()` before the binder/resolver has validated that the attribute or relationship actually exists (`BuiltInMutationTools.java:191`, `BuiltInMutationTools.java:329`, `BuiltInMutationTools.java:580`). This contradicts the mass-assignment contract documented in `MutationAttributeBinder` and can make unknown attributes surface as `access_denied` or runtime translator fallback depending on role shape, instead of the stable `validation_failed` result.

**Fix:** Add a first validation pass that resolves all requested properties/relationships before constructing any `EntityAttributeContext`, then run access checks on the validated property names, then perform coercion/loading.

### WR-02: WARNING - UUID Idempotency Keys Are Validated But Not Canonicalized

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/mutation/MutationIntentRepository.java:211`

**Issue:** `validateIdempotencyKey()` accepts any `UUID.fromString()` input, but `reserveOrReplay()` persists and queries the original string. UUID text is case-insensitive, so the same UUID submitted once as lowercase and once as uppercase becomes two different `(toolName, idempotencyKey, userUsername)` rows and can duplicate the host mutation.

**Fix:** Parse and canonicalize the key before it reaches the repository lookup/save path:

```java
String canonicalKey = UUID.fromString(idempotencyKey).toString();
```

Use `canonicalKey` for reservation, lookup, audit arguments, and tests. Add a test where the second call reuses the same UUID with different casing and must return `IDEMPOTENT_REPLAY`, not perform a second save.

---

_Reviewed: 2026-04-29T06:33:00Z_
_Reviewer: the agent (gsd-code-reviewer)_
_Depth: standard_
