package com.vn.agent.view.conversation;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.view.chat.ChatView;
import com.vn.agent.view.chat.MarkdownRenderer;
import com.vn.agent.view.chat.fragment.MessageBubbleComponent;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * UI-03 detail view — read-only transcript replay. Reuses {@link MessageBubbleComponent}
 * (shipped in Plan 07-03) so live-chat and replay share the exact same rendering pipeline
 * (T-07-13: all user content flows through {@code MarkdownRenderer.toSafeHtml} → OWASP sanitizer).
 *
 * <p>D-26: the "Continue in chat" button navigates to {@link ChatView} with the current
 * conversation id as the {@code conversationId} query parameter so the chat panel reopens
 * into the same conversation and streaming picks up from the last message.</p>
 *
 * <p>AiMessage has no {@code toolCallsJson} field in Phase 2 — Rule 1 deviation from the
 * plan's {@code <interfaces>} block. Consequently this view renders bubbles only for
 * USER / ASSISTANT / SYSTEM roles and quietly skips TOOL-role rows (they never carry
 * standalone human-visible content; tool activity is summarized inside assistant turns).
 * The {@code ToolCallCardComponent} import is therefore intentionally absent here.</p>
 */
@Route(value = "ai-agent/conversations/:id", layout = DefaultMainViewParent.class)
@ViewController("AiAgent_Conversation.detail")
@ViewDescriptor("conversation-detail-view.xml")
public class ConversationDetailView extends StandardDetailView<AiConversation> {

    private static final Logger log = LoggerFactory.getLogger(ConversationDetailView.class);

    @ViewComponent
    private CollectionLoader<AiMessage> messagesDl;
    @ViewComponent
    private CollectionContainer<AiMessage> messagesDc;
    @ViewComponent
    private VerticalLayout transcriptList;

    @Autowired
    private MarkdownRenderer markdownRenderer;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        // The XML <dataLoadCoordinator auto="true"/> already triggers messagesDl once
        // the :conv parameter is available from conversationDc. Avoid an extra manual
        // load — just render whatever the container holds after the auto-load fires.
        renderTranscript(messagesDc.getItems());
    }

    @Subscribe("continueInChatButton")
    public void onContinueInChatClick(final ClickEvent<Button> event) {
        AiConversation conv = getEditedEntity();
        if (conv == null || conv.getId() == null) {
            return;
        }
        UUID convId = conv.getId();
        UI ui = UI.getCurrent();
        if (ui != null) {
            ui.navigate(ChatView.class,
                    QueryParameters.simple(Map.of("conversationId", convId.toString())));
        }
    }

    /**
     * Replay messages as a vertical list of {@link MessageBubbleComponent} instances.
     * Role mapping: USER/ASSISTANT/SYSTEM → matching bubble role; TOOL is skipped
     * (Phase 2 AiMessage has no toolCallsJson — documented class-level deviation).
     */
    private void renderTranscript(Collection<AiMessage> messages) {
        transcriptList.removeAll();
        if (messages == null) {
            return;
        }
        for (AiMessage message : messages) {
            AiMessageRole role = message.getRole();
            if (role == null || role == AiMessageRole.TOOL) {
                continue;
            }
            MessageBubbleComponent.Role bubbleRole = switch (role) {
                case USER -> MessageBubbleComponent.Role.USER;
                case ASSISTANT -> MessageBubbleComponent.Role.ASSISTANT;
                case SYSTEM -> MessageBubbleComponent.Role.SYSTEM;
                // TOOL handled above; exhaustive switch guards against new enum values.
                case TOOL -> null;
            };
            if (bubbleRole == null) {
                continue;
            }
            MessageBubbleComponent bubble = new MessageBubbleComponent(bubbleRole, markdownRenderer);
            bubble.setMarkdown(message.getContent());
            transcriptList.add(bubble);
        }
        log.debug("Rendered {} message bubbles for conversation replay", transcriptList.getComponentCount());
    }

}
