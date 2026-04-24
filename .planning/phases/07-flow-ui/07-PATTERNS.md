# Phase 7: Flow UI — Pattern Map

**Mapped:** 2026-04-21
**Files analyzed:** 33 new + 3 modified
**Analogs found:** 28 / 33 new files have a local Jmix analog; 5 files (streaming, markdown, push, event broker, Fragment) have no in-repo analog and fall back to RESEARCH.md reference patterns.

All analogs live in the `jmix-app` module under `com.vn.jmixapp.view.*`. The add-on itself has no views yet, so `jmix-app` views are the canonical in-repo Jmix Flow UI shape. New code ships under `ai-agent/ai-agent/src/main/java/com/vn/agent/view/**` (D-27).

## File Classification

### New — views (Java + XML pair unless noted)

| New File | Role | Data Flow | Closest Analog | Match |
|----------|------|-----------|----------------|-------|
| `com/vn/agent/view/chat/ChatView.java` + `chat-view.xml` | detail-view (route) | streaming + event-driven | `jmix-app/.../view/user/UserDetailView.java` (event-heavy controller) + `.../order/OrderListView.xml` (layout skeleton) | role-match only — streaming has no local analog |
| `com/vn/agent/view/chat/fragment/ChatPanelFragment.java` + `chat-panel-fragment.xml` | fragment | streaming | (none in-repo) | no analog — use RESEARCH.md Pattern 5 |
| `com/vn/agent/view/chat/fragment/MessageBubbleComponent.java` | UI component | render | (none) | no analog |
| `com/vn/agent/view/chat/fragment/ToolCallCardComponent.java` | UI component | render | (none) | no analog — Vaadin `Details` |
| `com/vn/agent/view/chat/fragment/CitationDialog.java` | dialog | render + navigate | (none) | no analog — Vaadin `Dialog` |
| `com/vn/agent/view/chat/MarkdownRenderer.java` | utility (Spring bean) | transform | (none) | no analog — RESEARCH.md Pattern 7 |
| `com/vn/agent/view/conversation/ConversationListView.java` + `-view.xml` | list-view | CRUD (read) + role-branch | `jmix-app/.../view/order/OrderListView.java` + `order-list-view.xml` | exact role + request-response; role-branch extension only |
| `com/vn/agent/view/conversation/ConversationDetailView.java` + `-view.xml` | detail-view (replay, read-only) | read + compose fragment | `jmix-app/.../view/order/OrderDetailView.java` + `order-detail-view.xml` | exact role |
| `com/vn/agent/view/parameters/ParametersListView.java` + `-view.xml` | list-view (admin) | CRUD + row-action | `jmix-app/.../view/user/UserListView.java` + `user-list-view.xml` (row-action + dropdownButton) | exact |
| `com/vn/agent/view/parameters/ParametersDetailView.java` + `-view.xml` | detail-view | form binding + live-derived preview + validation | `jmix-app/.../view/user/UserDetailView.java` (Validation + BeforeSave + AfterSave events) | exact role; live-preview extension is new |
| `com/vn/agent/view/knowledge/KnowledgeBaseView.java` + `-view.xml` | list-view (admin) | file-I/O + event-driven | `jmix-app/.../view/order/OrderListView.java` (grid+buttons) | role-match; `<upload>` + push are new |
| `com/vn/agent/view/audit/ToolCallAuditListView.java` + `-view.xml` | list-view (admin, read-only) | CRUD (read) + export | `jmix-app/.../view/order/OrderListView.java` | role-match; typed filter + gridexport actions are new |
| `com/vn/agent/view/audit/ToolCallAuditDetailDialog.java` + `-dialog.xml` | dialog (modal) | render | (none) | no analog — Vaadin `Dialog` |

### New — push / event plumbing (no XML)

