package com.vn.agent.view.parameters;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.ComboBoxBase;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.router.Route;
import com.vn.agent.admin.config.ChatModelCatalog;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.parameters.AiParametersBody;
import com.vn.agent.parameters.AiParametersBodyYamlMapper;
import com.vn.agent.parameters.ParametersService;
import com.vn.agent.tools.AgentToolCallbacks;
import com.vn.agent.utils.NotificationUtils;
import io.jmix.core.EntityStates;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.codeeditor.CodeEditor;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.component.multiselectcombobox.JmixMultiSelectComboBox;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.component.textfield.JmixBigDecimalField;
import io.jmix.flowui.component.textfield.JmixIntegerField;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * UI-04 Parameters detail (admin-only via {@code AiAgentAdminRole @ViewPolicy} in 07-01).
 *
 * <p>D-10 Form is source of truth. D-12 YAML preview regenerates on every valueChange via
 * {@link AiParametersBodyYamlMapper#writeAsYaml(AiParametersBody)}. D-13 per-field validation
 * (Jakarta constraints on {@link AiParametersBody}) blocks Save. D-14 Set active button
 * commits immediately (no confirm).</p>
 *
 * <p>{@code enabledTools} list comes from the live {@code @Tool} registry
 * ({@link AgentToolCallbacks#forCurrentUser()}) — the view throws {@link IllegalStateException}
 * at init if the registry is empty, so a mis-configured deploy surfaces loudly rather than
 * shipping a silently-drifting hardcoded list.</p>
 *
 * <p>Plan 07-05 expected form fields for {@code rateLimit / tokenBudget / iterationCap /
 * outputScannerPatterns}. Those knobs live in {@link com.vn.agent.guard.AiAgentGuardProperties}
 * (app-level config), NOT in {@link AiParametersBody}. Rule 1 (plan field-list tolerance):
 * bind the real body fields — {@code model, temperature, topP, maxTokens, systemPrompt,
 * enabledTools, ragTopK, ragSimilarityThreshold} — and document the divergence in the Summary.</p>
 */
@Route(value = "ai-agent/parameters/:id", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_Parameters.detail")
@ViewDescriptor(path = "parameters-detail-view.xml")
@EditedEntityContainer("parametersDc")
public class ParametersDetailView extends StandardDetailView<AiParameters> {

    private static final Logger log = LoggerFactory.getLogger(ParametersDetailView.class);

    @ViewComponent
    private TypedTextField<String> profileNameField;
    @ViewComponent
    private JmixComboBox<String> modelField;
    @ViewComponent
    private JmixBigDecimalField temperatureField;
    @ViewComponent
    private JmixBigDecimalField topPField;
    @ViewComponent
    private JmixIntegerField maxTokensField;
    @ViewComponent
    private JmixTextArea systemPromptField;
    @ViewComponent
    private JmixMultiSelectComboBox<String> enabledToolsField;
    @ViewComponent
    private JmixIntegerField ragTopKField;
    @ViewComponent
    private JmixBigDecimalField ragSimilarityThresholdField;
    @ViewComponent
    private CodeEditor yamlPreviewField;
    @ViewComponent
    private JmixButton setActiveBtn;

    @Autowired
    private AiParametersBodyYamlMapper yamlMapper;
    @Autowired
    private ParametersService parametersService;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Messages messages;
    @Autowired
    private AgentToolCallbacks agentToolCallbacks;
    @Autowired
    private EntityStates entityStates;
    @Autowired
    private ChatModelCatalog chatModelCatalog;

    private List<String> registryToolNames = Collections.emptyList();

    @Subscribe
    public void onInit(final InitEvent event) {
        // ---- Tool registry discovery (no hardcoded six-tool fallback) ----
        List<String> toolNames = discoverToolNames();
        if (toolNames.isEmpty()) {
            throw new IllegalStateException(
                    "No @Tool beans registered — ParametersDetailView requires a non-empty tool "
                            + "registry. Check Phase 3/4 tool-bean wiring; do NOT paper over this "
                            + "with a hardcoded fallback. ("
                            + messages.getMessage("parametersDetail.toolRegistry.empty") + ")");
        }
        registryToolNames = List.copyOf(toolNames);
        enabledToolsField.setItems(registryToolNames);

        // ---- Curated open-weights model catalog (Phase 16 D-06 / RESEARCH Pattern 4) ----
        // ComboBox is allowCustomValue=true (see view XML), so the catalog seeds the dropdown
        // and any admin can still type a proprietary or experimental model id verbatim. The
        // label generator appends "(default)" to the marked-default entry so the admin sees
        // at-a-glance which choice mirrors default-params.yaml.model.
        List<String> catalogIds = chatModelCatalog.entries().stream()
                .map(ChatModelCatalog.Entry::id)
                .toList();
        modelField.setItems(catalogIds);
        modelField.setItemLabelGenerator(id -> {
            if (id == null) {
                return "";
            }
            ChatModelCatalog.Entry entry = chatModelCatalog.findById(id);
            if (entry == null) {
                // Custom-entered id — render verbatim (escape hatch per SPEC criterion 4).
                return id;
            }
            String label = entry.labelMessageKey() == null
                    ? id
                    : messages.getMessage(entry.labelMessageKey());
            return entry.isDefault()
                    ? label + " " + messages.getMessage("parametersDetail.modelField.defaultSuffix")
                    : label;
        });

        // Pin decimal fields to ENGLISH locale so "." is the decimal separator regardless of
        // the user's Jmix locale. Under "vi" locale the default NumberFormat uses "," as the
        // decimal separator, which rejects "0.1" / "0.3" with a parse error — a UX bug for
        // values like temperature / topP / similarity threshold that users conventionally type
        // with a dot.
        temperatureField.setLocale(Locale.ENGLISH);
        topPField.setLocale(Locale.ENGLISH);
        ragSimilarityThresholdField.setLocale(Locale.ENGLISH);
    }

    // ---- D-12 Live YAML preview: each field's @Subscribe("fieldId") valueChange handler ----
    // Each field has its own handler method so the wiring is declarative per Jmix convention.

    @Subscribe("modelField")
    public void onModelFieldChange(final AbstractField.ComponentValueChangeEvent<?, ?> event) {
        refreshYamlPreview();
    }

    /**
     * RESEARCH Pattern 4 — when the admin types a model id that is NOT in the curated
     * catalog and presses Enter, Vaadin's {@code ComboBox} fires a {@code CustomValueSetEvent}
     * but does NOT call {@code setValue} unless we do it explicitly. Without this wire, the
     * typed value is dropped on blur and never reaches the YAML body.
     */
    @Subscribe("modelField")
    public void onModelFieldCustomValueSet(
            final ComboBoxBase.CustomValueSetEvent<ComboBox<String>> event) {
        modelField.setValue(event.getDetail());
    }

    @Subscribe("temperatureField")
    public void onTemperatureFieldChange(final HasValue.ValueChangeEvent<?> event) {
        refreshYamlPreview();
    }

    @Subscribe("topPField")
    public void onTopPFieldChange(final HasValue.ValueChangeEvent<?> event) {
        refreshYamlPreview();
    }

    @Subscribe("maxTokensField")
    public void onMaxTokensFieldChange(final HasValue.ValueChangeEvent<?> event) {
        refreshYamlPreview();
    }

    @Subscribe("systemPromptField")
    public void onSystemPromptFieldChange(final HasValue.ValueChangeEvent<?> event) {
        refreshYamlPreview();
    }

    @Subscribe("enabledToolsField")
    public void onEnabledToolsFieldChange(final HasValue.ValueChangeEvent<?> event) {
        refreshYamlPreview();
    }

    @Subscribe("ragTopKField")
    public void onRagTopKFieldChange(final HasValue.ValueChangeEvent<?> event) {
        refreshYamlPreview();
    }

    @Subscribe("ragSimilarityThresholdField")
    public void onRagSimilarityThresholdFieldChange(final HasValue.ValueChangeEvent<?> event) {
        refreshYamlPreview();
    }

    @Subscribe("setActiveBtn")
    public void onSetActiveBtnClick(final ClickEvent<Button> event) {
        onSetActiveClick();
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        AiParameters edited = getEditedEntity();
        if (edited == null) {
            return;
        }
        // Parse existing bodyYaml into form fields (null-safe: new entities → empty form).
        if (edited.getBodyYaml() != null && !edited.getBodyYaml().isBlank()) {
            try {
                AiParametersBody body = yamlMapper.readValue(edited.getBodyYaml());
                populateFormFromBody(body);
            } catch (Exception ex) {
                log.warn("Unable to parse existing bodyYaml for profile {}: {}",
                        edited.getId(), ex.getMessage());
                NotificationUtils.warnWithDetail(notifications, messages,
                        "parametersDetail.error.yamlParse", ex);
            }
        }
        refreshYamlPreview();

        // Set active is only meaningful for persisted rows.
        setActiveBtn.setEnabled(!entityStates.isNew(edited) && !Boolean.TRUE.equals(edited.getActive()));
    }

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        AiParameters edited = getEditedEntity();
        AiParametersBody body;
        try {
            body = buildBodyFromForm();
        } catch (Exception ex) {
            event.preventSave();
            NotificationUtils.errorWithDetail(notifications, messages,
                    "parametersDetail.error.invalidForm", ex);
            return;
        }
        // writeAsYaml re-runs Jakarta validation; invalid form will throw here and we block save.
        try {
            edited.setBodyYaml(yamlMapper.writeAsYaml(body));
        } catch (Exception ex) {
            event.preventSave();
            NotificationUtils.errorWithDetail(notifications, messages,
                    "parametersDetail.error.yamlWrite", ex);
        }
    }

    /** D-14: immediate commit, no confirm dialog. */
    private void onSetActiveClick() {
        AiParameters edited = getEditedEntity();
        if (edited == null || edited.getId() == null) {
            return;
        }
        try {
            parametersService.setActive(edited.getId());
            edited.setActive(Boolean.TRUE);
            setActiveBtn.setEnabled(false);
            notifications.create(messages.getMessage("parametersList.action.setActive"))
                    .withThemeVariant(NotificationVariant.LUMO_SUCCESS)
                    .show();
        } catch (Exception ex) {
            log.warn("setActive failed for profile {}", edited.getId(), ex);
            NotificationUtils.errorWithDetail(notifications, messages,
                    "parametersDetail.error.setActive", ex);
        }
    }

    // ---- Tool discovery (issue #10: NO hardcoded six-tool fallback) ----

    /**
     * Obtain the list of {@code @Tool} names from the live registry. Uses Phase 3's per-request
     * assembly — {@link AgentToolCallbacks#forCurrentUser()} reflects over every {@code @Tool}
     * annotated method on the built-in tools + host {@code ToolContributor}s. Returns an empty
     * list when the registry has no tools — the caller throws {@link IllegalStateException}
     * rather than falling back to a hardcoded list (registry regressions must surface as
     * deploy errors, not silent UI drift).
     */
    private List<String> discoverToolNames() {
        Set<String> names = new LinkedHashSet<>();
        ToolCallback[] callbacks = agentToolCallbacks.forCurrentUser();
        if (callbacks != null) {
            for (ToolCallback cb : callbacks) {
                if (cb != null && cb.getToolDefinition() != null
                        && cb.getToolDefinition().name() != null) {
                    names.add(cb.getToolDefinition().name());
                }
            }
        }
        return new ArrayList<>(names);
    }

    // ---- Form <-> AiParametersBody bridging ----

    private AiParametersBody buildBodyFromForm() {
        List<String> tools = toList(enabledToolsField.getValue());
        return new AiParametersBody(
                trimToNull(modelField.getValue()),
                temperatureField.getValue(),
                topPField.getValue(),
                maxTokensField.getValue(),
                trimToNull(systemPromptField.getValue()),
                tools,
                ragTopKField.getValue(),
                ragSimilarityThresholdField.getValue()
        );
    }

    private void populateFormFromBody(AiParametersBody body) {
        // WR-06 — ComboBox (Plan 16-05) handles null as "no selection" cleanly. The previous
        // nullToBlank wrapper produced an empty-string selection that the value-change handler
        // treated as a custom value (allowCustomValue=true), surfacing `model: ''` in the YAML
        // preview. Pass the raw body.model() so a fresh AiParameters with model=null lands in
        // the form as "no selection" — required=true validation then asks the operator to pick
        // one rather than failing on an empty custom value.
        modelField.setValue(body.model());
        temperatureField.setValue(body.temperature());
        topPField.setValue(body.topP());
        maxTokensField.setValue(body.maxTokens());
        systemPromptField.setValue(nullToBlank(body.systemPrompt()));
        List<String> tools = body.enabledTools();
        if (tools == null || tools.isEmpty()) {
            enabledToolsField.setValue(Collections.emptySet());
        } else {
            // Intersect with registry to drop stale names; keeps Jakarta 'unknown tool' work out of UI layer.
            Set<String> known = new LinkedHashSet<>();
            for (String t : tools) {
                if (registryToolNames.contains(t)) {
                    known.add(t);
                }
            }
            enabledToolsField.setValue(known);
        }
        ragTopKField.setValue(body.ragTopK());
        ragSimilarityThresholdField.setValue(body.ragSimilarityThreshold());
    }

    private void refreshYamlPreview() {
        try {
            AiParametersBody body = buildBodyFromForm();
            yamlPreviewField.setValue(yamlMapper.writeAsYaml(body));
        } catch (Exception ex) {
            // Build/validation in progress — render a best-effort hint, not a hard error.
            String hint = messages.getMessage("parametersDetail.validation.modelRequired");
            yamlPreviewField.setValue("# " + hint + "\n# " + Objects.toString(ex.getMessage(), ""));
        }
    }

    // ---- Helpers ----

    private static List<String> toList(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return List.copyOf(values);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nullToBlank(String s) {
        return s == null ? "" : s;
    }

}
