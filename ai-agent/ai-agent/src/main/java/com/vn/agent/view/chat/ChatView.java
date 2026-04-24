package com.vn.agent.view.chat;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.router.Route;
import com.vn.agent.orchestration.ConversationNotFoundException;
import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import java.util.Locale;
import java.util.UUID;

/** D-06/D-29 route shell: route + conversationId query-param binding + New-chat
 *  confirm + Fragment composition. All chat logic lives in ChatPanelFragment. */
@Route(value = "ai-agent/chat", layout = DefaultMainViewParent.class)
@ViewController("AiAgent_Chat")
@ViewDescriptor("chat-view.xml")
public class ChatView extends StandardView {

    @ViewComponent private ChatPanelFragment chatPanelFragment;
    @Autowired private Dialogs dialogs;
    @Autowired private MessageSource messages;
    @Autowired private Notifications notifications;

    @Subscribe
    public void onQueryParametersChange(final View.QueryParametersChangeEvent event) {
        String raw = event.getQueryParameters()
                .getSingleParameter("conversationId")
                .orElse(null);
        if (raw == null) {
            chatPanelFragment.setConversationId(null);
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            notifyGenericError();
            chatPanelFragment.setConversationId(null);
            return;
        }
        try {
            chatPanelFragment.setConversationId(id);
        } catch (ConversationNotFoundException ex) {
            // T-07-09 opacity — never bubble gateway exception to UI.
            notifyGenericError();
            chatPanelFragment.setConversationId(null);
        }
    }

    @Subscribe("newChatButton")
    public void onNewChatButtonClick(final ClickEvent<Button> event) {
        if (!chatPanelFragment.hasMessages() && !chatPanelFragment.isStreaming()) {
            chatPanelFragment.startNewChat();
            return;
        }
        Locale locale = currentLocale();
        dialogs.createOptionDialog()
                .withHeader(messages.getMessage("chatView.newChat.confirmHeader", null, "Start new chat?", locale))
                .withText(messages.getMessage("chatView.newChat.confirmText", null,
                        "Unsaved messages in the current chat will be cleared.", locale))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withVariant(ActionVariant.PRIMARY)
                                .withHandler(ev -> chatPanelFragment.startNewChat()),
                        new DialogAction(DialogAction.Type.NO))
                .open();
    }

    private void notifyGenericError() {
        notifications.create(messages.getMessage("chatView.error.generic", null,
                "Something went wrong. Please try again.", currentLocale())).show();
    }

    private static Locale currentLocale() {
        UI ui = UI.getCurrent();
        return ui != null ? ui.getLocale() : Locale.getDefault();
    }
}
