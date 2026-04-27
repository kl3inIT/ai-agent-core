package com.vn.agent.exposure;

import io.jmix.core.UnconstrainedDataManager;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads {@link AiExposureRule} rows via {@link UnconstrainedDataManager} so that user-role
 * tweaks cannot bypass admin governance (EXP-06, MEMORY feedback_jmix_unconstrained_for_system_writes).
 *
 * <p>Store routing is auto-resolved from {@link AiExposureRule}'s {@code @Store(name = "agentstore")}
 * annotation when using {@code dataManager.load(Class)} fluent API. The
 * MEMORY {@code feedback_jmix_loadvalue_store} rule applies only to the raw-JPQL
 * {@code loadValue/loadValues} paths (see {@code AuditWriter}, {@code ConversationListView}),
 * which take a string entity name and cannot infer the store automatically.
 */
@Component
public class LlmExposureRuleRepository {

    private final UnconstrainedDataManager dataManager;

    public LlmExposureRuleRepository(UnconstrainedDataManager dataManager) {
        this.dataManager = dataManager;
    }

    /**
     * Returns the set of entity MetaClass names where {@code enabled = true} and
     * {@code mode = EXCLUDE}. Called per chat-turn from {@link LlmExposurePolicy};
     * expected rule count &lt;50 so latency is negligible compared to LLM round-trip.
     */
    public Set<String> findEnabledExcludedEntityNames() {
        return dataManager.load(AiExposureRule.class)
                .query("select r from aiExposure_AiExposureRule r where r.enabled = true and r.mode = :mode")
                .parameter("mode", AiExposureRuleMode.EXCLUDE.getId())
                .list()
                .stream()
                .map(AiExposureRule::getEntityName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
