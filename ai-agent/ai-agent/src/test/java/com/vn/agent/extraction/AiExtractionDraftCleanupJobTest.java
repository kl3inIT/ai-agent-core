package com.vn.agent.extraction;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 Plan 01 — structural cleanup-job assertions.
 *
 * <p>Runtime {@code @SpringBootTest} coverage is blocked by the pre-existing
 * shared Jmix test-context regression documented in STATE.md, so this test pins
 * the cleanup contract directly at the source seam.
 */
class AiExtractionDraftCleanupJobTest {

    private static final Path CLEANUP_JOB_SOURCE = Path.of(
            "src/main/java/com/vn/agent/extraction/AiExtractionDraftCleanupJob.java");

    @Test
    void cleanupJobUsesUnconstrainedDataManagerAndScheduledTtlPredicate() throws Exception {
        String source = Files.readString(CLEANUP_JOB_SOURCE);

        assertThat(source).contains(
                "import io.jmix.core.UnconstrainedDataManager;",
                "@Scheduled(fixedDelayString = \"${jmix.ai-agent.extraction.cleanup-interval-ms:3600000}\")",
                "@Transactional(\"agentstoreTransactionManager\")",
                "private final UnconstrainedDataManager dataManager;",
                "dataManager.load(AiExtractionDraft.class)",
                ".query(\"select e from ai_AiExtractionDraft e where e.expiresAt < :now\")",
                ".parameter(\"now\", now)",
                "dataManager.remove(expiredDraft)",
                "log.debug(\"Removed {} expired AiExtractionDraft rows\", expiredDrafts.size())");
        assertThat(source)
                .as("cleanup must not write audit rows")
                .doesNotContain("AuditWriter");
    }
}
