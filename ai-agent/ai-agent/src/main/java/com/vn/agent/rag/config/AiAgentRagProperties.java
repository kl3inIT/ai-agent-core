package com.vn.agent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration surface for the RAG layer, bound to {@code jmix.ai-agent.rag.*}
 * (D-22). Picked up by the {@code @ConfigurationPropertiesScan} on
 * {@code AIConfiguration} — no {@code @Component} needed.
 *
 * <p>Key groups:</p>
 * <ul>
 *   <li>{@link #adminBypass()} — D-06: when {@code AiAgentAdminRole} present,
 *       {@code RetrievalFilterBuilder} returns {@code null} and the retriever runs
 *       unfiltered. Default {@code true}; hosts with stricter governance flip to
 *       {@code false} without code changes.</li>
 *   <li>{@link #topK()} / {@link #similarityThreshold()} — tuning knobs for
 *       {@code VectorStoreDocumentRetriever} (AI-SPEC §4).</li>
 *   <li>{@link #splitter()} — {@code TokenTextSplitter} chunking sizes (D-13).
 *       Note: {@code chunkOverlap} is a semantic alias — {@code TokenTextSplitter}
 *       does not do overlapping windows; {@code minChunkSizeChars} is the honest
 *       lower-bound knob (RESEARCH Pitfall #5).</li>
 *   <li>{@link #embedRetry()} — Spring Retry backoff bounds for transient
 *       provider errors during embed calls (D-16).</li>
 *   <li>{@link #sampleIngester()} — classpath-markdown reference ingester
 *       enablement + path pattern (D-17).</li>
 *   <li>{@link #ingestExecutor()} — bounded {@code ThreadPoolTaskExecutor} sizing
 *       for the async ingestion worker (D-12 / D-14).</li>
 *   <li>{@link #ingest()} — hard guards on admissible document size before
 *       embedding (cost protection for bulk uploads).</li>
 * </ul>
 *
 * <p>All fields are boxed nullable types so missing keys tolerate gracefully;
 * {@code resolved*} accessors supply documented defaults. Records are for
 * binding — defaults live in accessors and constants so the property source is
 * the single source of truth.</p>
 *
 * <p><b>Security note</b>: no secrets are carried in this record; its
 * {@code toString} is safe to log. Any future addition of a secret-bearing field
 * must carry a {@code @JsonIgnore} / custom {@code toString} review.</p>
 */
@ConfigurationProperties("jmix.ai-agent.rag")
public record AiAgentRagProperties(
        Boolean adminBypass,
        Integer topK,
        Double similarityThreshold,
        Splitter splitter,
        EmbedRetry embedRetry,
        SampleIngester sampleIngester,
        IngestExecutor ingestExecutor,
        Ingest ingest) {

    /** {@code TokenTextSplitter} sizing — see D-13 and RESEARCH Pitfall #5. */
    public record Splitter(Integer chunkSize, Integer chunkOverlap, Integer minChunkSizeChars) {}

    /** Spring Retry bounds around the embed call (D-16). */
    public record EmbedRetry(Integer maxAttempts, Duration initialInterval, Double multiplier) {}

    /** Classpath-markdown reference ingester, disabled by default (D-17). */
    public record SampleIngester(Boolean enabled, String pathPattern) {}

    /** Bounded ingestion executor sizing (D-12). */
    public record IngestExecutor(Integer corePoolSize, Integer maxPoolSize, Integer queueCapacity, Integer keepAliveSeconds) {}

    /** Pre-embed admission guards. */
    public record Ingest(Integer maxDocumentChars) {}

    /** D-06: admin bypass default is {@code true}. */
    public boolean isAdminBypass() {
        return adminBypass == null || adminBypass;
    }

    /** AI-SPEC §4 default {@code topK = 5}. */
    public int resolvedTopK() {
        return topK == null ? 5 : topK;
    }

    /** AI-SPEC §4 default similarity threshold {@code 0.50}. */
    public double resolvedSimilarityThreshold() {
        return similarityThreshold == null ? 0.50 : similarityThreshold;
    }
}
