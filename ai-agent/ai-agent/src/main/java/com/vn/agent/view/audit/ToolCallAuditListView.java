package com.vn.agent.view.audit;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiToolCallAudit;
import com.vn.agent.entity.AiToolCallOutcome;
import io.jmix.core.DataManager;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.component.datetimepicker.TypedDateTimePicker;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * UI-06. Admin-only list view over {@link AiToolCallAudit} rows.
 *
 * <p>Wires:
 * <ul>
 *     <li>D-19 — typed filter bar (user / tool / outcome / date range) with declarative
 *         {@code @Subscribe("<fieldId>")} valueChange handlers that rebuild the loader's
 *         JPQL and reload. Jmix {@code <genericFilter>} is also declared in XML so admins
 *         have the full condition-builder for ad-hoc queries.</li>
 *     <li>D-20 — grid actions {@code grdexp_excelExport} / {@code grdexp_jsonExport}
 *         wired via XML {@code type=} attribute. Toolbar buttons bind to these actions
 *         via {@code action="auditsDataGrid.excelExport"}. Both exports honour the
 *         active filter bar automatically because gridexport streams through the
 *         DataProvider (RESEARCH Pitfall §gridexport).</li>
 *     <li>D-21 — row-click opens a {@link ToolCallAuditDetailDialog} modal. Retained
 *         as a raw {@code addItemClickListener} because ItemClickEvent carries the
 *         per-row entity needed for the dialog (dynamic grid-row wiring carve-out).</li>
 *     <li>D-22 — Outcome column renders as a themed Vaadin Badge.</li>
 * </ul>
 * </p>
 *
 * <p>Admin-only per the {@code AiAgent_ToolCallAudit.list} entry in
 * {@code AiAgentAdminRole.adminViews()} wired in 07-01.</p>
 */
