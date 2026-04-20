package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import io.jmix.core.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Sole {@link Transactional} status surface for the RAG ingestion pipeline (D-14).
 * Every public method runs in a fresh transaction ({@link Propagation#REQUIRES_NEW}) so the
 * status row commits independently of the caller — in particular, a worker's embedding failure
 * that rolls back partial chunks does NOT erase the {@code FAILED} status row (RAG-03 atomicity).
 *
 * <p>Mirrors the Phase 4 {@code AuditWriter} shape exactly: constructor-injected
 * {@link DataManager}, single-responsibility status transitions, no business logic. Per
 * <b>Pitfall #3 (proxy self-invocation)</b>, this class never calls its own public methods
 * from inside another public method — doing so would bypass the proxy and silently dissolve
 * {@code REQUIRES_NEW}.
 *
 * <p><b>Missing document tolerance:</b> if a document was deleted while the worker was
 * in flight (D-06 / D-20 atomic-delete race), {@link DataManager#load(Class)}
 * returns empty; each method logs a warning and returns silently rather than throwing.
 *
 * <p><b>Error message truncation:</b> the entity column is {@code VARCHAR(1024)} (see
 * Liquibase changelog {@code 050-ai-knowledge-document.xml}); {@link #markFailed} truncates
 * oversize messages to 1024 characters. This differs from the plan's nominal 2000-char
 * figure (the column limit is the source of truth).
 *
 * <p><b>{@code chunkCount} accepted but not persisted:</b> the entity does not carry a
 * {@code chunkCount} column in the Phase 2 schema; {@link #markReady(UUID, int)} records
 * the count to the log for observability. Plan 05-04 may add the column if downstream
 * views need it — adding a field here without a Liquibase changeset would break boot.
 */
@Component
public class IngestionStatusWriter {

    private static final Logger log = LoggerFactory.getLogger(IngestionStatusWriter.class);

    /** Entity {@code ERROR_MESSAGE} column is {@code VARCHAR(1024)}. */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1024;

    private final DataManager dataManager;

    public IngestionStatusWriter(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    /**
     * D-15 reingest reset: flips status back to {@code PENDING}, clears {@code errorMessage}
     * and {@code ingestedAt}. Used by Plan 05-04 {@code KnowledgeDocumentService.reingest}
     * before dispatching the async worker. Commits independently.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPending(UUID id) {
        dataManager.load(AiKnowledgeDocument.class).id(id).optional().ifPresentOrElse(doc -> {
            doc.setStatus(AiKnowledgeDocumentStatus.PENDING);
            doc.setErrorMessage(null);
            doc.setIngestedAt(null);
            dataManager.save(doc);
        }, () -> log.warn("markPending: document {} not found", id));
    }

    /** Flips status to {@code PROCESSING} and clears any stale {@code errorMessage}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID id) {
        dataManager.load(AiKnowledgeDocument.class).id(id).optional().ifPresentOrElse(doc -> {
            doc.setStatus(AiKnowledgeDocumentStatus.PROCESSING);
            doc.setErrorMessage(null);
            dataManager.save(doc);
        }, () -> log.warn("markProcessing: document {} not found", id));
    }

    /**
     * Flips status to {@code READY} and clears any stale {@code errorMessage}. The
     * {@code chunkCount} parameter is accepted for contract-compat with Plan 05-04 callers
     * but is currently logged rather than persisted — the Phase 2 entity schema does not
     * carry a {@code chunkCount} column.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReady(UUID id, int chunkCount) {
        dataManager.load(AiKnowledgeDocument.class).id(id).optional().ifPresentOrElse(doc -> {
            doc.setStatus(AiKnowledgeDocumentStatus.READY);
            doc.setErrorMessage(null);
            doc.setIngestedAt(java.time.OffsetDateTime.now());
            dataManager.save(doc);
            log.debug("markReady: document {} ingested with {} chunks", id, chunkCount);
        }, () -> log.warn("markReady: document {} not found", id));
    }

    /**
     * Flips status to {@code FAILED} and records a user-facing error message, truncated to
     * the entity's {@code VARCHAR(1024)} column width. Commits independently even if the
     * outer caller throws.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String userFacingMessage) {
        dataManager.load(AiKnowledgeDocument.class).id(id).optional().ifPresentOrElse(doc -> {
            doc.setStatus(AiKnowledgeDocumentStatus.FAILED);
            doc.setErrorMessage(truncate(userFacingMessage));
            dataManager.save(doc);
        }, () -> log.warn("markFailed: document {} not found", id));
    }

    /** Flips status to {@code CANCELLED} and clears any stale {@code errorMessage}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCancelled(UUID id) {
        dataManager.load(AiKnowledgeDocument.class).id(id).optional().ifPresentOrElse(doc -> {
            doc.setStatus(AiKnowledgeDocumentStatus.CANCELLED);
            doc.setErrorMessage(null);
            dataManager.save(doc);
        }, () -> log.warn("markCancelled: document {} not found", id));
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > ERROR_MESSAGE_MAX_LENGTH
                ? message.substring(0, ERROR_MESSAGE_MAX_LENGTH)
                : message;
    }
}
