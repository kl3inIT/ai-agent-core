---
status: ready-for-human
phase: 12-configurable-chat-surfaces
source:
  - 12-SPEC.md
  - 12-AI-SPEC.md
  - 12-01-SUMMARY.md
  - 12-02-SUMMARY.md
  - 12-03-SUMMARY.md
  - 12-04-SUMMARY.md
  - 12-05-SUMMARY.md
  - 12-06-PLAN.md
updated: 2026-05-02
---

# Phase 12 UAT Checklist

Manual checks for the two configurable chat surfaces, shared conversation state,
admin rollout controls, and conversation titles.

## Preconditions

- Application starts successfully with the AI Agent add-on installed.
- An admin user can open AI Agent settings views.
- A regular chat-capable user can open the chat route and conversation list.
- At least one working chat model profile is available for normal chat replies.

## Manual Checks

### 1. Admin Settings

**Covers:** SURF-02, SURF-08, ENT-06

1. Log in as an admin.
2. Open AI Agent -> UI settings.
3. Verify both surfaces are listed: Full route and Header button.
4. Enable both surfaces and select Full route as default.
5. Save settings, leave the view, return to UI settings.
6. Verify the saved enabled surfaces and default surface persist.
7. Repeat with only Header button enabled and default set to Header button.
8. Repeat with only Full route enabled and default set to Full route.

**Expected:** Settings persist as a single configuration row. The view prevents
saving with no enabled surface or with a default surface that is not enabled.

### 2. Regular User Surface Visibility

**Covers:** SURF-01, SURF-03, SURF-08, SURF-09

1. As admin, enable both Full route and Header button.
2. Log in as a regular chat-capable user.
3. Verify the AI Agent chat menu item is visible.
4. Navigate away from the full chat route.
5. Verify the header chat button is visible.
6. Navigate to the full chat route.
7. Verify the header chat button is hidden on that route.
8. As admin, disable Header button and keep Full route enabled.
9. Log in again as the regular user and verify the header button is hidden while the chat menu remains visible.
10. As admin, disable Full route and keep Header button enabled.
11. Log in again as the regular user and verify the chat menu is hidden while the header button remains visible on normal app routes.

**Expected:** Button, menu, and route availability match the admin toggle and
normal Jmix view/menu permissions.

### 3. Direct Route Gate

**Covers:** SURF-03, SURF-09

1. As admin, disable Full route and keep Header button enabled.
2. As a regular chat-capable user, navigate directly to `/ai-agent/chat`.
3. Observe the resulting page and notification.
4. Switch locale to Vietnamese if the host app supports locale switching and repeat.

**Expected:** The app forwards away from the disabled route and shows a
localized notice that chat is available through the header button only.

### 4. Cross-Surface Continuity

**Covers:** SURF-04, SURF-05, SURF-06, SURF-09, TEST-14

1. As admin, enable both surfaces.
2. As a regular chat-capable user, open the full chat route.
3. Send a first message and wait for the assistant response.
4. Navigate away from the full chat route.
5. Open the header chat dialog.
6. Verify the prior conversation and messages are visible.
7. Send a second message from the dialog and wait for the assistant response.
8. Open the conversation list.
9. Verify there is one conversation for the task, not two.
10. Reopen that conversation and verify both user turns and both assistant replies are present.

**Expected:** The same conversation continues across route and dialog. No
duplicate conversation is created during the surface switch.

### 5. Dialog Lifecycle

**Covers:** SURF-06, SURF-07

1. Open the header chat dialog from a normal app route.
2. Verify the dialog is non-modal, resizable, draggable if the browser/Jmix shell exposes the handle, and anchored near the top right.
3. Click the header chat button again or close the dialog.
4. Reopen it and verify the active conversation is still selected.
5. With the dialog open, navigate to another app route.
6. Verify the dialog remains open and attached after navigation.
7. Start a new chat from the dialog and verify only that action clears the active conversation state.

**Expected:** Closing the dialog hides the UI only. Route navigation does not
dispose the dialog or clear the current conversation.

### 6. Title Generation

**Covers:** SURF-09 title scope, AI-SPEC title quality and failure isolation

1. Start a new conversation with a specific English business question.
2. Wait for the first assistant response and allow a short delay for auto-title generation.
3. Verify the title becomes short, specific, and does not contain tool names, provider names, entity internals, quotes, or trailing punctuation.
4. Repeat with Vietnamese conversation content.
5. Verify the generated title is Vietnamese when the conversation is Vietnamese.
6. Edit the title manually with the pencil action.
7. Continue the conversation and wait for any async title processing.
8. Verify the manually edited title is not overwritten.

**Expected:** Auto-title is useful and restrained. Manual title edits win over
async generation.

### 7. Title Failure Isolation

**Covers:** AI-SPEC failure isolation, operational auditability

1. In a test environment, configure title generation so the title model call fails while normal chat still works.
2. Start a new conversation and wait for the first assistant response.
3. Verify the chat response remains visible and no title error is shown to the chat user.
4. As admin, open the tool call audit view.
5. Find the `conversation_title` audit entry.
6. Verify the entry has an error outcome and does not expose raw provider stack traces or prompt content to the chat user.

**Expected:** Title failures are visible to operators through audit/logs but
never interrupt the user-facing chat path.

## Traceability

| Success Criterion | Manual Check |
| --- | --- |
| `AiChatSurface` exposes `FULL_ROUTE` and `HEADER_BUTTON` | 1, 2 |
| `AiUiSettings` singleton persists enabled/default surfaces | 1 |
| Admin-only UI settings view | 1, 2 |
| Header button injection and visibility gates | 2 |
| `ChatDialogView` non-modal Jmix dialog surface | 5 |
| Header button toggle keeps conversation state | 5 |
| Dialog persists across route navigation | 5 |
| `AiChatSessionState` carries active conversation id | 4, 5 |
| Disabled full route forwards with notice | 3 |
| Cross-surface continuity with one conversation | 4 |
| Auto-title generation, sanitization, locale, audit | 6, 7 |
| Pencil edit prevents async clobber | 6 |
| Locale messages visible in both EN and VI | 3, 6 |
| Admin role policies cover UI settings | 1, 2 |
| Two-surface scope documented | 1, 2, 4, 5 |

## Completion Record

| Item | Result | Notes |
| --- | --- | --- |
| Admin settings checks | pending | Human browser pass required. |
| Regular user visibility checks | pending | Human browser pass required. |
| Cross-surface continuity | pending | Automated TEST-14 covers the deterministic gate; browser pass still required. |
| Dialog lifecycle | pending | Human browser pass required. |
| Title behavior | pending | Human and audit review required. |
