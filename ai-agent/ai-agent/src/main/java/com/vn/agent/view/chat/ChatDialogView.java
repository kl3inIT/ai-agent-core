package com.vn.agent.view.chat;

import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

/** Dialog surface for the chat panel. The DialogWindow's built-in close (X) and
 *  ChatSurfaceMounter's afterCloseListener handle teardown — no custom close button.
 *  New-chat is offered via the icon button in ChatPanelFragment.titleBar. */
@ViewController("AiAgent_ChatDialog")
@ViewDescriptor("chat-dialog-view.xml")
public class ChatDialogView extends StandardView {

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
