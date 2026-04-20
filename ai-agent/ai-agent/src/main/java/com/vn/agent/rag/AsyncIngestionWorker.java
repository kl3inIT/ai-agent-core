package com.vn.agent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import io.jmix.core.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Async ingestion pipeline: Tika → TokenTextSplitter → metadata enrichment → {@link VectorStore#add}
 * (D-11, D-12, D-14). Runs on {@code aiAgentIngestExecutor} off the request thread with MDC
 * propagated by {@link MdcPropagatingTaskDecorator}.
 *
 * <p><b>Status transitions</b> flow through {@link IngestionStatusWriter} (REQUIRES_NEW), never
 * this class directly — {@code @Async} and {@code @Transactional} MUST NOT combine on the same
 * method (RESEARCH Pitfall #7). {@link Async} methods have no caller transaction, so status rows
 * commit through a fresh writer transaction.
 *
 * <p><b>Metadata contract</b> (mirrors {@link RetrievalFilterBuilder} — Plan 05-02):
 * <ul>
 *   <li>{@link ChunkMetadata#SOURCE} — document {@code fileName}</li>
 *   <li>{@link ChunkMetadata#DOCUMENT_ID} — {@code document.id.toString()} (used by D-21 delete)</li>
 *   <li>{@link ChunkMetadata#EMBEDDING_MODEL} — current configured model (D-03 drift filter)</li>
 *   <li>{@link ChunkMetadata#ALLOWED_ROLES} — JSON-parsed list of role codes from the entity</li>
 *   <li>{@link ChunkMetadata#ROLE_FLAG_PREFIX}{@code <lowercase-code>} = {@code true} — Option A
 *       flattened role flags, lowercased via {@link Locale#ROOT} so the filter keys match
 *       regardless of role-code casing (threat T-05-03-08 mitigation).</li>
 * </ul>
 *
 * <p><b>RAG-03 atomicity</b>: on any failure, the catch block deletes all chunks already written
 * for this {@code documentId} via {@link VectorStore#delete(org.springframework.ai.vectorstore.filter.Filter.Expression)}
 * before calling {@link IngestionStatusWriter#markFailed} — no partial vectors survive a failed
 * ingest (threat T-05-03-06).
 *
 * <p><b>Cooperative cancellation</b>: the worker polls {@link CancellationRegistry#isCancelled}
 * at each batch boundary; if set, it marks {@code CANCELLED} and returns without writing the next
 * batch. Chunks already written are removed by the {@code KnowledgeDocumentService.delete} path
 * that flipped the flag (D-20).
 *
 * <p><b>Source resolution</b>: the Phase 2 entity does not carry a {@code sourceUri} field; this
 * worker interprets {@link AiKnowledgeDocument#getFileName()} as a source hint. If the file name
 * starts with {@code classpath:} or {@code file:}, it's used as-is via Spring's {@link ResourceLoader};
 * otherwise it is resolved as {@code classpath:ai-kb/<fileName>}. Unknown schemes become an
 * {@link IllegalArgumentException} that surfaces as a FAILED status with a user-readable message
 * (threat T-05-03-05). Plan 05-04's upload service will formalise this when file content storage
 * lands.
 */
@Component
public class AsyncIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncIngestionWorker.class);

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";
    /** Fallback resolution root for bare filenames. */
    private static final String DEFAULT_RESOURCE_ROOT = "classpath:ai-kb/";
    /** Safety bound on batch size — caps memory regardless of chunk sizing. */
    private static final int VECTOR_STORE_BATCH_SIZE = 32;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IngestionStatusWriter ingestionStatusWriter;
    private final CancellationRegistry cancellationRegistry;
    private final DataManager dataManager;
    private final VectorStore vectorStore;
    private final AiAgentRagProperties ragProperties;
    private final AiAgentEmbeddingProperties embeddingProperties;
    private final ResourceLoader resourceLoader;

    public AsyncIngestionWorker(IngestionStatusWriter ingestionStatusWriter,
                                CancellationRegistry cancellationRegistry,
                                DataManager dataManager,
                                VectorStore vectorStore,
                                AiAgentRagProperties ragProperties,
                                AiAgentEmbeddingProperties embeddingProperties,
                                ResourceLoader resourceLoader) {
        this.ingestionStatusWriter = ingestionStatusWriter;
        this.cancellationRegistry = cancellationRegistry;
        this.dataManager = dataManager;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.embeddingProperties = embeddingProperties;
        this.resourceLoader = resourceLoader;
    }

    @Async("aiAgentIngestExecutor")
    public void ingest(UUID documentId) {
        // WR-01: capture the generation at entry so later reingest() bumps mark THIS
        // worker as cancelled even if the legacy boolean flag is cleared between
        // scheduling and polling. Generation captured BEFORE markProcessing so a
        // racing cancel() called immediately after dispatch is still observed.
        final long generation = cancellationRegistry.currentGeneration(documentId);

        // markProcessing is OUTSIDE the try — writer runs in its own REQUIRES_NEW and its row
        // should commit even if the read/parse/embed block below throws immediately.
        ingestionStatusWriter.markProcessing(documentId);

        AiKnowledgeDocument document = dataManager.load(AiKnowledgeDocument.class)
                .id(documentId).optional().orElse(null);
        if (document == null) {
            // Racing with D-06 atomic delete — nothing to do.
            log.warn("ingest: document {} not found; ignoring", documentId);
            cancellationRegistry.clear(documentId);
            return;
        }

        if (cancellationRegistry.isCancelled(documentId, generation)) {
            ingestionStatusWriter.markCancelled(documentId);
            cancellationRegistry.clear(documentId);
            return;
        }

        try {
            Resource resource = resolveResource(document.getFileName());
            List<Document> raw = new TikaDocumentReader(resource).get();

            TokenTextSplitter splitter = buildSplitter();
            List<Document> chunks = splitter.apply(raw);

            List<String> allowedRoles = parseAllowedRoles(document.getAllowedRolesJson());
            String embeddingModel = embeddingProperties.resolvedModel();

            List<Document> enriched = new ArrayList<>(chunks.size());
            for (Document chunk : chunks) {
                enriched.add(enrich(chunk, document, allowedRoles, embeddingModel));
            }

            int written = 0;
            for (int i = 0; i < enriched.size(); i += VECTOR_STORE_BATCH_SIZE) {
                if (cancellationRegistry.isCancelled(documentId, generation)) {
                    ingestionStatusWriter.markCancelled(documentId);
                    cancellationRegistry.clear(documentId);
                    return;
                }
                List<Document> batch = enriched.subList(i, Math.min(i + VECTOR_STORE_BATCH_SIZE, enriched.size()));
                vectorStore.add(batch);
                written += batch.size();
            }

            ingestionStatusWriter.markReady(documentId, written);
            cancellationRegistry.clear(documentId);
        } catch (Throwable t) {
            // RAG-03 atomic failure: remove any chunks already written for this documentId
            // before flipping status to FAILED (threat T-05-03-06).
            try {
                FilterExpressionBuilder fb = new FilterExpressionBuilder();
                vectorStore.delete(fb.eq(ChunkMetadata.DOCUMENT_ID, documentId.toString()).build());
            } catch (Exception cleanup) {
                // Cleanup failure must NOT mask the real cause — log and continue to markFailed.
                log.error("Vector cleanup failed for {}", documentId, cleanup);
            }
            ingestionStatusWriter.markFailed(documentId,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
            cancellationRegistry.clear(documentId);
        }
    }

    private Resource resolveResource(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Document fileName is blank");
        }
        String location;
        if (fileName.startsWith(CLASSPATH_PREFIX) || fileName.startsWith(FILE_PREFIX)) {
            location = fileName;
        } else if (fileName.contains("://")) {
            // threat T-05-03-05: only classpath: and file: are acceptable schemes.
            throw new IllegalArgumentException("Unsupported source scheme in fileName: " + fileName);
        } else {
            location = DEFAULT_RESOURCE_ROOT + fileName;
        }
        return resourceLoader.getResource(location);
    }

    private TokenTextSplitter buildSplitter() {
        AiAgentRagProperties.Splitter cfg = ragProperties.splitter();
        TokenTextSplitter.Builder b = TokenTextSplitter.builder();
        if (cfg != null && cfg.chunkSize() != null) {
            b.withChunkSize(cfg.chunkSize());
        }
        if (cfg != null && cfg.minChunkSizeChars() != null) {
            b.withMinChunkSizeChars(cfg.minChunkSizeChars());
        }
        return b.build();
    }

    private Document enrich(Document chunk, AiKnowledgeDocument doc,
                            List<String> allowedRoles, String embeddingModel) {
        // Copy into a fresh mutable map — splitter-produced Documents may have unmodifiable metadata.
        Map<String, Object> merged = new HashMap<>();
        if (chunk.getMetadata() != null) {
            merged.putAll(chunk.getMetadata());
        }
        merged.put(ChunkMetadata.SOURCE, doc.getFileName());
        merged.put(ChunkMetadata.DOCUMENT_ID, doc.getId().toString());
        merged.put(ChunkMetadata.EMBEDDING_MODEL, embeddingModel);
        merged.put(ChunkMetadata.ALLOWED_ROLES, List.copyOf(allowedRoles));
        for (String role : allowedRoles) {
            if (role == null || role.isBlank()) continue;
            merged.put(ChunkMetadata.ROLE_FLAG_PREFIX + role.toLowerCase(Locale.ROOT), true);
        }
        return chunk.mutate().metadata(merged).build();
    }

    private static List<String> parseAllowedRoles(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> roles = JSON.readValue(json, new TypeReference<>() {});
            return roles == null ? List.of() : roles;
        } catch (Exception e) {
            log.warn("Failed to parse allowedRolesJson; treating as empty: {}", json, e);
            return List.of();
        }
    }
}
