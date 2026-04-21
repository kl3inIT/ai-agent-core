package com.vn.agent.view.knowledge;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.push.DocumentStatusChangedEvent;
import com.vn.agent.rag.KnowledgeDocumentService;
import com.vn.agent.rag.KnowledgeDocumentUploadService;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.component.upload.receiver.MultiFileTemporaryStorageBuffer;
import io.jmix.flowui.component.upload.receiver.TemporaryStorageFileData;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * UI-05 Knowledge Base admin view.
 *
 * <p>Admin-only per {@code AiAgentAdminRole.adminViews()} {@code @ViewPolicy} wired in 07-01.
 * Uses Jmix {@code <upload receiverType="MULTI_FILE_TEMPORARY_STORAGE_BUFFER">} — D-15 /
 * RESEARCH Pitfall #3: single-file {@code com.vaadin.flow.component.upload.receivers.Memory-Buffer}
 * would silently drop all but one file when
 * {@code maxFiles > 1}. The multi-file temporary-storage buffer writes each uploaded file
 * to {@code CoreProperties.tempDir} and we forward each file to
 * {@link KnowledgeDocumentUploadService#upload(String, String, java.util.Collection)} as a
 * {@code file:} URI after the Vaadin {@code AllFinishedEvent}.</p>
 *
 * <p><b>Size cap:</b> XML {@code maxFileSize=10485760} (10 MiB) clamps client-side uploads;
 * the server {@code KnowledgeDocumentUploadService} also enforces a character cap during
 * async ingestion via {@code jmix.ai-agent.rag.ingest.max-document-chars}. For the
 * {@code file:} URI path to succeed, the host application MUST set
 * {@code jmix.ai-agent.rag.upload.file-staging-root} to the Jmix temp directory
 * (typically {@code ${jmix.core.work-dir}/temp}). The view logs a WARN and rejects uploads
 * if the root is not configured (Rule 2 — safe default, fail-closed).</p>
 *
 * <p><b>Push refresh (D-16):</b> Spring {@code ApplicationListener} receives
 * {@link DocumentStatusChangedEvent} (published by {@code IngestionStatusWriter} in 07-02 via
 * {@code afterCommit}) and updates the affected row via {@code UI.access()}. Per-UI dispatch
 * — the ownerUi reference is captured onAttach and cleared onDetach.</p>
 *
 * <p><b>Row actions (D-18):</b> Reingest + Delete both open confirmation dialogs backed by
 * {@link KnowledgeDocumentService}.</p>
 */
@Route(value = "ai-agent/knowledge", layout = DefaultMainViewParent.class)
@ViewController(id = "AiAgent_KnowledgeBase.list")
@ViewDescriptor(path = "knowledge-base-view.xml")
public class KnowledgeBaseView extends StandardListView<AiKnowledgeDocument>
        implements BeforeEnterObserver {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseView.class);

    @ViewComponent
    private DataGrid<AiKnowledgeDocument> documentsDataGrid;
    @ViewComponent
    private JmixUpload documentUpload;
    @ViewComponent
    private CollectionLoader<AiKnowledgeDocument> documentsDl;
    @ViewComponent
    private CollectionContainer<AiKnowledgeDocument> documentsDc;
    @ViewComponent
    private JmixButton reingestBtn;
    @ViewComponent
    private JmixButton deleteBtn;

    @Autowired
    private KnowledgeDocumentUploadService uploadService;
    @Autowired
    private KnowledgeDocumentService documentService;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Notifications notifications;
    @Autowired
    private MessageSource messageSource;

    /** Captured onAttach so the push listener can UI.access() the correct UI per-browser-tab. */
    private volatile UI ownerUi;
    private UUID pendingSelectionDocumentId;

    @Subscribe
    public void onInit(final InitEvent event) {
        reingestBtn.setEnabled(false);
        deleteBtn.setEnabled(false);
        // Selection → action-button enabled state: kept raw because this is the
        // "dynamic grid selection → button enabled" carve-out allowed by the review.
        documentsDataGrid.addSelectionListener(e -> {
            boolean selected = e.getFirstSelectedItem().isPresent();
            reingestBtn.setEnabled(selected);
            deleteBtn.setEnabled(selected);
        });

        // Status column — Vaadin Badge via ComponentRenderer (D-17).
        documentsDataGrid.getColumnByKey("status")
                .setRenderer(new ComponentRenderer<>(doc -> renderStatusBadge(doc)));

        // Upload wiring — D-15 / RESEARCH Pitfall #3 multi-file receiver. Upload
        // succeed/fail/reject events are component-specific (not ClickEvent), so
        // they continue to be wired via explicit listener calls.
        documentsDl.addPostLoadListener(e -> selectPendingDocumentIfNeeded());
        configureUploadListeners();
    }

    @Subscribe("reingestBtn")
    public void onReingestButtonClick(final ClickEvent<Button> event) {
        onReingestClick();
    }

    @Subscribe("deleteBtn")
    public void onDeleteButtonClick(final ClickEvent<Button> event) {
        onDeleteClick();
    }

    @Subscribe
    public void onAttachEvent(final AttachEvent event) {
        ownerUi = event.getUI();
    }

    @Subscribe
    public void onDetachEvent(final DetachEvent event) {
        ownerUi = null;
    }

    /**
     * Push refresh listener (D-16). Runs on the Spring event thread; hops onto the per-tab
     * UI via {@code UI.access()} before touching the grid. If the view is detached (ownerUi
     * is null) the event is silently dropped — the next list reload will pick up the row.
     */
    @EventListener
    public void onDocumentStatusChanged(DocumentStatusChangedEvent event) {
        UI ui = this.ownerUi;
        if (ui == null) {
            return;
        }
        ui.access(() -> {
            Optional<AiKnowledgeDocument> row = documentsDc.getItems().stream()
                    .filter(d -> event.documentId().equals(d.getId()))
                    .findFirst();
            if (row.isPresent()) {
                AiKnowledgeDocument doc = row.get();
                doc.setStatus(event.status());
                if (event.status() == AiKnowledgeDocumentStatus.FAILED) {
                    doc.setErrorMessage(event.errorMessage());
                } else {
                    doc.setErrorMessage(null);
                }
                documentsDc.replaceItem(doc);
            } else {
                // Row not currently loaded (new upload from another tab) — trigger reload.
                documentsDl.load();
            }
        });
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        QueryParameters qp = event.getLocation().getQueryParameters();
        Map<String, List<String>> params = qp.getParameters();
        List<String> docIds = params.get("documentId");
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        try {
            pendingSelectionDocumentId = UUID.fromString(docIds.get(0));
        } catch (IllegalArgumentException ex) {
            log.debug("Ignoring malformed documentId query param: {}", docIds.get(0));
        }
    }

    // --- Internals -------------------------------------------------------------

    private Span renderStatusBadge(AiKnowledgeDocument doc) {
        AiKnowledgeDocumentStatus status = doc.getStatus();
        Locale locale = UI.getCurrent().getLocale();
        String key = "knowledgeBase.status." + (status == null ? "pending" : status.name().toLowerCase(Locale.ROOT));
        String fallback = status == null ? "Pending" : status.name();
        String label = messageSource.getMessage(key, null, fallback, locale);
        Span badge = new Span(label);
        badge.getElement().getThemeList().add("badge");
        badge.getElement().getThemeList().add(statusTheme(status));
        if (status == AiKnowledgeDocumentStatus.FAILED && doc.getErrorMessage() != null) {
            badge.getElement().setAttribute("title", doc.getErrorMessage());
        }
        return badge;
    }

    private void selectPendingDocumentIfNeeded() {
        UUID documentId = pendingSelectionDocumentId;
        if (documentId == null) {
            return;
        }
        pendingSelectionDocumentId = null;
        documentsDc.getItems().stream()
                .filter(d -> documentId.equals(d.getId()))
                .findFirst()
                .ifPresent(documentsDataGrid::select);
    }

    private static String statusTheme(AiKnowledgeDocumentStatus status) {
        if (status == null) {
            return "contrast";
        }
        return switch (status) {
            case READY -> "success";
            case FAILED -> "error";
            case PROCESSING, PENDING -> "contrast";
            case CANCELLED -> "contrast tertiary";
        };
    }

    @SuppressWarnings("removal") // Vaadin 24 deprecates these on Upload in favour of AllFinishedEvent;
    // Jmix 2.8 JmixUpload extends Upload directly so the deprecated listeners remain the idiomatic
    // integration point for succeed/fail/reject signals.
    private void configureUploadListeners() {
        documentUpload.addSucceededListener(e -> {
            try {
                String fileName = e.getFileName();
                String mimeType = e.getMIMEType();
                File staged = resolveStagedFile(fileName);
                if (staged == null) {
                    notifyError("knowledgeBase.upload.rejected", fileName);
                    return;
                }
                String uri = staged.toURI().toString();
                uploadService.upload(uri, mimeType, Collections.emptyList());
                notifyInfo("knowledgeBase.toast.uploadStarted", fileName);
                documentsDl.load();
            } catch (IllegalArgumentException ex) {
                // URI allowlist rejection — configuration issue; surface a clear toast.
                log.warn("Upload rejected by staging-root allowlist: {}", ex.getMessage());
                notifyError("knowledgeBase.upload.rejected", e.getFileName());
            } catch (Exception ex) {
                log.warn("Upload failed for {}", e.getFileName(), ex);
                notifyError("knowledgeBase.upload.rejected", e.getFileName());
            }
        });

        documentUpload.addFileRejectedListener(e ->
                notifyError("knowledgeBase.upload.rejected", e.getErrorMessage()));

        documentUpload.addFailedListener(e -> {
            log.warn("Upload transport failed for {}", e.getFileName(), e.getReason());
            notifyError("knowledgeBase.upload.rejected", e.getFileName());
        });
    }

    private File resolveStagedFile(String fileName) {
        if (!(documentUpload.getReceiver() instanceof MultiFileTemporaryStorageBuffer buffer)) {
            log.warn("Upload receiver is not MultiFileTemporaryStorageBuffer; cannot stage {}", fileName);
            return null;
        }
        Map<UUID, TemporaryStorageFileData> files = buffer.getFiles();
        // Scan matching entries — multi-file uploads may hold several entries; take the
        // most-recently-registered one for this filename (Vaadin reuses the buffer).
        File latest = null;
        for (Map.Entry<UUID, TemporaryStorageFileData> entry : files.entrySet()) {
            TemporaryStorageFileData data = entry.getValue();
            if (data.getFileName().equals(fileName)) {
                File f = data.getFileInfo().getFile();
                if (f != null && f.exists()) {
                    latest = f;
                }
            }
        }
        return latest;
    }

    private void onReingestClick() {
        AiKnowledgeDocument doc = documentsDataGrid.getSingleSelectedItem();
        if (doc == null) {
            return;
        }
        Locale locale = UI.getCurrent().getLocale();
        dialogs.createOptionDialog()
                .withHeader(messageSource.getMessage(
                        "knowledgeBase.confirm.reingest.title",
                        new Object[]{doc.getFileName()},
                        "Reingest?", locale))
                .withText(messageSource.getMessage(
                        "knowledgeBase.confirm.reingest.body", null,
                        "Existing chunks will be replaced.", locale))
                .withActions(
                        new DialogAction(DialogAction.Type.OK)
                                .withVariant(ActionVariant.PRIMARY)
                                .withHandler(ev -> {
                                    try {
                                        documentService.reingest(doc.getId());
                                        documentsDl.load();
                                    } catch (Exception ex) {
                                        log.warn("reingest failed for {}", doc.getId(), ex);
                                        notifyDetailedError("knowledgeBase.reingest.failed", ex.getMessage());
                                    }
                                }),
                        new DialogAction(DialogAction.Type.CANCEL))
                .open();
    }

    private void onDeleteClick() {
        AiKnowledgeDocument doc = documentsDataGrid.getSingleSelectedItem();
        if (doc == null) {
            return;
        }
        Locale locale = UI.getCurrent().getLocale();
        dialogs.createOptionDialog()
                .withHeader(messageSource.getMessage(
                        "knowledgeBase.confirm.delete.title",
                        new Object[]{doc.getFileName()},
                        "Delete?", locale))
                .withText(messageSource.getMessage(
                        "knowledgeBase.confirm.delete.body", null,
                        "The document and all its embedded chunks will be removed. This cannot be undone.", locale))
                .withActions(
                        new DialogAction(DialogAction.Type.OK)
                                .withVariant(ActionVariant.DANGER)
                                .withHandler(ev -> {
                                    try {
                                        documentService.delete(doc.getId());
                                        documentsDl.load();
                                    } catch (Exception ex) {
                                        log.warn("delete failed for {}", doc.getId(), ex);
                                        notifyDetailedError("knowledgeBase.delete.failed", ex.getMessage());
                                    }
                                }),
                        new DialogAction(DialogAction.Type.CANCEL))
                .open();
    }

    private void notifyInfo(String key, Object... args) {
        Locale locale = UI.getCurrent().getLocale();
        String msg = messageSource.getMessage(key, args, key, locale);
        notifications.create(msg).show();
    }

    private void notifyError(String key, Object... args) {
        Locale locale = UI.getCurrent().getLocale();
        String msg = messageSource.getMessage(key, args, key, locale);
        notifications.create(msg)
                .withThemeVariant(NotificationVariant.LUMO_ERROR)
                .show();
    }

    private void notifyDetailedError(String key, String detail) {
        Locale locale = UI.getCurrent().getLocale();
        String msg = messageSource.getMessage(key, null, key, locale);
        if (detail != null && !detail.isBlank()) {
            msg = msg + " " + Objects.toString(detail, "");
        }
        notifications.create(msg)
                .withThemeVariant(NotificationVariant.LUMO_ERROR)
                .show();
    }
}
