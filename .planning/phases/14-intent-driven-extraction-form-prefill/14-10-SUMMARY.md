---
phase: 14-intent-driven-extraction-form-prefill
plan: 10
subsystem: chat-ui
tags: [jmix, vaadin, action-intents, tool-gating, rag, uat-gap]
requires:
  - phase: 14-intent-driven-extraction-form-prefill
    provides: persisted extraction drafts, confirm-row navigation, chat streaming, mutation tools
provides:
  - Post-clarification action proposal tool for side-effecting chat choices
  - Action-choice UI row rendered only from READY server-validated proposals
  - Per-action tool-surface routing for create-now and prefill-form
  - Current-user authentication propagation for stream UI callbacks
  - Provider/RAG diagnostics tests separated from action-choice behavior
affects: [chat-ui, extraction, mutation-tools, rag, tests, phase-docs]
tech-stack:
  added: []
  patterns:
    - Safe planning tool emits structured proposal payloads without mutation or navigation
    - Server-side Vaadin action rows are appended as message-list siblings
    - Selected action intent controls the next turn's tool callbacks
key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionIntentId.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposal.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalResult.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalService.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/action/ActionProposalTool.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/action/ActionProposalServiceTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/action/ActionProposalToolTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ActionChoiceRowTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/fragment/RenderStreamEventActionProposalTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/ProviderConfigurationContractTest.java
    - ai-agent/ai-agent/src/test/java/com/vn/agent/rag/advisor/AuditingDocumentRetrieverTest.java
  modified:
    - .planning/phases/14-intent-driven-extraction-form-prefill/14-HUMAN-UAT.md
    - .planning/phases/14-intent-driven-extraction-form-prefill/14-UAT-CHECKLIST.md
    - .planning/phases/14-intent-driven-extraction-form-prefill/14-UI-SPEC.md
    - .planning/phases/14-intent-driven-extraction-form-prefill/14-VERIFICATION.md
    - ai-agent/ai-agent/src/main/java/com/vn/agent/DefaultChatServiceImpl.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/audit/ToolCallbackAuditDecorator.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/guard/AgentSystemPromptRulesComposer.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/tools/AgentToolCallbacks.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/StreamEventRenderer.java
    - ai-agent/ai-agent/src/main/resources/META-INF/resources/frontend/styles/ai-agent-chat.css
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_en.properties
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/messages_vi.properties
key-decisions:
  - "Side-effecting chat turns now use a planning-first action proposal flow: clarify data, emit choices, wait for user selection, then route the selected tool surface."
  - "Generic prefill-form action proposals create AiExtractionDraft rows directly in application code, not through model navigation, and must carry a source conversation id."
  - "Default planning turns expose read/link tools plus propose_action_choices, while create-now and prefill-form selections use separate constrained callback surfaces."
  - "RAG retrieval failure remains best-effort and is tested as provider diagnostics, not as an action-flow failure."
patterns-established:
  - "Action proposal payloads are streamed through ToolCallbackAuditDecorator only when status is READY."
  - "StreamEventRenderer remains pure parsing and produces side-channel markers; ChatPanelFragment owns Vaadin row creation."
requirements-completed: [EXTRACT-01, EXTRACT-05, EXTRACT-06, EXTRACT-07, EXTRACT-08, EXTRACT-10, SEC-06, TEST-15]
duration: ~2h
completed: 2026-05-10
---

# Phase 14 Plan 10: Gap Closure Summary

**Post-clarification action choices now replace the old first-screen picker for side-effecting chat actions.**

## Performance

- **Duration:** ~2h across resumed implementation and cleanup
- **Started:** 2026-05-10T02:23:00+07:00
- **Completed:** 2026-05-10T03:30:00+07:00
- **Tasks:** 6 completed
- **Files modified:** 32 phase-related files created or modified

## Accomplishments

