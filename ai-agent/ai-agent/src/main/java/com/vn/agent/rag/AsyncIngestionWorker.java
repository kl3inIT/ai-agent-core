package com.vn.agent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 *   <li>{@code role_<normalized-code>} = {@code true} — Option A flattened role flags,
 *       normalized via {@link ChunkMetadata#normalizeRoleCode(String)} so filter keys are valid
 *       JSONPath member names (threat T-05-03-08 mitigation).</li>
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
    private final UnconstrainedDataManager dataManager;
    private final VectorStore vectorStore;
    private final AiAgentRagProperties ragProperties;
    private final AiAgentEmbeddingProperties embeddingProperties;
    private final ResourceLoader resourceLoader;
    private final SystemAuthenticator systemAuthenticator;

    public AsyncIngestionWorker(IngestionStatusWriter ingestionStatusWriter,
                                CancellationRegistry cancellationRegistry,
                                UnconstrainedDataManager dataManager,
                                VectorStore vectorStore,
                                AiAgentRagProperties ragProperties,
                                AiAgentEmbeddingProperties embeddingProperties,
                                ResourceLoader resourceLoader,
                                SystemAuthenticator systemAuthenticator) {
        this.ingestionStatusWriter = ingestionStatusWriter;
        this.cancellationRegistry = cancellationRegistry;
        this.dataManager = dataManager;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.embeddingProperties = embeddingProperties;
        this.resourceLoader = resourceLoader;
        this.systemAuthenticator = systemAuthenticator;
    }

    @Async("aiAgentIngestExecutor")
    public void ingest(UUID documentId) {
        // Async thread has no SecurityContext. Run under Jmix system auth; the document was
        // already role-validated by KnowledgeDocumentUploadService at upload time, and the
        // internal entity read uses UnconstrainedDataManager because ingestion is system
        // bookkeeping. Retrieval-time visibility is still enforced via per-chunk allowedRoles.
        systemAuthenticator.runWithSystem(() -> ingestInternal(documentId));
    }

    private void ingestInternal(UUID documentId) {
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
            List<Document> raw = readResource(resource, document);

            // WR-02: enforce jmix.ai-agent.rag.ingest.max-document-chars BEFORE splitting /
            // embedding. Tika has already materialised the text so we can size it accurately;
            // exceeding the cap fails the document with a clear message and skips vectorStore.add.
            enforceMaxDocumentChars(raw);

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

    /**
     * WR-02: enforce the documented {@code jmix.ai-agent.rag.ingest.max-document-chars}
     * cap before splitting / embedding. {@code null} means no cap configured; any other
     * value aborts ingest with {@link IllegalArgumentException} so the catch block flips
     * status to FAILED with a clear message.
     */
    private void enforceMaxDocumentChars(List<Document> raw) {
        AiAgentRagProperties.Ingest cfg = ragProperties == null ? null : ragProperties.ingest();
        if (cfg == null || cfg.maxDocumentChars() == null) {
            return;
        }
        int cap = cfg.maxDocumentChars();
        if (cap <= 0) {
            return;
        }
        long total = 0L;
        for (Document d : raw) {
            String text = d.getText();
            if (text != null) {
                total += text.length();
                if (total > cap) {
                    throw new IllegalArgumentException(
                            "Document exceeds jmix.ai-agent.rag.ingest.max-document-chars=" + cap
                                    + " (observed > " + total + " characters)");
                }
            }
        }
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

    private List<Document> readResource(Resource resource, AiKnowledgeDocument document) {
        if (isMarkdown(document)) {
            try {
                String text = new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(ChunkMetadata.SOURCE, document.getFileName());
                return List.of(new Document(text, metadata));
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read markdown resource as UTF-8: "
                        + document.getFileName(), e);
            }
        }
        return new TikaDocumentReader(resource).get();
    }

    private boolean isMarkdown(AiKnowledgeDocument document) {
        String fileName = document.getFileName();
        String mimeType = document.getMimeType();
        return (mimeType != null && mimeType.toLowerCase().contains("markdown"))
                || (fileName != null && fileName.toLowerCase().endsWith(".md"));
    }

    private Document enrich(Document chunk, AiKnowledgeDocument doc,
                            List<String> allowedRoles, String embeddingModel) {
        // Copy into a fresh mutable map — splitter-produced Documents may have unmodifiable metadata.
        Map<String, Object> merged = new HashMap<>();
        merged.putAll(chunk.getMetadata());
        merged.put(ChunkMetadata.SOURCE, doc.getFileName());
        merged.put(ChunkMetadata.DOCUMENT_ID, doc.getId().toString());
        merged.put(ChunkMetadata.EMBEDDING_MODEL, embeddingModel);
        merged.put(ChunkMetadata.ALLOWED_ROLES, List.copyOf(allowedRoles));
        for (String role : allowedRoles) {
            if (role == null || role.isBlank()) continue;
            merged.put(ChunkMetadata.roleFlagKey(role), true);
        }
        // EXP-05 / D-07: Mirror sourceEntityName to chunk metadata so RetrievalFilterBuilder
        // can apply the entity denylist filter (source_entity NOT IN <denied>). Null-guarded:
        // chunks from documents without a sourceEntityName do NOT receive the SOURCE_ENTITY
        // key. This preserves the D-06 legacy-doc contract — chunks without the key remain
        // visible regardless of denylist contents until the document is reingested with
        // sourceEntityName populated.
        if (doc.getSourceEntityName() != null) {
            merged.put(ChunkMetadata.SOURCE_ENTITY, doc.getSourceEntityName());
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
