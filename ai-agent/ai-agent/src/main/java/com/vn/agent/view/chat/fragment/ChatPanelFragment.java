package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.shared.Registration;
import com.vn.agent.ChatService;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.rag.CancellationRegistry;
import com.vn.agent.view.chat.AiChatSessionState;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.app.inputdialog.DialogActions;
import io.jmix.flowui.app.inputdialog.DialogOutcome;
import io.jmix.flowui.app.inputdialog.InputParameter;
import io.jmix.flowui.component.validation.ValidationErrors;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.fragment.FragmentOwner;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    @ViewComponent private H3 conversationTitle;
    @ViewComponent private JmixButton editConversationTitleButton;
    @ViewComponent private VerticalLayout messageListSlot;
    @ViewComponent private VerticalLayout messageInputSlot;

    @Autowired private ChatService chatService;
    @Autowired private ConversationGateway conversationGateway;
    @Autowired private CancellationRegistry cancellationRegistry;
    @Autowired private CurrentAuthentication currentAuthentication;
    @Autowired private Messages messages;
    @Autowired private Dialogs dialogs;
    @Autowired private DataManager dataManager;
    @Autowired private Notifications notifications;
    @Autowired private AiChatSessionState chatSessionState;

    private MessageList messageList;
    private MessageInput messageInput;
    private ProgressBar streamProgressBar;
    private final List<MessageListItem> items = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();

    private UUID conversationId;
    private UUID activeRunId;
    private volatile Disposable activeStream;
    private MessageListItem botMsg;
    private volatile UI ownerUi;
    private Registration conversationIdStateRegistration;

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

        streamProgressBar = new ProgressBar();
        streamProgressBar.setWidthFull();
        streamProgressBar.setIndeterminate(true);
        streamProgressBar.setVisible(false);
        streamProgressBar.addClassName("ai-agent-chat-panel__stream-progress");
        messageInputSlot.add(streamProgressBar, messageInput);

        resolveLabels();
        updateTitleEditState();
        messageList.setItems(items);
    }

    @Override
    protected void onAttach(@NonNull AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        this.ownerUi = attachEvent.getUI();
        registerConversationIdStateListener(this.ownerUi);
    }

    @Override
    protected void onDetach(@NonNull DetachEvent detachEvent) {
        unregisterConversationIdStateListener();
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
        stopActiveStream();
    }

    @Subscribe("editConversationTitleButton")
    public void onEditConversationTitleButtonClick(final ClickEvent<JmixButton> event) {
        if (conversationId == null) {
            updateTitleEditState();
            return;
        }
        openTitleEditDialog();
    }

    // ---- Public API --------------------------------------------------------

    public void setConversationId(UUID cid) {
        setConversationIdInternal(cid);
        updateSessionConversationId(cid);
    }

    void setConversationIdFromState(UUID cid) {
        setConversationIdInternal(cid);
    }

    private void setConversationIdInternal(UUID cid) {
        if (Objects.equals(this.conversationId, cid)) {
            return;
        }

        if (isStreaming()) {
            stopActiveStream();
        }
        this.conversationId = cid;
        items.clear();
        if (cid == null) {
            if (messageList != null) messageList.setItems(items);
            updateConversationTitle(null);
            updateTitleEditState();
            return;
        }
        // Ownership check (D-09) — foreign / missing ids throw ConversationNotFoundException.
        AiConversation conversation =
                conversationGateway.loadOrCreate(currentAuthentication.getUser().getUsername(), cid, null);
        updateConversationTitle(conversation.getTitle());
        updateTitleEditState();

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
        setConversationIdInternal(null);
        updateSessionConversationId(null);
    }

    private void stopActiveStream() {
        // D-04 — route through the registry when runId is known so CANCELLED is audited.
        if (activeRunId != null) {
            cancellationRegistry.cancel(activeRunId);
            finishStreamInternal();
            return;
        }
        Disposable streamDisposable = activeStream;
        if (streamDisposable != null && !streamDisposable.isDisposed()) {
            streamDisposable.dispose();
            finishStreamInternal();
        }
    }

    private void openTitleEditDialog() {
        dialogs.createInputDialog(hostView())
                .withHeader(messages.getMessage("chatView.editTitle.dialog.header"))
                .withLabelsPosition(Dialogs.InputDialogBuilder.LabelsPosition.TOP)
                .withParameters(
                        InputParameter.stringParameter("title")
                                .withLabel(messages.getMessage("chatView.editTitle.field.title"))
                                .withRequired(true)
                                .withDefaultValue(currentConversationTitle())
                )
                .withValidator(context -> {
                    String title = context.getValue("title");
                    if (title == null || title.isBlank()) {
                        return ValidationErrors.of(messages.getMessage("chatView.editTitle.validation.required"));
                    }
                    return ValidationErrors.none();
                })
                .withActions(DialogActions.OK_CANCEL)
                .withCloseListener(closeEvent -> {
                    if (!closeEvent.closedWith(DialogOutcome.OK)) {
                        return;
                    }
                    try {
                        saveManualConversationTitle(closeEvent.getValue("title"));
                    } catch (RuntimeException failure) {
                        log.warn("Manual conversation title save failed conversationId={}", conversationId, failure);
                        showGenericErrorNotification();
                    }
                })
                .open();
    }

    void saveManualConversationTitle(String rawTitle) {
        String title = normalizeManualConversationTitle(rawTitle);
        if (conversationId == null) {
            return;
        }

        String username = currentAuthentication.getUser().getUsername();
        AiConversation conversation = conversationGateway.loadOrCreate(username, conversationId, null);
        conversation.setTitle(title);
        dataManager.save(conversation);
        updateConversationTitle(title);
    }

    String normalizeManualConversationTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            throw new IllegalArgumentException("chatView.editTitle.validation.required");
        }
        return rawTitle.strip();
    }

    private View<?> hostView() {
        FragmentOwner parentController = getParentController();
        if (parentController instanceof View<?> view) {
            return view;
        }
        throw new IllegalStateException("ChatPanelFragment must be attached to a Jmix view");
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

        setStreamingUiState(true);

        final String userId = currentAuthentication.getUser().getUsername();
        final UUID targetConversationId = ensureConversationIdForSubmit(userId, text);
        final StreamEventRenderer.CitationState citationState = new StreamEventRenderer.CitationState();

        Flux<StreamingEvent> source = chatService.stream(userId, targetConversationId, text, null);
        activeStream = source
                .doOnSubscribe(sub -> {
                    if (sub instanceof Disposable disposable && activeRunId != null) {
                        cancellationRegistry.register(activeRunId, disposable);
                    }
                })
                .doOnNext(evt -> {
                    if (evt instanceof StreamingEvent.Final f && activeRunId == null) {
                        activeRunId = f.runId();
                        if (conversationId == null && f.conversationId() != null) {
                            conversationId = f.conversationId();
                            updateSessionConversationId(conversationId);
                        }
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
                    accessUi(() -> { showGenericErrorNotification(); finishStreamInternal(); });
                })
                .doOnComplete(() -> accessUi(this::finishStreamInternal))
                .subscribe();
    }

    private void finishStreamInternal() {
        setStreamingUiState(false);
        if (activeRunId != null) cancellationRegistry.clearDisposable(activeRunId);
        activeRunId = null;
        activeStream = null;
        botMsg = null;
    }

    void setStreamingUiState(boolean streaming) {
        if (streamProgressBar != null) {
            streamProgressBar.setVisible(streaming);
        }
        if (messageInput != null) {
            messageInput.setEnabled(!streaming);
        }
        if (stopButton != null) {
            stopButton.setVisible(streaming);
        }
    }

    // ---- Helpers -----------------------------------------------------------

    /**
     * Reserve a stable conversation id before streaming starts so follow-up turns always
     * continue the same thread, even if the stream terminates before a Final event arrives.
     */
    UUID ensureConversationIdForSubmit(String userId, String firstMessage) {
        if (conversationId != null) {
            return conversationId;
        }
        AiConversation conversation = conversationGateway.loadOrCreate(userId, null, firstMessage);
        UUID resolved = conversation.getId();
        if (resolved == null) {
            throw new IllegalStateException("Conversation id must not be null");
        }
        conversationId = resolved;
        updateConversationTitle(conversation.getTitle());
        updateTitleEditState();
        updateSessionConversationId(resolved);
        return resolved;
    }

    void registerConversationIdStateListener(UI ui) {
        unregisterConversationIdStateListener();
        if (chatSessionState == null) {
            return;
        }

        conversationIdStateRegistration =
                chatSessionState.addConversationIdChangeListener(ui, this::setConversationIdFromState);
        UUID stateConversationId = chatSessionState.getCurrentConversationId();
        if (stateConversationId != null) {
            setConversationIdFromState(stateConversationId);
        }
    }

    private void unregisterConversationIdStateListener() {
        Registration registration = this.conversationIdStateRegistration;
        if (registration != null) {
            registration.remove();
            this.conversationIdStateRegistration = null;
        }
    }

    private void updateSessionConversationId(UUID cid) {
        if (chatSessionState != null
                && !Objects.equals(chatSessionState.getCurrentConversationId(), cid)) {
            chatSessionState.setCurrentConversationId(cid);
        }
    }

    private void updateConversationTitle(String title) {
        if (conversationTitle == null) {
            return;
        }
        String displayTitle = title == null || title.isBlank()
                ? resolveLabel("chatView.action.newChat", "chatView.action.newChat")
                : title.strip();
        conversationTitle.setText(displayTitle);
    }

    private String currentConversationTitle() {
        if (conversationTitle == null) {
            return "";
        }
        String title = conversationTitle.getText();
        return title == null ? "" : title;
    }

    private void updateTitleEditState() {
        if (editConversationTitleButton == null) {
            return;
        }
        boolean hasConversation = conversationId != null;
        editConversationTitleButton.setVisible(hasConversation);
        editConversationTitleButton.setEnabled(hasConversation);
        if (!hasConversation) {
            updateConversationTitle(null);
        }
    }

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
        labels.put("chatView.error.generic",
                resolveLabel("chatView.error.generic", "Something went wrong. Please try again."));
        labels.put("chatView.error.conversationNotFound",
                resolveLabel("chatView.error.conversationNotFound", "Conversation not found or no longer accessible."));
        labels.put("ai-agent.guard.rate-limit-exceeded",
                resolveLabel("ai-agent.guard.rate-limit-exceeded", "Too many requests — please wait before trying again."));
        labels.put("ai-agent.guard.token-budget-exhausted",
                resolveLabel("ai-agent.guard.token-budget-exhausted",
                        "This conversation has reached its token limit. Start a new chat to continue."));
        labels.put("ai-agent.guard.iteration-cap-exceeded",
                resolveLabel("ai-agent.guard.iteration-cap-exceeded",
                        "The assistant could not complete the task within the allowed number of steps."));
        labels.put("ai-agent.guard.tool-vetoed",
                resolveLabel("ai-agent.guard.tool-vetoed", "This action was blocked by policy."));
    }

    private String resolveLabel(String key, String fallback) {
        try {
            String message = messages.getMessage(key);
            return message.equals(key) ? fallback : message;
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    private void showGenericErrorNotification() {
        String key = "chatView.error.generic";
        String text = resolveLabel(key, key);
        notifications.create(text != null ? text : key)
                .withThemeVariant(NotificationVariant.LUMO_ERROR)
                .show();
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
