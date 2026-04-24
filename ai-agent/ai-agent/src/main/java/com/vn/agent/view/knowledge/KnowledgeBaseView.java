package com.vn.agent.view.knowledge;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.push.DocumentStatusChangedEvent;
import com.vn.agent.rag.KnowledgeDocumentService;
import com.vn.agent.rag.KnowledgeDocumentUploadService;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.upload.JmixUpload;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.upload.TemporaryStorage;
import io.jmix.flowui.view.DefaultMainViewParent;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;

import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

/**
 * UI-05 Knowledge Base admin view.
 *
 * <p>Admin-only per {@code AiAgentAdminRole.adminViews()} {@code @ViewPolicy} wired in 07-01.
 * Uploads are staged into Jmix {@link TemporaryStorage} via Vaadin's non-deprecated
 * {@link UploadHandler#toFile(com.vaadin.flow.server.streams.FileUploadCallback, com.vaadin.flow.server.streams.FileFactory)}
 * path — Jmix's {@code receiverType} uses {@code Upload.setReceiver(...)} which is
 * {@code @Deprecated(since="24.8", forRemoval=true)}, so we stick with the UploadHandler
 * callback. Each file lands under the temp root validated by
 * {@link KnowledgeDocumentUploadService#upload(String, String, java.util.Collection)} before
 * async ingestion begins. Only {@link FileRejectedEvent} is wired declaratively via
 * {@code @Subscribe} because it does not require {@code getReceiver()}.</p>
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
public class KnowledgeBaseView extends StandardListView<AiKnowledgeDocument> {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseView.class);

    private static final String MSG_UPLOAD_REJECTED = "upload.rejected";

    @ViewComponent
    private DataGrid<AiKnowledgeDocument> documentsDataGrid;
    @ViewComponent
    private JmixUpload documentUpload;
    @ViewComponent
    private CollectionLoader<AiKnowledgeDocument> documentsDl;
    @ViewComponent
    private CollectionContainer<AiKnowledgeDocument> documentsDc;
    @ViewComponent
    private MessageBundle messageBundle;

    @Autowired
    private KnowledgeDocumentUploadService uploadService;
    @Autowired
    private KnowledgeDocumentService documentService;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Notifications notifications;
    @Autowired
    private TemporaryStorage temporaryStorage;
    @Autowired
    private UiComponents uiComponents;

    /** Captured onAttach so the push listener can UI.access() the correct UI per-browser-tab. */
    private volatile UI ownerUi;
    private UUID pendingSelectionDocumentId;

    @Subscribe
    public void onInit(final InitEvent event) {
        documentUpload.setUploadHandler(UploadHandler.toFile((metadata, stagedFile) -> {
            try {
                uploadService.upload(stagedFile.toURI().toString(), metadata.contentType(), Collections.emptyList());
                notifications.create(messageBundle.formatMessage("toast.uploadStarted", metadata.fileName())).show();
                documentsDl.load();
            } catch (IllegalArgumentException ex) {
                log.warn("Upload rejected by staging-root allowlist: {}", ex.getMessage());
                notifyError(messageBundle.formatMessage(MSG_UPLOAD_REJECTED, metadata.fileName()));
                throw ex;
            } catch (Exception ex) {
                log.warn("Upload failed for {}", metadata.fileName(), ex);
                notifyError(messageBundle.formatMessage(MSG_UPLOAD_REJECTED, metadata.fileName()));
                throw ex;
            }
        }, metadata -> temporaryStorage.createFile().getFile()));
    }

    @Subscribe("documentUpload")
    public void onDocumentUploadFileRejected(final FileRejectedEvent event) {
        notifyError(messageBundle.formatMessage(MSG_UPLOAD_REJECTED, event.getErrorMessage()));
    }

    @Subscribe(id = "documentsDl", target = Target.DATA_LOADER)
    public void onDocumentsDlPostLoad(final CollectionLoader.PostLoadEvent<AiKnowledgeDocument> event) {
        selectPendingDocumentIfNeeded();
    }

    @Supply(to = "documentsDataGrid.status", subject = "renderer")
    private Renderer<AiKnowledgeDocument> documentsDataGridStatusRenderer() {
        return new ComponentRenderer<>(this::createStatusBadge, this::updateStatusBadge);
    }

    @Subscribe("documentsDataGrid.reingest")
    public void onReingestAction(final ActionPerformedEvent event) {
        onReingestClick();
    }

    @Subscribe("documentsDataGrid.delete")
    public void onDeleteAction(final ActionPerformedEvent event) {
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
            AiKnowledgeDocument doc = documentsDc.getItemOrNull(event.documentId());
            if (doc == null) {
                // Row not currently loaded (new upload from another tab) — trigger reload.
                documentsDl.load();
                return;
            }
            doc.setStatus(event.status());
            doc.setErrorMessage(event.status() == AiKnowledgeDocumentStatus.FAILED
                    ? event.errorMessage() : null);
            documentsDc.replaceItem(doc);
        });
    }

    /**
     * Jmix-native deep-link hook. Fires after {@code InitEvent} on navigation-opened views —
     * see Jmix docs: Flow UI → Views → View Events → QueryParametersChangeEvent.
     * The captured id is applied after the loader finishes via {@link #selectPendingDocumentIfNeeded()}.
     */
    @Subscribe
    public void onQueryParametersChange(final View.QueryParametersChangeEvent event) {
        event.getQueryParameters()
                .getSingleParameter("documentId")
                .ifPresent(raw -> {
                    try {
                        pendingSelectionDocumentId = UUID.fromString(raw);
                    } catch (IllegalArgumentException ex) {
                        log.debug("Ignoring malformed documentId query param: {}", raw);
                    }
                });
    }

    // --- Internals -------------------------------------------------------------

    private Span createStatusBadge() {
        Span badge = uiComponents.create(Span.class);
        badge.getElement().getThemeList().add("badge");
        return badge;
    }

    private void updateStatusBadge(Span badge, AiKnowledgeDocument doc) {
        AiKnowledgeDocumentStatus status = doc.getStatus();
        String key = "status." + (status == null ? "pending" : status.name().toLowerCase(Locale.ROOT));
        badge.setText(messageBundle.getMessage(key));
        // Reset theme list — ComponentRenderer reuses the Span across rows, so stale
        // variants from a previous row would leak onto the current one.
        badge.getElement().getThemeList().clear();
        badge.getElement().getThemeList().add("badge");
        for (String variant : statusTheme(status).split(" ")) {
            badge.getElement().getThemeList().add(variant);
        }
        if (status == AiKnowledgeDocumentStatus.FAILED && doc.getErrorMessage() != null) {
            badge.getElement().setAttribute("title", doc.getErrorMessage());
        } else {
            badge.getElement().removeAttribute("title");
        }
    }

    private void selectPendingDocumentIfNeeded() {
        UUID documentId = pendingSelectionDocumentId;
        if (documentId == null) {
            return;
        }
        pendingSelectionDocumentId = null;
        AiKnowledgeDocument doc = documentsDc.getItemOrNull(documentId);
        if (doc != null) {
            documentsDataGrid.select(doc);
        }
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

    private void onReingestClick() {
        AiKnowledgeDocument doc = documentsDataGrid.getSingleSelectedItem();
        if (doc == null) {
            return;
        }
        dialogs.createOptionDialog()
                .withHeader(messageBundle.formatMessage("confirm.reingest.title", doc.getFileName()))
                .withText(messageBundle.getMessage("confirm.reingest.body"))
                .withActions(
                        new DialogAction(DialogAction.Type.OK)
                                .withVariant(ActionVariant.PRIMARY)
                                .withHandler(ev -> {
                                    try {
                                        documentService.reingest(doc.getId());
                                        documentsDl.load();
                                    } catch (Exception ex) {
                                        log.warn("reingest failed for {}", doc.getId(), ex);
                                        notifyDetailedError("reingest.failed", ex.getMessage());
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
        dialogs.createOptionDialog()
                .withHeader(messageBundle.formatMessage("confirm.delete.title", doc.getFileName()))
                .withText(messageBundle.getMessage("confirm.delete.body"))
                .withActions(
                        new DialogAction(DialogAction.Type.OK)
                                .withVariant(ActionVariant.DANGER)
                                .withHandler(ev -> {
                                    try {
                                        documentService.delete(doc.getId());
                                        documentsDl.load();
                                    } catch (Exception ex) {
                                        log.warn("delete failed for {}", doc.getId(), ex);
                                        notifyDetailedError("delete.failed", ex.getMessage());
                                    }
                                }),
                        new DialogAction(DialogAction.Type.CANCEL))
                .open();
    }

    private void notifyError(String message) {
        notifications.create(message)
                .withThemeVariant(NotificationVariant.LUMO_ERROR)
                .show();
    }

    private void notifyDetailedError(String key, String detail) {
        String message = messageBundle.getMessage(key);
        if (detail != null && !detail.isBlank()) {
            message = message + " " + detail;
        }
        notifyError(message);
    }
}
