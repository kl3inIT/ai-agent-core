package com.vn.agent.view.configuration;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.Route;
import com.vn.agent.admin.config.AiKnobDefaults;
import com.vn.agent.entity.AiChatSurface;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.entity.AiUiSettings;
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
import com.vn.agent.view.chat.AiUiSettingsService;
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
import io.jmix.flowui.component.checkbox.JmixCheckbox;
import io.jmix.flowui.component.checkboxgroup.JmixCheckboxGroup;
import io.jmix.flowui.component.codeeditor.CodeEditor;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.multiselectcombobox.JmixMultiSelectComboBox;
import io.jmix.flowui.component.radiobuttongroup.JmixRadioButtonGroup;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.component.textfield.JmixIntegerField;
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
    @ViewComponent
    private JmixCheckboxGroup<AiChatSurface> enabledSurfacesField;
    @ViewComponent
    private JmixRadioButtonGroup<AiChatSurface> defaultSurfaceField;

    @ViewComponent
    private TypedTextField<String> taskFileTtlSecondsField;
    @ViewComponent
    private JmixIntegerField taskFilePerTurnMaxFilesField;
    @ViewComponent
    private TypedTextField<String> taskFilePerTurnMaxTotalBytesField;
    @ViewComponent
    private TypedTextField<String> taskFileMaxFileSizeBytesField;
    @ViewComponent
    private JmixCheckbox mutationConfirmationRequiredField;
    @ViewComponent
    private TypedTextField<String> mutationIdempotencyTtlSecondsField;
    @ViewComponent
    private JmixIntegerField mutationBulkMaxRowsField;
    @ViewComponent
    private JmixIntegerField promptEntityInventoryLimitField;
    @ViewComponent
    private JmixIntegerField toolsMaxFilterDepthField;
    @ViewComponent
    private JmixIntegerField titleMaxContextMessagesField;
    @ViewComponent
    private JmixIntegerField titleMinAssistantMessagesTriggerField;
    @ViewComponent
    private TypedTextField<String> uploadMaxFileSizeBytesField;

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
    private AiUiSettingsService uiSettingsService;
    @Autowired
    private AiKnobDefaults knobDefaults;
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
    private AiUiSettings currentUiSettings;

    @Subscribe
    public void onInit(final InitEvent event) {
        createAction.setViewClass(ParametersDetailView.class);
        editAction.setViewClass(ParametersDetailView.class);
        parametersDataGrid.setPartNameGenerator(parameters ->
                Boolean.TRUE.equals(parameters.getActive()) ? "ai-agent-active-row" : null);

        selectableEntities = metaclassComboBoxHelper.buildFilteredList();
        hiddenEntitiesField.setItems(selectableEntities);
        hiddenEntitiesField.setItemLabelGenerator(this::entityLabel);

        enabledSurfacesField.setItems(AiChatSurface.class);
        enabledSurfacesField.setItemLabelGenerator(messages::getMessage);
        defaultSurfaceField.setItems(AiChatSurface.class);
        defaultSurfaceField.setItemLabelGenerator(messages::getMessage);

        applyTier1DefaultHints();
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        refreshExposureSelection();
        refreshBaselinePreview();
        refreshGeneralSelection();
        refreshTier1Selection();
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

    @Subscribe("saveGeneralButton")
    public void onSaveGeneralButtonClick(final ClickEvent<Button> event) {
        Set<AiChatSurface> enabledSurfaces = enabledSurfacesField.getValue();
        AiChatSurface defaultSurface = defaultSurfaceField.getValue();

        if (enabledSurfaces == null || enabledSurfaces.isEmpty()) {
            notifyGeneralError("aiUiSettingsDetail.validation.enabledSurfacesRequired");
            return;
        }
        if (defaultSurface == null || !enabledSurfaces.contains(defaultSurface)) {
            notifyGeneralError("aiUiSettingsDetail.validation.defaultSurfaceEnabled");
            return;
        }
        try {
            currentUiSettings.setEnabledSurfaceSet(enabledSurfaces);
            currentUiSettings.setDefaultSurface(defaultSurface);
            currentUiSettings = uiSettingsService.save(currentUiSettings);
            refreshGeneralSelection();
            notifications.create(messages.getMessage("aiUiSettingsDetail.notification.saved"))
                    .withThemeVariant(NotificationVariant.LUMO_SUCCESS)
                    .show();
        } catch (Exception ex) {
            log.warn("Unable to save AI UI Settings", ex);
            NotificationUtils.errorWithDetail(notifications, messages,
                    "aiUiSettingsDetail.notification.saveError", ex);
        }
    }

    @Subscribe("resetGeneralButton")
    public void onResetGeneralButtonClick(final ClickEvent<Button> event) {
        refreshGeneralSelection();
    }

    @Subscribe("saveTier1Button")
    public void onSaveTier1ButtonClick(final ClickEvent<Button> event) {
        Long taskFileTtl;
        Integer taskFilePerTurnMaxFiles;
        Long taskFilePerTurnMaxTotalBytes;
        Long taskFileMaxFileSize;
        Long mutationIdempotencyTtl;
        Long uploadMaxFileSize;
        try {
            taskFileTtl = parseNullableLong(taskFileTtlSecondsField.getValue(),
                    "aiUiSettings.field.taskFileTtlSeconds");
            taskFilePerTurnMaxFiles = taskFilePerTurnMaxFilesField.getValue();
            taskFilePerTurnMaxTotalBytes = parseNullableLong(taskFilePerTurnMaxTotalBytesField.getValue(),
                    "aiUiSettings.field.taskFilePerTurnMaxTotalBytes");
            taskFileMaxFileSize = parseNullableLong(taskFileMaxFileSizeBytesField.getValue(),
                    "aiUiSettings.field.taskFileMaxFileSizeBytes");
            mutationIdempotencyTtl = parseNullableLong(mutationIdempotencyTtlSecondsField.getValue(),
                    "aiUiSettings.field.mutationIdempotencyTtlSeconds");
            uploadMaxFileSize = parseNullableLong(uploadMaxFileSizeBytesField.getValue(),
                    "aiUiSettings.field.uploadMaxFileSizeBytes");
        } catch (NumberFormatException invalid) {
            notifyGeneralError("aiUiSettings.tier1.error.notANumber");
            return;
        }
        currentUiSettings.setTaskFileTtlSeconds(taskFileTtl);
        currentUiSettings.setTaskFilePerTurnMaxFiles(taskFilePerTurnMaxFiles);
        currentUiSettings.setTaskFilePerTurnMaxTotalBytes(taskFilePerTurnMaxTotalBytes);
        currentUiSettings.setTaskFileMaxFileSizeBytes(taskFileMaxFileSize);
        currentUiSettings.setMutationConfirmationRequired(mutationConfirmationRequiredField.getValue());
        currentUiSettings.setMutationIdempotencyTtlSeconds(mutationIdempotencyTtl);
        currentUiSettings.setMutationBulkMaxRows(mutationBulkMaxRowsField.getValue());
        currentUiSettings.setPromptEntityInventoryLimit(promptEntityInventoryLimitField.getValue());
        currentUiSettings.setToolsMaxFilterDepth(toolsMaxFilterDepthField.getValue());
        currentUiSettings.setTitleMaxContextMessages(titleMaxContextMessagesField.getValue());
        currentUiSettings.setTitleMinAssistantMessagesTrigger(titleMinAssistantMessagesTriggerField.getValue());
        currentUiSettings.setUploadMaxFileSizeBytes(uploadMaxFileSize);

        try {
            currentUiSettings = uiSettingsService.save(currentUiSettings);
            refreshTier1Selection();
            notifications.create(messages.getMessage("aiUiSettingsDetail.notification.saved"))
                    .withThemeVariant(NotificationVariant.LUMO_SUCCESS)
                    .show();
        } catch (Exception ex) {
            log.warn("Unable to save Tier-1 AI UI settings", ex);
            NotificationUtils.errorWithDetail(notifications, messages,
                    "aiUiSettingsDetail.notification.saveError", ex);
        }
    }

    @Subscribe("resetTier1Button")
    public void onResetTier1ButtonClick(final ClickEvent<Button> event) {
        refreshTier1Selection();
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

    private void refreshGeneralSelection() {
        currentUiSettings = uiSettingsService.loadCurrent();
        Set<AiChatSurface> enabled = currentUiSettings.getEnabledSurfaceSet();
        enabledSurfacesField.setValue(enabled == null || enabled.isEmpty()
                ? EnumSet.noneOf(AiChatSurface.class)
                : EnumSet.copyOf(enabled));
        defaultSurfaceField.setValue(currentUiSettings.getDefaultSurface());
    }

    private void refreshTier1Selection() {
        if (currentUiSettings == null) {
            currentUiSettings = uiSettingsService.loadCurrent();
        }
        taskFileTtlSecondsField.setValue(longToText(currentUiSettings.getTaskFileTtlSeconds()));
        taskFilePerTurnMaxFilesField.setValue(currentUiSettings.getTaskFilePerTurnMaxFiles());
        taskFilePerTurnMaxTotalBytesField.setValue(longToText(currentUiSettings.getTaskFilePerTurnMaxTotalBytes()));
        taskFileMaxFileSizeBytesField.setValue(longToText(currentUiSettings.getTaskFileMaxFileSizeBytes()));
        mutationConfirmationRequiredField.setValue(
                currentUiSettings.getMutationConfirmationRequired() != null
                        && currentUiSettings.getMutationConfirmationRequired());
        mutationIdempotencyTtlSecondsField.setValue(longToText(currentUiSettings.getMutationIdempotencyTtlSeconds()));
        mutationBulkMaxRowsField.setValue(currentUiSettings.getMutationBulkMaxRows());
        promptEntityInventoryLimitField.setValue(currentUiSettings.getPromptEntityInventoryLimit());
        toolsMaxFilterDepthField.setValue(currentUiSettings.getToolsMaxFilterDepth());
        titleMaxContextMessagesField.setValue(currentUiSettings.getTitleMaxContextMessages());
        titleMinAssistantMessagesTriggerField.setValue(currentUiSettings.getTitleMinAssistantMessagesTrigger());
        uploadMaxFileSizeBytesField.setValue(longToText(currentUiSettings.getUploadMaxFileSizeBytes()));
    }

    private void applyTier1DefaultHints() {
        applyDefaultHint(taskFileTtlSecondsField, String.valueOf(knobDefaults.taskFileTtlSeconds()));
        applyDefaultHint(taskFilePerTurnMaxFilesField, String.valueOf(knobDefaults.taskFilePerTurnMaxFiles()));
        applyDefaultHint(taskFilePerTurnMaxTotalBytesField, String.valueOf(knobDefaults.taskFilePerTurnMaxTotalBytes()));
        applyDefaultHint(taskFileMaxFileSizeBytesField, String.valueOf(knobDefaults.taskFileMaxFileSizeBytes()));
        applyDefaultHint(mutationConfirmationRequiredField, String.valueOf(knobDefaults.mutationConfirmationRequired()));
        applyDefaultHint(mutationIdempotencyTtlSecondsField, String.valueOf(knobDefaults.mutationIdempotencyTtlSeconds()));
        applyDefaultHint(mutationBulkMaxRowsField, String.valueOf(knobDefaults.mutationBulkMaxRows()));
        applyDefaultHint(promptEntityInventoryLimitField, String.valueOf(knobDefaults.promptEntityInventoryLimit()));
        applyDefaultHint(toolsMaxFilterDepthField, String.valueOf(knobDefaults.toolsMaxFilterDepth()));
        applyDefaultHint(titleMaxContextMessagesField, String.valueOf(knobDefaults.titleMaxContextMessages()));
        applyDefaultHint(titleMinAssistantMessagesTriggerField, String.valueOf(knobDefaults.titleMinAssistantMessagesTrigger()));
        applyDefaultHint(uploadMaxFileSizeBytesField, String.valueOf(knobDefaults.uploadMaxFileSizeBytes()));
    }

    /**
     * Stamps placeholder + helperText on a Tier-1 input so admins see the
     * resolver fallback value when a field is empty. Saving an empty field
     * persists {@code null} so future default changes flow through.
     */
    private void applyDefaultHint(com.vaadin.flow.component.AbstractField<?, ?> field, String defaultText) {
        String helper = messages.formatMessage("", "aiUiSettings.tier1.helper.default", defaultText);
        if (field instanceof com.vaadin.flow.component.HasPlaceholder placeholderField) {
            placeholderField.setPlaceholder(defaultText);
        }
        if (field instanceof com.vaadin.flow.component.HasHelper helperField) {
            helperField.setHelperText(helper);
        }
    }

    private static String longToText(Long value) {
        return value == null ? "" : value.toString();
    }

    private static Long parseNullableLong(String text, String fieldKey) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            throw new NumberFormatException("Field " + fieldKey + " is not a valid integer: " + text);
        }
    }

    private void notifyGeneralError(String messageKey) {
        notifications.create(messages.getMessage(messageKey))
                .withThemeVariant(NotificationVariant.LUMO_ERROR)
                .withPosition(Notification.Position.MIDDLE)
                .withDuration(5000)
                .show();
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
