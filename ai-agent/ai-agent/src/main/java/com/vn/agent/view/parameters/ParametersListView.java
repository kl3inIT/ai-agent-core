package com.vn.agent.view.parameters;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.parameters.AiParametersBody;
import com.vn.agent.parameters.AiParametersBodyYamlMapper;
import com.vn.agent.parameters.ParametersService;
import io.jmix.flowui.action.list.CreateAction;
import io.jmix.flowui.action.list.EditAction;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

/**
 * UI-04 Parameters list (admin-only via {@code AiAgentAdminRole @ViewPolicy} in 07-01).
 *
 * <p>Columns render parsed YAML (Profile name / Model / Status badge / Created date) — the
 * raw {@code bodyYaml} is NOT displayed in the grid; it lives only in the detail view's YAML
 * tab. D-14: "Set active" button commits immediately (no confirm). D-28 admin-gated.</p>
 *
 * <p>Parsed-body results are cached per-row in a concurrent map keyed by entity id; the cache
 * is cleared on every loader reload via {@code addPostLoadListener}.</p>
 */
@Route(value = "ai-agent/parameters", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_Parameters.list")
@ViewDescriptor(path = "parameters-list-view.xml")
public class ParametersListView extends StandardListView<AiParameters> {

    private static final Logger log = LoggerFactory.getLogger(ParametersListView.class);

    @ViewComponent
    private DataGrid<AiParameters> parametersDataGrid;
    @ViewComponent
    private JmixButton setActiveBtn;
    @ViewComponent
    private CollectionLoader<AiParameters> parametersDl;
    @ViewComponent("parametersDataGrid.createAction")
    private CreateAction<AiParameters> createAction;
    @ViewComponent("parametersDataGrid.editAction")
    private EditAction<AiParameters> editAction;

    @Autowired
    private ParametersService parametersService;
    @Autowired
    private AiParametersBodyYamlMapper yamlMapper;
    @Autowired
    private Notifications notifications;
    @Autowired
    private MessageSource messageSource;
    @Autowired
    private UiComponents uiComponents;

    /** Per-view cache: parse YAML once per row render pass; invalidated on reload. */
    private final Map<UUID, AiParametersBody> parsedCache = new ConcurrentHashMap<>();

    @Subscribe
    public void onInit(final InitEvent event) {
        setActiveBtn.setEnabled(false);
        createAction.setViewClass(ParametersDetailView.class);
        editAction.setViewClass(ParametersDetailView.class);

        // Grid selection → button enabled state: kept raw per the review's carve-out
        // for dynamic grid selection wiring.
        parametersDataGrid.addSelectionListener(e ->
                setActiveBtn.setEnabled(e.getFirstSelectedItem().isPresent()));

        // Active-row highlight (left border + tint via Lumo variables; applied via part name).
        parametersDataGrid.setPartNameGenerator(p ->
                Boolean.TRUE.equals(p.getActive()) ? "ai-agent-active-row" : null);

        // Invalidate parsed cache on any reload so renderers re-read fresh bodyYaml.
        parametersDl.addPostLoadListener(e -> parsedCache.clear());
    }

    /** Model column — parsed from bodyYaml, NEVER raw blob. */
    @Supply(to = "parametersDataGrid.model", subject = "renderer")
    private Renderer<AiParameters> parametersDataGridModelRenderer() {
        return new TextRenderer<>(row -> {
            AiParametersBody body = parsedBodyFor(row);
            return body == null ? "" : Objects.toString(body.model(), "");
        });
    }

    /** Status column — Vaadin Badge via ComponentRenderer. */
    @Supply(to = "parametersDataGrid.status", subject = "renderer")
    private Renderer<AiParameters> parametersDataGridStatusRenderer() {
        return new ComponentRenderer<>(this::createStatusBadge, this::updateStatusBadge);
    }

    private Span createStatusBadge() {
        Span badge = uiComponents.create(Span.class);
        badge.getElement().getThemeList().add("badge");
        return badge;
    }

    private void updateStatusBadge(Span badge, AiParameters row) {
        boolean active = Boolean.TRUE.equals(row.getActive());
        String key = active ? "parametersList.badge.active" : "parametersList.column.active";
        badge.setText(messageSource.getMessage(
                key, null, active ? "Active" : "-", UI.getCurrent().getLocale()));
        // Reset theme list — ComponentRenderer reuses the Span across rows.
        badge.getElement().getThemeList().clear();
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add(active ? "success" : "contrast");
    }

    @Subscribe("setActiveBtn")
    public void onSetActiveBtnClick(final ClickEvent<Button> event) {
        onSetActiveClick();
    }

    /** D-14: immediate commit, no confirm dialog. */
    private void onSetActiveClick() {
        AiParameters row = parametersDataGrid.getSingleSelectedItem();
        if (row == null) {
            return;
        }
        try {
            parametersService.setActive(row.getId());
            parsedCache.clear();
            parametersDl.load();
            String msg = messageSource.getMessage(
                    "parametersList.action.setActive", null,
                    "Set active", UI.getCurrent().getLocale());
            notifications.create(msg)
                    .withThemeVariant(NotificationVariant.LUMO_SUCCESS)
                    .show();
        } catch (Exception ex) {
            log.warn("setActive failed for profile {}", row.getId(), ex);
            notifications.create(buildErrorMessage("parametersList.error.setActive", ex))
                    .withThemeVariant(NotificationVariant.LUMO_ERROR)
                    .show();
        }
    }

    /** Parse bodyYaml for a row once per render pass; null on malformed YAML. */
    private AiParametersBody parsedBodyFor(AiParameters row) {
        if (row == null || row.getBodyYaml() == null || row.getBodyYaml().isBlank()) {
            return null;
        }
        return parsedCache.computeIfAbsent(row.getId(), id -> {
            try {
                return yamlMapper.readValue(row.getBodyYaml());
            } catch (Exception ex) {
                // Malformed row — column renders blank; detail view surfaces the full error on open.
                log.debug("Unable to parse bodyYaml for profile {}: {}", id, ex.getMessage());
                return null;
            }
        });
    }

    private String buildErrorMessage(String key, Exception ex) {
        String message = messageSource.getMessage(key, null, key, currentLocale());
        String detail = ex.getMessage();
        if (detail == null || detail.isBlank()) {
            return message;
        }
        return message + " " + detail;
    }

    private Locale currentLocale() {
        UI ui = UI.getCurrent();
        return ui == null ? Locale.getDefault() : ui.getLocale();
    }

}
