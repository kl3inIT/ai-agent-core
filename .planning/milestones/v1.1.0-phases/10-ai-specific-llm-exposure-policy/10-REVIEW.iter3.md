---
phase: 10-ai-specific-llm-exposure-policy
reviewed: 2026-04-28T04:13:45Z
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
  critical: 5
  warning: 2
  info: 0
  total: 7
status: issues_found
---

# Phase 10: Code Review Report

**Reviewed:** 2026-04-28T04:13:45Z
**Depth:** standard
**Files Reviewed:** 41
**Status:** issues_found

## Summary

Re-reviewed the listed files after the iteration-1 fixes, using the fix report as context and checking the current implementation rather than restating resolved items. The first-pass fixes landed, but the current code still has security and correctness defects in the RAG exposure filter, role metadata encoding, permission reingest transaction flow, and the exposure rule list descriptor.

## Critical Issues

### CR-01: BLOCKER - Admin RAG Bypass Skips The Exposure Denylist

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java:83`

**Issue:** `buildFor()` returns `null` for `ai-agent-admin` / `system-full-access` before it ever reads `llmExposurePolicy.getDenylistedEntityNames()` (lines 83-88). `DefaultChatServiceImpl` omits `VectorStoreDocumentRetriever.FILTER_EXPRESSION` when this method returns `null`, so an admin chat turn can retrieve chunks whose `source_entity` is denylisted. Exposure rules are supposed to narrow the LLM-visible surface as `current user visibility AND NOT excluded`; this branch removes the `NOT excluded` part for the users most likely to have broad Jmix access.

**Fix:**

```java
Set<String> denied = llmExposurePolicy.getDenylistedEntityNames();
boolean adminBypass = ragProps.isAdminBypass()
        && (roles.contains(AiAgentAdminRole.CODE)
        || roles.contains(JMIX_SYSTEM_FULL_ACCESS_ROLE_CODE));

FilterExpressionBuilder b = new FilterExpressionBuilder();
String currentModel = embeddingProps.resolvedModel();
FilterExpressionBuilder.Op modelPin = b.eq(ChunkMetadata.EMBEDDING_MODEL, currentModel);

FilterExpressionBuilder.Op exposureClause = denied.isEmpty()
        ? null
        : b.or(b.isNull(ChunkMetadata.SOURCE_ENTITY),
               b.nin(ChunkMetadata.SOURCE_ENTITY, new ArrayList<>(denied)));

if (adminBypass) {
    return exposureClause == null ? null : b.and(modelPin, exposureClause).build();
}
```

Update `RetrievalFilterBuilderDenylistTest.whenAdminUser_thenBuildForReturnsNull()` so admin bypass only bypasses role overlap, not exposure-denylist filtering.

### CR-02: BLOCKER - Row-Level Role Authorities Can Satisfy Resource-Role Retrieval Checks

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java:145`

**Issue:** `toRoleCode()` maps both `ROLE_...` and `ROW_LEVEL_ROLE_...` authorities into the same role-code namespace (lines 145-154). Knowledge document visibility is validated against `ResourceRoleRepository`, so the filter must only trust resource-role authorities. As written, a row-level role code that collides with a resource role, including `ai-agent-admin`, can satisfy a document's `role_*` flag or trigger the admin bypass.

**Fix:**

```java
private static String toResourceRoleCode(String authority) {
    if (authority == null || authority.isBlank()) {
        return "";
    }
    if (authority.startsWith(JMIX_ROW_LEVEL_ROLE_AUTHORITY_PREFIX)) {
        return "";
    }
    if (authority.startsWith(JMIX_RESOURCE_ROLE_AUTHORITY_PREFIX)) {
        return authority.substring(JMIX_RESOURCE_ROLE_AUTHORITY_PREFIX.length())
                .toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-');
    }
    return "";
}
```

Then filter blanks before building role clauses and admin-bypass checks.

### CR-03: BLOCKER - Role Metadata Keys Are Collision-Prone

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/ChunkMetadata.java:61`

**Issue:** `normalizeRoleCode()` lowercases role codes and replaces every non `[a-z0-9_]` character with `_` (lines 61-67). That makes distinct valid role codes such as `sales-admin` and `sales_admin` share the same metadata key, `role_sales_admin`. Ingestion writes only that flattened boolean key, and retrieval queries the same key, so a user with one colliding role can retrieve documents intended for the other.

**Fix:**

```java
public static String roleFlagKey(String roleCode) {
    byte[] bytes = roleCode.getBytes(StandardCharsets.UTF_8);
    return ROLE_FLAG_PREFIX + HexFormat.of().formatHex(bytes);
}
```

Use a reversible safe encoding for metadata keys, or reject any upload/update whose selected roles collide after normalization by checking all configured resource roles. Existing chunks must be reingested after changing the key scheme.

### CR-04: BLOCKER - Permission Update Reingest Uses A Nested New Transaction On The Same Row

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java:164`