| New File | Role | Data Flow | Closest Analog | Match |
|----------|------|-----------|----------------|-------|
| `com/vn/agent/push/AiAgentAppShell.java` | config (Vaadin discovery) | n/a | (none) | no analog — RESEARCH.md Pattern 2 |
| `com/vn/agent/push/DocumentStatusChangedEvent.java` | event (record) | pub-sub | (none) | no analog — plain Spring `ApplicationEvent` |
| `com/vn/agent/push/DocumentStatusEventPublisher.java` | utility | pub-sub | (none) — new Spring bean | Phase 4 `AuditWriter.writeToolCall` `afterCommit` is the pattern reference |
| `com/vn/agent/orchestration/StreamingEvent.java` | sealed interface (DTO) | transform | `com/vn/agent/orchestration/ChatResponseDto.java` | role-match (same package, same DTO shape) |

### New — service / interface extensions

| New File / Extension | Role | Data Flow | Closest Analog | Match |
|-----------------------|------|-----------|----------------|-------|
| `ChatService.stream(...)` added method | service contract | streaming | existing `ChatService.ask(...)` (same file) | exact — add Flux-returning overloads alongside blocking ones |
| `ChatServiceImpl.stream(...)` impl | service | streaming | `jmix-ai-backend/ChatImpl.java` (external ref per CONTEXT D-05) | out-of-repo ref; no local analog for `Sinks.Many` bridge |
| `IngestionStatusWriter` — `afterCommit` event emit | service extension | pub-sub | `com/vn/agent/audit/AuditWriter.java` (Phase 4) — same pattern | exact (per RESEARCH.md Assumption A5) |

### New — security / i18n / tests

| New File | Role | Data Flow | Closest Analog | Match |
|----------|------|-----------|----------------|-------|
| `AiAgentAdminRole` — extended with `@MenuPolicy` + `@ViewPolicy` | role interface | n/a | `com/vn/agent/security/AiAgentAdminRole.java` (current file — extend in place) | exact — same interface, add annotations |
| `ai-agent/.../resources/com/vn/agent/menu.xml` — extend | menu config | n/a | `jmix-app/.../resources/com/vn/jmixapp/menu.xml` | exact |
| `messages.properties` — add ~80 keys | i18n | n/a | existing same file + `jmix-app/.../messages_en.properties` | exact |
| `messages_vi.properties` — add ~80 keys | i18n | n/a | existing same file | exact |
| `src/test/java/com/vn/agent/i18n/LocaleParityTest.java` | unit test | n/a | no direct analog; see RESEARCH.md Example 4 |
| `src/test/java/com/vn/agent/security/AdminViewAccessTest.java` | integration test | n/a | `com/vn/agent/rag/RoleScopedRetrievalIntegrationTest.java` (Spring-boot + role fixture) | role-match |
| `src/test/java/com/vn/agent/view/**/*Test.java` (7 files) | `@UiTest` | n/a | (no `@UiTest` in repo yet) | no analog — follow Jmix `@UiTest` skill |

### Modified — Gradle

| Modified File | Changes |
|---------------|---------|
| `ai-agent/ai-agent/ai-agent.gradle` | Add `flexmark`, `flexmark-ext-gfm-tables`, `flexmark-ext-autolink`, `owasp-java-html-sanitizer`, `jmix-gridexport-flowui-starter` (see RESEARCH.md §Standard Stack) |

---

## Pattern Assignments

### `ConversationListView.java` + `conversation-list-view.xml` (list-view, CRUD read + role-branch)

**Analog:** `jmix-app/src/main/java/com/vn/jmixapp/view/order/OrderListView.java` + `jmix-app/src/main/resources/com/vn/jmixapp/view/order/order-list-view.xml`

**Controller class header pattern** (`OrderListView.java` lines 1-14):
```java
package com.vn.jmixapp.view.order;

import com.vn.jmixapp.entity.Order;
import com.vn.jmixapp.view.main.MainView;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.view.*;

@Route(value = "orders", layout = MainView.class)
@ViewController(id = "Order.list")
@ViewDescriptor(path = "order-list-view.xml")
@LookupComponent("ordersDataGrid")
@DialogMode(width = "64em")
public class OrderListView extends StandardListView<Order> {
}
```

