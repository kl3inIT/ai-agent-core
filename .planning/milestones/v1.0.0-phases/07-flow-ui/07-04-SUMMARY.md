---
phase: 07-flow-ui
plan: 04
subsystem: flow-ui-conversation-replay
tags: [jmix, flowui, vaadin, conversation, replay, role-aware, phase7-wave4]

requires:
  - phase: 02-foundations
    provides: AiConversation / AiMessage entities, AiMessageRole enum, AiAgentAdminRole.CODE
  - phase: 07-flow-ui
    provides: "07-01 bundles (conversationList.* + conversationDetail.* keys, menu.xml entry); 07-03 MessageBubbleComponent + MarkdownRenderer; 07-03 ChatView @Route(\"ai-agent/chat\") + conversationId query param contract"
provides:
  - ConversationListView at @Route("ai-agent/conversations") — role-aware filter + message-count column
  - ConversationDetailView at @Route("ai-agent/conversations/:id") — read-only transcript replay reusing MessageBubbleComponent
  - End-to-end flow List → double-click → Detail → Continue in chat → ChatView with conversationId query param
affects: [07-07b]

tech-stack:
  added: []
  patterns:
    - "Role-aware UI affordances without bypassing row-level security — CurrentAuthentication.getUser().getAuthorities() probe drives UI visibility only; DataManager row-level policy still narrows results server-side (T-07-12)"
    - "Per-row DataManager count renderer (plan path b) for messageCount column — trades N count queries for implementation simplicity; acceptable for v1 with small per-user result sets"
    - "Dynamic JPQL rebuild on every filter valueChange via CollectionLoader.setQuery + setParameter — matches the pattern already in 07-06 ToolCallAuditListView"
    - "XML-first layout: everything visible in the view descriptor; Java controller only handles admin probe, renderer wiring, filter state, and navigation (CLAUDE.md Jmix-first rule)"

key-files:
  created:
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/conversation/conversation-list-view.xml
    - ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationDetailView.java
    - ai-agent/ai-agent/src/main/resources/com/vn/agent/view/conversation/conversation-detail-view.xml
  modified: []

key-decisions:
  - "Role detection API: CurrentAuthentication.getUser().getAuthorities() — Jmix 2.8's CurrentAuthentication does NOT expose getAuthorities() directly (Rule 1 plan-interface bug); the UserDetails returned by getUser() does, matching the pattern already in BaselineContextProvider.rolesOf / RetrievalFilterBuilder.buildFor."
  - "@JmixEntity names are 'ai_AiConversation' and 'ai_AiMessage' — the plan's pseudocode assumed 'AiAgent_AiConversation' / 'AiAgent_AiMessage' prefix which does not match the actual entity annotations. JPQL corrected at write time."
  - "messageCount implementation = path (b) per-row DataManager.loadValue count(m). Path (a) JPQL subquery projection into a KeyValueCollectionLoader was available but requires a second loader shape + manual tuple-access API that is not documented for Jmix 2.8 CollectionLoader (which is typed to the entity class). The plan explicitly permits the fallback. Trade-off: N extra count queries per render. Acceptable for v1 because non-admin users see only their own conversations (row-level security narrows the set) and admin pages with many conversations are rare in the MVP — a future optimization can swap in a KeyValueCollectionLoader projection without changing the public surface."
  - "conversationList.column.messageCount bundle key was already seeded by 07-01 in both messages.properties and messages_vi.properties (lines 148 / 148). No new i18n keys added in 07-04."
  - "AiMessage has NO toolCallsJson field in Phase 2 (plan's <interfaces> block was stale). Consequently ToolCallCardComponent is NOT imported in ConversationDetailView; TOOL-role messages are skipped during replay. Bubble-only replay still covers the must-have (read-only transcript); tool-call fidelity is a future-plan concern gated on adding toolCallsJson to AiMessage or a companion entity."

metrics:
  duration: ~20m (wall clock across 2 tasks)
  completed: 2026-04-21

requirements-completed: [UI-03]
---

