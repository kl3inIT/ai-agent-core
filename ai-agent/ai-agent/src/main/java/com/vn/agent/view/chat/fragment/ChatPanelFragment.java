package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.shared.Registration;
import com.vn.agent.ChatService;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.entity.AiTaskFile;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.rag.CancellationRegistry;
import com.vn.agent.taskfile.AiTaskFileProperties;
import com.vn.agent.view.chat.AiChatSessionState;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.app.inputdialog.DialogActions;
import io.jmix.flowui.app.inputdialog.DialogOutcome;
import io.jmix.flowui.app.inputdialog.InputParameter;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.component.validation.ValidationErrors;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.fragment.FragmentOwner;
import io.jmix.flowui.kit.action.ActionVariant;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    @ViewComponent private JmixButton newChatButton;
    @ViewComponent private H3 conversationTitle;
    @ViewComponent private JmixButton editConversationTitleButton;
    @ViewComponent private VerticalLayout messageListSlot;
    @ViewComponent private VerticalLayout messageInputSlot;

    // Phase 13 Plan 04 — D-04 chip strip + upload affordance.
    @ViewComponent private VerticalLayout attachmentsPanel;
    @ViewComponent private JmixUpload upload;
    @ViewComponent private JmixButton attachButton;

    @Autowired private ChatService chatService;
    @Autowired private ConversationGateway conversationGateway;
    @Autowired private CancellationRegistry cancellationRegistry;
    @Autowired private CurrentAuthentication currentAuthentication;
    @Autowired private Messages messages;
    @Autowired private Dialogs dialogs;
    @Autowired private DataManager dataManager;
    @Autowired private Notifications notifications;
    @Autowired private AiChatSessionState chatSessionState;
    // Phase 13 Plan 04 — task-file persistence collaborators.
    @Autowired private Metadata metadataApi;
    @Autowired private FileStorageLocator fileStorageLocator;
    @Autowired private AiTaskFileProperties taskFileProperties;
    @Autowired private UiComponents uiComponents;

    private MessageList messageList;
    private MessageInput messageInput;
    private ProgressBar streamProgressBar;
    private final List<MessageListItem> items = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();

    // Phase 13 Plan 04 — chip-strip rendering state.
    // chipById preserves insertion order so chips render in upload sequence; the value is the
    // chip wrapper component that is removed when the user clicks the chip's remove icon.
    private final Map<UUID, Component> chipById = new LinkedHashMap<>();
    private HorizontalLayout chipStrip;
    private Path uploadTempDir;

    /**
     * REVIEWS HIGH-5 — server-side MIME allowlist re-validated INSIDE the upload handler
     * BEFORE FileStorage.saveStream and BEFORE Metadata.create(AiTaskFile.class). Mirrors
     * the 13-entry allowlist consumed by AiTaskFileMediaResolver (Plan 13-02). Client-side
     * acceptedFileTypes on the &lt;upload&gt; element is bypassable via dev tools, so we
     * MUST repeat the check server-side (see threat T-13-15).
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "text/csv",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/html",
            "text/plain",
            "text/markdown",
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp"
    );

    /**
     * Extension-based fallback when the browser sends a generic content type (e.g.
     * application/octet-stream). Mirrors AiTaskFileMediaResolver#EXTENSION_MIME_TYPES.
     */
    private static final Map<String, String> EXTENSION_TO_CONTENT_TYPE = Map.ofEntries(
            Map.entry(".pdf", "application/pdf"),
            Map.entry(".csv", "text/csv"),
            Map.entry(".doc", "application/msword"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".xls", "application/vnd.ms-excel"),
            Map.entry(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry(".html", "text/html"),
            Map.entry(".htm", "text/html"),
            Map.entry(".txt", "text/plain"),
            Map.entry(".md", "text/markdown"),
            Map.entry(".png", "image/png"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".gif", "image/gif"),
            Map.entry(".webp", "image/webp")
    );

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

        // Phase 13 Plan 04 — D-04 chip strip + upload handler wiring.
        initAttachmentsAndUpload();
    }

    /**
     * Phase 13 Plan 04 (D-04 + REVIEWS HIGH-4 + HIGH-5).
     * <ul>
     *   <li>Builds the chip-strip {@link HorizontalLayout} into the {@code attachmentsPanel}
     *       slot. The slot is left {@code visible="false"} until the first chip lands so the
     *       chat surface does not show empty whitespace above the input.</li>
     *   <li>Installs {@link UploadHandler#toFile} on the JmixUpload component (REVIEWS HIGH-4 —
     *       Vaadin 24.8 marks the legacy Upload receiver API forRemoval; the
     *       {@code UploadHandler.toFile} path is the canonical Jmix 2.8 contract per
     *       project memory feedback_jmix_upload_receiver_deprecated and KnowledgeBaseView).</li>
     *   <li>The {@code attachButton} declared in XML is held invisible — JmixUpload renders
     *       its own button with the localized {@code uploadText} which is the actual user
     *       affordance. The XML declaration stays so a future enhancement can flip
     *       {@code attachButton} visible and use it as a hidden-Upload trigger.</li>
     * </ul>
     */
    private void initAttachmentsAndUpload() {
        chipStrip = new HorizontalLayout();
        chipStrip.addClassName("ai-agent-chat-panel__chips");
        chipStrip.setWidthFull();
        chipStrip.setPadding(false);
        chipStrip.setSpacing(true);
        chipStrip.getStyle().set("flex-wrap", "wrap");
        attachmentsPanel.add(chipStrip);
        // Stays hidden until a chip is added (refreshAttachmentsVisibility).
        attachmentsPanel.setVisible(false);

        if (attachButton != null) {
            // JmixUpload renders its own visible upload button; the XML attachButton is kept
            // for future enhancement (visible toggle + programmatic upload trigger) but stays
            // invisible in v1.1 to avoid duplicating the affordance.
            attachButton.setVisible(false);
        }

        try {
            uploadTempDir = Files.createTempDirectory("ai-agent-task-file-upload-");
        } catch (IOException ex) {
            log.warn("Failed to create upload temp directory; falling back to default temp", ex);
            uploadTempDir = Path.of(System.getProperty("java.io.tmpdir", "."));
        }

        // REVIEWS HIGH-4 — UploadHandler.toFile: each accepted multi-file upload is streamed
        // by Jmix into a temp file under uploadTempDir; the lambda runs ONCE per file with
        // the metadata + Path. MIME / size validation runs INSIDE the lambda (REVIEWS HIGH-5)
        // BEFORE FileStorage.saveStream and BEFORE Metadata.create(AiTaskFile.class).
        upload.setMaxFileSize((int) Math.min(taskFileProperties.getMaxFileSizeBytes(), Integer.MAX_VALUE));
        upload.setUploadHandler(UploadHandler.toFile(
                (uploadMetadata, stagedFile) -> handleUploadedFile(
                        uploadMetadata.fileName(),
                        uploadMetadata.contentType(),
                        uploadMetadata.contentLength(),
                        stagedFile.toPath()),
                fileMetadata -> uploadTempDir.resolve(
                        UUID.randomUUID() + "-" + safeFileName(fileMetadata.fileName())).toFile()));
    }

    /**
     * @return the input filename with anything outside {@code [A-Za-z0-9.-]} replaced by
     *         {@code _}, so the staging-temp path never carries a directory separator or
     *         shell metacharacter from a user-supplied filename. The final stored filename
     *         comes from FileStorage and is independent of this temp-only sanitisation.
     */
    private String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "upload";
        }
        return fileName.replaceAll("[^A-Za-z0-9.\\-]", "_");
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

    @Subscribe("newChatButton")
    public void onNewChatButtonClick(final ClickEvent<JmixButton> event) {
        if (!hasMessages() && !isStreaming()) {
            startNewChat();
            return;
        }
        dialogs.createOptionDialog()
                .withHeader(messages.getMessage("chatView.newChat.confirmHeader"))
                .withText(messages.getMessage("chatView.newChat.confirmText"))
                .withActions(
                        new DialogAction(DialogAction.Type.YES).withVariant(ActionVariant.PRIMARY)
                                .withHandler(actionPerformedEvent -> startNewChat()),
                        new DialogAction(DialogAction.Type.NO))
                .open();
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

    // ---- Phase 13 Plan 04 — D-04 + REVIEWS HIGH-4 + HIGH-5 attachments wiring -------

    /**
     * Wired declaratively because {@link FileRejectedEvent} does not reference the
     * deprecated receiver API (memory {@code feedback_jmix_upload_receiver_deprecated}).
     * Client-side rejection fires when the user selects a file exceeding the
     * {@code maxFileSize} or outside {@code acceptedFileTypes}; server-side validation
     * inside {@link #handleUploadedFile} re-checks both for defence-in-depth.
     */
    @Subscribe("upload")
    public void onUploadFileRejected(final FileRejectedEvent event) {
        log.debug("Upload rejected client-side: {}", event.getErrorMessage());
        notifications.create(messages.getMessage("chatView.attachments.upload.tooLarge"))
                .withThemeVariant(NotificationVariant.LUMO_WARNING)
                .show();
    }

    /**
     * Handles one accepted upload (REVIEWS HIGH-4 + HIGH-5).
     *
     * <p>Order of operations is load-bearing:
     * <ol>
     *   <li>Resolve target conversation id (fail fast if no chat session).</li>
     *   <li><b>REVIEWS HIGH-5</b> — validate size + MIME against the server-side allowlist
     *       BEFORE any blob persist or row create. A {@code .exe} upload bypassing the
     *       client-side {@code acceptedFileTypes} attribute lands here, gets rejected, and
     *       the temp file is deleted. No FileStorage blob, no AiTaskFile row.</li>
     *   <li>Stream the temp file into FileStorage to get a {@link FileRef}.</li>
     *   <li>Build the {@link AiTaskFile} via {@link Metadata#create(Class)} (CLAUDE.md
     *       forbids {@code new AiTaskFile()}).</li>
     *   <li>Save under the regular {@code DataManager} so the user row-level policy from
     *       Plan 13-01 stamps the row with {@code userUsername} and prevents cross-user reads.</li>
     *   <li>Render the chip into the chip-strip.</li>
     * </ol>
     *
     * <p>Best-effort temp-file cleanup runs in the {@code finally} block; a missing temp
     * file is not a failure (Jmix may have already removed it).
     */
    private void handleUploadedFile(String fileName, String declaredContentType, long sizeBytes, Path tempFile) {
        try {
            UUID convId = ensureConversationIdForUpload();
            if (convId == null) {
                log.warn("Upload arrived without an active conversation; dropping file {}", fileName);
                notifications.create(messages.getMessage("chatView.attachments.upload.failed"))
                        .withThemeVariant(NotificationVariant.LUMO_WARNING)
                        .show();
                return;
            }

            // REVIEWS HIGH-5 — server-side size cap re-validated BEFORE blob persist.
            long maxBytes = taskFileProperties.getMaxFileSizeBytes();
            if (sizeBytes > maxBytes) {
                log.warn("Upload rejected (size {} > cap {}): {}", sizeBytes, maxBytes, fileName);
                notifications.create(messages.getMessage("chatView.attachments.upload.tooLarge"))
                        .withThemeVariant(NotificationVariant.LUMO_WARNING)
                        .show();
                return;
            }
            // REVIEWS HIGH-5 — server-side MIME allowlist re-validated BEFORE blob persist.
            String resolvedContentType = resolveAllowedContentType(declaredContentType, fileName);
            if (resolvedContentType == null) {
                log.warn("Upload rejected (unsupported MIME): name={} declared={}", fileName, declaredContentType);
                notifications.create(messages.getMessage("chatView.attachments.upload.unsupportedType"))
                        .withThemeVariant(NotificationVariant.LUMO_WARNING)
                        .show();
                return;
            }

            FileRef fileRef = saveBlob(fileName, tempFile);
            if (fileRef == null) {
                notifications.create(messages.getMessage("chatView.attachments.upload.failed"))
                        .withThemeVariant(NotificationVariant.LUMO_ERROR)
                        .show();
                return;
            }

            AiConversation conversation = dataManager.load(AiConversation.class).id(convId).one();

            AiTaskFile row = metadataApi.create(AiTaskFile.class);
            row.setConversation(conversation);
            row.setStorageRef(fileRef);
            row.setFilename(fileName);
            row.setContentType(resolvedContentType);
            row.setSizeBytes(sizeBytes);
            row.setUserUsername(currentAuthentication.getUser().getUsername());
            // Phase 13.1 LIFE-01: ttlSeconds is the operator-facing TTL knob; sentinel
            // -1 disables purge but the upload row still needs a deterministic expiresAt
            // value, so fall back to the 24h default in that case.
            long ttlSeconds = taskFileProperties.getTtlSeconds();
            if (ttlSeconds == -1L) {
                ttlSeconds = 86_400L;
            }
            row.setExpiresAt(OffsetDateTime.now().plusSeconds(ttlSeconds));

            AiTaskFile saved = dataManager.save(row);
            renderChip(saved);
        } catch (RuntimeException ex) {
            log.warn("Task-file upload failed for {}", fileName, ex);
            notifications.create(messages.getMessage("chatView.attachments.upload.failed"))
                    .withThemeVariant(NotificationVariant.LUMO_ERROR)
                    .show();
        } finally {
            tryDeleteTemp(tempFile);
        }
    }

    private FileRef saveBlob(String fileName, Path tempFile) {
        FileStorage storage = fileStorageLocator.getDefault();
        try (InputStream in = Files.newInputStream(tempFile)) {
            return storage.saveStream(fileName, in);
        } catch (IOException io) {
            log.warn("Failed to stream upload {} into FileStorage", fileName, io);
            return null;
        } catch (RuntimeException rt) {
            log.warn("FileStorage rejected upload {}", fileName, rt);
            return null;
        }
    }

    /**
     * Returns the canonical allowed MIME type for the upload, or {@code null} if neither
     * the declared content type nor the filename extension match the allowlist. Trims any
     * {@code charset=} parameters before the allowlist lookup.
     */
    private String resolveAllowedContentType(String declaredContentType, String fileName) {
        if (declaredContentType != null && !declaredContentType.isBlank()) {
            String normalized = declaredContentType.toLowerCase(Locale.ROOT);
            int semi = normalized.indexOf(';');
            if (semi >= 0) {
                normalized = normalized.substring(0, semi).trim();
            }
            if (ALLOWED_CONTENT_TYPES.contains(normalized)) {
                return normalized;
            }
        }
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : EXTENSION_TO_CONTENT_TYPE.entrySet()) {
            if (lower.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** REVIEWS HIGH-5 hook for the verifier grep gate. Server-side MIME validation. */
    @SuppressWarnings("unused")
    private boolean isAllowedMimeType(String contentType, String fileName) {
        return resolveAllowedContentType(contentType, fileName) != null;
    }

    private void tryDeleteTemp(Path tempFile) {
        if (tempFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
            // Best-effort; OS will clean up the temp directory eventually.
        }
    }

    /**
     * Ensures a conversation id is available for the upload row's required FK. If the user
     * uploads BEFORE typing their first message, eagerly create the conversation so the row
     * has a stable conversation FK; the same id is reused when the user later submits.
     */
    private UUID ensureConversationIdForUpload() {
        if (conversationId != null) {
            return conversationId;
        }
        try {
            String username = currentAuthentication.getUser().getUsername();
            AiConversation conversation = conversationGateway.loadOrCreate(username, null, null);
            UUID resolved = conversation.getId();
            if (resolved == null) {
                return null;
            }
            conversationId = resolved;
            updateConversationTitle(conversation.getTitle());
            updateTitleEditState();
            updateSessionConversationId(resolved);
            return resolved;
        } catch (RuntimeException ex) {
            log.warn("Failed to create conversation for upload", ex);
            return null;
        }
    }

    private void renderChip(AiTaskFile saved) {
        Span label = new Span(saved.getFilename());
        label.addClassName("ai-agent-chat-panel__chip-label");

        JmixButton removeBtn = uiComponents.create(JmixButton.class);
        removeBtn.setIcon(VaadinIcon.CLOSE_SMALL.create());
        removeBtn.addClassName("ai-agent-chat-panel__chip-remove");
        removeBtn.setAriaLabel(messages.getMessage("chatView.attachments.chip.removeAria"));
        removeBtn.addThemeName("tertiary-inline");
        removeBtn.addThemeName("small");
        removeBtn.addThemeName("icon");

        HorizontalLayout chipBox = new HorizontalLayout(label, removeBtn);
        chipBox.addClassName("ai-agent-chat-panel__chip");
        chipBox.setSpacing(false);
        chipBox.setPadding(false);
        chipBox.setAlignItems(HorizontalLayout.Alignment.CENTER);

        UUID rowId = saved.getId();
        FileRef ref = saved.getStorageRef();
        removeBtn.addClickListener(click -> handleChipRemove(rowId, ref, chipBox));

        chipStrip.add(chipBox);
        chipById.put(rowId, chipBox);
        refreshAttachmentsVisibility();
    }

    /**
     * Removes the row first (host policy gate via DataManager), then the blob (best-effort —
     * if the row delete fails the blob stays in place for the cleanup-job to reap on TTL,
     * preventing dangling row pointers; if the blob delete fails we still treat the chip as
     * gone since the cleanup-job will sweep it later).
     */
    private void handleChipRemove(UUID rowId, FileRef ref, Component chipBox) {
        try {
            AiTaskFile row = dataManager.load(AiTaskFile.class).id(rowId).optional().orElse(null);
            if (row != null) {
                dataManager.remove(row);
            }
            if (ref != null) {
                try {
                    FileStorage storage = fileStorageLocator.getByName(ref.getStorageName());
                    storage.removeFile(ref);
                } catch (RuntimeException blobEx) {
                    log.warn("Failed to remove blob for AiTaskFile {} (row already gone; cleanup-job will retry)",
                            rowId, blobEx);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to remove AiTaskFile {}", rowId, ex);
            notifications.create(messages.getMessage("chatView.attachments.upload.failed"))
                    .withThemeVariant(NotificationVariant.LUMO_WARNING)
                    .show();
            return;
        }
        chipStrip.remove(chipBox);
        chipById.remove(rowId);
        refreshAttachmentsVisibility();
    }

    private void refreshAttachmentsVisibility() {
        attachmentsPanel.setVisible(!chipById.isEmpty());
    }
}
