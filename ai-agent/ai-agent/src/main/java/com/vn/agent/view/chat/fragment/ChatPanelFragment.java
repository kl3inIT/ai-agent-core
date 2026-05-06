package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.notification.NotificationVariant;
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
import com.vn.agent.view.chat.MarkdownRenderer;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.app.inputdialog.DialogActions;
import io.jmix.flowui.app.inputdialog.DialogOutcome;
import io.jmix.flowui.app.inputdialog.InputParameter;
import io.jmix.flowui.component.gridlayout.GridLayout;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.component.validation.ValidationErrors;
import io.jmix.flowui.fragment.Fragment;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.fragment.FragmentOwner;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
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
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** D-29 substrate: Phase 13.1 reshape — left chat column renders mixed bubbles
 *  ({@link MessageBubbleComponent}) for USER/ASSISTANT and {@code <vaadin-message
 *  class="attachment-event">} sibling rows for NOTICE (Pitfall 7 Option A). The right
 *  pane is data-loader driven via {@code taskFilesDl}; empty-state visibility toggles
 *  on {@link CollectionLoader.PostLoadEvent}. D-03 per-event ui.access; D-04 Stop via
 *  CancellationRegistry.cancel; Pitfall #8 dispose-on-detach. Public API for ChatView:
 *  setConversationId / hasMessages / isStreaming / startNewChat. */
@FragmentDescriptor("chat-panel-fragment.xml")
public class ChatPanelFragment extends Fragment<VerticalLayout> {

    private static final Logger log = LoggerFactory.getLogger(ChatPanelFragment.class);

    @ViewComponent private JmixButton stopButton;
    @ViewComponent private JmixButton newChatButton;
    @ViewComponent private H3 conversationTitle;
    @ViewComponent private JmixButton editConversationTitleButton;
    @ViewComponent private VerticalLayout messageListSlot;
    @ViewComponent private VerticalLayout messageInputSlot;

    // Phase 13.1 REQ-7 / Pitfall 6 — slot id contract preserved; field type stays
    // VerticalLayout exactly because Phase 12 ChatSurfaceMounter binds by this type.
    @ViewComponent private VerticalLayout attachmentsPanel;
    // Phase 13.1 UI-01 — right-pane data container + loader for the card grid.
    @ViewComponent private CollectionContainer<AiTaskFile> taskFilesDc;
    @ViewComponent private CollectionLoader<AiTaskFile> taskFilesDl;
    @ViewComponent private VerticalLayout attachmentsEmptyState;
    @ViewComponent private GridLayout attachmentsGridLayout;
    @ViewComponent private JmixUpload taskFileUpload;

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
    // Phase 13.1 — server-side markdown for mixed-bubble rendering (Option A).
    @Autowired private MarkdownRenderer markdownRenderer;

    private MessageInput messageInput;
    private ProgressBar streamProgressBar;
    private final Map<String, String> labels = new HashMap<>();

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
    private MessageBubbleComponent botBubble;
    private volatile UI ownerUi;
    private Registration conversationIdStateRegistration;
    // Phase 13.1 — message-count tracker replaces the old item buffer so hasMessages()
    // stays a cheap O(1) probe without scanning messageListSlot children.
    private int messageCount;

    @Subscribe
    public void onReady(final ReadyEvent event) {
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

        // Phase 13.1 UI-01 — right-pane upload handler + loader binding.
        initAttachmentsAndUpload();
        if (conversationId != null) {
            taskFilesDl.setParameter("conversationId", conversationId);
            taskFilesDl.load();
        }
        // Initial empty-state toggle (loader may already be primed by setConversationIdInternal).
        refreshTaskFiles();
    }

    /**
     * Phase 13.1 UI-01 — server-side validation + UploadHandler.toFile registration on the
     * reshaped {@code taskFileUpload} element (right-pane). The handler body (file save →
     * AiTaskFile insert → handleUploadedFile callback) is unchanged from Phase 13 except
     * the id rename and the chip-strip removal.
     *
     * <p>REVIEWS HIGH-4 carry-over: Vaadin 24.8 marks the legacy Upload receiver API
     * forRemoval; the {@code UploadHandler.toFile} path is the canonical Jmix 2.8 contract
     * per project memory feedback_jmix_upload_receiver_deprecated.</p>
     */
    private void initAttachmentsAndUpload() {
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
        taskFileUpload.setMaxFileSize((int) Math.min(taskFileProperties.getMaxFileSizeBytes(), Integer.MAX_VALUE));
        taskFileUpload.setUploadHandler(UploadHandler.toFile(
                (uploadMetadata, stagedFile) -> handleUploadedFile(
                        uploadMetadata.fileName(),
                        uploadMetadata.contentType(),
                        uploadMetadata.contentLength(),
                        stagedFile.toPath()),
                fileMetadata -> uploadTempDir.resolve(
                        UUID.randomUUID() + "-" + safeFileName(fileMetadata.fileName())).toFile()));
    }