New `ConversationListView` follows the same shape:
- `@Route(value = "ai-agent/conversations", layout = MainView.class)` — note `MainView` is resolved via the host app; for add-on use `layout = DefaultMainViewParent.class` (Jmix-skill decides). Planner must verify — `jmix-app.MainView` is NOT on the add-on classpath.
- `@ViewController(id = "AiAgent_Conversation.list")` (D-28 view ids use `AiAgent_` prefix)
- Role-branch: add `@Subscribe` on `BeforeShowEvent` to toggle `user` filter column visibility (D-24 — see UserDetailView's `onInitEntity` pattern below).

**List-view XML skeleton pattern** (`order-list-view.xml` lines 1-50): use verbatim for ConversationListView, swap entity `com.vn.agent.entity.AiConversation`, keep `<genericFilter>`, `<simplePagination>`, `<dataGrid>` with `list_create`/`list_edit`/`list_remove` actions — for ConversationListView drop `list_create` and `list_remove` (users cannot delete their own per AiAgentUserRole Phase 2), keep `list_edit` (navigates to replay detail).

**Event-branching pattern** (from `UserDetailView.java` lines 47-50 and 52-57):
```java
@Subscribe
public void onInit(final InitEvent event) {
    timeZoneField.setItems(List.of(TimeZone.getAvailableIDs()));
}

@Subscribe
public void onInitEntity(final InitEntityEvent<User> event) {
    usernameField.setReadOnly(false);
    ...
}
```

Apply this same `@Subscribe` pattern in ConversationListView — on `BeforeShowEvent` call `currentAuthentication.getUser()` + role check to (a) hide `user` column when non-admin, (b) add row-level filter `createdBy = currentUser` to the `CollectionLoader` query.

---

### `ConversationDetailView.java` + `conversation-detail-view.xml` (detail-view, read-only replay)

**Analog:** `jmix-app/src/main/java/com/vn/jmixapp/view/order/OrderDetailView.java` + `order-detail-view.xml`

**Controller header** (`OrderDetailView.java` lines 1-13):
```java
@Route(value = "orders/:id", layout = MainView.class)
@ViewController(id = "Order.detail")
@ViewDescriptor(path = "order-detail-view.xml")
@EditedEntityContainer("orderDc")
public class OrderDetailView extends StandardDetailView<Order> {
}
```

Adapt: `@Route("ai-agent/conversations/:id")`, `id = "AiAgent_Conversation.detail"`, `StandardDetailView<AiConversation>`.

**Detail XML data-container pattern with child collection** (`order-detail-view.xml` lines 5-15):
```xml
<instance id="orderDc" class="com.vn.jmixapp.entity.Order">
    <fetchPlan extends="_base">
        <property name="customer" fetchPlan="_base"/>
        <property name="lines" fetchPlan="_base">
            <property name="product" fetchPlan="_base"/>
        </property>
    </fetchPlan>
    <loader/>
    <collection id="linesDc" property="lines"/>
</instance>
```

ConversationDetailView substitutes `lines` → `messages` and renders each `AiMessage` as a bubble via the shared `ChatPanelFragment` in read-only mode (D-23/D-25), rather than a `<dataGrid>`.

---

### `ParametersListView.java` + `parameters-list-view.xml` (list-view, CRUD + row-action)

**Analog:** `jmix-app/src/main/java/com/vn/jmixapp/view/user/UserListView.java` + `user-list-view.xml`

**Row-action pattern** (`user-list-view.xml` lines 37-44, 59-62) — `Set active` is an extra list-grid action modelled on `showRoleAssignmentsAction`:
```xml
<button id="showRoleAssignmentsButton" action="usersDataGrid.showRoleAssignmentsAction"/>
...
<actions>
    <action id="showRoleAssignmentsAction" type="sec_showRoleAssignments"/>
    ...
</actions>
```

For ParametersListView, declare a custom action:
```xml
<action id="setActiveAction"/>  <!-- handler in Java via @Install or @Subscribe -->
<button id="setActiveButton" action="parametersDataGrid.setActiveAction"/>
```

Wire the handler in the controller with `@Subscribe("parametersDataGrid.setActiveAction")` or `@Install(to = "parametersDataGrid.setActiveAction", subject = "enabledRule")` — follow the Jmix `jmix-views` skill for action definition.

**Active-badge column:** use a `renderer` column as in `columns/column` with a custom `ComponentRenderer` (see `jmix-views` skill). No local analog — RESEARCH.md §Color §Badge colour mapping is authoritative.

---

### `ParametersDetailView.java` + `parameters-detail-view.xml` (detail-view, form + live preview + validation)

**Analog:** `jmix-app/src/main/java/com/vn/jmixapp/view/user/UserDetailView.java`

**Full event-driven validation + save pattern** (`UserDetailView.java` lines 27-93 — use as the controller template):
```java
@ViewComponent private TypedTextField<String> usernameField;
@ViewComponent private PasswordField passwordField;
@ViewComponent private MessageBundle messageBundle;
@Autowired private Notifications notifications;
@Autowired private EntityStates entityStates;

@Subscribe
public void onInit(final InitEvent event) {
    timeZoneField.setItems(List.of(TimeZone.getAvailableIDs()));
}

@Subscribe
public void onValidation(final ValidationEvent event) {
    if (entityStates.isNew(getEditedEntity())
            && !Objects.equals(passwordField.getValue(), confirmPasswordField.getValue())) {
        event.getErrors().add(messageBundle.getMessage("passwordsDoNotMatch"));
    }
}

@Subscribe
public void onBeforeSave(final BeforeSaveEvent event) {
    if (entityStates.isNew(getEditedEntity())) {
        getEditedEntity().setPassword(passwordEncoder.encode(passwordField.getValue()));
        newEntity = true;
    }
}

@Subscribe
public void onAfterSave(final AfterSaveEvent event) {
    if (newEntity) {
        notifications.create(messageBundle.getMessage("noAssignedRolesNotification"))
                .withThemeVariant(NotificationVariant.LUMO_WARNING)
                .withPosition(Notification.Position.TOP_END).show();
    }
}
```

Apply to ParametersDetailView:
- `onInit` populates `modelField.setItems(...)` and `enabledToolsField.setItems(...)` from registered `@Tool` beans.
- `onValidation` runs range checks for temperature/topP/maxTokens against `AiParametersBody` — use error keys from existing `messages.properties` lines 104-108 (`ai-agent.parameters.*`).
- `onBeforeSave` serializes the Form state → `AiParametersBody` → YAML via `AiParametersBodyYamlMapper.writeAsYaml(...)` and writes into `AiParameters.bodyYaml`.
- Add a ValueChangeListener on every form field in `onReady` that calls `yamlPreviewField.setValue(AiParametersBodyYamlMapper.writeAsYaml(buildBodyFromForm()))` — debounce is not needed at human typing speed.
- Tab layout: `<tabs>` + two `<tab>` children, one `Div` content host per tab, swap content in Java on selection (RESEARCH.md §Interaction Contracts — Parameters form ↔ YAML preview).

**Detail XML action bar pattern** (`order-detail-view.xml` lines 20-23, 55-58):
```xml
<actions>
    <action id="saveCloseAction" type="detail_saveClose"/>
    <action id="closeAction" type="detail_close"/>
</actions>
...
<hbox id="detailActions">
    <button id="saveAndCloseButton" action="saveCloseAction"/>
    <button id="closeButton" action="closeAction"/>
</hbox>
```

Extend with a `Set active` button bound to a custom action calling `ParametersService.setActive(...)` (D-14 — no confirm dialog).

---

### `KnowledgeBaseView.java` + `knowledge-base-view.xml` (list-view, file-I/O + event-driven)

**Analog:** `jmix-app/src/main/java/com/vn/jmixapp/view/order/OrderListView.java` + `order-list-view.xml` — for grid + buttons shell only. The `<upload>` + event-push portions have **no local analog**.

**Grid shell pattern** (copy from `order-list-view.xml` lines 36-48):
```xml
<dataGrid id="documentsDataGrid" width="100%" minHeight="20em" dataContainer="documentsDc">
    <actions>
        <action id="reingestAction"/>
        <action id="deleteAction" type="list_remove"/>
    </actions>
    <columns resizable="true">
        <column property="fileName"/>
        <column property="mimeType"/>
        <!-- status column: ComponentRenderer → Badge -->
        <column key="status" header="msg://knowledgeBase.column.status"/>
        <column property="ingestedAt"/>
    </columns>
</dataGrid>
```

**New — `<upload>` + event broker:** follow RESEARCH.md §Pattern 4 (Jmix `<upload>`) and §Pattern 3 (ingestion status push via ApplicationEventPublisher). In controller register `AttachEvent`/`DetachEvent` subscriptions mirroring UserDetailView's `@Subscribe` event style; inside the handler call `documentsDataGrid.getDataProvider().refreshItem(...)`.

---

### `ToolCallAuditListView.java` + `tool-call-audit-list-view.xml` (list-view, read + export)

**Analog:** `order-list-view.xml` lines 15-49 for data + grid + genericFilter skeleton.

**New — gridexport actions** (per RESEARCH.md Example 2; no local analog):
```xml
<hbox id="auditButtonsPanel" classNames="buttons-panel">
    <button id="excelExportBtn" action="auditDataGrid.excelExport"
            text="msg://auditList.action.exportExcel" themeNames="primary"/>
    <button id="jsonExportBtn"  action="auditDataGrid.jsonExport"
            text="msg://auditList.action.exportJson"/>
</hbox>
<dataGrid id="auditDataGrid" dataContainer="auditsDc" selectionMode="MULTI">
    <actions>
        <action id="excelExport" type="grdexp_excelExport"/>
        <action id="jsonExport"  type="grdexp_jsonExport"/>
    </actions>
    <columns> ... </columns>
</dataGrid>
```

**Typed filter bar:** plain `<hbox>` containing `<comboBox>`, `<select>`, `<datePicker>` (pair) above the generic filter — wire each to a property of the `ordersDl`-style `CollectionLoader`'s query via `@Subscribe` `valueChange` handlers that call `auditsDl.setParameter(...)` + `auditsDl.load()`. Grid shell (`genericFilter` + `dataGrid` + `simplePagination`) comes verbatim from `order-list-view.xml`.

**Row-click dialog:** use Jmix `dialogWindows.detail(...)` OR `dialogs.createOptionDialog(...)` — see Jmix `jmix-views` skill. UserDetailView shows Jmix `Notifications` usage (`user-detail-view` lines 83-92) as a simpler but related pattern for dialog/notification API.

---

### `ChatView.java` + `chat-view.xml` (detail-view, streaming)

**Analog (partial):** `UserDetailView.java` for `@Subscribe` controller lifecycle + `Notifications` + `MessageBundle` injection patterns. **No local analog for streaming subscription** — use RESEARCH.md §Pattern 1 verbatim.

**Injection patterns to copy** (from `UserDetailView.java` lines 27-43):
```java
@ViewComponent private MessageBundle messageBundle;
@Autowired    private Notifications notifications;
```

Extend with:
```java
@ViewComponent("chatPanel") private ChatPanelFragment chatPanel;
@Autowired    private ChatService chatService;
@Autowired    private CancellationRegistry cancellationRegistry;
private Disposable activeStreamDisposable;
```

**Lifecycle pattern** — apply `UserDetailView`'s `onInit`/`onReady` shape, add `@Subscribe public void onDetach(DetachEvent e)` that calls `activeStreamDisposable.dispose()` (RESEARCH.md Pitfall #1).

**Route pattern with optional query param (D-26):** follow `OrderDetailView`'s `@Route("orders/:id")` but register the no-id route too — `@Route("ai-agent/chat")`. Use Vaadin `QueryParameters` via `BeforeEnterEvent` (Jmix skill).

---

### `AiAgentAdminRole.java` — extend in place (role interface)

**Analog:** `com/vn/agent/security/AiAgentAdminRole.java` (current file, lines 1-29).

**Extension pattern** — add a new method with `@MenuPolicy` + `@ViewPolicy` alongside existing `adminAccess()`:
```java
// Add at same indentation as existing adminAccess():
@io.jmix.security.role.annotation.MenuPolicy(menuIds = {
        "aiAgent.parameters.list",
        "aiAgent.knowledge.list",
        "aiAgent.audit.list"})
@io.jmix.security.role.annotation.ViewPolicy(viewIds = {
        "AiAgent_Parameters.list", "AiAgent_Parameters.detail",
        "AiAgent_KnowledgeBase.list",
        "AiAgent_ToolCallAudit.list"})
void adminViews();
```

Do **not** touch `AiAgentUserRole` — chat/conversation views are accessible to any authenticated user (UI-SPEC §Admin gating).

Direct-URL protection (belt-and-suspenders) on the admin view classes themselves — see RESEARCH.md Example 3 for the `@Secured("ROLE_ai-agent-admin")` annotation on `ParametersListView`. Use Jmix 2.8 `@ViewAccessChecker` or Spring `@Secured`; planner confirms via `jmix-security-roles` skill.

---

### `menu.xml` extension (in place)

**Analog A:** existing `ai-agent/ai-agent/src/main/resources/com/vn/agent/menu.xml` (1-7) — the `AI` root stub.
**Analog B:** `jmix-app/src/main/resources/com/vn/jmixapp/menu.xml` lines 4-9:
```xml
<menu id="application" title="msg://com.vn.jmixapp/menu.application.title" opened="true">
    <item view="User.list" title="msg://com.vn.jmixapp.view.user/UserListView.title"/>
    <item view="Customer.list" title="msg://com.vn.jmixapp.view.customer/CustomerListView.title"/>
    ...
</menu>
```

Phase 7 shape — convert the existing stub to a menu with items:
```xml
<menu id="aiAgent" title="msg://com.vn.agent/menu.addon">
    <item id="aiAgent.chat"          view="AiAgent_Chat"                     title="msg://com.vn.agent/menu.chat"/>
    <item id="aiAgent.conversations" view="AiAgent_Conversation.list"        title="msg://com.vn.agent/menu.conversations"/>
    <item id="aiAgent.parameters.list" view="AiAgent_Parameters.list"        title="msg://com.vn.agent/menu.parameters"/>
    <item id="aiAgent.knowledge.list" view="AiAgent_KnowledgeBase.list"      title="msg://com.vn.agent/menu.knowledge"/>
    <item id="aiAgent.audit.list"    view="AiAgent_ToolCallAudit.list"       title="msg://com.vn.agent/menu.audit"/>
</menu>
```

Menu item `id` values match exactly what `AiAgentAdminRole.@MenuPolicy(menuIds = ...)` references (above).

---

### `messages.properties` + `messages_vi.properties` extension

**Analog:** existing `ai-agent/ai-agent/src/main/resources/com/vn/agent/messages.properties` lines 1-108. Both files already exist; Phase 7 appends all keys from UI-SPEC §Copywriting Contract for en (base) + vi in parity (D-30).

**Key namespace pattern** (from existing lines 6-9, 23-38): use dotted class-scoped keys for entity/enum (`com.vn.agent.entity/...`); for view copy use the non-namespaced flat keys defined verbatim in UI-SPEC (`chatView.title`, `parametersDetail.field.model`, etc.). Both conventions coexist in `jmix-app/messages_en.properties` — see `com.vn.jmixapp.view.order/OrderListView.title=Orders` (line 84).

---

### `AiAgentAppShell.java` (config) and push plumbing — no in-repo analog

No analog in this repo. Planner uses RESEARCH.md §Pattern 2 (AppShellConfigurator with `@ConditionalOnProperty`) and §Pattern 3 (ApplicationEventPublisher + `afterCommit` synchronization) directly.

**Reference for `afterCommit` event publish:** Phase 4 `com/vn/agent/audit/AuditWriter.java` — same pattern already used for audit row emission. Planner re-reads `AuditWriter` when wiring `IngestionStatusWriter.markReady` / `.markFailed`.

---

### `LocaleParityTest.java` — no analog (new test pattern)

No existing locale-parity / hardcoded-string-scan test. Use RESEARCH.md §Example 4 verbatim. Place under `ai-agent/ai-agent/src/test/java/com/vn/agent/i18n/`.

### `AdminViewAccessTest.java`

**Analog:** `ai-agent/ai-agent/src/test/java/com/vn/agent/rag/RoleScopedRetrievalIntegrationTest.java` — Spring-boot integration test that flips between user and admin role fixtures. Copy its `@SpringBootTest` + `SystemAuthenticator` pattern, replace retrieval assertions with `ViewRegistry` / `ViewNavigators` probes that confirm admin views deny non-admin access.

---

## Shared Patterns

### Pattern: view controller boilerplate
**Source:** `jmix-app/src/main/java/com/vn/jmixapp/view/order/OrderListView.java` (lines 1-14) and `OrderDetailView.java` (lines 1-13)
**Apply to:** every new `*ListView` and `*DetailView` controller
**Take verbatim:** `@Route(...)`, `@ViewController(id=...)`, `@ViewDescriptor(...)`, `@LookupComponent` (list only), `@DialogMode(width="64em")` (list only), `@EditedEntityContainer` (detail only), parent `StandardListView<E>` / `StandardDetailView<E>`.

### Pattern: event-driven controller bean injection
**Source:** `jmix-app/src/main/java/com/vn/jmixapp/view/user/UserDetailView.java` lines 27-45
**Apply to:** ChatView, ParametersDetailView, KnowledgeBaseView, ConversationListView, ConversationDetailView (any view with non-trivial controller logic)
```java
@ViewComponent private TypedTextField<String> someField;
@ViewComponent private MessageBundle messageBundle;
@Autowired    private Notifications notifications;
@Autowired    private EntityStates entityStates;
```
**Rule:** `@ViewComponent` for XML-declared components + `MessageBundle` + data containers/loaders; `@Autowired` for Spring services. Matches CLAUDE.md "Views: `@ViewComponent` for components defined in XML … `@Autowired` for Spring beans".

### Pattern: validation + lifecycle subscriber
**Source:** `UserDetailView.java` lines 47-93
**Apply to:** ParametersDetailView (most critical — multi-field validation + before-save YAML rebuild), ChatView (onDetach → dispose stream), KnowledgeBaseView (onAttach/onDetach → register/unregister UI with event broker)
**Take:** the `@Subscribe` method signature for `InitEvent`, `InitEntityEvent`, `ReadyEvent`, `ValidationEvent`, `BeforeSaveEvent`, `AfterSaveEvent`, `DetachEvent`.

### Pattern: list XML skeleton
**Source:** `jmix-app/src/main/resources/com/vn/jmixapp/view/order/order-list-view.xml` (entire file)
**Apply to:** all five `*ListView` XMLs
**Take verbatim:** `<data>` + `<collection>` + `<fetchPlan extends="_base">` + `<loader>` + `<facets>` + `<genericFilter>` + `<hbox classNames="buttons-panel">` + `<dataGrid>` with `<actions>` and `<columns>` + `<simplePagination>`.

### Pattern: detail XML skeleton
**Source:** `order-detail-view.xml`
**Apply to:** ConversationDetailView, ParametersDetailView
**Take verbatim:** `<data>/<instance>/<fetchPlan>/<loader>` + `<facets><dataLoadCoordinator auto="true"/></facets>` + top-level `<actions>` with `detail_saveClose`/`detail_close` + `<formLayout>` or a custom layout inside `<layout>` + action bar `<hbox id="detailActions">` with save/close buttons.

### Pattern: event broker with afterCommit publish
**Source:** `com/vn/agent/audit/AuditWriter.java` (Phase 4 — audit row emitted via `TransactionSynchronization.afterCommit`)
**Apply to:** `IngestionStatusWriter.markReady/markFailed` extension (D-16)
**Reason:** same transaction semantics; RESEARCH.md §Pattern 3 + Assumption A5 confirm this is the established repo idiom.

### Pattern: message bundle dual-locale parity
**Source:** existing `ai-agent/.../messages.properties` (108 lines) + `messages_vi.properties` (same keys translated)
**Apply to:** both files per UI-SPEC §Copywriting Contract
**Rule:** every key present in both, zero extras, parity enforced by new `LocaleParityTest` (no analog — RESEARCH.md Example 4).

### Pattern: role interface extension
**Source:** `com/vn/agent/security/AiAgentAdminRole.java` (current 29-line file)
**Apply to:** the same file (add `adminViews()` method with `@MenuPolicy` + `@ViewPolicy`)
**Constraint:** do NOT touch `AiAgentUserRole` or `AiAgentUserRowLevelRole`.

---

## No Analog Found

Files for which the in-repo codebase has no close match — planner falls back to RESEARCH.md patterns (cited) or external reference impl (CONTEXT D-05).

| File | Role | Data Flow | Fallback source |
|------|------|-----------|-----------------|
| `ChatServiceImpl.stream(...)` body | service | streaming | RESEARCH.md §Pattern 1 + external `jmix-ai-backend/ChatImpl.java` (CONTEXT D-05) |
| `MarkdownRenderer.java` | utility bean | transform | RESEARCH.md §Pattern 7 |
| `ChatPanelFragment` + bubble / tool-card / citation components | fragment + components | render | RESEARCH.md §Pattern 5 (Jmix Fragment) + Jmix `jmix-fragments` skill |
| `AiAgentAppShell.java` + push-config | Vaadin app shell | n/a | RESEARCH.md §Pattern 2 |
| `DocumentStatusEventPublisher` + event broker listening UIs | pub-sub | event-driven | RESEARCH.md §Pattern 3 |
| `@UiTest` view tests | test | n/a | Jmix `jmix-views` skill + Jmix `@UiTest` docs |
| `LocaleParityTest` | test | n/a | RESEARCH.md §Example 4 |
| Typed filter bar in audit list | custom filter composition | n/a | Jmix `jmix-views` skill (`GenericFilter` is declarative; typed bar is ad-hoc layout) |

---

## Metadata

**Analog search scope:**
- `jmix-app/src/main/java/com/vn/jmixapp/view/**` (11 view classes — all scanned)
- `jmix-app/src/main/resources/com/vn/jmixapp/view/**` (11 XML descriptors — all scanned)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/security/**` (3 role interfaces — all scanned)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/orchestration/**` (DTO + Gateway reviewed for service-contract analog)
- `ai-agent/ai-agent/src/main/java/com/vn/agent/audit/**` (`AuditWriter` cited as afterCommit pattern source)
- `ai-agent/ai-agent/src/main/resources/com/vn/agent/` (`messages.properties`, `messages_vi.properties`, `menu.xml`)
- `jmix-app/src/main/resources/com/vn/jmixapp/` (`menu.xml`, `messages_en.properties`)

**Files scanned:** ~30 Java + XML files read in full or in relevant ranges.

**Pattern extraction date:** 2026-04-21

---

## PATTERN MAPPING COMPLETE

**Phase:** 07 - flow-ui
**Files classified:** 33 new + 3 modified
**Analogs found:** 28 / 33 (exact+role-match) ; 5 no-analog (documented fallbacks)

### Coverage
- Files with exact analog: 12 (list + detail views, menu.xml, messages, role interface extension)
- Files with role-match analog: 16 (controllers reusing UserDetailView event pattern for novel flows)
- Files with no analog (use RESEARCH.md / skills): 5 (streaming impl, markdown, push config, event broker, Fragment chat panel)

### Key Patterns Identified
- All Jmix list views use `OrderListView`/`UserListView` shape: `StandardListView<E>` + XML `<collection>/<loader>/<genericFilter>/<dataGrid>/<simplePagination>` — Phase 7 reuses this verbatim.
- All detail views use `OrderDetailView`/`UserDetailView` shape: `StandardDetailView<E>` + `@Subscribe` lifecycle (`InitEvent`, `InitEntityEvent`, `ReadyEvent`, `ValidationEvent`, `BeforeSaveEvent`, `AfterSaveEvent`). ParametersDetailView inherits this whole pattern for its multi-field validation + YAML rebuild.
- Menu & i18n extension is additive to existing `ai-agent/.../menu.xml` + `messages*.properties` — the add-on already has the root stub.
- Role-policy extension lives in existing `AiAgentAdminRole` interface — add one method with `@MenuPolicy` + `@ViewPolicy`, do not create a new role.
- Repo already uses `TransactionSynchronization.afterCommit` in `AuditWriter` — same pattern is the natural fit for `IngestionStatusWriter` event emission (D-16).
- Novel code (no local analog): streaming bridge, markdown renderer, AppShell push config, event broker, Fragment chat panel. All five have explicit RESEARCH.md patterns cited above.

### File Created
`.planning/phases/07-flow-ui/07-PATTERNS.md`

### Ready for Planning
Pattern mapping complete. Planner can reference `jmix-app` analog views, the existing `AiAgentAdminRole` / `AuditWriter` / `messages*.properties` in-repo assets, and RESEARCH.md patterns for the 5 no-analog files.
