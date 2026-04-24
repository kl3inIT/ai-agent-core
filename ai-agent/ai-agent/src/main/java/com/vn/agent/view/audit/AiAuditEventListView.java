package com.vn.agent.view.audit;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.utils.DataGridRenderers;
import com.vn.agent.utils.DataGridRenderers.ActionColumnType;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.component.datetimepicker.TypedDateTimePicker;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * UI-06 / D-11. Admin-only tree list view over {@link AiAuditEvent} rows.
 *
 * <p>Phase 07.2 Plan 04 full redesign: renders one expandable row per root CHAT
 * event; expand reveals TOOL + RETRIEVAL children via Jmix {@code @Composition}
 * fetch plan (no client-side grouping). Base query is root-only
 * ({@code e.parent is null}); children come from the hierarchical fetch plan
 * declared in the XML descriptor.</p>
 *
 * <p>URL {@code /ai-agent/audit} preserved — stakeholder-facing.</p>
 */
@Route(value = "ai-agent/audit", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_AiAuditEvent.list")
@ViewDescriptor(path = "ai-audit-event-list-view.xml")
public class AiAuditEventListView extends StandardListView<AiAuditEvent> {

    private static final Logger log = LoggerFactory.getLogger(AiAuditEventListView.class);

    private static final String BASE_QUERY =
            "select e from ai_AiAuditEvent e where e.parent is null";
    private static final String BASE_ORDER =
            " order by e.startedAt desc";

    @ViewComponent
    private TreeDataGrid<AiAuditEvent> auditsDataGrid;
    @ViewComponent
    private CollectionLoader<AiAuditEvent> auditsDl;
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
    private Messages messages;
    @Autowired
    private DialogWindows dialogWindows;
    @Autowired
    private UiComponents uiComponents;

    @Subscribe
    public void onInit(final InitEvent event) {
        // Populate filter combos.
        toolFilter.setItems(loadDistinctEventNames());
        outcomeFilter.setItems(Arrays.asList(AiToolCallOutcome.values()));
        outcomeFilter.setItemLabelGenerator(o -> messages.getMessage(
                "auditList.outcome." + o.name().toLowerCase(Locale.ROOT)));

        userFilter.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.LAZY);

        // D-21 Row-click opens detail dialog.
        auditsDataGrid.addItemClickListener(e -> {
            if (e.getItem() != null) {
                openDetailDialog(e.getItem());
            }
        });
    }

    @Supply(to = "auditsDataGrid.actions", subject = "renderer")
    private Renderer<AiAuditEvent> auditsDataGridActionsRenderer() {
        return DataGridRenderers.buildActionsColumn(
                uiComponents,
                EnumSet.of(ActionColumnType.VIEW),
                (row, type) -> openDetailDialog(row));
    }

    private void openDetailDialog(AiAuditEvent row) {
        DialogWindow<AiAuditEventDetailDialog> dialogWindow =
                dialogWindows.view(this, AiAuditEventDetailDialog.class).build();
        dialogWindow.getView().setAudit(row);
        dialogWindow.open();
    }

    /** D-22 Outcome badge renderer. */
    @Supply(to = "auditsDataGrid.outcome", subject = "renderer")
    private Renderer<AiAuditEvent> auditsDataGridOutcomeRenderer() {
        return DataGridRenderers.buildBadgeColumn(
                uiComponents,
                row -> messages.getMessage("auditList.outcome."
                        + (row.getOutcome() == null ? "success"
                                : row.getOutcome().name().toLowerCase(Locale.ROOT))),
                row -> AiAuditEventDetailDialog.outcomeTheme(row.getOutcome()));
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

    private List<String> loadDistinctEventNames() {
        try {
            return dataManager.loadValue(
                            "select distinct e.eventName from ai_AiAuditEvent e where e.eventName is not null order by e.eventName",
                            String.class)
                    .list();
        } catch (Exception ex) {
            log.debug("Unable to pre-load event names for filter combo: {}", ex.getMessage());
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
            where.add("(e.eventName = :tool or exists (select c from ai_AiAuditEvent c "
                    + "where c.parent = e and c.eventName = :tool))");
        }
        AiToolCallOutcome outcome = outcomeFilter.getValue();
        if (outcome != null) {
            where.add("(e.outcome = :outcome or exists (select c from ai_AiAuditEvent c "
                    + "where c.parent = e and c.outcome = :outcome))");
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
            jpql.append(" and ").append(String.join(" and ", where));
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
    static Class<AiAuditEventDetailDialog> detailDialogType() {
        return AiAuditEventDetailDialog.class;
    }
}
