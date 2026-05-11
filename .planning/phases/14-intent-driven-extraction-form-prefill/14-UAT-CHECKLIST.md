---
phase: 14-intent-driven-extraction-form-prefill
source: 14-10-PLAN.md
status: pending
updated: 2026-05-10
---

# Phase 14 Manual UAT

## Purpose

Validate the corrected side-effecting chat pattern:

1. Chat opens without a static entity/action picker.
2. The assistant asks for missing required data before offering actions.
3. The UI renders action choices only after a server-validated ready proposal.
4. The user-selected action controls the next tool surface.
5. Prefill still routes through the existing Jmix detail-view confirmation and save path.

## Setup

- Start the app with `./gradlew bootRun`.
- Open `http://localhost:8088`.
- Log in as `admin/admin`, unless a scenario says to use a restricted user.
- Open the AI chat surface.
- Use a target entity that the logged-in user can create and whose detail view is registered.

## Scenario 1 - Initial Chat State

**Steps**

1. Open the chat surface.
2. Do not send a message.

**Expected**

- No entity/action intent card should appear just because the chat opened.
- The message input is ready for typing.
- No record is created.
- No draft is created.

**Result:** pending

## Scenario 2 - Missing Fields Are Clarified

**Steps**

1. Send a create request with incomplete data, for example `Create a product`.
2. Wait for the assistant response.

**Expected**

- The assistant asks for missing required fields.
- No `Create now` action is shown yet.
- No `Prefill form` action is shown yet.
- No mutation tool creates a record.
- No form opens.

**Result:** pending

## Scenario 3 - Action Choices After Clarification

**Steps**

1. Continue the same conversation from Scenario 2.
2. Provide the required fields requested by the assistant.
3. Wait for the assistant response and streamed tool results.

**Expected**

- The app validates a ready action proposal server-side.
- The message list renders an action-choice row after the assistant response.
- The available choices match the user's permissions.
- For a create-capable user, the row includes `Create now` and `Prefill form`.
- The row does not appear while the proposal has missing fields or validation errors.

**Result:** pending

## Scenario 4 - Create Now Path

**Steps**

1. From a ready action-choice row, click `Create now`.
2. Let the selected action turn finish.
3. Open the target entity list or detail screen and inspect the result.

**Expected**

- Mutation-capable tools are available only after the `Create now` click.
- The record is created only during the selected action turn.
- Normal Jmix entity, attribute, and row-level security still apply.
- The audit trail records the selected mutation path.

**Result:** pending

## Scenario 5 - Prefill Form Path

**Steps**

1. Repeat the clarified create request or start a fresh one.
2. From a ready action-choice row, click `Prefill form`.
3. Wait for the inline confirm row.
4. Click `Open form to confirm`.
5. Inspect the opened Jmix detail view.
6. Edit one field and click Save.

**Expected**

- `Prefill form` creates an extraction draft instead of saving the final entity.
- The inline `Open form to confirm` row appears in the message-list area.
- The Jmix detail view opens only after the confirm click.
- The form is prefilled with the collected values.
- Normal Jmix validation runs on Save.
- Save succeeds for valid data.
- The draft is deleted after successful Save.

**Result:** pending

## Scenario 6 - Expired Or Removed Draft

**Steps**

1. Create a prefill-form draft.
2. Let the draft expire or delete the draft row before clicking `Open form to confirm`.
3. Click the confirm button.

**Expected**

- No form opens.
- No alternate draft is opened.
- The confirm button becomes disabled or the user sees the draft-expired notification.
- The chat remains usable for a new request.

**Result:** pending

## Scenario 7 - Permission Denied

**Steps**

1. Log in as a user without create permission or without access to the target detail view.
2. Trigger a create request through the chat.
3. If a prefill confirm row is available, click `Open form to confirm`.

**Expected**

- Choices that require missing permissions are not offered.
- If access is lost between proposal and confirm, no form opens.
- The user receives a permission-denied notification.
- No record is created outside Jmix security.

**Result:** pending

## Scenario 8 - Streaming Authentication

**Steps**

1. Run Scenarios 2 through 5 while watching the app logs.
2. Pay attention to streamed tool results and UI row insertion.

**Expected**

- No `Authentication is not set` stack trace appears.
- Action-choice rows and confirm rows render during streaming callbacks.
- Secured Jmix loaders/actions still run as the logged-in user.

**Result:** pending

## Scenario 9 - Provider And RAG Diagnostics

**Steps**

1. Run a non-RAG create/prefill request with normal chat-model configuration.
2. If embedding or retrieval configuration is unavailable, observe the chat behavior.

**Expected**

- Retrieval or embedding warnings are best-effort diagnostics.
- A non-RAG action-choice turn can proceed when the chat model call succeeds.
- Provider model errors are treated as configuration issues, not as action-choice UI failures.

**Result:** pending

## Scenario 10 - Cancelled Pending Action Cannot Still Mutate

**Steps**

1. Ask the assistant to create a Customer with enough data to render action choices.
2. Do not click an action button yet.
3. Send a cancellation message such as `Do not create that customer anymore`.
4. Try to click the old `Create now` button if it is still visible.
5. Inspect the Customer list.

**Expected**

- The assistant may acknowledge the cancellation.
- The cancelled action-choice row is removed or disabled.
- The cancelled proposal cannot create a record after cancellation.
- No Customer record is created for the cancelled proposal.

**Result:** pending

## Scenario 11 - Ambiguous Multi-Record Quantity

**Steps**

1. Send a request with an ambiguous count, for example `Create 2 or 3 customers named A, B, C`.
2. Wait for the assistant response.

**Expected**

- The assistant asks the user to choose the exact count, or rejects the batch gracefully if multi-row proposals are unsupported.
- The assistant does not silently choose a count.
- No generic tool argument or deserialization error is shown to the user.
- No record is created before an explicit valid action choice.

**Result:** pending

## Scenario 12 - Full-Page Prefill Cancel Preserves Conversation State

**Steps**

1. Open the full chat page.
2. Create a ready prefill proposal.
3. Click `Prefill form`, then `Open form to confirm`.
4. Click Cancel in the detail view.
5. Choose `Don't save`.
6. Return to the chat page.

**Expected**

- The user returns to the same chat conversation.
- Previous messages and pending confirm/action rows remain available as appropriate.
- The chat does not reset to a fresh conversation unless the user explicitly starts one.

**Result:** pending

## Scenario 13 - User-Facing Transcript Hides Internal Action Payloads

**Steps**

1. Create a ready action proposal.
2. Select an action such as `Create now`.
3. Close and reopen the floating chat dialog, or reload the chat history.

**Expected**

- User-visible chat history does not contain selected-action routing text.
- Proposal ids, internal entity names, and collected JSON payloads are not rendered as normal chat messages.
- The user only sees business-level responses.

**Result:** pending

## Scenario 14 - Create Now Double Click Idempotency

**Steps**

1. Create a ready action proposal.
2. Double-click `Create now`.
3. Inspect the target list.

**Expected**

- At most one selected-action turn is submitted.
- At most one target record is created.
- The selected action row is removed or disabled quickly enough to prevent duplicate side effects.

**Result:** pending

## Pass Criteria

- Scenarios 1 through 8 pass.
- Scenario 9 does not block the action-choice flow when the chat model itself works.
- No static first-screen entity/action picker is required or accepted as proof of success.
- No record is created before explicit user action selection.
- No form opens before the existing confirm click.
- Manual failures are recorded in `14-HUMAN-UAT.md` before planning another gap closure.
