---
phase: 14-intent-driven-extraction-form-prefill
source: 14-08-PLAN.md
status: pending
created: 2026-05-08
---

# Phase 14 Manual UAT Checklist

Run after merge against a locally started app.

## Setup

- Start the app:
  `./gradlew bootRun`
- Open `http://localhost:8080`.
- Log in as `admin/admin`.
- Open the AI chat surface.

## Intent Picker

- Verify the intent picker is visible when the reference intent is enabled.
- Verify Auto is selected by default.
- Expected visible copy keys:
  - `chatView.intent.cardRow.ariaLabel`
  - `chatView.intent.auto.label`
  - `chatView.intent.auto.description`
  - `chatView.intent.customer-from-pdf.label`
  - `chatView.intent.customer-from-pdf.description`

## Customer Draft Creation

- Type or paste customer source text with a name, email, and phone.
- Optional: attach a customer document fixture with the same fields.
- Select the Customer intent card.
- Send the message.
- Verify the picker resets to Auto while the response is produced.
- Verify a confirm row appears in the message-list area, not in the attachment pane.
- Expected visible copy keys:
  - `chatView.intent.confirmButton.summary`
  - `chatView.intent.confirmButton`

## Confirm And Save

- Click the confirm button.
- Verify the Customer detail view opens.
- Verify the new Customer form is prefilled with the extracted name, email, and phone.
- Edit one field.
- Save the form.
- Verify the save succeeds through the normal Jmix detail-view validation path.
- Verify the draft row is deleted after Save.

## Expired Draft

- Create a draft.
- Let the draft expire or delete the draft row before clicking confirm.
- Click the confirm button.
- Verify the button becomes disabled and no other draft is opened.
- Expected visible copy key:
  - `chatView.intent.draftExpired`

## Permission Denied

- Log in as a user without access to the target Customer detail view.
- Trigger or reuse a Customer draft confirm row.
- Click the confirm button.
- Verify no form opens.
- Verify an error notification appears.
- Expected visible copy key:
  - `chatView.intent.permissionDenied`

## Misconfiguration And Payload Errors

- Disable or misconfigure the draft tool path if testing operator-failure handling.
- Verify the user-facing error uses:
  - `chatView.intent.configurationError`
- Trigger or simulate an invalid structured draft payload.
- Verify the user-facing error uses:
  - `chatView.intent.draftPayloadInvalid`

## Pass Criteria

- Intent picker appears only when at least one named intent is eligible.
- Named-intent turns expose only the draft-preparation path.
- Confirm row is rendered from structured tool payload, not a parsed prose summary.
- Navigation happens only after the user clicks the confirm button.
- Prefill writes only permitted attributes.
- Save deletes the draft.
- Expired and permission-denied paths do not open a different draft or form.
