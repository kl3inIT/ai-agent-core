package com.vn.agent.taskfile;

import com.vn.agent.entity.AiTaskFile;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves pending {@link AiTaskFile} rows for a conversation into Spring AI
 * {@link Media} objects so the chat client can pass them to the multimodal LLM.
 *
 * <p>D-01: returns Media for files where {@code injectedAt IS NULL} —
 * single-turn injection only (REVIEWS HIGH-1 — stable pending marker, NOT
 * {@code messageId}; the projecting chat-memory repo deletes/reinserts
 * {@link com.vn.agent.entity.AiMessage} rows on every {@code saveAll}, so
 * {@code message} is unstable while {@code injectedAt} is set exactly once
 * by {@link AiTaskFileRepository#markInjected}).
 *
 * <p>Verbatim port of {@code D:/DTH/jmix-crm/.../AiAttachmentMediaResolver}
 * (constants, MIME table, helpers preserved line-for-line) — adapted for the
 * Phase 13 entity name {@code ai_AiTaskFile} and the {@code injectedAt is null}
 * predicate.
 *
 * <p>Task-file pathway is structurally disjoint from KB ingestion (TEST-16;
 * see this package's {@code package-info.java} for the forbidden-token list).
 */
@Component
public class AiTaskFileMediaResolver {

    private static final int MAX_MEDIA_NAME_LENGTH = 96;

    private static final Set<MimeType> SUPPORTED_MEDIA_TYPES = Set.of(
            Media.Format.DOC_PDF,
            Media.Format.DOC_CSV,
            Media.Format.DOC_DOC,
            Media.Format.DOC_DOCX,
            Media.Format.DOC_XLS,
            Media.Format.DOC_XLSX,
            Media.Format.DOC_HTML,
            Media.Format.DOC_TXT,
            Media.Format.DOC_MD,
            Media.Format.IMAGE_PNG,
            Media.Format.IMAGE_JPEG,
            Media.Format.IMAGE_GIF,
            Media.Format.IMAGE_WEBP
    );

    private static final Map<String, MimeType> EXTENSION_MIME_TYPES = Map.ofEntries(
            Map.entry(".pdf", Media.Format.DOC_PDF),
            Map.entry(".csv", Media.Format.DOC_CSV),
            Map.entry(".doc", Media.Format.DOC_DOC),
            Map.entry(".docx", Media.Format.DOC_DOCX),
            Map.entry(".xls", Media.Format.DOC_XLS),
            Map.entry(".xlsx", Media.Format.DOC_XLSX),
            Map.entry(".html", Media.Format.DOC_HTML),
            Map.entry(".htm", Media.Format.DOC_HTML),
            Map.entry(".txt", Media.Format.DOC_TXT),
            Map.entry(".md", Media.Format.DOC_MD),
            Map.entry(".png", Media.Format.IMAGE_PNG),
            Map.entry(".jpg", Media.Format.IMAGE_JPEG),
            Map.entry(".jpeg", Media.Format.IMAGE_JPEG),
            Map.entry(".gif", Media.Format.IMAGE_GIF),
            Map.entry(".webp", Media.Format.IMAGE_WEBP)
    );

    private final DataManager dataManager;
    private final FileStorageLocator fileStorageLocator;

    public AiTaskFileMediaResolver(DataManager dataManager,
                                   FileStorageLocator fileStorageLocator) {
        this.dataManager = dataManager;
        this.fileStorageLocator = fileStorageLocator;
    }

    /**
     * Loads pending task-file rows for the conversation and converts each into
     * a {@link Media} object. Pending = {@code injectedAt IS NULL AND
     * expiresAt > :now} per REVIEWS HIGH-1.
     *
     * <p>Uses {@link DataManager} (NOT {@link io.jmix.core.UnconstrainedDataManager})
     * so the user row-level policy filters by {@code userUsername} (Plan 13-01).
     *
     * <p>Returns {@link Resolved#empty()} when {@code conversationId} is null
     * or no pending rows exist; the caller injects {@code .media(...)} only
     * when {@link Resolved#isEmpty()} is false.
     */
    public Resolved resolvePending(UUID conversationId) {
        if (conversationId == null) {
            return Resolved.empty();
        }
        List<AiTaskFile> pending = dataManager.load(AiTaskFile.class)
                .query("select e from ai_AiTaskFile e " +
                        "where e.conversation.id = :cid and e.injectedAt is null " +
                        "and e.expiresAt > :now " +
                        "order by e.createdDate asc")
                .parameter("cid", conversationId)
                .parameter("now", OffsetDateTime.now())
                .list();
        if (pending.isEmpty()) {
            return Resolved.empty();
        }
        List<Media> media = pending.stream().map(this::buildMedia).toList();
        List<UUID> ids = pending.stream().map(AiTaskFile::getId).toList();
        return new Resolved(media, ids);
    }

    private Media buildMedia(AiTaskFile row) {
        FileRef fileRef = row.getStorageRef();
        if (fileRef == null) {
            throw new IllegalStateException("AiTaskFile has no storageRef: " + row.getId());
        }
        return Media.builder()
                .mimeType(resolveSupportedMimeType(row.getContentType(), row.getFilename()))
                .data(readFileBytes(fileRef))
                .name(sanitizeMediaName(row.getFilename()))
                .build();
    }

    private byte[] readFileBytes(FileRef fileRef) {
        FileStorage fileStorage = fileStorageLocator.getByName(fileRef.getStorageName());
        try (InputStream inputStream = fileStorage.openStream(fileRef)) {
            return inputStream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read task file from file storage: " + fileRef.getFileName(), e);
        }
    }

    private MimeType resolveSupportedMimeType(String rawMimeType, String fileName) {
        MimeType parsed = tryParseMimeType(rawMimeType);
        if (parsed != null && SUPPORTED_MEDIA_TYPES.contains(parsed)) {
            return parsed;
        }

        MimeType fromExtension = mimeTypeFromExtension(fileName);
        if (fromExtension != null && SUPPORTED_MEDIA_TYPES.contains(fromExtension)) {
            return fromExtension;
        }

        throw new IllegalArgumentException("Unsupported task-file media type for model input: " + fileName);
    }

    private MimeType tryParseMimeType(String rawMimeType) {
        if (!StringUtils.hasText(rawMimeType)) {
            return null;
        }
        try {
            return MimeTypeUtils.parseMimeType(rawMimeType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private MimeType mimeTypeFromExtension(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        String normalized = fileName.toLowerCase();
        return EXTENSION_MIME_TYPES.entrySet().stream()
                .filter(entry -> normalized.endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private String sanitizeMediaName(String fileName) {
        String sanitized = (StringUtils.hasText(fileName) ? fileName : "uploaded-file")
                .replaceAll("[^A-Za-z0-9\\s\\-()\\[\\]]", "_")
                .replaceAll("\\s+", " ")
                .trim();

        if (!StringUtils.hasText(sanitized)) {
            sanitized = "uploaded-file";
        }
        return sanitized.length() > MAX_MEDIA_NAME_LENGTH ? sanitized.substring(0, MAX_MEDIA_NAME_LENGTH) : sanitized;
    }

    /**
     * Result of {@link #resolvePending(UUID)} — the pending {@link Media} list
     * and the corresponding {@link AiTaskFile} ids that the caller will pass
     * to {@link AiTaskFileRepository#markInjected} after the user message is
     * persisted.
     */
    public record Resolved(List<Media> media, List<UUID> taskFileIds) {
        public static Resolved empty() {
            return new Resolved(List.of(), List.of());
        }

        public boolean isEmpty() {
            return media.isEmpty();
        }
    }
}
