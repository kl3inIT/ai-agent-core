package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.QueryParameters;
import com.vn.agent.view.knowledge.KnowledgeBaseView;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardOutcome;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * D-09 — citation chunk preview + Knowledge Base navigation.
 *
 * <p>Dialog width matches Audit detail dialog (UI-SPEC §Interaction Contracts). Open in KB
 * uses {@link ViewNavigators} with a {@code documentId} query parameter so the
 * Knowledge Base list view keeps all navigation inside Jmix Flow UI services.</p>
 */
@ViewController("AiAgent_Citation.dialog")
@ViewDescriptor("citation-dialog.xml")
@DialogMode(width = "var(--lumo-size-xl)", draggable = true, closeOnEsc = true)
public class CitationDialog extends StandardView {

    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private JmixTextArea snippetField;
    @ViewComponent
    private JmixButton openInKbBtn;

    @Autowired
    private ViewNavigators viewNavigators;

    private UUID documentId;

    @Subscribe
    public void onInit(final InitEvent event) {
        openInKbBtn.setEnabled(false);
    }

    public void setCitation(UUID documentId, String documentName, String snippetText) {
        this.documentId = documentId;
        String resolvedName = documentName != null ? documentName
                : (documentId != null ? documentId.toString() : "");
        setPageTitle(messageBundle.formatMessage("chatView.citation.dialogTitle", resolvedName));
        snippetField.setValue(snippetText == null ? "" : snippetText);
        openInKbBtn.setEnabled(documentId != null);
    }

    @Subscribe("closeBtn")
    public void onCloseBtnClick(final ClickEvent<Button> event) {
        close(StandardOutcome.CLOSE);
    }

    @Subscribe("openInKbBtn")
    public void onOpenInKbBtnClick(final ClickEvent<Button> event) {
        if (documentId == null) {
            return;
        }
        viewNavigators.view(this, KnowledgeBaseView.class)
                .withQueryParameters(QueryParameters.simple(Map.of("documentId", documentId.toString())))
                .navigate();
        close(StandardOutcome.CLOSE);
    }
}