    /**
     * Phase 13.1 UI-01 — empty-state visibility toggle, fired by Jmix's typed
     * {@link CollectionLoader.PostLoadEvent}. Matches project memory
     * {@code feedback_jmix_data_loader_events}: data-loader events use
     * {@code @Subscribe(id="...", target=Target.DATA_LOADER)} with typed event records,
     * not {@code loader.addPostLoadListener(...)}.
     */
    @Subscribe(id = "taskFilesDl", target = Target.DATA_LOADER)
    public void onTaskFilesPostLoad(final CollectionLoader.PostLoadEvent<AiTaskFile> event) {
        refreshTaskFiles();
    }

    private void refreshTaskFiles() {
        if (taskFilesDc == null || attachmentsEmptyState == null || attachmentsGridLayout == null) {
            return;
        }
        boolean empty = taskFilesDc.getItems().isEmpty();
        attachmentsEmptyState.setVisible(empty);
        attachmentsGridLayout.setVisible(!empty);
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
        clearMessageList();
        if (cid == null) {
            updateConversationTitle(null);
            updateTitleEditState();
            // Reset right-pane loader so empty-state shows when no conversation is active.
            if (taskFilesDl != null) {
                taskFilesDl.removeParameter("conversationId");
            }
            if (taskFilesDc != null) {
                taskFilesDc.setItems(List.of());
            }
            refreshTaskFiles();
            return;
        }
        // Ownership check (D-09) — foreign / missing ids throw ConversationNotFoundException.
        AiConversation conversation =
                conversationGateway.loadOrCreate(currentAuthentication.getUser().getUsername(), cid, null);
        updateConversationTitle(conversation.getTitle());
        updateTitleEditState();

        // Phase 13.1 UI-01 — re-bind right-pane loader on conversation switch.
        if (taskFilesDl != null) {
            taskFilesDl.setParameter("conversationId", cid);
            taskFilesDl.load();
        }

        List<AiMessage> history = dataManager.load(AiMessage.class)
                .query("select m from ai_AiMessage m where m.conversation.id = :cid " +
                       "order by m.createdDate asc, m.seq asc")
                .parameter("cid", cid)
                .list();

        // Phase 13.1 UX-01 — dispatch by role:
        //   USER / ASSISTANT  → MessageBubbleComponent (Option A migration; substrate is now
        //                        a VerticalLayout of Composites + sibling vaadin-message rows)
        //   NOTICE            → <vaadin-message class="attachment-event"> sibling element
        //   SYSTEM / TOOL     → skipped (replay shows user-visible turns only)
        for (AiMessage m : history) {
            AiMessageRole role = m.getRole();
            if (role == AiMessageRole.USER) {
                appendBubble(MessageBubbleComponent.Role.USER, m.getContent());
            } else if (role == AiMessageRole.ASSISTANT) {
                appendBubble(MessageBubbleComponent.Role.ASSISTANT, m.getContent());
            } else if (role == AiMessageRole.NOTICE) {
                appendNoticeRow(m.getContent());
            }
        }
    }

    public UUID getConversationId() { return conversationId; }

    public boolean hasMessages() { return messageCount > 0; }

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

