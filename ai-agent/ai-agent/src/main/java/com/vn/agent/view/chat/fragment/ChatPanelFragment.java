package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vn.agent.ChatService;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.rag.CancellationRegistry;
import io.jmix.core.DataManager;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.Notifications;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** D-29 substrate on Vaadin MessageList + MessageInput. D-03 per-event ui.access; D-04 Stop
 *  via CancellationRegistry.cancel; Pitfall #8 dispose-on-detach. Public API for ChatView:
 *  setConversationId / hasMessages / isStreaming / startNewChat. */
@FragmentDescriptor("chat-panel-fragment.xml")
public class ChatPanelFragment extends Fragment<VerticalLayout> {

    private static final Logger log = LoggerFactory.getLogger(ChatPanelFragment.class);
    private static final int USER_COLOR = 0;
    private static final int AI_COLOR = 2;

    @ViewComponent private JmixButton stopButton;
    @ViewComponent private VerticalLayout messageListSlot;
    @ViewComponent private VerticalLayout messageInputSlot;

    @Autowired private ChatService chatService;
    @Autowired private ConversationGateway conversationGateway;
    @Autowired private CancellationRegistry cancellationRegistry;
    @Autowired private CurrentAuthentication currentAuthentication;
    @Autowired private MessageSource messages;
    @Autowired private DataManager dataManager;
    @Autowired private Notifications notifications;

    private MessageList messageList;
    private MessageInput messageInput;
    private final List<MessageListItem> items = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();

    private UUID conversationId;
    private UUID activeRunId;
    private volatile Disposable activeStream;
    private MessageListItem botMsg;
    private volatile UI ownerUi;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        messageList = new MessageList();
        messageList.setMarkdown(true);
        messageList.setWidthFull();
        messageList.getStyle().set("flex-grow", "1");
        messageListSlot.add(messageList);

        messageInput = new MessageInput();
        messageInput.setWidthFull();
        messageInput.addSubmitListener(this::onSubmit);
        messageInputSlot.add(messageInput);

