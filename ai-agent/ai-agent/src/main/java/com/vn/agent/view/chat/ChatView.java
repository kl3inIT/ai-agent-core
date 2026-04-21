package com.vn.agent.view.chat;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vn.agent.orchestration.ConversationNotFoundException;
import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * D-29 composition point. ChatView owns only the route + page chrome (New chat button).
 * All chat logic lives in {@link ChatPanelFragment} so v2 floating launcher embeds the
 * same substrate unchanged.
 *
 * <p>Route: {@code ai-agent/chat} (D-26). Accepts an optional {@code conversationId}
 * query parameter; invalid or foreign ids fall through to a fresh chat + error
 * notification (T-07-09 mitigation — gateway exception is never bubbled to the UI).</p>
 */
@Route(value = "ai-agent/chat", layout = DefaultMainViewParent.class)
@ViewController("AiAgent_Chat")
@ViewDescriptor("chat-view.xml")
public class ChatView extends StandardView implements BeforeEnterObserver {

    private static final Logger log = LoggerFactory.getLogger(ChatView.class);

    @ViewComponent
    private ChatPanelFragment chatPanel;
    @ViewComponent
    private JmixButton newChatButton;

    @Autowired
    private Dialogs dialogs;
    @Autowired
    private MessageSource messages;
    @Autowired
    private Notifications notifications;

    @Subscribe
    public void onInit(final InitEvent event) {
        newChatButton.addClickListener(e -> onNewChatClick());
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        QueryParameters qp = event.getLocation().getQueryParameters();
        Map<String, List<String>> params = qp.getParameters();
        List<String> idValues = params.getOrDefault("conversationId", List.of());
        if (idValues.isEmpty()) {
            return;
        }
        String raw = idValues.get(0);
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            log.debug("Ignoring malformed conversationId query param: {}", raw);
            notifyGenericError();
            return;
        }
        try {
            chatPanel.setConversationId(id);
        } catch (ConversationNotFoundException ex) {
            // T-07-09: ownership / missing id → never bubble gateway exception to UI.
            log.debug("conversationId {} not loadable for current user", id);
            notifyGenericError();
            chatPanel.setConversationId(null);
        }
    }

    private void onNewChatClick() {
        if (!chatPanel.hasMessages()) {
            // Nothing to preserve — just reset silently.
            chatPanel.setConversationId(null);
            return;
        }
        Locale locale = currentLocale();
        dialogs.createOptionDialog()
                .withHeader(messages.getMessage(
                        "chatView.confirm.newChat.title", null,
                        "Start a new chat?", locale))
                .withText(messages.getMessage(
                        "chatView.confirm.newChat.body", null,
                        "The current conversation will remain in your history.", locale))
                .withActions(
                        new DialogAction(DialogAction.Type.OK)
                                .withVariant(ActionVariant.PRIMARY)
                                .withHandler(ev -> resetToNewChat()),
                        new DialogAction(DialogAction.Type.CANCEL))
                .open();
    }

    private void resetToNewChat() {
        chatPanel.setConversationId(null);
    }

    private void notifyGenericError() {
        Locale locale = currentLocale();
        String text = messages.getMessage(
                "chatView.error.generic", null,
                "Something went wrong. Please try again.", locale);
        notifications.create(text).show();
    }

    private static Locale currentLocale() {
        UI ui = UI.getCurrent();
        return ui != null ? ui.getLocale() : Locale.getDefault();
    }
}