# Phase 07 Plan 04: ConversationListView + ConversationDetailView Summary

**Wave 4 plan shipping UI-03: role-aware conversation list + read-only transcript replay that reuses the live-chat MessageBubbleComponent so users can revisit past conversations and pick up the thread via "Continue in chat" → ChatView with the conversationId query param.**

## Performance

- **Duration:** ~20 minutes
- **Tasks:** 2 (all `type="auto"`)
- **Files created:** 4
- **Files modified:** 0
- **Commits:** 2 atomic task commits

## Task Commits

1. **Task 1: ConversationListView (XML + Java)** — `3818edc`
2. **Task 2: ConversationDetailView (XML + Java)** — `87fa46c`

## Accomplishments

### ConversationListView

- `@Route("ai-agent/conversations")` + `@ViewController("AiAgent_Conversation.list")` — matches the `AiAgent_Conversation.list` id wired in `menu.xml` by 07-01.
- **Role-aware admin probe** — `currentUserIsAdmin()` reads `CurrentAuthentication.getUser().getAuthorities()` and matches on `AiAgentAdminRole.CODE`. Defaults to `false` on any exception (anonymous / pre-authn contexts).
- **Non-admin UX** — `userFilter` hidden, `createdBy` column hidden. Row-level security (phase-2 `AiAgentUserRowLevelRole` `:current_user_username` JPQL policy) still applies server-side.
- **Admin UX** — `userFilter` visible (substring match on `createdBy`), `createdBy` column visible. Base JPQL stays unfiltered because row-level security already permits admin to see all rows (AiAgentAdminRole has no narrowing row-level policy).
- **Dynamic filter query rebuild** — each valueChange on `titleFilter` / `userFilter` recomposes the JPQL (`where lower(e.title) like :title and lower(e.createdBy) like :userSub`) and reloads via `CollectionLoader.setQuery` + `setParameter`. Matches the pattern already in `ToolCallAuditListView`.
- **messageCount column** — `setRenderer(TextRenderer.of(row -> countMessages(row.getId())))` where `countMessages` calls `dataManager.loadValue("select count(m) from ai_AiMessage m where m.conversation.id = :cid", Long.class).parameter("cid", id).one()`.
- **Navigation** — `addItemDoubleClickListener` → `viewNavigators.detailView(this, AiConversation.class).editEntity(row).navigate()`.
- **Layout 100% XML** — controller only orchestrates filter state, admin probe, column renderer, and navigation.

### ConversationDetailView

- `@Route("ai-agent/conversations/:id")` + `@ViewController("AiAgent_Conversation.detail")`.
- Two data containers: `conversationDc` (instance, auto-loaded via `:id` route param by Jmix) + `messagesDc` (collection, `messagesDl` with `:conv` parameter bound in `onReady`).
- **onReady** — binds `messagesDl.setParameter("conv", getEditedEntity())`, loads, then calls `renderTranscript(messagesDc.getItems())`.
- **renderTranscript** — iterates messages in `createdDate asc, seq asc` order, maps `AiMessageRole` → `MessageBubbleComponent.Role` (USER/ASSISTANT/SYSTEM), creates a new `MessageBubbleComponent(role, markdownRenderer)`, calls `setMarkdown(message.getContent())`, appends to `transcriptList`. TOOL-role rows are skipped (no standalone display content in Phase 2 AiMessage).
- **Continue in chat** — `UI.getCurrent().navigate(ChatView.class, QueryParameters.simple(Map.of("conversationId", convId.toString())))`. ChatView's BeforeEnterObserver (07-03) then reopens the conversation.
- **Read-only banner** — Lumo contrast badge `msg://conversationDetail.banner.readOnly` (EN "Read-only replay" / VI "Chế độ xem lại").
- **Layout 100% XML** — controller only handles onInit click-listener wiring, onReady data loading, and the render loop.

## Verification