        resolveLabels();
        messageList.setItems(items);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        this.ownerUi = attachEvent.getUI();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        // Pitfall #8 — dispose-on-detach. Route through registry when runId is known so
        // the CANCELLED audit row is written; bare dispose is only the last-resort fallback.
        Disposable d = this.activeStream;
        if (d != null && !d.isDisposed()) {
            try {
                if (activeRunId != null) {
                    cancellationRegistry.cancel(activeRunId);
                } else {
                    activeStream.dispose();
                }
            } catch (RuntimeException ignored) {
                // Teardown must not throw.
            }
        }
        this.activeStream = null;
        this.ownerUi = null;
        super.onDetach(detachEvent);
    }

    @Subscribe("stopButton")
    public void onStopButtonClick(final ClickEvent<Button> event) {
        // D-04 — MUST route through the registry so the audit row records CANCELLED.
        if (activeRunId != null) {
            cancellationRegistry.cancel(activeRunId);
        }
    }

    // ---- Public API --------------------------------------------------------

    public void setConversationId(UUID cid) {
        if (isStreaming() && activeRunId != null) {
            cancellationRegistry.cancel(activeRunId);
        }
        this.conversationId = cid;
        items.clear();
        if (cid == null) {
            if (messageList != null) messageList.setItems(items);
            return;
        }
        // Ownership check (D-09) — foreign / missing ids throw ConversationNotFoundException.
        conversationGateway.loadOrCreate(currentAuthentication.getUser().getUsername(), cid, null);

        List<AiMessage> history = dataManager.load(AiMessage.class)
                .query("select m from ai_AiMessage m where m.conversation.id = :cid " +
                       "order by m.createdDate asc, m.seq asc")
                .parameter("cid", cid)
                .list();

        String userName = resolveLabel("chatView.message.userName", "You");
        String aiName = resolveLabel("chatView.message.assistantName", "AI Assistant");
        for (AiMessage m : history) {
            AiMessageRole role = m.getRole();
            // SYSTEM / TOOL roles skipped — replay shows user-visible turns only.
            if (role == AiMessageRole.USER) {
                items.add(buildItem(m, userName, USER_COLOR));
            } else if (role == AiMessageRole.ASSISTANT) {
                items.add(buildItem(m, aiName, AI_COLOR));
            }
        }
        if (messageList != null) messageList.setItems(items);
    }

    public UUID getConversationId() { return conversationId; }

    public boolean hasMessages() { return !items.isEmpty(); }

    public boolean isStreaming() {
        Disposable d = activeStream;
        return d != null && !d.isDisposed();
    }

    public void startNewChat() {
        if (isStreaming() && activeRunId != null) {
            cancellationRegistry.cancel(activeRunId);
        }
        items.clear();
        if (messageList != null) messageList.setItems(items);
        conversationId = null;
    }

    // ---- Streaming ---------------------------------------------------------

    private void onSubmit(MessageInput.SubmitEvent event) {
        String text = event.getValue();
        if (text == null || text.isBlank()) return;

        String userName = resolveLabel("chatView.message.userName", "You");
        String aiName = resolveLabel("chatView.message.assistantName", "AI Assistant");
        MessageListItem userItem = new MessageListItem(text, Instant.now(), userName);
        userItem.setUserColorIndex(USER_COLOR);
        items.add(userItem);

        botMsg = new MessageListItem("", Instant.now(), aiName);
        botMsg.setUserColorIndex(AI_COLOR);
        items.add(botMsg);
        // Last setItems of the turn — mid-stream mutations use appendText only (Pitfall #5).
        messageList.setItems(new ArrayList<>(items));

        messageInput.setEnabled(false);
        stopButton.setVisible(true);

        final String userId = currentAuthentication.getUser().getUsername();
        final StreamEventRenderer.CitationState citationState = new StreamEventRenderer.CitationState();

        Flux<StreamingEvent> source = chatService.stream(userId, conversationId, text, null);
        activeStream = source
                .doOnSubscribe(sub -> {
                    if (sub instanceof Disposable disposable && activeRunId != null) {
                        cancellationRegistry.register(activeRunId, disposable);
                    }
                })
                .doOnNext(evt -> {
                    if (evt instanceof StreamingEvent.Final f && activeRunId == null) {
                        activeRunId = f.runId();
                    }
                    String md = StreamEventRenderer.renderStreamEvent(evt, labels, citationState);
                    if (md.isEmpty()) return;
                    accessUi(() -> {
                        if (botMsg != null) botMsg.appendText(md);
                        scrollToBottom();
                    });
                })
                .doOnError(err -> {
                    log.warn("Chat stream failed", err);
                    accessUi(() -> { showErrorNotification("chatView.error.generic"); finishStreamInternal(); });
                })
                .doOnComplete(() -> accessUi(this::finishStreamInternal))
                .subscribe();
    }

    private void finishStreamInternal() {
        messageInput.setEnabled(true);
        stopButton.setVisible(false);
        if (activeRunId != null) cancellationRegistry.clearDisposable(activeRunId);
        activeRunId = null;
        activeStream = null;
        botMsg = null;
    }

    // ---- Helpers -----------------------------------------------------------

    private MessageListItem buildItem(AiMessage m, String name, int colorIndex) {
        String content = m.getContent() == null ? "" : m.getContent();
        OffsetDateTime created = m.getCreatedDate();
        Instant time = created == null ? Instant.now() : created.toInstant();
        MessageListItem item = new MessageListItem(content, time, name);
        item.setUserColorIndex(colorIndex);
        return item;
    }

    private void resolveLabels() {
        labels.clear();
        labels.put("chatView.stream.sources", resolveLabel("chatView.stream.sources", "Sources"));
        labels.put("chatView.stream.outcome.SUCCESS", resolveLabel("chatView.stream.outcome.SUCCESS", "done"));
        labels.put("chatView.stream.outcome.BLOCKED", resolveLabel("chatView.stream.outcome.BLOCKED", "blocked"));
        labels.put("chatView.stream.outcome.ERROR", resolveLabel("chatView.stream.outcome.ERROR", "error"));
        labels.put("chatView.stream.outcome.FLAGGED", resolveLabel("chatView.stream.outcome.FLAGGED", "flagged"));
        labels.put("chatView.stream.error", resolveLabel("chatView.stream.error", "error"));
    }

    private String resolveLabel(String key, String fallback) {
        Locale locale = ownerUi != null ? ownerUi.getLocale() : Locale.getDefault();
        return messages.getMessage(key, null, fallback, locale);
    }

    private void showErrorNotification(String key) {
        Locale locale = ownerUi != null ? ownerUi.getLocale() : Locale.getDefault();
        String text = messages.getMessage(key, null, key, locale);
        notifications.create(text).withThemeVariant(NotificationVariant.LUMO_ERROR).show();
    }

    private void scrollToBottom() {
        messageList.getElement().executeJs("setTimeout(() => { this.scrollTop = this.scrollHeight; }, 50);");
    }

    private void accessUi(Runnable action) {
        UI ui = this.ownerUi;
        if (ui == null) ui = getUI().orElse(null);
        if (ui == null) return; // detached — drop update
        ui.access(action::run);
    }
}
