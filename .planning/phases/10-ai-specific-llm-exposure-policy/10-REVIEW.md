---
phase: 10-ai-specific-llm-exposure-policy
reviewed: 2026-04-28T00:00:00Z
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
  blocker: 2
  warning: 11
  total: 13
status: issues_found
---

# Phase 10: Code Review Report

**Reviewed:** 2026-04-28
**Depth:** standard
**Files Reviewed:** 41
**Status:** issues_found

## Summary

The Phase 10 LLM exposure policy implementation is generally well-structured. The central security
invariant (uniform `unknown_entity` opacity across all four LLM-facing surfaces) is well-pinned by
the integration test suite, the delegate-and-narrow shape of `LlmExposurePolicy` is correctly
applied, the admin-bypass branch in `RetrievalFilterBuilder` is structurally above the policy
lookup, and the defensive `(IS NULL OR NOT IN ...)` pgvector filter form is verified by a
dedicated unit test. The single-publish-site invariant for `LlmExposureChangedEvent` is documented
in three places and respected by the toggle action.

However, two issues are real correctness bugs that can deny service or fail uploads silently, and
several quality issues weaken either the schema migration story or the upload UX. The most
serious findings:

- **BLOCKER**: `AsyncIngestionWorker.enrich()` calls `List.copyOf(allowedRoles)` which throws NPE
  on any null element returned by `parseAllowedRoles`, killing ingestion of any document whose
  `allowedRolesJson` deserializes a null entry.
- **BLOCKER**: Liquibase changelog `060-ai-exposure-rule.xml` does not create the
  `IDX_AI_EXPOSURE_RULE_ENTITY_NAME` index that the `@Table` annotation declares; the entity
  declares it as `unique=true`, but the changelog creates a different-named unique constraint
  (`UNQ_AI_EXPOSURE_RULE_ENTITY_NAME`). On Liquibase-managed databases the JPA-declared index name
  is never produced, leading to potential duplicate-constraint errors under JPA schema validation
  and broken DDL parity across environments.

The warnings cover: a silent partial-failure window in `KnowledgeDocumentService
.updatePermissionsAndReingest` that leaves stale chunks visible, an unfiltered role list in the
"Edit permissions" dialog (selectable system roles like `system-full-access`), conflicting upload
configuration in the KB view (`receiverType=` XML + programmatic `setUploadHandler`), an
optimistic-lock pitfall on the toggle action, dead `embeddingProperties.resolvedModel()` call,
inconsistent `formatMessage` vs `getMessage(getClass(), ...)` lookup style, and a few migration /
defaults hygiene items.

## Blocker Issues

### BLOCKER-01: `enrich()` throws NPE on null element in `allowedRoles`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java:282`
**Issue:** `parseAllowedRoles` deserializes JSON via `JSON.readValue(json, new TypeReference<>() {})`
which can return a `List<String>` containing null elements (e.g., if a host inserts
`["ai-agent-user", null]` directly into the column, or if a future serializer emits nulls). The
loop on line 283 has a null/blank guard for the role-flag generation, but
`merged.put(ChunkMetadata.ALLOWED_ROLES, List.copyOf(allowedRoles))` on line 282 happens BEFORE
that loop and `List.copyOf` is documented to throw NPE on any null element. The whole ingest
fails, the catch block at line 179 marks status FAILED with the cryptic
`NullPointerException: ...`, and the document is unusable.

**Fix:**
```java
// Filter nulls/blanks before storing the canonical list, mirroring the loop guard below.
List<String> safeAllowedRoles = allowedRoles.stream()
        .filter(r -> r != null && !r.isBlank())
        .toList();
merged.put(ChunkMetadata.ALLOWED_ROLES, safeAllowedRoles);
for (String role : safeAllowedRoles) {
    merged.put(ChunkMetadata.roleFlagKey(role), true);
}
```
Or harden `parseAllowedRoles` to drop null/blank entries during deserialization.

### BLOCKER-02: Liquibase 060 does not produce the JPA-declared `IDX_AI_EXPOSURE_RULE_ENTITY_NAME`

