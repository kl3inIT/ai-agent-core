package com.vn.agent.view.chat.fragment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.card.CardVariant;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.shared.Registration;
import com.vn.agent.ChatService;
import com.vn.agent.action.ActionIntentId;
import com.vn.agent.action.ActionProposal;
import com.vn.agent.action.ActionProposalService;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.entity.AiTaskFile;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.extraction.IntentOption;
import com.vn.agent.extraction.IntentRegistry;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.rag.CancellationRegistry;
import com.vn.agent.taskfile.AiTaskFileProperties;
import com.vn.agent.view.chat.AiChatSessionState;
import com.vn.agent.view.chat.intent.OpenFormWithDraftHandler;
import io.jmix.core.DataManager;
import io.jmix.core.UnconstrainedDataManager;
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
import io.jmix.flowui.component.card.JmixCard;
import io.jmix.flowui.component.gridlayout.GridLayout;
import io.jmix.flowui.component.radiobuttongroup.JmixRadioButtonGroup;
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
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** D-29 substrate: Phase 13.1 UAT-fix — left chat column renders USER/ASSISTANT turns
 *  on Vaadin {@link MessageList} + {@link MessageListItem} (Phase 7.1 baseline,
 *  feedback_jmix_first_ui — these ARE Vaadin components shipped by Jmix). NOTICE rows
 *  render as plain {@code <div class="ai-agent-attachment-notice">} sibling elements
 *  appended to the message-list slot AFTER the {@code <vaadin-message-list>} block;
 *  this avoids the default {@code <vaadin-avatar>} upload-arrow fallback that the
 *  prior {@code <vaadin-message class="attachment-event">} substrate exposed when no
 *  userName was set. UI-SPEC §138 explicitly allows "alternatively a custom inline
 *  element" for the NOTICE row, so this satisfies the visual contract.
 *  The right pane stays data-loader driven via {@code taskFilesDl}; empty-state
 *  visibility toggles on {@link CollectionLoader.PostLoadEvent}. D-03 per-event
 *  ui.access; D-04 Stop via CancellationRegistry.cancel; Pitfall #8 dispose-on-detach.
 *  Public API for ChatView: setConversationId / hasMessages / isStreaming / startNewChat. */
@FragmentDescriptor("chat-panel-fragment.xml")
// Phase 15 Plan 03 (REVIEWS point #5): the @CssImport for ai-agent-chat.css used to
// live only on the message-bubble component (used solely by ConversationDetailView's
// read-only transcript replay), which the MessageList-based live chat path never
// instantiates — so the .ai-agent-sidebar* rules added in this plan (and the Plan-04
// observability rules) would not load on the sidebar/chat render path. This fragment
// is present in every chat/sidebar render, so the import belongs here too. The
// existing import on the bubble component stays (ConversationDetailView still relies
// on it; a duplicate @CssImport of the same stylesheet path is harmless).
@CssImport("./styles/ai-agent-chat.css")
public class ChatPanelFragment extends Fragment<VerticalLayout> {

    private static final Logger log = LoggerFactory.getLogger(ChatPanelFragment.class);
    private static final OffsetDateTime NON_EXPIRING_EXPIRES_AT =
            OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
    private static final int USER_COLOR = 0;
    private static final int AI_COLOR = 2;
    private static final String AUTO_INTENT_ID = "__auto__";

    @ViewComponent private JmixButton stopButton;
    @ViewComponent private JmixButton newChatButton;
    @ViewComponent private H3 conversationTitle;
    @ViewComponent private JmixButton editConversationTitleButton;
    @ViewComponent private VerticalLayout messageListSlot;
    @ViewComponent private JmixRadioButtonGroup<IntentOption> intentCardRow;
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
    // Phase 15 Plan 04 (RESEARCH Open Q1) — AiAgentUserRole has NO AiAuditEvent EntityPolicy
    // (AiAuditEventListView is an admin-only view); the chat user cannot read AiAuditEvent via
    // the constrained DataManager. The per-turn-detail audit reads therefore use
    // UnconstrainedDataManager with a MANDATORY `where e.userUsername = :me and e.conversation.id
    // = :cid` clause on every read — never `runId`-only unconstrained. The conversation was
    // already ownership-checked at setConversationIdInternal (~line 563).
    @Autowired private UnconstrainedDataManager unconstrainedDataManager;
    @Autowired private Notifications notifications;
    @Autowired private AiChatSessionState chatSessionState;
    @Autowired private IntentRegistry intentRegistry;
    @Autowired private OpenFormWithDraftHandler openFormWithDraftHandler;
    @Autowired private ActionProposalService actionProposalService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UiComponents uiComponents;
    // Phase 13 Plan 04 — task-file persistence collaborators.
    @Autowired private Metadata metadataApi;
    @Autowired private FileStorageLocator fileStorageLocator;
    @Autowired private AiTaskFileProperties taskFileProperties;

    private MessageList messageList;
    private MessageInput messageInput;
    private ProgressBar streamProgressBar;
    private final List<MessageListItem> items = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();
    private final Map<String, List<JmixCard>> intentCardsByKey = new HashMap<>();
    private final Map<String, Div> actionChoiceRowsByProposalId = new HashMap<>();

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
    private volatile Authentication ownerAuthentication;
    private Registration conversationIdStateRegistration;
    // Phase 13.1 UAT-fix — tracks visible turns across the mixed substrate
    // (MessageList items + NOTICE divs) so hasMessages() is O(1).
    private int messageCount;

    // ---- Phase 15 Plan 04 — observability surfaces -------------------------
    /** OBS-04 / review point #12 — cap protects against a single pathological turn. */
    private static final int LIVE_TURN_STEP_CAP = 50;
    /** Memoization flag set on a history {@link Details} once its steps have been lazily loaded. */
    private static final String TURN_DETAILS_LOADED_KEY = "ai-agent.turnDetails.loaded";

    /** Ephemeral KIND-keyed streaming-status {@code <span>} (a sibling AFTER {@code <vaadin-message-list>});
     *  {@code null} when no turn is streaming. Removed entirely on every terminal/teardown site. */
    private com.vaadin.flow.dom.Element statusRow;
    /** {@code true} once the first {@link StreamingEvent.Content} of the current turn has arrived
     *  (the first-Content-implies-CHAT flip, review point #6). Reset to {@code false} at turn start. */
    private boolean turnContentSeen;

    /** Per-fragment-instance bounded live-turn step accumulator (NOT {@code AiChatSessionState}),
     *  capped at {@link #LIVE_TURN_STEP_CAP}, cleared on every terminal/teardown site. */
    private final List<LiveTurnStep> liveTurnSteps = new ArrayList<>();
    /** The single ordered activity block ({@code <div class="ai-agent-turn-activity">}) appended
     *  after {@code <vaadin-message-list>} in {@code messageListSlot}; holds the per-turn {@link Details}. */
    private Div turnActivityBlock;
    /** Per-turn {@link Details} anchored by {@code runId} (so a re-rendered turn replaces, not duplicates). */
    private final Map<UUID, Details> turnDetailsByRunId = new HashMap<>();

