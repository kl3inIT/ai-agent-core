package com.vn.agent.view.chat;

import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiChatSurface;
import com.vn.agent.orchestration.ConversationNotFoundException;
import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import java.util.UUID;

/** D-06/D-29 route shell: route + conversationId query-param binding + Fragment composition.
 *  All chat logic (including new-chat confirm) lives in ChatPanelFragment. */
@Route(value = "ai-agent/chat", layout = DefaultMainViewParent.class)
@ViewController("AiAgent_Chat")
@ViewDescriptor("chat-view.xml")
public class ChatView extends StandardView {

    @ViewComponent private ChatPanelFragment chatPanelFragment;
    @Autowired private Messages messages;
    @Autowired private Notifications notifications;
    @Autowired private AiUiSettingsService uiSettingsService;
    @Autowired private AiChatSessionState chatSessionState;

    @Override
    public void beforeEnter(@NonNull BeforeEnterEvent event) {
        if (!uiSettingsService.loadCurrent().getEnabledSurfaceSet().contains(AiChatSurface.FULL_ROUTE)) {
            event.forwardTo("");
            notifications.create(messages.getMessage("chatView.fullRouteDisabled")).show();
            return;
        }

        super.beforeEnter(event);
    }

    @Subscribe
    public void onQueryParametersChange(final View.QueryParametersChangeEvent event) {
        String raw = event.getQueryParameters()
                .getSingleParameter("conversationId")
                .orElse(null);
        if (raw == null) {
            chatPanelFragment.setConversationId(chatSessionState.getCurrentConversationId());
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

    private void notifyGenericError() {
        notifications.create(messages.getMessage("chatView.error.generic")).show();
    }
}
