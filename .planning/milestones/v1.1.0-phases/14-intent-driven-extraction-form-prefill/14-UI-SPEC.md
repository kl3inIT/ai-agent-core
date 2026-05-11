---
phase: 14
slug: intent-driven-extraction-form-prefill
status: updated
updated: 2026-05-10
---

# Phase 14 — UI Design Contract

This contract supersedes the earlier static intent-card picker design for side-effecting chat actions.

The corrected UX is post-clarification:

1. Opening chat shows no entity/action choice row.
2. The user asks for an operation, such as creating a product.
3. The assistant asks for missing required fields.
4. After enough data exists, the assistant calls `propose_action_choices`.
5. The server validates metadata, write access, view access, and required fields.
6. The UI renders explicit action choices only for a READY proposal.
7. The selected action controls the next turn's tool surface.

The old `IntentExtractor` registry remains available for backend named extraction paths, but it is not shown as a first-screen picker for create/update actions.

## Design System

| Property | Value |
|----------|-------|
| UI stack | Jmix Flow UI 2.8 + Vaadin Flow 24.8 + Lumo |
| Layout source | Existing `chat-panel-fragment.xml`; no new frontend framework |
| Controller owner | `ChatPanelFragment` |
| Renderer owner | `StreamEventRenderer` parses structured stream payloads only |
| Navigation owner | `OpenFormWithDraftHandler` remains the only form-navigation owner |
| Message bundles | `messages_en.properties` and `messages_vi.properties` |
| Stylesheet | `META-INF/resources/frontend/styles/ai-agent-chat.css` |

## Layout Contract

The existing chat layout is preserved:

- `messageListSlot` remains the mixed substrate for `MessageList` plus server-side rows.
- `messageInputSlot` remains the normal input row.
- `attachmentsPanel` and the split layout are unchanged.

The XML `intentCardRow` may remain declared for legacy named extraction support, but it is hidden on ready and does not drive the first-screen side-effecting action flow.

Post-proposal action choices are appended as a sibling row inside `messageListSlot`:

```text
messageListSlot
  vaadin-message-list
  div.ai-agent-action-choice
    span.ai-agent-action-choice__summary
    button Create now
    button Prefill form
```

## Action-Choice Row

| Element | Contract |
|---------|----------|
| Container | Server-side `Div` with class `ai-agent-action-choice`, `role="status"`, and `aria-live="polite"` |
| Summary | `chatView.actionChoice.summary`, formatted with the validated proposal instance name |
| Create now | Visible only when the validated choices include `create-now`; sends a selected action turn with `action:create-now` |
| Prefill form | Visible only when the validated choices include `prefill-form`; creates a draft and reuses the existing confirm row |
| Clear behavior | Removed by the existing `clearMessageList()` path together with draft confirm rows and attachment notice rows |

The action-choice row is not markdown. It is a server-side Vaadin row because button clicks must enter controller code and then route through constrained tool surfaces.

## Stream Payload Contract

`StreamEventRenderer` handles `StreamingEvent.ToolResult` as follows:

- `toolName == "propose_action_choices"` and payload `action == "show_action_choices"` with `status == "READY"` produces an `ActionProposalPayload` side-channel marker.
- Non-READY proposal results produce no UI choice row.
- Malformed proposal payloads produce no choice row.
- `toolName == "prepare_form_draft"` continues to parse only `open_form_with_draft` payloads for the existing confirm-row flow.
- The renderer must not import Jmix navigation APIs.

## Tool-Surface Contract

| Turn type | Tool surface |
|-----------|--------------|
| Default planning turn | Read/link tools plus `propose_action_choices`; no built-in mutation tools and no `prepare_form_draft` |
| Named extraction intent | Exactly `prepare_form_draft` |
| `action:create-now` | Read/link tools plus mutation tools; no `prepare_form_draft` |
| `action:prefill-form` | Draft/form path only |

The UI selection, not the model's first response, determines whether the app mutates data or prepares a form draft.

## Prefill Confirm Row

The existing `ai-agent-intent-confirm` row remains the confirmation boundary:

- It appears only after `Prefill form` creates an `AiExtractionDraft`.
- It opens the Jmix detail view only when the user clicks `chatView.intent.confirmButton`.
- Saving the detail view is still the only persistence confirmation for the prefilled form.
- Expired drafts disable the button and use `chatView.intent.draftExpired`.
- Permission denial uses `chatView.intent.permissionDenied`.

## Copywriting Contract

New action-choice keys must exist in both locale bundles:

- `chatView.actionChoice.summary`
- `chatView.actionChoice.createNow`
- `chatView.actionChoice.prefillForm`
- `chatView.actionChoice.missingFields`
- `chatView.actionChoice.invalidProposal`

Existing draft-confirm keys remain required:

- `chatView.intent.confirmButton.summary`
- `chatView.intent.confirmButton`
- `chatView.intent.draftExpired`
- `chatView.intent.permissionDenied`
- `chatView.intent.draftPayloadInvalid`

## Accessibility

- The action-choice container uses `role="status"` and `aria-live="polite"`.
- Buttons use visible localized labels as accessible labels.
- Choices are redundant in text and icon, not icon-only.
- The prefill confirm row keeps its existing status-row and button semantics.

## Acceptance Criteria

- No action/entity choice row appears on initial chat open.
- `ChatPanelFragment.onReady()` hides the legacy `intentCardRow` rather than refreshing it into view.
- Action choices are rendered only from a READY `propose_action_choices` stream payload.
- Non-READY and malformed proposal payloads do not render choices.
- `Create now` sends `action:create-now`; `Prefill form` creates a draft and then renders the existing confirm row.
- All visible copy is message-bundle backed in English and Vietnamese.
- `StreamEventRenderer` has no navigation imports.
- `OpenFormWithDraftHandler` remains the sole form navigation owner for draft confirmation.
- `clearMessageList()` removes action-choice rows.
- Streaming UI updates that touch Jmix-secured state run with the captured current-user authentication.
