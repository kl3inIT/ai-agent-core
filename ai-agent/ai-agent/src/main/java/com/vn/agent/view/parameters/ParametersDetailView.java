package com.vn.agent.view.parameters;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.parameters.AiParametersBody;
import com.vn.agent.parameters.AiParametersBodyYamlMapper;
import com.vn.agent.parameters.ParametersService;
import com.vn.agent.tools.AgentToolCallbacks;
import io.jmix.core.EntityStates;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.codeeditor.CodeEditor;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;

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
    private TypedTextField<String> modelField;
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
    private MessageSource messageSource;
    @Autowired
    private AgentToolCallbacks agentToolCallbacks;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private EntityStates entityStates;

    private List<String> registryToolNames = Collections.emptyList();

    @Subscribe
    public void onInit(final InitEvent event) {
        // ---- Tool registry discovery (no hardcoded six-tool fallback) ----
        List<String> toolNames = discoverToolNames();
        if (toolNames.isEmpty()) {
            String label = messageSource.getMessage(
                    "parametersDetail.toolRegistry.empty", null,
                    "No @Tool beans registered", currentLocale());
            throw new IllegalStateException(
                    "No @Tool beans registered — ParametersDetailView requires a non-empty tool "
                            + "registry. Check Phase 3/4 tool-bean wiring; do NOT paper over this "
                            + "with a hardcoded fallback. (" + label + ")");
        }
        registryToolNames = List.copyOf(toolNames);
        enabledToolsField.setItems(registryToolNames);

        // ---- Live YAML preview: every form field value-change refreshes the preview (D-12) ----
        modelField.addValueChangeListener(e -> refreshYamlPreview());
        temperatureField.addValueChangeListener(e -> refreshYamlPreview());
        topPField.addValueChangeListener(e -> refreshYamlPreview());
        maxTokensField.addValueChangeListener(e -> refreshYamlPreview());
        systemPromptField.addValueChangeListener(e -> refreshYamlPreview());
        enabledToolsField.addValueChangeListener(e -> refreshYamlPreview());
        ragTopKField.addValueChangeListener(e -> refreshYamlPreview());
        ragSimilarityThresholdField.addValueChangeListener(e -> refreshYamlPreview());

        // ---- Set active button (D-14 immediate commit) ----
        setActiveBtn.addClickListener(e -> onSetActiveClick());
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
                notifications.create(ex.getMessage() == null ? "YAML parse error" : ex.getMessage())
                        .withThemeVariant(NotificationVariant.LUMO_WARNING)
                        .show();
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
            notifications.create(ex.getMessage() == null ? "Invalid form" : ex.getMessage())
                    .withThemeVariant(NotificationVariant.LUMO_ERROR)
                    .show();
            return;
        }
        // writeAsYaml re-runs Jakarta validation; invalid form will throw here and we block save.
        try {
            edited.setBodyYaml(yamlMapper.writeAsYaml(body));
        } catch (Exception ex) {
            event.preventSave();
            notifications.create(ex.getMessage() == null ? "YAML write error" : ex.getMessage())
                    .withThemeVariant(NotificationVariant.LUMO_ERROR)
                    .show();
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
            notifications.create(messageSource.getMessage(
                            "parametersList.action.setActive", null,
                            "Set active", currentLocale()))
                    .withThemeVariant(NotificationVariant.LUMO_SUCCESS)
                    .show();
        } catch (Exception ex) {
            log.warn("setActive failed for profile {}", edited.getId(), ex);
            notifications.create(ex.getMessage() == null ? "Error" : ex.getMessage())
                    .withThemeVariant(NotificationVariant.LUMO_ERROR)
                    .show();
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
        try {
            ToolCallback[] callbacks = agentToolCallbacks.forCurrentUser();
            if (callbacks != null) {
                for (ToolCallback cb : callbacks) {
                    if (cb != null && cb.getToolDefinition() != null
                            && cb.getToolDefinition().name() != null) {
                        names.add(cb.getToolDefinition().name());
                    }
                }
            }
        } catch (Exception ex) {
            // AgentToolCallbacks.forCurrentUser relies on CurrentAuthentication; during
            // autoconfig-loaded views we have a request context, so this shouldn't normally
            // fail. If it does, fall through to ApplicationContext scan below.
            log.warn("AgentToolCallbacks.forCurrentUser() threw during tool discovery; falling "
                    + "back to ApplicationContext scan", ex);
        }
        if (names.isEmpty()) {
            // Fallback #2: reflectively scan ApplicationContext for @Tool methods.
            names.addAll(scanApplicationContextForToolMethods());
        }
        return new ArrayList<>(names);
    }

    private Set<String> scanApplicationContextForToolMethods() {
        Set<String> names = new LinkedHashSet<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception skip) {
                continue;
            }
            Class<?> clazz = bean.getClass();
            // Unwrap Spring proxies.
            while (clazz != null && clazz.getName().contains("$$")) {
                clazz = clazz.getSuperclass();
            }
            if (clazz == null) continue;
            for (var method : clazz.getDeclaredMethods()) {
                var tool = method.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
                if (tool != null) {
                    String name = tool.name() == null || tool.name().isEmpty()
                            ? method.getName() : tool.name();
                    names.add(name);
                }
            }
        }
        return names;
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
        modelField.setValue(nullToBlank(body.model()));
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
            String hint = messageSource.getMessage(
                    "parametersDetail.validation.modelRequired", null,
                    "Fill required fields to preview YAML.", currentLocale());
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

    private Locale currentLocale() {
        UI ui = UI.getCurrent();
        return ui == null ? Locale.getDefault() : ui.getLocale();
    }

}
