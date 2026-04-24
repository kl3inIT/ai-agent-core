package com.vn.agent.view.conversation;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.utils.DataGridRenderers;
import com.vn.agent.utils.DataGridRenderers.ActionColumnType;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * UI-03 list view. D-24 role-aware scoping via {@link #isAdmin} flag set at {@code onInit}.
 *
 * <p>Non-admin users see only conversations where {@code createdBy = currentUser}; admins
 * see every conversation plus an additional "User" column + user filter field.</p>
 *
 * <p>Messages count column is computed once per page via a single grouped JPQL query on
 * {@code PostLoadEvent} — no per-row N+1. Row-level security (Jmix
 * {@code AiAgentUserRowLevelRole}) remains the source of truth for ownership; the
 * {@link #isAdmin} flag only controls UI affordances (extra column, extra filter).</p>
 */
@Route(value = "ai-agent/conversations", layout = DefaultMainViewParent.class)
@ViewController("AiAgent_Conversation.list")
@ViewDescriptor("conversation-list-view.xml")
public class ConversationListView extends StandardListView<AiConversation> {

    private static final Logger log = LoggerFactory.getLogger(ConversationListView.class);

    @ViewComponent
    private DataGrid<AiConversation> conversationsDataGrid;
    @ViewComponent
    private CollectionLoader<AiConversation> conversationsDl;
    @ViewComponent
    private TypedTextField<String> titleFilter;
    @ViewComponent
    private TypedTextField<String> userFilter;

    @Autowired
    private AccessManager accessManager;
    @Autowired
    private ViewNavigators viewNavigators;
    @Autowired
    private DataManager dataManager;
    @Autowired
    private UiComponents uiComponents;

    private boolean isAdmin;

    /** Per-page message counts keyed by conversation id. Refreshed on every loader reload. */
    private final Map<UUID, Long> messageCounts = new HashMap<>();

    @Subscribe
    public void onInit(final InitEvent event) {
        this.isAdmin = currentUserIsAdmin();

        userFilter.setVisible(isAdmin);
        conversationsDataGrid.getColumnByKey("createdBy").setVisible(isAdmin);

        conversationsDataGrid.addItemDoubleClickListener(e -> {
            if (e.getItem() != null) {
                onRowAction(e.getItem(), ActionColumnType.VIEW);
            }
        });

        rebuildQuery();
    }

    /** Batch-load message counts for the current page in a single query. */
    @Subscribe(id = "conversationsDl", target = Target.DATA_LOADER)
    public void onConversationsDlPostLoad(final CollectionLoader.PostLoadEvent<AiConversation> event) {
        messageCounts.clear();
        List<AiConversation> items = event.getLoadedEntities();
        if (items.isEmpty()) {
            return;
        }
        List<UUID> ids = items.stream().map(AiConversation::getId).toList();
        try {
            List<KeyValueEntity> rows = dataManager.loadValues(
                            "select m.conversation.id, count(m) from ai_AiMessage m "
                                    + "where m.conversation.id in :ids group by m.conversation.id")
                    .properties("cid", "cnt")
                    .parameter("ids", ids)
                    .list();
            for (KeyValueEntity row : rows) {
                messageCounts.put(row.getValue("cid"), row.getValue("cnt"));
            }
        } catch (Exception ex) {
            log.warn("messageCount batch lookup failed for {} conversations", ids.size(), ex);
        }
    }

    @Supply(to = "conversationsDataGrid.messageCount", subject = "renderer")
    private Renderer<AiConversation> conversationsDataGridMessageCountRenderer() {
        return new TextRenderer<>(row ->
                String.valueOf(messageCounts.getOrDefault(row.getId(), 0L)));
    }

    @Supply(to = "conversationsDataGrid.actions", subject = "renderer")
    private Renderer<AiConversation> conversationsDataGridActionsRenderer() {
        return DataGridRenderers.buildActionsColumn(
                uiComponents,
                EnumSet.of(ActionColumnType.VIEW),
                this::onRowAction);
    }

    private void onRowAction(AiConversation row, ActionColumnType type) {
        switch (type) {
            case VIEW -> viewNavigators.detailView(this, AiConversation.class)
                    .withViewClass(ConversationDetailView.class)
                    .editEntity(row)
                    .navigate();
            default -> { }
        }
    }

    @Subscribe("titleFilter")
    public void onTitleFilterChange(
            final AbstractField.ComponentValueChangeEvent<TypedTextField<String>, String> event) {
        rebuildQuery();
    }

    @Subscribe("userFilter")
    public void onUserFilterChange(
            final AbstractField.ComponentValueChangeEvent<TypedTextField<String>, String> event) {
        rebuildQuery();
    }

    /**
     * Admin probe helper — package-private so 07-07 {@code ConversationListRoleFilterTest}
     * can stub via reflection or a Spring slice. Uses Jmix {@link AccessManager} +
     * {@link UiShowViewContext} to probe an admin-only view id.
     */
    boolean currentUserIsAdmin() {
        try {
            UiShowViewContext ctx = new UiShowViewContext("AiAgent_ToolCallAudit.list");
            accessManager.applyRegisteredConstraints(ctx);
            return ctx.isPermitted();
        } catch (Exception ex) {
            log.warn("Admin probe failed, defaulting to non-admin: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Composes the JPQL query from filter-bar state. Row-level security still narrows
     * to the current user for non-admins; admins see everything. The {@code userFilter}
     * is an admin-only substring match on {@code createdBy}.
     */
    private void rebuildQuery() {
        String title = normalized(titleFilter.getValue());
        String userSub = isAdmin ? normalized(userFilter.getValue()) : null;

        StringBuilder jpql = new StringBuilder("select e from ai_AiConversation e");
        List<String> where = new ArrayList<>(2);
        if (title != null) {
            where.add("lower(e.title) like :title");
        }
        if (userSub != null) {
            where.add("lower(e.createdBy) like :userSub");
        }
        if (!where.isEmpty()) {
            jpql.append(" where ").append(String.join(" and ", where));
        }
        jpql.append(" order by e.createdDate desc");

        conversationsDl.setQuery(jpql.toString());
        applyLikeParam("title", title);
        applyLikeParam("userSub", userSub);
        conversationsDl.load();
    }

    private void applyLikeParam(String name, String normalized) {
        if (normalized == null) {
            conversationsDl.removeParameter(name);
        } else {
            conversationsDl.setParameter(name, "%" + normalized + "%");
        }
    }

    private static String normalized(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.toLowerCase(Locale.ROOT);
    }

}
