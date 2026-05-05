---
status: testing
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
started: 2026-05-05
updated: 2026-05-05
---

# Phase 12 UAT — Configurable Chat Surfaces

Manual checks for the two configurable chat surfaces, shared conversation state, admin rollout controls, and conversation titles.

## Preconditions

- Application starts successfully with the AI Agent add-on installed.
- An admin user can open AI Agent settings views.
- A regular chat-capable user can open the chat route and conversation list.
- At least one working chat model profile is available for normal chat replies.

## Current Test

number: 1
name: Admin Settings
expected: |
  In AI Agent → UI settings, both surfaces (Full route, Header button) are listed.
  Enabling both with Full route as default saves and persists across navigation.
  Toggling to only Header button (default = Header button) saves and persists.
  Toggling to only Full route (default = Full route) saves and persists.
  The view BLOCKS saving with no enabled surface, or with a default surface that is not enabled.
awaiting: user response

## Tests

### 1. Admin Settings
covers: SURF-02, SURF-08, ENT-06
expected: |
  Log in as admin → AI Agent → UI settings.
  Both surfaces (Full route, Header button) are listed.
  Enable both, default = Full route → save → leave view → return → settings persist as a single configuration row.
  Repeat with only Header button enabled (default = Header button) → persists.
  Repeat with only Full route enabled (default = Full route) → persists.
  View prevents saving with no enabled surface OR a default surface that is not enabled.
result: pending

### 2. Regular User Surface Visibility
covers: SURF-01, SURF-03, SURF-08, SURF-09
expected: |
  As admin, enable both surfaces.
  As regular chat-capable user: AI Agent chat menu item is visible; header chat button is visible on non-chat routes; header chat button is HIDDEN on the full chat route.
  Admin disables Header button (Full route enabled) → regular user sees menu, no header button.
  Admin disables Full route (Header button enabled) → regular user sees header button on normal routes, no chat menu.
result: pending

### 3. Direct Route Gate
covers: SURF-03, SURF-09
expected: |
  As admin, disable Full route (Header button enabled).
  As regular chat-capable user, navigate directly to /ai-agent/chat.
  App forwards away from the disabled route AND shows a localized notice that chat is available through the header button only.
  Switch locale to Vietnamese (if host supports locale switching) → notice is in Vietnamese.
result: pending

### 4. Cross-Surface Continuity
covers: SURF-04, SURF-05, SURF-06, SURF-09, TEST-14
expected: |
  As admin, enable both surfaces.
  As regular chat-capable user: open full chat route → send message → wait for assistant reply.
  Navigate away → open header chat dialog → prior conversation + messages are visible.
  Send second message from dialog → wait for assistant reply.
  Open conversation list → exactly ONE conversation for this task (not two).
  Reopen that conversation → both user turns + both assistant replies are present.
result: pending

### 5. Dialog Lifecycle
covers: SURF-06, SURF-07
expected: |
  Open header chat dialog from a normal app route.
  Dialog is non-modal, resizable, draggable (if shell exposes the handle), anchored near top-right.
  Close + reopen → active conversation still selected.
  With dialog open, navigate to another app route → dialog stays open and attached.
  Start a new chat from the dialog → ONLY that action clears active conversation state.
result: pending

### 6. Title Generation
covers: SURF-09 title scope, AI-SPEC title quality, manual edit precedence
expected: |
  Start a new conversation with a specific English business question → wait for first assistant reply + brief delay.
  Title becomes short, specific, no tool/provider names, no entity internals, no quotes, no trailing punctuation.
  Repeat with Vietnamese conversation content → title is Vietnamese.
  Edit title manually via pencil action → continue conversation → wait for any async title processing.
  Manually edited title is NOT overwritten by async generation.
result: pending

### 7. Title Failure Isolation
covers: AI-SPEC failure isolation, operational auditability
expected: |
  In a test environment, configure title generation so the title model call FAILS while normal chat still works.
  Start a new conversation → wait for first assistant reply.
  Chat reply remains visible; NO title error shown to chat user.
  As admin, open tool call audit view → find conversation_title entry → outcome = error, no raw provider stack traces or prompt content exposed to the chat user.
result: pending

## Summary

total: 7
passed: 0
issues: 0
pending: 7
skipped: 0
blocked: 0

## Gaps

[none yet]

## Traceability

| Success Criterion | Test |
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
