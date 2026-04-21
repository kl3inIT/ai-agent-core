package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.html.Div;
import com.vn.agent.view.chat.MarkdownRenderer;

/**
 * D-06 role-styled bubble. Server-side markdown rendering via {@link MarkdownRenderer}
 * with a streaming buffer (append-and-re-render) so token chunks accumulate into a
 * single sanitized HTML blob. Reused by {@code ChatPanelFragment} on the live path AND
 * by {@code ConversationDetailView} (Plan 07-04) for read-only replay via {@link #setMarkdown(String)}.
 *
 * <p>XSS mitigation (T-07-08): all markdown flows through {@code MarkdownRenderer.toSafeHtml},
 * which runs Flexmark → OWASP-HTML-Sanitizer. No {@code innerHTML} assignments without that pipe.</p>
 */
public class MessageBubbleComponent extends Composite<Div> {

    public enum Role {USER, ASSISTANT, SYSTEM}

    private final Role role;
    private final MarkdownRenderer markdownRenderer;
    private final StringBuilder buffer = new StringBuilder();
    private final Html htmlContent;
    private boolean stopped;

    public MessageBubbleComponent(Role role, MarkdownRenderer markdownRenderer) {
        this.role = role;
        this.markdownRenderer = markdownRenderer;
        Div root = getContent();
        root.getStyle().setPadding("var(--lumo-space-m)");
        root.getStyle().setBorderRadius("var(--lumo-border-radius-m)");
        root.getStyle().setBackground("var(--lumo-contrast-5pct)");
        root.getStyle().setMaxWidth("80%");
        switch (role) {
            case ASSISTANT -> root.getStyle().setBorderLeft("2px solid var(--lumo-primary-color)");
            case USER -> {
                root.getStyle().setMarginLeft("auto");
                root.getStyle().setBackground("var(--lumo-primary-color-10pct)");
            }
            case SYSTEM -> root.getStyle().setBorderLeft("2px solid var(--lumo-contrast-30pct)");
        }
        this.htmlContent = new Html("<div></div>");
        root.add(htmlContent);
    }

    public Role getRole() {
        return role;
    }

    /** Append a streaming markdown chunk and re-render the full bubble content. */
    public void appendMarkdown(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        buffer.append(chunk);
        renderBuffer();
    }

    /** Replace the buffer wholesale (used for conversation-replay of completed messages). */
    public void setMarkdown(String full) {
        buffer.setLength(0);
        if (full != null) {
            buffer.append(full);
        }
        renderBuffer();
    }

    /** Append a muted "— stopped" suffix. Idempotent. */
    public void markStopped() {
        if (stopped) {
            return;
        }
        stopped = true;
        renderBuffer();
    }

    private void renderBuffer() {
        String safe = markdownRenderer.toSafeHtml(buffer.toString());
        String suffix = stopped ? "<span class=\"ai-agent-stopped\">&nbsp;— stopped</span>" : "";
        // Wrap in a single div so Html always has exactly one root (Vaadin Html requirement).
        htmlContent.setHtmlContent("<div>" + safe + suffix + "</div>");
    }
}
