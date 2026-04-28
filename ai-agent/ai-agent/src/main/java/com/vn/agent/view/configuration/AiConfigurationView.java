package com.vn.agent.view.configuration;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.exposure.AiExposureRuleAdminService;
import com.vn.agent.orchestration.AiParametersResolver;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.orchestration.SystemPromptComposer;
import com.vn.agent.parameters.AiParametersBody;
import com.vn.agent.parameters.AiParametersBodyYamlMapper;
import com.vn.agent.parameters.ParametersService;
import com.vn.agent.utils.DataGridRenderers;
import com.vn.agent.utils.DataGridRenderers.ActionColumnType;
import com.vn.agent.utils.NotificationUtils;
import com.vn.agent.view.exposure.MetaclassComboBoxHelper;
import com.vn.agent.view.parameters.ParametersDetailView;
import io.jmix.core.MessageTools;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.action.list.CreateAction;
import io.jmix.flowui.action.list.EditAction;
import io.jmix.flowui.action.list.RemoveAction;
import io.jmix.flowui.component.codeeditor.CodeEditor;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.multiselectcombobox.JmixMultiSelectComboBox;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Unified admin surface for AI runtime configuration, governance, and prompt diagnostics.
 */
@Route(value = "ai-agent/configuration", layout = DefaultMainViewParent.class)
@ViewController("AiAgent_Configuration")
@ViewDescriptor("ai-configuration-view.xml")
public class AiConfigurationView extends StandardView {

    private static final Logger log = LoggerFactory.getLogger(AiConfigurationView.class);

    @ViewComponent
    private DataGrid<AiParameters> parametersDataGrid;
    @ViewComponent
    private CollectionLoader<AiParameters> parametersDl;
    @ViewComponent("parametersDataGrid.createAction")
    private CreateAction<AiParameters> createAction;
    @ViewComponent("parametersDataGrid.editAction")
    private EditAction<AiParameters> editAction;
    @ViewComponent("parametersDataGrid.removeAction")
    private RemoveAction<AiParameters> removeAction;
    @ViewComponent
    private JmixMultiSelectComboBox<MetaClass> hiddenEntitiesField;
    @ViewComponent
    private TypedTextField<String> generatedAtField;
    @ViewComponent
    private TypedTextField<String> activeProfileField;
    @ViewComponent
    private TypedTextField<String> previewConversationIdField;
    @ViewComponent
    private CodeEditor baselinePreviewField;
    @ViewComponent
    private CodeEditor composedPromptPreviewField;

    @Autowired
    private ParametersService parametersService;
    @Autowired
    private AiParametersBodyYamlMapper yamlMapper;
    @Autowired
    private AiExposureRuleAdminService exposureRuleAdminService;
    @Autowired
    private BaselineContextProvider baselineContextProvider;
    @Autowired
    private AiParametersResolver parametersResolver;
    @Autowired
    private CurrentAuthentication currentAuthentication;
    @Autowired
    private MetaclassComboBoxHelper metaclassComboBoxHelper;
    @Autowired
    private MessageTools messageTools;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Messages messages;
    @Autowired
    private UiComponents uiComponents;

    private final Map<UUID, AiParametersBody> parsedCache = new HashMap<>();
    private List<MetaClass> selectableEntities = Collections.emptyList();

    @Subscribe
    public void onInit(final InitEvent event) {
        createAction.setViewClass(ParametersDetailView.class);
        editAction.setViewClass(ParametersDetailView.class);
        parametersDataGrid.setPartNameGenerator(parameters ->
                Boolean.TRUE.equals(parameters.getActive()) ? "ai-agent-active-row" : null);

        selectableEntities = metaclassComboBoxHelper.buildFilteredList();
        hiddenEntitiesField.setItems(selectableEntities);
        hiddenEntitiesField.setItemLabelGenerator(this::entityLabel);
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        refreshExposureSelection();
        refreshBaselinePreview();
    }

    @Subscribe(id = "parametersDl", target = Target.DATA_LOADER)
    public void onParametersDlPostLoad(final CollectionLoader.PostLoadEvent<AiParameters> event) {
        parsedCache.clear();
    }

    @Supply(to = "parametersDataGrid.model", subject = "renderer")
    private Renderer<AiParameters> parametersDataGridModelRenderer() {
        return new TextRenderer<>(row -> {
            AiParametersBody body = parsedBodyFor(row);
            return body == null ? "" : Objects.toString(body.model(), "");
        });
    }

    @Supply(to = "parametersDataGrid.status", subject = "renderer")
    private Renderer<AiParameters> parametersDataGridStatusRenderer() {
        return DataGridRenderers.buildBadgeColumn(
                uiComponents,
                row -> messages.getMessage(Boolean.TRUE.equals(row.getActive())
                        ? "parametersList.badge.active"
                        : "parametersList.column.active"),
                row -> Boolean.TRUE.equals(row.getActive()) ? "success" : "contrast");
    }

