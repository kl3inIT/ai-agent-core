package com.vn.agent.push;

import com.vn.agent.entity.AiKnowledgeDocumentStatus;

import java.util.UUID;

/**
 * Fired by {@code IngestionStatusWriter} via
 * {@code TransactionSynchronization.afterCommit} — Plan 07-02 wires the
 * publisher. Subscribed by KnowledgeBaseView per-UI listener (Plan 07-06)
 * to drive Vaadin Push refreshes of grid rows whose status changed.
 *
 * <p>{@code errorMessage} is non-null only when
 * {@code status == AiKnowledgeDocumentStatus.FAILED}; every other status
 * carries {@code null} here.
 *
 * @param documentId   the knowledge document whose status transitioned
 * @param status       the committed terminal or in-flight status
 * @param errorMessage failure detail when status is FAILED, otherwise {@code null}
 */
public record DocumentStatusChangedEvent(
        UUID documentId,
        AiKnowledgeDocumentStatus status,
        String errorMessage) {
}