- Added `propose_action_choices` as a safe, audited planning tool that validates proposal data without saving records, opening views, creating drafts, or calling mutation tools.
- Hid the legacy intent row on chat ready and added a post-proposal `ai-agent-action-choice` row with localized `Create now` and `Prefill form` actions.
- Routed default, named extraction, `action:create-now`, and `action:prefill-form` turns through distinct tool surfaces.
- Preserved current-user authentication across Reactor stream UI callbacks before secured Jmix loaders are touched.
- Added provider/RAG tests documenting OpenRouter configuration and best-effort retrieval behavior.
- Rewrote the Phase 14 manual UAT checklist, human-UAT tracker, verification report, and UI spec to describe the action-intent flow instead of the superseded static picker.

## Task Commits

Task-level commits were not created because the worktree already contained partially implemented Phase 14 gap-closure edits and unrelated untracked Playwright/output artifacts when execution resumed. The completed gap closure is consolidated into the final Phase 14-10 commit instead.

## Files Created/Modified

- `ai-agent/ai-agent/src/main/java/com/vn/agent/action/*` - Action intent ids, proposal DTOs, validation service, and safe tool.
- `AgentToolCallbacks.java` - Default planning and selected action callback routing.
- `ToolCallbackAuditDecorator.java` - Structured READY action proposal stream payload propagation.
- `StreamEventRenderer.java` - Action proposal payload parsing side channel.
- `ChatPanelFragment.java` - Hidden initial intent row, action-choice row rendering, prefill draft creation, and authenticated UI access.
- `DefaultChatServiceImpl.java` - Action intent routing separated from named extraction intent routing.
- `AgentSystemPromptRulesComposer.java` - Prompt rules for planning turns and selected action turns.
- `messages_en.properties`, `messages_vi.properties`, `ai-agent-chat.css` - Localized copy and action-choice styling.
- `14-UAT-CHECKLIST.md`, `14-HUMAN-UAT.md`, `14-UI-SPEC.md`, `14-VERIFICATION.md` - Manual UAT, tracking, design, and verification artifacts updated to the gap-closure flow.
- New and updated tests cover action proposal validation/tooling, rendering, callback gating, prompt rules, async authentication, provider config, RAG fallback, locale parity, and stale source contracts.

## Decisions Made

- Keep `IntentExtractor` as a backend extraction capability, not a first-screen side-effecting action picker.
- Use a server-validated action proposal as the only trigger for rendering action choices.
- Keep create-now as a selected action turn rather than a direct button-side service call, so mutation still runs through the existing tool and audit layers.
- Implement prefill-form as application-side draft creation from the validated proposal payload, then reuse the existing "Open form to confirm" row.
- Keep RAG/provider availability out of the action UX acceptance path; provider errors should be diagnosed through configuration tests and retrieval fallback behavior.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Data Integrity] Set sourceConversationId for generic action drafts**
- **Found during:** Task 3/4 cleanup
- **Issue:** `AiExtractionDraft.SOURCE_CONVERSATION_ID` is non-null, but generic action drafts initially did not set it.
- **Fix:** Added `ActionProposalService.createDraft(ActionProposal, UUID, UUID)` and passed the current chat `conversationId` from `ChatPanelFragment`.
- **Files modified:** `ActionProposalService.java`, `ChatPanelFragment.java`, `ActionProposalServiceTest.java`
- **Verification:** `ActionProposalServiceTest.createDraftPersistsValidatedPayloadWithSourceConversation`

**2. [Rule 3 - Stale Contract] Updated callback-count and intent-row tests**
- **Found during:** Test review after adding `propose_action_choices`
- **Issue:** Existing tests still expected the old full tool count and first-screen intent picker behavior.
- **Fix:** Updated source tests and callback tests to pin the new action-proposal surface.
- **Files modified:** `AgentToolCallbacksDefaultConfigTest.java`, `AgentToolCallbacksMutationEnabledTest.java`, `IntentCardRowTest.java`, `OpenFormWithDraftRenderingTest.java`
- **Verification:** Targeted callback and source-contract test suites passed.

