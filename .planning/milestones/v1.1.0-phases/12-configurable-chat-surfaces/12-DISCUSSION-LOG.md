# Phase 12: Configurable Chat Surfaces — Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-30
**Phase:** 12-configurable-chat-surfaces
**Areas discussed:** Header-button injection mechanism, Dialog shell, Cross-surface continuity contract, Auto-title service inclusion + shape

---

## Pre-discussion scope change

Original ROADMAP/REQUIREMENTS shape was 3 surfaces (`FULL_ROUTE` + `SIDEBAR`
mounted to `slot="drawer-end"` + `FLOATING` raw-Vaadin `Dialog` modeless+draggable
bottom-right launcher with P-21 admin-dialog stacking mitigation). User
asked whether to mirror `D:/DTH/jmix-crm` simpler 2-surface pattern
(header `chatButton` opening `dialogWindows.detail(...).setModal(false)`
+ direct route). Confirmed yes — drops `SidebarChatComponent`, drops raw
Vaadin `Dialog`, drops P-21. Adopted 2-surface scope: `FULL_ROUTE` +
`HEADER_BUTTON`. ROADMAP.md / REQUIREMENTS.md to be amended in
Plan 12-01.

---

## Header-button injection mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| AppLayout.addToNavbar via UIInitEvent | Find StandardMainView/AppLayout via UI tree, call addToNavbar(button). No host edits. Verify timing via Context7. | ✓ (via "You decide") |
| Host opt-in slot | Host adds `<slot id='aiAgentChatButtonSlot'/>` to main-view.xml. Violates SURF-03 "no host code edits". | |
| UI.add() absolute fixed top-right | UI-level position:fixed. Floats over login / full-screen. UX worse. | |

**User's choice:** "You decide" → Claude committed to AppLayout.addToNavbar.
**Notes:** Planner must verify timing (UIInitEvent vs first AfterNavigationEvent) via Context7.

| Option | Description | Selected |
|--------|-------------|----------|
| Silent skip + WARN log | Log warn + skip mount when AppLayout absent. FULL_ROUTE remains via menu. | ✓ |
| Fallback UI.add() fixed top-right | Always-visible button regardless of host shell. Consistency loss. | |
| Hard requirement (startup fail) | Throw IllegalStateException if AppLayout missing. Breaks custom hosts. | |

**User's choice:** Silent skip + WARN log

| Option | Description | Selected |
|--------|-------------|----------|
| Hide menu + block route | Programmatic menu hide + ChatView.onBeforeEnter forwardTo(home) when FULL_ROUTE disabled. | ✓ |
| Hide menu only, route still open | Menu hide only; `/ai-agent/chat` accessible via direct URL. Half-measure. | |
| Leave both, AiUiSettings only controls HEADER_BUTTON | FULL_ROUTE always-on. Violates SURF-01 admin-toggleable contract. | |

**User's choice:** Hide menu + block route

| Option | Description | Selected |
|--------|-------------|----------|
| Per-UI no-cache + reload-on-nav | Read AiUiSettings via UnconstrainedDataManager on UIInit + each AfterNavigationEvent. Eventual consistency. | ✓ |
| Spring cache + invalidation event | @Cacheable + @PostUpdate event + UI.access push. Real-time but complex. | |
| Capture-once per UI, ignore changes | Read at UIInit only. Admin save invisible until logout. | |

**User's choice:** Per-UI no-cache + reload-on-nav

---

## Dialog shell: new view vs reuse ChatView

| Option | Description | Selected |
|--------|-------------|----------|
| New ChatDialogView | StandardView (no entity), composes `<fragment ChatPanelFragment/>` + close + new-chat. Shells separated. | ✓ |
| Reuse ChatView with dialogMode parameter | Couples route-shell + dialog-shell. Query-param parsing conflicts. | |
| Mount ChatPanelFragment direct into DialogWindow | No StandardView lifecycle. No XML descriptor. | |

**User's choice:** New ChatDialogView

| Option | Description | Selected |
|--------|-------------|----------|
| Hard-code 35%×75% top-right (jmix-crm) | setLeft('65%')/setTop('5%')/setWidth('35%')/setHeight('75%')/setResizable. No new fields. | ✓ |
| Config in AiUiSettings | New fields dialogLeft/dialogTop/dialogWidth/dialogHeight. Liquibase columns. | |
| Config via application.yml | AiAgentSurfaceProperties. Trade-off middle ground. | |

**User's choice:** Hard-code mirroring jmix-crm

| Option | Description | Selected |
|--------|-------------|----------|
| Toggle: open/close | First click opens, second click closes. No conversation dispose. | ✓ |
| Singleton: focus existing dialog | Bring-to-front on second click. Requires X-button to close. | |
| Re-open: fresh dialog instance | Wasteful, can cancel mid-stream. | |

**User's choice:** Toggle: open/close

| Option | Description | Selected |
|--------|-------------|----------|
| Persist across nav + auto-close on session end | UI-level attach. Dialog stays through route changes. Mid-stream survives. | ✓ |
| Auto-close on navigation | Closes on each nav. Mid-stream cancels. Mental model simpler but UX worse. | |
| Persist + close with Esc/X only | Conflicts with Toggle decision. | |

**User's choice:** Persist across nav + auto-close on session end

---

## Cross-surface continuity contract

| Option | Description | Selected |
|--------|-------------|----------|
| Minimal: currentConversationId + dialogInstance handle | Two fields. No activeRunId (CancellationRegistry has it). No fragment instance store. | ✓ |
| Full: + activeRunId + activeSurface + listeners | Adds redundant runId tracking + surface enum. | |
| Stateless service + DB read | Re-derive 'current' from DB recency. Wrong with multiple concurrent conversations. | |

