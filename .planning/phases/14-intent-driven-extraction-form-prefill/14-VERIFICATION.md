---
phase: 14-intent-driven-extraction-form-prefill
verified: 2026-05-08T15:34:36+07:00
status: gaps_found
score: 10/14 requirements verified; 4 phase-goal gaps and 1 inherited release blocker remain
schema_drift:
  status: passed
  drift_detected: false
codebase_drift:
  status: skipped
  reason: no-structure-md
human_verification: []
gaps:
  - BL-02 expired or confirmed extraction drafts remain loadable by id
  - BL-03 first-turn streaming submit creates a conversation before service guards run
  - BL-04 Customer reference intent reserializes a Jmix entity into draft payload JSON
  - BL-05 source-faithfulness is represented by fixtures only, not enforced in production
  - BL-01 committed development credentials remain in application.properties
---

# Phase 14: Intent-Driven Extraction -> Form Prefill - Verification Report

**Phase Goal:** The LLM produces a structured draft for a host entity, the user confirms through a chat-rendered button, and a Jmix detail view opens prefilled without giving the LLM any UI-mutation primitive. Jmix security and normal detail-view validation must remain the authority for the eventual save.

**Status:** gaps_found  
**Verdict:** Do not mark Phase 14 complete yet.

The implementation delivers the planned foundation, SPI, structured tool payload, intent-aware chat routing, controller-side confirm/open flow, reference Customer intent, scanner tests, eval fixtures, and UAT checklist. The targeted Phase 14 test matrix passes. However, verification found critical gaps that affect the phase's safety contract: stale drafts can still be applied, the Customer reference payload can expand beyond the synthesized schema, production source-faithfulness is not enforced, and the UI can create a first-turn conversation before service guards run.

## Requirement Coverage

| Requirement | Status | Evidence |
| --- | --- | --- |
| EXTRACT-01 | VERIFIED | `chat-panel-fragment.xml` and `ChatPanelFragment` implement the intent picker; Plan 14-06 tests cover Auto/default selection and named-intent rendering. |
| EXTRACT-02 | PARTIAL | `IntentExtractor<T>` and `CustomerDraftIntentExtractor` exist, but the reference extractor returns a `Customer` entity after validating a narrowed map, which contributes to BL-04. |
| EXTRACT-03 | VERIFIED | `CustomerDraftIntentExtractor` uses the active Spring AI `ChatClient`; no separate Phase 14 model pin was added. |
| EXTRACT-04 | PARTIAL | `AiExtractionDraft` exists with TTL fields and row-level security, but live load paths do not reject expired or already-confirmed rows (BL-02). |
| EXTRACT-05 | PARTIAL | `ExtractionService.prepare(...)` dispatches to the extractor and persists drafts, but it serializes the extractor return value without filtering against the synthesized schema (BL-04). |
| EXTRACT-06 | VERIFIED | `ExtractionToolBridge` exposes only `prepare_form_draft`; `ToolNavigationLeakScannerTest` guards tool-side navigation leaks. |
| EXTRACT-07 | VERIFIED | `StreamEventRenderer` recognizes the structured `open_form_with_draft` payload and `OpenFormWithDraftHandler` owns navigation through Jmix UI APIs. |
| EXTRACT-08 | VERIFIED | `DraftLoader` applies payload fields through `canModify(...)` checks and a single guarded `setValueIfPermitted(...)`; scanner coverage pins the raw `EntityValues.setValue` boundary. |
| EXTRACT-09 | PARTIAL | Save-time deletion exists, and the cleanup job deletes expired rows hourly, but expired rows remain usable before cleanup runs (BL-02). |
| EXTRACT-10 | VERIFIED | Scanner tests assert LLM-facing tool surfaces do not import `ViewNavigators` or call `.navigate()`. |
| ENT-08 | VERIFIED | `AiExtractionDraft` entity and Liquibase changelog were added in Plan 14-01. |
| SPI-12 | VERIFIED | `IntentExtractor<T>`, `IntentRegistry`, `ExtractionInput`, and schema synthesis exist and are tested. |
| TEST-15 | VERIFIED | `ToolNavigationLeakScannerTest`, `DraftSetValueBypassScannerTest`, and `CoreCustomerImportScannerTest` pass in the targeted matrix. |
| SEC-06 | VERIFIED | `AiAgentUserRole` / row-level role coverage for own draft rows was added and tested. |

## Critical Gaps

### BL-02: Expired or Confirmed Drafts Remain Usable

`DraftLoader.loadDraft(...)` loads by id only:

- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/DraftLoader.java:114-118`

`OpenFormWithDraftHandler.loadDraft(...)` also loads by id only:

- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java:113-119`

The cleanup job deletes rows where `expiresAt < now`, but it runs separately and hourly:

- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/AiExtractionDraftCleanupJob.java:34-41`

This leaves a window where an expired row still opens and applies a prefilled form. The load paths must require `expiresAt > now` and `confirmed = false`, and tests must cover an expired row that still exists.

### BL-03: First-Turn Streaming Submit Creates a Conversation Before Guards

`ChatPanelFragment.onSubmit()` calls `ensureConversationIdForSubmit(...)` before invoking `chatService.stream(...)`:

- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:688-693`

That helper calls `conversationGateway.loadOrCreate(...)` when `conversationId` is null:

