package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vn.agent.entity.AiTaskFile;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Messages;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiEventPublisher;
import io.jmix.flowui.action.DialogAction;
import io.jmix.flowui.component.card.JmixCard;
import io.jmix.flowui.fragment.FragmentDescriptor;
import io.jmix.flowui.fragmentrenderer.FragmentRenderer;
import io.jmix.flowui.fragmentrenderer.RendererItemContainer;
import io.jmix.flowui.kit.action.ActionVariant;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.InstanceContainer;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.DecimalFormat;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Per-row card renderer for the right-pane Attachments grid (Phase 13.1 UI-01).
 *
 * <p>Verbatim port of CRM's {@code AiAttachmentCardFragmentRenderer} with five
 * adaptations: AiTaskFile entity binding, taskFileDc instance container id, filename
 * header property, +1 TRASH delete button, sizeValue replacing the source-icon hbox.
 *
 * <p>Open uses Jmix {@link io.jmix.flowui.download.Downloader} to stream the FileRef back as a download
 * (showNewWindow=true). Delete opens an option dialog (D-B2 confirm), then removes
 * the storage blob before the JPA row. If blob removal fails, the row is preserved
 * so the user can retry and the scheduled TTL cleanup can still discover it.
 */
@FragmentDescriptor("ai-task-file-card-fragment.xml")
@RendererItemContainer("taskFileDc")
public class AiTaskFileCardFragmentRenderer extends FragmentRenderer<JmixCard, AiTaskFile> {

    private static final Logger log = LoggerFactory.getLogger(AiTaskFileCardFragmentRenderer.class);

    @ViewComponent
    private H5 titleValue;
    @ViewComponent
    private Span sizeValue;
    @ViewComponent
    private Icon attachmentIcon;
    @ViewComponent
    private JmixButton openButton;
    @ViewComponent
    private JmixButton deleteButton;
    @ViewComponent
    private InstanceContainer<AiTaskFile> taskFileDc;

    @Autowired
    private io.jmix.flowui.download.Downloader downloader;
    @Autowired
    private DataManager dataManager;
    @Autowired
    private FileStorageLocator fileStorageLocator;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private Messages messages;
    @Autowired
    private Notifications notifications;
    @Autowired
    private UiEventPublisher uiEventPublisher;

    @Subscribe
    public void onReady(final ReadyEvent event) {
        openButton.setAriaLabel(message("chatView.attachments.action.download"));
        deleteButton.setAriaLabel(message("chatView.attachments.action.delete"));
        applyState(taskFileDc.getItemOrNull());
    }

    @Subscribe(id = "taskFileDc", target = Target.DATA_CONTAINER)
    public void onItemChange(final InstanceContainer.ItemChangeEvent<AiTaskFile> event) {
        applyState(event.getItem());
    }

    @Subscribe("openButton")
    public void onOpenButtonClick(final ClickEvent<JmixButton> event) {
        AiTaskFile row = taskFileDc.getItemOrNull();
        if (row == null || row.getStorageRef() == null) {
            return;
        }
        downloader.setShowNewWindow(true);
        downloader.download(row.getStorageRef());
    }

    @Subscribe("deleteButton")
    public void onDeleteButtonClick(final ClickEvent<JmixButton> event) {
        AiTaskFile row = taskFileDc.getItemOrNull();
        if (row == null) {
            return;
        }
        dialogs.createOptionDialog()
                .withHeader(message("chatView.attachments.deleteConfirm.title"))
                .withText(MessageFormat.format(
                        messages.getMessage("chatView.attachments.deleteConfirm.message"),
                        safeFilename(row)))
                .withActions(
                        new DialogAction(DialogAction.Type.YES)
                                .withVariant(ActionVariant.DANGER)
                                .withText(message("chatView.attachments.deleteConfirm.confirm"))
                                .withHandler(e -> performDelete(row)),
                        new DialogAction(DialogAction.Type.NO)
                                .withText(message("chatView.attachments.deleteConfirm.cancel")))
                .open();
    }

    private void performDelete(AiTaskFile row) {
        FileRef ref = row.getStorageRef();
        UUID conversationId = row.getConversation() == null ? null : row.getConversation().getId();
        if (ref != null) {
            try {
                FileStorage storage = fileStorageLocator.getByName(ref.getStorageName());
                storage.removeFile(ref);
            } catch (RuntimeException blobEx) {
                log.warn("Failed to remove blob for AiTaskFile {}; preserving row for retry",
                        row.getId(), blobEx);
                showDeleteFailed();
                return;
            }
        }
        try {
            dataManager.remove(row);
        } catch (RuntimeException ex) {
            log.warn("Failed to remove AiTaskFile {} after blob delete", row.getId(), ex);
            showDeleteFailed();
            return;
        }
        publishDeletedEvent(conversationId, row.getId());
    }

    private void applyState(AiTaskFile row) {
        if (row == null) {
            titleValue.setText(message("chatView.attachments.missingFileName"));
            attachmentIcon.setIcon(VaadinIcon.FILE_O);
            sizeValue.setText("");
            openButton.setEnabled(false);
            deleteButton.setEnabled(false);
            return;
        }
        titleValue.setText(safeFilename(row));
        attachmentIcon.setIcon(resolveIcon(row.getFilename()));
        sizeValue.setText(humanizeSize(row.getSizeBytes()));
        openButton.setEnabled(row.getStorageRef() != null);
        deleteButton.setEnabled(true);
    }

    private String safeFilename(AiTaskFile row) {
        String filename = row.getFilename();
        if (filename != null && !filename.isBlank()) {
            return filename;
        }
        return message("chatView.attachments.missingFileName");
    }

    private void showDeleteFailed() {
        notifications.create(message("chatView.attachments.delete.failed"))
                .withThemeVariant(NotificationVariant.LUMO_WARNING)
                .show();
    }

    private void publishDeletedEvent(UUID conversationId, UUID taskFileId) {
        try {
            uiEventPublisher.publishEventForCurrentUI(
                    new AiTaskFileDeletedUiEvent(this, conversationId));
        } catch (RuntimeException eventFailure) {
            log.debug("Failed to publish task-file delete UI event for {}", taskFileId, eventFailure);
        }
    }

    private String message(String key) {
        // Phase 13.1 UAT-fix-02 — root-bundle no-group form. Prior pack-form
        // messages.getMessage("com.vn.agent", key) returned the literal key because
        // the bundle is registered as the default MessageSource, not under the
        // "com.vn.agent" group label.
        return messages.getMessage(key);
    }

    /**
     * Per-extension icon table. Mirrors the CRM analog with an additional image case
     * (PNG/JPG/JPEG/GIF/WEBP) because the agent-core MIME allowlist accepts images.
     */
    private VaadinIcon resolveIcon(String fileName) {
        String ext = fileExtension(fileName);
        return switch (ext) {
            case "csv", "xlsx", "xls" -> VaadinIcon.TABLE;
            case "pdf", "html", "htm", "md", "txt" -> VaadinIcon.FILE_TEXT_O;
            case "png", "jpg", "jpeg", "gif", "webp" -> VaadinIcon.FILE_PICTURE;
            default -> VaadinIcon.FILE_O;
        };
    }

    private static String fileExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Humanise sizeBytes to "12.3 KB" / "4.5 MB" / "1.2 GB" — never raw bytes.
     * Binary 1024 base per common Jmix-app convention.
     */
    private static String humanizeSize(Long bytes) {
        if (bytes == null || bytes <= 0L) {
            return "0 KB";
        }
        double v = bytes.doubleValue();
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return new DecimalFormat("#,##0.#").format(v) + " " + units[u];
    }
}
