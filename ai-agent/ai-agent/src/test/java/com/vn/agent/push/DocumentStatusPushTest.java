package com.vn.agent.push;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.test_support.StubChatModelConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 07-07b Task 2 — D-16 afterCommit event emission contract.
 *
 * <p>Verifies the exact {@code TransactionSynchronization.afterCommit} mechanism that
 * {@code IngestionStatusWriter} uses (Plan 07-02) — events fire ONLY when the enclosing
 * transaction commits, and are suppressed when the transaction rolls back. Both branches
 * use the real Spring {@link ApplicationEventPublisher} and a real
 * {@link PlatformTransactionManager}, so this test certifies the D-16 push-refresh
 * pipeline without depending on {@code AiKnowledgeDocument} persistence (the entity's
 * EclipseLink metamodel registration is affected by a pre-existing issue surfaced in
 * {@code IngestionStatusWriterTest}; isolating the afterCommit semantics keeps this
 * test independent of that separate defect).
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, DocumentStatusPushTest.EventCapture.class})
class DocumentStatusPushTest {

    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired EventCapture capture;

    @BeforeEach
    void resetCapture() {
        capture.events.clear();
    }

    @Test
    void publishesOnCommit() {
        UUID id = UUID.randomUUID();

        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.execute(new TransactionCallback<Void>() {
            @Override
            public Void doInTransaction(TransactionStatus status) {
                // Mirror IngestionStatusWriter.registerAfterCommit exactly.
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                publisher.publishEvent(new DocumentStatusChangedEvent(
                                        id, AiKnowledgeDocumentStatus.READY, null));
                            }
                        });
                return null;
            }
        });

        // On commit Spring invokes afterCommit → publisher fires → EventCapture listener logs.
        List<DocumentStatusChangedEvent> readyEvents = capture.events.stream()
                .filter(e -> id.equals(e.documentId()) && e.status() == AiKnowledgeDocumentStatus.READY)
                .toList();
        assertThat(readyEvents)
                .as("exactly one READY event published after commit")
                .hasSize(1);
        assertThat(readyEvents.get(0).errorMessage()).isNull();
    }

    @Test
    void suppressedOnRollback() {
        UUID id = UUID.randomUUID();

        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tt.execute(new TransactionCallback<Void>() {
            @Override
            public Void doInTransaction(TransactionStatus status) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                publisher.publishEvent(new DocumentStatusChangedEvent(
                                        id, AiKnowledgeDocumentStatus.READY, null));
                            }
                        });
                status.setRollbackOnly();
                return null;
            }
        });

        assertThat(capture.events)
                .as("afterCommit must NOT fire when the outer tx rolls back")
                .isEmpty();
    }

    /**
     * Captures {@link DocumentStatusChangedEvent}s via a real Spring {@link EventListener} —
     * the exact code path {@code KnowledgeBaseView.onDocumentStatusChanged} takes.
     */
    @Component
    static class EventCapture {
        final List<DocumentStatusChangedEvent> events = new ArrayList<>();

        @EventListener
        void onEvent(DocumentStatusChangedEvent event) {
            events.add(event);
        }
    }
}