- `ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java:829-846`

This undermines the service-level guard ordering where rate-limit and token-budget checks are intended to run before first-turn conversation creation. Let `DefaultChatServiceImpl.stream(...)` create the first conversation after guards and update the UI conversation id from `StreamingEvent.Final`.

### BL-04: Customer Reference Payload Can Expand Beyond the Synthesized Schema

`CustomerDraftIntentExtractor` validates a narrowed map, converts it to `Customer`, and returns the entity:

- `jmix-app/src/main/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractor.java:81-98`

`ExtractionService` then serializes the returned object through `ExtractionJsonSupport.toPayloadMap(...)`:

- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionService.java:106-110`
- `ai-agent/ai-agent/src/main/java/com/vn/agent/extraction/ExtractionJsonSupport.java:21-30`

Because the returned object is a Jmix entity, Jackson can reintroduce getter-backed fields that were never part of the synthesized schema. The draft payload should contain only schema-approved attributes. Fix either the service by filtering serialized payloads against the schema or the reference extractor by returning a schema-shaped DTO/map through a compatible SPI contract.

### BL-05: Source-Faithfulness Is Not Enforced in Production

`extraction-fixtures.yaml` encodes `source_faithfulness_failure`, and `ExtractionEvaluationContractTest` asserts that the fixture represents no draft promotion. Production validation in `CustomerDraftIntentExtractor.validatePayload(...)` checks allowed keys, String values, email validation, and phone shape:

- `jmix-app/src/main/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractor.java:121-149`

It does not verify that each extracted value is supported by the source text or attached media. A fabricated but syntactically valid email can pass and create a draft. The extractor needs a provenance/evidence contract and executable tests that drive the real implementation path.

### BL-01: Committed Credentials Remain a Release Blocker

The code review found committed DB/admin defaults in `jmix-app/src/main/resources/application.properties`. This was previously accepted as a dev-only artifact in Phase 13, but Phase 14 touched the same sample app configuration and the release blocker remains. Before shipping, move these values to environment-backed or non-committed development configuration.

## Verified Deliverables

| Deliverable | Status | Evidence |
| --- | --- | --- |
| Draft persistence/security/TTL foundation | VERIFIED with stale-load gap | `AiExtractionDraft`, Liquibase 110, cleanup job, roles, row-level policy. |
| SPI and schema synthesis | VERIFIED | `IntentExtractor`, `IntentRegistry`, `MetaClassDtoSynthesizer`, and unit coverage. |
| `prepare_form_draft` tool bridge | VERIFIED | `ExtractionToolBridge` exposes one payload-only tool; audit ownership stays in `ExtractionService`. |
| Named-intent chat routing | VERIFIED | `ChatService` selected-intent overloads and `DefaultChatServiceImpl` callback gating are present. |
| Controller-side open/apply/save lifecycle | PARTIAL | Navigation and save deletion exist; stale draft load must be fixed. |
| Chat UI intent picker and confirm row | VERIFIED | UI descriptor/controller/renderer/CSS/i18n shipped and tested by source/XML contracts. |
| Host Customer reference intent | PARTIAL | End-to-end host example exists and focused tests pass; schema-faithful payload and source-faithfulness need fixes. |
| Final scanners/eval/UAT artifacts | VERIFIED with eval limitation | Scanners and deterministic eval fixtures exist; eval fixtures are not production enforcement. |

## Automated Checks

Passed checks recorded during execution:

- `./gradlew --no-daemon :ai-agent:ai-agent:test --tests "*ExtractionEvaluationContractTest"` with constrained `GRADLE_OPTS`
- Targeted Phase 14 add-on matrix covering draft, registry, schema, extraction, tool bridge, intent gating, loader/open-form/UI/scanner/eval/locale tests
- `./gradlew --no-daemon :jmix-app:test --tests "*CustomerDraft*"`
- `./gradlew --no-daemon :ai-agent:ai-agent:test`
- `gsd-sdk query verify.schema-drift 14` -> `drift_detected=false`

Residual failures:

- `./gradlew --no-daemon :jmix-app:test` fails in broad pre-existing host context tests because `UserRepository` cannot initialize: `MetaClass not found for class com.vn.jmixapp.entity.User`.
- `./gradlew --no-daemon :ai-agent:ai-agent:check :jmix-app:check --continue` fails because `:ai-agent:ai-agent:integrationTest` workers hit native memory/paging-file errors and `:jmix-app:test` repeats the host `User` metaclass failure.
- `gsd-sdk query verify.codebase-drift` skipped with `reason=no-structure-md`.

No `hs_err_pid*.log` files were present at verification time, and the worktree was clean before this report was written.

## Manual UAT

Manual UI verification is prepared in `14-UAT-CHECKLIST.md`, but this report does not route Phase 14 to `human_needed` because critical automated/code-review gaps must be fixed first. After gap closure, rerun verification and then execute the UAT checklist against a running app.

## Next Step

Create gap-closure plans for Phase 14 and execute them before marking the phase complete:

`$gsd-plan-phase 14 --gaps`

---

_Verified: 2026-05-08T15:34:36+07:00_  
_Verifier: Codex inline verifier (gsd-execute-phase fallback)_  
_Branch: gsd/phase-14-intent-driven-extraction-form-prefill_
