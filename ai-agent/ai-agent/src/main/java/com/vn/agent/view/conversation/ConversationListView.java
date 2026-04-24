package com.vn.agent.view.conversation;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiConversation;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
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

import java.util.Locale;
import java.util.UUID;

/**
 * UI-03 list view. D-24 role-aware scoping via {@link #isAdmin} flag set at {@code onInit}.
 *
 * <p>Non-admin users see only conversations where {@code createdBy = currentUser}; admins
 * see every conversation plus an additional "User" column + user filter field.</p>
 *
 * <p>Messages count column is computed per-row via {@link DataManager} count query —
 * path (b) fallback from the plan (per-row N queries accepted for v1; typical
 * non-admin result sets are small because row-level security already narrows to
 * the current user). Admin pages with many conversations may warrant moving to
 * a {@code KeyValueCollectionLoader} JPQL projection in a future optimization
 * pass. See 07-04 SUMMARY for the trade-off.</p>
 *
 * <p>Row-level security (Jmix {@code AiAgentUserRowLevelRole}) remains the source of
 * truth for ownership. The {@link #isAdmin} flag only decides which UI affordances
 * show up (extra column, extra filter). A non-admin who spoofs {@code isAdmin=true}
 * client-side still gets only their rows because DataManager applies the row-level
 * predicate server-side (T-07-12).</p>
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

    private boolean isAdmin;

    @Subscribe
    public void onInit(final InitEvent event) {
        this.isAdmin = currentUserIsAdmin();

        // Admin-visible affordances.
        userFilter.setVisible(isAdmin);
        conversationsDataGrid.getColumnByKey("createdBy").setVisible(isAdmin);

        // Filter bar — debounce user input via LAZY, reload on every change. The
        // reload wiring itself is declarative via @Subscribe("<fieldId>") handlers below.
        titleFilter.setValueChangeMode(ValueChangeMode.LAZY);
        userFilter.setValueChangeMode(ValueChangeMode.LAZY);

        // Double-click a row → open detail view. Retained as a raw listener because
        // ItemDoubleClickEvent carries per-row context (the item) that the handler
        // needs synchronously for navigation — this is the kind of dynamic grid
        // wiring the review carves out as acceptable.
        conversationsDataGrid.addItemDoubleClickListener(e -> {
            AiConversation row = e.getItem();
            if (row != null) {
                viewNavigators.detailView(this, AiConversation.class)
                        .withViewClass(ConversationDetailView.class)
                        .editEntity(row)
                        .navigate();
            }
        });

        rebuildQuery();
    }

    /** Messages count column — per-row DataManager count. Small result sets per user. */
    @Supply(to = "conversationsDataGrid.messageCount", subject = "renderer")
    private Renderer<AiConversation> conversationsDataGridMessageCountRenderer() {
        return new TextRenderer<>(row -> String.valueOf(countMessages(row.getId())));
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
     * Admin probe helper — extracted so the 07-07 {@code ConversationListRoleFilterTest}
     * can stub via reflection or a Spring slice. Package-private to permit test overrides.
     *
     * <p>Uses Jmix {@link AccessManager} + {@link UiShowViewContext} to probe an
     * admin-only view id (AI Agent ToolCallAudit list). This is the same plumbing
     * Vaadin navigation walks at runtime, so role drift or renamed roles cannot
     * produce a false positive — unlike string-matching authority codes.</p>
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
        StringBuilder jpql = new StringBuilder("select e from ai_AiConversation e");
        boolean hasWhere = false;

        String title = titleFilter.getValue();
        if (title != null && !title.isBlank()) {
            jpql.append(" where lower(e.title) like :title");
            hasWhere = true;
        }
        String userSub = isAdmin ? userFilter.getValue() : null;
        if (userSub != null && !userSub.isBlank()) {
            jpql.append(hasWhere ? " and" : " where");
            jpql.append(" lower(e.createdBy) like :userSub");
        }
        jpql.append(" order by e.createdDate desc");

        conversationsDl.setQuery(jpql.toString());
        if (title != null && !title.isBlank()) {
            conversationsDl.setParameter("title", "%" + title.toLowerCase(Locale.ROOT) + "%");
        } else {
            conversationsDl.removeParameter("title");
        }
        if (userSub != null && !userSub.isBlank()) {
            conversationsDl.setParameter("userSub", "%" + userSub.toLowerCase(Locale.ROOT) + "%");
        } else {
            conversationsDl.removeParameter("userSub");
        }
        conversationsDl.load();
    }

    /** Per-row message count. Returns 0 on any failure so the grid renders cleanly. */
    private long countMessages(UUID conversationId) {
        if (conversationId == null) {
            return 0L;
        }
        try {
            Long n = dataManager.loadValue(
                            "select count(m) from ai_AiMessage m where m.conversation.id = :cid",
                            Long.class)
                    .parameter("cid", conversationId)
                    .one();
            return n == null ? 0L : n;
        } catch (Exception ex) {
            log.debug("messageCount lookup failed for {}: {}", conversationId, ex.getMessage());
            return 0L;
        }
    }
}
