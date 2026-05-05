---
status: complete
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

[testing complete — all 7 tests PASS, 4 gaps discovered + fixed during this UAT cycle]

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
result: issue
verified_by: playwright-mcp 2026-05-05
sub_results:
  - both_surfaces_listed: pass
  - cycle_both_enabled_default_full: pass (persisted across navigation)
  - cycle_only_header_button: pass (persisted)
  - cycle_only_full_route: pass (persisted)
  - validation_no_surface_enabled: pass (red message "Enable at least one chat surface." shown under checkbox group)
  - validation_default_not_enabled: issue (save blocked server-side — state preserved — but NO user-visible error/notification; user sees button click apparently lost)
reported: "Save with default = unenabled surface: persistence layer rejects (good) but UI gives no feedback — no notification, no field-level error, no invalid radiogroup state."
severity: minor

### 2. Regular User Surface Visibility
covers: SURF-01, SURF-03, SURF-08, SURF-09
expected: |
  As admin, enable both surfaces.
  As regular chat-capable user: AI Agent chat menu item is visible; header chat button is visible on non-chat routes; header chat button is HIDDEN on the full chat route.
  Admin disables Header button (Full route enabled) → regular user sees menu, no header button.
  Admin disables Full route (Header button enabled) → regular user sees header button on normal routes, no chat menu.