**File:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/060-ai-exposure-rule.xml:32-40`
**Issue:** The `AiExposureRule` `@Table` annotation declares
`@Index(name = "IDX_AI_EXPOSURE_RULE_ENTITY_NAME", columnList = "ENTITY_NAME", unique = true)`,
but the changelog creates a unique constraint named `UNQ_AI_EXPOSURE_RULE_ENTITY_NAME` instead.
There is no Liquibase changeSet that produces an index with the JPA-declared name. Two
consequences:
1. On databases with `hibernate.hbm2ddl.auto` or Jmix's schema validator running,
   the validator will report a missing `IDX_AI_EXPOSURE_RULE_ENTITY_NAME` index and may try to
   create it — producing a duplicate unique constraint clash with the Liquibase-created
   `UNQ_AI_EXPOSURE_RULE_ENTITY_NAME`.
2. DDL parity across environments breaks: a developer machine that triggers JPA schema-update
   sees both names; CI / production using only Liquibase sees only `UNQ_*`.

The same issue does not affect `IDX_AI_EXPOSURE_RULE_ENABLED` because the changelog `<createIndex>`
matches the JPA-declared index name byte-for-byte.

**Fix:** Replace the `<addUniqueConstraint>` with a `<createIndex unique="true">` whose name
matches the JPA annotation:
```xml
<changeSet id="2" author="ai-agent">
    <createIndex indexName="IDX_AI_EXPOSURE_RULE_ENTITY_NAME"
                 tableName="AI_EXPOSURE_RULE"
                 unique="true">
        <column name="ENTITY_NAME"/>
    </createIndex>
    <createIndex indexName="IDX_AI_EXPOSURE_RULE_ENABLED"
                 tableName="AI_EXPOSURE_RULE">
        <column name="ENABLED"/>
    </createIndex>
</changeSet>
```
Also remove `unique = true` from the JPA `@Index` annotation if you keep the `unique=true` index
in Liquibase, since some JPA providers will still emit a separate unique index for the explicit
annotation flag. Standardise on one source of truth (Liquibase) for the constraint name.

## Warnings

### WARNING-01: `updatePermissionsAndReingest` leaves stale chunks visible on partial failure

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java:161-186`
**Issue:** The class-level `@Transactional` (default `Propagation.REQUIRED`) wraps both
`dataManager.save(doc)` (line 174) and the call to `reingest(documentId)` (line 179). On the
happy path, `reingest()` purges chunks via `vectorStore.delete(...)` and resets status via
`ingestionStatusWriter.markPending(...)` (REQUIRES_NEW). If `vectorStore.delete` succeeds but
`markPending` fails, the catch returns `SAVED_REINGEST_FAILED` — but the outer `@Transactional`
still commits the save, so the document row carries the new `sourceEntityName` /
`allowedRolesJson` while the old chunks (with old `source_entity` / `role_*` metadata) remain in
pgvector. RAG retrieval can then return chunks whose `source_entity` no longer matches the
document's intent, partially defeating the EXP-05 NIN filter. The user-facing message says
"Use Reingest action to retry," which is correct, but there is no automated guard.

**Fix:** Prefer one of:
1. Move `vectorStore.delete` into the same `REQUIRES_NEW` boundary as the status reset and treat
   the entire reingest enqueue as transactional ("either both happen, or neither"); or
2. Mark the document with a sentinel status (e.g., `STALE_CHUNKS`) inside the catch so RAG
   retrieval can skip the document entirely until reingest succeeds.

### WARNING-02: Edit-permissions dialog exposes ALL roles including `system-full-access`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java:378-382`
**Issue:** The "Edit permissions" dialog populates the role checkbox group from
`resourceRoleRepository.getAllRoles()`. This includes Jmix system roles
(`system-full-access`, etc.) and other admin-only roles that are not intended targets for
"end-user can read this knowledge document." An admin can pin a document to `system-full-access`
only, making it invisible to all interactive users while still consuming embedding storage. The
upload form makes this even worse: it provides no role field at all, so every uploaded document
ships with empty `allowedRoles` and is invisible to non-admin users by default per the D-05 fail-closed retrieval contract.

