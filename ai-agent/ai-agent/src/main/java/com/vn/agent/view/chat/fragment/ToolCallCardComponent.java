package com.vn.agent.view.chat.fragment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vn.agent.entity.AiToolCallOutcome;
import org.springframework.context.MessageSource;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * D-08, D-25 — shared between ChatView live-streaming (Plan 07-03) and ConversationDetailView
 * replay (Plan 07-04). Collapsed-badge row → expand-on-click via Vaadin {@link Details}.
 *
 * <p>Summary row: tool icon + tool name + short args summary.
 * Expanded body: pretty-printed args JSON + result summary + outcome badge.</p>
 */
public class ToolCallCardComponent extends Composite<Details> {

    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper();
    private static final int SHORT_SUMMARY_MAX = 40;

    private final UUID toolCallId;
    private final String toolName;
    private final MessageSource messages;
    private final Pre resultPre;
    private final Span outcomeBadge;

    public ToolCallCardComponent(UUID toolCallId, String toolName, String argsJson, MessageSource messages) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.messages = messages;

        Details details = getContent();

        // ---- Summary row: 🔧 toolName (shortSummary) -------------------------------
        HorizontalLayout summary = new HorizontalLayout();
        summary.setSpacing(true);
        summary.setPadding(false);
        summary.add(new Icon(VaadinIcon.TOOLS));
        String shortSummary = shortSummaryOf(argsJson);
        String collapsed = MessageFormat.format(msg("chatView.toolCard.collapsed"), toolName, shortSummary);
        Span titleSpan = new Span(collapsed);
        summary.add(titleSpan);
        details.setSummary(summary);

        // ---- Body ------------------------------------------------------------------
        VerticalLayout body = new VerticalLayout();
        body.setSpacing(false);
        body.setPadding(false);

        Span argsHeading = new Span(msg("chatView.toolCard.argsHeading"));
        argsHeading.getStyle().setFontWeight("600");
        Pre argsPre = new Pre(prettyJson(argsJson));
        argsPre.getStyle().set("white-space", "pre-wrap");
        argsPre.getStyle().setMaxHeight("20em");
        argsPre.getStyle().set("overflow", "auto");

        Span resultHeading = new Span(msg("chatView.toolCard.resultHeading"));
        resultHeading.getStyle().setFontWeight("600");
        this.resultPre = new Pre("");
        resultPre.getStyle().set("white-space", "pre-wrap");
        resultPre.getStyle().setMaxHeight("20em");
        resultPre.getStyle().set("overflow", "auto");

        this.outcomeBadge = new Span();
        outcomeBadge.getElement().getThemeList().add("badge");

        body.add(argsHeading, argsPre, resultHeading, resultPre, outcomeBadge);
        details.add(body);
    }

    public UUID getToolCallId() {
        return toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    /** Populate result body + outcome badge after the tool completes. */
    public void setResult(String resultSummary, AiToolCallOutcome outcome) {
        resultPre.setText(resultSummary == null ? "" : resultSummary);
        outcomeBadge.setText(outcomeLabel(outcome));
        outcomeBadge.getElement().getThemeList().clear();
        outcomeBadge.getElement().getThemeList().add("badge");
        outcomeBadge.getElement().getThemeList().add(themeFor(outcome));
    }

    private String outcomeLabel(AiToolCallOutcome outcome) {
        if (outcome == null) {
            return "";
        }
        String key = "com.vn.agent.entity/AiToolCallOutcome." + outcome.name();
        return messages.getMessage(key, null, outcome.name(), currentLocale());
    }

    /**
     * UI-SPEC §Color outcome theme map against the real {@link AiToolCallOutcome} enum
     * (SUCCESS / BLOCKED / ERROR / FLAGGED — matches Plan 07-06 KB view decision).
     */
    private static String themeFor(AiToolCallOutcome outcome) {
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

    private static String shortSummaryOf(String argsJson) {
        if (argsJson == null) {
            return "";
        }
        String single = argsJson.replaceAll("\\s+", " ").trim();
        if (single.length() <= SHORT_SUMMARY_MAX) {
            return single;
        }
        return single.substring(0, SHORT_SUMMARY_MAX - 1) + "…";
    }

    private static String prettyJson(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }
        try {
            JsonNode node = PRETTY_MAPPER.readTree(argsJson);
            return node.toPrettyString();
        } catch (Exception ex) {
            return argsJson;
        }
    }

    private String msg(String key) {
        return messages.getMessage(key, null, key, currentLocale());
    }

    private static Locale currentLocale() {
        UI ui = UI.getCurrent();
        return ui != null ? ui.getLocale() : Locale.getDefault();
    }
}
