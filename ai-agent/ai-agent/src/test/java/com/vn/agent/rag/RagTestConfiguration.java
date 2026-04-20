package com.vn.agent.rag;

import com.vn.agent.rag.config.StubEmbeddingModelConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Integration-test override: replaces the async {@code aiAgentIngestExecutor} with a
 * {@link SyncTaskExecutor} per CONTEXT D-12 so Phase 5 integration tests observe a fully
 * completed ingest on the test thread — no polling loops, no {@link Thread#sleep}, no flakes.
 *
 * <p>Also imports {@link StubEmbeddingModelConfiguration} so Phase 5 integration tests never
 * reach OpenRouter — deterministic 1536-dim vectors derived from {@link String#hashCode} are
 * sufficient for role-scoping, atomicity, and fail-closed posture assertions.</p>
 *
 * <p>This is a {@code @TestConfiguration} (test-only classpath), so it cannot leak into
 * production auto-configuration (threat T-05-05-01 mitigation). The bean-collision test from
 * Plan 05-01 + {@code @ConditionalOnMissingBean} seams from Plan 05-01 protect the shipping
 * artifact.</p>
 */
@TestConfiguration
@Profile("rag-it")
@Import(StubEmbeddingModelConfiguration.class)
public class RagTestConfiguration {

    /**
     * Override the production {@code ThreadPoolTaskExecutor} registered in {@code AIConfiguration}
     * with a {@link SyncTaskExecutor}. The {@code @Primary} marker wins bean selection for
     * {@code @Async("aiAgentIngestExecutor")} resolution in {@link AsyncIngestionWorker}.
     *
     * <p>NOTE: Spring's {@code @Async} infrastructure resolves the named executor via the bean
     * factory; declaring the same bean name with {@code @Primary} is the documented way to
     * swap a named async executor in tests (it cannot be registered with a conflicting type
     * under the same name unless the existing definition is replaced).</p>
     */
    @Bean(name = "aiAgentIngestExecutor")
    @Primary
    public TaskExecutor aiAgentIngestExecutor() {
        return new SyncTaskExecutor();
    }
}