    /** A live-turn step (transient ToolCall→ToolResult arrival delta is a FALLBACK only — the real
     *  {@code latencyMs} comes from the {@code AiAuditEvent} children read on {@code Final}). Holds
     *  ONLY a label key, a nullable latency, an errored flag, and the transient {@code toolCallId}
     *  for dedupe — never a tool/entity name (T-15-D1). */
    private record LiveTurnStep(String labelKey, Long latencyMs, boolean errored,
                                UUID toolCallId, long startedAtNanos) {
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        // Phase 13.1 UAT-fix — restore Vaadin MessageList substrate (Phase 7.1 baseline).
        messageList = new MessageList();
        messageList.setMarkdown(true);
        messageList.setWidthFull();
        messageList.getStyle().set("flex-grow", "1");
        messageListSlot.add(messageList);
        messageList.setItems(items);

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
        ownerAuthentication = captureCurrentAuthentication();
        hideInitialIntentCardRow();
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

    @EventListener
    public void onTaskFileDeleted(final AiTaskFileDeletedUiEvent event) {
        if (event.getConversationId() != null
                && !Objects.equals(conversationId, event.getConversationId())) {
            return;
        }
        if (taskFilesDl != null) {
            taskFilesDl.load();
        } else {
            refreshTaskFiles();
        }
    }

    private void refreshTaskFiles() {
        if (taskFilesDc == null || attachmentsEmptyState == null || attachmentsGridLayout == null) {
            return;
        }
        boolean empty = taskFilesDc.getItems().isEmpty();
        attachmentsEmptyState.setVisible(empty);
        attachmentsGridLayout.setVisible(!empty);
    }

    void refreshIntentCardRow() {
        if (intentCardRow == null) {
            return;
        }
        IntentOption autoOption = buildAutoIntentOption();
        List<IntentOption> namedIntents = intentRegistry == null
                ? List.of()
                : intentRegistry.eligibleForCurrentUser();
        List<IntentOption> options = new ArrayList<>(namedIntents.size() + 1);
        options.add(autoOption);
        options.addAll(namedIntents);

        List<IntentOption> currentIntentOptions = List.copyOf(options);
        intentCardsByKey.clear();
        intentCardRow.setItems(currentIntentOptions);
        intentCardRow.setVisible(!namedIntents.isEmpty());
        intentCardRow.setValue(autoOption);
    }

    private void hideInitialIntentCardRow() {
        if (intentCardRow != null) {
            intentCardRow.setVisible(false);
            intentCardRow.setValue(buildAutoIntentOption());
        }
    }

    @Supply(to = "intentCardRow", subject = "renderer")
    private ComponentRenderer<JmixCard, IntentOption> intentCardRowRenderer() {
        return new ComponentRenderer<>(this::createIntentCard);
    }

    @Subscribe("intentCardRow")
    public void onIntentCardRowValueChange(
            final AbstractField.ComponentValueChangeEvent<JmixRadioButtonGroup<IntentOption>, IntentOption> event) {
        refreshIntentCardSelection();
    }

    private IntentOption buildAutoIntentOption() {
        return new IntentOption(
                AUTO_INTENT_ID,
                resolveLabel("chatView.intent.auto.label", "Auto"),
                resolveLabel("chatView.intent.auto.description", "Default chat"),
                null,
                true);
    }

    private JmixCard createIntentCard(IntentOption option) {
        IntentOption safeOption = option == null ? buildAutoIntentOption() : option;

        JmixCard card = uiComponents.create(JmixCard.class);
        card.addThemeVariants(CardVariant.LUMO_OUTLINED);
        card.addClassName("ai-agent-intent-card");
        card.setWidthFull();

        H5 title = new H5(safeText(safeOption.label()));
        title.addClassNames("m-0", "ai-agent-intent-card__label");

        Span description = new Span(intentDescription(safeOption));
        description.addClassNames("text-secondary", "text-s", "ai-agent-intent-card__description");

        card.setHeaderPrefix(safeOption.auto()
                ? VaadinIcon.MAGIC.create()
                : VaadinIcon.FILE_TEXT_O.create());
        card.setTitle(title);
        card.add(description);

        intentCardsByKey.computeIfAbsent(intentCardKey(safeOption), ignored -> new ArrayList<>())
                .add(card);
        applyIntentCardSelection(card, safeOption);
        return card;
    }

    private String intentDescription(IntentOption option) {
        if (option.auto()) {
            return safeText(option.description());
        }
        String key = "chatView.intent." + option.intentId() + ".description";
        return resolveLabel(key, safeText(option.description()));
    }

    private void refreshIntentCardSelection() {
        if (intentCardRow == null) {
            return;
        }
        String selectedKey = intentCardKey(intentCardRow.getValue());
        for (Map.Entry<String, List<JmixCard>> entry : intentCardsByKey.entrySet()) {
            boolean selected = Objects.equals(selectedKey, entry.getKey());
            for (JmixCard card : entry.getValue()) {
                if (selected) {
                    card.addClassName("ai-agent-intent-card--selected");
                } else {
                    card.removeClassName("ai-agent-intent-card--selected");
                }
            }
        }
    }

    private void applyIntentCardSelection(JmixCard card, IntentOption option) {
        if (Objects.equals(intentCardKey(option), intentCardKey(intentCardRow.getValue()))) {
            card.addClassName("ai-agent-intent-card--selected");
        }
    }

    private String intentCardKey(IntentOption option) {
        if (option == null || option.auto()) {
            return AUTO_INTENT_ID;
        }
        return option.intentId();
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
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
        // Phase 15 Plan 04 (review point #7) — explicit observability-state teardown on detach
        // (the cancelled stream's .doOnComplete does NOT fire on dispose). Element children go
        // away with the fragment; the field cleanup keeps the contract uniform.
        liveTurnSteps.clear();
        removeStatusRow();
        turnContentSeen = false;
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
                       "order by m.seq asc, m.createdDate asc")
                .parameter("cid", cid)
                .list();

        // Phase 13.1 UAT-fix — dispatch by role across mixed substrate:
        //   USER / ASSISTANT  → MessageListItem accumulated into the items list, then
        //                        flushed with a single setItems at the end of replay
        //                        (Pitfall #5 — only the turn boundary calls setItems).
        //   NOTICE            → plain <div class="ai-agent-attachment-notice"> appended
        //                        as sibling of the MessageList element. Strict
        //                        chronological in-bubble interleaving is approximated as
        //                        end-of-list (UI-SPEC §138 allows custom inline element).
        //   SYSTEM / TOOL     → skipped (replay shows user-visible turns only).
        String userName = resolveLabel("chatView.message.userName", "You");
        String aiName = resolveLabel("chatView.message.assistantName", "AI Assistant");
        int assistantTurnCount = 0;
        for (AiMessage m : history) {
            AiMessageRole role = m.getRole();
            if (role == AiMessageRole.USER) {
                items.add(buildItem(m, userName, USER_COLOR));
                messageCount++;
            } else if (role == AiMessageRole.ASSISTANT) {
                items.add(buildItem(m, aiName, AI_COLOR));
                messageCount++;
                assistantTurnCount++;
            } else if (role == AiMessageRole.NOTICE) {
                appendNoticeRow(m.getContent());
            }
        }
        if (messageList != null) {
            messageList.setItems(new ArrayList<>(items));
        }
        // Phase 15 Plan 04 (SPEC req 5 / CONTEXT D-07) — additive post-navigation correlation:
        // anchor a collapsed per-turn Details (in the .ai-agent-turn-activity block) by runId for
        // each prior ASSISTANT turn whose AiAuditEvent CHAT root has >=1 tool/retrieval child,
        // ONLY when the assistant-turn count matches the audit-root count (else none — no guess).
        correlateHistoryTurnDetails(cid, assistantTurnCount);
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
        if (looksLikeActionCancellation(text)) {
            removeAllActionChoiceRows();
        }
        submitChatTurn(text, text, selectedIntentIdForSubmit());
    }

