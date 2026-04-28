---
phase: 10-ai-specific-llm-exposure-policy
reviewed: 2026-04-28T03:35:01Z
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
  critical: 4
  warning: 4
  info: 0
  total: 8
status: issues_found
---

# Phase 10: Code Review Report

**Reviewed:** 2026-04-28T03:35:01Z
**Depth:** standard
**Files Reviewed:** 41
**Status:** issues_found

## Summary

Post-fix review found four blockers. Two are direct Phase 10 exposure-policy leaks: relationship metadata can still reveal denylisted entity names, and failed KB reingest setup can leave stale chunks retrievable under old metadata after permissions/source-entity edits. The upload service also has a classpath traversal gap, and the exposure-rule list descriptor has a Jmix XML error that can break the admin UI.

## Critical Issues

### CR-01: BLOCKER - Denylisted Entities Leak Through Relationship Metadata

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java:156`

**Issue:** `describe_entity` resolves the root entity through `LlmExposurePolicy`, but then passes the unfiltered readable attribute set directly to `ToolResultFormatter.describe(...)`. For readable relationships whose target entity is denylisted, `ToolResultFormatter` still emits `attributeType=ref:<target>` and `relationshipTarget.name=<target>`. `get_related_records` also returns `unknown_entity` with the target entity name when the relationship target is denied (lines 285-290), revealing a name the LLM should not know.

**Fix:**
```java
private Set<String> llmReadableAttributes(MetaClass metaClass, Set<String> readableAttributeNames) {
    return readableAttributeNames.stream()
            .filter(attributeName -> {
                MetaProperty property = metaClass.findProperty(attributeName);
                return property == null
                        || !property.getRange().isClass()
                        || llmExposurePolicy.canReadEntity(property.getRange().asClass());
            })
            .collect(Collectors.toCollection(LinkedHashSet::new));
}

// In describeEntity:
Set<String> readableAttributeNames = llmReadableAttributes(metaClass, schemaAttributes);
```

Also change the denied-target branch in `getRelatedRecords` to surface as `unknown_attribute` for the requested relationship, not `unknown_entity` for the hidden target.

### CR-02: BLOCKER - Permission Edits Commit While Stale Chunks Remain Retrievable

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java:176`

**Issue:** `updatePermissionsAndReingest()` saves new `allowedRolesJson` / `sourceEntityName`, then catches any `reingest()` failure and still returns normally. If `vectorStore.delete(...)` fails, the transaction commits the stricter document metadata while old pgvector chunks remain visible with old role/source metadata. Marking the document `FAILED` does not affect `RetrievalFilterBuilder`, which filters only chunk metadata, so this can keep restricted content retrievable until a manual reingest succeeds.

**Fix:**
```java
dataManager.save(doc);

try {
    reingest(documentId);
} catch (RuntimeException ex) {
    // Roll back the document metadata update. If chunks were already purged,
    // this fails closed; if purge failed, old chunks still match old metadata.
    throw ex;
}
return new UpdatePermissionsResult(UpdatePermissionsResult.Status.SAVED_AND_REINGESTING);
```

If partial-save UX is required, add a retrieval-time fail-closed marker to every stale chunk or a separate deny filter that excludes failed document ids before committing metadata.

### CR-03: BLOCKER - Classpath Upload Allowlist Can Be Bypassed With `..`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java:215`

**Issue:** Classpath URI validation is a raw `startsWith(prefix)` check. Inputs like `classpath:ai-kb/../application.properties` and bare `../application.properties` pass because the string starts with `classpath:ai-kb/` after the bare-name rewrite (lines 252-256). Spring classpath resource resolution normalizes `..`, so this can ingest resources outside the intended KB directory, including application configuration or other classpath secrets.

**Fix:**
```java
private void validateClasspathUri(String location) {
    String normalized = org.springframework.util.StringUtils.cleanPath(location);
    if (normalized.contains("../") || normalized.startsWith("../")) {
        throw new IllegalArgumentException("classpath sourceUri must not contain path traversal");
    }
    boolean allowed = effectiveClasspathAllowedPrefixes().stream()
            .map(org.springframework.util.StringUtils::cleanPath)
            .anyMatch(normalized::startsWith);
    if (!allowed) {
        throw new IllegalArgumentException("sourceUri classpath location is not allowed: " + location);
    }
}
```

