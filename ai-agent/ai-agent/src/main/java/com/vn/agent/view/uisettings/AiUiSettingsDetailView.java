package com.vn.agent.view.uisettings;

import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiChatSurface;
import com.vn.agent.entity.AiUiSettings;
import com.vn.agent.view.chat.AiUiSettingsService;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.checkboxgroup.JmixCheckboxGroup;
import io.jmix.flowui.component.radiobuttongroup.JmixRadioButtonGroup;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;

import java.util.EnumSet;
import java.util.Set;

@Route(value = "ai-agent/ui-settings", layout = DefaultMainViewParent.class)
@ViewController("AiAgent_AiUiSettings.detail")
@ViewDescriptor("ai-ui-settings-detail-view.xml")
@EditedEntityContainer("uiSettingsDc")
public class AiUiSettingsDetailView extends StandardDetailView<AiUiSettings> {

    @ViewComponent
    private JmixCheckboxGroup<AiChatSurface> enabledSurfacesField;
    @ViewComponent
    private JmixRadioButtonGroup<AiChatSurface> defaultSurfaceField;

    @Autowired
    private AiUiSettingsService uiSettingsService;
    @Autowired
    private Messages messages;
    @Autowired
    private Notifications notifications;

    public AiUiSettingsDetailView() {
        setReloadEdited(false);
    }

    @Override
    protected void findEntityId(@NonNull BeforeEnterEvent event) {
        // Singleton editor: route has no entity id and arbitrary row navigation is disabled.
    }

    @Override
    protected void setupEntityToEdit() {
        setEntityToEdit(uiSettingsService.loadCurrent());
    }

    @Subscribe
    public void onInit(final InitEvent event) {
        enabledSurfacesField.setItems(AiChatSurface.class);
        enabledSurfacesField.setItemLabelGenerator(messages::getMessage);
        defaultSurfaceField.setItems(AiChatSurface.class);
        defaultSurfaceField.setItemLabelGenerator(messages::getMessage);
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        AiUiSettings settings = getEditedEntity();
        enabledSurfacesField.setValue(copySurfaceSet(settings.getEnabledSurfaceSet()));
        defaultSurfaceField.setValue(settings.getDefaultSurface());
    }

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        Set<AiChatSurface> enabledSurfaces = enabledSurfacesField.getValue();
        AiChatSurface defaultSurface = defaultSurfaceField.getValue();

        if (enabledSurfaces == null || enabledSurfaces.isEmpty()) {
            rejectSave(event, "aiUiSettingsDetail.validation.enabledSurfacesRequired");
            return;
        }
        if (defaultSurface == null || !enabledSurfaces.contains(defaultSurface)) {
            rejectSave(event, "aiUiSettingsDetail.validation.defaultSurfaceEnabled");
            return;
        }

        AiUiSettings settings = getEditedEntity();
        settings.setEnabledSurfaceSet(enabledSurfaces);
        settings.setDefaultSurface(defaultSurface);
    }

    private void rejectSave(BeforeSaveEvent event, String messageKey) {
        notifications.create(messages.getMessage(messageKey))
                .withThemeVariant(NotificationVariant.LUMO_WARNING)
                .show();
        event.preventSave();
    }

    private static Set<AiChatSurface> copySurfaceSet(Set<AiChatSurface> source) {
        if (source == null || source.isEmpty()) {
            return EnumSet.noneOf(AiChatSurface.class);
        }
        return EnumSet.copyOf(source);
    }
}
