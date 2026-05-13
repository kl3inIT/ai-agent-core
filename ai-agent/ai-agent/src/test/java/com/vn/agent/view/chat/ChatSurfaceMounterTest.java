package com.vn.agent.view.chat;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiChatSurface;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiUiSettings;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import com.vn.agent.view.conversation.ConversationListView;
import io.jmix.core.AccessManager;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.component.applayout.JmixAppLayout;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.kit.component.main.ListMenu;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.DialogWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@UiTest
@SpringBootTest(classes = {AITestConfiguration.class, FlowuiTestAssistConfiguration.class})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
class ChatSurfaceMounterTest {

    @Autowired
    ChatSurfaceMounter chatSurfaceMounter;
    @Autowired
    AccessManager accessManager;
    @Autowired
    SystemAuthenticator systemAuthenticator;
    @Autowired
    AiUiSettingsService uiSettingsService;
    @Autowired
    UnconstrainedDataManager unconstrainedDataManager;
    @Autowired
    ObjectProvider<AiChatUIState> chatUIStateProvider;
    @Autowired
    ObjectProvider<AiChatSessionState> chatSessionStateProvider;
    @Autowired
    ConversationGateway conversationGateway;
    @Autowired
    ViewNavigators viewNavigators;
    @Autowired
    UiComponents uiComponents;
    @Autowired
    org.springframework.context.ApplicationContext applicationContext;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        unconstrainedDataManager.load(AiUiSettings.class)
                .all()
                .list()
                .forEach(unconstrainedDataManager::remove);
        unconstrainedDataManager.load(AiConversation.class)
                .all()
                .list()
                .forEach(unconstrainedDataManager::remove);
    }

    @Test
    void mounterUsesVaadinServiceInitAndRequiredRuntimeGates() throws Exception {
        String source = readMounterSource();

        assertThat(com.vaadin.flow.server.VaadinServiceInitListener.class)
                .isAssignableFrom(ChatSurfaceMounter.class);
        assertThat(source)
                .contains("addUIInitListener")
                .contains("addAfterNavigationListener")
                .contains("uiSettingsService.loadCurrent()")
                .contains("new UiShowViewContext(CHAT_DIALOG_VIEW_ID)")
                .contains("setModal(false)")
                .contains("setLeft(\"50%\")")
                .contains("setTop(\"5%\")")
                .contains("setWidth(\"50%\")")
                .contains("setHeight(\"85%\")")
                .contains("setResizable(true)")
                .contains("setDraggable(true)")
                .contains("AiAgent_Chat")
                .contains("AI Agent chat button not mounted");
    }

    @Test
    void chatCapableUserCanShowHeaderDialogView() {
        Boolean permitted = systemAuthenticator.withUser("alice", () -> {
            UiShowViewContext accessContext = new UiShowViewContext("AiAgent_ChatDialog");
            accessManager.applyRegisteredConstraints(accessContext);
            return accessContext.isPermitted();
        });

        assertThat(permitted).isTrue();
    }

    @Test
    void defaultSettingsMountExactlyOneVisibleHeaderButtonForChatUser() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);

            chatSurfaceMounter.initializeUiForTest(ui);
            chatSurfaceMounter.initializeUiForTest(ui);

            List<JmixButton> buttons = findHeaderButtons(ui);
            assertThat(buttons).hasSize(1);
            assertThat(buttons.get(0).isVisible()).isTrue();
        });
    }

    @Test
    void disablingHeaderButtonHidesMountedButtonAfterNavigationRefresh() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);
            JmixListMenu menu = addChatMenu(appLayout);
            chatSurfaceMounter.initializeUiForTest(ui);

            AiUiSettings settings = uiSettingsService.loadCurrent();
            settings.setEnabledSurfaceSet(EnumSet.of(AiChatSurface.FULL_ROUTE));
            unconstrainedDataManager.save(settings);

            chatSurfaceMounter.refreshMountedSurfacesForTest(ui, false);

            assertThat(findHeaderButtons(ui)).singleElement()
                    .extracting(Component::isVisible)
                    .isEqualTo(false);
            assertChatMenuVisible(menu, true);
        });
    }

    @Test
    void disablingFullRouteHidesMenuButLeavesHeaderButtonVisible() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);
            JmixListMenu menu = addChatMenu(appLayout);
            chatSurfaceMounter.initializeUiForTest(ui);

            AiUiSettings settings = uiSettingsService.loadCurrent();
            settings.setEnabledSurfaceSet(EnumSet.of(AiChatSurface.HEADER_BUTTON));
            unconstrainedDataManager.save(settings);

            chatSurfaceMounter.refreshMountedSurfacesForTest(ui, false);

            assertChatMenuVisible(menu, false);
            assertThat(findHeaderButtons(ui)).singleElement()
                    .extracting(Component::isVisible)
                    .isEqualTo(true);
        });
    }

    @Test
    void fullChatRouteHidesMountedButton() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);
            chatSurfaceMounter.initializeUiForTest(ui);

            chatSurfaceMounter.refreshMountedSurfacesForTest(ui, true);

            assertThat(findHeaderButtons(ui)).singleElement()
                    .extracting(Component::isVisible)
                    .isEqualTo(false);
        });
    }

    // ---- SIDEBAR surface (Phase 15 Plan 03) ----------------------------------

    @Test
    void defaultSettingsMountSidebarToggleAndClosedPanelForChatUser() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);

            chatSurfaceMounter.initializeUiForTest(ui);

            JmixButton toggle = findSidebarToggle(ui).orElseThrow();
            assertThat(toggle.isVisible()).isTrue();
            assertThat(iconName(toggle)).isEqualTo("vaadin:panel");
            assertThat(toggle.getElement().getAttribute("aria-pressed")).isEqualTo("false");

            Div panel = findSidebarPanel(ui).orElseThrow();
            assertThat(panel.isVisible()).isTrue();
            assertThat(panel.hasClassName(ChatSurfaceMounter.SIDEBAR_PANEL_OPEN_CLASS)).isFalse();
            // Header with the in-panel closer.
            assertThat(panel.getChildren()
                    .anyMatch(c -> c instanceof Div d && d.hasClassName(ChatSurfaceMounter.SIDEBAR_PANEL_HEADER_CLASS)))
                    .isTrue();
            assertThat(findInPanelCloser(panel)).isPresent();
            // The panel hosts the AiAgentSidebarView, which owns the ChatPanelFragment.
            assertThat(findFirstComponentOfType(panel, AiAgentSidebarView.class)).isPresent();
            assertThat(findFirstComponentOfType(panel, ChatPanelFragment.class)).isPresent();
            // The toggle is distinct from the magic header button.
            assertThat(findHeaderButtons(ui)).singleElement()
                    .extracting(c -> iconName((JmixButton) c)).isEqualTo("vaadin:magic");
            // Panel lives on the UI element, not the AppLayout content slot.
            assertThat(panel.getElement().getNode().isAttached()).isTrue();
            assertThat(findFirstComponentOfType(appLayout, Div.class)
                    .filter(d -> d.hasClassName(ChatSurfaceMounter.SIDEBAR_PANEL_CLASS)))
                    .as("the sidebar panel must not be nested inside the AppLayout content slot")
                    .isEmpty();
            assertThat(findSidebarPanel(ui)).isPresent();
        });
    }

    @Test
    void togglingSidebarFlipsOpenClassesAriaAndInPanelCloserAlsoCloses() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);
            chatSurfaceMounter.initializeUiForTest(ui);

            Div panel = findSidebarPanel(ui).orElseThrow();
            JmixButton toggle = findSidebarToggle(ui).orElseThrow();
            JmixButton closer = findInPanelCloser(panel).orElseThrow();

            // open
            chatSurfaceMounter.toggleSidebarForTest(ui);
            assertThat(panel.hasClassName(ChatSurfaceMounter.SIDEBAR_PANEL_OPEN_CLASS)).isTrue();
            assertThat(appLayout.hasClassName(ChatSurfaceMounter.CONTENT_PUSHED_CLASS)).isTrue();
            assertThat(toggle.hasClassName(ChatSurfaceMounter.SIDEBAR_TOGGLE_ACTIVE_CLASS)).isTrue();
            assertThat(toggle.getElement().getAttribute("aria-pressed")).isEqualTo("true");

            // close via the in-panel closer
            closer.click();
            assertThat(panel.hasClassName(ChatSurfaceMounter.SIDEBAR_PANEL_OPEN_CLASS)).isFalse();
            assertThat(appLayout.hasClassName(ChatSurfaceMounter.CONTENT_PUSHED_CLASS)).isFalse();
            assertThat(toggle.hasClassName(ChatSurfaceMounter.SIDEBAR_TOGGLE_ACTIVE_CLASS)).isFalse();
            assertThat(toggle.getElement().getAttribute("aria-pressed")).isEqualTo("false");
        });
    }

    @Test
    void sidebarAndHeaderButtonAreTwoDistinctIndependentButtons() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);
            chatSurfaceMounter.initializeUiForTest(ui);

            AiChatUIState chatUIState = chatUIStateProvider.getObject();
            JmixButton headerButton = (JmixButton) findHeaderButtons(ui).get(0);
            JmixButton toggle = findSidebarToggle(ui).orElseThrow();
            assertThat(toggle).isNotSameAs(headerButton);
            assertThat(iconName(headerButton)).isEqualTo("vaadin:magic");
            assertThat(iconName(toggle)).isEqualTo("vaadin:panel");

            // Toggling the sidebar must not touch the header dialog.
            chatSurfaceMounter.toggleSidebarForTest(ui);
            assertThat(chatUIState.getDialogInstance()).isNull();

            // Opening the header dialog must not change the sidebar open state.
            DialogWindow<ChatDialogView> dialogWindow = chatSurfaceMounter.openDialogForTest();
            assertThat(chatUIState.getDialogInstance()).isSameAs(dialogWindow);
            assertThat(findSidebarPanel(ui).orElseThrow()
                    .hasClassName(ChatSurfaceMounter.SIDEBAR_PANEL_OPEN_CLASS)).isTrue();
        });
    }

    @Test
    void disablingSidebarRemovesToggleAndPanelFromRenderedDom() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);
            chatSurfaceMounter.initializeUiForTest(ui);

            AiUiSettings settings = uiSettingsService.loadCurrent();
            settings.setEnabledSurfaceSet(EnumSet.of(AiChatSurface.FULL_ROUTE, AiChatSurface.HEADER_BUTTON));
            unconstrainedDataManager.save(settings);

            chatSurfaceMounter.refreshMountedSurfacesForTest(ui, false);

            // setVisible(false) removes the element from the rendered DOM (absent, not greyed).
            assertThat(findSidebarToggle(ui)).hasValueSatisfying(b -> assertThat(b.isVisible()).isFalse());
            assertThat(findSidebarPanel(ui)).hasValueSatisfying(p -> assertThat(p.isVisible()).isFalse());
            // The magic header button is unaffected.
            assertThat(findHeaderButtons(ui)).singleElement()
                    .extracting(Component::isVisible).isEqualTo(true);
        });
    }

    @Test
    void sidebarSurvivesNavigationBetweenTwoRoutesKeepingPushClassAndOpenState() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);
            chatSurfaceMounter.initializeUiForTest(ui);
            chatSurfaceMounter.toggleSidebarForTest(ui);
            Div panel = findSidebarPanel(ui).orElseThrow();
            assertThat(panel.hasClassName(ChatSurfaceMounter.SIDEBAR_PANEL_OPEN_CLASS)).isTrue();

            viewNavigators.view(UiTestUtils.getCurrentView(), ConversationListView.class).navigate();
            chatSurfaceMounter.refreshMountedSurfacesForTest(ui, false);
            viewNavigators.view(UiTestUtils.getCurrentView(), ConversationListView.class).navigate();
            chatSurfaceMounter.refreshMountedSurfacesForTest(ui, false);

            Div panelAfter = findSidebarPanel(ui).orElseThrow();
            assertThat(panelAfter).isSameAs(panel);
            assertThat(panelAfter.getElement().getNode().isAttached()).isTrue();
            assertThat(panelAfter.hasClassName(ChatSurfaceMounter.SIDEBAR_PANEL_OPEN_CLASS)).isTrue();
            assertThat(findSidebarToggle(ui)).isPresent();
            // No modal-overlay element was added by the panel — the main view stays interactive.
            assertThat(findFirstComponentOfType(ui, com.vaadin.flow.component.dialog.Dialog.class)).isEmpty();
        });
    }

    @Test
    void sidebarUsesTheSameSingletonChatServiceAndExistingFragmentImplementation() throws Exception {
        // The sidebar's fragment is hosted by the existing ChatPanelFragment class — no new
        // fragment subclass, no second fragment-class XML reference, no second ChatService.
        assertThat(readSidebarDescriptorSource())
                .contains("com.vn.agent.view.chat.fragment.ChatPanelFragment");

        String mounterSource = readMounterSource();
        assertThat(mounterSource)
                .contains("views.create(AiAgentSidebarView.class)")
                .contains("AiChatSurface.SIDEBAR")
                .contains("ui.getElement().appendChild(panelDiv.getElement())")
                .contains("VaadinIcon.PANEL");

        // Exactly one ChatService bean in the context — no second instance for the sidebar.
        assertThat(applicationContext.getBeanNamesForType(com.vn.agent.ChatService.class)).hasSize(1);

        // AiAgentSidebarView and ChatDialogView both compose the SAME ChatPanelFragment class
        // (the fragment field type is identical) — no duplicate fragment implementation.
        assertThat(AiAgentSidebarView.class.getDeclaredField("chatPanelFragment").getType())
                .isEqualTo(ChatPanelFragment.class)
                .isEqualTo(ChatDialogView.class.getDeclaredField("chatPanelFragment").getType());
    }

    @Test
    void secondClickClosesDialogAndKeepsSessionConversationId() {
        DialogWindow<?> existingDialog = mock(DialogWindow.class);
        AiChatUIState chatUIState = new AiChatUIState();
        AiChatSessionState chatSessionState = new AiChatSessionState();
        UUID conversationId = UUID.randomUUID();
        chatUIState.setDialogInstance(existingDialog);
        chatSessionState.setCurrentConversationId(conversationId);

        boolean closed = ChatSurfaceMounter.closeExistingDialog(chatUIState);

        assertThat(closed).isTrue();
        verify(existingDialog).close();
        assertThat(chatUIState.getDialogInstance()).isNull();
        assertThat(chatSessionState.getCurrentConversationId()).isEqualTo(conversationId);
    }

    @Test
    void dialogWindowSurvivesRouteNavigationWithConversationState() {
        systemAuthenticator.runWithUser("alice", () -> {
            AiChatSessionState chatSessionState = chatSessionStateProvider.getObject();
            AiConversation conversation = conversationGateway.loadOrCreate("alice", null, "first");
            chatSessionState.setCurrentConversationId(conversation.getId());

            assertThat(chatSurfaceMounter.findDialogParentViewForTest(UI.getCurrent()))
                    .isPresent();
            DialogWindow<ChatDialogView> dialogWindow = chatSurfaceMounter.openDialogForTest();

            viewNavigators.view(UiTestUtils.getCurrentView(), ConversationListView.class)
                    .navigate();

            ChatDialogView dialogView = dialogWindow.getView();
            ChatPanelFragment fragment = UiTestUtils.getComponent(dialogView, "chatPanelFragment");
            assertThat(chatUIStateProvider.getObject().getDialogInstance()).isSameAs(dialogWindow);
            assertThat(dialogWindow.getElement().getNode().isAttached()).isTrue();
            assertThat(fragment.getConversationId()).isEqualTo(conversation.getId());
        });
    }

    @Test
    void openDialogClosesWhenNavigatingToFullChatRoute() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);
            chatSurfaceMounter.initializeUiForTest(ui);
            AiChatUIState chatUIState = chatUIStateProvider.getObject();
            DialogWindow<ChatDialogView> dialogWindow = chatSurfaceMounter.openDialogForTest();
            assertThat(chatUIState.getDialogInstance()).isSameAs(dialogWindow);

            viewNavigators.view(UiTestUtils.getCurrentView(), ChatView.class)
                    .navigate();

            assertThat(chatUIState.getDialogInstance()).isNull();
            assertThat(findHeaderButtons(ui)).singleElement()
                    .extracting(Component::isVisible)
                    .isEqualTo(false);
        });
    }

    @Test
    void openDialogClosesWhenHeaderSurfaceDisabledAfterNavigation() {
        systemAuthenticator.runWithUser("alice", () -> {
            UI ui = UI.getCurrent();
            JmixAppLayout appLayout = uiComponents.create(JmixAppLayout.class);
            ui.add(appLayout);
            JmixListMenu menu = addChatMenu(appLayout);
            chatSurfaceMounter.initializeUiForTest(ui);
            AiChatUIState chatUIState = chatUIStateProvider.getObject();
            DialogWindow<ChatDialogView> dialogWindow = chatSurfaceMounter.openDialogForTest();
            assertThat(chatUIState.getDialogInstance()).isSameAs(dialogWindow);

            AiUiSettings settings = uiSettingsService.loadCurrent();
            settings.setEnabledSurfaceSet(EnumSet.of(AiChatSurface.FULL_ROUTE));
            unconstrainedDataManager.save(settings);

            viewNavigators.view(UiTestUtils.getCurrentView(), ConversationListView.class)
                    .navigate();

            assertThat(chatUIState.getDialogInstance()).isNull();
            assertThat(findHeaderButtons(ui)).singleElement()
                    .extracting(Component::isVisible)
                    .isEqualTo(false);
            assertChatMenuVisible(menu, true);
        });
    }

    @Test
    void dialogAfterCloseListenerClearsHandleForStandardClosePaths() {
        systemAuthenticator.runWithUser("alice", () -> {
            AiChatUIState chatUIState = chatUIStateProvider.getObject();
            DialogWindow<ChatDialogView> dialogWindow = chatSurfaceMounter.openDialogForTest();

            dialogWindow.close();

            assertThat(chatUIState.getDialogInstance()).isNull();

            DialogWindow<ChatDialogView> secondDialogWindow = chatSurfaceMounter.openDialogForTest();

            assertThat(secondDialogWindow).isNotSameAs(dialogWindow);
            assertThat(chatUIState.getDialogInstance()).isSameAs(secondDialogWindow);
        });
    }

    @Test
    void customShellWithoutAppLayoutDoesNotThrowOrMountButton() {
        UI ui = new UI();

        boolean mounted = chatSurfaceMounter.mountOrWarnForTest(ui);

        assertThat(mounted).isFalse();
        assertThat(findHeaderButtons(ui)).isEmpty();
    }

    @Test
    void fullRouteDisabledBeforeEnterForwardsHomeAndShowsLocalizedNotification() throws Exception {
        ChatView chatView = new ChatView();
        AiUiSettingsService settingsService = mock(AiUiSettingsService.class);
        AiUiSettings settings = mock(AiUiSettings.class);
        Messages messages = mock(Messages.class);
        Notifications notifications = mock(Notifications.class, RETURNS_DEEP_STUBS);
        BeforeEnterEvent event = mock(BeforeEnterEvent.class);
        String notificationText = "Chat is available via the header button only.";
        when(settings.getEnabledSurfaceSet()).thenReturn(EnumSet.of(AiChatSurface.HEADER_BUTTON));
        when(settingsService.loadCurrent()).thenReturn(settings);
        when(messages.getMessage("chatView.fullRouteDisabled")).thenReturn(notificationText);
        inject(chatView, "uiSettingsService", settingsService);
        inject(chatView, "messages", messages);
        inject(chatView, "notifications", notifications);

        chatView.beforeEnter(event);

        verify(event).forwardTo("");
        verify(notifications).create(notificationText);
        assertThat(readChatViewSource())
                .doesNotContain("implements BeforeEnterObserver")
                .contains("super.beforeEnter(event)")
                .contains("chatView.fullRouteDisabled")
                .contains("AiUiSettingsService");
    }

    @Test
    void fullRouteEnabledBeforeEnterDelegatesToJmixLifecycle() throws Exception {
        assertThat(readChatViewSource())
                .doesNotContain("implements BeforeEnterObserver")
                .contains("super.beforeEnter(event)");
    }

    private static List<JmixButton> findHeaderButtons(Component root) {
        return root.getChildren()
                .flatMap(child -> {
                    List<JmixButton> nested = findHeaderButtons(child);
                    if (isHeaderButton(child)) {
                        return java.util.stream.Stream.concat(java.util.stream.Stream.of((JmixButton) child),
                                nested.stream());
                    }
                    return nested.stream();
                })
                .toList();
    }

    private static boolean isHeaderButton(Component component) {
        return component instanceof JmixButton
                && component.getId().orElse("").equals(ChatSurfaceMounter.CHAT_BUTTON_ID);
    }

    private JmixListMenu addChatMenu(JmixAppLayout appLayout) {
        JmixListMenu menu = uiComponents.create(JmixListMenu.class);
        menu.addMenuItem(ListMenu.MenuItem.create(ChatSurfaceMounter.FULL_CHAT_MENU_ID)
                .withTitle("Chat"));
        appLayout.addToDrawer(menu);
        return menu;
    }

    private static void assertChatMenuVisible(JmixListMenu menu, boolean visible) {
        ListMenu.MenuItem chatMenuItem = menu.getMenuItem(ChatSurfaceMounter.FULL_CHAT_MENU_ID);
        assertThat(chatMenuItem).isNotNull();
        assertThat(chatMenuItem.isVisible()).isEqualTo(visible);
    }

    private static Optional<JmixButton> findSidebarToggle(Component root) {
        return findFirstComponentMatching(root, c -> c instanceof JmixButton
                && c.getId().orElse("").equals(ChatSurfaceMounter.SIDEBAR_TOGGLE_BUTTON_ID))
                .map(JmixButton.class::cast);
    }

    private static Optional<Div> findSidebarPanel(Component root) {
        return findFirstComponentMatching(root, c -> c instanceof Div d
                && d.hasClassName(ChatSurfaceMounter.SIDEBAR_PANEL_CLASS))
                .map(Div.class::cast);
    }

    private static Optional<JmixButton> findInPanelCloser(Component root) {
        return findFirstComponentMatching(root, c -> c instanceof JmixButton b
                && b.hasClassName(ChatSurfaceMounter.SIDEBAR_CLOSE_BUTTON_CLASS))
                .map(JmixButton.class::cast);
    }

    private static <T extends Component> Optional<T> findFirstComponentOfType(Component root, Class<T> type) {
        return findFirstComponentMatching(root, type::isInstance).map(type::cast);
    }

    private static Optional<Component> findFirstComponentMatching(Component root,
                                                                 java.util.function.Predicate<Component> predicate) {
        if (predicate.test(root)) {
            return Optional.of(root);
        }
        return root.getChildren()
                .map(child -> findFirstComponentMatching(child, predicate))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static String iconName(JmixButton button) {
        Component icon = button.getIcon();
        if (icon == null) {
            return null;
        }
        return icon.getElement().getAttribute("icon");
    }

    private static String readSidebarDescriptorSource() throws Exception {
        Path primary = Paths.get("src/main/resources/com/vn/agent/view/chat/ai-agent-sidebar-view.xml");
        if (Files.exists(primary)) {
            return Files.readString(primary, StandardCharsets.UTF_8);
        }
        Path fallback = Paths.get(System.getProperty("user.dir"))
                .resolve("ai-agent/ai-agent/src/main/resources/com/vn/agent/view/chat/ai-agent-sidebar-view.xml");
        return Files.readString(fallback, StandardCharsets.UTF_8);
    }

    private static String readMounterSource() throws Exception {
        Path primary = Paths.get("src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java");
        if (Files.exists(primary)) {
            return Files.readString(primary, StandardCharsets.UTF_8);
        }
        Path fallback = Paths.get(System.getProperty("user.dir"))
                .resolve("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatSurfaceMounter.java");
        return Files.readString(fallback, StandardCharsets.UTF_8);
    }

    private static String readChatViewSource() throws Exception {
        Path primary = Paths.get("src/main/java/com/vn/agent/view/chat/ChatView.java");
        if (Files.exists(primary)) {
            return Files.readString(primary, StandardCharsets.UTF_8);
        }
        Path fallback = Paths.get(System.getProperty("user.dir"))
                .resolve("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/ChatView.java");
        return Files.readString(fallback, StandardCharsets.UTF_8);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
