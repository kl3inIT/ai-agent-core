package com.vn.agent.rag.advisor;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.orchestration.RunContext;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.UUID;

/**
 * Decorator around a {@link DocumentRetriever} delegate that writes one RETRIEVAL
 * {@code AiAuditEvent} row per {@link #retrieve(Query)} invocation (D-09). Parent id is read from
 * {@link RunContext#getRootAuditId()} (set by {@code AuditAdvisor} before the advisor chain runs)
 * so every retrieval row chains under the current chat turn's root. Not a Spring
 * {@code @Component} — instantiated inline by {@link RetrievalAugmentationAdvisorFactory}.
 *
 * <p><b>SECURITY (Phase 5 Pitfall #3 preserved):</b> this wrapper only READS the current request's
 * filter state from {@link RunContext} for audit purposes. It does NOT apply any static filter to
 * the delegate. Filter enforcement stays in the advisor-param layer
 * ({@code VectorStoreDocumentRetriever#FILTER_EXPRESSION}), identical to pre-7.2 behaviour. Mixing
 * a static retriever filter with the per-request param would silently replace (not AND) the
 * per-request scope, a role-scoping bypass.</p>
 *
 * <p>Audit-write failures NEVER rethrow into the retrieval path (T-07.2-11 Denial mitigation):
 * the retrieve() result is always returned to the advisor even if the audit row cannot be
 * persisted, and the audit failure is logged at WARN level.</p>
 */
public class AuditingDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(AuditingDocumentRetriever.class);

    private final DocumentRetriever delegate;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;

    public AuditingDocumentRetriever(DocumentRetriever delegate,
                                     AuditWriter auditWriter,
                                     CurrentAuthentication currentAuthentication) {
        this.delegate = delegate;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
    }

    @Override
    public List<Document> retrieve(Query query) {
        UUID parentId = RunContext.getRootAuditId();
        UUID runId = RunContext.get();
        String userUsername = safeUsername();
        long startNanos = System.nanoTime();
        List<Document> docs = null;
        String outcome = "SUCCESS";
        String errorClass = null;
        try {
            docs = delegate.retrieve(query);
            return docs;
        } catch (Throwable t) {
            outcome = "ERROR";
            errorClass = t.getClass().getSimpleName();
            throw t;
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            try {
                Integer topK = RunContext.getRetrievalTopK();
                String filtersJson = RunContext.getRetrievalFiltersJson();
                int hitCount = docs == null ? 0 : docs.size();
                Double topScore = (docs == null || docs.isEmpty()) ? null : safeScore(docs.get(0));
                String queryText = safeQueryText(query);
                auditWriter.writeRetrieval(parentId, runId, userUsername, null,
                        queryText, topK, hitCount, topScore, filtersJson, latencyMs, outcome, errorClass);
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
}
