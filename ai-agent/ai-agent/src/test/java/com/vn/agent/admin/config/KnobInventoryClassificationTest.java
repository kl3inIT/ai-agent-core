package com.vn.agent.admin.config;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wave 0 scaffold — Plan 16-06 implements the bodies.
 *
 * <p>{@code @Disabled} at class level until that plan; each method currently calls
 * {@code fail()} as a defense-in-depth gate.
 *
 * <p>Disabled until Plan 06 fills in the body — see Phase 16 inter-plan branch-green
 * strategy (16-REVIEWS codex HIGH concern #3).
 *
 * <p>Asserts the D-02 three-layer mechanism for {@link KnobMetadata}:
 * <ul>
 *   <li>Every existing {@code @ConfigurationProperties} record component carries a
 *       {@link KnobMetadata} annotation (reflective scan filtered by
 *       {@code jmix.ai-agent.} / {@code ai-agent.} prefix per opencode HIGH
 *       Concern #2).</li>
 *   <li>The Tier-3 secret-pattern mask still applies to an un-annotated property key
 *       (defense-in-depth — a host forgetting {@code @KnobMetadata(tier=TIER_3)}
 *       must still be masked).</li>
 *   <li>Every {@link KnobMetadata#tier()}={@code TIER_2} boot-time toggle carries
 *       {@code requiresRestart=true}.</li>
 * </ul>
 */
@Disabled("Phase 16 Wave 0 scaffold — Plan 06 removes @Disabled and fills in body")
@Tag("phase-16-scaffold")
class KnobInventoryClassificationTest {

    @Test
    void everyConfigurationPropertiesRecordHasKnobMetadata() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 06");
    }

    @Test
    void tier3SecretPatternMaskAppliesToUnannotatedKey() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 06");
    }

    @Test
    void tier2BootToggleHasRequiresRestartTrue() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 06");
    }
}
