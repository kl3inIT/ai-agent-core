package com.vn.agent.exposure;

import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin-facing write service for maintaining the entity denylist exposed through
 * the AI configuration UI.
 */
@Service
public class AiExposureRuleAdminService {

    private final UnconstrainedDataManager dataManager;
    private final Metadata metadata;
    private final LlmExposureRuleRepository ruleRepository;

    public AiExposureRuleAdminService(UnconstrainedDataManager dataManager,
                                      Metadata metadata,
                                      LlmExposureRuleRepository ruleRepository) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.ruleRepository = ruleRepository;
    }

    public Set<String> findHiddenEntityNames() {
        return ruleRepository.findEnabledExcludedEntityNames();
    }

    /**
     * Replaces the active denylist for the selectable entity set shown in the UI.
     *
     * <p>Only rules whose entity name is present in {@code selectableEntityNames} are
     * disabled when absent from {@code selectedHiddenEntityNames}. Existing enabled
     * rules for non-selectable/legacy entities are preserved because the user cannot
     * see or intentionally change them from the selector.</p>
     */
    public void replaceHiddenEntityNames(Collection<String> selectedHiddenEntityNames,
                                         Collection<String> selectableEntityNames) {
        Set<String> selectableNames = normalize(selectableEntityNames);
        if (selectableNames.isEmpty()) {
            return;
        }

        Set<String> selectedNames = normalize(selectedHiddenEntityNames).stream()
                .filter(selectableNames::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<AiExposureRule> existingRules = dataManager.load(AiExposureRule.class)
                .query("select r from aiExposure_AiExposureRule r where r.mode = :mode")
                .parameter("mode", AiExposureRuleMode.EXCLUDE.getId())
                .list();

        Map<String, AiExposureRule> rulesByEntityName = existingRules.stream()
                .filter(rule -> rule.getEntityName() != null)
                .collect(Collectors.toMap(
                        AiExposureRule::getEntityName,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        List<AiExposureRule> changedRules = new ArrayList<>();
        for (String entityName : selectedNames) {
            AiExposureRule rule = rulesByEntityName.get(entityName);
            if (rule == null) {
                rule = metadata.create(AiExposureRule.class);
                rule.setEntityName(entityName);
                rule.setMode(AiExposureRuleMode.EXCLUDE);
                rule.setEnabled(true);
                changedRules.add(rule);
            } else if (!Boolean.TRUE.equals(rule.getEnabled())) {
                rule.setEnabled(true);
                changedRules.add(rule);
            }
        }

        for (AiExposureRule rule : existingRules) {
            String entityName = rule.getEntityName();
            if (entityName != null
                    && selectableNames.contains(entityName)
                    && !selectedNames.contains(entityName)
                    && Boolean.TRUE.equals(rule.getEnabled())) {
                rule.setEnabled(false);
                changedRules.add(rule);
            }
        }

        for (AiExposureRule rule : changedRules) {
            dataManager.save(rule);
        }
    }

    private static Set<String> normalize(Collection<String> entityNames) {
        if (entityNames == null || entityNames.isEmpty()) {
            return Set.of();
        }
        return entityNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