**Issue:** `updatePermissionsAndReingest()` loads and mutates the document, calls `dataManager.save(doc)` inside the class-level transaction, then self-invokes `reingest()` (lines 169-180). `reingest()` calls `ingestionStatusWriter.markPending(documentId)` before the outer transaction commits (line 130), and that writer runs in `REQUIRES_NEW` and saves the same `AiKnowledgeDocument` row. This can deadlock on the row lock or increment the version in the nested transaction so the outer metadata update fails with optimistic locking after chunks have already been purged.

**Fix:**

```java
public UpdatePermissionsResult updatePermissionsAndReingest(UUID documentId,
                                                            Collection<String> allowedRoles,
                                                            @Nullable String sourceEntityName) {
    AiKnowledgeDocument doc = loadOrThrow(documentId);
    List<String> roles = KnowledgeDocumentRoleValidator.validateRoleCodes(allowedRoles, roleRepository);

    cancellationRegistry.bumpGeneration(documentId);
    cancellationRegistry.clear(documentId);
    vectorStore.delete(documentIdFilter(documentId));

    doc.setAllowedRolesJson(writeRolesJson(roles));
    doc.setSourceEntityName(sourceEntityName);
    doc.setStatus(AiKnowledgeDocumentStatus.PENDING);
    doc.setErrorMessage(null);
    doc.setIngestedAt(null);
    dataManager.save(doc);

    registerAfterCommit(() -> asyncIngestionWorker.ingest(documentId));
    return new UpdatePermissionsResult(UpdatePermissionsResult.Status.SAVED_AND_REINGESTING);
}
```

Keep the metadata update, status reset, and optimistic-lock version update in one transaction for this path; reserve `REQUIRES_NEW` status writes for the async worker after commit.

### CR-05: BLOCKER - Exposure Rule List Descriptor Still Has An Invalid GenericFilter Configuration

**File:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/exposure/ai-exposure-rule-list-view.xml:22`

**Issue:** The previous fix added `id="default"` to the `genericFilter` configuration, but JetBrains/Jmix inspection reports `default` is not a valid Java identifier because it is a Java keyword. The descriptor still has a concrete design-time error and can fail view processing.

**Fix:**

```xml
<configuration id="defaultConfiguration" default="true" name="default">
    <propertyFilter property="entityName" operation="CONTAINS"/>
    <propertyFilter property="enabled" operation="EQUAL"/>
</configuration>
```

Re-run `get_file_problems` on the descriptor after renaming the configuration id.

## Warnings

### WR-01: WARNING - Edit-Permissions Error Message Now Lies About Rollback

**File:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties:354`

**Issue:** `KnowledgeBaseView` catches failures from `updatePermissionsAndReingest()` and displays `knowledgeBase.error.editPermissionsReingest`, but the service now propagates reingest failures so the metadata edit rolls back. The English and Vietnamese messages still say permissions were saved and only reingest failed (`messages_en.properties:354`, `messages_vi.properties:356`), which gives admins the wrong remediation path.

**Fix:** Update both locale files to match the transaction behavior, for example:

```properties
knowledgeBase.error.editPermissionsReingest=Permissions were not saved because reingest could not be scheduled. No chunks were changed.
```

### WR-02: WARNING - Upload Validation Trims The URI But Persists The Untrimmed Value

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java:183`

**Issue:** `validateSourceUri()` validates `sourceUri.trim()` (lines 183-189), but `upload()` persists the original `sourceUri` into `AiKnowledgeDocument.fileName` (line 141). A whitespace-padded `file:` or `classpath:` URI can pass validation for the trimmed value and then fail async ingestion because `AsyncIngestionWorker` reads the untrimmed persisted value.

**Fix:**

```java
String normalizedSourceUri = sourceUri.trim();
validateSourceUri(normalizedSourceUri);
document.setFileName(normalizedSourceUri);
```

Better, make validation return the normalized URI so the value being validated and the value being persisted cannot diverge.

---

_Reviewed: 2026-04-28T04:13:45Z_
_Reviewer: the agent (gsd-code-reviewer)_
_Depth: standard_