**Fix:** Filter the displayed roles to a project-level allowlist (e.g., codes starting with
`ai-agent-` or roles annotated with a host-supplied `@AiKnowledgeRole` marker), and add an
"Allowed roles" field to the upload form so admins are not forced to upload-then-edit.

### WARNING-03: Conflicting upload mechanisms in `KnowledgeBaseView`

**File:**
- XML: `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/knowledge/knowledge-base-view.xml:30-37`
- Java: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java:158-177`

**Issue:** The XML declares `<upload ... receiverType="MultiFileTemporaryStorageBuffer" />` which
configures the upload through the deprecated Jmix `Upload.setReceiver(...)` path (see MEMORY
note `feedback_jmix_upload_receiver_deprecated.md` — `Upload.getReceiver/setReceiver` is
`@Deprecated(since="24.8", forRemoval=true)`). The Java controller then independently calls
`documentUpload.setUploadHandler(UploadHandler.toFile(...))`. Whether these two mechanisms
coexist or one silently disables the other is Vaadin-version-specific and brittle.

**Fix:** Remove `receiverType="MultiFileTemporaryStorageBuffer"` from the XML; the
`UploadHandler.toFile(...)` programmatic wiring is the non-deprecated path and is sufficient on
its own.

### WARNING-04: Optimistic-lock failure on consecutive toggle clicks

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/AiExposureRuleListView.java:121-137`
**Issue:** `unconstrainedDataManager.save(rule)` returns the freshly-persisted entity with the
incremented `@Version`, but the code discards the return value and continues to operate on the
in-memory `rule` instance whose `version` is stale. If the admin clicks the toggle button twice
in quick succession (and the grid has not reloaded between clicks), the second save will throw
`OptimisticLockException`. The catch surfaces a generic "Could not update exposure rule" toast,
masking what is actually a save against a stale version. The first click also reloads via
`exposureRulesDl.load()`, which should provide a fresh row — but the action button's renderer
captures the old `rule` reference, so the second click still acts on the stale instance until
the grid component fully rebinds.

**Fix:**
```java
private void toggleEnabled(AiExposureRule rule) {
    try {
        rule.setEnabled(!Boolean.TRUE.equals(rule.getEnabled()));
        AiExposureRule saved = unconstrainedDataManager.save(rule);
        // capture the freshly-persisted version to avoid stale-version reuse
        exposureRulesDl.load();
        // ...notification using saved.getEnabled()
    } catch (OptimisticLockException e) {
        // refresh + retry hint
    }
}
```

### WARNING-05: Dead call `embeddingProperties.resolvedModel()` in upload service

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java:165`
**Issue:** The line `embeddingProperties.resolvedModel();` discards its return value. The Javadoc
above admits "kept for future use and to document the coupling; it is intentionally not
persisted on the entity." A no-op call carries no protection — if the embedding model property
is later removed or renamed, the discarded call will not flag the breakage. Documentation that
the intended coupling exists belongs in a comment, not a side-effect-free expression.

**Fix:** Remove the call and replace with a single-line comment if the coupling note is
worth preserving:
```java
// embeddingProperties.resolvedModel() is read by AsyncIngestionWorker.enrich() during ingest
// and stamped on each chunk via ChunkMetadata.EMBEDDING_MODEL.
```

### WARNING-06: Inconsistent message-lookup style in `KnowledgeBaseView`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`
**Issue:** Some calls use `messages.formatMessage("knowledgeBase.confirm.reingest.title", ...)`
(line 346) while others in the same class use
`messages.getMessage(getClass(), "knowledgeBase.action.editPermissions")` (line 372). The
package-default overload and the class-anchored overload search different bundles in Jmix —
the project relies on all keys living in the root bundle (per MEMORY
`feedback_jmix_messages_over_spring`), but the inconsistency is a foot-gun: someone may
accidentally split keys into a per-package bundle later and only some lookups will follow.