        appendBubble(MessageBubbleComponent.Role.USER, text);
        botBubble = appendBubble(MessageBubbleComponent.Role.ASSISTANT, "");

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
                    if (evt instanceof StreamingEvent.Final f) {
                        if (activeRunId == null) {
                            activeRunId = f.runId();
                        }
                        if (conversationId == null && f.conversationId() != null) {
                            conversationId = f.conversationId();
                            updateSessionConversationId(conversationId);
                        }
                        // Phase 13.1 D-D1 — streaming-path budget-exceeded toast (CONTEXT D-D1
                        // demands the toast on BOTH transports; the streaming Final event
                        // carries the same flag as ChatResponseDto.budgetExceeded per Plan 03
                        // Option A).
                        if (f.budgetExceeded()) {
                            showBudgetExceededToast();
                        }
                    }
                    String md = StreamEventRenderer.renderStreamEvent(evt, labels, citationState);
                    if (md.isEmpty()) return;
                    accessUi(() -> {
                        if (botBubble != null) {
                            botBubble.appendMarkdown(md);
                        }
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

    /**
     * Phase 13.1 D-D1 — blocking-path budget-exceeded consumer (CONTEXT D-D1: the toast must
     * surface on the {@code .ask(...)} blocking transport AS WELL AS the streaming
     * {@code Final} event consumer above). Currently invoked indirectly: the streaming
     * fallback inside {@code DefaultChatServiceImpl.stream()} catches
     * {@code UnsupportedOperationException} and routes through {@code executeBlockingTurn},
     * propagating the same budgetExceeded flag back through the streaming Final event. This
     * accessor is the test-visible blocking-path consumer surface; it is wired by integration
     * tests (Plan 13.1-06 SurfaceMountingTest) and by future hosts that consume {@code .ask}
     * directly.
     */
    void onBlockingResponse(com.vn.agent.orchestration.ChatResponseDto response) {
        if (response == null) {
            return;
        }
        if (response.budgetExceeded()) {
            showBudgetExceededToast();
        }
    }

    private void showBudgetExceededToast() {
        // Per project memory feedback_jmix_messages_over_spring: keep keys in the root
        // (com.vn.agent) bundle and resolve via the Class-less Messages.getMessage(key)
        // form. The class-scoped form would derive the group from the fragment's package
        // and miss the root-bundle entry.
        accessUi(() -> notifications.create(
                        messages.getMessage("chatView.attachments.budgetExceeded"))
                .withThemeVariant(NotificationVariant.LUMO_WARNING)
                .show());
    }

    private void finishStreamInternal() {
        setStreamingUiState(false);
        if (activeRunId != null) cancellationRegistry.clearDisposable(activeRunId);
        activeRunId = null;
        activeStream = null;
        botBubble = null;
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
        if (taskFilesDl != null) {
            taskFilesDl.setParameter("conversationId", resolved);
            taskFilesDl.load();
        }
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

    /**
     * Phase 13.1 UX-01 — append a USER/ASSISTANT bubble to the message list slot.
     *
     * <p>Returns the created bubble so the caller can hold a reference for streaming
     * appends (the assistant bubble accumulates token chunks via
     * {@link MessageBubbleComponent#appendMarkdown(String)}).</p>
     */
    private MessageBubbleComponent appendBubble(MessageBubbleComponent.Role role, String content) {
        MessageBubbleComponent bubble = new MessageBubbleComponent(role, markdownRenderer);
        if (content != null && !content.isEmpty()) {
            bubble.setMarkdown(content);
        }
        messageListSlot.add(bubble);
        messageCount++;
        return bubble;
    }

    /**
     * Phase 13.1 UX-01 — append a NOTICE divider as a {@code <vaadin-message>} sibling
     * element with class {@code attachment-event}. Vaadin escapes the {@code text}
     * property by default (T-13.1-17 mitigation), so the formatted message bundle text
     * cannot inject HTML even if a filename contains script tags.
     */
    private void appendNoticeRow(String text) {
        if (text == null) {
            text = "";
        }
        com.vaadin.flow.dom.Element msg = new com.vaadin.flow.dom.Element("vaadin-message");
        msg.getClassList().add("attachment-event");
        msg.setProperty("text", text);
        messageListSlot.getElement().appendChild(msg);
        messageCount++;
    }

    private void clearMessageList() {
        if (messageListSlot != null) {
            messageListSlot.removeAll();
            // removeAll() only removes Component children; vaadin-message siblings added via
            // raw Element APIs are NOT Components, so wipe the underlying element children
            // explicitly. Element.removeAllChildren() is safe to call on an already-empty slot.
            messageListSlot.getElement().removeAllChildren();
        }
        botBubble = null;
        messageCount = 0;
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
        if (messageListSlot == null) {
            return;
        }
        messageListSlot.getElement().executeJs(
                "setTimeout(() => { this.scrollTop = this.scrollHeight; }, 50);");
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
    @Subscribe("taskFileUpload")
    public void onUploadFileRejected(final FileRejectedEvent event) {
        log.debug("Upload rejected client-side: {}", event.getErrorMessage());
        notifications.create(messages.getMessage("chatView.attachments.upload.tooLarge"))
                .withThemeVariant(NotificationVariant.LUMO_WARNING)
                .show();
    }

    /**
     * Handles one accepted upload (REVIEWS HIGH-4 + HIGH-5 + Phase 13.1 UX-01).
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
     *   <li><b>Phase 13.1 UX-01</b> — persist a NOTICE {@link AiMessage} attributed to the
     *       uploading user and append a {@code <vaadin-message class="attachment-event">}
     *       sibling row to {@code messageListSlot}. Failure is logged and the upload still
     *       succeeds (per CONTEXT integration-points "log-and-continue").</li>
     *   <li>Reload {@code taskFilesDl} so the right-pane card grid + empty-state toggle
     *       reflect the new row.</li>
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

            // Pitfall 2 — load the conversation fresh AFTER ensureConversationIdForUpload so
            // both the AiTaskFile row AND the NOTICE AiMessage row reference a managed entity
            // pointing at the just-created conversation (avoids a stale managed reference if
            // ensureConversationIdForUpload created the conversation this turn).
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

            // Phase 13.1 UX-01 — bilingual NOTICE row. Log-and-continue per CONTEXT
            // integration-points: a NOTICE-write failure does NOT roll back the upload.
            try {
                AiMessage notice = metadataApi.create(AiMessage.class);
                notice.setConversation(conversation);
                notice.setRole(AiMessageRole.NOTICE);
                // Root-bundle key (memory feedback_jmix_messages_over_spring) — use the
                // group-form formatMessage so the resolver hits the same com.vn.agent bundle
                // as the rest of the chat view.
                notice.setContent(messages.formatMessage("com.vn.agent",
                        "chatView.attachments.notice",
                        currentAuthentication.getUser().getUsername(),
                        saved.getFilename()));
                notice.setCreatedDate(OffsetDateTime.now());
                notice.setSeq(nextSeq(conversation.getId()));
                AiMessage savedNotice = dataManager.save(notice);
                final String noticeText = savedNotice.getContent();
                accessUi(() -> appendNoticeRow(noticeText));
            } catch (RuntimeException noticeEx) {
                log.warn("Failed to write NOTICE AiMessage for upload {}", fileName, noticeEx);
            }

            // Refresh the right-pane grid + emptyState toggle via PostLoadEvent.
            if (taskFilesDl != null) {
                accessUi(() -> taskFilesDl.load());
            }
        } catch (RuntimeException ex) {
            log.warn("Task-file upload failed for {}", fileName, ex);
            notifications.create(messages.getMessage("chatView.attachments.upload.failed"))
                    .withThemeVariant(NotificationVariant.LUMO_ERROR)
                    .show();
        } finally {
            tryDeleteTemp(tempFile);
        }
    }

    /**
     * Computes the next {@code seq} for a NOTICE row in the given conversation by reading
     * {@code max(m.seq)} via {@link DataManager#loadValue}. Per project memory
     * {@code feedback_jmix_loadvalue_store}, raw-JPQL {@code loadValue} on agentstore
     * entities does NOT auto-resolve the store from the entity name — must call
     * {@code .store("agentstore")} explicitly.
     *
     * @param conversationId conversation FK to scope the max-seq query
     * @return {@code maxSeq + 1}, or {@code 0} when the conversation has no messages yet
     */
    private int nextSeq(UUID conversationId) {
        Integer maxSeq = dataManager.loadValue(
                        "select max(m.seq) from ai_AiMessage m where m.conversation.id = :cid",
                        Integer.class)
                .store("agentstore")
                .parameter("cid", conversationId)
                .optional()
                .orElse(null);
        return maxSeq == null ? 0 : maxSeq + 1;
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
            if (taskFilesDl != null) {
                taskFilesDl.setParameter("conversationId", resolved);
                taskFilesDl.load();
            }
            return resolved;
        } catch (RuntimeException ex) {
            log.warn("Failed to create conversation for upload", ex);
            return null;
        }
    }
}
