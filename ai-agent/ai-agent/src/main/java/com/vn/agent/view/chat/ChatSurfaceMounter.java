package com.vn.agent.view.chat;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.AfterNavigationEvent;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vn.agent.entity.AiChatSurface;
import com.vn.agent.entity.AiUiSettings;
import io.jmix.core.AccessManager;
import io.jmix.core.Messages;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.Views;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.component.main.JmixListMenu;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.NavigateCloseAction;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewControllerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.util.Optional;

@org.springframework.stereotype.Component
public class ChatSurfaceMounter implements VaadinServiceInitListener {

    static final String CHAT_BUTTON_ID = "aiAgentHeaderChatButton";
    static final String CHAT_BUTTON_CLASS_NAME = "ai-agent-chat-header-button";
    static final String CHAT_DIALOG_VIEW_ID = "AiAgent_ChatDialog";
    static final String FULL_CHAT_VIEW_ID = "AiAgent_Chat";
    static final String FULL_CHAT_MENU_ID = "aiAgent.chat";

    // --- SIDEBAR surface (Phase 15 Plan 03 — D-01..D-04, REVIEWS point #1) ---
    static final String SIDEBAR_VIEW_ID = "AiAgent_Sidebar";
    static final String SIDEBAR_TOGGLE_BUTTON_ID = "aiAgentSidebarToggleButton";
    static final String SIDEBAR_TOGGLE_BUTTON_CLASS = "ai-agent-sidebar-toggle-button";
    static final String SIDEBAR_PANEL_CLASS = "ai-agent-sidebar";
    static final String SIDEBAR_PANEL_HEADER_CLASS = "ai-agent-sidebar__header";
    static final String SIDEBAR_PANEL_OPEN_CLASS = "ai-agent-sidebar--open";
    static final String SIDEBAR_TOGGLE_ACTIVE_CLASS = "ai-agent-sidebar-toggle--active";
    static final String CONTENT_PUSHED_CLASS = "ai-agent-content--pushed";
    static final String SIDEBAR_CLOSE_BUTTON_CLASS = "ai-agent-sidebar__close-button";

    private static final Logger log = LoggerFactory.getLogger(ChatSurfaceMounter.class);

    private final AiUiSettingsService uiSettingsService;
    private final AccessManager accessManager;
    private final ObjectProvider<AiChatUIState> chatUIStateProvider;
    private final Views views;
    private final UiComponents uiComponents;
    private final Messages messages;
    private final ApplicationContext applicationContext;

    public ChatSurfaceMounter(AiUiSettingsService uiSettingsService,
                              AccessManager accessManager,
                              ObjectProvider<AiChatUIState> chatUIStateProvider,
                              Views views,
                              UiComponents uiComponents,
                              Messages messages,
                              ApplicationContext applicationContext) {
        this.uiSettingsService = uiSettingsService;
        this.accessManager = accessManager;
        this.chatUIStateProvider = chatUIStateProvider;
        this.views = views;
        this.uiComponents = uiComponents;
        this.messages = messages;
        this.applicationContext = applicationContext;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(uiInitEvent -> initializeUi(uiInitEvent.getUI()));
    }

    void initializeUiForTest(UI ui) {
        initializeUi(ui);
    }

    boolean mountOrWarnForTest(UI ui) {
        return mountHeaderButton(ui, mountedState(ui), true);
    }

    void refreshMountedSurfacesForTest(UI ui, boolean fullChatRoute) {
        refreshMountedSurfaces(ui, uiSettingsService.loadCurrent(), isDialogViewPermitted(),
                isSidebarViewPermitted(), fullChatRoute);
    }

    void toggleSidebarForTest(UI ui) {
        toggleSidebar(ui);
    }

    private void initializeUi(UI ui) {
        MountedChatSurfaceState mountedState = mountedState(ui);
        ui.addAfterNavigationListener(event -> afterNavigation(ui, event));

        AiUiSettings settings = uiSettingsService.loadCurrent();
        mountHeaderButton(ui, mountedState, false);
        mountSidebar(ui, mountedState);
        refreshMountedSurfaces(ui, settings, isDialogViewPermitted(), isSidebarViewPermitted(), false);
    }