**User's choice:** Minimal: currentConversationId + dialogInstance handle

| Option | Description | Selected |
|--------|-------------|----------|
| Split bean | AiChatSessionState (@VaadinSessionScope): conversationId + listeners. AiChatUIState (@UIScope): dialog handle. Multi-tab consistent. | ✓ |
| @UIScope only, accept divergence | Single bean per tab. Tabs diverge. Violates SURF-04. | |
| @VaadinSessionScope only (no dialog handle) | Lookup dialog via UI.getInternals(). Fragile. | |

**User's choice:** Split bean

| Option | Description | Selected |
|--------|-------------|----------|
| Hide header button on FULL_ROUTE | AfterNavigationEvent listener toggles button visibility. Eliminates dual-mount entirely. | ✓ |
| Allow dual-mount, dialog wins (input lock) | Confusing UX (route input disabled silently). | |
| Allow dual-mount, sync via shared Flux multicast | High implementation overhead for rare edge case. | |

**User's choice:** Hide header button on FULL_ROUTE

| Option | Description | Selected |
|--------|-------------|----------|
| @UiTest serial mount | Boot UI → fragment A mount → send → detach → fragment B mount → assert continuity. Reuses existing UiTest infra. | ✓ |
| Backend-only @SpringBootTest | Skip UI; assert JDBC counts only. Misses fragment behavior. | |
| Hybrid: UiTest fragment-level + backend JDBC | Two test classes for max coverage. | |

**User's choice:** @UiTest serial mount

---

## Auto-title service inclusion + shape

| Option | Description | Selected |
|--------|-------------|----------|
| Fold + ship pencil | Both auto-title + pencil-edit override in Phase 12. | ✓ |
| Defer auto-title, ship pencil only | UX gap: titles default forever. | |
| Defer both | Conservative; title editing entirely v1.2. | |

**User's choice:** Fold + ship pencil

| Option | Description | Selected |
|--------|-------------|----------|
| Same provider + per-request override | Reuse main provider; clone Builder with model+temp+maxTokens override; no tools/advisors. | ✓ |
| Separate small-model ChatClient bean | New @Bean for title client. Provider config split. | |
| Reuse main ChatClient without override | Full model + tools enabled. Anti-pattern. | |

**User's choice:** Same provider + per-request override

| Option | Description | Selected |
|--------|-------------|----------|
| @Async after ChatClient response commit | @EventListener on ConversationTitleEligibleEvent. Doesn't block reply latency. | ✓ |
| Sync inline after response | Adds 200-500ms to first reply. Bad streaming UX. | |
| Scheduled batch job | Hourly batch. Users see "New conversation" up to 1 hour. | |

**User's choice:** @Async after ChatClient response commit

| Option | Description | Selected |
|--------|-------------|----------|
| Audit on, locale-aware prompt, fail-silent | AuditWriter.writeToolCall reuse + bilingual prompt + catch+log+no-rethrow. | ✓ |
| No audit, locale-aware, fail-silent | Skip audit row. Hidden cost. | |
| Audit on, no locale, fail-loud | Single language + user notification on error. UX bad. | |

**User's choice:** Audit on, locale-aware prompt, fail-silent

---

## Claude's Discretion

- Mount mechanism choice (AppLayout.addToNavbar) — picked via "You decide" answer.
- `AiUiSettings` row-management strategy (singleton id constant + ensure-default).
- `AiUiSettingsView` admin Flow UI shape (StandardDetailView + singleton-load override).
- `AiInternalEntityNames` extension (`AiUiSettings` always-excluded).
- `AiAgentAdminRole` extension (entity + view + menu policies).
- Liquibase changelog `080-ai-ui-settings.xml` placement.
- `enabledSurfaces` Set<EnumClass<String>> persistence shape (text+converter vs join table) — planner picks.
- Package layout (`com.vn.agent.view.chat.*`, `com.vn.agent.view.uisettings.*`, `com.vn.agent.conversation.*`).
- Pencil-edit button placement + `dialogs.createInputDialog` wiring.
- `attachmentsPanel` slot (hidden empty in v1.1; Phase 13 fills).
- Test layout (`ChatPanelFragmentSurfaceSwitchTest`, `AiConversationTitleServiceTest`, `AiUiSettingsServiceSingletonTest`).
- Default surface enabled set (both `FULL_ROUTE` + `HEADER_BUTTON` enabled; `defaultSurface = FULL_ROUTE`).

## Deferred Ideas

- SidebarChatComponent (slot="drawer-end") — dropped from 2-surface scope; v1.2 candidate.
- Raw-Vaadin Dialog modeless+draggable bottom-right launcher — replaced by Jmix DialogWindow.
- P-21 admin-dialog stacking mitigation — moot under DialogWindow.
- Dialog default size/position configurability in AiUiSettings or application.yml.
- SURF-10 setCompactMode on ChatPanelFragment.
- Real-time push of AiUiSettings changes via UI.access.
- Conversation list dropdown inline in fragment.
- attachmentsPanel content (upload + AiTaskFile + TTL job) → Phase 13.
- Collapsible "AI did" tool-detail + ephemeral streaming-status — already deferred per PROJECT.md.
- Multi-conversation tabs / split-screen.
- Separate small-model ChatClient bean for auto-title.
- Cost-cap / max-retries properties for auto-title.
- AiUiSettings own audit log.
- Programmatic surface-registration SPI for hosts.
