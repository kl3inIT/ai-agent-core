package com.vn.agent.view.uisettings;

import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Route;
import com.vn.agent.admin.config.KnobInventory;
import com.vn.agent.entity.AiChatSurface;
import com.vn.agent.entity.AiUiSettings;
import com.vn.agent.view.chat.AiUiSettingsService;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.checkboxgroup.JmixCheckboxGroup;
import io.jmix.flowui.component.radiobuttongroup.JmixRadioButtonGroup;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
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

    @ViewComponent
    private CollectionContainer<KnobInventory.KnobRow> bootConfigDc;
    @ViewComponent
    private CollectionContainer<KnobInventory.SecretIndicatorRow> secretsDc;

    @Autowired
    private AiUiSettingsService uiSettingsService;
    @Autowired
    private Messages messages;
    @Autowired
    private Notifications notifications;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private KnobInventory knobInventory;

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

        // Phase 16 D-02 — populate the Boot Config + Secrets dataGrids from the
        // KnobInventory snapshot captured at ApplicationReadyEvent. The values
        // surfaced here are a boot-time snapshot; Tier-1 edits in the tabs above
        // do NOT change them until the next restart (CFG-02 boot-config semantics).
        bootConfigDc.getMutableItems().clear();
        bootConfigDc.getMutableItems().addAll(knobInventory.tier2());
        secretsDc.getMutableItems().clear();
        secretsDc.getMutableItems().addAll(knobInventory.tier3());
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        AiUiSettings settings = getEditedEntity();
        enabledSurfacesField.setValue(copySurfaceSet(settings.getEnabledSurfaceSet()));
        defaultSurfaceField.setValue(settings.getDefaultSurface());

        // Sync field changes to the managed entity immediately. Reading from the entity
        // at BeforeSaveEvent time is more reliable than re-reading enabledSurfacesField,
        // and forces the DataContext snapshot diff to detect the change so the UPDATE
        // actually fires (entity setter must route through setEnabledSurfaceIds — see
        // AiUiSettings.setEnabledSurfaceSet for the EclipseLink weaving requirement).
        enabledSurfacesField.addValueChangeListener(change -> {
            Set<AiChatSurface> next = change.getValue();
            settings.setEnabledSurfaceSet(next == null ? EnumSet.noneOf(AiChatSurface.class) : next);
        });
        defaultSurfaceField.addValueChangeListener(change -> {
            settings.setDefaultSurface(change.getValue());
        });
    }

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        AiUiSettings settings = getEditedEntity();
        Set<AiChatSurface> enabledSurfaces = settings.getEnabledSurfaceSet();
        AiChatSurface defaultSurface = settings.getDefaultSurface();

        if (enabledSurfaces == null || enabledSurfaces.isEmpty()) {
            rejectSave(event, "aiUiSettingsDetail.validation.enabledSurfacesRequired");
            return;
        }
        if (defaultSurface == null || !enabledSurfaces.contains(defaultSurface)) {
            rejectSave(event, "aiUiSettingsDetail.validation.defaultSurfaceEnabled");
        }
    }

    /**
     * Pattern D — {@code @Supply} renderer for the Boot Config grid's badge column.
     * Renders a localised label distinguishing Tier-2 boot toggles ("property only —
     * requires restart") from Tier-1 editable knobs ("editable above"). The Span
     * receives the {@code badge} theme so Lumo styles it as a pill.
     */
    @Supply(to = "bootConfigGrid.badge", subject = "renderer")
    private Renderer<KnobInventory.KnobRow> bootConfigBadgeRenderer() {
        return new ComponentRenderer<>(row -> {
            Span badge = uiComponents.create(Span.class);
            badge.setText(messages.getMessage(row.requiresRestart()
                    ? "aiUiSettings.bootConfig.badge.requiresRestart"
                    : "aiUiSettings.bootConfig.badge.tier1Editable"));
            badge.getElement().getThemeList().add("badge");
            return badge;
        });
    }

    /**
     * Pattern D — {@code @Supply} renderer for the Secrets grid's configured column.
     * SEC-08 invariant: this renderer NEVER reads the raw secret value into the DOM.
     * Only the boolean {@code configured} flag (computed at scan time as
     * {@code environment.getProperty(key) != null && !blank}) reaches the rendered
     * Span. Build-time {@code SecretRedactionInvariantsTest#noSecretBoundEditable}
     * cross-checks that this controller never calls {@code environment.getProperty}
     * on a Tier-3 key.
     */
    @Supply(to = "secretsGrid.configured", subject = "renderer")
    private Renderer<KnobInventory.SecretIndicatorRow> secretsConfiguredRenderer() {
        return new ComponentRenderer<>(row -> {
            Span span = uiComponents.create(Span.class);
            span.setText(messages.getMessage(row.configured()
                    ? "aiUiSettings.secrets.indicator.yes"
                    : "aiUiSettings.secrets.indicator.no"));
            return span;
        });
    }

    private void rejectSave(BeforeSaveEvent event, String messageKey) {
        notifications.create(messages.getMessage(messageKey))
                .withThemeVariant(NotificationVariant.LUMO_ERROR)
                .withPosition(Notification.Position.MIDDLE)
                .withDuration(5000)
                .show();
        event.preventSave();
    }

    private static Set<AiChatSurface> copySurfaceSet(Set<AiChatSurface> source) {
        if (source == null || source.isEmpty()) {
            return EnumSet.noneOf(AiChatSurface.class);
        }
        return EnumSet.copyOf(source);
    }

    // UI import retained for Vaadin Flow rendering context (silence unused-import IDE hint).
    @SuppressWarnings("unused")
    private static UI uiReference() {
        return UI.getCurrent();
    }
}
