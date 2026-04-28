---
phase: 10-ai-specific-llm-exposure-policy
reviewed: 2026-04-28T04:44:23Z
depth: standard
files_reviewed: 41
files_reviewed_list:
  - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiKnowledgeDocument.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRule.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleEntityListener.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRuleMode.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureChangedEvent.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposurePolicy.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureRuleRepository.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/BaselineContextProvider.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/rag/UpdatePermissionsResult.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/security/AiAgentAdminRole.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/fetchplan/FetchPlanIntersector.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/AiExposureRuleDetailView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/AiExposureRuleListView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/MetaclassComboBoxHelper.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java
  - ai-agent/ai-agent/src/main/java/com/vn/agent/view/vectorstore/VectorStoreDebugView.java
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/060-ai-exposure-rule.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/061-ai-knowledge-document-source-entity.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/exposure/ai-exposure-rule-detail-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/exposure/ai-exposure-rule-list-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/knowledge/knowledge-base-view.xml
  - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/vectorstore/vector-store-debug-view.xml
  - ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposurePolicyIntegrationTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposurePolicyTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/orchestration/BaselineContextProviderTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/performance/ToolQueryCountBaselineTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderDenylistTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/security/FilteredSchemaAndExecutionDenialTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/test_support/NoCustomerReadRoleConfiguration.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/FetchPlanIntersectorTest.java
  - ai-agent/ai-agent/src/test/java/com/vn/agent/tools/UnknownEntityRetryHintTest.java
findings:
  critical: 1
  warning: 2
  info: 0
  total: 3
status: issues_found
---

# Phase 10: Code Review Report

**Reviewed:** 2026-04-28T04:44:23Z
**Depth:** standard
**Files Reviewed:** 41
**Status:** issues_found

## Summary

Re-reviewed the listed files after the iteration-2 fixes and treated `.planning/phases/10-ai-specific-llm-exposure-policy/10-REVIEW-FIX.md` as current fix context. The seven iteration-2 findings are fixed in the current code. This pass found one remaining BLOCKER in the structured-filter path, plus two WARNING-level robustness/UI defects.

## Critical Issues

### CR-01: BLOCKER - Structured Filters Can Still Traverse Denylisted Relationship Targets

**Classification:** BLOCKER
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java:188`

**Issue:** `find_records` and `count_records` resolve the root entity through `LlmExposurePolicy`, but then hand the caller-supplied filter to `StructuredFilterConditionMapper` (also at line 226). That mapper still validates dotted paths through `CurrentUserSchemaAccess` rather than the exposure policy (`StructuredFilterConditionMapper.java:189-195`). A denylisted-but-Jmix-readable relationship target can therefore be used in filters such as `customer.name == 'Alice'`, letting the LLM infer hidden entity data from which visible root rows match.

**Fix:**

```java
// In StructuredFilterConditionMapper, inject LlmExposurePolicy instead of
// CurrentUserSchemaAccess and enforce it for every path hop.
if (!llmExposurePolicy.canReadAttribute(currentMetaClass, segment)) {
    throw new ToolUserError("unknown_attribute",
            "no attribute " + segment + " on " + currentMetaClass.getName());
}
if (currentProperty.getRange().isClass() && i < segments.length - 1) {
    MetaClass nextMetaClass = currentProperty.getRange().asClass();
    if (!llmExposurePolicy.canReadEntity(nextMetaClass)) {
        throw new ToolUserError("unknown_attribute",
                "no attribute " + segment + " on " + currentMetaClass.getName());
    }
    currentMetaClass = nextMetaClass;
}
```

Add a regression test where a visible root entity has a relationship to a denylisted target and `find_records` with a dotted filter path is rejected opaquely.

## Warnings

### WR-01: WARNING - Reingest Scheduling Failure Cannot Roll Back The Metadata Edit

**Classification:** WARNING
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java:181`

**Issue:** `updatePermissionsAndReingest()` documents that scheduling failure rolls back the metadata edit (line 152), but scheduling happens later in `TransactionSynchronization.afterCommit()` (line 203). If the async executor rejects the task or is shutting down, the document row is already committed as `PENDING` and old chunks have already been purged, leaving no worker to rebuild them and no immediate failed status for operators.

**Fix:** Catch scheduling failures in the after-commit callback and mark the document failed, or move to a durable outbox/work-queue record committed with the document update.

```java
@Override
public void afterCommit() {
    try {
        asyncIngestionWorker.ingest(documentId);
    } catch (RuntimeException ex) {
        ingestionStatusWriter.markFailed(documentId,
                "Reingest could not be scheduled: " + ex.getMessage());
    }
}
```

Also update the method Javadoc so it no longer promises rollback for failures that occur after commit.

### WR-02: WARNING - Vector Store Filter Parse Errors May Not Be Visible Inline

**Classification:** WARNING
**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/vectorstore/VectorStoreDebugView.java:168`

**Issue:** The parse-error path sets only `metadataFilterField.setErrorMessage(...)` and returns. Vaadin/Jmix text fields show the error message as validation feedback only when the field is marked invalid; clearing also only resets the message at lines 154 and 183. The debug view can therefore silently refuse to reload on an invalid filter without the promised inline validation cue.

**Fix:**

```java
metadataFilterField.setInvalid(false);
metadataFilterField.setErrorMessage(null);

try {
    Filter.Expression expression = new FilterExpressionTextParser().parse(filterText);
    requestBuilder.filterExpression(expression);
} catch (Exception ex) {
    metadataFilterField.setErrorMessage(
            messages.getMessage("vectorStoreDebug.error.filterParse"));
    metadataFilterField.setInvalid(true);
    return;
}
```

---

_Reviewed: 2026-04-28T04:44:23Z_
_Reviewer: the agent (gsd-code-reviewer)_
_Depth: standard_