**3. [Rule 1 - Action Idempotence] Disable action-choice row after selection**
- **Found during:** Inline code review
- **Issue:** Action-choice buttons stayed enabled after selection, allowing duplicate selected-action submissions or duplicate generic prefill draft attempts.
- **Fix:** Disabled all buttons in the action-choice row after a click; Prefill form re-enables the row only if draft creation fails.
- **Files modified:** `ChatPanelFragment.java`
- **Verification:** `testClasses` and targeted action-choice suites passed.

**4. [Rule 4 - Prompt Boundary] Serialize selected-action values as JSON**
- **Found during:** Inline code review
- **Issue:** The selected Create now turn serialized collected values with `Map.toString()`, which is ambiguous at the model-facing prompt boundary.
- **Fix:** Injected `ObjectMapper` and serialize collected values as JSON in the selected-action prompt.
- **Files modified:** `ChatPanelFragment.java`
- **Verification:** `testClasses` and targeted action-choice suites passed.

---

**Total deviations:** 4 auto-fixed (1 data integrity, 1 stale test contract, 2 review hardening fixes)
**Impact on plan:** These fixes tighten the planned behavior and do not expand scope beyond the UAT gap closure.

## Issues Encountered

- `--gaps` is not the documented flag name; `--gaps-only` is. The phase contained only one incomplete `gap_closure: true` plan, so execution safely continued on `14-10-PLAN.md`.
- JetBrains MCP inspections could not run for this repository because the connected IDE project was `D:/study-materials-summer-2026/EXE202/zero-mail`.
- Full `./gradlew --no-daemon :ai-agent:ai-agent:test` exceeded a 6-minute shell timeout and left an orphaned Gradle/test worker, which was stopped. The targeted Phase 14 and callback suites passed.

## Verification

Passed:

- `./gradlew --no-daemon :ai-agent:ai-agent:testClasses`
- `./gradlew --no-daemon :ai-agent:ai-agent:test --tests "*ActionProposalServiceTest" --tests "*ActionProposalToolTest" --tests "*ActionChoiceRowTest" --tests "*RenderStreamEventActionProposalTest" --tests "*DefaultChatServiceIntentRoutingTest" --tests "*AgentToolCallbacksIntentGatingTest" --tests "*AgentSystemPromptRulesComposerIntentTest" --tests "*ChatPanelFragmentConversationIdTest" --tests "*ToolNavigationLeakScannerTest" --tests "*ProviderConfigurationContractTest" --tests "*AuditingDocumentRetrieverTest" --tests "*IntentCardRowTest" --tests "*OpenFormWithDraftRenderingTest" --tests "*LocaleParityTest" --tests "*ExtractionAuditTest"`
- `./gradlew --no-daemon :ai-agent:ai-agent:test --tests "*AgentToolCallbacksDefaultConfigTest" --tests "*AgentToolCallbacksMutationEnabledTest" --tests "*AgentToolCallbacksMutationEnabledAllowDeleteTest"`
- Re-ran the same `testClasses`, targeted gap-closure suite, and callback suite after the inline review hardening patch.
- `git diff --check`
- `rg -n "Customer intent.*visible|intent picker is visible|card appears" .planning/phases/14-intent-driven-extraction-form-prefill/14-UAT-CHECKLIST.md` returned no matches.
- `rg -n "No entity/action intent card should appear" .planning/phases/14-intent-driven-extraction-form-prefill/14-UAT-CHECKLIST.md`
- `rg -n "refreshIntentCardRow\\(\\);" ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java` returned no matches.
- `rg -n "ViewNavigators|\\.navigate\\(" ai-agent/ai-agent/src/main/java/com/vn/agent/action` returned no matches.

Not completed:

- JetBrains `get_file_problems` for touched files, because MCP is attached to the wrong IDE project.
- Full module test, because it timed out before returning results.
- Manual UAT, because it requires a running app and user verification.

## User Setup Required

None - no new external service configuration required.

## Next Phase Readiness

Phase 14's automated gap-closure checks are green. The next step is manual UAT from the updated `14-UAT-CHECKLIST.md`, especially the create-now and prefill-form paths under real Jmix permissions.

---
*Phase: 14-intent-driven-extraction-form-prefill*
*Completed: 2026-05-10*
