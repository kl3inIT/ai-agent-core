package com.vn.agent.view.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
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
import com.vn.agent.rag.UpdatePermissionsResult;
import com.vn.agent.utils.DataGridRenderers;
import com.vn.agent.utils.DataGridRenderers.ActionColumnType;
import com.vn.agent.utils.NotificationUtils;
import com.vn.agent.view.exposure.MetaclassComboBoxHelper;
import io.jmix.core.MessageTools;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
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
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import io.jmix.security.model.ResourceRole;
import io.jmix.security.role.ResourceRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String MSG_UPLOAD_REJECTED = "knowledgeBase.upload.rejected";
    /** Em dash U+2014 placeholder for empty source-entity cell. */
    private static final String SOURCE_ENTITY_EMPTY = "—";

    @ViewComponent
    private DataGrid<AiKnowledgeDocument> documentsDataGrid;
    @ViewComponent
    private JmixUpload documentUpload;
    @ViewComponent
    private CollectionLoader<AiKnowledgeDocument> documentsDl;
    @ViewComponent
    private CollectionContainer<AiKnowledgeDocument> documentsDc;
    @ViewComponent
    private ComboBox<MetaClass> sourceEntityNameComboBox;
    @ViewComponent
    private CheckboxGroup<String> uploadAllowedRolesGroup;

    @Autowired
    private KnowledgeDocumentUploadService uploadService;
    @Autowired
    private KnowledgeDocumentService documentService;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Messages messages;
    @Autowired
    private MessageTools messageTools;
    @Autowired
    private Metadata metadataApi;
    @Autowired
    private TemporaryStorage temporaryStorage;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private MetaclassComboBoxHelper metaclassComboBoxHelper;
    @Autowired
    private ResourceRoleRepository resourceRoleRepository;

    /** Captured onAttach so the push listener can UI.access() the correct UI per-browser-tab. */
    private volatile UI ownerUi;
    private UUID pendingSelectionDocumentId;

    @Subscribe
    public void onInit(final InitEvent event) {
        // Populate the source-entity ComboBox via the shared helper (Plan 10-06).
        sourceEntityNameComboBox.setItems(metaclassComboBoxHelper.buildFilteredList());
        sourceEntityNameComboBox.setItemLabelGenerator(mc ->
                messageTools.getEntityCaption(mc) + " (" + mc.getName() + ")");

        // WARNING-08: populate the upload-form allowedRoles checkbox group with the same
        // filtered list used in the edit-permissions dialog (system-* roles excluded —
        // WARNING-02). Without this field every upload landed admin-only-visible.
        List<String> uploadRoleCodes = resourceRoleRepository.getAllRoles().stream()
                .map(ResourceRole::getCode)
                .filter(code -> !isSystemRole(code))
                .sorted(Comparator.naturalOrder())
                .toList();
        uploadAllowedRolesGroup.setItems(uploadRoleCodes);

        documentUpload.setUploadHandler(UploadHandler.toFile((metadata, stagedFile) -> {
            try {
                // Plan 10-08 / D-07: capture sourceEntityName at upload time so it is
                // persisted on the document BEFORE the async ingester runs.
                MetaClass selectedMc = sourceEntityNameComboBox.getValue();
                String sourceEntityName = selectedMc != null ? selectedMc.getName() : null;
                // WARNING-08: pass the admin-selected role list (may be empty — empty means
                // admin-only-visible per the D-05 fail-closed retrieval contract).
                Set<String> selectedRoles = uploadAllowedRolesGroup.getValue();
                Collection<String> roles = selectedRoles == null
                        ? Collections.emptySet()
                        : new LinkedHashSet<>(selectedRoles);
                uploadService.upload(stagedFile.toURI().toString(), metadata.contentType(),
                        roles, sourceEntityName);
                notifications.create(messages.formatMessage("knowledgeBase.toast.uploadStarted", metadata.fileName())).show();
                documentsDl.load();
            } catch (IllegalArgumentException ex) {
                log.warn("Upload rejected by staging-root allowlist: {}", ex.getMessage());
                notifyError(messages.formatMessage(MSG_UPLOAD_REJECTED, metadata.fileName()));
                throw ex;
            } catch (Exception ex) {
                log.warn("Upload failed for {}", metadata.fileName(), ex);
                notifyError(messages.formatMessage(MSG_UPLOAD_REJECTED, metadata.fileName()));
                throw ex;
            }
        }, metadata -> temporaryStorage.createFile().getFile()));
    }

    @Subscribe("documentUpload")
    public void onDocumentUploadFileRejected(final FileRejectedEvent event) {
        notifyError(messages.formatMessage(MSG_UPLOAD_REJECTED, event.getErrorMessage()));
    }

    @Subscribe(id = "documentsDl", target = Target.DATA_LOADER)
    public void onDocumentsDlPostLoad(final CollectionLoader.PostLoadEvent<AiKnowledgeDocument> event) {
        selectPendingDocumentIfNeeded();
    }

    @Supply(to = "documentsDataGrid.status", subject = "renderer")
    private Renderer<AiKnowledgeDocument> documentsDataGridStatusRenderer() {
        return DataGridRenderers.buildBadgeColumn(
                uiComponents,
                doc -> messages.getMessage("knowledgeBase.status."
                        + (doc.getStatus() == null ? "pending"
                                : doc.getStatus().name().toLowerCase(Locale.ROOT))),
                doc -> statusTheme(doc.getStatus()),
                (badge, doc) -> {
                    if (doc.getStatus() == AiKnowledgeDocumentStatus.FAILED
                            && doc.getErrorMessage() != null) {
                        badge.getElement().setAttribute("title", doc.getErrorMessage());
                    } else {
                        badge.getElement().removeAttribute("title");
                    }
                });
    }

    @Supply(to = "documentsDataGrid.actions", subject = "renderer")
    private Renderer<AiKnowledgeDocument> documentsDataGridActionsRenderer() {
        return DataGridRenderers.buildActionsColumn(
                uiComponents,
                EnumSet.of(ActionColumnType.RELOAD, ActionColumnType.DELETE),
                (row, type) -> {
                    documentsDataGrid.select(row);
                    switch (type) {
                        case RELOAD -> onReingestClick();
                        case DELETE -> onDeleteClick();
                        default -> { }
                    }
                });
    }

    @Subscribe("documentsDataGrid.reingest")
    public void onReingestAction(final ActionPerformedEvent event) {
        onReingestClick();
    }

    @Subscribe("documentsDataGrid.delete")
    public void onDeleteAction(final ActionPerformedEvent event) {
        onDeleteClick();
    }

    @Subscribe("documentsDataGrid.editPermissions")
    public void onEditPermissionsAction(final ActionPerformedEvent event) {
        onEditPermissionsClick();
    }

    /**
     * Renders the source-entity column. Shows the localized entity caption with the
     * raw {@code MetaClass.getName()} in parentheses (matching the ComboBox label
     * format), or an em-dash placeholder when the document has no link
     * (Plan 10-08, D-06 legacy-doc contract).
     */
    @Supply(to = "documentsDataGrid.sourceEntity", subject = "renderer")
    private Renderer<AiKnowledgeDocument> documentsDataGridSourceEntityRenderer() {
        return new ComponentRenderer<>(doc -> {
            Span span = new Span();
            String name = doc.getSourceEntityName();
            if (name == null || name.isBlank()) {
                span.setText(SOURCE_ENTITY_EMPTY);
            } else {
                MetaClass mc = metadataApi.getSession().findClass(name);
                span.setText(mc != null
                        ? messageTools.getEntityCaption(mc) + " (" + name + ")"
                        : name);
            }
            return span;
        });
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
                .withHeader(messages.formatMessage("knowledgeBase.confirm.reingest.title", doc.getFileName()))
                .withText(messages.getMessage("knowledgeBase.confirm.reingest.body"))
                .withActions(
                        new DialogAction(DialogAction.Type.OK)
                                .withVariant(ActionVariant.PRIMARY)
                                .withHandler(ev -> {
                                    try {
                                        documentService.reingest(doc.getId());
                                        documentsDl.load();
                                    } catch (Exception ex) {
                                        log.warn("reingest failed for {}", doc.getId(), ex);
                                        NotificationUtils.errorWithDetail(notifications, messages,
                                                "knowledgeBase.reingest.failed", ex);
                                    }
                                }),
                        new DialogAction(DialogAction.Type.CANCEL))
                .open();
    }

    private void onEditPermissionsClick() {
        AiKnowledgeDocument doc = documentsDataGrid.getSingleSelectedItem();
        if (doc == null) {
            return;
        }

        Dialog dialog = new Dialog();
        // WARNING-06: keys live in the project root bundle (per memory
        // feedback_jmix_messages_over_spring "keep keys in root bundle"); use the
        // package-default lookup to keep the whole class consistent with the
        // formatMessage/getMessage(key) calls used elsewhere in this file.
        dialog.setHeaderTitle(messages.getMessage("knowledgeBase.action.editPermissions"));
        dialog.setWidth("420px");

        // Roles multi-select — populated from Jmix ResourceRoleRepository, filtered to
        // exclude Jmix system roles (system-full-access, system-minimal, etc.) so admins
        // cannot accidentally pin a document to a role no interactive user holds.
        // WARNING-02: pinning to system-full-access only would consume embedding storage
        // while the document remains invisible to all end users.
        CheckboxGroup<String> rolesGroup = new CheckboxGroup<>();
        rolesGroup.setLabel(messages.getMessage("knowledgeBase.upload.field.allowedRoles"));
        List<String> allRoleCodes = resourceRoleRepository.getAllRoles().stream()
                .map(ResourceRole::getCode)
                .filter(code -> !isSystemRole(code))
                .sorted(Comparator.naturalOrder())
                .toList();
        rolesGroup.setItems(allRoleCodes);
        rolesGroup.setValue(parseAllowedRoles(doc.getAllowedRolesJson()));

        // Source-entity ComboBox — same helper as upload form.
        ComboBox<MetaClass> entityCombo = new ComboBox<>();
        entityCombo.setLabel(messages.getMessage("knowledgeBase.upload.field.sourceEntityName"));
        entityCombo.setHelperText(messages.getMessage(
                "knowledgeBase.upload.field.sourceEntityName.helper"));
        entityCombo.setItems(metaclassComboBoxHelper.buildFilteredList());
        entityCombo.setItemLabelGenerator(mc ->
                messageTools.getEntityCaption(mc) + " (" + mc.getName() + ")");
        entityCombo.setClearButtonVisible(true);
        entityCombo.setWidthFull();
        if (doc.getSourceEntityName() != null) {
            MetaClass current = metadataApi.getSession().findClass(doc.getSourceEntityName());
            if (current != null) {
                entityCombo.setValue(current);
            }
        }

        VerticalLayout content = new VerticalLayout(rolesGroup, entityCombo);
        content.setPadding(false);
        dialog.add(content);

        Button saveBtn = new Button(
                messages.getMessage("knowledgeBase.dialog.editPermissions.save"),
                e -> confirmAndSavePermissions(doc, rolesGroup.getValue(), entityCombo.getValue(), dialog));
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelBtn = new Button(
                messages.getMessage("knowledgeBase.dialog.editPermissions.cancel"),
                e -> dialog.close());

        dialog.getFooter().add(cancelBtn, saveBtn);
        dialog.open();
    }

    /**
     * Reingest-required confirmation step (UI-SPEC: Surface 3, copywriting table).
     * On OK delegates to the service; the view does not orchestrate save+reingest
     * itself (Fix W3, CLAUDE.md "no business logic in views").
     */
    private void confirmAndSavePermissions(AiKnowledgeDocument doc,
                                           Set<String> selectedRoles,
                                           MetaClass selectedEntity,
                                           Dialog dialog) {
        dialogs.createOptionDialog()
                .withHeader(messages.getMessage(
                        "knowledgeBase.confirm.editPermissions.reingest.title"))
                .withText(messages.getMessage(
                        "knowledgeBase.confirm.editPermissions.reingest.body"))
                .withActions(
                        new DialogAction(DialogAction.Type.OK)
                                .withVariant(ActionVariant.PRIMARY)
                                .withHandler(ev -> {
                                    Collection<String> roles = selectedRoles == null
                                            ? Collections.emptySet()
                                            : new LinkedHashSet<>(selectedRoles);
                                    String sourceEntityName = selectedEntity != null
                                            ? selectedEntity.getName() : null;

                                    try {
                                        UpdatePermissionsResult result = documentService
                                                .updatePermissionsAndReingest(doc.getId(), roles, sourceEntityName);

                                        switch (result.status()) {
                                            case SAVED_AND_REINGESTING ->
                                                    notifications.create(messages.getMessage(
                                                            "knowledgeBase.notification.reingestStarted")).show();
                                            case SAVED_REINGEST_FAILED ->
                                                    notifyError(messages.getMessage(
                                                            "knowledgeBase.error.editPermissionsReingest"));
                                        }
                                    } catch (Exception ex) {
                                        // WARNING-07: catch DocumentNotFoundException (or any service-layer
                                        // exception) so the dialog closes cleanly instead of leaving the
                                        // user staring at an open dialog with no error feedback.
                                        log.warn("editPermissions update failed for {}", doc.getId(), ex);
                                        NotificationUtils.errorWithDetail(notifications, messages,
                                                "knowledgeBase.error.editPermissionsReingest", ex);
                                    }
                                    documentsDl.load();
                                    dialog.close();
                                }),
                        new DialogAction(DialogAction.Type.CANCEL))
                .open();
    }

    /**
     * WARNING-02: Jmix system roles (system-full-access, system-minimal, ...) are not
     * intended targets for "end-user can read this knowledge document". Filter them
     * out of the role-selection UI so admins only see project-supplied resource roles.
     */
    private static boolean isSystemRole(String code) {
        return code != null && code.startsWith("system-");
    }

    private static Set<String> parseAllowedRoles(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptySet();
        }
        try {
            List<String> roles = JSON.readValue(json, new TypeReference<>() {});
            return roles == null ? Collections.emptySet() : new LinkedHashSet<>(roles);
        } catch (Exception e) {
            log.warn("Failed to parse allowedRolesJson; treating as empty: {}", json, e);
            return Collections.emptySet();
        }
    }

    private void onDeleteClick() {
        AiKnowledgeDocument doc = documentsDataGrid.getSingleSelectedItem();
        if (doc == null) {
            return;
        }
        dialogs.createOptionDialog()
                .withHeader(messages.formatMessage("knowledgeBase.confirm.delete.title", doc.getFileName()))
                .withText(messages.getMessage("knowledgeBase.confirm.delete.body"))
                .withActions(
                        new DialogAction(DialogAction.Type.OK)
                                .withVariant(ActionVariant.DANGER)
                                .withHandler(ev -> {
                                    try {
                                        documentService.delete(doc.getId());
                                        documentsDl.load();
                                    } catch (Exception ex) {
                                        log.warn("delete failed for {}", doc.getId(), ex);
                                        NotificationUtils.errorWithDetail(notifications, messages,
                                                "knowledgeBase.delete.failed", ex);
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
}