@Route(value = "ai-agent/audit", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_ToolCallAudit.list")
@ViewDescriptor(path = "tool-call-audit-list-view.xml")
public class ToolCallAuditListView extends StandardListView<AiToolCallAudit> {

    private static final Logger log = LoggerFactory.getLogger(ToolCallAuditListView.class);

    private static final String BASE_QUERY =
            "select e from ai_AiToolCallAudit e";
    private static final String BASE_ORDER =
            " order by e.startedAt desc";

    @ViewComponent
    private DataGrid<AiToolCallAudit> auditsDataGrid;
    @ViewComponent
    private CollectionLoader<AiToolCallAudit> auditsDl;
    @ViewComponent
    private TypedTextField<String> userFilter;
    @ViewComponent
    private JmixComboBox<String> toolFilter;
    @ViewComponent
    private JmixComboBox<AiToolCallOutcome> outcomeFilter;
    @ViewComponent
    private TypedDateTimePicker<LocalDateTime> dateFromFilter;
    @ViewComponent
    private TypedDateTimePicker<LocalDateTime> dateToFilter;

    @Autowired
    private DataManager dataManager;
    @Autowired
    private MessageSource messageSource;
    @Autowired
    private DialogWindows dialogWindows;

    @Subscribe
    public void onInit(final InitEvent event) {
        // Populate filter combos.
        toolFilter.setItems(loadDistinctToolNames());
        outcomeFilter.setItems(Arrays.asList(AiToolCallOutcome.values()));
        outcomeFilter.setItemLabelGenerator(o -> messageSource.getMessage(
                "auditList.outcome." + o.name().toLowerCase(Locale.ROOT),
                null, o.name(), UI.getCurrent().getLocale()));

        userFilter.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);

        // D-22 Outcome badge renderer.
        auditsDataGrid.getColumnByKey("outcome")
                .setRenderer(new ComponentRenderer<>(this::renderOutcomeBadge));

        // D-21 Row-click opens detail dialog. Retained as a raw listener because the
        // ItemClickEvent carries the per-row entity the dialog needs.
        auditsDataGrid.addItemClickListener(e -> {
            AiToolCallAudit row = e.getItem();
            if (row != null) {
                DialogWindow<ToolCallAuditDetailDialog> dialogWindow =
                        dialogWindows.view(this, ToolCallAuditDetailDialog.class).build();
                dialogWindow.getView().setAudit(row);
                dialogWindow.open();
            }
        });
    }

    @Subscribe("userFilter")
    public void onUserFilterChange(
            final AbstractField.ComponentValueChangeEvent<TypedTextField<String>, String> event) {
        rebuildQuery();
    }

    @Subscribe("toolFilter")
    public void onToolFilterChange(
            final AbstractField.ComponentValueChangeEvent<JmixComboBox<String>, String> event) {
        rebuildQuery();
    }

    @Subscribe("outcomeFilter")
    public void onOutcomeFilterChange(
            final AbstractField.ComponentValueChangeEvent<JmixComboBox<AiToolCallOutcome>, AiToolCallOutcome> event) {
        rebuildQuery();
    }

    @Subscribe("dateFromFilter")
    public void onDateFromFilterChange(
            final AbstractField.ComponentValueChangeEvent<TypedDateTimePicker<LocalDateTime>, LocalDateTime> event) {
        rebuildQuery();
    }

    @Subscribe("dateToFilter")
    public void onDateToFilterChange(
            final AbstractField.ComponentValueChangeEvent<TypedDateTimePicker<LocalDateTime>, LocalDateTime> event) {
        rebuildQuery();
    }

    private Span renderOutcomeBadge(AiToolCallAudit row) {
        AiToolCallOutcome outcome = row.getOutcome();
        Locale locale = UI.getCurrent().getLocale();
        String key = "auditList.outcome." + (outcome == null ? "success" : outcome.name().toLowerCase(Locale.ROOT));
        String label = messageSource.getMessage(key, null, outcome == null ? "" : outcome.name(), locale);
        Span badge = new Span(label);
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add(ToolCallAuditDetailDialog.outcomeTheme(outcome));
        return badge;
    }

    private List<String> loadDistinctToolNames() {
        try {
            return dataManager.loadValue(
                            "select distinct e.toolName from ai_AiToolCallAudit e order by e.toolName",
                            String.class)
                    .list();
        } catch (Exception ex) {
            log.debug("Unable to pre-load tool names for filter combo: {}", ex.getMessage());
            return List.of();
        }
    }

    /** Composes a dynamic JPQL query from the filter bar state and reloads. */
    private void rebuildQuery() {
        StringBuilder jpql = new StringBuilder(BASE_QUERY);
        List<String> where = new ArrayList<>();

        String user = userFilter.getValue();
        if (user != null && !user.isBlank()) {
            where.add("lower(e.userUsername) like :user");
        }
        String tool = toolFilter.getValue();
        if (tool != null && !tool.isBlank()) {
            where.add("e.toolName = :tool");
        }
        AiToolCallOutcome outcome = outcomeFilter.getValue();
        if (outcome != null) {
            where.add("e.outcome = :outcome");
        }
        LocalDateTime from = dateFromFilter.getValue();
        if (from != null) {
            where.add("e.startedAt >= :fromDate");
        }
        LocalDateTime to = dateToFilter.getValue();
        if (to != null) {
            where.add("e.startedAt <= :toDate");
        }

        if (!where.isEmpty()) {
            jpql.append(" where ").append(String.join(" and ", where));
        }
        jpql.append(BASE_ORDER);

        auditsDl.setQuery(jpql.toString());
        if (user != null && !user.isBlank()) {
            auditsDl.setParameter("user", "%" + user.toLowerCase(Locale.ROOT) + "%");
        } else {
            auditsDl.removeParameter("user");
        }
        if (tool != null && !tool.isBlank()) {
            auditsDl.setParameter("tool", tool);
        } else {
            auditsDl.removeParameter("tool");
        }
        if (outcome != null) {
            auditsDl.setParameter("outcome", outcome.getId());
        } else {
            auditsDl.removeParameter("outcome");
        }
        if (from != null) {
            auditsDl.setParameter("fromDate", from.atOffset(ZoneOffset.UTC));
        } else {
            auditsDl.removeParameter("fromDate");
        }
        if (to != null) {
            auditsDl.setParameter("toDate", to.atOffset(ZoneOffset.UTC));
        } else {
            auditsDl.removeParameter("toDate");
        }
        auditsDl.load();
    }

    /** Convenience for tests: expose the composed dialog type. */
    static Class<ToolCallAuditDetailDialog> detailDialogType() {
        return ToolCallAuditDetailDialog.class;
    }
}
