package com.vn.agent.view.diagnostics;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.Route;
import com.vn.agent.orchestration.BaselineContextProvider;
import io.jmix.core.Messages;
import io.jmix.flowui.component.codeeditor.CodeEditor;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;

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
    private Messages messages;

    @ViewComponent
    private TypedTextField<String> generatedAtField;
    @ViewComponent
    private CodeEditor baselinePreviewField;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        refreshBaselinePreview();
    }

    @Subscribe("refreshButton")
    public void onRefreshButtonClick(final ClickEvent<Button> event) {
        refreshBaselinePreview();
    }

    void refreshBaselinePreview() {
        String baselineText = baselineContextProvider.renderAsText(null);
        baselinePreviewField.setValue(baselineText == null || baselineText.isBlank()
                ? messages.getMessage("baselineContext.empty")
                : baselineText);
        generatedAtField.setValue(OffsetDateTime.now().toString());
    }
}
