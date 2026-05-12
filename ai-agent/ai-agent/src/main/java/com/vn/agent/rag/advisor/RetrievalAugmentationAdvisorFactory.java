package com.vn.agent.rag.advisor;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.rag.config.AiAgentRagProperties;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Produces the {@link RetrievalAugmentationAdvisor} bean slotted between
 * {@code MessageChatMemoryAdvisor (+200)} and {@code ToolCallAdvisor (+300)} per Phase 4 D-02.
 *
 * <p><b>RESEARCH Pitfall #3 — critical security invariant:</b> the {@link VectorStoreDocumentRetriever}
 * is built WITHOUT a static {@code .filterExpression(...)}. The per-request
 * {@link VectorStoreDocumentRetriever#FILTER_EXPRESSION} advisor param set by
 * {@code DefaultChatServiceImpl} (from {@code RetrievalFilterBuilder}) is the sole security-critical
 * filter. A static retriever filter is REPLACED (not AND-ed) by the runtime param, so any static
 * filter here would silently vanish when the param is supplied — a role-scoping bypass.</p>
 *
 * <p><b>AUDIT (7.2 D-09):</b> the retriever passed to
 * {@link RetrievalAugmentationAdvisor.Builder#documentRetriever(DocumentRetriever)} is an
 * {@link AuditingDocumentRetriever} wrapper that delegates to the
 * {@link VectorStoreDocumentRetriever}. The wrapper sits on the OUTSIDE of the delegate; it only
 * reads filter state from {@code RunContext} for audit-row population and does NOT apply any
 * filter statically. The security invariant above is therefore unchanged.</p>
 */
@Configuration
public class RetrievalAugmentationAdvisorFactory {

    /** Advisor slot between memory (+200) and tool (+300); ordered via {@code Builder.order(int)}. */
    public static final int ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 250;

    @Bean
    @ConditionalOnMissingBean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(VectorStore vectorStore,
                                                                    AiAgentRagProperties props,
                                                                    AuditWriter auditWriter,
                                                                    CurrentAuthentication currentAuthentication,
                                                                    StreamingSinkHolder streamingSinkHolder) {
        DocumentRetriever retriever = new AuditingDocumentRetriever(vectorStore,
                props.resolvedTopK(), props.resolvedSimilarityThreshold(), auditWriter, currentAuthentication,
                streamingSinkHolder);

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                // Spring AI defaults to "empty-context => refuse". Keep RAG optional for
                // general chat/tooling flows by allowing the model to answer when no KB docs match.
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .build())
                .order(ADVISOR_ORDER)
                .build();
    }
}
