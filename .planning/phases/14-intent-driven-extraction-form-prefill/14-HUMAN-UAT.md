---
status: pending_retest
phase: 14-intent-driven-extraction-form-prefill
source: [14-VERIFICATION.md, 14-10-PLAN.md, 14-10-SUMMARY.md]
started: 2026-05-08T17:32:04+07:00
updated: 2026-05-10T03:30:00+07:00
---

# Phase 14 Human UAT

## Current Test

number: 1
name: Post-clarification action-choice chat-to-form UAT
checklist: 14-UAT-CHECKLIST.md
expected: |
  Run the scenario checklist in 14-UAT-CHECKLIST.md. Chat must open without a static entity/action picker. The assistant must clarify missing required fields before action choices appear. Once enough data is available, the UI must render server-validated action choices. Create now may create a record only after that click. Prefill form must create a draft and then require the existing Open form to confirm click before the Jmix detail view opens. Save must run through normal Jmix validation, and successful Save must delete the draft.
awaiting: manual browser retest after 14-10 gap closure

## Tests

### 1. Initial Chat State

expected: Chat opens with no entity/action intent card, usable message input, no created record, and no draft.
result: pending

### 2. Missing Fields Are Clarified

expected: An incomplete create request makes the assistant ask for required fields. No action-choice row, mutation, or form navigation occurs yet.
result: pending

### 3. Action Choices After Clarification

expected: After required fields are provided, a server-validated action-choice row appears with choices allowed by the logged-in user's Jmix permissions.
result: pending

### 4. Create Now Path

expected: Clicking Create now enables the mutation path only for the selected action turn, creates the record under Jmix security, and records audit evidence.
result: pending

### 5. Prefill Form Path

expected: Clicking Prefill form creates an extraction draft, renders Open form to confirm, opens the Jmix detail view only after that click, preloads permitted values, saves through normal validation, and deletes the draft after Save.
result: pending

### 6. Expired Or Removed Draft

expected: An expired or removed draft cannot open a form or substitute another draft; the user sees the expired-draft behavior and chat remains usable.
result: pending

### 7. Permission Denied

expected: Unauthorized choices are not offered, and access lost between proposal and confirm prevents navigation or record creation.
result: pending

### 8. Streaming Authentication

expected: Streaming callbacks render action and confirm rows without `Authentication is not set` errors, and secured Jmix work runs as the logged-in user.
result: pending

### 9. Provider And RAG Diagnostics

expected: Retrieval or embedding warnings do not block a non-RAG action-choice turn when the chat model succeeds; provider model failures are treated as configuration issues.
result: pending

## Summary

total: 9
passed: 0
issues: 0
pending: 9
skipped: 0
blocked: 0

## Gap Closure

- Plan 14-10 replaced the old static first-screen intent picker with a post-clarification action proposal flow.
- Plan 14-10 added constrained selected-action routing for Create now and Prefill form.
- Plan 14-10 restored captured current-user authentication inside streaming UI callbacks.
- Manual browser UAT is still required before this human check can be marked passed.