    @Supply(to = "parametersDataGrid.actions", subject = "renderer")
    private Renderer<AiParameters> parametersDataGridActionsRenderer() {
        return DataGridRenderers.buildActionsColumn(
                uiComponents,
                EnumSet.of(ActionColumnType.EDIT, ActionColumnType.DELETE),
                this::onParameterRowAction);
    }

    @Subscribe("parametersDataGrid.setActive")
    public void onSetActiveAction(final ActionPerformedEvent event) {
        AiParameters row = parametersDataGrid.getSingleSelectedItem();
        if (row == null) {
            return;
        }
        try {
            parametersService.setActive(row.getId());
            parsedCache.clear();
            parametersDl.load();
            refreshBaselinePreview();
            notifications.create(messages.getMessage("parametersList.action.setActive"))
                    .withThemeVariant(NotificationVariant.LUMO_SUCCESS)
                    .show();
        } catch (Exception ex) {
            log.warn("setActive failed for profile {}", row.getId(), ex);
            NotificationUtils.errorWithDetail(notifications, messages,
                    "parametersList.error.setActive", ex);
        }
    }

    @Subscribe("saveExposureRulesButton")
    public void onSaveExposureRulesButtonClick(final ClickEvent<Button> event) {
        try {
            exposureRuleAdminService.replaceHiddenEntityNames(
                    selectedHiddenEntityNames(),
                    selectableEntityNames());
            refreshExposureSelection();
            refreshBaselinePreview();
            notifications.create(messages.getMessage("aiConfiguration.exposure.saved"))
                    .withThemeVariant(NotificationVariant.LUMO_SUCCESS)
                    .show();
        } catch (Exception ex) {
            log.warn("Unable to save AI exposure rules", ex);
            NotificationUtils.errorWithDetail(notifications, messages,
                    "aiConfiguration.exposure.error.save", ex);
        }
    }

    @Subscribe("refreshExposureRulesButton")
    public void onRefreshExposureRulesButtonClick(final ClickEvent<Button> event) {
        refreshExposureSelection();
        refreshBaselinePreview();
    }

    @Subscribe("refreshPromptButton")
    public void onRefreshPromptButtonClick(final ClickEvent<Button> event) {
        refreshBaselinePreview();
    }

    private void onParameterRowAction(AiParameters row, ActionColumnType type) {
        parametersDataGrid.select(row);
        switch (type) {
            case EDIT -> editAction.execute();
            case DELETE -> removeAction.execute();
            default -> { }
        }
    }

    private AiParametersBody parsedBodyFor(AiParameters row) {
        if (row == null || row.getBodyYaml() == null || row.getBodyYaml().isBlank()) {
            return null;
        }
        return parsedCache.computeIfAbsent(row.getId(), id -> {
            try {
                return yamlMapper.readValue(row.getBodyYaml());
            } catch (Exception ex) {
                log.debug("Unable to parse bodyYaml for profile {}: {}", id, ex.getMessage());
                return null;
            }
        });
    }

    private void refreshExposureSelection() {
        Set<String> hiddenEntityNames = exposureRuleAdminService.findHiddenEntityNames();
        Set<MetaClass> selected = selectableEntities.stream()
                .filter(metaClass -> hiddenEntityNames.contains(metaClass.getName()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        hiddenEntitiesField.setValue(selected);
    }

    private Set<String> selectedHiddenEntityNames() {
        Set<MetaClass> selected = hiddenEntitiesField.getValue();
        if (selected == null || selected.isEmpty()) {
            return Set.of();
        }
        return selected.stream()
                .map(MetaClass::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> selectableEntityNames() {
        return selectableEntities.stream()
                .map(MetaClass::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String entityLabel(MetaClass metaClass) {
        return messageTools.getEntityCaption(metaClass) + " (" + metaClass.getName() + ")";
    }

    private void refreshBaselinePreview() {
        UUID previewConversationId = UUID.randomUUID();
        UUID previewRunId = UUID.randomUUID();
        AiParameters activeParameters = parametersResolver.resolveActive();
        String profileSystemPrompt = parametersResolver.effectiveSystemPrompt(
                activeParameters, currentUsername(), previewConversationId, previewRunId);
        String baselineText = baselineContextProvider.renderAsText(previewConversationId);
        baselinePreviewField.setValue(baselineText == null || baselineText.isBlank()
                ? messages.getMessage("baselineContext.empty")
                : baselineText);
        composedPromptPreviewField.setValue(SystemPromptComposer.compose(baselineText, profileSystemPrompt));
        activeProfileField.setValue(activeParameters.getProfileName());
        previewConversationIdField.setValue(previewConversationId.toString());
        generatedAtField.setValue(OffsetDateTime.now().toString());
    }

    private String currentUsername() {
        try {
            UserDetails user = currentAuthentication.getUser();
            String username = user.getUsername();
            if (username != null && !username.isBlank()) {
                return username;
            }
        } catch (RuntimeException ignored) {
            // Preview can still render static/default prompt without a user.
        }
        return "anonymous";
    }
}
