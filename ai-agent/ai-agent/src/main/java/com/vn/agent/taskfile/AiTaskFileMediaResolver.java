package com.vn.agent.taskfile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiTaskFile;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import io.jmix.core.DataManager;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorage;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves task-file rows for a conversation into Spring AI {@link Media} objects so the
 * chat client can pass them to the multimodal LLM.
 *
 * <p>Phase 13.1 RES-01: returns Media for ALL non-expired AiTaskFile rows in the
 * conversation, applying token-budget caps from {@link AiTaskFileProperties}
 * ({@code perTurnMaxFiles}, {@code perTurnMaxTotalBytes}) with LRU eviction on
 * {@code createdDate DESC}. Emits one {@code task_file_budget_exceeded} audit on
 * overflow via {@link AuditWriter#writeToolCall} (REQUIRES_NEW; failure is swallowed
 * with a log.warn so the chat turn cannot be poisoned by a downstream audit error).
 *
 * <p>Verbatim port of {@code D:/DTH/jmix-crm/.../AiAttachmentMediaResolver}
 * (constants, MIME table, helpers preserved line-for-line) — adapted for the
 * Phase 13 entity name {@code ai_AiTaskFile}.
 *
 * <p>Task-file pathway is structurally disjoint from KB ingestion (TEST-16;
 * see this package's {@code package-info.java} for the forbidden-token list).
 */
@Component
public class AiTaskFileMediaResolver {

    private static final Logger log = LoggerFactory.getLogger(AiTaskFileMediaResolver.class);

    private static final int MAX_MEDIA_NAME_LENGTH = 96;

    /**
     * Phase 13.1 UAT-fix-02 — per-document text-extraction cap. Spring AI 1.1
     * OpenAI {@code ChatModel} only renders {@link Media} as {@code image_url}
     * content parts; non-image documents (PDF/DOCX/XLSX/TXT/CSV/HTML/MD) cause
     * a {@code 400 BAD_REQUEST} from OpenRouter (and direct OpenAI for most
     * models). Workaround: extract text via Apache Tika and prepend to the
     * user-message text. Cap each document at ~50KB chars (~12-15K tokens) so
     * a single huge PDF cannot blow the request token budget. Truncation is
     * reported via {@link DocumentText#truncated()}.
     */
    static final int MAX_DOC_TEXT_CHARS = 50_000;

    private static final Set<MimeType> IMAGE_MIME_TYPES = Set.of(
            Media.Format.IMAGE_PNG,
            Media.Format.IMAGE_JPEG,
            Media.Format.IMAGE_GIF,
            Media.Format.IMAGE_WEBP
    );

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
    private final AiTaskFileProperties taskFileProperties;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;

    public AiTaskFileMediaResolver(DataManager dataManager,
                                   FileStorageLocator fileStorageLocator,
                                   AiTaskFileProperties taskFileProperties,
                                   AuditWriter auditWriter,
                                   CurrentAuthentication currentAuthentication) {
        this.dataManager = dataManager;
        this.fileStorageLocator = fileStorageLocator;
        this.taskFileProperties = taskFileProperties;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
    }

    /**
     * Loads ALL non-expired task-file rows for the conversation in {@code createdDate DESC}
     * order, applies {@link AiTaskFileProperties#getPerTurnMaxFiles()} and
     * {@link AiTaskFileProperties#getPerTurnMaxTotalBytes()} caps with LRU eviction
     * (oldest dropped first because the iteration walks newest-first), and converts each
     * kept row into a {@link Media} object.
     *
     * <p>Sentinel {@code -1} on either cap disables that cap; setting both to {@code -1}
     * returns every row and emits zero audit rows.
     *
     * <p>Uses {@link DataManager} (NOT {@link io.jmix.core.UnconstrainedDataManager}) so
     * the user row-level policy filters by {@code userUsername} (Plan 13-01); cross-user
     * reads are structurally impossible.
     *
     * <p>Returns {@link Resolved#empty()} when {@code conversationId} is null or no
     * non-expired rows exist; the caller injects {@code .media(...)} only when
     * {@link Resolved#isEmpty()} is false.
     */
    public Resolved resolveActive(UUID conversationId) {
        if (conversationId == null) {
            return Resolved.empty();
        }
        boolean ttlDisabled = taskFileProperties.getTtlSeconds() == -1L;
        var loader = dataManager.load(AiTaskFile.class)
                .query(ttlDisabled
                        ? "select e from ai_AiTaskFile e " +
                        "where e.conversation.id = :cid order by e.createdDate desc"
                        : "select e from ai_AiTaskFile e " +
                        "where e.conversation.id = :cid and e.expiresAt > :now " +
                        "order by e.createdDate desc")
                .parameter("cid", conversationId);
        if (!ttlDisabled) {
            loader.parameter("now", OffsetDateTime.now());
        }
        List<AiTaskFile> rows = loader.list();
        if (rows.isEmpty()) {
            return Resolved.empty();
        }

        int maxFiles = taskFileProperties.getPerTurnMaxFiles();          // -1 disables file cap
        long maxBytes = taskFileProperties.getPerTurnMaxTotalBytes();    // -1 disables byte cap

        List<AiTaskFile> kept = new ArrayList<>();
        long keptBytes = 0L;
        int droppedRows = 0;
        long droppedBytes = 0L;
        long totalBytes = 0L;
        for (AiTaskFile row : rows) {
            long size = row.getSizeBytes() == null ? 0L : row.getSizeBytes();
            totalBytes += size;
            boolean fileCapHit = (maxFiles >= 0) && (kept.size() >= maxFiles);
            boolean byteCapHit = (maxBytes >= 0) && (keptBytes + size > maxBytes);
            if (fileCapHit || byteCapHit) {
                droppedRows++;
                droppedBytes += size;
                continue;
            }
            kept.add(row);
            keptBytes += size;
        }

        boolean budgetExceeded = droppedRows > 0;
        if (budgetExceeded) {
            try {
                String argumentsJson = buildBudgetArgumentsJson(conversationId,
                        rows.size(), totalBytes, kept.size(), keptBytes,
                        droppedRows, droppedBytes, maxFiles, maxBytes);
                auditWriter.writeToolCall(
                        /*parentId*/ null,
                        /*runId*/ runContextRunIdOrNew(),
                        /*userUsername*/ currentUsername(),
                        /*conversationId*/ conversationId,
                        /*toolName*/ "task_file_budget_exceeded",
                        argumentsJson,
                        /*resultSummary*/ null,
                        /*latencyMs*/ 0L,
                        AiToolCallOutcome.SUCCESS,
                        /*denialReason*/ null,
                        /*errorClass*/ null);
            } catch (RuntimeException auditEx) {
                log.warn("Failed to write task_file_budget_exceeded audit row for conv={}",
                        conversationId, auditEx);
            }
        }

        // Phase 13.1 UAT-fix-02 — split kept rows by MIME family. Images keep their Media
        // representation (Spring AI OpenAI renders as image_url, accepted by OpenRouter and
        // direct OpenAI on every vision model). Documents (PDF/DOCX/XLSX/TXT/CSV/HTML/MD)
        // are extracted to text via Apache Tika and returned as DocumentText so the chat
        // service prepends them to the user-message text — chat/completions does not accept
        // non-image data URLs and would 400 if we tried to send them as Media.
        List<Media> media = new ArrayList<>();
        List<DocumentText> documentTexts = new ArrayList<>();
        for (AiTaskFile row : kept) {
            MimeType mime = resolveSupportedMimeType(row.getContentType(), row.getFilename());
            if (IMAGE_MIME_TYPES.contains(mime)) {
                media.add(buildMedia(row, mime));
            } else {
                documentTexts.add(extractDocumentText(row));
            }
        }
        return new Resolved(media, documentTexts, budgetExceeded);
    }

    private String buildBudgetArgumentsJson(UUID conversationId,
                                            int totalRows, long totalBytes,
                                            int keptRows, long keptBytes,
                                            int droppedRows, long droppedBytes,
                                            int maxFiles, long maxBytes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(BudgetExceededAuditKeys.CONVERSATION_ID, conversationId.toString());
        payload.put(BudgetExceededAuditKeys.TOTAL_ROWS, totalRows);
        payload.put(BudgetExceededAuditKeys.TOTAL_BYTES, totalBytes);
        payload.put(BudgetExceededAuditKeys.KEPT_ROWS, keptRows);
        payload.put(BudgetExceededAuditKeys.KEPT_BYTES, keptBytes);
        payload.put(BudgetExceededAuditKeys.DROPPED_ROWS, droppedRows);
        payload.put(BudgetExceededAuditKeys.DROPPED_BYTES, droppedBytes);
        payload.put(BudgetExceededAuditKeys.PER_TURN_MAX_FILES, maxFiles);
        payload.put(BudgetExceededAuditKeys.PER_TURN_MAX_TOTAL_BYTES, maxBytes);
        try {
            return new ObjectMapper().writeValueAsString(payload);
        } catch (JsonProcessingException jsonEx) {
            log.warn("Failed to serialize task_file_budget_exceeded argumentsJson; falling back to literal",
                    jsonEx);
            return String.format(
                    "{\"%s\":\"%s\",\"%s\":%d,\"%s\":%d,\"%s\":%d,\"%s\":%d,\"%s\":%d,\"%s\":%d,\"%s\":%d,\"%s\":%d}",
                    BudgetExceededAuditKeys.CONVERSATION_ID, conversationId,
                    BudgetExceededAuditKeys.TOTAL_ROWS, totalRows,
                    BudgetExceededAuditKeys.TOTAL_BYTES, totalBytes,
                    BudgetExceededAuditKeys.KEPT_ROWS, keptRows,
                    BudgetExceededAuditKeys.KEPT_BYTES, keptBytes,
                    BudgetExceededAuditKeys.DROPPED_ROWS, droppedRows,
                    BudgetExceededAuditKeys.DROPPED_BYTES, droppedBytes,
                    BudgetExceededAuditKeys.PER_TURN_MAX_FILES, maxFiles,
                    BudgetExceededAuditKeys.PER_TURN_MAX_TOTAL_BYTES, maxBytes);
        }
    }

    private UUID runContextRunIdOrNew() {
        UUID runId = RunContext.get();
        return runId != null ? runId : UUID.randomUUID();
    }

    private String currentUsername() {
        try {
            UserDetails user = currentAuthentication.getUser();
            if (user != null && StringUtils.hasText(user.getUsername())) {
                return user.getUsername();
            }
        } catch (RuntimeException anonymous) {
            // No authenticated user (e.g. test without security context) — fall through.
        }
        return "system";
    }

    private Media buildMedia(AiTaskFile row, MimeType mime) {
        FileRef fileRef = row.getStorageRef();
        if (fileRef == null) {
            throw new IllegalStateException("AiTaskFile has no storageRef: " + row.getId());
        }
        return Media.builder()
                .mimeType(mime)
                .data(readFileBytes(fileRef))
                .name(sanitizeMediaName(row.getFilename()))
                .build();
    }

    /**
     * Phase 13.1 UAT-fix-02 — extract text content from a non-image AiTaskFile via
     * Apache Tika ({@link TikaDocumentReader}). Tika auto-detects document type from
     * the byte stream (PDF / DOCX / XLSX / TXT / CSV / HTML / MD all supported via
     * the Tika app classpath bundled with {@code spring-ai-tika-document-reader}).
     * The result is capped at {@link #MAX_DOC_TEXT_CHARS} characters so a single
     * large file cannot blow the LLM token budget. Failure is logged and a
     * placeholder is returned so the chat turn never fails because of one
     * unreadable document.
     */
    private DocumentText extractDocumentText(AiTaskFile row) {
        FileRef fileRef = row.getStorageRef();
        if (fileRef == null) {
            log.warn("AiTaskFile has no storageRef: {}", row.getId());
            return new DocumentText(safeFilename(row), "[Empty file]", false);
        }
        String filename = safeFilename(row);
        try {
            byte[] bytes = readFileBytes(fileRef);
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> documents = reader.get();
            String text = documents.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n"));
            boolean truncated = text.length() > MAX_DOC_TEXT_CHARS;
            if (truncated) {
                text = text.substring(0, MAX_DOC_TEXT_CHARS);
            }
            return new DocumentText(filename, text, truncated);
        } catch (RuntimeException tikaEx) {
            log.warn("Tika failed to extract text from task file {} ({}): {}",
                    filename, row.getId(), tikaEx.getMessage());
            return new DocumentText(filename,
                    "[Failed to extract text from " + filename + "]", false);
        }
    }

    private String safeFilename(AiTaskFile row) {
        return StringUtils.hasText(row.getFilename()) ? row.getFilename() : "unnamed";
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
     * Result of {@link #resolveActive(UUID)} — the per-turn {@link Media} list (images
     * only; Spring AI OpenAI renders these as {@code image_url} content parts), the
     * per-turn {@link DocumentText} list (Tika-extracted text for non-image documents,
     * to be prepended into the user-message text), and a {@code budgetExceeded} flag
     * set when at least one row was dropped due to the {@code perTurnMaxFiles} /
     * {@code perTurnMaxTotalBytes} caps. The chat path uses the flag to surface a UI
     * toast (D-D1).
     */
    public record Resolved(List<Media> media,
                           List<DocumentText> documentTexts,
                           boolean budgetExceeded) {
        public static Resolved empty() {
            return new Resolved(List.of(), List.of(), false);
        }

        public boolean isEmpty() {
            return media.isEmpty() && documentTexts.isEmpty();
        }
    }

    /**
     * Phase 13.1 UAT-fix-02 — Tika-extracted text content for a single non-image
     * task file. The chat service prepends a labeled block of these into the user
     * message text since OpenAI/OpenRouter chat/completions does not accept document
     * data URLs as Media.
     *
     * @param filename  display name shown in the prepended block header
     * @param text      extracted text body, capped at {@link #MAX_DOC_TEXT_CHARS}
     * @param truncated whether the original document exceeded the cap and was sliced
     */
    public record DocumentText(String filename, String text, boolean truncated) {
    }
}