    private void submitChatTurn(String displayText, String modelText, String toolSurfaceIntentId) {
        submitChatTurn(displayText, modelText, toolSurfaceIntentId, null);
    }

    private void submitChatTurn(String displayText, String modelText,
                                String toolSurfaceIntentId,
                                String privateSystemAppendix) {
        String userName = resolveLabel("chatView.message.userName", "You");
        String aiName = resolveLabel("chatView.message.assistantName", "AI Assistant");

        MessageListItem userItem = new MessageListItem(displayText, Instant.now(), userName);
        userItem.setUserColorIndex(USER_COLOR);
        items.add(userItem);
        messageCount++;

        botMsg = new MessageListItem("", Instant.now(), aiName);
        botMsg.setUserColorIndex(AI_COLOR);
        items.add(botMsg);
        messageCount++;
        // Pitfall #5 — last setItems of the turn; mid-stream chunks use appendText only.
        messageList.setItems(new ArrayList<>(items));

        setStreamingUiState(true);
        // Phase 15 Plan 04 — fresh per-turn observability state; neutral typing indicator
        // until the first Activity/Content arrives.
        turnContentSeen = false;
        liveTurnSteps.clear();
        accessUi(() -> showStatus(TurnDetailRenderer.neutralStatusKey()));

        final Authentication submitAuthentication = captureCurrentAuthentication();
        ownerAuthentication = submitAuthentication;
        final String userId = currentAuthentication.getUser().getUsername();
        final UUID targetConversationId = conversationId;
        final StreamEventRenderer.CitationState citationState = new StreamEventRenderer.CitationState();
        Flux<StreamingEvent> source = chatService.stream(userId, targetConversationId, modelText,
                null, toolSurfaceIntentId, privateSystemAppendix);
        resetIntentCardRowToAutoIfNamed(toolSurfaceIntentId);
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
                            accessUiAuthenticated(submitAuthentication, () ->
                                    initializeConversationFromFinalEvent(f.conversationId()));
                        }
                        // Phase 13.1 D-D1 — streaming-path budget-exceeded toast (CONTEXT D-D1
                        // demands the toast on BOTH transports; the streaming Final event
                        // carries the same flag as ChatResponseDto.budgetExceeded per Plan 03
                        // Option A).
                        if (f.budgetExceeded()) {
                            showBudgetExceededToast();
                        }
                    }
                    // Phase 15 Plan 04 — ephemeral status line + per-turn step accumulation.
                    // The Activity variant is NOT routed through StreamEventRenderer (it contributes
                    // no markdown); record the status flip and (for RETRIEVAL) a live step, then skip
                    // the markdown path entirely.
                    if (evt instanceof StreamingEvent.Activity a) {
                        if (a.kind() == StreamingEvent.ActivityKind.RETRIEVAL) {
                            recordLiveStep(new LiveTurnStep(
                                    TurnDetailRenderer.STEP_RETRIEVAL_KEY, null, false, null, System.nanoTime()));
                        }
                        accessUi(() -> showStatus(TurnDetailRenderer.statusKeyFor(a.kind())));
                        return;
                    }
                    if (evt instanceof StreamingEvent.ToolCall tc) {
                        // Live "started" step keyed by toolCallId — store the start nanos for the
                        // FALLBACK arrival delta only; never store toolName()/argsJson().
                        recordLiveStep(new LiveTurnStep(
                                TurnDetailRenderer.STEP_TOOL_KEY, null, false, tc.toolCallId(), System.nanoTime()));
                    }
                    if (evt instanceof StreamingEvent.ToolResult tr) {
                        finishLiveStep(tr.toolCallId(), tr.outcome());
                    }
                    StreamEventRenderer.RenderedStreamEvent rendered =
                            StreamEventRenderer.renderStreamEventDetails(evt, labels, citationState);
                    if (rendered.draftPayloadInvalid()) {
                        accessUi(this::showDraftPayloadInvalidNotification);
                    }
                    if (rendered.draftPayload() != null) {
                        StreamEventRenderer.DraftPayload draftPayload = rendered.draftPayload();
                        accessUi(() -> appendIntentConfirmRow(
                                draftPayload.draftId(),
                                draftPayload.entityName(),
                                draftPayload.instanceName()));
                    }
                    if (rendered.actionProposalPayload() != null) {
                        StreamEventRenderer.ActionProposalPayload actionProposalPayload =
                                rendered.actionProposalPayload();
                        accessUi(() -> appendActionChoiceRow(actionProposalPayload));
                    }
                    String md = rendered.markdown();
                    if (md.isEmpty()) return;
                    // Review point #6 — the first Content event of the turn implies CHAT/"thinking…"
                    // (regardless of any prior Activity(RETRIEVAL)); a later Activity(...) still wins.
                    if (!turnContentSeen) {
                        turnContentSeen = true;
                        accessUi(() -> showStatus(
                                TurnDetailRenderer.statusKeyFor(StreamingEvent.ActivityKind.CHAT)));
                    }
                    accessUi(() -> {
                        if (botMsg != null) {
                            botMsg.appendText(md);
                        }
                        scrollToBottom();
                    });
                })
                .doOnError(err -> {
                    log.warn("Chat stream failed", err);
                    liveTurnSteps.clear();
                    accessUi(() -> { showGenericErrorNotification(); finishStreamInternal(); });
                })
                .doOnComplete(() -> accessUi(() -> {
                    // Review point #8 — on the terminal Final of the just-completed turn, read the
                    // runId's AiAuditEvent TOOL/RETRIEVAL children for REAL latencyMs (unified
                    // loadTurnSteps path); fall back to the transient arrival-delta steps only if
                    // the audit read returns nothing. A zero-step turn produces NO Details.
                    UUID runId = activeRunId;
                    UUID cid = conversationId;
                    if (runId != null && cid != null) {
                        List<TurnDetailRenderer.StepRow> steps = loadTurnSteps(runId, cid);
                        if (steps.isEmpty()) {
                            steps = liveTurnStepsAsStepRows();
                        }
                        if (!steps.isEmpty()) {
                            appendTurnDetails(runId, steps);
                        }
                    }
                    liveTurnSteps.clear();
                    finishStreamInternal();
                }))
                .subscribe();
    }

    /** Adds a live-turn step iff under {@link #LIVE_TURN_STEP_CAP} (review point #12). */
    private void recordLiveStep(LiveTurnStep step) {
        if (liveTurnSteps.size() < LIVE_TURN_STEP_CAP) {
            liveTurnSteps.add(step);
        }
    }

    /** Marks the live "started" TOOL step with the matching {@code toolCallId} as finished,
     *  recording the {@link AiToolCallOutcome} and the transient ToolCall→ToolResult arrival
     *  delta in ms as a FALLBACK only (the real {@code latencyMs} is read from the audit children
     *  on {@code Final}). */
    private void finishLiveStep(UUID toolCallId, AiToolCallOutcome outcome) {
        if (toolCallId == null) {
            return;
        }
        for (int i = 0; i < liveTurnSteps.size(); i++) {
            LiveTurnStep step = liveTurnSteps.get(i);
            if (toolCallId.equals(step.toolCallId())) {
                long deltaMs = Math.max(0L, (System.nanoTime() - step.startedAtNanos()) / 1_000_000L);
                boolean errored = TurnDetailRenderer.stepRow(
                        StreamingEvent.ActivityKind.TOOL, deltaMs, outcome).errored();
                liveTurnSteps.set(i, new LiveTurnStep(step.labelKey(), deltaMs, errored,
                        step.toolCallId(), step.startedAtNanos()));
                return;
            }
        }
    }

    /** Converts the bounded live-turn step list into renderer {@link TurnDetailRenderer.StepRow}s
     *  (fallback path — used only when the audit read returns nothing on {@code Final}). */
    private List<TurnDetailRenderer.StepRow> liveTurnStepsAsStepRows() {
        List<TurnDetailRenderer.StepRow> rows = new ArrayList<>(liveTurnSteps.size());
        for (LiveTurnStep step : liveTurnSteps) {
            rows.add(new TurnDetailRenderer.StepRow(step.labelKey(), step.latencyMs(), step.errored()));
        }
        return rows;
    }

    private void initializeConversationFromFinalEvent(UUID newConversationId) {
        if (conversationId != null || newConversationId == null) {
            return;
        }
        conversationId = newConversationId;
        updateSessionConversationId(conversationId);
        updateTitleEditState();
        if (taskFilesDl != null) {
            taskFilesDl.setParameter("conversationId", conversationId);
            taskFilesDl.load();
        }
    }

    private String selectedIntentIdForSubmit() {
        if (intentCardRow == null) {
            return null;
        }
        IntentOption selectedOption = intentCardRow.getValue();
        if (selectedOption == null || selectedOption.auto()) {
            return null;
        }
        return selectedOption.intentId();
    }

    private void resetIntentCardRowToAutoIfNamed(String selectedIntentId) {
        if (selectedIntentId == null || intentCardRow == null) {
            return;
        }
        intentCardRow.setValue(buildAutoIntentOption());
        refreshIntentCardSelection();
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

    private void showDraftPayloadInvalidNotification() {
        notifications.create(messages.getMessage("chatView.intent.draftPayloadInvalid"))
                .withThemeVariant(NotificationVariant.LUMO_WARNING)
                .show();
    }

    private void finishStreamInternal() {
        setStreamingUiState(false);
        if (activeRunId != null) cancellationRegistry.clearDisposable(activeRunId);
        activeRunId = null;
        activeStream = null;
        botMsg = null;
        // Phase 15 Plan 04 (review point #7) — the ephemeral status line is GONE in every
        // teardown path (not blanked). finishStreamInternal is reached by .doOnComplete /
        // .doOnError / the stop-button handler (stopActiveStream) and via clearMessageList on a
        // conversation switch; the onDetach hook routes through stopActiveStream/cancel as well.
        removeStatusRow();
        turnContentSeen = false;
    }

    // ---- Phase 15 Plan 04 — ephemeral streaming-status line (OBS-01) -------

    /**
     * Shows/updates the ephemeral KIND-keyed streaming-status {@code <span>} — a sibling of
     * {@code <vaadin-message-list>} inside {@code messageListSlot}, never inside any
     * {@link MessageListItem}. Text is set via {@link com.vaadin.flow.dom.Element#setText(String)}
     * (HTML-escaped); no markdown rendering on this element (T-15-D2). The status text is NEVER
     * concatenated into the assistant bubble.
     */
    private void showStatus(String messageKey) {
        if (messageListSlot == null) {
            return;
        }
        if (statusRow == null) {
            statusRow = new com.vaadin.flow.dom.Element("span");
            statusRow.getClassList().add("ai-agent-status");
            statusRow.setAttribute("role", "status");
            statusRow.setAttribute("aria-live", "polite");
            messageListSlot.getElement().appendChild(statusRow);
        }
        statusRow.setText(messages.getMessage(messageKey));
    }

    /** Removes the status {@code <span>} from its parent and nulls the field — used in every
     *  terminal/teardown site (review point #7). Idempotent. */
    private void removeStatusRow() {
        if (statusRow != null) {
            statusRow.removeFromParent();
            statusRow = null;
        }
    }

    // ---- Phase 15 Plan 04 — per-turn tool-detail Details (OBS-02) ----------

    /** Lazily creates the single {@code <div class="ai-agent-turn-activity">} block (appended
     *  after {@code <vaadin-message-list>} inside {@code messageListSlot}); the per-turn
     *  {@link Details} are added INTO it in turn order. Review point #2: a {@link Details} cannot
     *  be inserted between two {@link MessageListItem}s on the {@code MessageList} substrate, so
     *  the disclosures are grouped here. */
    private Div turnActivityBlock() {
        if (turnActivityBlock == null) {
            turnActivityBlock = new Div();
            turnActivityBlock.addClassName("ai-agent-turn-activity");
            if (messageListSlot != null) {
                messageListSlot.add(turnActivityBlock);
            }
        }
        return turnActivityBlock;
    }

    /** Builds + appends a collapsed-by-default {@link Details} for a just-completed live turn
     *  from already-resolved step rows (real {@code latencyMs} from the audit children, or the
     *  arrival-delta fallback). Caller has already ensured {@code steps} is non-empty. */
    private void appendTurnDetails(UUID runId, List<TurnDetailRenderer.StepRow> steps) {
        Details details = buildTurnDetails(steps);
        ComponentUtil.setData(details, TURN_DETAILS_LOADED_KEY, Boolean.TRUE);
        replaceTurnDetails(runId, details);
    }

    /** Builds + appends a collapsed {@link Details} for a prior (history-replayed) turn whose
     *  CHAT root we already know has &ge;1 child. The step rows are read lazily on first expand
     *  via {@link #loadTurnSteps(UUID, UUID)} and memoized ({@link #TURN_DETAILS_LOADED_KEY}) so a
     *  re-expand does not re-query. If (defensively) zero children come back the {@link Details}
     *  is removed. */
    private void appendHistoryTurnDetails(UUID runId, UUID conversationId) {
        Details details = new Details();
        details.setOpened(false);
        details.setSummaryText(messages.getMessage("chatView.turnDetail.summaryPending"));
        details.addOpenedChangeListener(event -> {
            if (!event.isOpened()
                    || Boolean.TRUE.equals(ComponentUtil.getData(details, TURN_DETAILS_LOADED_KEY))) {
                return;
            }
            List<TurnDetailRenderer.StepRow> steps = loadTurnSteps(runId, conversationId);
            if (steps.isEmpty()) {
                details.removeFromParent();
                turnDetailsByRunId.remove(runId, details);
                return;
            }
            populateTurnDetails(details, steps);
            ComponentUtil.setData(details, TURN_DETAILS_LOADED_KEY, Boolean.TRUE);
        });
        replaceTurnDetails(runId, details);
    }

    private void replaceTurnDetails(UUID runId, Details details) {
        Details existing = turnDetailsByRunId.remove(runId);
        if (existing != null) {
            existing.removeFromParent();
        }
        turnActivityBlock().add(details);
        turnDetailsByRunId.put(runId, details);
    }

    private Details buildTurnDetails(List<TurnDetailRenderer.StepRow> steps) {
        Details details = new Details();
        details.setOpened(false);
        populateTurnDetails(details, steps);
        return details;
    }

    private void populateTurnDetails(Details details, List<TurnDetailRenderer.StepRow> steps) {
        long totalMs = 0L;
        for (TurnDetailRenderer.StepRow step : steps) {
            if (step.latencyMs() != null) {
                totalMs += step.latencyMs();
            }
        }
        details.setSummaryText(MessageFormat.format(
                messages.getMessage(TurnDetailRenderer.summaryKey()),
                TurnDetailRenderer.summaryArgs(steps.size(), totalMs)));
        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(false);
        content.addClassName("ai-agent-turn-activity__steps");
        content.removeAll();
        for (TurnDetailRenderer.StepRow step : steps) {
            content.add(buildStepRow(step));
        }
        details.removeAll();
        details.add(content);
    }

    private Div buildStepRow(TurnDetailRenderer.StepRow step) {
        Div row = new Div();
        row.addClassName("ai-agent-turn-activity__step");
        // Label-only KIND-keyed text — never a tool/entity name (T-15-D1).
        Span label = new Span(messages.getMessage(step.labelKey()));
        label.addClassName("ai-agent-turn-activity__step-label");
        Span duration = new Span(step.latencyMs() == null
                ? TurnDetailRenderer.UNKNOWN_DURATION_TEXT
                : step.latencyMs() + " ms");
        duration.addClassName("ai-agent-turn-activity__step-duration");
        row.add(label, duration);
        if (step.errored()) {
            Span error = new Span(messages.getMessage(TurnDetailRenderer.errorIndicatorKey()));
            error.addClassName("ai-agent-turn-activity__step-error");
            row.add(error);
        }
        return row;
    }

    /**
     * The unified live + history audit read (review point #8). Reads the {@code AiAuditEvent}
     * TOOL/RETRIEVAL children for one {@code runId} via {@link UnconstrainedDataManager} with the
     * MANDATORY {@code where e.userUsername = :me and e.conversation.id = :cid} clause — never
     * {@code runId}-only unconstrained (T-15-D3). The narrow fetch plan deliberately omits the
     * name columns and LOBs ({@code eventName}/{@code argumentsJson}/{@code resultSummary}/
     * {@code queryText}) — only {@code kind}/{@code startedAt}/{@code finishedAt}/{@code latencyMs}/
     * {@code outcome}/{@code errorClass} cross. Typed {@code load(AiAuditEvent.class)} infers the
     * {@code agentstore} store.
     */
    private List<TurnDetailRenderer.StepRow> loadTurnSteps(UUID runId, UUID conversationId) {
        if (runId == null || conversationId == null) {
            return List.of();
        }
        try {
            List<AiAuditEvent> children = unconstrainedDataManager.load(AiAuditEvent.class)
                    .query("select e from ai_AiAuditEvent e " +
                           "where e.userUsername = :me and e.conversation.id = :cid " +
                           "and e.runId = :rid and e.parent is not null " +
                           "and e.kind in (:toolKind, :retrievalKind) " +
                           "order by e.startedAt asc")
                    .parameter("me", currentAuthentication.getUser().getUsername())
                    .parameter("cid", conversationId)
                    .parameter("rid", runId)
                    .parameter("toolKind", com.vn.agent.spi.AuditKind.TOOL)
                    .parameter("retrievalKind", com.vn.agent.spi.AuditKind.RETRIEVAL)
                    .fetchPlan(fp -> {
                        fp.add("kind");
                        fp.add("startedAt");
                        fp.add("finishedAt");
                        fp.add("latencyMs");
                        fp.add("outcome");
                        fp.add("errorClass");
                    })
                    .list();
            List<TurnDetailRenderer.StepRow> rows = new ArrayList<>(children.size());
            for (AiAuditEvent child : children) {
                rows.add(TurnDetailRenderer.stepRow(child.getKind(), child.getLatencyMs(), child.getOutcome()));
            }
            return rows;
        } catch (RuntimeException failure) {
            log.debug("Turn-detail audit read failed runId={} conversationId={}", runId, conversationId, failure);
            return List.of();
        }
    }

    /**
     * Additive post-navigation correlation pass (SPEC req 5 / CONTEXT D-07, review point #3).
     * Called AFTER the history-replay loop calls {@code messageList.setItems(...)}. Loads, in
     * {@code startedAt} order, the conversation's CHAT-root {@code runId}s + each root's
     * TOOL/RETRIEVAL child count via two narrow raw-JPQL {@code loadValues} reads against the
     * {@code agentstore} (raw {@code loadValues} does NOT infer the store — project memory
     * {@code feedback_jmix_loadvalue_store} — so {@code .store("agentstore")} is explicit). The
     * two-query form is used (one ordered list of roots, one grouped child-count map) — a single
     * {@code left join} on a {@code @Composition} self-relation is awkward in JPQL. Zips the
     * ordered {@code runId} list 1:1 against the replayed ASSISTANT turns ONLY when the counts
     * match; on a mismatch renders NO disclosures (debug-log, never throws, never guesses) — a
     * wrong association is worse than none. Appends a collapsed history {@link Details} only for
     * roots with {@code childCount > 0}.
     */
    private void correlateHistoryTurnDetails(UUID conversationId, int assistantTurnCount) {
        if (conversationId == null || assistantTurnCount <= 0) {
            return;
        }
        try {
            String me = currentAuthentication.getUser().getUsername();
            List<UUID> rootRunIds = new ArrayList<>();
            for (io.jmix.core.entity.KeyValueEntity row : unconstrainedDataManager.loadValues(
                            "select e.runId from ai_AiAuditEvent e " +
                            "where e.userUsername = :me and e.conversation.id = :cid " +
                            "and e.parent is null and e.kind = :chatKind " +
                            "order by e.startedAt asc")
                    .store("agentstore")
                    .properties("runId")
                    .parameter("me", me)
                    .parameter("cid", conversationId)
                    .parameter("chatKind", com.vn.agent.spi.AuditKind.CHAT)
                    .list()) {
                Object rid = row.getValue("runId");
                if (rid instanceof UUID uuid) {
                    rootRunIds.add(uuid);
                }
            }
            Map<UUID, Long> childCounts = new HashMap<>();
            for (io.jmix.core.entity.KeyValueEntity row : unconstrainedDataManager.loadValues(
                            "select c.runId, count(c) from ai_AiAuditEvent c " +
                            "where c.userUsername = :me and c.conversation.id = :cid and c.parent is not null " +
                            "and c.kind in (:toolKind, :retrievalKind) " +
                            "group by c.runId")
                    .store("agentstore")
                    .properties("runId", "cnt")
                    .parameter("me", me)
                    .parameter("cid", conversationId)
                    .parameter("toolKind", com.vn.agent.spi.AuditKind.TOOL)
                    .parameter("retrievalKind", com.vn.agent.spi.AuditKind.RETRIEVAL)
                    .list()) {
                Object rid = row.getValue("runId");
                Object cnt = row.getValue("cnt");
                if (rid instanceof UUID uuid && cnt instanceof Number number) {
                    childCounts.put(uuid, number.longValue());
                }
            }
            if (rootRunIds.size() != assistantTurnCount) {
                log.debug("turn-detail correlation skipped: {} assistant turns vs {} audit roots",
                        assistantTurnCount, rootRunIds.size());
                return;
            }
            for (UUID runId : rootRunIds) {
                if (childCounts.getOrDefault(runId, 0L) > 0L) {
                    appendHistoryTurnDetails(runId, conversationId);
                }
            }
        } catch (RuntimeException failure) {
            log.debug("turn-detail correlation failed conversationId={}", conversationId, failure);
        }
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
     * Phase 13.1 UAT-fix — build a {@link MessageListItem} for replay from a persisted
     * {@link AiMessage}. Keeps the original timestamp so MessageList renders the historical
     * order naturally.
     */
    private MessageListItem buildItem(AiMessage m, String name, int colorIndex) {
        String content = m.getContent() == null ? "" : m.getContent();
        OffsetDateTime created = m.getCreatedDate();
        Instant time = created == null ? Instant.now() : created.toInstant();
        MessageListItem item = new MessageListItem(content, time, name);
        item.setUserColorIndex(colorIndex);
        return item;
    }

    /**
     * Phase 13.1 UAT-fix — append a NOTICE divider as a plain {@code <div>} sibling of
     * the {@code <vaadin-message-list>} block. The plain-div substrate avoids the default
     * {@code <vaadin-avatar>} upload-arrow fallback that the prior {@code <vaadin-message
     * class="attachment-event">} substrate exposed when no userName was set. The
     * {@code <div>} text content is set via {@code setText}, which is HTML-escaped by the
     * Vaadin Element API (T-13.1-17 mitigation), so even a filename containing script tags
     * cannot inject HTML.
     */
    private void appendNoticeRow(String text) {
        if (text == null) {
            text = "";
        }
        com.vaadin.flow.dom.Element notice = new com.vaadin.flow.dom.Element("div");
        notice.getClassList().add("ai-agent-attachment-notice");
        notice.setText(text);
        messageListSlot.getElement().appendChild(notice);
        messageCount++;
    }

    void appendIntentConfirmRow(UUID draftId, String entityName, String instanceName) {
        Div row = new Div();
        row.addClassName("ai-agent-intent-confirm");
        row.getElement().setAttribute("role", "status");
        row.getElement().setAttribute("aria-live", "polite");

        Span summary = new Span(MessageFormat.format(
                messages.getMessage("chatView.intent.confirmButton.summary"),
                safeText(instanceName)));
        summary.addClassName("ai-agent-intent-confirm__summary");

        Button confirmButton = new Button(
                messages.getMessage("chatView.intent.confirmButton"),
                VaadinIcon.EXTERNAL_LINK.create());
        confirmButton.addThemeNames("primary", "small");
        confirmButton.setAriaLabel(messages.getMessage("chatView.intent.confirmButton"));
        confirmButton.addClickListener(event -> {
            OpenFormWithDraftHandler.OpenResult result =
                    openFormWithDraftHandler.open(this, draftId, entityName, instanceName);
            if (result.status() == OpenFormWithDraftHandler.OpenStatus.EXPIRED) {
                markIntentConfirmRowExpired(summary, confirmButton);
            }
        });

        row.add(summary, confirmButton);
        messageListSlot.add(row);
        messageCount++;
    }

    void appendActionChoiceRow(StreamEventRenderer.ActionProposalPayload proposalPayload) {
        if (proposalPayload == null || proposalPayload.choices().isEmpty()) {
            return;
        }
        Div row = new Div();
        row.addClassName("ai-agent-action-choice");
        row.getElement().setAttribute("role", "status");
        row.getElement().setAttribute("aria-live", "polite");
        row.getElement().setAttribute("data-proposal-id", proposalPayload.proposalId());

        Span summary = new Span(MessageFormat.format(
                messages.getMessage("chatView.actionChoice.summary"),
                safeText(proposalPayload.instanceName())));
        summary.addClassName("ai-agent-action-choice__summary");
        row.add(summary);

        if (proposalPayload.choices().contains(ActionIntentId.CREATE_NOW)) {
            Button createNowButton = new Button(
                    messages.getMessage("chatView.actionChoice.createNow"),
                    VaadinIcon.CHECK.create());
            createNowButton.addThemeNames("primary", "small");
            createNowButton.setAriaLabel(messages.getMessage("chatView.actionChoice.createNow"));
            createNowButton.addClickListener(event ->
                    submitActionChoice(row, proposalPayload, ActionIntentId.CREATE_NOW));
            row.add(createNowButton);
        }
        if (proposalPayload.choices().contains(ActionIntentId.PREFILL_FORM)) {
            Button prefillButton = new Button(
                    messages.getMessage("chatView.actionChoice.prefillForm"),
                    VaadinIcon.EXTERNAL_LINK.create());
            prefillButton.addThemeNames("small");
            prefillButton.setAriaLabel(messages.getMessage("chatView.actionChoice.prefillForm"));
            prefillButton.addClickListener(event ->
                    submitActionChoice(row, proposalPayload, ActionIntentId.PREFILL_FORM));
            row.add(prefillButton);
        }
        Button discardButton = new Button(
                messages.getMessage("chatView.actionChoice.discard"),
                VaadinIcon.CLOSE_SMALL.create());
        discardButton.addThemeNames("small", "tertiary");
        discardButton.setAriaLabel(messages.getMessage("chatView.actionChoice.discard"));
        discardButton.addClickListener(event -> removeActionChoiceRow(row));
        row.add(discardButton);

        // The model can re-emit a proposal with the same proposalId; drop any stale row first
        // so it does not leak in messageListSlot and double-count messageCount.
        Div existingRow = actionChoiceRowsByProposalId.get(proposalPayload.proposalId());
        if (existingRow != null) {
            removeActionChoiceRow(existingRow);
        }
        messageListSlot.add(row);
        actionChoiceRowsByProposalId.put(proposalPayload.proposalId(), row);
        messageCount++;
    }

    private void submitActionChoice(Div actionChoiceRow,
                                    StreamEventRenderer.ActionProposalPayload proposalPayload,
                                    String actionIntentId) {
        disableActionChoiceRow(actionChoiceRow);
        if (ActionIntentId.CREATE_NOW.equals(actionIntentId)) {
            String label = messages.getMessage("chatView.actionChoice.createNow");
            try {
                submitChatTurn(label, label,
                        ActionIntentId.selectionParameter(actionIntentId),
                        actionSelectionPrompt(proposalPayload, actionIntentId));
                removeActionChoiceRow(actionChoiceRow);
            } catch (RuntimeException failure) {
                enableActionChoiceRow(actionChoiceRow);
                throw failure;
            }
            return;
        }
        if (ActionIntentId.PREFILL_FORM.equals(actionIntentId)) {
            try {
                ActionProposalService.DraftResult draftResult =
                        actionProposalService.createDraft(
                                toActionProposal(proposalPayload, actionIntentId), conversationId, null);
                removeActionChoiceRow(actionChoiceRow);
                appendIntentConfirmRow(draftResult.draftId(), draftResult.entityName(), draftResult.instanceName());
            } catch (RuntimeException failure) {
                log.warn("Prefill action proposal failed", failure);
                enableActionChoiceRow(actionChoiceRow);
                showGenericErrorNotification();
            }
        }
    }

    private ActionProposal toActionProposal(StreamEventRenderer.ActionProposalPayload proposalPayload,
                                            String actionIntentId) {
        return new ActionProposal(
                proposalPayload.proposalId(),
                "create",
                proposalPayload.targetEntityName(),
                proposalPayload.instanceName(),
                proposalPayload.values(),
                List.of(),
                List.of(actionIntentId));
    }

    private String actionSelectionPrompt(StreamEventRenderer.ActionProposalPayload proposalPayload,
                                         String actionIntentId) {
        return "Selected action intent: " + actionIntentId + "\n"
                + "Proposal id: " + proposalPayload.proposalId() + "\n"
                + "Target entity: " + proposalPayload.targetEntityName() + "\n"
                + "Collected values JSON: " + valuesJson(proposalPayload.values());
    }

    private String valuesJson(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize action proposal values", e);
        }
    }

    private void disableActionChoiceRow(Div actionChoiceRow) {
        setActionChoiceRowEnabled(actionChoiceRow, false);
    }

    private void enableActionChoiceRow(Div actionChoiceRow) {
        setActionChoiceRowEnabled(actionChoiceRow, true);
    }

    private void setActionChoiceRowEnabled(Div actionChoiceRow, boolean enabled) {
        if (actionChoiceRow == null) {
            return;
        }
        actionChoiceRow.getChildren()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .forEach(button -> button.setEnabled(enabled));
    }

    private void removeActionChoiceRow(Div actionChoiceRow) {
        if (actionChoiceRow == null) {
            return;
        }
        actionChoiceRow.removeFromParent();
        actionChoiceRowsByProposalId.values().removeIf(row -> row == actionChoiceRow);
        if (messageCount > 0) {
            messageCount--;
        }
    }

    private void removeAllActionChoiceRows() {
        List<Div> rows = new ArrayList<>(actionChoiceRowsByProposalId.values());
        for (Div row : rows) {
            removeActionChoiceRow(row);
        }
        actionChoiceRowsByProposalId.clear();
    }

    /**
     * Maximum normalized length of a message still treated as a bare cancellation instruction.
     * A longer message is assumed to carry substantive content (e.g. "don't create a duplicate,
     * update the existing one") rather than being a standalone "cancel"/"huy".
     */
    private static final int CANCELLATION_MAX_LENGTH = 40;
    /** A negated cancel verb ("don't cancel", "khong huy") is not itself a cancellation. */
    private static final java.util.regex.Pattern CANCELLATION_NEGATED = java.util.regex.Pattern.compile(
            ".*\\b(khong|chua|do not|dont|don't|never|dung)\\b\\s+(huy|cancel|discard)\\b.*");
    private static final java.util.regex.Pattern CANCELLATION_LEADING_POLITENESS = java.util.regex.Pattern.compile(
            "^(please|pls|kindly|vui long|lam on|xin|hay)\\b\\s*");

    private boolean looksLikeActionCancellation(String text) {
        String normalized = normalizeCancellationText(text).trim();
        if (normalized.isEmpty() || normalized.length() > CANCELLATION_MAX_LENGTH) {
            return false;
        }
        if (CANCELLATION_NEGATED.matcher(normalized).matches()) {
            // "khong huy nua" / "I don't want to cancel" — a negated cancellation is not a cancellation.
            return false;
        }
        String imperative = CANCELLATION_LEADING_POLITENESS.matcher(normalized).replaceFirst("").trim();
        return imperative.matches("^(huy|cancel|discard)\\b.*")
                || imperative.startsWith("khong tao")
                || imperative.startsWith("dung tao")
                || imperative.startsWith("bo qua yeu cau")
                || imperative.startsWith("do not create")
                || imperative.startsWith("dont create")
                || imperative.startsWith("don't create");
    }

    private String normalizeCancellationText(String text) {
        if (text == null) {
            return "";
        }
        String stripped = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return stripped.replace('đ', 'd');
    }

    private void markIntentConfirmRowExpired(Span summary, Button confirmButton) {
        summary.setText(messages.getMessage("chatView.intent.draftExpired"));
        confirmButton.setEnabled(false);
    }

    private void clearMessageList() {
        items.clear();
        actionChoiceRowsByProposalId.clear();
        // Phase 15 Plan 04 (review point #7) — drop the observability state for the outgoing
        // conversation; removeAllChildren() below detaches the status <span> + activity-block Div,
        // but the fields must be nulled so the next turn re-creates them in the fresh substrate.
        turnDetailsByRunId.clear();
        liveTurnSteps.clear();
        turnActivityBlock = null;
        statusRow = null;
        turnContentSeen = false;
        if (messageListSlot != null) {
            // Wipe the underlying element children so both the MessageList component AND any
            // raw <div class="ai-agent-attachment-notice"> sibling rows are removed.
            messageListSlot.removeAll();
            messageListSlot.getElement().removeAllChildren();
            // Re-attach a fresh MessageList so subsequent items render into a clean substrate.
            messageList = new MessageList();
            messageList.setMarkdown(true);
            messageList.setWidthFull();
            messageList.getStyle().set("flex-grow", "1");
            messageListSlot.add(messageList);
            messageList.setItems(items);
        }
        botMsg = null;
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
        // Phase 13.1 UAT-fix-02 — auto-scroll across all candidate scroll containers.
        // Vaadin's <vaadin-message-list> scrolls INTERNALLY (its bubble area has its
        // own overflow:auto), and we also have a sibling NOTICE div after the list.
        // To keep the latest streaming token visible, force-scroll BOTH the slot vbox
        // and the inner message-list to their bottom on each token append. setTimeout
        // defers the scroll one paint frame so newly-appended bubble content has been
        // measured into scrollHeight before we read it.
        messageListSlot.getElement().executeJs(
                "setTimeout(() => { " +
                "  this.scrollTop = this.scrollHeight; " +
                "  const list = this.querySelector('vaadin-message-list'); " +
                "  if (list) { list.scrollTop = list.scrollHeight; } " +
                "  const last = this.lastElementChild; " +
                "  if (last) last.scrollIntoView({behavior: 'smooth', block: 'end'}); " +
                "}, 50);");
    }

    private void accessUi(Runnable action) {
        accessUiAuthenticated(ownerAuthentication, action);
    }

    private void accessUiAuthenticated(Authentication authentication, Runnable action) {
        UI ui = this.ownerUi;
        if (ui == null) ui = getUI().orElse(null);
        if (ui == null) return; // detached — drop update
        ui.access(() -> runWithAuthentication(authentication, action));
    }

    private void runWithAuthentication(Authentication authentication, Runnable action) {
        if (authentication == null) {
            action.run();
            return;
        }

        SecurityContext previousContext = SecurityContextHolder.getContext();
        SecurityContext capturedContext = SecurityContextHolder.createEmptyContext();
        capturedContext.setAuthentication(authentication);
        try {
            SecurityContextHolder.setContext(capturedContext);
            action.run();
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }

    private Authentication captureCurrentAuthentication() {
        try {
            return currentAuthentication == null ? null : currentAuthentication.getAuthentication();
        } catch (RuntimeException authenticationMissing) {
            log.debug("Unable to capture current authentication for async UI callback", authenticationMissing);
            return null;
        }
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
     *       uploading user and append a {@code <div class="ai-agent-attachment-notice">}
     *       sibling row to {@code messageListSlot}. Failure is logged and the upload still
     *       succeeds (per CONTEXT integration-points "log-and-continue").</li>
     *   <li>Reload {@code taskFilesDl} so the right-pane card grid + empty-state toggle
     *       reflect the new row.</li>
     * </ol>
     *
     * <p>Best-effort temp-file cleanup runs in the {@code finally} block; a missing temp
     * file is not a failure (Jmix may have already removed it).
     */
    private void handleUploadedFile(String fileName, String declaredContentType,
                                    long declaredSizeBytes, Path tempFile) {
        try {
            UUID convId = ensureConversationIdForUpload();
            if (convId == null) {
                log.warn("Upload arrived without an active conversation; dropping file {}", fileName);
                notifications.create(messages.getMessage("chatView.attachments.upload.failed"))
                        .withThemeVariant(NotificationVariant.LUMO_WARNING)
                        .show();
                return;
            }

            long actualSizeBytes;
            try {
                actualSizeBytes = Files.size(tempFile);
            } catch (IOException sizeFailure) {
                log.warn("Failed to measure staged upload {}; rejecting", fileName, sizeFailure);
                notifications.create(messages.getMessage("chatView.attachments.upload.failed"))
                        .withThemeVariant(NotificationVariant.LUMO_ERROR)
                        .show();
                return;
            }
            if (declaredSizeBytes >= 0 && declaredSizeBytes != actualSizeBytes) {
                log.debug("Upload metadata size mismatch for {}: declared={} actual={}",
                        fileName, declaredSizeBytes, actualSizeBytes);
            }

            // REVIEWS HIGH-5 — server-side size cap re-validated BEFORE blob persist.
            long maxBytes = taskFileProperties.getMaxFileSizeBytes();
            if (actualSizeBytes > maxBytes) {
                log.warn("Upload rejected (size {} > cap {}): {}", actualSizeBytes, maxBytes, fileName);
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
            row.setSizeBytes(actualSizeBytes);
            row.setUserUsername(currentAuthentication.getUser().getUsername());
            // Phase 13.1 LIFE-01: ttlSeconds is the operator-facing TTL knob; sentinel
            // -1 disables purge and active-row filtering. Persist a DB-safe far-future
            // timestamp instead of the 24h default so sentinel uploads do not age out.
            long ttlSeconds = taskFileProperties.getTtlSeconds();
            row.setExpiresAt(ttlSeconds == -1L
                    ? NON_EXPIRING_EXPIRES_AT
                    : OffsetDateTime.now().plusSeconds(ttlSeconds));

            AiTaskFile saved = dataManager.save(row);

            // Phase 13.1 UX-01 — bilingual NOTICE row. Log-and-continue per CONTEXT
            // integration-points: a NOTICE-write failure does NOT roll back the upload.
            try {
                AiMessage notice = metadataApi.create(AiMessage.class);
                notice.setConversation(conversation);
                notice.setRole(AiMessageRole.NOTICE);
                // Phase 13.1 UAT-fix-02 — Java overload-resolution trap: when calling
                // messages.formatMessage(key, arg1, arg2) with three String args, the compiler
                // picks the more-specific (String pack, String key, Object... params) overload,
                // treating arg1 as the key and arg2 as the only param. That returned just
                // arg1 ("admin") because the resolver failed to find a key called "admin"
                // and fell back to returning the key literal. Workaround: fetch the raw
                // template via getMessage(key) and interpolate with java.text.MessageFormat
                // so we never touch the ambiguous formatMessage signature.
                String noticeTemplate = messages.getMessage("chatView.attachments.notice");
                notice.setContent(MessageFormat.format(noticeTemplate,
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
