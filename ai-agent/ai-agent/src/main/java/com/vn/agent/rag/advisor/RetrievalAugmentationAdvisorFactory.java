package com.vn.agent.rag.advisor;

import com.vn.agent.rag.config.AiAgentRagProperties;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
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
 */
@Configuration
public class RetrievalAugmentationAdvisorFactory {

    /** Advisor slot between memory (+200) and tool (+300); ordered via {@code Builder.order(int)}. */
    public static final int ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 250;

    @Bean
    @ConditionalOnMissingBean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(VectorStore vectorStore,
                                                                    AiAgentRagProperties props) {
        VectorStoreDocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(props.resolvedSimilarityThreshold())
                .topK(props.resolvedTopK())
                .build();   // NO .filterExpression(...) — Pitfall #3

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .order(ADVISOR_ORDER)
                .build();
    }
}
