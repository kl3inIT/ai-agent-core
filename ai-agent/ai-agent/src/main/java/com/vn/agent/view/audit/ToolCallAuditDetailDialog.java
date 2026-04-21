package com.vn.agent.view.audit;

import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vn.agent.entity.AiToolCallAudit;
import com.vn.agent.entity.AiToolCallOutcome;
import org.springframework.context.MessageSource;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * UI-06 / D-21. Modal dialog rendering the full audit fields for a single
 * {@link AiToolCallAudit} row. Opened by a row-click listener in
 * {@link ToolCallAuditListView}.
 *
 * <p>Layout is Vaadin {@link FormLayout} with one row per audit field; large-text
 * fields (arguments JSON, result summary) render inside {@link Pre} for monospace
 * word-wrap. Outcome renders as a themed Badge per UI-SPEC.</p>
 *
 * <p>All labels come through {@link MessageSource} with msg:// keys under
 * {@code auditList.detail.*}; no hardcoded strings.</p>
 *
 * <p><b>Styling (WR-03 partial):</b> scroll-pre visuals live in the shared
 * {@code META-INF/resources/frontend/styles/ai-agent-chat.css} under
 * {@code ai-agent-scroll-pre}. Conversion of this dialog to a full Jmix XML view
 * descriptor is tracked as a follow-up (see 07-REVIEW-FIX.md iteration 2 notes).</p>
 */
@CssImport("./styles/ai-agent-chat.css")
public class ToolCallAuditDetailDialog extends Dialog {

    private static final String CSS_SCROLL_PRE = "ai-agent-scroll-pre";

    public ToolCallAuditDetailDialog(AiToolCallAudit audit, MessageSource messageSource, Locale locale) {
        Objects.requireNonNull(audit, "audit must not be null");
        setHeaderTitle(m(messageSource, locale, "auditList.detail.title", "Tool call details"));
        setWidth("var(--lumo-size-xl-plus, 960px)");
        setResizable(true);
        setDraggable(true);

        FormLayout form = new FormLayout();
        form.setResponsiveSteps(
                new FormLayout.ResponsiveStep("0", 1),
                new FormLayout.ResponsiveStep("40em", 2));

        addField(form, messageSource, locale, "auditList.detail.startedAt",
                fmt(audit.getStartedAt()));
        addField(form, messageSource, locale, "auditList.detail.user",
                Objects.toString(audit.getUserUsername(), ""));
        addField(form, messageSource, locale, "auditList.detail.tool",
                Objects.toString(audit.getToolName(), ""));
        addField(form, messageSource, locale, "auditList.detail.phase",
                Objects.toString(audit.getPhase(), ""));
        addField(form, messageSource, locale, "auditList.detail.runId",
                uuidStr(audit.getRunId()));
        addField(form, messageSource, locale, "auditList.detail.latencyMs",
                audit.getLatencyMs() == null ? "" : audit.getLatencyMs().toString());
        addField(form, messageSource, locale, "auditList.detail.errorClass",
                Objects.toString(audit.getErrorClass(), ""));
        addField(form, messageSource, locale, "auditList.detail.denialReason",
                Objects.toString(audit.getDenialReason(), ""));

        // Outcome badge on its own row.
        FormLayout.FormItem outcomeItem = form.addFormItem(
                renderOutcomeBadge(audit, messageSource, locale),
                m(messageSource, locale, "auditList.detail.outcome", "Outcome"));
        form.setColspan(outcomeItem, 2);

        // Arguments JSON — Pre for monospace. Styling from CSS class.
        Pre args = new Pre(Objects.toString(audit.getArgumentsJson(), ""));
        args.addClassName(CSS_SCROLL_PRE);
        FormLayout.FormItem argsItem = form.addFormItem(args,
                m(messageSource, locale, "auditList.detail.argsJson", "Arguments"));
        form.setColspan(argsItem, 2);

        // Result summary.
        Pre result = new Pre(Objects.toString(audit.getResultSummary(), ""));
        result.addClassName(CSS_SCROLL_PRE);
        FormLayout.FormItem resultItem = form.addFormItem(result,
                m(messageSource, locale, "auditList.detail.resultSummary", "Result"));
        form.setColspan(resultItem, 2);

        add(form);
    }

    private static void addField(FormLayout form, MessageSource ms, Locale locale,
                                 String key, String value) {
        Span span = new Span(value == null ? "" : value);
        form.addFormItem(span, m(ms, locale, key, key));
    }

    private static Span renderOutcomeBadge(AiToolCallAudit audit, MessageSource ms, Locale locale) {
        AiToolCallOutcome outcome = audit.getOutcome();
        String key = "auditList.outcome." + (outcome == null ? "success" : outcome.name().toLowerCase(Locale.ROOT));
        String label = ms.getMessage(key, null, outcome == null ? "" : outcome.name(), locale);
        Span badge = new Span(label);
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add(outcomeTheme(outcome));
        return badge;
    }

    static String outcomeTheme(AiToolCallOutcome outcome) {
        if (outcome == null) {
            return "contrast";
        }
        return switch (outcome) {
            case SUCCESS -> "success";
            case ERROR -> "error";
            case BLOCKED -> "warning";
            case FLAGGED -> "contrast";
        };
    }

    private static String m(MessageSource ms, Locale locale, String key, String fallback) {
        return ms.getMessage(key, null, fallback, locale);
    }

    private static String uuidStr(UUID id) {
        return id == null ? "" : id.toString();
    }

    private static String fmt(OffsetDateTime ts) {
        return ts == null ? "" : ts.toString();
    }
}
