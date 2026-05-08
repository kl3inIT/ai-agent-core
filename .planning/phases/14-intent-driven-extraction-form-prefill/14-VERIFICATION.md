---
phase: 14-intent-driven-extraction-form-prefill
verified: 2026-05-08T17:32:04+07:00
status: human_needed
score: "9/9 must-haves verified"
overrides_applied: 1
overrides:
  - must_have: "BL-01 - jmix-app/src/main/resources/application.properties contains no committed database passwords, admin default password, or host-specific database IP; local values move to environment variables or ignored .env."
    reason: "User explicitly narrowed BL-01: datasource and UI-login defaults are not Phase 14 verification failures; only OpenRouter API key must remain env-backed and .env.example must only document OPENROUTER_API_KEY."
    accepted_by: "user"
    accepted_at: "2026-05-08T17:32:04+07:00"
re_verification:
  previous_status: gaps_found
  previous_score: "10/14 requirements verified; 4 phase-goal gaps and 1 inherited release blocker remain"
  gaps_closed:
    - "BL-02 expired or confirmed extraction drafts remain loadable by id"
    - "BL-03 first-turn streaming submit creates a conversation before service guards run"
    - "BL-04 Customer reference intent reserializes a Jmix entity into draft payload JSON"
    - "BL-05 source-faithfulness is represented by fixtures only, not enforced in production"
    - "BL-01 narrowed to API-key env handling only"
  gaps_remaining: []
  regressions: []
human_verification:
  - test: "Run the manual chat-to-form UAT from 14-UAT-CHECKLIST.md in a browser"
    expected: "Customer intent is visible, selecting it and sending supported source text or a task file produces an inline Open form to confirm button, clicking opens Customer detail prefilled, Save succeeds through normal Jmix validation, and the draft row is deleted."
    why_human: "Vaadin/Jmix rendering, real navigation, and real provider-backed extraction require a running app and browser session."
---

# Phase 14: Intent-Driven Extraction -> Form Prefill Verification Report

**Phase Goal:** The LLM produces a structured draft for a host entity, the user confirms through a chat-rendered button, and a Jmix detail view opens prefilled without giving the LLM any UI-mutation primitive. Jmix security and normal detail-view validation remain the authority for the eventual save.
**Verified:** 2026-05-08T17:32:04+07:00
**Status:** human_needed
**Re-verification:** Yes - after 14-09 gap closure

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Named intent turns expose only `prepare_form_draft`; Auto turns keep the normal tool surface; tool output is `{action,draftId,entityName,instanceName}` and tool classes do not navigate. | VERIFIED | `AgentToolCallbacks.callbacksFor(..., intentId)` filters to `prepare_form_draft`; `ExtractionToolBridge` returns the locked map; `ToolNavigationLeakScannerTest` passed. |
| 2 | `StreamingEvent.ToolResult` carries structured payload data, `ChatPanelFragment` renders an inline confirm button, and only controller-side code navigates after `AccessManager` view checks. | VERIFIED | `StreamingEvent.ToolResult.toolName/payloadJson`, `StreamEventRenderer.parseOpenFormWithDraftPayload`, `ChatPanelFragment.appendIntentConfirmRow`, and `OpenFormWithDraftHandler` are wired. |
| 3 | Draft prefill applies only to permitted attributes on the `StandardDetailView` edited entity, uses a gated `setValueIfPermitted` boundary, audits counts, and Save deletes the draft while close leaves it for TTL. | VERIFIED | `DraftLoader.canModify()` gates before `EntityValues.setValue`; scanner confines raw setValue to the helper; `OpenFormWithDraftHandler.AfterSaveEvent` confirms/removes the draft and `AfterCloseEvent` removes listeners only. |
| 4 | `AiExtractionDraft` is persisted in `agentstore`, row-level-scoped by owner, hidden from LLM schema/tools, has TTL cleanup, and `prepare_form_draft` is audited. | VERIFIED | `AiExtractionDraft`, Liquibase `110-ai-extraction-draft.xml`, `AiAgentUserRole`, `AiAgentUserRowLevelRole`, `AiInternalEntityNames`, `AiExtractionDraftCleanupJob`, and `ExtractionService.writeSuccessAudit` exist and are covered by tests/source checks. |
| 5 | BL-02: Expired or confirmed drafts cannot be opened or applied through user-facing paths. | VERIFIED | `ExtractionDraftAccess.loadOpenDraft()` uses secured `DataManager` with `e.id = :draftId and e.expiresAt > :now and e.confirmed = false`; both `DraftLoader` and `OpenFormWithDraftHandler` use it. |
| 6 | BL-03: First-turn streaming submit no longer creates a conversation in the UI before service guards run. | VERIFIED | `ChatPanelFragment.onSubmit()` passes `final UUID targetConversationId = conversationId`; no production `ensureConversationIdForSubmit(userId, text)` remains; `DefaultChatServiceImpl.stream()` checks rate limit before `conversationGateway.loadOrCreate(...)`. |
| 7 | BL-04: Draft `payloadJson` and success audit summaries are limited to `MetaClassDtoSynthesizer`-approved attribute names. | VERIFIED | `ExtractionService` calls `schemaSynthesizer.buildSchema(metaClass)` then `filterPayloadToSchema(...)` before JSON persistence and audit summary creation; tests cover unsupported keys such as `recommendedProducts`, `version`, and `class`. |
| 8 | BL-05: Reference Customer extraction rejects unsupported string values when textual evidence exists. | VERIFIED | `ExtractionInput` carries `sourceTexts`; `RunContext`, `DefaultChatServiceImpl`, and `ExtractionToolBridge` propagate them; `CustomerDraftIntentExtractor.assertSourceFaithful(...)` rejects fabricated strings against user/document text. Image-only input remains prompt-based by explicit 14-09 scope. |
| 9 | BL-01 narrowed: OpenRouter API key remains env-backed and `.env.example` only documents that key. | PASSED (override) | `spring.ai.openai.api-key=${OPENROUTER_API_KEY:}` remains in `jmix-app/src/main/resources/application.properties`; `jmix-app/.env.example` contains only `OPENROUTER_API_KEY=`. Datasource/UI defaults remain by user correction. |

