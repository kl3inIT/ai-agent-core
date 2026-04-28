---
phase: 10-ai-specific-llm-exposure-policy
fixed_at: 2026-04-28T10:30:00Z
review_path: .planning/phases/10-ai-specific-llm-exposure-policy/10-REVIEW.md
iteration: 1
findings_in_scope: 13
fixed: 13
skipped: 0
status: all_fixed
---

# Phase 10: Code Review Fix Report

**Fixed at:** 2026-04-28T10:30:00Z
**Source review:** .planning/phases/10-ai-specific-llm-exposure-policy/10-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 13 (2 BLOCKER + 11 WARNING)
- Fixed: 13
- Skipped: 0

**Verification:** `./gradlew :ai-agent:ai-agent:test` — BUILD SUCCESSFUL (2m 49s, all tests pass).

## Fixed Issues

### BLOCKER-01: `enrich()` throws NPE on null element in `allowedRoles`

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`
**Commit:** 7adeed8
**Applied fix:** Filter null/blank entries from `allowedRoles` BEFORE `List.copyOf(...)` (which throws NPE on any null element). The downstream role-flag loop already skipped null/blank entries; the canonical `ALLOWED_ROLES` list now mirrors the same shape so the two views agree.

### BLOCKER-02: Liquibase 060 does not produce JPA-declared `IDX_AI_EXPOSURE_RULE_ENTITY_NAME`

**Files modified:**
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/liquibase/agentstore-changelog/060-ai-exposure-rule.xml`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/exposure/AiExposureRule.java`

**Commit:** a53b5ca
**Applied fix:** Replaced `addUniqueConstraint(UNQ_AI_EXPOSURE_RULE_ENTITY_NAME)` with `createIndex(IDX_AI_EXPOSURE_RULE_ENTITY_NAME, unique=true)` so the Liquibase-managed index name matches the JPA `@Index` declaration. Also dropped the redundant column-level `unique=true` on `entityName` so JPA providers do not emit a parallel unique constraint with a generated name. Liquibase remains the single source of truth.

### WARNING-01: `updatePermissionsAndReingest` leaves stale chunks visible on partial failure

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentService.java`
**Commit:** c1bb1d1
**Applied fix:** When the doc save commits but the reingest enqueue throws, mark the document `FAILED` via `IngestionStatusWriter.markFailed(...)` (REQUIRES_NEW) so the admin UI flags the row and the `DocumentStatusChangedEvent` push listener fires a refresh. Admins remediate via the Reingest action — same user-facing message — but the dangerous mid-state is now observable instead of silent.

### WARNING-02: Edit-permissions dialog exposes ALL roles including `system-full-access`

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`
**Commit:** be62691
**Applied fix:** Filter the role list shown in the edit-permissions dialog to exclude codes prefixed `system-`. New private helper `isSystemRole(String)` is reused by the upload-form fix in WARNING-08.

### WARNING-03: Conflicting upload mechanisms in `KnowledgeBaseView`

**Files modified:** `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/knowledge/knowledge-base-view.xml`
**Commit:** 298cb95
**Applied fix:** Removed `receiverType="MultiFileTemporaryStorageBuffer"` from the XML. The controller already wires `UploadHandler.toFile(...)` (the non-deprecated path); the deprecated `Upload.setReceiver(...)` path is no longer triggered.

### WARNING-04: Optimistic-lock failure on consecutive toggle clicks

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/exposure/AiExposureRuleListView.java`
**Commit:** dbf81a8
**Applied fix:** Capture the `UnconstrainedDataManager.save(...)` return value to read the durable `enabled` state for the post-save notification. Added an `OptimisticLockException` catch branch that reloads the grid first so the action-button renderer rebinds against a fresh row, then surfaces the toast.

### WARNING-05: Dead `embeddingProperties.resolvedModel()` call in upload service

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/KnowledgeDocumentUploadService.java`
**Commit:** bf4c65e
**Applied fix:** Replaced the side-effect-free `embeddingProperties.resolvedModel();` call with a clarifying comment. Documentation that the coupling exists belongs in a comment, not a discarded expression.

### WARNING-06: Inconsistent message-lookup style in `KnowledgeBaseView`

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`
**Commit:** 9bc1fa4
**Applied fix:** Converted all `messages.getMessage(getClass(), key)` calls in this view to the package-default `messages.getMessage(key)` overload. Project convention (memory `feedback_jmix_messages_over_spring`) keeps keys in the root bundle, so the `getClass()`-anchored lookup adds nothing and is a footgun if anyone later splits keys per package. The whole class now uses one style for static text and parameterised lookups.

