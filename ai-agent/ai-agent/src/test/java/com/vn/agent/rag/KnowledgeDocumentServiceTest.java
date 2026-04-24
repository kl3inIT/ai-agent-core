package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import io.jmix.core.DataManager;
import io.jmix.core.FluentLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-only unit test for {@link KnowledgeDocumentService}. {@code @Transactional}
 * proxy semantics are not exercised — the service is invoked directly. Integration-level
 * transactional behaviour lands in Plan 05-05.
 */
class KnowledgeDocumentServiceTest {

    private DataManager dataManager;
    private VectorStore vectorStore;
    private CancellationRegistry cancellationRegistry;
    private AsyncIngestionWorker asyncIngestionWorker;
    private IngestionStatusWriter ingestionStatusWriter;

    private KnowledgeDocumentService service;

    @BeforeEach
    void setUp() {
        dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        vectorStore = mock(VectorStore.class);
        cancellationRegistry = mock(CancellationRegistry.class);
        asyncIngestionWorker = mock(AsyncIngestionWorker.class);
        ingestionStatusWriter = mock(IngestionStatusWriter.class);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service = new KnowledgeDocumentService(
                dataManager, vectorStore, cancellationRegistry,
                asyncIngestionWorker, ingestionStatusWriter);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    // --- Helpers -------------------------------------------------------------

    private static AiKnowledgeDocument doc(UUID id) {
        AiKnowledgeDocument d = new AiKnowledgeDocument();
        try {
            Field f = AiKnowledgeDocument.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(d, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return d;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubLoad(UUID id, AiKnowledgeDocument value) {
        FluentLoader<AiKnowledgeDocument> loader = mock(FluentLoader.class, RETURNS_DEEP_STUBS);
        when(dataManager.load(AiKnowledgeDocument.class)).thenReturn(loader);
        when(loader.id(id).optional()).thenReturn(Optional.ofNullable(value));
    }

    // --- Delete tests --------------------------------------------------------

    @Test
    void delete_happy_path_cancels_then_purges_then_removes() {
        UUID id = UUID.randomUUID();
        AiKnowledgeDocument d = doc(id);
        stubLoad(id, d);

        service.delete(id);

        verify(cancellationRegistry).cancel(id);
        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(dataManager).remove(d);
    }

    @Test
    void delete_missing_document_throws_and_touches_no_collaborators() {
        UUID id = UUID.randomUUID();
        stubLoad(id, null);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(DocumentNotFoundException.class);

        verify(cancellationRegistry, never()).cancel(any());
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(dataManager, never()).remove(any(AiKnowledgeDocument.class));
    }

    @Test
    void delete_propagates_vectorStore_failure_and_skips_entity_remove() {
        UUID id = UUID.randomUUID();
        AiKnowledgeDocument d = doc(id);
        stubLoad(id, d);
        doThrow(new RuntimeException("pgvector down"))
                .when(vectorStore).delete(any(Filter.Expression.class));

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("pgvector down");

        verify(cancellationRegistry).cancel(id);
        verify(dataManager, never()).remove(any(AiKnowledgeDocument.class));
    }

    @Test
    void delete_cancels_before_vectorStore_delete_before_entity_remove() {
        UUID id = UUID.randomUUID();
        AiKnowledgeDocument d = doc(id);
        stubLoad(id, d);

        service.delete(id);

        InOrder order = inOrder(cancellationRegistry, vectorStore, dataManager);
        order.verify(cancellationRegistry).cancel(id);
        order.verify(vectorStore).delete(any(Filter.Expression.class));
        order.verify(dataManager).remove(d);
    }

    // --- Reingest tests ------------------------------------------------------

    @Test
    void reingest_happy_path_cancels_clears_purges_marks_pending_and_schedules_afterCommit() {
        UUID id = UUID.randomUUID();
        stubLoad(id, doc(id));

        service.reingest(id);

        // WR-01: reingest uses bumpGeneration (not cancel) so the new worker's captured
        // generation is not races-cancelled by a prior generation's lingering flag.
        InOrder order = inOrder(cancellationRegistry, vectorStore, ingestionStatusWriter);
        order.verify(cancellationRegistry).bumpGeneration(id);
        order.verify(cancellationRegistry).clear(id);
        order.verify(vectorStore).delete(any(Filter.Expression.class));
        order.verify(ingestionStatusWriter).markPending(id);

        // worker not yet invoked — only after commit.
        verify(asyncIngestionWorker, never()).ingest(any());

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        syncs.get(0).afterCommit();
        verify(asyncIngestionWorker, times(1)).ingest(id);
    }

    @Test
    void reingest_missing_document_throws() {
        UUID id = UUID.randomUUID();
        stubLoad(id, null);

        assertThatThrownBy(() -> service.reingest(id))
                .isInstanceOf(DocumentNotFoundException.class);

        verify(cancellationRegistry, never()).cancel(any());
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(ingestionStatusWriter, never()).markPending(any());
        verify(asyncIngestionWorker, never()).ingest(any());
    }

    @Test
    void reingest_cancels_and_clears_even_when_vectorStore_delete_throws() {
        UUID id = UUID.randomUUID();
        stubLoad(id, doc(id));
        doThrow(new RuntimeException("boom"))
                .when(vectorStore).delete(any(Filter.Expression.class));

        assertThatThrownBy(() -> service.reingest(id))
                .isInstanceOf(RuntimeException.class);

        // Bump + clear happened BEFORE the throwing delete; they must not leak.
        verify(cancellationRegistry).bumpGeneration(id);
        verify(cancellationRegistry).clear(id);
        verify(ingestionStatusWriter, never()).markPending(any());
        verify(asyncIngestionWorker, never()).ingest(any());
    }
}
