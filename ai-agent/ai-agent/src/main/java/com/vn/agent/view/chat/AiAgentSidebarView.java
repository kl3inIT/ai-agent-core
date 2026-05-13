package com.vn.agent.view.chat;

import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

/** SIDEBAR surface host view (Phase 15 Plan 03 — D-01..D-04, REVIEWS point #1).
 *  A lean Jmix-owned {@link StandardView} whose only content is the existing
 *  {@link ChatPanelFragment} (no new fragment subclass — same implementation, same
 *  singleton {@code ChatService}, same chat memory). Mirrors {@code ChatDialogView}:
 *  it is the {@code FragmentOwner} for the sidebar's fragment so {@code hostView()}
 *  works, and it syncs {@link ChatPanelFragment#setConversationId} from
 *  {@link AiChatSessionState} on show so the sidebar opens onto the current
 *  conversation (cross-surface continuity). {@code ChatSurfaceMounter} creates this
 *  via {@code views.create(AiAgentSidebarView.class)} and attaches its element into
 *  the fixed-position panel {@code Div} on the UI element. */
@ViewController("AiAgent_Sidebar")
@ViewDescriptor("ai-agent-sidebar-view.xml")
public class AiAgentSidebarView extends StandardView {

    @ViewComponent
    private ChatPanelFragment chatPanelFragment;

    @Autowired
    private AiChatSessionState aiChatSessionState;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        syncConversationIdFromSessionState();
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        syncConversationIdFromSessionState();
    }

    private void syncConversationIdFromSessionState() {
        chatPanelFragment.setConversationId(aiChatSessionState.getCurrentConversationId());
    }
}