**Fix:** Pick one style for the whole class. Project convention based on the surrounding code is
`messages.getMessage(getClass(), key)` for static text and `messages.formatMessage(getClass(),
key, args)` for parameterised lookups.

### WARNING-07: `confirmAndSavePermissions` does not catch `DocumentNotFoundException`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java:443-455`
**Issue:** `documentService.updatePermissionsAndReingest(...)` may throw
`DocumentNotFoundException` if the document was deleted between the dialog open and the OK click
(e.g., from another tab via the push refresh). The exception escapes the dialog handler, the
dialog stays open, and the user sees no error. The two sibling row-actions (`onReingestClick`
and `onDeleteClick`) both wrap `documentService` calls in try/catch but
`confirmAndSavePermissions` does not.

**Fix:**
```java
try {
    UpdatePermissionsResult result = documentService
            .updatePermissionsAndReingest(doc.getId(), roles, sourceEntityName);
    // ...switch on result.status() ...
    documentsDl.load();
    dialog.close();
} catch (DocumentNotFoundException ex) {
    log.warn("editPermissions target {} no longer exists", doc.getId(), ex);
    NotificationUtils.errorWithDetail(notifications, messages,
            "knowledgeBase.error.editPermissionsReingest", ex);
    dialog.close();
}
```

### WARNING-08: KB upload form has NO `allowedRoles` field — every doc invisible by default

**File:**
- View XML: `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/knowledge/knowledge-base-view.xml:18-37`
- Controller: `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java:165`

**Issue:** The upload handler hard-codes `Collections.emptyList()` for `allowedRoles`:
```java
uploadService.upload(stagedFile.toURI().toString(), metadata.contentType(),
        Collections.emptyList(), sourceEntityName);
```
Per `KnowledgeDocumentUploadService` contract: empty `allowedRoles` produces a document where
all chunks have NO role flags set, and the `RetrievalFilterBuilder` D-09 ANY semantics requires
at least one role-flag match — so no non-admin user ever sees the document. Admin bypass is
the only path that retrieves it. This is a footgun: every upload silently lands as
admin-only-visible. UI-SPEC promised an "Allowed roles" multi-select on the upload form (see
the existing `knowledgeBase.upload.field.allowedRoles` message key); the controller wires it for
the edit dialog but not for the upload form.

**Fix:** Add a `<checkboxGroup>` (or Vaadin `CheckboxGroup<String>`) to the upload form with the
filtered role list (see WARNING-02 for filtering), capture its value at upload time, and pass
through to `uploadService.upload`. Also surface a banner reminding the admin "Documents with no
roles selected are visible only to admins."

### WARNING-09: `LlmExposureRuleRepository` query string omits `agentstore` store hint

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/LlmExposureRuleRepository.java:34-42`
**Issue:** The repository uses `dataManager.load(AiExposureRule.class)` which auto-resolves the
store from `@Store(name="agentstore")` — that path is fine. The Javadoc explicitly notes that
the MEMORY `feedback_jmix_loadvalue_store` rule does not apply here. However, the comment is
correct only as long as no future maintainer switches to the raw `loadValue/loadValues` path
(which silently routes to the main datasource without an explicit `.store("agentstore")`). The
risk is low, but consider adding a regression test that exercises the multi-store case so future
edits do not silently break.

**Fix:** Add a one-line integration test asserting the query reads from `agentstore` (e.g., seed
a row via `UnconstrainedDataManager`, query via `LlmExposureRuleRepository`, expect the row).
The integration test `LlmExposurePolicyIntegrationTest` already does this transitively but the
intent is implicit; an explicit per-repository test prevents accidental store-routing regressions.

### WARNING-10: `AsyncIngestionWorker.enrich()` does not handle null `doc.getId()`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java:280`
**Issue:** `merged.put(ChunkMetadata.DOCUMENT_ID, doc.getId().toString())` — the document is
freshly loaded by id at line 130 and `dataManager.load(...)` returns a managed entity, so
`getId()` is non-null in practice. But a defensive `Objects.requireNonNull` would catch any
future regression where ingestion is called for a transient entity (e.g., from a unit test or
an alternate dispatch path).

