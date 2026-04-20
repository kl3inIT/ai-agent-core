package com.vn.agent.rag;

import com.vn.agent.entity.AiKnowledgeDocument;
import io.jmix.core.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * Manages the lifecycle of {@link AiKnowledgeDocument} rows after initial upload:
 * atomic delete (RAG-07) and reingest (RAG-08). Upload itself lives in
 * {@link KnowledgeDocumentUploadService} because it has a different collaborator
 * set (role repository + embedding properties).
 *
 * <p><b>Atomic delete (RAG-07 / D-06 / D-20):</b> {@link #delete(UUID)} runs under
 * {@code @Transactional(REQUIRED)} and executes, in order:
 * <ol>
 *   <li>{@link CancellationRegistry#cancel} — signals any in-flight worker to stop
 *       at the next batch boundary. Signalled BEFORE any destructive operation so
 *       cancellation latency is minimised.</li>
 *   <li>{@link VectorStore#delete} filtered by {@code documentId == X} — removes
 *       chunks the worker may have already written.</li>
 *   <li>{@link DataManager#remove} — drops the entity.</li>
 * </ol>
 * If step 2 throws, step 3 is never reached and the {@code @Transactional} rollback
 * keeps the entity intact so an admin can retry. If step 3 throws, the JPA rollback
 * cannot undo the pgvector delete (non-transactional boundary); the dangerous
 * failure mode — orphan chunks visible to retrieval — cannot occur, but the entity
 * may linger and require a second delete to reclaim.
 *
 * <p><b>Reingest (RAG-08 / D-15):</b> {@link #reingest(UUID)} cancels the current
 * worker (if any), clears the cancellation flag (re-ingests are a fresh generation),
 * purges existing chunks via {@code VectorStore.delete}, flips status to
 * {@code PENDING} via {@link IngestionStatusWriter#markPending} (REQUIRES_NEW so the
 * reset survives any subsequent rollback), then schedules the worker afterCommit.
 * Reingest intentionally does NOT re-validate roles — the document's stored
 * {@code allowedRolesJson} is already validated at upload time.
 */
@Service
@Transactional
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);

    private final DataManager dataManager;
    private final VectorStore vectorStore;
    private final CancellationRegistry cancellationRegistry;
    private final AsyncIngestionWorker asyncIngestionWorker;
    private final IngestionStatusWriter ingestionStatusWriter;

    public KnowledgeDocumentService(DataManager dataManager,
                                    VectorStore vectorStore,
                                    CancellationRegistry cancellationRegistry,
                                    AsyncIngestionWorker asyncIngestionWorker,
                                    IngestionStatusWriter ingestionStatusWriter) {
        this.dataManager = dataManager;
        this.vectorStore = vectorStore;
        this.cancellationRegistry = cancellationRegistry;
        this.asyncIngestionWorker = asyncIngestionWorker;
        this.ingestionStatusWriter = ingestionStatusWriter;
    }

    /**
     * Atomically delete a knowledge document and all of its pgvector chunks.
     *
     * @throws DocumentNotFoundException if no document exists with the given id
     */
    public void delete(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        AiKnowledgeDocument document = loadOrThrow(documentId);

        // 1) Signal in-flight worker BEFORE any destructive operation (D-20).
        cancellationRegistry.cancel(documentId);

        // 2) Purge pgvector chunks for this document id (D-21). If this throws the
        //    surrounding @Transactional rollback keeps the entity intact.
        vectorStore.delete(documentIdFilter(documentId));

        // 3) Remove the entity. If JPA remove throws after vectors are gone the
        //    rollback cannot undo pgvector (non-transactional boundary — see class
        //    javadoc). Orphaned-vectors-visible-to-retrieval is still impossible;
        //    a lingering entity row is recoverable by repeating delete().
        dataManager.remove(document);
    }

    /**
     * Re-run ingestion for an existing document: purge existing chunks, reset status,
     * dispatch the async worker.
     *
     * @throws DocumentNotFoundException if no document exists with the given id
     */
    public void reingest(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        loadOrThrow(documentId); // existence check — result unused

        // Cancel any in-flight worker from a prior generation, then clear the flag so
        // the newly-dispatched worker is not aborted on its entry guard.
        cancellationRegistry.cancel(documentId);
        cancellationRegistry.clear(documentId);

        // Purge old chunks (D-15). If this throws, the status writer below is not
        // invoked and the REQUIRED transaction rolls back.
        vectorStore.delete(documentIdFilter(documentId));

        // REQUIRES_NEW: resets status=PENDING / errorMessage=null / ingestedAt=null
        // in a fresh transaction so the reset survives even if a later step in the
        // outer transaction rolls back (D-15).
        ingestionStatusWriter.markPending(documentId);

        // Dispatch async worker AFTER commit so it observes the reset row.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncIngestionWorker.ingest(documentId);
                }
            });
        } else {
            log.warn("reingest: no active transaction synchronization; dispatching ingest inline for {}",
                    documentId);
            asyncIngestionWorker.ingest(documentId);
        }
    }

    private AiKnowledgeDocument loadOrThrow(UUID documentId) {
        return dataManager.load(AiKnowledgeDocument.class)
                .id(documentId)
                .optional()
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    private static Filter.Expression documentIdFilter(UUID id) {
        return new FilterExpressionBuilder()
                .eq(ChunkMetadata.DOCUMENT_ID, id.toString())
                .build();
    }
}
