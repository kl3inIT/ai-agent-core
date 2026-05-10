---
status: testing
phase: 14-intent-driven-extraction-form-prefill
source: [14-VERIFICATION.md, 14-10-PLAN.md, 14-10-SUMMARY.md]
started: 2026-05-08T17:32:04+07:00
updated: 2026-05-10T18:19:18+07:00
---

# Phase 14 Human UAT

## Current Test

number: 6
name: Expired Or Removed Draft
expected: |
  An expired or removed draft cannot open a form or substitute another draft; the user sees the expired-draft behavior and chat remains usable.
awaiting: user response

## Tests

### 1. Initial Chat State

expected: Chat opens with no entity/action intent card, usable message input, no created record, and no draft.
result: pass
verified: "Browser retest on 2026-05-10 against localhost:8090 showed a new chat with no static entity/action picker."

### 2. Missing Fields Are Clarified

expected: An incomplete create request makes the assistant ask for required fields. No action-choice row, mutation, or form navigation occurs yet.
result: pass
verified: "The assistant asked for missing email/phone after an incomplete Customer create request, and no action-choice row appeared before clarification."

### 3. Action Choices After Clarification

expected: After required fields are provided, a server-validated action-choice row appears with choices allowed by the logged-in user's Jmix permissions.
result: pass
verified: "After the user replied to skip missing optional fields, the message list rendered a ready action-choice row with Create now and Prefill form."

### 4. Create Now Path

expected: Clicking Create now enables the mutation path only for the selected action turn, creates the record under Jmix security, and records audit evidence.
result: pass
verified: "User confirmed the Create now path passed. The selected action created the target record only after the explicit Create now click."
ux_observation: "User noticed the disabled action-choice card remained anchored near the bottom after selection. This was treated as a minor UX cleanup and resolved by removing the action-choice row once an action is selected."

### 5. Prefill Form Path

expected: Clicking Prefill form creates an extraction draft, renders Open form to confirm, opens the Jmix detail view only after that click, preloads permitted values, saves through normal validation, and deletes the draft after Save.
result: pass
reported_issue: "The prefilled Customer form opened and pressing OK created the record, but the UI also showed an optimistic-lock error for the Extraction draft object."
resolution: "Fixed in OpenFormWithDraftHandler by removing the saved AiExtractionDraft instance returned by DataManager.save instead of the stale pre-save instance. Browser retest on localhost:8090 confirmed OK saves and returns to chat without the draft error."

### 6. Expired Or Removed Draft

expected: An expired or removed draft cannot open a form or substitute another draft; the user sees the expired-draft behavior and chat remains usable.
result: pending

### 7. Permission Denied

expected: Unauthorized choices are not offered, and access lost between proposal and confirm prevents navigation or record creation.
result: pending

### 8. Streaming Authentication

expected: Streaming callbacks render action and confirm rows without `Authentication is not set` errors, and secured Jmix work runs as the logged-in user.
result: pass
verified: "Browser retest rendered both the action-choice row and the confirm row during streaming-backed interaction, with no Authentication-is-not-set error observed in the app log."

### 9. Provider And RAG Diagnostics

expected: Retrieval or embedding warnings do not block a non-RAG action-choice turn when the chat model succeeds; provider model failures are treated as configuration issues.
result: pass
verified: "The app log contained a best-effort embedding/retrieval warning, but the non-RAG Customer create/prefill action-choice flow still completed."

## Summary

total: 9
passed: 7
issues: 0
pending: 2
skipped: 0
blocked: 0

## Gaps

- truth: "Prefill form save should create the target record, delete the extraction draft, and not show an optimistic-lock error for AiExtractionDraft."
  status: resolved
  reason: "User reported that pressing OK in the prefilled Customer form creates the record but also shows an optimistic-lock error for the Extraction draft object."
  severity: major
  test: 5
  root_cause: "OpenFormWithDraftHandler saved the draft and then removed the stale pre-save draft instance, so Jmix could detect a version conflict after the draft save incremented the entity version."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/intent/OpenFormWithDraftHandler.java"
      issue: "confirmAndDeleteDraft saves AiExtractionDraft and removes the original stale instance instead of the saved instance returned by DataManager.save."
  missing:
    - "Resolved: remove the saved AiExtractionDraft instance returned by DataManager.save."
    - "Resolved: add a regression test that fails if draft removal uses the stale pre-save instance."
  debug_session: "inline"
  fixed_by: "Use the AiExtractionDraft instance returned by DataManager.save for DataManager.remove."
  verified: "Targeted tests passed and Playwright retest on localhost:8090 confirmed Prefill form OK no longer shows the draft optimistic-lock error."

- truth: "After the user selects an action choice, the stale disabled action-choice card should not remain anchored at the bottom of the chat surface."
  status: resolved
  reason: "User reported that the disabled action-choice card appeared fixed at the bottom after Create now had already completed."
  severity: minor
  test: 4
  root_cause: "ChatPanelFragment disabled action-choice buttons after selection but left the selected action row mounted below the scrolling MessageList."
  artifacts:
    - path: "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java"
      issue: "submitActionChoice disabled the selected action row but did not remove it after the action was selected."
  missing:
    - "Resolved: remove the action-choice row after Create now submits successfully."
    - "Resolved: remove the action-choice row after Prefill form creates the draft and before rendering the Open form confirmation row."
  debug_session: "inline"
  fixed_by: "Remove the selected action-choice row after a successful action selection."
  verified: "Regression test asserts selected action-choice rows are removed after selection."

## Gap Closure

- Plan 14-10 replaced the old static first-screen intent picker with a post-clarification action proposal flow.
- Plan 14-10 added constrained selected-action routing for Create now and Prefill form.
- Plan 14-10 restored captured current-user authentication inside streaming UI callbacks.
- Manual browser UAT is still required before this human check can be marked passed.
