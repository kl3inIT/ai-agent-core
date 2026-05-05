---
phase: 12-configurable-chat-surfaces
verified: 2026-05-05T06:37:06Z
status: passed
score: "4/4 roadmap success criteria verified; 12/12 requirement IDs satisfied; 7/7 UAT checks passed"
overrides_applied: 0
re_verification:
  prior_status: issues_found
  previous_score: "Phase code review found 3 critical issues and 2 warnings"
  issues_closed:
    - "Chat dialog access and admin policies corrected; AiAgentAdminRole now grants AiAgent_ChatDialog."
    - "ChatSurfaceMounter closes/clears stale dialogs when HEADER_BUTTON is disabled, the dialog view is denied, or the full chat route is active; after-close cleanup is registered on the DialogWindow."
    - "Conversation title sanitizer rejects the NEW_CONVERSATION sentinel after normalization."
    - "AiUiSettings audit annotations and singleton audit coverage added."
    - "UAT gaps for settings re-enable persistence, validation feedback, dialog chrome, and full-route menu hiding were fixed and re-verified."
  remaining_issues: []
  regressions: []
---

# Phase 12: Configurable Chat Surfaces Verification Report

**Phase Goal:** One `ChatPanelFragment`, one `ChatService`, one `AiConversation` per user-session, surfaced through two admin-toggleable presentations (`FULL_ROUTE` and `HEADER_BUTTON` dialog) with continuous conversation state across surface switches.
**Verified:** 2026-05-05T06:37:06Z
**Status:** passed
**Re-verification:** Yes - after code-review and UAT gap fixes.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Admin can toggle `FULL_ROUTE` and `HEADER_BUTTON`, choose the default surface, and the host shell renders only enabled surfaces without host code edits. | VERIFIED | `AiUiSettings`, `AiUiSettingsService`, `AiUiSettingsDetailView`, `ChatSurfaceMounter`, and `AiAgentAdminRole` are present. UAT Test 1 and Test 2 passed, including both-surface, header-only, full-route-only, no-surface validation, and default-not-enabled validation paths. |
| 2 | A user can switch from full route to header dialog mid-session and continue the same conversation id with the same JDBC-backed history. | VERIFIED | `ChatPanelFragmentSurfaceSwitchTest` gates TEST-14 with same UUID, one `AiConversation`, four projected `AiMessage` rows, and four Spring AI JDBC memory rows. UAT Test 4 passed with real route-to-dialog continuity and exactly one conversation. |
| 3 | Header button opens a non-modal Jmix `DialogWindow` anchored top-right, using the shared `ChatPanelFragment` instead of the deferred raw Vaadin launcher. | VERIFIED | `ChatDialogView` and `ChatSurfaceMounter` create a Jmix `DialogWindow`; UAT Test 5 passed for header open, non-modal behavior, top-right placement, route-navigation persistence, and clean dialog chrome. |
| 4 | There is exactly one active user-session conversation regardless of surface, carried by `AiChatSessionState`. | VERIFIED | `AiChatSessionState` is `@VaadinSessionScope`, stores only the active conversation id and listeners, and is consumed by `ChatPanelFragment`. UAT Test 4 confirmed one conversation row across full-route and dialog turns. |

**Score:** 4/4 roadmap success criteria verified.

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `AiChatSurface.java` | Two values: `FULL_ROUTE`, `HEADER_BUTTON` | VERIFIED | EnumClass-backed surface ids are used by settings and mounter code. |
| `AiUiSettings.java` + `080-ai-ui-settings.xml` | Singleton agentstore settings with text-backed enabled surface ids, default surface, and audit fields | VERIFIED | Entity has UUID, version, instance name, helper methods, no `enabledSurfaces` JavaBean collection property, Jmix audit annotations, and Liquibase DDL. |
| `AiUiSettingsService.java` | No-cache singleton loader/creator | VERIFIED | Loads by fixed singleton id with trusted system writes and duplicate-create recovery. |
| `AiUiSettingsDetailView.java` + XML | Admin settings UI | VERIFIED | Singleton detail view persists enabled/default surface choices, blocks invalid combinations, and surfaces validation feedback. |
| `AiAgentAdminRole.java` | Admin settings, chat, dialog, and menu policies | VERIFIED | Grants AiUiSettings READ/UPDATE, settings menu/view, full chat, and `AiAgent_ChatDialog`; no CREATE/DELETE for settings. |
| `AiChatSessionState.java` | Vaadin session-scoped conversation id state | VERIFIED | Stores current conversation id and listener registrations only; no stream/run authority. |
| `AiChatUIState.java` | UI-scoped dialog handle state | VERIFIED | Holds the per-UI dialog instance and is resolved through `ObjectProvider` inside active UI scope. |
| `ChatDialogView.java` + XML | Header dialog shell over `ChatPanelFragment` | VERIFIED | No-route StandardView shell around the shared fragment; dialog close cleanup is handled by the mounter. |
| `ChatSurfaceMounter.java` | Header button mount, visibility gates, dialog lifecycle, menu/full-route gates | VERIFIED | Gates by settings, route, and `UiShowViewContext`; hides the full-route menu when disabled; closes stale dialogs when unavailable. |
| `ChatView.java` | Full-route disable gate | VERIFIED | Disabled full route forwards away and shows localized EN/VI notice while preserving Jmix before-enter lifecycle on allowed navigation. |
| `AiConversationTitleService.java` + prompt | Async fail-silent title generation and sanitizer | VERIFIED | Generates short titles, audits success/error, rejects sentinel/internal titles, and does not affect normal chat on title failures. |
| `ChatPanelFragment.java` + XML | Shared fragment state, manual title edit, hidden Phase 13 attachment slot | VERIFIED | Shared conversation id synchronization, manual title edit precedence, single title row, and hidden `attachmentsPanel` are present. |

