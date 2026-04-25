package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.push.DocumentStatusChangedEvent;
import io.jmix.core.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *
 * <p>Plan 07-02 adds post-commit emission of {@link DocumentStatusChangedEvent} via
 * {@link TransactionSynchronizationManager}, mirroring the Phase 4 {@code AuditWriter.writeToolCall}
 * pattern. Listeners (Plan 07-06 {@code KnowledgeBaseView}) subscribe via Spring's
 * {@code @EventListener} and fan out to attached Vaadin UIs with {@code UI.access()}
 * (RESEARCH Pattern 3). Events fire only after the REQUIRES_NEW transaction commits — on
 * rollback (e.g., a {@code save(doc)} constraint violation) nothing is published, so
 * listeners never observe a stale status.
 */
@Component
public class IngestionStatusWriter {

    private static final Logger log = LoggerFactory.getLogger(IngestionStatusWriter.class);

    /** Entity {@code ERROR_MESSAGE} column is {@code VARCHAR(1024)}. */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 1024;

    private final DataManager dataManager;
    private final ApplicationEventPublisher publisher;

    public IngestionStatusWriter(DataManager dataManager, ApplicationEventPublisher publisher) {
        this.dataManager = dataManager;
        this.publisher = publisher;
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
            registerAfterCommit(id, AiKnowledgeDocumentStatus.PENDING, null);
        }, () -> log.warn("markPending: document {} not found", id));
    }

    /** Flips status to {@code PROCESSING} and clears any stale {@code errorMessage}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID id) {
        dataManager.load(AiKnowledgeDocument.class).id(id).optional().ifPresentOrElse(doc -> {
            doc.setStatus(AiKnowledgeDocumentStatus.PROCESSING);
            doc.setErrorMessage(null);
            dataManager.save(doc);
            registerAfterCommit(id, AiKnowledgeDocumentStatus.PROCESSING, null);
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
            registerAfterCommit(id, AiKnowledgeDocumentStatus.READY, null);
        }, () -> log.warn("markReady: document {} not found", id));
    }

    /**
     * Flips status to {@code FAILED} and records a user-facing error message, truncated to
     * the entity's {@code VARCHAR(1024)} column width. Commits independently even if the
     * outer caller throws.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID id, String userFacingMessage) {
        final String truncatedMessage = truncate(userFacingMessage);
        dataManager.load(AiKnowledgeDocument.class).id(id).optional().ifPresentOrElse(doc -> {
            doc.setStatus(AiKnowledgeDocumentStatus.FAILED);
            doc.setErrorMessage(truncatedMessage);
            dataManager.save(doc);
            registerAfterCommit(id, AiKnowledgeDocumentStatus.FAILED, truncatedMessage);
        }, () -> log.warn("markFailed: document {} not found", id));
    }

    /** Flips status to {@code CANCELLED} and clears any stale {@code errorMessage}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCancelled(UUID id) {
        dataManager.load(AiKnowledgeDocument.class).id(id).optional().ifPresentOrElse(doc -> {
            doc.setStatus(AiKnowledgeDocumentStatus.CANCELLED);
            doc.setErrorMessage(null);
            dataManager.save(doc);
            registerAfterCommit(id, AiKnowledgeDocumentStatus.CANCELLED, null);
        }, () -> log.warn("markCancelled: document {} not found", id));
    }

    /**
     * Register a {@link TransactionSynchronization} that fires only after the REQUIRES_NEW
     * transaction commits. On rollback, Spring does not invoke {@code afterCommit}, so the
     * event is never published — listeners observe only durable state (T-07-07 mitigation).
     *
     * <p>Defensive: if no synchronization is active (unexpected — REQUIRES_NEW always has
     * one), the event is published inline and a warning is logged.</p>
     */
    private void registerAfterCommit(UUID documentId, AiKnowledgeDocumentStatus status, String errorMessage) {
        final UUID capturedId = documentId;
        final AiKnowledgeDocumentStatus capturedStatus = status;
        final String capturedErrorMessage = errorMessage;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        publisher.publishEvent(new DocumentStatusChangedEvent(
                                capturedId, capturedStatus, capturedErrorMessage));
                    } catch (RuntimeException ex) {
                        log.warn("DocumentStatusChangedEvent afterCommit publish failed id={} status={}",
                                capturedId, capturedStatus, ex);
                    }
                }
            });
        } else {
            log.warn("No active synchronization for doc={} status={} — firing inline",
                    capturedId, capturedStatus);
            publisher.publishEvent(new DocumentStatusChangedEvent(
                    capturedId, capturedStatus, capturedErrorMessage));
        }
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
