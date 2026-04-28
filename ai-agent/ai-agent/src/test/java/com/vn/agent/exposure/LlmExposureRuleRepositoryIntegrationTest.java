package com.vn.agent.exposure;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WARNING-09 regression gate — pins the explicit "AiExposureRule lives in agentstore"
 * routing. {@link LlmExposureRuleRepository#findEnabledExcludedEntityNames()} relies on
 * the {@code @Store(name = "agentstore")} annotation being honoured by the fluent
 * {@code dataManager.load(Class)} path. The MEMORY rule
 * {@code feedback_jmix_loadvalue_store} only applies to raw-JPQL
 * {@code loadValue/loadValues}; this test prevents an accidental future switch from the
 * fluent path silently routing to the main datasource.
 *
 * <p>Seeds a single rule via {@link UnconstrainedDataManager}, asserts the repository
 * surfaces it, and removes the row in {@code @AfterEach}. Mirrors the seed/cleanup
 * pattern used by {@link LlmExposurePolicyIntegrationTest}.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class LlmExposureRuleRepositoryIntegrationTest {

    /**
     * Pinned to a stable on-classpath Jmix entity so the test row never collides with
     * host-supplied rules. Same rationale as {@code LlmExposurePolicyIntegrationTest.TEST_ENTITY_NAME}.
     */
    private static final String SEED_ENTITY_NAME = "ai_AiAuditEvent";

    @Autowired LlmExposureRuleRepository repository;
    @Autowired UnconstrainedDataManager unconstrainedDataManager;
    @Autowired Metadata metadata;

    private UUID seededRuleId;

    @AfterEach
    void cleanUp() {
        if (seededRuleId != null) {
            unconstrainedDataManager.load(AiExposureRule.class)
                    .id(seededRuleId)
                    .optional()
                    .ifPresent(unconstrainedDataManager::remove);
            seededRuleId = null;
        }
    }

    @Test
    void seededRuleIsReadFromAgentstore() {
        AiExposureRule rule = metadata.create(AiExposureRule.class);
        rule.setEntityName(SEED_ENTITY_NAME);
        rule.setMode(AiExposureRuleMode.EXCLUDE);
        rule.setEnabled(true);
        AiExposureRule saved = unconstrainedDataManager.save(rule);
        seededRuleId = saved.getId();

        Set<String> denied = repository.findEnabledExcludedEntityNames();

        assertThat(denied)
                .as("LlmExposureRuleRepository must read from the agentstore datasource — "
                        + "the seeded rule for %s must surface in the result set "
                        + "(WARNING-09 regression gate)", SEED_ENTITY_NAME)
                .contains(SEED_ENTITY_NAME);
    }

    @Test
    void disabledRuleIsExcluded() {
        AiExposureRule rule = metadata.create(AiExposureRule.class);
        rule.setEntityName(SEED_ENTITY_NAME);
        rule.setMode(AiExposureRuleMode.EXCLUDE);
        rule.setEnabled(false); // disabled — must NOT surface
        AiExposureRule saved = unconstrainedDataManager.save(rule);
        seededRuleId = saved.getId();

        Set<String> denied = repository.findEnabledExcludedEntityNames();

        assertThat(denied)
                .as("Disabled rules must be excluded from the denylist (enabled=false filter)")
                .doesNotContain(SEED_ENTITY_NAME);
    }
}