### Code Review Closure

| Finding | Status | Evidence |
|---|---|---|
| CR-01: Open dialog survives disabled/conflicting surface state | CLOSED | `ChatSurfaceMounter` now closes and clears dialogs when the header surface is unavailable; test coverage added in `ChatSurfaceMounterTest`. |
| CR-02: Admin role cannot open header dialog | CLOSED | `AiAgentAdminRole` includes `AiAgent_ChatDialog`; `AdminViewAccessTest` asserts admin access. |
| CR-03: Auto-title accepts `NEW_CONVERSATION` sentinel | CLOSED | `AiConversationTitleService.sanitizeTitle()` rejects `NEW_CONVERSATION`; `AiConversationTitleServiceTest` covers plain, quoted, and trailing punctuation variants. |
| WR-01: Dialog handle not cleared for non-button close paths | CLOSED | `DialogWindow.addAfterCloseListener(...)` clears the UI-scoped dialog handle. |
| WR-02: AiUiSettings audit columns never populated | CLOSED | `AiUiSettings` has Jmix audit annotations; `AiUiSettingsServiceSingletonTest` asserts creation/update audit fields. |

### Behavioral Spot-Checks

| Behavior | Command / Evidence | Result | Status |
|---|---|---|---|
| Full module regression gate | `.\gradlew.bat :ai-agent:ai-agent:test` | BUILD SUCCESSFUL in 4m 45s | PASS |
| UAT suite | `.planning/phases/12-configurable-chat-surfaces/12-UAT.md` | 7 total, 7 passed, 0 issues, 0 pending, 0 skipped, 0 blocked | PASS |
| Surface toggles and visibility | UAT Tests 1-3 | Admin settings persist; header/menu/full-route gates work; EN and VI disabled-route notices render | PASS |
| Cross-surface continuity | UAT Test 4 | Full-route and dialog turns stay in one conversation with both replies visible | PASS |
| Dialog lifecycle | UAT Test 5 | Non-modal top-right dialog stays attached across navigation and reopens cleanly | PASS |
| Title behavior and failure isolation | UAT Tests 6-7 | Generated titles are specific/localized; manual title is preserved; title model failure is silent to chat users and audited safely | PASS |

### Requirements Coverage

| Requirement | Description | Status | Evidence |
|---|---|---|---|
| SURF-01 | Two chat presentation surfaces over same backend and fragment | SATISFIED | `FULL_ROUTE` + `HEADER_BUTTON`; shared `ChatPanelFragment`; UAT 2, 4, 5. |
| SURF-02 | `AiUiSettings` singleton in agentstore with deterministic text-backed enabled ids and audit fields | SATISFIED | Entity, Liquibase, service, audit annotations, service tests. |
| SURF-03 | `ChatSurfaceMounter` injects configured header button and reads admin toggles | SATISFIED | Mounter source/tests; UAT 2-3. |
| SURF-04 | `AiChatSessionState` carries active conversation id across surface switches | SATISFIED | Session state tests and TEST-14 continuity test. |
| SURF-05 | One ChatService, one AiConversation, one active fragment per UI tab/session conversation | SATISFIED | TEST-14 and UAT 4. |
| SURF-06 | Header button opens non-modal top-right Jmix DialogWindow | SATISFIED | `ChatDialogView`, `ChatSurfaceMounter`, UAT 5. |
| SURF-07 | Jmix DialogWindow replaces raw Vaadin stacking mitigation scope | SATISFIED | DialogWindow implementation; no raw bottom-right launcher introduced. |
| SURF-08 | Admin Flow UI for runtime surface toggles, admin-only | SATISFIED | Settings view/menu/policies and UAT 1. |
| SURF-09 | Cross-surface continuity test with same conversation and JDBC memory rows | SATISFIED | `ChatPanelFragmentSurfaceSwitchTest` and UAT 4. |
| SURF-10 | Compact-mode work deferred; both surfaces use full fragment layout | SATISFIED | Roadmap/requirements updated; no compact surface introduced. |
| ENT-06 | AiUiSettings entity contract | SATISFIED | Entity, Liquibase, i18n, internal metadata exclusion, tests. |
| TEST-14 | Route-to-dialog continuity gate | SATISFIED | `ChatPanelFragmentSurfaceSwitchTest`; full module test passed. |

No orphaned Phase 12 requirement IDs were found. The Phase 12 requirements set in ROADMAP is fully covered.

### Human Verification

Manual browser UAT was completed on 2026-05-05 with Playwright-assisted evidence in `12-UAT.md`. All four discovered UAT gaps were fixed and re-verified:

- Settings re-enable persistence: fixed in `7ebf979`.
- Default surface validation feedback: fixed in `7ebf979`.
- Dialog chrome redundancy: fixed in `b39a500`.
- Disabled full-route menu hiding: fixed in `b3dcf74`.

### Anti-Patterns Found

No blocker anti-patterns remain for the Phase 12 shipping surface. The full module test suite passes after code-review and UAT gap closure.

### Closure Summary

Phase 12 is verified and ready to ship. The configured chat surfaces satisfy the two-surface scope, admin rollout controls, route/dialog visibility gates, cross-surface continuity, title generation behavior, and fail-silent title audit requirements. Code-review findings and UAT gaps have been closed, and the module regression suite is green.

---

_Verified: 2026-05-05T06:37:06Z_
_Verifier: the agent (gsd-ship inline verification gate)_