    private void afterNavigation(UI ui, AfterNavigationEvent event) {
        AiUiSettings settings = uiSettingsService.loadCurrent();
        boolean fullChatRoute = isFullChatRoute(event);
        boolean dialogPermitted = isDialogViewPermitted();
        boolean sidebarPermitted = isSidebarViewPermitted();

        MountedChatSurfaceState mountedState = mountedState(ui);
        mountHeaderButton(ui, mountedState, true);
        mountSidebar(ui, mountedState);
        refreshMountedSurfaces(ui, settings, dialogPermitted, sidebarPermitted, fullChatRoute);
        syncDialogAvailability(settings, dialogPermitted, fullChatRoute);
    }

    private boolean mountHeaderButton(UI ui, MountedChatSurfaceState mountedState, boolean warnIfMissing) {
        if (mountedState.chatButton != null && mountedState.chatButton.getParent().isPresent()) {
            return true;
        }

        Optional<JmixButton> existingButton = findComponentById(ui, CHAT_BUTTON_ID, JmixButton.class);
        if (existingButton.isPresent()) {
            mountedState.chatButton = existingButton.get();
            return true;
        }

        Optional<AppLayout> appLayout = findFirstComponent(ui, AppLayout.class);
        if (appLayout.isEmpty()) {
            warnMissingAppLayoutOnce(mountedState, warnIfMissing);
            return false;
        }

        JmixButton chatButton = createChatButton();
        appLayout.get().addToNavbar(chatButton);
        mountedState.chatButton = chatButton;
        return true;
    }

