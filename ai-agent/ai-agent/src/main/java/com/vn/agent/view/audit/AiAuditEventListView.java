package com.vn.agent.view.audit;

import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.utils.DataGridRenderers;
import com.vn.agent.utils.DataGridRenderers.ActionColumnType;
import io.jmix.core.Messages;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.grid.TreeDataGrid;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumSet;
import java.util.Locale;

/**
 * UI-06 / D-11. Admin-only tree list view over {@link AiAuditEvent} rows.
 *
 * <p>Phase 07.2 Plan 04 redesign: tree-lite — one expandable row per root CHAT
 * event; expand reveals TOOL + RETRIEVAL children via Jmix {@code @Composition}
 * fetch plan. Base query is root-only ({@code e.parent is null}); children come
 * from the hierarchical fetch plan declared in the XML descriptor. Filtering is
 * delegated to {@code <genericFilter>} + {@code <propertyFilter>} children
 * (Jmix-native, no custom JPQL composition).</p>
 *
 * <p>URL {@code /ai-agent/audit} preserved — stakeholder-facing.</p>
 */
@Route(value = "ai-agent/audit", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_AiAuditEvent.list")
@ViewDescriptor(path = "ai-audit-event-list-view.xml")
public class AiAuditEventListView extends StandardListView<AiAuditEvent> {

    @ViewComponent
    private TreeDataGrid<AiAuditEvent> auditsDataGrid;

    @Autowired
    private Messages messages;
    @Autowired
    private DialogWindows dialogWindows;
    @Autowired
    private UiComponents uiComponents;

    @Supply(to = "auditsDataGrid.actions", subject = "renderer")
    private Renderer<AiAuditEvent> auditsDataGridActionsRenderer() {
        return DataGridRenderers.buildActionsColumn(
                uiComponents,
                EnumSet.of(ActionColumnType.VIEW),
                (row, type) -> openDetailDialog(row));
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

    private void openDetailDialog(AiAuditEvent row) {
        DialogWindow<AiAuditEventDetailDialog> dialogWindow =
                dialogWindows.view(this, AiAuditEventDetailDialog.class).build();
        dialogWindow.getView().setAudit(row);
        dialogWindow.open();
    }

    /** Convenience for tests: expose the composed dialog type. */
    static Class<AiAuditEventDetailDialog> detailDialogType() {
        return AiAuditEventDetailDialog.class;
    }
}
