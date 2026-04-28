package com.vn.agent.view.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.spi.AuditKind;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardOutcome;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * UI-06 / D-21. Modal dialog rendering the full audit fields for a single
 * {@link AiAuditEvent} row. Opened by a row-click listener in
 * {@link AiAuditEventListView}.
 *
 * <p>Kind-specific fields are rendered only for the matching row kind so RETRIEVAL rows do not
 * show empty TOOL argument/result boxes and vice versa.</p>
 */
@ViewController("AiAgent_AiAuditEvent.detailDialog")
@ViewDescriptor("ai-audit-event-detail-dialog.xml")
@DialogMode(width = "var(--lumo-size-xl-plus, 960px)", draggable = true, resizable = true, closeOnEsc = true)
public class AiAuditEventDetailDialog extends StandardView {

    private static final ObjectMapper JSON = new ObjectMapper();

    @ViewComponent
    private MessageBundle messageBundle;
    @ViewComponent
    private TypedTextField<String> startedAtField;
    @ViewComponent
    private TypedTextField<String> userField;
    @ViewComponent
    private TypedTextField<String> kindField;
    @ViewComponent
    private TypedTextField<String> eventNameField;
    @ViewComponent
    private TypedTextField<String> runIdField;
    @ViewComponent
    private TypedTextField<String> parentField;
    @ViewComponent
    private TypedTextField<String> latencyMsField;
    @ViewComponent
    private TypedTextField<String> errorClassField;
    @ViewComponent
    private TypedTextField<String> denialReasonField;
    @ViewComponent
    private TypedTextField<String> topKField;
    @ViewComponent
    private TypedTextField<String> hitCountField;
    @ViewComponent
    private TypedTextField<String> topScoreField;
    @ViewComponent
    private Span outcomeBadge;
    @ViewComponent
    private JmixTextArea argumentsField;
    @ViewComponent
    private JmixTextArea resultField;
    @ViewComponent
    private JmixTextArea queryTextField;
    @ViewComponent
    private JmixTextArea retrievalHitsField;
    @ViewComponent
    private JmixTextArea filtersJsonField;

    public void setAudit(AiAuditEvent audit) {
        Objects.requireNonNull(audit, "audit must not be null");
        startedAtField.setValue(fmt(audit.getStartedAt()));
        userField.setValue(Objects.toString(audit.getUserUsername(), ""));
        kindField.setValue(Objects.toString(audit.getKind(), ""));
        eventNameField.setValue(Objects.toString(audit.getEventName(), ""));
        runIdField.setValue(uuidStr(audit.getRunId()));
        parentField.setValue(audit.getParent() == null ? "" : audit.getParent().getId().toString());
        latencyMsField.setValue(audit.getLatencyMs() == null ? "" : audit.getLatencyMs().toString());
        errorClassField.setValue(Objects.toString(audit.getErrorClass(), ""));
        denialReasonField.setValue(Objects.toString(audit.getDenialReason(), ""));
        argumentsField.setValue(Objects.toString(audit.getArgumentsJson(), ""));
        resultField.setValue(Objects.toString(audit.getResultSummary(), ""));
        queryTextField.setValue(Objects.toString(audit.getQueryText(), ""));
        topKField.setValue(audit.getTopK() == null ? "" : audit.getTopK().toString());
        hitCountField.setValue(audit.getHitCount() == null ? "" : audit.getHitCount().toString());
        topScoreField.setValue(audit.getTopScore() == null ? "" : audit.getTopScore().toString());
        retrievalHitsField.setValue(prettyJson(audit.getRetrievalHitsJson()));
        filtersJsonField.setValue(Objects.toString(audit.getFiltersJson(), ""));
        applyKindVisibility(audit.getKind());
        applyOutcomeBadge(audit.getOutcome());
    }

    @Subscribe("closeBtn")
    public void onCloseBtnClick(final ClickEvent<Button> event) {
        close(StandardOutcome.CLOSE);
    }

    private void applyOutcomeBadge(AiToolCallOutcome outcome) {
        String key = "auditList.outcome." + (outcome == null ? "success" : outcome.name().toLowerCase(Locale.ROOT));
        outcomeBadge.setText(messageBundle.getMessage(key));
        outcomeBadge.getElement().getThemeList().clear();
        outcomeBadge.getElement().getThemeList().add("badge");
        outcomeBadge.getElement().getThemeList().add(outcomeTheme(outcome));
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
            case IDEMPOTENT_REPLAY -> "success";   // Phase 11 D-08 — replayed success result
            case COMMIT_FAILED -> "error";          // Phase 11 D-08 — host save returned but finalization failed
        };
    }

    private void applyKindVisibility(String kind) {
        boolean tool = AuditKind.TOOL.equals(kind);
        boolean retrieval = AuditKind.RETRIEVAL.equals(kind);
        argumentsField.setVisible(tool);
        resultField.setVisible(tool);
        queryTextField.setVisible(retrieval);
        topKField.setVisible(retrieval);
        hitCountField.setVisible(retrieval);
        topScoreField.setVisible(retrieval);
        retrievalHitsField.setVisible(retrieval);
        filtersJsonField.setVisible(retrieval);
    }

    private static String prettyJson(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(value));
        } catch (JsonProcessingException e) {
            return value;
        }
    }

    private static String uuidStr(UUID id) {
        return id == null ? "" : id.toString();
    }

    private static String fmt(OffsetDateTime ts) {
        return ts == null ? "" : ts.toString();
    }
}
