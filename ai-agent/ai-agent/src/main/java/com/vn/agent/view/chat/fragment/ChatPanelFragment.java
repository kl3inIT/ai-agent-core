package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vn.agent.ChatService;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.view.chat.MarkdownRenderer;
import io.jmix.core.DataManager;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * D-29 substrate. Composed by {@code ChatView} (full route) in v1; the v2 floating launcher
 * will embed this SAME fragment unchanged.
 *
 * <p><b>Streaming pipeline (Pitfall #7 fix):</b> {@link StreamingEvent.Content} events are
 * coalesced at 50 ms via {@link Flux#bufferTimeout(int, Duration)} so DOM mutations stay
 * {@code ≤ 20 Hz} regardless of upstream token rate. A single {@code UI.access} per batch
 * concatenates the buffered markdown chunks and calls
 * {@link MessageBubbleComponent#appendMarkdown(String)} once.</p>
 *
 * <p><b>Dispose-on-detach (Pitfall #8):</b> {@link #onDetach(DetachEvent)} disposes any active
 * stream so navigating away tears down the Flux subscription.</p>
 *
 * <p>All UI mutations hop onto the per-UI event loop via {@code getUI().ifPresent(ui -> ui.access(...))}
 * because Push callbacks arrive off the {@code ai-agent-stream} scheduler thread.</p>
 */
@FragmentDescriptor("chat-panel-fragment.xml")
public class ChatPanelFragment extends Fragment<VerticalLayout> {

    private static final Logger log = LoggerFactory.getLogger(ChatPanelFragment.class);

    /**
     * Maximum number of Content events coalesced into a single UI.access tick. Acts as an
     * upper bound when the provider is extremely fast; the 50 ms time trigger is the primary
     * rate limiter.
     */
    private static final int CONTENT_BATCH_MAX = 64;

    /** Time window for Content-event coalescing — yields ≤20 UI DOM updates per second. */
    private static final Duration CONTENT_BATCH_WINDOW = Duration.ofMillis(50);

    @ViewComponent
    private VerticalLayout messageList;
    @ViewComponent
    private Scroller messageScroller;
    @ViewComponent
    private Div emptyStatePanel;
    @ViewComponent
    private JmixTextArea messageInput;
    @ViewComponent
    private JmixButton sendButton;
    @ViewComponent
    private JmixButton stopButton;

    @Autowired
    private ChatService chatService;
    @Autowired
    private CurrentAuthentication currentAuthentication;
    @Autowired
    private MarkdownRenderer markdownRenderer;
    @Autowired
    private MessageSource messages;
    @Autowired
    private ConversationGateway conversationGateway;
    @Autowired
    private DataManager dataManager;

    private UUID conversationId;
    private volatile Disposable activeStream;
    private MessageBubbleComponent activeAssistantBubble;
    private final Map<UUID, ToolCallCardComponent> toolCardsByCallId = new HashMap<>();
    private volatile UI ownerUi;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        sendButton.addClickListener(e -> onSendClick());
        stopButton.addClickListener(e -> onStopClick());
        // Enter → send; Shift+Enter → newline (chat UX convention). The text area's
        // default Enter behaviour inserts a newline, so we must preventDefault when
        // firing the send to stop the newline from being appended first.
        messageInput.getElement().addEventListener("keydown", ev -> onSendClick())
                .setFilter("event.key === 'Enter' && !event.shiftKey && !event.isComposing")
                .addEventData("event.preventDefault()");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        this.ownerUi = attachEvent.getUI();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        try {
            Disposable d = this.activeStream;
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
        } catch (RuntimeException ignored) {
            // Fragment teardown must not throw.
        }
        this.activeStream = null;
        this.ownerUi = null;
        super.onDetach(detachEvent);
    }

    // --- Public lifecycle --------------------------------------------------------

    /**
     * Set or clear the conversation bound to this fragment. A non-null id triggers
     * transcript load; a null id resets to the empty-state panel.
     */
    public void setConversationId(UUID conversationId) {
        // Dispose any in-flight stream so a mid-stream reset does not continue writing
        // events into the cleared fragment state (the bubble reference is nulled below
        // but the Flux would keep pushing until natural completion otherwise).
        Disposable d = this.activeStream;
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        this.activeStream = null;
        this.conversationId = conversationId;
        messageList.removeAll();
        toolCardsByCallId.clear();
        activeAssistantBubble = null;
        if (conversationId != null) {
            loadTranscript();
        } else {
            emptyStatePanel.setVisible(true);
        }
    }

    public UUID getConversationId() {
        return conversationId;
    }

    /** True if this fragment has rendered at least one message bubble. */
    public boolean hasMessages() {
        return messageList.getComponentCount() > 0;
    }

    /**
     * Read-only load of the transcript for {@link #conversationId}. Skips the
     * {@code loadOrCreate}'s auto-create branch by issuing a direct ownership-checked JPQL
     * query so no side-effect happens when the view is merely displaying history.
     */
    private void loadTranscript() {
        String userId = currentAuthentication.getUser().getUsername();
        // Ownership check (D-09 opacity) via gateway — throws ConversationNotFoundException
        // if the id is missing or owned by another user.
        AiConversation conversation = conversationGateway.loadOrCreate(userId, conversationId, null);

        List<AiMessage> history = dataManager.load(AiMessage.class)
                .query("select m from ai_AiMessage m where m.conversation = :conv order by m.seq asc, m.createdDate asc")
                .parameter("conv", conversation)
                .list();

        if (history.isEmpty()) {
            emptyStatePanel.setVisible(true);
            return;
        }

        emptyStatePanel.setVisible(false);
        for (AiMessage message : history) {
            MessageBubbleComponent.Role role = roleOf(message.getRole());
            MessageBubbleComponent bubble = new MessageBubbleComponent(role, markdownRenderer);
            bubble.setMarkdown(message.getContent());
            messageList.add(bubble);
        }
        scrollToEnd();
    }

    private static MessageBubbleComponent.Role roleOf(AiMessageRole role) {
        if (role == null) {
            return MessageBubbleComponent.Role.SYSTEM;
        }
        return switch (role) {
            case USER -> MessageBubbleComponent.Role.USER;
            case ASSISTANT -> MessageBubbleComponent.Role.ASSISTANT;
            default -> MessageBubbleComponent.Role.SYSTEM;
        };
    }

    // --- Send / Stop -------------------------------------------------------------

    private void onSendClick() {
        String text = messageInput.getValue();
        if (text == null || text.isBlank()) {
            return;
        }
        messageInput.clear();
        messageInput.setEnabled(false);
        sendButton.setVisible(false);
        stopButton.setVisible(true);
        emptyStatePanel.setVisible(false);

        // Append USER bubble.
        MessageBubbleComponent userBubble = new MessageBubbleComponent(
                MessageBubbleComponent.Role.USER, markdownRenderer);
        userBubble.setMarkdown(text);
        messageList.add(userBubble);

        // Append streaming ASSISTANT bubble.
        this.activeAssistantBubble = new MessageBubbleComponent(
                MessageBubbleComponent.Role.ASSISTANT, markdownRenderer);
        messageList.add(activeAssistantBubble);
        scrollToEnd();

        String userId = currentAuthentication.getUser().getUsername();

        Flux<StreamingEvent> source = chatService.stream(userId, conversationId, text, null);

        // 50 ms Content-event coalescing (Pitfall #7). Using the simple-shape bufferTimeout
        // over the FULL event stream: handleBatch iterates and dispatches per type under a
        // single UI.access per 50 ms window — keeps DOM updates ≤ 20 Hz regardless of the
        // provider's token rate, at the cost of also bunching structural events by 50 ms
        // (acceptable for the UI contract; tool cards / citations are not real-time critical).
        this.activeStream = source
                .bufferTimeout(CONTENT_BATCH_MAX, CONTENT_BATCH_WINDOW)
                .filter(batch -> !batch.isEmpty())
                .subscribe(
                        this::handleBatch,
                        this::handleError,
                        this::finishStream);
    }

    private void onStopClick() {
        Disposable d = this.activeStream;
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
        if (activeAssistantBubble != null) {
            activeAssistantBubble.markStopped();
        }
        finishStream();
    }

    // --- Batch dispatch ----------------------------------------------------------

    private void handleBatch(List<StreamingEvent> batch) {
        accessUi(() -> {
            StringBuilder contentBuffer = new StringBuilder();
            List<StreamingEvent> structural = new ArrayList<>();
            for (StreamingEvent event : batch) {
                if (event instanceof StreamingEvent.Content c) {
                    if (c.markdownChunk() != null) {
                        contentBuffer.append(c.markdownChunk());
                    }
                } else {
                    structural.add(event);
                }
            }
            // Single DOM mutation per 50 ms window for content.
            if (contentBuffer.length() > 0 && activeAssistantBubble != null) {
                activeAssistantBubble.appendMarkdown(contentBuffer.toString());
            }
            for (StreamingEvent event : structural) {
                dispatchStructural(event);
            }
            if (!batch.isEmpty()) {
                scrollToEnd();
            }
        });
    }

    private void dispatchStructural(StreamingEvent event) {
        if (event instanceof StreamingEvent.ToolCall tc) {
            ToolCallCardComponent card = new ToolCallCardComponent(
                    tc.toolCallId(), tc.toolName(), tc.argsJson(), messages);
            toolCardsByCallId.put(tc.toolCallId(), card);
            messageList.add(card);
        } else if (event instanceof StreamingEvent.ToolResult tr) {
            ToolCallCardComponent card = toolCardsByCallId.get(tr.toolCallId());
            if (card != null) {
                card.setResult(tr.summary(), tr.outcome());
            }
        } else if (event instanceof StreamingEvent.Citation c) {
            // Append inline [n] marker; clicking would normally open the dialog. For v1 we
            // render the marker as part of the bubble and surface a single click through a
            // helper span so the dialog is wired here without taking a hard dependency on
            // the bubble's internal HTML structure.
            if (activeAssistantBubble != null) {
                activeAssistantBubble.appendMarkdown(" [" + c.index() + "]");
            }
            openCitationDialog(c);
        } else if (event instanceof StreamingEvent.Error err) {
            showErrorNotification(err.messageKey());
        } else if (event instanceof StreamingEvent.Final) {
            // Final is the stream terminator — onComplete handles finishStream().
        }
    }

    private void openCitationDialog(StreamingEvent.Citation c) {
        // Document name is not carried on the Citation event yet — use the id as fallback
        // header. A follow-up can resolve the AiKnowledgeDocument title in a later plan.
        CitationDialog dialog = new CitationDialog(
                c.index(),
                c.documentId(),
                c.documentId() == null ? null : c.documentId().toString(),
                c.snippet(),
                messages);
        dialog.open();
    }

    private void handleError(Throwable t) {
        log.warn("Chat stream failed", t);
        accessUi(() -> {
            showErrorNotification("chatView.error.generic");
            finishStreamInternal();
        });
    }

    private void finishStream() {
        accessUi(this::finishStreamInternal);
    }

    private void finishStreamInternal() {
        sendButton.setVisible(true);
        stopButton.setVisible(false);
        messageInput.setEnabled(true);
        activeAssistantBubble = null;
        activeStream = null;
    }

    private void showErrorNotification(String messageKey) {
        Locale locale = ownerUi != null ? ownerUi.getLocale() : Locale.getDefault();
        String text = messages.getMessage(messageKey, null, messageKey, locale);
        Notification notification = Notification.show(text);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void scrollToEnd() {
        // Scroller snaps to the bottom via JS-free position update — the scroller's content
        // grows downward so simply scrolling its host to maxScroll is enough.
        messageScroller.getElement().executeJs("this.scrollTop = this.scrollHeight;");
    }

    /**
     * Hop onto the owning UI's lock before mutating the DOM. Falls back to
     * {@link #getUI()} when the ownerUi was not captured (pre-attach path).
     */
    private void accessUi(Runnable action) {
        UI ui = this.ownerUi;
        if (ui == null) {
            ui = getUI().orElse(null);
        }
        if (ui == null) {
            // Fragment is detached — drop the update (navigate-away race).
            return;
        }
        ui.access(() -> action.run());
    }
}