### WARNING-07: `confirmAndSavePermissions` does not catch `DocumentNotFoundException`

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`
**Commit:** 9bc1fa4 (combined with WARNING-06 — same method)
**Applied fix:** Wrapped `documentService.updatePermissionsAndReingest(...)` in a try/catch inside `confirmAndSavePermissions` so any service-layer exception (including `DocumentNotFoundException`) closes the dialog with an error toast instead of leaving the user staring at an open dialog with no feedback. Mirrors the pattern in `onReingestClick` and `onDeleteClick`.

### WARNING-08: KB upload form has NO `allowedRoles` field

**Files modified:**
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/view/knowledge/knowledge-base-view.xml`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/knowledge/KnowledgeBaseView.java`
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties`
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties`

**Commit:** eb494d8
**Applied fix:** Added a `<checkboxGroup id="uploadAllowedRolesGroup">` to the upload form, populated in `onInit` from the same filtered role list used in the edit dialog (system-* excluded — WARNING-02). Pass the selection through to `uploadService.upload(...)`. Added helper text key `knowledgeBase.upload.field.allowedRoles.helper` to BOTH locale bundles (project rule: every UI text in en + vi). Empty selection now means admin-only-visible (matches D-05 fail-closed contract) but the helper text makes that explicit.

### WARNING-09: `LlmExposureRuleRepository` lacks explicit agentstore routing test

**Files modified:** `ai-agent/ai-agent/src/test/java/com/vn/agent/exposure/LlmExposureRuleRepositoryIntegrationTest.java` (new file)
**Commit:** 5ed4ae5
**Applied fix:** Added a two-test integration fixture (`@SpringBootTest` + `AITestConfiguration`) that seeds an `AiExposureRule` via `UnconstrainedDataManager` and asserts the repository surfaces it (and that disabled rules are filtered out). A future accidental switch to raw `loadValue/loadValues` — which would silently route to the main datasource — fails this test loudly.

### WARNING-10: `AsyncIngestionWorker.enrich()` does not handle null `doc.getId()`

**Files modified:** `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/AsyncIngestionWorker.java`
**Commit:** 16bc932
**Applied fix:** Added `Objects.requireNonNull(doc.getId(), "document id must not be null")` at the top of `enrich(...)`. DOCUMENT_ID is the join key for delete/reingest; a null id from a future alternate dispatch path would silently break those flows.

### WARNING-11: `RetrievalFilterBuilder` per-role model-pin multiplication is undocumented

**Files modified:**
- `ai-agent/ai-agent/src/main/java/com/vn/agent/rag/RetrievalFilterBuilder.java`
- `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RetrievalFilterBuilderRoleScalingGuardTest.java` (new file)

**Commit:** 95df7c9
**Applied fix:** Took the review's option #2 (the safer one) — added a class-level Javadoc scaling note that explains the intentional `(model AND role)` per-role duplication, and added a guard test that pins the model-pin occurrence count for a five-role caller. Any future flatten-the-OR refactor will fail the test, forcing the maintainer to update both the Javadoc and the assertion in a deliberate change.

## Skipped Issues

None. All in-scope findings were fixed.

## Verification

- All 13 fixes committed atomically with `fix(10): ...` prefix.
- Two findings (WARNING-06 and WARNING-07) shared a single commit because they touched the same `confirmAndSavePermissions` method — combining them avoided a noisy back-and-forth diff. Both are documented in the commit message.
- Tier 1 (re-read) performed for every fix.
- Tier 2 (full test build) performed once at the end: `./gradlew :ai-agent:ai-agent:test` → BUILD SUCCESSFUL.
- New tests added for WARNING-09 and WARNING-11 confirm regression gates around agentstore routing and RAG filter scaling.
- WARNING-01 (status sentinel on partial failure) is a logic-touching change. The selected approach (`markFailed` in REQUIRES_NEW) is straightforward and aligns with the existing IngestionStatusWriter pattern, but a human should sanity-check that flagging the document FAILED on reingest-enqueue failure is the right user-facing semantics for this enterprise app.

---

_Fixed: 2026-04-28T10:30:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
