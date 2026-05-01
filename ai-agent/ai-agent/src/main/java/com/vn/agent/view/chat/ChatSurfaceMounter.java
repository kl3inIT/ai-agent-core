package com.vn.agent.view.chat;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.ButtonVariant;
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
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewController;
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
        refreshMountedSurfaces(ui, uiSettingsService.loadCurrent(), fullChatRoute);
    }

    private void initializeUi(UI ui) {
        MountedChatSurfaceState mountedState = mountedState(ui);
        ui.addAfterNavigationListener(event -> afterNavigation(ui, event));

        AiUiSettings settings = uiSettingsService.loadCurrent();
        mountHeaderButton(ui, mountedState, false);
        refreshMountedSurfaces(ui, settings, false);
    }

    private void afterNavigation(UI ui, AfterNavigationEvent event) {
        AiUiSettings settings = uiSettingsService.loadCurrent();
        MountedChatSurfaceState mountedState = mountedState(ui);
        mountHeaderButton(ui, mountedState, true);
        refreshMountedSurfaces(ui, settings, isFullChatRoute(event));
        reattachOpenDialogToUi();
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

    private static void configureDialogWindow(DialogWindow<ChatDialogView> dialogWindow) {
        dialogWindow.setModal(false);
        dialogWindow.setLeft("65%");
        dialogWindow.setTop("5%");
        dialogWindow.setWidth("35%");
        dialogWindow.setHeight("75%");
        dialogWindow.setResizable(true);
        dialogWindow.setDraggable(true);
    }

    private void refreshMountedSurfaces(UI ui, AiUiSettings settings, boolean fullChatRoute) {
        MountedChatSurfaceState mountedState = mountedState(ui);
        if (mountedState.chatButton != null) {
            mountedState.chatButton.setVisible(shouldShowHeaderButton(
                    settings,
                    isDialogViewPermitted(),
                    fullChatRoute));
        }
        updateFullRouteMenuVisibility(ui, settings);
    }

    private void updateFullRouteMenuVisibility(UI ui, AiUiSettings settings) {
        findFirstComponent(ui, JmixListMenu.class)
                .map(menu -> menu.getMenuItem(FULL_CHAT_MENU_ID))
                .ifPresent(menuItem -> menuItem.setVisible(shouldShowFullRouteMenu(settings)));
    }

    static boolean shouldShowHeaderButton(AiUiSettings settings, boolean dialogPermitted, boolean fullChatRoute) {
        return settings.getEnabledSurfaceSet().contains(AiChatSurface.HEADER_BUTTON)
                && dialogPermitted
                && !fullChatRoute;
    }

    static boolean shouldShowFullRouteMenu(AiUiSettings settings) {
        return settings.getEnabledSurfaceSet().contains(AiChatSurface.FULL_ROUTE);
    }

    private boolean isDialogViewPermitted() {
        UiShowViewContext accessContext = new UiShowViewContext(CHAT_DIALOG_VIEW_ID);
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
    }
}