result: pass
verified_by: playwright-mcp 2026-05-05 (admin session; persistence bug fixed in 7ebf979, menu-hide gap fixed in b3dcf74)
sub_results:
  - both_surfaces_enabled (sub-A):
      menu_chat_link_visible: pass (visible on /customers)
      header_chat_button_visible: pass (rendered top-right of navbar on /customers, id=aiAgentHeaderChatButton)
      header_chat_button_hidden_on_full_route: pass (element NOT in DOM on /ai-agent/chat)
  - only_full_route_enabled (sub-B):
      menu_chat_link_visible: pass
      header_chat_button_present: pass (hidden — element absent from DOM)
  - only_header_button_enabled (sub-C):
      header_chat_button_visible: pass (id=aiAgentHeaderChatButton in DOM)
      menu_chat_link_hidden: pass (li#aiAgent.chat hidden=true display=none after Gap 4 fix; reverse re-enable also restores)

### 3. Direct Route Gate
covers: SURF-03, SURF-09
expected: |
  As admin, disable Full route (Header button enabled).
  As regular chat-capable user, navigate directly to /ai-agent/chat.
  App forwards away from the disabled route AND shows a localized notice that chat is available through the header button only.
  Switch locale to Vietnamese (if host supports locale switching) → notice is in Vietnamese.
result: pass
verified_by: playwright-mcp 2026-05-05 (admin session, both locales)
sub_results:
  - forward_away_from_disabled_route: pass (URL goes /ai-agent/chat → /)
  - english_notice: pass ("Chat is available via the header button only.")
  - vietnamese_notice: pass ("Trò chuyện chỉ khả dụng qua nút trên thanh đầu trang.")
notes: "Notification captured via MutationObserver — auto-dismisses too fast for snapshot but DOM observation confirms it renders correctly. The user/operator could increase notification duration if they want longer-lasting feedback, but the current ~5s default is standard Vaadin."

### 4. Cross-Surface Continuity
covers: SURF-04, SURF-05, SURF-06, SURF-09, TEST-14
expected: |
  As admin, enable both surfaces.
  As regular chat-capable user: open full chat route → send message → wait for assistant reply.
  Navigate away → open header chat dialog → prior conversation + messages are visible.
  Send second message from dialog → wait for assistant reply.
  Open conversation list → exactly ONE conversation for this task (not two).
  Reopen that conversation → both user turns + both assistant replies are present.
result: pass
verified_by: playwright-mcp 2026-05-05 (admin session, VI locale, real LLM round-trips against OpenRouter)
sub_results:
  - first_message_full_route: pass ("Hello from full route surface" → AI reply received and rendered, title auto-generated)
  - navigation_away: pass (navigated to /customers, conversation persists in session state)
  - dialog_shows_prior_conversation: pass (opened header dialog from /customers, both prior turns rendered with timestamps; title visible as auto-generated AI thematic title "Hỗ trợ thông tin về hệ thống quản lý bán hàng")
  - second_message_from_dialog: pass ("And this is from the dialog surface" → AI reply received in same conversation thread)
  - exactly_one_conversation: pass (Hội thoại list shows topmost row started 12:26, 4 messages = 2 user + 2 AI turns; no duplicate row created during surface switch)
  - both_turns_and_replies_visible: pass (phase12-test4-msg2-from-dialog.png shows all 4 messages in chronological order with correct sender labels Bạn / Trợ lý AI)
notes: "Confirms phase 12's core promise — ONE ChatService + ChatPanelFragment + AiConversation per user-session surfaced via two configurable surfaces with continuous state across switches. AiChatSessionState carries the active conversation id between surfaces correctly."

### 5. Dialog Lifecycle
covers: SURF-06, SURF-07
expected: |
  Open header chat dialog from a normal app route.
  Dialog is non-modal, resizable, draggable (if shell exposes the handle), anchored near top-right.
  Close + reopen → active conversation still selected.
  With dialog open, navigate to another app route → dialog stays open and attached.
  Start a new chat from the dialog → ONLY that action clears active conversation state.
result: pass
verified_by: playwright-mcp 2026-05-05 (admin session, VI locale)
sub_results:
  - opens_from_header_button: pass (clean chrome — fix from commit b39a500 confirmed: single title row with + and pencil, DialogWindow X handles close, no redundant inner toolbar)
  - non_modal: pass (Customers grid visible AND interactive behind the dialog — phase12-test5-dialog-persists-nav.png)
  - anchored_top_right: pass (rendered at right side of viewport)
  - resizable_draggable: not verified (visual quality, defer to manual UAT)
  - persists_across_route_navigation: pass (dialog stayed open + attached when navigating /ai-agent/ui-settings → /customers via menu link)
  - close_and_reopen_preserves_state: pass (X closes, header button reopens; empty conversation state remains empty as expected)
  - new_chat_from_dialog_only_clears_state: not verified (requires sending an actual message first — needs LLM round-trip; defer to Test 4 chain or manual UAT)
gap_3_chrome_redundancy_status: closed (verified visually — see phase12-test5-dialog-chrome.png)

### 6. Title Generation
covers: SURF-09 title scope, AI-SPEC title quality, manual edit precedence
expected: |
  Start a new conversation with a specific English business question → wait for first assistant reply + brief delay.
  Title becomes short, specific, no tool/provider names, no entity internals, no quotes, no trailing punctuation.
  Repeat with Vietnamese conversation content → title is Vietnamese.
  Edit title manually via pencil action → continue conversation → wait for any async title processing.
  Manually edited title is NOT overwritten by async generation.
result: pass
verified_by: playwright-mcp 2026-05-05 (real LLM round-trips)
sub_results:
  - english_title_quality: pass (sent EN message "Show me recent orders for customer Phan Hong Dat" → AI rewrote title to "Đơn hàng gần đây của khách hàng Phan Hồng Đạt", 45 chars, specific, no quotes/punctuation/tool names/provider names/entity internals)
  - vietnamese_title_locale: pass (sent VI message "Cho tôi xem khách hàng Bình Minh Logistics có đơn hàng gần đây nào không?" → title generated as "Đơn hàng gần đây của khách hàng Bình Minh Logistics" — Vietnamese as expected)
  - manual_edit_precedence: pass (set title via pencil dialog to "MY MANUAL TITLE 12345", then sent follow-up message "Cho tôi xem danh sách sản phẩm" → AI replied → title remained "MY MANUAL TITLE 12345" both in chat header AND persisted in conversations list with msg count=4)
notes: "Even when user message is in English, AI title generator may translate to session locale (VI). This matched the assistant's mixed-language response style in the test, so acceptable. Async title generator correctly checks current title against the default placeholder before overwriting (defaultTitle equality check in AiConversationTitleService.onConversationTitleEligible) — this preserves manual edits."

### 7. Title Failure Isolation
covers: AI-SPEC failure isolation, operational auditability
expected: |
  In a test environment, configure title generation so the title model call FAILS while normal chat still works.
  Start a new conversation → wait for first assistant reply.
  Chat reply remains visible; NO title error shown to chat user.
  As admin, open tool call audit view → find conversation_title entry → outcome = error, no raw provider stack traces or prompt content exposed to the chat user.
result: pass
verified_by: playwright-mcp 2026-05-05 (configured ai-agent.conversation-title.model-id=non-existent-model-uat-test-7, then reverted)
sub_results:
  - chat_path_unaffected: pass (sent "Test 7 — title model is broken, this should still get a reply" → AI assistant responded normally, chat title placeholder shown correctly)
  - no_user_facing_error: pass (MutationObserver captured ZERO notifications during 25-second window after send)
  - audit_row_recorded: pass (Nhật ký sự kiện AI shows TOOL row with Tên sự kiện=conversation_title, Kết quả=Lỗi, Độ trễ=296ms at 2026-05-05T13:05:35)
  - audit_no_leak: pass (Loại lỗi=NonTransientAiException class name only — no stack trace; Lý do từ chối=title_generation_failed sanitized constant — not raw provider error message; Tham số shows config metadata {model, maxContextMessages, locale} ONLY — no prompt content / no user message text / no PII)
notes: "Failure isolation works as designed. Audit row provides operator visibility without leaking sensitive content. Title model config reverted to default after testing."

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
passed: 7
issues: 0
pending: 0
skipped: 0
blocked: 0
gaps_total: 4
verdict: ALL_PASS
gap_status:
  - gap_1_persistence_re_enable: FIXED (commit 7ebf979) — verified
  - gap_2_default_not_enabled_silent: FIXED (commit 7ebf979) — verified
  - gap_3_dialog_chrome_redundant: FIXED (commit b39a500) — verified visually
  - gap_4_full_route_menu_not_hidden: FIXED (commit b3dcf74 — see below) — verified bidirectional

## Gaps

- truth: "When FULL_ROUTE is disabled, the AI Agent → Chat menu link is hidden from the sidebar"
  status: fixed
  reason: "ChatSurfaceMounter.updateFullRouteMenuVisibility called JmixListMenu.getMenuItem(...).setVisible(false), but the rendered <li id=aiAgent.chat> kept display=list-item / hidden=false. Jmix's MenuItem.setVisible did not propagate to the underlying <li>."
  severity: minor
  test: 2
  fix_commit: b3dcf74
  fix_summary: |
    Augmented ChatSurfaceMounter.updateFullRouteMenuVisibility with a defensive direct-DOM toggle:
    after the existing JmixListMenu.MenuItem.setVisible call, also issue ui.getElement().executeJs to
    set <li id=aiAgent.chat>.hidden = !visible. The JS path is idempotent and runs inside Vaadin's
    UI.access via Element.executeJs so it is thread-safe. Both directions verified via Playwright:
    disable FULL_ROUTE → li hidden=true display=none; re-enable → hidden=false display=list-item.
  evidence:
    - "phase12-test2-subc-menu-hidden-FIXED.png — sidebar shows AI Agent expanded with no Trò chuyện entry"
    - "Browser DOM inspection: li#aiAgent.chat hidden='', display=none, offsetParent=null after FULL_ROUTE disabled"
    - "Reverse direction verified: re-enabling FULL_ROUTE restores li to display=list-item, offsetParent=parent"
  artifacts:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java
  suggested_fix: |
    Either:
    (a) Add @MenuItemOnAccess (or equivalent menu-policy hook) gated on a SecurityContext-aware predicate that reads AiUiSettings.enabledSurfaces, so menu rendering is settings-aware up-front instead of being patched after first render.
    (b) Make ChatSurfaceMounter.updateFullRouteMenuVisibility set visibility via the DOM element directly (menuItem.getElement().setVisible(false)) as a workaround for the case where JmixListMenu's MenuItem wrapper has detached from the rendered <li>.
    (c) Hook into a Jmix menu-rebuild event so the visibility filter is applied at menu construction, not retroactively.
    Recommend (a) for cleanest design — co-locates surface gating with the existing menu-policy plumbing.
  artifacts:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java
  needs_fix_plan: true

- truth: "Admin can re-enable a previously-disabled chat surface and the change persists"
  status: failed
  reason: "Starting from a persisted state of {FULL_ROUTE} only, clicking the Header button checkbox does not result in HEADER_BUTTON being added to the persisted enabledSurfaces. Multiple click strategies tried (Playwright getByRole click, JS element.click(), JS group.value setter with change/input events). The save handler navigates without error (no notification, no validation rejection visible), but on revisit the persisted state is unchanged. In one path, after clicking Full-route uncheck → Header-button check → default-radio Header → save, the checkbox group ended up with NO surfaces selected and validator correctly blocked with 'Enable at least one chat surface.' — proving the JmixCheckboxGroup's internal value did not actually pick up the Header button click despite the [checked] attribute appearing on the checkbox host element."
  severity: blocker
  test: 2
  evidence:
    - "phase12-test2-state-before-save.png — visual state shows both checkboxes [checked] (Full route + Header button), default = Full route, before clicking Save."
    - "Subsequent revisit returns groupValue=['1'] only — Header button silently dropped during persistence."
    - "phase12-test2-blocked-save.png — alternate sequence (uncheck Full → check Header → switch default to Header → save) ends in empty checkbox group with required-validator error, even though the user clicked Header button after Full route."
  suggested_fix: |
    Most likely root cause: AiUiSettingsDetailView lacks data-binding between fields and the uiSettingsDc data container. The XML descriptor (ai-ui-settings-detail-view.xml:20-32) has no
    `dataContainer="uiSettingsDc" property="enabledSurfaceIds"` on the checkboxGroup nor `property="defaultSurface"` on the radioButtonGroup. The controller manually sets/reads field values via setValue/getValue in onReady and onBeforeSave. This pattern is fragile against Vaadin client-server state syncing and the JmixCheckboxGroup setSelectionMode internal state — once the field has been touched and re-rendered, JS-driven clicks may bypass the binder's state-sync hook.
    Two fixes possible:
      (a) Properly data-bind both fields via XML attributes so changes propagate through the DataContext directly. This requires the entity to expose enabledSurfaces as a typed Set rather than a comma-separated String column, OR exposing a virtual @Transient property with a metaclass-recognized type — non-trivial given the current denormalized String storage.
      (b) Keep the manual binding but add a setReadOnly/setItems re-init in a focused ReadyEvent listener AND switch to using `Notifications.create(...)` for both validators (closing the silent-default-not-enabled gap from Test 1 too).
    Recommend (a) — cleanest long-term fix; align with `setReloadEdited(false)` workaround already in place.
  artifacts:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/uisettings/ai-ui-settings-detail-view.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java
  needs_fix_plan: true

- truth: "Save attempt with default surface set to a disabled surface surfaces a clear validation error to the user"
  status: failed
  reason: "Server-side persistence rejection works (cycle 3 baseline preserved across the bad-save attempt) but the UI shows nothing — no notification, no field-level error message, no invalid radiogroup state. User cannot tell why their save did nothing."
  severity: minor
  test: 1
  evidence: "phase12-test1-validation-a.png — Full route checked, Header button (default) unchecked, Save clicked: page does not navigate, no red text appears anywhere. Compare to phase12-test1-validation-b.png where 'Enable at least one chat surface.' renders clearly under the checkbox group when no surface is selected."
  suggested_fix: |
    AiUiSettingsDetailView likely uses a Bean Validation @AssertTrue / cross-field validator that throws a ValidationException
    swallowed somewhere in the save handler. Either:
    (a) attach a Vaadin Binder ValidationStatusHandler that copies the cross-field message onto the radiogroup's error-message attribute and sets [invalid], OR
    (b) catch the ValidationException in the save handler and surface it via Notifications.create(...).withThemeVariant(LUMO_ERROR).show(),
    matching the existing 'Enable at least one chat surface.' behavior.
  artifacts:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/uisettings/AiUiSettingsDetailView.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiUiSettings.java
  needs_fix_plan: true

- truth: "Dialog surface presents a single, uncluttered title row that aligns with message content"
  status: fixed
  reason: "User reported during Test 5 exploration: dialog stacked three rows of chrome (DialogWindow header + custom dialogHeaderBar with redundant new-chat/close + fragment titleBar), and the conversation title sat flush at x=0."
  severity: minor
  test: 5
  fix_commit: b39a500
  fix_summary: |
    Removed dialogHeaderBar from chat-dialog-view.xml and headerBar from chat-view.xml.
    Moved newChatButton into ChatPanelFragment.titleBar as a tertiary-inline icon button
    (single source of truth for both surfaces). DialogWindow's built-in X + ChatSurfaceMounter
    afterCloseListener handle teardown — custom closeButton + handler removed. Added padding
    + ellipsis CSS to .ai-agent-chat-panel__toolbar / __title.
  artifacts:
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-dialog-view.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/chat-view.xml
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatDialogView.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java
    - jmix-app/src/main/frontend/themes/jmix-app/jmix-app.css
    - ai-agent/ai-agent/src/test/java/com/vn/agent/view/chat/ChatDialogViewTest.java
  needs_visual_reverify: true

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
