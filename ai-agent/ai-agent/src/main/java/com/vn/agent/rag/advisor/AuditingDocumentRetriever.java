package com.vn.agent.rag.advisor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.rag.ChunkMetadata;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Decorator around a {@link DocumentRetriever} delegate that writes one RETRIEVAL
 * {@code AiAuditEvent} row per {@link #retrieve(Query)} invocation (D-09). Parent id is read from
 * {@link RunContext#getRootAuditId()} (set by {@code AuditAdvisor} before the advisor chain runs)
 * so every retrieval row chains under the current chat turn's root. Not a Spring
 * {@code @Component} â€” instantiated inline by {@link RetrievalAugmentationAdvisorFactory}.
 *
 * <p><b>SECURITY (Phase 5 Pitfall #3 preserved):</b> this wrapper applies only the current
 * request's {@link VectorStoreDocumentRetriever#FILTER_EXPRESSION} from {@link Query#context()}.
 * It does NOT carry a static retriever filter; mixing a static filter with the per-request param
 * would silently replace (not AND) the per-request scope, a role-scoping bypass.</p>
 *
 * <p>Audit-write failures NEVER rethrow into the retrieval path (T-07.2-11 Denial mitigation):
 * the retrieve() result is always returned to the advisor even if the audit row cannot be
 * persisted, and the audit failure is logged at WARN level.</p>
 */
public class AuditingDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(AuditingDocumentRetriever.class);

    public static final String TOP_K_CONTEXT_KEY = "ai_agent_retrieval_top_k";
    public static final String SIMILARITY_THRESHOLD_CONTEXT_KEY = "ai_agent_retrieval_similarity_threshold";
    static final int MAX_AUDITED_HITS = 50;
    static final int TEXT_PREVIEW_MAX_CHARS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final DocumentRetriever delegate;
    private final VectorStore vectorStore;
    private final int defaultTopK;
    private final double defaultSimilarityThreshold;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;

    public AuditingDocumentRetriever(DocumentRetriever delegate,
                                     AuditWriter auditWriter,
                                     CurrentAuthentication currentAuthentication) {
        this.delegate = delegate;
        this.vectorStore = null;
        this.defaultTopK = 5;
        this.defaultSimilarityThreshold = 0.5;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
    }

    public AuditingDocumentRetriever(VectorStore vectorStore,
                                     int defaultTopK,
                                     double defaultSimilarityThreshold,
                                     AuditWriter auditWriter,
                                     CurrentAuthentication currentAuthentication) {
        this.delegate = null;
        this.vectorStore = vectorStore;
        this.defaultTopK = defaultTopK;
        this.defaultSimilarityThreshold = defaultSimilarityThreshold;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
    }

    @Override
    public @NonNull List<Document> retrieve(@NonNull Query query) {
        UUID parentId = RunContext.getRootAuditId();
        UUID runId = RunContext.get();
        if (runId == null) {
            Object contextRunId = query.context().get("audit.runId");
            if (contextRunId instanceof UUID uuid) {
                runId = uuid;
            }
        }
        String userUsername = safeUsername();
        long startNanos = System.nanoTime();
        List<Document> docs = null;
        String outcome = "SUCCESS";
        String errorClass = null;
        try {
            docs = retrieveDocuments(query);
            return docs;
        } catch (Throwable t) {
            outcome = "ERROR";
            errorClass = t.getClass().getSimpleName();
            throw t;
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            try {
                Integer topK = resolveTopK(query);
                String filtersJson = resolveFiltersJson(query);
                int hitCount = docs == null ? 0 : docs.size();
                Double topScore = (docs == null || docs.isEmpty()) ? null : safeScore(docs.getFirst());
                String queryText = safeQueryText(query);
                String retrievalHitsJson = buildRetrievalHitsJson(docs);
                auditWriter.writeRetrieval(parentId, runId, userUsername, RunContext.getConversationId(),
                        queryText, topK, hitCount, topScore, filtersJson, retrievalHitsJson,
                        latencyMs, outcome, errorClass);
            } catch (Throwable t2) {
                log.warn("Retrieval audit write failed for parentId={} runId={}", parentId, runId, t2);
            }
        }
    }

    private String safeUsername() {
        try {
            return currentAuthentication != null && currentAuthentication.getUser() != null
                    ? currentAuthentication.getUser().getUsername()
                    : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Double safeScore(Document doc) {
        try {
            return doc.getScore();
        } catch (Throwable t) {
            return null;
        }
    }

    private String safeQueryText(Query query) {
        try {
            return query == null ? null : query.text();
        } catch (Throwable t) {
            return null;
        }
    }

    private String buildRetrievalHitsJson(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "{\"hits\":[],\"truncated\":false}";
        }
        List<Map<String, Object>> hits = new java.util.ArrayList<>();
        int limit = Math.min(docs.size(), MAX_AUDITED_HITS);
        for (int i = 0; i < limit; i++) {
            hits.add(toAuditHit(i + 1, docs.get(i)));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hits", hits);
        payload.put("truncated", docs.size() > MAX_AUDITED_HITS);
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialize retrieval hit audit payload", e);
            return null;
        }
    }

    private Map<String, Object> toAuditHit(int rank, Document doc) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("rank", rank);
        putIfPresent(hit, "id", safeDocumentId(doc));
        putIfPresent(hit, "score", safeScore(doc));
        putIfPresent(hit, "documentId", metadataValue(doc, ChunkMetadata.DOCUMENT_ID));
        putIfPresent(hit, "source", metadataValue(doc, ChunkMetadata.SOURCE));
        putIfPresent(hit, "embeddingModel", metadataValue(doc, ChunkMetadata.EMBEDDING_MODEL));
        putIfPresent(hit, "allowedRoles", metadataValue(doc, ChunkMetadata.ALLOWED_ROLES));
        putIfPresent(hit, "textPreview", previewText(doc));
        return hit;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String safeDocumentId(Document doc) {
        try {
            return doc == null ? null : doc.getId();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object metadataValue(Document doc, String key) {
        try {
            Map<String, Object> metadata = doc == null ? null : doc.getMetadata();
            Object value = metadata == null ? null : metadata.get(key);
            if (value instanceof String s && s.isBlank()) {
                return null;
            }
            return value;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String previewText(Document doc) {
        try {
            String text = doc == null ? null : doc.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            String normalized = text.replaceAll("\\s+", " ").trim();
            return normalized.length() <= TEXT_PREVIEW_MAX_CHARS
                    ? normalized
                    : normalized.substring(0, TEXT_PREVIEW_MAX_CHARS) + "...";
        } catch (Throwable t) {
            return null;
        }
    }

    private List<Document> retrieveDocuments(Query query) {
        if (vectorStore == null) {
            return delegate.retrieve(query);
        }
        Integer topK = resolveTopK(query);
        Double similarityThreshold = resolveSimilarityThreshold(query);
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query.text())
                .topK(topK == null ? defaultTopK : topK)
                .similarityThreshold(similarityThreshold == null ? defaultSimilarityThreshold : similarityThreshold);
        Object filter = query.context().get(VectorStoreDocumentRetriever.FILTER_EXPRESSION);
        if (filter instanceof Filter.Expression expression) {
            builder.filterExpression(expression);
        } else if (filter != null && !filter.toString().isBlank()) {
            builder.filterExpression(filter.toString());
        }
        return vectorStore.similaritySearch(builder.build());
    }

    private Integer resolveTopK(Query query) {
        Integer contextTopK = positiveInteger(query.context().get(TOP_K_CONTEXT_KEY));
        if (contextTopK != null) {
            return contextTopK;
        }
        Integer runContextTopK = RunContext.getRetrievalTopK();
        return runContextTopK != null && runContextTopK > 0 ? runContextTopK : defaultTopK;
    }

    private Double resolveSimilarityThreshold(Query query) {
        Double contextThreshold = boundedDouble(query.context().get(SIMILARITY_THRESHOLD_CONTEXT_KEY));
        if (contextThreshold != null) {
            return contextThreshold;
        }
        Double runContextThreshold = RunContext.getRetrievalSimilarityThreshold();
        return runContextThreshold != null && runContextThreshold >= 0.0 && runContextThreshold <= 1.0
                ? runContextThreshold
                : defaultSimilarityThreshold;
    }

    private String resolveFiltersJson(Query query) {
        Object filter = query.context().get(VectorStoreDocumentRetriever.FILTER_EXPRESSION);
        if (filter != null && !filter.toString().isBlank()) {
            return filter.toString();
        }
        return RunContext.getRetrievalFiltersJson();
    }

    private static Integer positiveInteger(Object value) {
        if (value instanceof Number number) {
            int candidate = number.intValue();
            return candidate > 0 ? candidate : null;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                int candidate = Integer.parseInt(s.trim());
                return candidate > 0 ? candidate : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double boundedDouble(Object value) {
        if (value instanceof Number number) {
            double candidate = number.doubleValue();
            return candidate >= 0.0 && candidate <= 1.0 ? candidate : null;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                double candidate = Double.parseDouble(s.trim());
                return candidate >= 0.0 && candidate <= 1.0 ? candidate : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
