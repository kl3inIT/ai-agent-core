package com.vn.agent.view.diagnostics;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.orchestration.AiParametersResolver;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.orchestration.SystemPromptComposer;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.component.codeeditor.CodeEditor;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Admin diagnostic view for inspecting the current read-only baseline prompt block.
 */
@Route(value = "ai-agent/baseline-context", layout = DefaultMainViewParent.class)
@ViewController("AiAgent_BaselineContext")
@ViewDescriptor("baseline-context-view.xml")
public class BaselineContextView extends StandardView {

    @Autowired
    private BaselineContextProvider baselineContextProvider;
    @Autowired
    private AiParametersResolver parametersResolver;
    @Autowired
    private CurrentAuthentication currentAuthentication;
    @Autowired
    private Messages messages;

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

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        refreshBaselinePreview();
    }

    @Subscribe("refreshButton")
    public void onRefreshButtonClick(final ClickEvent<Button> event) {
        refreshBaselinePreview();
    }

    void refreshBaselinePreview() {
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
            // Diagnostic preview can still render the static/default prompt without a user.
        }
        return "anonymous";
    }
}
