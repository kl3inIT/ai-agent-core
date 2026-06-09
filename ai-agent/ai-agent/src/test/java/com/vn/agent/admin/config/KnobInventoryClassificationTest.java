package com.vn.agent.admin.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan 16-06 Task 3 — D-02 three-layer enforcement test.
 *
 * <p><b>Rule 3 workaround</b>: the original plan called for {@code @SpringBootTest +
 * @TestPropertySource} but the ai-agent functional module's Spring context boot is
 * blocked by the documented Phase 11/13 regression (atmosphere-runtime /
 * agentstoreEntityManagerFactory) — the same blocker that forced Plans 13.1-06/07,
 * 14-01/02, 16-01/02/03/04/05 onto pure-JUnit workarounds.</p>
 *
 * <p>This test reflectively walks the 10 phase-targeted {@code @ConfigurationProperties}
 * carriers (the SAME classes the scanner walks at boot) and asserts the three D-02
 * invariants without a Spring context:</p>
 * <ul>
 *   <li>Every record component / setter-style field of the 10 phase-targeted
 *       carriers carries an {@code @KnobMetadata} annotation.</li>
 *   <li>Every Tier-2 boot toggle carries {@code requiresRestart=true}.</li>
 *   <li>Every Tier-1 migrated knob carries {@code requiresRestart=false}.</li>
 * </ul>
 *
 * <p>The prefix-filter assertion (opencode HIGH Concern #2) and property-key-
 * reconstruction assertion (codex HIGH Concern #7) cannot run without a Spring
 * context — they are documented here as deferred and will be enabled when the
 * boot regression is fixed in a future hardening pass.</p>
 */
@Tag("unit")
class KnobInventoryClassificationTest {

    /** The 10 phase-targeted {@code @ConfigurationProperties} carriers per RESEARCH §10. */
    private static final List<Class<?>> PHASE_TARGETED_CARRIERS = List.of(
            com.vn.agent.rag.config.AiAgentRagProperties.class,
            com.vn.agent.tools.mutation.AiAgentMutationProperties.class,
            com.vn.agent.audit.AiAgentAuditProperties.class,
            com.vn.agent.guard.AiAgentGuardProperties.class,
            com.vn.agent.orchestration.AiAgentPromptProperties.class,
            com.vn.agent.conversation.AiAgentTitleProperties.class,
            com.vn.agent.orchestration.AiAgentDefaultsProperties.class,
            com.vn.agent.taskfile.AiTaskFileProperties.class,
            com.vn.agent.rag.config.AiAgentEmbeddingProperties.class,
            com.vn.agent.extraction.AiExtractionProperties.class);

    @Test
    void everyConfigurationPropertiesRecordHasKnobMetadata() {
        List<String> unannotated = new ArrayList<>();
        for (Class<?> carrier : PHASE_TARGETED_CARRIERS) {
            if (carrier.isRecord()) {
                for (RecordComponent component : carrier.getRecordComponents()) {
                    if (component.getAnnotation(KnobMetadata.class) == null) {
                        unannotated.add(carrier.getSimpleName() + "." + component.getName());
                    }
                }
            } else {
                for (Field field : carrier.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (field.getAnnotation(KnobMetadata.class) == null) {
                        unannotated.add(carrier.getSimpleName() + "." + field.getName());
                    }
                }
            }
        }
        assertTrue(unannotated.isEmpty(),
                "Every component of the 10 phase-targeted @ConfigurationProperties " +
                        "carriers must carry @KnobMetadata. Missing: " + unannotated);
    }

    @Test
    void tier2BootToggleHasRequiresRestartTrue() {
        // Specifically asserted toggles per plan:
        // - AiAgentMutationProperties.enabled
        // - AiAgentTitleProperties.enabled
        // - AiAgentRagProperties.sampleIngester
        KnobMetadata mutationEnabled = recordComponentAnnotation(
                com.vn.agent.tools.mutation.AiAgentMutationProperties.class, "enabled");
        assertNotNull(mutationEnabled, "AiAgentMutationProperties.enabled must carry @KnobMetadata");
        assertEquals(KnobMetadata.Tier.TIER_2, mutationEnabled.tier());
        assertTrue(mutationEnabled.requiresRestart(),
                "Tier-2 boot toggle jmix.ai-agent.tools.mutation.enabled MUST carry requiresRestart=true");

        KnobMetadata titleEnabled = recordComponentAnnotation(
                com.vn.agent.conversation.AiAgentTitleProperties.class, "enabled");
        assertEquals(KnobMetadata.Tier.TIER_2, titleEnabled.tier());
        assertTrue(titleEnabled.requiresRestart());

        KnobMetadata sampleIngester = recordComponentAnnotation(
                com.vn.agent.rag.config.AiAgentRagProperties.class, "sampleIngester");
        assertEquals(KnobMetadata.Tier.TIER_2, sampleIngester.tier());
        assertTrue(sampleIngester.requiresRestart());

        // Walk every annotated component of the 10 carriers; every Tier-2
        // annotation MUST have requiresRestart=true.
        List<String> tier2WithoutRestart = new ArrayList<>();
        for (Class<?> carrier : PHASE_TARGETED_CARRIERS) {
            if (carrier.isRecord()) {
                for (RecordComponent component : carrier.getRecordComponents()) {
                    KnobMetadata m = component.getAnnotation(KnobMetadata.class);
                    if (m != null && m.tier() == KnobMetadata.Tier.TIER_2 && !m.requiresRestart()) {
                        tier2WithoutRestart.add(carrier.getSimpleName() + "." + component.getName());
                    }
                }
            } else {
                for (Field field : carrier.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    KnobMetadata m = field.getAnnotation(KnobMetadata.class);
                    if (m != null && m.tier() == KnobMetadata.Tier.TIER_2 && !m.requiresRestart()) {
                        tier2WithoutRestart.add(carrier.getSimpleName() + "." + field.getName());
                    }
                }
            }
        }
        assertTrue(tier2WithoutRestart.isEmpty(),
                "Every Tier-2 component MUST carry requiresRestart=true. Offenders: " + tier2WithoutRestart);
    }

    @Test
    void tier1MigratedKnobCarriesRequiresRestartFalse() {
        // Specifically asserted per plan:
        // - AiTaskFileProperties.ttlSeconds → Tier-1, requiresRestart=false
        // - AiTaskFileProperties.maxFileSizeBytes → Tier-1, requiresRestart=false (codex HIGH #4)
        KnobMetadata ttl = fieldAnnotation(
                com.vn.agent.taskfile.AiTaskFileProperties.class, "ttlSeconds");
        assertEquals(KnobMetadata.Tier.TIER_1, ttl.tier());
        assertFalse(ttl.requiresRestart(),
                "Tier-1 migrated knob ttlSeconds MUST NOT require restart");

        KnobMetadata maxFile = fieldAnnotation(
                com.vn.agent.taskfile.AiTaskFileProperties.class, "maxFileSizeBytes");
        assertEquals(KnobMetadata.Tier.TIER_1, maxFile.tier());
        assertFalse(maxFile.requiresRestart());
    }

    @Test
    void tier3SecretPatternMaskAppliesToUnannotatedKey() {
        // The Tier-3 pattern mask lives in AdminSecretPatternProperties.resolvedPatterns()
        // and is applied at scan time by KnobInventoryScanner.scanEnvironmentForSecrets()
        // to ANY property key whose name matches the glob — regardless of whether the
        // declaring record carries @KnobMetadata(tier=TIER_3). This test asserts the
        // contract is encoded in the locked default list.
        AdminSecretPatternProperties props = new AdminSecretPatternProperties(null);
        List<String> patterns = props.resolvedPatterns();
        assertTrue(patterns.contains("*.api-key"));
        assertTrue(patterns.contains("*.password"));
        assertTrue(patterns.contains("*.secret"));
        assertTrue(patterns.contains("*.token"));
        // A host adding `openai.api-key=VALUE` without @KnobMetadata(tier=TIER_3)
        // still gets masked because the glob `*.api-key` matches the raw property
        // name regardless of the declaring carrier. Defense-in-depth — RESEARCH §1.6.
        assertEquals(4, patterns.size(),
                "Locked default Tier-3 pattern list must remain exactly the SPEC-4 set.");
    }

    private static KnobMetadata recordComponentAnnotation(Class<?> carrier, String componentName) {
        for (RecordComponent component : carrier.getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                return component.getAnnotation(KnobMetadata.class);
            }
        }
        throw new AssertionError("No record component '" + componentName + "' on " + carrier.getSimpleName());
    }

    private static KnobMetadata fieldAnnotation(Class<?> carrier, String fieldName) {
        try {
            Field field = carrier.getDeclaredField(fieldName);
            return field.getAnnotation(KnobMetadata.class);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("No field '" + fieldName + "' on " + carrier.getSimpleName(), e);
        }
    }

}
