package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vn.agent.view.knowledge.KnowledgeBaseView;
import org.springframework.context.MessageSource;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * D-09 — citation chunk preview + Knowledge Base navigation.
 *
 * <p>Dialog width matches Audit detail dialog (UI-SPEC §Interaction Contracts). Open in KB
 * uses typed {@code UI.navigate} to {@link KnowledgeBaseView} with {@code documentId} query
 * param — no placeholder fallback (depends_on 07-06: {@link KnowledgeBaseView} is guaranteed
 * on the classpath at this point).</p>
 *
 * <p><b>Styling (WR-03 partial):</b> snippet scroll-pre visuals live in the shared
 * {@code META-INF/resources/frontend/styles/ai-agent-chat.css} under the
 * {@code ai-agent-scroll-pre} / {@code ai-agent-scroll-pre--tall} classes (30em tall
 * variant for the citation payload). Full Jmix XML view descriptor conversion is a
 * tracked follow-up.</p>
 */
@CssImport("./styles/ai-agent-chat.css")
public class CitationDialog extends Dialog {

    public CitationDialog(int index,
                          UUID documentId,
                          String documentName,
                          String snippetText,
                          MessageSource messages) {
        Locale locale = currentLocale();
        String dialogTitleTemplate = messages.getMessage(
                "chatView.citation.dialogTitle",
                null,
                "Source: {0}",
                locale);
        String resolvedName = documentName != null ? documentName
                : (documentId != null ? documentId.toString() : "");
        setHeaderTitle(MessageFormat.format(dialogTitleTemplate, resolvedName));
        setWidth("var(--lumo-size-xl)");

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(true);
        Div snippet = new Div();
        snippet.setText(snippetText == null ? "" : snippetText);
        snippet.addClassName("ai-agent-scroll-pre");
        snippet.addClassName("ai-agent-scroll-pre--tall");
        content.add(snippet);
        add(content);

        Button openInKb = new Button(
                messages.getMessage("chatView.citation.openInKb", null, "Open in Knowledge base", locale),
                e -> {
                    UI ui = UI.getCurrent();
                    if (ui != null && documentId != null) {
                        ui.navigate(
                                KnowledgeBaseView.class,
                                QueryParameters.of("documentId", documentId.toString()));
                    }
                    close();
                });
        openInKb.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancel = new Button(
                messages.getMessage("chatView.citation.close", null, "Close", locale),
                e -> close());
        getFooter().add(cancel, openInKb);
    }

    private static Locale currentLocale() {
        UI ui = UI.getCurrent();
        return ui != null ? ui.getLocale() : Locale.getDefault();
    }
}
