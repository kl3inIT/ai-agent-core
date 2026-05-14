package com.vn.agent.orchestration;

import com.vn.agent.admin.config.KnobMetadata;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration surface for baseline prompt rendering, bound to {@code jmix.ai-agent.prompt.*}.
 * Picked up by the {@code @ConfigurationPropertiesScan} on {@code AIConfiguration}.
 *
 * <p>Defaults (consulted by {@code resolved*} accessors when keys are absent):
 * <ul>
 *   <li>{@code resolvedEntityInventoryLimit() = 100} — D-03: max entities rendered into the
 *       {@code agent.entities} block before the truncation hint kicks in. Aligns with
 *       {@code TOOL-06 find_records max=100} cap.</li>
 * </ul>
 *
 * <p><b>Cache-key invariant (P-8 / PROMPT-02 explicit wording):</b> locale-sensitive labels
 * are NEVER part of any cache key derived from these properties. Phase 9 baseline rendering is
 * uncached per-request; if Phase 10+ adds caching the key MUST be {@code (userId, roleSet,
 * metaclass-name-set)} only, with labels resolved at render time via
 * {@code MessageTools.getEntityCaption}.
 */
@ConfigurationProperties("jmix.ai-agent.prompt")
public record AiAgentPromptProperties(
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_1, requiresRestart = false,
                displayMessageKey = "bootConfig.knob.prompt.entityInventory")
        EntityInventory entityInventory) {

    /** D-03: entity-inventory truncation config. */
    public record EntityInventory(Integer limit) {
    }

    /** D-03: default 100 entities. */
    public int resolvedEntityInventoryLimit() {
        return entityInventory == null || entityInventory.limit() == null ? 100 : entityInventory.limit();
    }
}