| Command | Result |
|---|---|
| `./gradlew :ai-agent:ai-agent:compileJava` | BUILD SUCCESSFUL |
| `./gradlew :ai-agent:ai-agent:processResources` | BUILD SUCCESSFUL |
| `./gradlew :ai-agent:ai-agent:compileTestJava` | BUILD SUCCESSFUL (ConversationListRoleFilterTest RED skeleton still compiles — @Disabled until 07-07b) |
| `grep -c "AiAgent_Conversation.list" ConversationListView.java` | 1 |
| `grep -c "AiAgentAdminRole\\.CODE" ConversationListView.java` | 1 |
| `grep -c "currentAuthentication" ConversationListView.java` | 2 (field + usage) |
| `grep -c "messageCount" conversation-list-view.xml` | 2 (column key + comment) |
| `grep -oE '(text|header|placeholder|title)="[^m][^s][^g]' conversation-list-view.xml` | (no matches — all strings use `msg://`) |
| `grep -c "AiAgent_Conversation.detail" ConversationDetailView.java` | 1 |
| `grep -c "MessageBubbleComponent" ConversationDetailView.java` | 8 (import + Role enum refs + component instantiation + class-level Javadoc) |
| `grep -c "conversationId\\|ChatView" ConversationDetailView.java` | 5 (import + Javadoc + navigate(ChatView.class) + QueryParameters entry) |
| `grep -oE '(text\|header\|placeholder\|title)="[^m][^s][^g]' conversation-detail-view.xml` | (no matches — all strings use `msg://`) |

## Decisions Made

1. **Role detection via `CurrentAuthentication.getUser().getAuthorities()`** — plan pseudocode wrote `currentAuthentication.getAuthorities()` which does not exist on Jmix 2.8's `CurrentAuthentication`. The correct pattern (already in `BaselineContextProvider` and `RetrievalFilterBuilder`) goes through `UserDetails`.
2. **messageCount via path (b)** — per-row `DataManager.loadValue` count query in a `TextRenderer` lambda. Trades N count queries per render for simplicity. The column renders correct counts; the optimization to a projection loader is deferred without API breakage.
3. **JPQL entity names `ai_AiConversation` / `ai_AiMessage`** — read from the actual `@Entity(name = "...")` annotations; the plan assumed `AiAgent_*` prefixes which are wrong.
4. **No `toolCallsJson` rendering** — AiMessage has no such field in Phase 2; render bubbles only for USER/ASSISTANT/SYSTEM. Explicitly documented in class-level Javadoc and in this SUMMARY's decisions.
5. **No new i18n keys** — all 10 `conversationList.*` / `conversationDetail.*` keys were pre-seeded by 07-01 in both EN and VI bundles. Verified via `grep "^conversationList\\.\\|^conversationDetail\\." messages.properties` / `messages_vi.properties` before writing either view.
6. **View access granted by default** — neither `AiAgentUserRole` nor `AiAgentAdminRole` declares a `@ViewPolicy` that enumerates conversation views. Jmix treats this as "no restriction, all authenticated users may access" (matches how ChatView is already accessible). Admin-only views (`AiAgent_Parameters.*`, `AiAgent_ToolCallAudit.list`, `AiAgent_KnowledgeBase.list`) are explicitly listed in `AiAgentAdminRole.adminViews()`. Keeping conversations unlisted preserves the must-have "accessible to any authenticated Jmix user".

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 — Bug] `CurrentAuthentication.getAuthorities()` does not exist**
- **Found during:** Task 1 `./gradlew :ai-agent:ai-agent:compileJava`
- **Issue:** Plan pseudocode called `currentAuthentication.getAuthorities()`; Jmix 2.8's `CurrentAuthentication` interface does not expose that method directly.
- **Fix:** Switched to `currentAuthentication.getUser().getAuthorities()` — the same path already used in `BaselineContextProvider.rolesOf` and `RetrievalFilterBuilder.buildFor`.
- **Files modified:** `ConversationListView.java`
- **Commit:** `3818edc`