Apply the same normalization to the explicit `classpath:` branch and the bare-filename branch before accepting the upload.

### CR-04: BLOCKER - Exposure Rule List Descriptor Has Invalid GenericFilter Configuration

**File:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/exposure/ai-exposure-rule-list-view.xml:22`

**Issue:** The `<configuration>` element under `<genericFilter>` has `name="default"` but no required `id`. JetBrains Jmix inspection reports this as an XML error. This can prevent the exposure-rule list view from loading, which blocks the admin governance surface for Phase 10.

**Fix:**
```xml
<configuration id="default" default="true" name="default">
    <propertyFilter property="entityName" operation="CONTAINS"/>
    <propertyFilter property="enabled" operation="EQUAL"/>
</configuration>
```

## Warnings

### WR-01: WARNING - Root Bundle Messages Are Looked Up With Package-Scoped Keys

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/AiExposureRuleListView.java:96`

**Issue:** `messages.getMessage(getClass(), "...")` is used for keys stored in the root `com.vn.agent/messages_*.properties` bundle. JetBrains reports missing keys such as `com.vn.agent.view.exposure/exposureRulesList.action.hideFromAi`. The same pattern is repeated in `VectorStoreDebugView.java` at lines 96, 113, 155, 189, 206, 210, 218, and 232. These admin views will show unresolved labels/tooltips or fail localization.

**Fix:** Use root-bundle lookups consistently, matching the fixed `KnowledgeBaseView` pattern:
```java
btn.setText(messages.getMessage("exposureRulesList.action.hideFromAi"));
metadataFilterField.setErrorMessage(messages.getMessage("vectorStoreDebug.error.filterParse"));
```

### WR-02: WARNING - Post-Ingest Permission Edits Bypass Role Validation

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java:167`

**Issue:** Uploads validate every role code through `ResourceRoleRepository`, but `updatePermissionsAndReingest()` serializes whatever `allowedRoles` it receives. The current UI supplies checkbox values, but the service is the business boundary and can persist stale, misspelled, null, blank, or system role codes if called from another controller/test/host bean. That creates inaccessible or misleading chunk role metadata after reingest.

**Fix:** Move role validation into a shared service helper and use it for both upload and update:
```java
List<String> roles = validateRoleCodes(allowedRoles);
doc.setAllowedRolesJson(writeRolesJson(roles));
```

Filter null/blank entries deliberately or reject them with `UnknownRoleCodeException` before saving.

### WR-03: WARNING - Vector Store Debug View Is Not Actually Paginated

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/vectorstore/VectorStoreDebugView.java:143`

**Issue:** The view is documented and scoped as a paginated debug grid, but the implementation always executes one `similaritySearch` with `topK(100)` and replaces grid items. There is no offset, "load more", or page control in `vector-store-debug-view.xml`, so admins cannot inspect chunks beyond the first 100.

**Fix:** Add explicit pagination or load-more state:
```java
private int pageSize = 100;
private int loadedLimit = 100;

// On Load more:
loadedLimit += pageSize;
SearchRequest request = SearchRequest.builder()
        .query("")
        .topK(loadedLimit)
        .similarityThreshold(0.0)
        .filterExpression(parsedFilter)
        .build();
```

If true pagination is not possible with the active `VectorStore`, rename the UI copy and phase artifact from "paginated" to "top-100 preview" so operators do not rely on it for full inspection.

### WR-04: WARNING - `KnowledgeDocumentUploadService` Keeps an Unused Dependency

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java:75`

**Issue:** `embeddingProperties` is injected and assigned but never read after the previous dead `resolvedModel()` call was removed. The remaining dependency suggests upload consumes embedding model state when it does not, and IDE inspection flags it as unused.

**Fix:** Remove the field and constructor parameter unless upload validation really needs this dependency:
```java
public KnowledgeDocumentUploadService(Metadata metadata,
                                      DataManager dataManager,
                                      ResourceRoleRepository roleRepository,
                                      AsyncIngestionWorker asyncIngestionWorker,
                                      AiAgentRagProperties ragProperties) {
    ...
}
```

---

_Reviewed: 2026-04-28T03:35:01Z_
_Reviewer: the agent (gsd-code-reviewer)_
_Depth: standard_