**Fix:** Add a `Objects.requireNonNull(doc.getId(), "document id must not be null")` guard at
the top of `enrich()`.

### WARNING-11: `RetrievalFilterBuilder` expression construction concatenates per-role
`(model AND role)` with OR, repeating the model pin

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java:90-98`
**Issue:** The comment explicitly notes the model pin is repeated per role to "avoid relying on
converter parenthesization." That is a defensible workaround for a real Spring AI converter
quirk, but the resulting expression grows linearly with the number of roles a user holds. For a
user with N roles, the expression has N copies of the model-pin clause. Most pgvector converters
handle this fine, but the resulting JSONPath predicate may hit converter token limits for users
with very large role sets (e.g., 50+). Realistic for Phase 10's expected count? No. Worth a
defensive cap or a release-note that says "users with >50 roles may see degraded RAG performance"?
Yes.

**Fix:** Either:
1. Collapse to `model AND (roleA OR roleB OR ...)` once you confirm the converter handles it
   (test against pgvector + the chromadb / weaviate adapters); or
2. Document the per-role-multiplication behaviour in the class Javadoc with the expected
   maximum, and add a guard test that pins the count of clauses for a 5-role user.

## Notes (informational, not classified)

- The `MetaclassComboBoxHelper.AI_INTERNAL_ENTITY_NAMES` allowlist hard-codes six entity names.
  If a new AI-* entity ships in a later phase, the helper must be updated by hand or the entity
  will appear in the source-entity dropdown of the KB upload form. Consider deriving this from
  the package of the entity class (e.g., everything under `com.vn.agent.entity` or
  `com.vn.agent.exposure`) so the allowlist is automatic.
- `AiExposureRule.mode` defaults to `EXCLUDE` in two places: the Java field initializer
  (`= AiExposureRuleMode.EXCLUDE.getId()`) and the Liquibase column default
  (`defaultValue="EXCLUDE"`). Two sources of truth for the same constant. Future addition of a
  second mode will need to update both. Acceptable for v1.1 since only one mode exists.
- `KnowledgeBaseView.onEditPermissionsClick` constructs an inline Vaadin `Dialog` rather than a
  Jmix `@DialogMode` detail view — this is a deviation from the project's "Jmix-first UI" rule
  (MEMORY `feedback_jmix_first_ui`). Since the dialog content is not bound to a Jmix entity
  (it is a transient form over `Set<String>` + `MetaClass`), the deviation is justified, but
  worth re-evaluating once a Jmix-native pattern lands.

## Things that look right (worth preserving)

- `LlmExposurePolicy.getReadableSchema()` loads the denylist ONCE at the top and uses
  `removeIf` against the `Set<String>` — Pitfall #1 (per-entity DB call) is correctly avoided
  and the dedicated unit test pins this behaviour.
- The defensive `(source_entity IS NULL) OR (source_entity NOT IN <denied>)` pgvector filter
  shape is correctly implemented and pinned by `RetrievalFilterBuilderDenylistTest
  .whenDenylistNonEmptyAndChunkHasNoSourceEntityKey_thenChunkRemainsVisible`. The class-level
  comment explaining the converter-version rationale is excellent.
- The `AiExposureRuleEntityListener` single-publisher invariant for
  `LlmExposureChangedEvent` is documented in three places (the entity listener Javadoc, the
  event class Javadoc, and the list-view Javadoc) — each cross-references the others. Future
  maintainers cannot accidentally add a second publisher without tripping over the warning.
- The integration test seeds the rule via `UnconstrainedDataManager` (so test users without
  `AiExposureRule` policies can still drive the test), then removes by id in `@AfterEach`
  rather than wiping the table — preserves any host-supplied rules during test isolation.
- Uniform opacity (`unknown_entity` for non-existent + denied) is verified at four call sites
  (list_entities, agent.entities, find_records, RAG filter) by `LlmExposurePolicyIntegrationTest`.

---

_Reviewed: 2026-04-28T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