    private JmixButton createChatButton() {
        JmixButton chatButton = uiComponents.create(JmixButton.class);
        chatButton.setId(CHAT_BUTTON_ID);
        chatButton.addClassName(CHAT_BUTTON_CLASS_NAME);
        chatButton.setIcon(VaadinIcon.MAGIC.create());
        chatButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON);
        chatButton.getElement()
                .setAttribute("aria-label", messages.getMessage("chatSurfaceMounter.headerButton.ariaLabel"));
        chatButton.addClickListener(clickEvent -> toggleDialog());
        return chatButton;
    }

    // ---- SIDEBAR surface mount + toggle -----------------------------------

    /**
     * Mount (idempotently) the SIDEBAR navbar toggle and the fixed-position panel Div.
     * <p>RESEARCH Open Q3 — mirrors the existing HEADER_BUTTON pattern: mount-always-then
     * -{@code setVisible(false)} (see {@link #refreshMountedSurfaces}). Vaadin
     * {@code setVisible(false)} removes the element from the rendered DOM, so "absent
     * (not greyed) when disabled/not-permitted" holds. The panel lives on the UI element
     * (NOT the AppLayout content slot, which {@code setContent()}-replaces on navigation)
     * so it survives route navigation; {@code afterNavigation} re-asserts attachment, the
     * push class and the toggle. The host view ({@link AiAgentSidebarView}) is created
     * ONCE per UI via {@code views.create(...)} (the same way {@code openDialog()} creates
     * {@code ChatDialogView}) and re-attached idempotently — never re-created.</p>
     */
    private void mountSidebar(UI ui, MountedChatSurfaceState mountedState) {
        mountSidebarToggle(ui, mountedState);
        mountSidebarPanel(ui, mountedState);
    }

    private void mountSidebarToggle(UI ui, MountedChatSurfaceState mountedState) {
        if (mountedState.sidebarToggleButton != null
                && mountedState.sidebarToggleButton.getParent().isPresent()) {
            return;
        }

        Optional<JmixButton> existingButton = findComponentById(ui, SIDEBAR_TOGGLE_BUTTON_ID, JmixButton.class);
        if (existingButton.isPresent()) {
            mountedState.sidebarToggleButton = existingButton.get();
            return;
        }

        Optional<AppLayout> appLayout = findFirstComponent(ui, AppLayout.class);
        if (appLayout.isEmpty()) {
            warnMissingAppLayoutOnce(mountedState, true);
            return;
        }

        JmixButton toggleButton = createSidebarToggleButton(ui);
        appLayout.get().addToNavbar(toggleButton);
        mountedState.sidebarToggleButton = toggleButton;
        applySidebarOpenStateToToggle(mountedState);
    }

    private JmixButton createSidebarToggleButton(UI ui) {
        JmixButton toggleButton = uiComponents.create(JmixButton.class);
        toggleButton.setId(SIDEBAR_TOGGLE_BUTTON_ID);
        toggleButton.addClassName(SIDEBAR_TOGGLE_BUTTON_CLASS);
        toggleButton.setIcon(VaadinIcon.PANEL.create());
        toggleButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON);
        toggleButton.getElement().setAttribute("aria-pressed", "false");
        toggleButton.getElement().setAttribute("aria-label",
                messages.getMessage("chatSurfaceMounter.sidebarToggle.ariaLabel.closed"));
        toggleButton.addClickListener(clickEvent -> toggleSidebar(ui));
        return toggleButton;
    }

    private void mountSidebarPanel(UI ui, MountedChatSurfaceState mountedState) {
        Div panelDiv = mountedState.sidebarPanelDiv;
        if (panelDiv == null) {
            panelDiv = createSidebarPanel(ui, mountedState);
            mountedState.sidebarPanelDiv = panelDiv;
        }

        // Re-assert the panel is attached to the UI element (it survives navigation only
        // because it lives on the UI, not the AppLayout content slot — mirrors
        // attachDialogWindowToUi for the modeless HEADER_BUTTON dialog).
        if (!panelDiv.getElement().getNode().isAttached()) {
            ui.getElement().appendChild(panelDiv.getElement());
        }

        // Re-assert the push class on the (possibly new) AppLayout if the sidebar is open.
        if (mountedState.sidebarOpen) {
            findFirstComponent(ui, AppLayout.class)
                    .ifPresent(appLayout -> appLayout.addClassName(CONTENT_PUSHED_CLASS));
        }
    }

    private Div createSidebarPanel(UI ui, MountedChatSurfaceState mountedState) {
        Div panelDiv = new Div();
        panelDiv.addClassName(SIDEBAR_PANEL_CLASS);

        Div headerDiv = new Div();
        headerDiv.addClassName(SIDEBAR_PANEL_HEADER_CLASS);

        JmixButton closeButton = uiComponents.create(JmixButton.class);
        closeButton.addClassName(SIDEBAR_CLOSE_BUTTON_CLASS);
        closeButton.setIcon(VaadinIcon.ANGLE_DOUBLE_RIGHT.create());
        closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON);
        closeButton.getElement().setAttribute("aria-label",
                messages.getMessage("chatSurfaceMounter.sidebarCloser.ariaLabel"));
        closeButton.addClickListener(clickEvent -> toggleSidebar(ui));
        headerDiv.add(closeButton);
        panelDiv.add(headerDiv);

        // The lean Jmix-owned host view that owns the ChatPanelFragment (REVIEWS point #1)
        // — created exactly the way openDialog() creates ChatDialogView. We attach the
        // view's element directly under the panel Div and drive its show lifecycle via
        // ViewControllerUtils.fireEvent(BeforeShowEvent, then ReadyEvent) — this is the
        // Jmix 2.8 path for showing a non-routed, non-dialog view: views.create(...) has
        // already completed @ViewComponent injection / @Subscribe wiring / fragment
        // loading; firing ReadyEvent on the host view propagates to the fragment's
        // own onReady (Fragment.onHostReadyInternal). No DialogWindow overlay machinery
        // is involved, so there is no modality curtain and the main view stays interactive.
        AiAgentSidebarView sidebarView = views.create(AiAgentSidebarView.class);
        panelDiv.getElement().appendChild(sidebarView.getElement());
        ViewControllerUtils.fireEvent(sidebarView, new View.BeforeShowEvent(sidebarView));
        ViewControllerUtils.fireEvent(sidebarView, new View.ReadyEvent(sidebarView));
        mountedState.sidebarHostView = sidebarView;

        return panelDiv;
    }

    /** Single toggle path shared by the navbar toggle button AND the in-panel closer so
     *  the open/closed state never drifts (D-03). Flips: the panel {@code --open} class,
     *  the AppLayout push class, the toggle {@code --active} class, {@code aria-pressed},
     *  and the toggle's {@code aria-label}. */
    private void toggleSidebar(UI ui) {
        MountedChatSurfaceState mountedState = mountedState(ui);
        setSidebarOpen(ui, mountedState, !mountedState.sidebarOpen);
    }

    private void setSidebarOpen(UI ui, MountedChatSurfaceState mountedState, boolean open) {
        mountedState.sidebarOpen = open;

        if (mountedState.sidebarPanelDiv != null) {
            if (open) {
                mountedState.sidebarPanelDiv.addClassName(SIDEBAR_PANEL_OPEN_CLASS);
            } else {
                mountedState.sidebarPanelDiv.removeClassName(SIDEBAR_PANEL_OPEN_CLASS);
            }
        }

        findFirstComponent(ui, AppLayout.class).ifPresent(appLayout -> {
            if (open) {
                appLayout.addClassName(CONTENT_PUSHED_CLASS);
            } else {
                appLayout.removeClassName(CONTENT_PUSHED_CLASS);
            }
        });

        applySidebarOpenStateToToggle(mountedState);
    }

    private void applySidebarOpenStateToToggle(MountedChatSurfaceState mountedState) {
        JmixButton toggleButton = mountedState.sidebarToggleButton;
        if (toggleButton == null) {
            return;
        }

        boolean open = mountedState.sidebarOpen;
        if (open) {
            toggleButton.addClassName(SIDEBAR_TOGGLE_ACTIVE_CLASS);
        } else {
            toggleButton.removeClassName(SIDEBAR_TOGGLE_ACTIVE_CLASS);
        }
        toggleButton.getElement().setAttribute("aria-pressed", Boolean.toString(open));
        toggleButton.getElement().setAttribute("aria-label", messages.getMessage(open
                ? "chatSurfaceMounter.sidebarToggle.ariaLabel.open"
                : "chatSurfaceMounter.sidebarToggle.ariaLabel.closed"));
    }

    private void toggleDialog() {
        AiChatUIState chatUIState = chatUIStateProvider.getObject();
        if (closeExistingDialog(chatUIState)) {
            return;
        }

        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }

        if (findDialogParentView(ui).isEmpty()) {
            log.warn("AI Agent chat dialog not opened: no Jmix view is currently attached to the UI.");
            return;
        }

        openDialog();
    }

    DialogWindow<ChatDialogView> openDialogForTest() {
        return openDialog();
    }

    Optional<View<?>> findDialogParentViewForTest(UI ui) {
        return findDialogParentView(ui);
    }

    private DialogWindow<ChatDialogView> openDialog() {
        AiChatUIState chatUIState = chatUIStateProvider.getObject();
        ChatDialogView dialogView = views.create(ChatDialogView.class);
        DialogWindow<ChatDialogView> dialogWindow = new DialogWindow<>(dialogView);
        dialogWindow.setApplicationContext(applicationContext);
        dialogWindow.afterPropertiesSet();
        configureDialogWindow(dialogWindow);
        dialogWindow.addAfterCloseListener(afterCloseEvent -> {
            if (!(afterCloseEvent.getCloseAction() instanceof NavigateCloseAction)) {
                clearDialogInstanceIfCurrent(dialogWindow);
            }
        });
        chatUIState.setDialogInstance(dialogWindow);
        dialogWindow.open();
        attachDialogWindowToUi(dialogWindow);
        return dialogWindow;
    }

    private void reattachOpenDialogToUi() {
        AiChatUIState chatUIState = chatUIStateProvider.getIfAvailable();
        if (chatUIState != null && chatUIState.getDialogInstance() != null) {
            attachDialogWindowToUi(chatUIState.getDialogInstance());
        }
    }

    private static void attachDialogWindowToUi(DialogWindow<?> dialogWindow) {
        UI ui = UI.getCurrent();
        if (ui != null) {
            ui.getElement().appendChild(dialogWindow.getElement());
        }
    }

    static boolean closeExistingDialog(AiChatUIState chatUIState) {
        DialogWindow<?> dialogWindow = chatUIState.getDialogInstance();
        if (dialogWindow == null) {
            return false;
        }

        dialogWindow.close();
        chatUIState.clearDialogInstance();
        return true;
    }

    private void syncDialogAvailability(AiUiSettings settings, boolean dialogPermitted, boolean fullChatRoute) {
        if (shouldShowHeaderButton(settings, dialogPermitted, fullChatRoute)) {
            reattachOpenDialogToUi();
            return;
        }

        closeAndClearDialogInstance();
    }

    private void closeAndClearDialogInstance() {
        AiChatUIState chatUIState = chatUIStateProvider.getIfAvailable();
        if (chatUIState == null) {
            return;
        }

        DialogWindow<?> dialogWindow = chatUIState.getDialogInstance();
        if (dialogWindow == null) {
            return;
        }

        dialogWindow.close();
        clearDialogInstanceIfCurrent(dialogWindow);
    }

    private void clearDialogInstanceIfCurrent(DialogWindow<?> dialogWindow) {
        AiChatUIState chatUIState = chatUIStateProvider.getIfAvailable();
        if (chatUIState != null && chatUIState.getDialogInstance() == dialogWindow) {
            chatUIState.clearDialogInstance();
        }
    }

    private static void configureDialogWindow(DialogWindow<ChatDialogView> dialogWindow) {
        // Phase 13.1 UAT-fix-02 — width 35% was too narrow for the right-pane
        // attachment cards (the H5 filename + dual icon buttons need ~200px of
        // their own and 32% of 35%-of-viewport on a 1366px laptop is ~150px).
        // Bumped to 50% + repositioned to 50% left so cards no longer overflow.
        dialogWindow.setModal(false);
        dialogWindow.setLeft("50%");
        dialogWindow.setTop("5%");
        dialogWindow.setWidth("50%");
        dialogWindow.setHeight("85%");
        dialogWindow.setResizable(true);
        dialogWindow.setDraggable(true);
    }

    private void refreshMountedSurfaces(UI ui,
                                        AiUiSettings settings,
                                        boolean dialogPermitted,
                                        boolean sidebarPermitted,
                                        boolean fullChatRoute) {
        MountedChatSurfaceState mountedState = mountedState(ui);
        if (mountedState.chatButton != null) {
            mountedState.chatButton.setVisible(shouldShowHeaderButton(
                    settings,
                    dialogPermitted,
                    fullChatRoute));
        }

        boolean showSidebar = shouldShowSidebar(settings, sidebarPermitted);
        if (mountedState.sidebarToggleButton != null) {
            mountedState.sidebarToggleButton.setVisible(showSidebar);
        }
        if (mountedState.sidebarPanelDiv != null) {
            mountedState.sidebarPanelDiv.setVisible(showSidebar);
            if (!showSidebar && mountedState.sidebarOpen) {
                // Mirrors syncDialogAvailability for the dialog — collapse the panel and
                // drop the push class when the surface gets disabled/un-permitted.
                setSidebarOpen(ui, mountedState, false);
            }
        }

        updateFullRouteMenuVisibility(ui, settings);
    }

    private void updateFullRouteMenuVisibility(UI ui, AiUiSettings settings) {
        boolean visible = shouldShowFullRouteMenu(settings);
        // Try the JmixListMenu API path first — it's the right thing to do when the menu
        // is rebuilt fresh (e.g. on route change). However, on this app's StandardMainView
        // the rendered <li id="aiAgent.chat"> is not consistently re-rendered when the
        // MenuItem.setVisible flips, so we follow up with a direct DOM toggle by id as a
        // workaround. The JS path is idempotent (hidden attribute set/removed) and runs
        // inside ui.access via Element.executeJs, so it is thread-safe.
        findFirstComponent(ui, JmixListMenu.class)
                .map(menu -> menu.getMenuItem(FULL_CHAT_MENU_ID))
                .ifPresent(menuItem -> menuItem.setVisible(visible));
        ui.getElement().executeJs(
                "const item = document.getElementById($0); if (item) item.hidden = !$1;",
                FULL_CHAT_MENU_ID,
                visible);
    }

    static boolean shouldShowHeaderButton(AiUiSettings settings, boolean dialogPermitted, boolean fullChatRoute) {
        return settings.getEnabledSurfaceSet().contains(AiChatSurface.HEADER_BUTTON)
                && dialogPermitted
                && !fullChatRoute;
    }

    static boolean shouldShowFullRouteMenu(AiUiSettings settings) {
        return settings.getEnabledSurfaceSet().contains(AiChatSurface.FULL_ROUTE);
    }

    static boolean shouldShowSidebar(AiUiSettings settings, boolean sidebarViewPermitted) {
        return settings.getEnabledSurfaceSet().contains(AiChatSurface.SIDEBAR)
                && sidebarViewPermitted;
    }

    private boolean isDialogViewPermitted() {
        UiShowViewContext accessContext = new UiShowViewContext(CHAT_DIALOG_VIEW_ID);
        accessManager.applyRegisteredConstraints(accessContext);
        return accessContext.isPermitted();
    }

    private boolean isSidebarViewPermitted() {
        UiShowViewContext accessContext = new UiShowViewContext(SIDEBAR_VIEW_ID);
        accessManager.applyRegisteredConstraints(accessContext);
        return accessContext.isPermitted();
    }

    private static boolean isFullChatRoute(AfterNavigationEvent event) {
        return event.getActiveChain().stream()
                .map(Object::getClass)
                .map(componentClass -> componentClass.getAnnotation(ViewController.class))
                .anyMatch(viewController -> viewController != null
                        && FULL_CHAT_VIEW_ID.equals(viewController.value()));
    }

    private static void warnMissingAppLayoutOnce(MountedChatSurfaceState mountedState, boolean warnIfMissing) {
        if (!warnIfMissing || mountedState.missingAppLayoutWarned) {
            return;
        }

        mountedState.missingAppLayoutWarned = true;
        log.warn("AI Agent chat button not mounted: host main view does not extend AppLayout. "
                + "Use the FULL_ROUTE surface or wrap your shell in StandardMainView.");
    }

    private static MountedChatSurfaceState mountedState(UI ui) {
        MountedChatSurfaceState mountedState = ComponentUtil.getData(ui, MountedChatSurfaceState.class);
        if (mountedState == null) {
            mountedState = new MountedChatSurfaceState();
            ComponentUtil.setData(ui, MountedChatSurfaceState.class, mountedState);
        }
        return mountedState;
    }

    private static Optional<View<?>> findDialogParentView(UI ui) {
        Optional<StandardMainView> mainView = findFirstComponent(ui, StandardMainView.class);
        if (mainView.isPresent()) {
            return Optional.of(mainView.get());
        }

        return findFirstComponent(ui, View.class)
                .map(view -> (View<?>) view);
    }

    private static <T extends Component> Optional<T> findComponentById(Component root,
                                                                       String id,
                                                                       Class<T> componentClass) {
        if (componentClass.isInstance(root) && root.getId().orElse("").equals(id)) {
            return Optional.of(componentClass.cast(root));
        }

        return root.getChildren()
                .map(child -> findComponentById(child, id, componentClass))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static <T extends Component> Optional<T> findFirstComponent(Component root, Class<T> componentClass) {
        if (componentClass.isInstance(root)) {
            return Optional.of(componentClass.cast(root));
        }

        return root.getChildren()
                .map(child -> findFirstComponent(child, componentClass))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static final class MountedChatSurfaceState {
        private JmixButton chatButton;
        private boolean missingAppLayoutWarned;

        // SIDEBAR surface — per-UI state (D-04 / REVIEWS point #1). Kept on the per-UI
        // MountedChatSurfaceState (NOT AiChatSessionState, which stays at
        // currentConversationId + listeners), so AiChatUIState.java is intentionally
        // left untouched by this plan.
        private JmixButton sidebarToggleButton;
        private Div sidebarPanelDiv;
        private AiAgentSidebarView sidebarHostView;
        private boolean sidebarOpen;
    }
}
