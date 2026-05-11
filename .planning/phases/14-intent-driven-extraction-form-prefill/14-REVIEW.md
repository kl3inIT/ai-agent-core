---
phase: 14-intent-driven-extraction-form-prefill
reviewed: 2026-05-11
depth: standard
files_reviewed: 39
status: issues_found
findings:
  blocker: 0
  critical: 0
  warning: 6
  info: 5
  total: 11
supersedes: "earlier 14-REVIEW.md (2026-05-10, pre-ship gap-closure review)"
---

# Phase 14: Code Review Report

**Reviewed:** 2026-05-11 (pre-merge review of full phase branch, PR #28)
**Depth:** standard
**Files Reviewed:** 39 production source files (ai-agent + jmix-app `src/main`)
**Status:** issues_found

> This report supersedes the earlier `14-REVIEW.md` dated 2026-05-10, which reviewed only the 14-09/14-10 gap-closure diff. This pass covers the production source surface of the whole phase branch (diff base `a228717`).

## Summary

Phase 14 adds intent-driven LLM extraction → Jmix form prefill plus a server-validated `propose_action_choices` planning tool. The core security invariants hold: the LLM surface never receives `ViewNavigators` or a UI-mutation primitive (navigation is confined to `OpenFormWithDraftHandler`, which is not a `@Tool`); `AiExtractionDraft` is `agentstore`, row-level owner-scoped, hidden via `AiInternalEntityNames`, and TTL-reaped via `AiExtractionDraftCleanupJob` using `UnconstrainedDataManager`; prefill writes go through the permission-gated `EntityAttributeContext.canModify()` boundary in `DraftLoader`, with raw `EntityValues.setValue` confined to `DraftLoader.setValueIfPermitted`; the action-choice row only renders after a server-validated `READY` result (gated in `ToolCallbackAuditDecorator` + `StreamEventRenderer`). Messages exist in both locales; the changelog is auto-included via `includeAll`.

The defects below are robustness / UX-degradation issues, not security holes. No BLOCKER- or CRITICAL-class data-loss or auth-bypass defect was found. The WARNING-class items are worth fixing before merge; the INFO items are cleanup.

## Warnings

### WR-01: `OpenFormWithDraftHandler.afterNavigation` does not handle exceptions from `draftLoader.apply(...)`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java:156-176`
**Issue:** `afterNavigation` calls `draftLoader.apply(draftId, editedEntity)` with no try/catch. `DraftLoader.apply` can throw `DraftNotFoundException` (the draft can be reaped by the TTL job or expire between the user clicking "Open form to confirm" and the after-navigation callback firing), `ExtractionSchemaException` (malformed `payloadJson`), or `IllegalArgumentException` (entity mismatch). Any of these escape the `withAfterNavigationHandler` callback after the detail view has already opened, surfacing as a raw Vaadin internal-error dialog and leaving the user on a half-prepared form.
**Fix:** Wrap `draftLoader.apply` in try/catch; on `DraftNotFoundException` show `chatView.intent.draftExpired`, on other failures show `chatView.intent.configurationError`, and still register the lifecycle listeners (or close the view) so the UI stays consistent.

```java
DraftApplyResult applyResult;
try {
    applyResult = draftLoader.apply(draftId, editedEntity);
} catch (DraftNotFoundException expired) {
    showWarning("chatView.intent.draftExpired");
    return;
} catch (RuntimeException failure) {
    log.warn("Draft apply failed for draftId={}", draftId, failure);
    showConfigurationError();
    return;
}
```

### WR-02: Denied/skipped prefill attributes are silently dropped with no user feedback

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java:163` (`DraftApplyResult ignored = draftLoader.apply(...)`)
**Issue:** `DraftLoader.apply` returns a `DraftApplyResult` carrying `deniedAttributeCount` / `deniedAttributeNames`, but the caller assigns it to a variable literally named `ignored` and does nothing with it. When the extraction produced values for attributes the user cannot modify (or that fail coercion), the form silently opens without those fields and the user has no indication anything was dropped. The audit row records it, but end users never see the audit.
**Fix:** If `result.deniedAttributeCount() > 0`, show an informational notification (e.g. `chatView.intent.partialPrefill` with the count) after navigation completes.

### WR-03: `ExtractionService.prepare` can persist a draft and then return a failure to the LLM

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionService.java:117-152`
**Issue:** `dataManager.save(draft)` commits the draft, then `writeSuccessAudit(...)` runs. If `writeSuccessAudit` throws a `RuntimeException` (e.g. transient agentstore failure inside `AuditWriter`), control falls into `catch (RuntimeException runtimeFailure)`, which writes a `FAILED` audit row and rethrows `ExtractionSchemaException.validationFailure(...)`. Net result: the draft row exists (orphaned until TTL), the tool tells the model the extraction failed, and the audit trail shows both no SUCCESS row and a misleading `validation_failed` FAILED row. The `RuntimeException runtimeFailure` catch was meant for extractor failures, not audit-write failures.
**Fix:** Move `writeSuccessAudit` into its own try/catch (`log.warn` on failure, do not rethrow) so audit-write failures cannot reclassify a successful extraction as a validation failure, mirroring the best-effort posture used elsewhere (`auditDenial`, `task_file_budget_exceeded`).

### WR-04: `looksLikeActionCancellation` matches substrings that are not cancellations

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:1144-1153`
**Issue:** Any message whose normalized form contains the standalone words `huy` / `cancel` / `discard`, or substrings `khong tao` / `dung tao` / `bo qua yeu cau` / `dont create` / `don't create`, removes ALL pending action-choice rows before the turn is even submitted. Messages like "I don't want to cancel this", "không hủy nữa" (after diacritic stripping → "khong huy nua", matches `\bhuy\b`), or "please don't create a duplicate, update the existing one" will incorrectly discard a valid pending proposal.
**Fix:** Tighten the heuristic (anchor to start-of-message imperative phrasing, or require the cancellation token to be the dominant content), or drop the auto-removal and rely on the explicit "Discard" button that already exists on every action-choice row.

### WR-05: Stale action-choice row leaks when the model re-emits a proposal with a colliding id

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:1043` (`actionChoiceRowsByProposalId.put(...)`)
**Issue:** `appendActionChoiceRow` does `actionChoiceRowsByProposalId.put(proposalPayload.proposalId(), row)` without first removing any existing row for that id from the DOM. If two `propose_action_choices` results carry the same `proposalId` (the model can pass `proposalId` explicitly via the tool — `ActionProposal` only generates one when blank), the first `Div` is orphaned in `messageListSlot` and `messageCount` is double-counted, but the map only tracks the second.
**Fix:** Before `put`, look up and `removeActionChoiceRow(existing)` if the map already holds that proposalId, or de-duplicate by always allocating a fresh server-side id and ignoring any model-supplied one.

### WR-06: `confirmAndDeleteDraft` performs save + remove in two separate transactions

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java:178-187`
**Issue:** `dataManager.save(draft)` (sets `confirmed=true`) and `dataManager.remove(savedDraft)` run as two independent `DataManager` operations. If the `remove` fails, the row persists with `confirmed=true`; `ExtractionDraftAccess.loadOpenDraft` filters on `confirmed = false` so it becomes invisible to the UI and only the TTL job will clean it up. Not data loss, but the intent (delete the draft once the entity is saved) silently degrades to "leak until TTL." Also the intermediate `confirmed=true` write is unnecessary if the row is being removed.
**Fix:** Either just `dataManager.remove(draft)` directly (no `confirmed` flip), or wrap the flip+remove in a single `@Transactional("agentstoreTransactionManager")` method.

## Info

### IN-01: `ActionProposalService` stores a draft `instanceName` containing a different random UUID than the draft id

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalService.java:285-289, 196`
**Issue:** `validate(...)` calls `withChoices(...)` which builds `instanceName(proposal, metaClass, UUID.randomUUID())`. `createDraft` then re-validates and reads `validation.proposal().instanceName()` — which (when the original proposal had no instance name) is `"<caption> draft <random-uuid-A>"` — and `instanceName(readyProposal, metaClass, draftId)` returns it verbatim because it is now non-blank, so the persisted `instanceName` references `random-uuid-A` while the draft id is `draftId`. Cosmetic, but confusing in the drafts table / audit.
**Fix:** Compute the instance name once, against the actual `draftId`, after the row is created (as `ExtractionService.computeInstanceName` does).

### IN-02: `OpenFormWithDraftHandler` is `@VaadinSessionScope` but holds no session-scoped state

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java:38`
**Issue:** All fields are injected singletons; `open(...)` / `afterNavigation(...)` are stateless aside from the per-call `DraftLifecycleRegistration` local. The session scope adds a proxy with no benefit.
**Fix:** Make it a plain `@Component` (singleton) unless future session state is planned.

### IN-03: `MetaClassDtoSynthesizer` exposes a `uuid`-typed schema slot for reference attributes gated only by `LlmExposurePolicy`, not by Jmix CRUD read permission

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/MetaClassDtoSynthesizer.java:152-160`
**Issue:** For a `range.isClass()` attribute the schema is emitted whenever `llmExposurePolicy.canReadEntity(targetMetaClass)` is true; the Jmix `CrudEntityContext.isReadPermitted()` check is only applied later in `DraftLoader.coerceReferenceValue`. No data leaks (the slot is just `{"type":"string","format":"uuid","description":<caption>}`), and the apply path re-checks, but the schema/prompt advertises a related-entity slot the user may not be allowed to resolve.
**Fix:** Also consult `CrudEntityContext.isReadPermitted()` (or reuse `DraftLoader.canReadEntity`) when deciding whether to include a reference attribute in the schema, for consistency with the apply-time gate.

### IN-04: Dead branch in `AgentToolCallbacks.callbacksFor(...)` / `effectiveActionRules(PREFILL_FORM)`

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java:215-218`; `ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java:109-117`
**Issue:** `action:prefill-form` is never sent to `chatService.stream(...)` — `ChatPanelFragment.submitActionChoice` handles `PREFILL_FORM` by calling `actionProposalService.createDraft(...)` directly and never starting a chat turn. So the `ActionIntentId.PREFILL_FORM` branch in `callbacksFor` (and the matching `effectiveActionRules` branch) is unreachable in the current wiring, and the trailing `return singlePrepareFormDraftCallback();` makes the explicit `PREFILL_FORM` `if` redundant with the fall-through.
**Fix:** Either remove the unreachable branches, or document that they exist for hosts that route prefill through chat; collapse the duplicated `singlePrepareFormDraftCallback()` return.

### IN-05: `ExtractionService.prepare(String, Map)` will accept an LLM-supplied `conversationId` on the no-run-scope fallback path

**File:** `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionService.java:81-93`; `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionToolBridge.java:65-91`
**Issue:** When `runScopedInputAvailable()` is false, `ExtractionToolBridge` calls `extractionService.prepare(intentId, copyContextRefs(contextRefs))`, and `contextConversationId` reads `conversationId` straight from the model-supplied `contextRefs`. In the normal named-intent flow `RunContext.getIntentId()` is non-null so the run-scoped path (which uses `RunContext.getConversationId()`) is always taken — so this is not exploitable today — but if the run-scoped path is ever bypassed, a prompt-injected `conversationId` would land in `AiExtractionDraft.sourceConversationId` and in the audit row (the draft itself is still owner-scoped, so no cross-user data exposure).
**Fix:** Always prefer `RunContext.getConversationId()` over the `contextRefs` value, or reject a `contextRefs.conversationId` that does not match the run-scoped conversation.

---

_Reviewed: 2026-05-11 · Reviewer: gsd-code-reviewer (standard depth, 39 files, diff base `a228717`)_
