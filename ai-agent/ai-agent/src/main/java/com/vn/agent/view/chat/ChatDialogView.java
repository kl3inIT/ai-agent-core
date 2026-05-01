package com.vn.agent.view.chat;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.button.Button;
import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import io.jmix.core.Messages;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.view.DialogWindow;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

@ViewController("AiAgent_ChatDialog")
@ViewDescriptor("chat-dialog-view.xml")
public class ChatDialogView extends StandardView {

    @ViewComponent
    private ChatPanelFragment chatPanelFragment;

    @Autowired
    private AiChatSessionState aiChatSessionState;
    @Autowired
    private AiChatUIState aiChatUIState;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Messages messages;

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        syncConversationIdFromSessionState();
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        syncConversationIdFromSessionState();
    }

    @Subscribe("closeButton")
    public void onCloseButtonClick(final ClickEvent<Button> event) {
        DialogWindow<?> dialogWindow = aiChatUIState.getDialogInstance();
        if (dialogWindow != null) {
            dialogWindow.close();
            return;
        }

        closeWithDefaultAction();
    }

    @Subscribe("newChatButton")
    public void onNewChatButtonClick(final ClickEvent<Button> event) {
        if (!chatPanelFragment.hasMessages() && !chatPanelFragment.isStreaming()) {
            chatPanelFragment.startNewChat();
            return;
        }

        dialogs.createOptionDialog()
                .withHeader(messages.getMessage("chatView.newChat.confirmHeader"))
                .withText(messages.getMessage("chatView.newChat.confirmText"))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withVariant(ActionVariant.PRIMARY)
                                .withHandler(actionPerformedEvent -> chatPanelFragment.startNewChat()),
                        new DialogAction(DialogAction.Type.NO))
                .open();
    }

    private void syncConversationIdFromSessionState() {
        chatPanelFragment.setConversationId(aiChatSessionState.getCurrentConversationId());
    }
}