**Score:** 9/9 truths verified. Status is still `human_needed` because browser/manual UAT is required.

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `AiExtractionDraft.java` + `110-ai-extraction-draft.xml` | Agentstore draft entity and table | VERIFIED | Entity has UUID, version, instance name, owner, payload JSON, source ids, timestamps, expiry, confirmed flag; changelog uses UUID/timestamp properties and indexes owner/expiry/conversation. |
| `AiAgentUserRole.java` / `AiAgentUserRowLevelRole.java` | Owner-scoped user access | VERIFIED | Resource policy covers draft CRUD; row-level JPQL scopes `userUsername = :current_user_username`. |
| `IntentExtractor`, `IntentRegistry`, `MetaClassDtoSynthesizer` | Host SPI, eligible intent registry, schema synthesis | VERIFIED | Registry filters with `LlmExposurePolicy` read/create and sorts deterministically; schema excludes non-writable/internal/collection fields and renders to-one refs as UUID strings. |
| `ExtractionService` / `ExtractionToolBridge` | Draft orchestration and single LLM-facing tool | VERIFIED | Service handles exposure denial, extraction, schema filtering, persistence, and audit; bridge returns payload only and has no navigation imports. |
| `ExtractionDraftAccess`, `DraftLoader`, `OpenFormWithDraftHandler` | Live draft loading, gated apply, controller navigation | VERIFIED | Load paths reject missing/expired/confirmed rows; view permission uses `UiShowViewContext`; create permission checked before new detail view. |
| `ChatService`, `DefaultChatServiceImpl`, `RunContext`, `AgentToolCallbacks` | Intent-aware chat routing | VERIFIED | Intent overloads, named prompt suffix, callback filter, source-text context, and prepare-form invocation cap are present. |
| `chat-panel-fragment.xml`, `ChatPanelFragment`, `StreamEventRenderer`, CSS/messages | Intent picker and confirm row UI | VERIFIED | XML radio group, `@Supply` renderer, named-send reset, structured payload marker, confirm row, and bilingual keys are present. |
| `CustomerDraftIntentExtractor` | Host reference Customer intent | VERIFIED | Lives in `jmix-app`, enabled by property, uses Spring AI Map output, narrows fields, validates, and enforces textual source faithfulness. |
| Scanner/eval/UAT files | TEST-15 and final verification support | VERIFIED | Navigation, raw setValue, core Customer import, eval contract, locale parity, and UAT checklist artifacts exist and targeted tests pass. |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `ChatPanelFragment.onSubmit` | `ChatService.stream` | Selected `intentId` argument | VERIFIED | Named intent id is read from `intentCardRow`; Auto passes null; named selection resets to Auto after dispatch. |
| `DefaultChatServiceImpl` | `AgentToolCallbacks` | `callbacksFor(userId, convId, intentId)` | VERIFIED | Named intent gets exactly `prepare_form_draft`; stale intent and callback misconfiguration fail closed before model invocation. |
| `ExtractionToolBridge` | `ExtractionService` | `prepareFormDraft(...)` delegates | VERIFIED | Tool returns only `open_form_with_draft` payload. |
| `ExtractionService` | `AiExtractionDraft` | Secured `DataManager.save` | VERIFIED | Draft JSON is schema-filtered before save; audit summaries use filtered attributes and counts only. |
| `ToolCallbackAuditDecorator` | `StreamingEvent.ToolResult` | `toolName` and `payloadJson` | VERIFIED | Structured payload is carried for `prepare_form_draft`; other tools keep `payloadJson=null`. |
| `StreamEventRenderer` | `ChatPanelFragment` | `RenderedStreamEvent.DraftPayload` | VERIFIED | Renderer parses payload JSON only, not human summary; fragment appends confirm row. |
| `ChatPanelFragment` | `OpenFormWithDraftHandler` | Confirm button click | VERIFIED | Handler receives origin view and draft identifiers; renderer never imports `ViewNavigators`. |
| `OpenFormWithDraftHandler` | `DraftLoader` | `withAfterNavigationHandler` | VERIFIED | Handler opens primary detail view, then applies the draft to `detailView.getEditedEntity()`. |
| `AiTaskFileMediaResolver` / user text | `CustomerDraftIntentExtractor` | `ExtractionSourceText` through `RunContext` / `ExtractionInput` | VERIFIED | Textual source evidence reaches the reference extractor and gates string values. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|---|---|---|---|---|
| Intent card row | `IntentOption` list | `IntentRegistry.eligibleForCurrentUser()` over registered `IntentExtractor` beans | Yes | VERIFIED |
| Named chat turn | `intentId`, task file ids/media/source text | `ChatPanelFragment` + `DefaultChatServiceImpl` + `AiTaskFileMediaResolver` | Yes | VERIFIED |
| Draft payload | `payloadJson` | `IntentExtractor.extract(...)` -> Jackson map -> schema filter | Yes | VERIFIED |
| Confirm row | `DraftPayload` | `StreamingEvent.ToolResult.payloadJson` from decorated tool output | Yes | VERIFIED |
| Prefilled entity | `editingEntity` values | `AiExtractionDraft.payloadJson` loaded by `ExtractionDraftAccess` | Yes, if draft is open/unexpired/unconfirmed | VERIFIED |
| Customer source-faithfulness | `sourceTexts` | Tika/document text and user message | Yes for textual evidence; image-only bypass is scoped | VERIFIED |

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|---|---|---|---|
| Phase 14 add-on gap/scanner/schema matrix | `./gradlew --no-daemon --max-workers=1 :ai-agent:ai-agent:test --tests "*ExtractionDraftAccessTest" --tests "*DraftLoaderTest" --tests "*OpenFormWithDraftHandlerTest" --tests "*ExtractionServiceTest" --tests "*ExtractionToolBridgeTest" --tests "*ChatPanelFragmentConversationIdTest" --tests "*IntentCardRowTest" --tests "*DefaultChatServiceIntentRoutingTest" --tests "*ExtractionEvaluationContractTest" --tests "*ToolNavigationLeakScannerTest" --tests "*DraftSetValueBypassScannerTest" --tests "*CoreCustomerImportScannerTest" --tests "*LocaleParityTest" --tests "*IntentRegistryTest" --tests "*MetaClassDtoSynthesizerTest"` | BUILD SUCCESSFUL | PASS |
| Host Customer reference tests | `./gradlew --no-daemon --max-workers=1 :jmix-app:test --tests "*CustomerDraftIntentExtractorTest" --tests "*CustomerDraftWorkflowTest"` | BUILD SUCCESSFUL | PASS |
| BL source checks | PowerShell assertions for BL-02, BL-03, BL-04, BL-05, and narrowed BL-01 | All PASS | PASS |
| PLAN artifact verifier | `gsd-sdk query verify.artifacts 14-09-PLAN.md` | 3/4 passed; stale BL-01 expected `MAIN_DATASOURCE_URL` | PASS WITH OVERRIDE |
| Key-link verifier | `gsd-sdk query verify.key-links 14-09-PLAN.md` | No `must_haves.key_links` in frontmatter | SKIP |

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| EXTRACT-01 | 14-04/14-06 | User selects an intent before sending; Auto remains default path | VERIFIED | `intentCardRow`, `IntentRegistry`, selected intent submit/reset tests. |
| EXTRACT-02 | 14-02/14-07 | `IntentExtractor<T>` SPI plus reference extractor | VERIFIED | SPI exists; `CustomerDraftIntentExtractor` implements host reference. |
| EXTRACT-03 | 14-02/14-04/14-07 | Extraction follows active chat model/profile | VERIFIED | No separate model pin; extractor uses injected `ChatClient`; chat service resolves active parameters. |
| EXTRACT-04 | 14-01/14-09 | Persisted `AiExtractionDraft` with TTL and owner | VERIFIED | Entity/changelog/roles plus open-draft live predicate. |
| EXTRACT-05 | 14-03/14-09 | `ExtractionService` orchestrates and persists draft | VERIFIED | Service resolves intent, exposure-checks, extracts, filters payload, saves, audits. |
| EXTRACT-06 | 14-03/14-04/14-08 | Single `prepare_form_draft` tool, no UI primitive | VERIFIED | Tool bridge payload only; callback gating; navigation scanner passed. |
| EXTRACT-07 | 14-05/14-06 | Chat renders confirm button and controller opens form | VERIFIED | Renderer/fragment/handler wiring and tests passed. |
| EXTRACT-08 | 14-05/14-08 | Permission-gated prefill, no raw bypass | VERIFIED | `EntityAttributeContext.canModify` gate and setValue scanner passed. |
| EXTRACT-09 | 14-01/14-05/14-09 | Draft lifecycle delete-on-save and TTL cleanup | VERIFIED | Cleanup job; save deletion; close retention; expired/confirmed rows rejected. |
| EXTRACT-10 | 14-03/14-08 | LLM cannot bypass confirm flow | VERIFIED | TEST-15 scanner passed; `OpenFormWithDraftHandler` is UI-side only. |
| ENT-08 | 14-01 | `AiExtractionDraft` entity | VERIFIED | Entity exists with Jmix/agentstore annotations and Liquibase table. |
| SPI-12 | 14-02 | `IntentExtractor<T>` SPI | VERIFIED | SPI and registry tests passed. |
| TEST-15 | 14-08 | Navigation scanner | VERIFIED | `ToolNavigationLeakScannerTest` included in passing matrix. |
| SEC-06 | 14-01/14-09 | User role row-level access for draft rows | VERIFIED | Role and row-level policy exist; `ExtractionDraftAccess` uses secured `DataManager`. |

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|---|---:|---|---|---|
| `14-09-PLAN.md` artifact contract | n/a | Stale BL-01 broad env migration expectation | INFO | Superseded by explicit user correction; formal override applied. |
| Main Phase 14 source files | n/a | `return null` grep hits | INFO | All reviewed hits are control-flow sentinels, not stubs or user-visible placeholders. |

### Human Verification Required

#### 1. Manual Chat-to-Form UAT

**Test:** Start the app, log in, open the chat surface, verify the Customer intent card appears, submit supported source text or a supported task file with the Customer intent selected, click the inline confirm button, verify Customer detail opens prefilled, edit if needed, and Save.

**Expected:** The detail view opens through normal Jmix navigation, prefilled values match the source, normal validation applies, Save succeeds, and the draft row is gone afterward.

**Why human:** This validates Vaadin rendering, browser interaction, real detail-view navigation, and provider-backed extraction. Automated tests cover code paths and source contracts but do not run the browser flow here.

### Gaps Summary

No automated blocker gaps remain. Phase 14 should not be marked fully complete until the manual UAT above is executed and accepted.

---

_Verified: 2026-05-08T17:32:04+07:00_
_Verifier: the agent (gsd-verifier)_
