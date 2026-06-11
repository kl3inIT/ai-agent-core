package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.CardVariant;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vn.agent.entity.AiTaskFile;
import io.jmix.core.Messages;
import io.jmix.flowui.component.card.JmixCard;

import java.text.DecimalFormat;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Inline attachment card rendered directly in the chat timeline (jmix-crm style port of
 * {@code AiAttachmentCard}). Replaces the retired right-pane card grid: an uploaded
 * {@link AiTaskFile} now shows up as a downloadable card anchored under the turn it was
 * attached to.
 *
 * <p>This is a pure renderer — it owns no data access. The owning {@code ChatPanelFragment}
 * passes {@code onDownload} / {@code onDelete} callbacks so the file-storage, persistence and
 * UI-event wiring stay in one place (and stay unit-testable on the fragment). Icon selection
 * and human-readable size formatting mirror the deleted {@code AiTaskFileCardFragmentRenderer}.
 */
public class AiTaskFileInlineCard extends JmixCard {

    private final Messages messages;

    public AiTaskFileInlineCard(Messages messages) {
        this.messages = messages;
        addThemeVariants(CardVariant.LUMO_OUTLINED);
        addClassName("ai-agent-attachment-card");
        setWidthFull();
    }

    /**
     * Binds the card to a task file. {@code onDelete} may be {@code null} to render a
     * download-only card (no trash button).
     */
    public void setTaskFile(AiTaskFile file,
                            Consumer<AiTaskFile> onDownload,
                            Consumer<AiTaskFile> onDelete) {
        removeAll();

        setHeaderPrefix(resolveIcon(file.getFilename()).create());

        H5 title = new H5(safeFilename(file));
        title.addClassNames("m-0", "ai-agent-attachment-card__name");
        setTitle(title);

        HorizontalLayout actions = new HorizontalLayout();
        actions.setSpacing(false);
        actions.setPadding(false);

        if (file.getStorageRef() != null && onDownload != null) {
            Button downloadButton = new Button(VaadinIcon.DOWNLOAD.create());
            downloadButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE,
                    ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
            downloadButton.setAriaLabel(messages.getMessage("chatView.attachments.action.download"));
            downloadButton.addClickListener(event -> onDownload.accept(file));
            actions.add(downloadButton);
        }
        if (onDelete != null) {
            Button deleteButton = new Button(VaadinIcon.TRASH.create());
            deleteButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE,
                    ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
            deleteButton.setAriaLabel(messages.getMessage("chatView.attachments.action.delete"));
            deleteButton.addClickListener(event -> onDelete.accept(file));
            actions.add(deleteButton);
        }
        if (actions.getComponentCount() > 0) {
            setHeaderSuffix(actions);
        }

        Span size = new Span(humanizeSize(file.getSizeBytes()));
        size.addClassNames("text-secondary", "text-s", "ai-agent-attachment-card__size");
        add(size);
    }

    private String safeFilename(AiTaskFile file) {
        String filename = file.getFilename();
        if (filename != null && !filename.isBlank()) {
            return filename;
        }
        return messages.getMessage("chatView.attachments.missingFileName");
    }

    /**
     * Per-extension icon table. Mirrors the deleted card renderer (images added because the
     * agent-core MIME allowlist accepts PNG/JPG/GIF/WEBP).
     */
    static VaadinIcon resolveIcon(String fileName) {
        return switch (fileExtension(fileName)) {
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

    /** Humanise sizeBytes to "12.3 KB" / "4.5 MB" — never raw bytes. Binary 1024 base. */
    static String humanizeSize(Long bytes) {
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