**2. [Rule 1 — Bug] JPQL entity-name prefix**
- **Found during:** Task 1 write-time — read `AiConversation.java` and `AiMessage.java` `@Entity(name = "...")` before writing the XML.
- **Issue:** Plan pseudocode assumed `AiAgent_AiConversation` / `AiAgent_AiMessage`; actual annotations are `ai_AiConversation` / `ai_AiMessage`.
- **Fix:** Used the real entity names in all JPQL (list loader, messageCount count query, detail messages loader).
- **Files modified:** `conversation-list-view.xml`, `conversation-detail-view.xml`, `ConversationListView.java`
- **Commit:** `3818edc` + `87fa46c`

**3. [Rule 1 — Bug] `AiMessage.toolCallsJson` field does not exist**
- **Found during:** Task 2 read-first on `AiMessage.java`.
- **Issue:** Plan's `<interfaces>` block lists `toolCallsJson` as an AiMessage field; Phase 2 entity has only `id / version / conversation / role / content / seq / createdDate`.
- **Fix:** Removed the `ToolCallCardComponent` import and the tool-call rendering loop from `ConversationDetailView`. Documented in class Javadoc and in this SUMMARY's decisions. TOOL-role messages are quietly skipped; USER/ASSISTANT/SYSTEM render as bubbles.
- **Files modified:** `ConversationDetailView.java`
- **Commit:** `87fa46c`

### Intentional scope additions

None.

## Authentication Gates

None.

## Deferred / Follow-up

- **07-07b `ConversationListRoleFilterTest`** — skeleton exists at `ai-agent/ai-agent/src/test/java/com/vn/agent/view/conversation/ConversationListRoleFilterTest.java` with two `@Disabled` tests (`nonAdminSeesOnlyOwnRows`, `adminSeesAllRowsAndCreatedByColumn`). 07-07b Task 0 will un-disable and fill both bodies. The `currentUserIsAdmin()` helper is package-private precisely so 07-07b can stub via reflection or a Spring slice.
- **messageCount N+1 optimization** — path (a) KeyValueCollectionLoader JPQL subquery projection is a viable future optimization if admin views on very large conversation sets surface latency. Today's implementation is correct but runs N extra count queries per render pass. No public API change required to swap.
- **AiMessage.toolCallsJson** — if a future plan adds this field (or a companion entity), reintroduce `ToolCallCardComponent` rendering in `renderTranscript`. The component is already imported and used by `ChatPanelFragment`; the pattern is proven.

## Threat Flags

None — all new surfaces are covered by the plan's `<threat_model>` (T-07-12 elevation, T-07-13 XSS).

- **T-07-12 (Elevation)** — `isAdmin` flag only decides UI affordances. Row-level security (`AiAgentUserRowLevelRole` `createdBy = :current_user_username`) is authoritative. A non-admin whose client-side state is tampered with still sees only their rows. Will be verified by `ConversationListRoleFilterTest` in 07-07b.
- **T-07-13 (XSS)** — all `message.content` flows through `MessageBubbleComponent.setMarkdown` → `MarkdownRenderer.toSafeHtml` (Flexmark → OWASP HTML sanitizer). Identical code path to ChatView live streaming.

## Self-Check: PASSED

Artifacts verified (`[ -f ... ]` + `git log`):

- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationListView.java
- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/view/conversation/conversation-list-view.xml
- FOUND: ai-agent/ai-agent/src/main/java/com/vn/agent/view/conversation/ConversationDetailView.java
- FOUND: ai-agent/ai-agent/src/main/resources/com/vn/agent/view/conversation/conversation-detail-view.xml
- FOUND commit: 3818edc (Task 1 — ConversationListView)
- FOUND commit: 87fa46c (Task 2 — ConversationDetailView)

Build gates:

- `./gradlew :ai-agent:ai-agent:compileJava` → BUILD SUCCESSFUL
- `./gradlew :ai-agent:ai-agent:processResources` → BUILD SUCCESSFUL
- `./gradlew :ai-agent:ai-agent:compileTestJava` → BUILD SUCCESSFUL (Wave-0 RED skeleton compiles unchanged)

---
*Phase: 07-flow-ui*
*Completed: 2026-04-21*
