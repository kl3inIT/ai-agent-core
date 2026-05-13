package com.vn.agent;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wave 0 scaffold — Plan 16-07 implements the bodies.
 *
 * <p>{@code @Disabled} at class level until that plan; each method currently calls
 * {@code fail()} as a defense-in-depth gate.
 *
 * <p>Disabled until Plan 07 fills in the body — see Phase 16 inter-plan branch-green
 * strategy (16-REVIEWS codex HIGH concern #3).
 *
 * <p>Asserts the D-05 catch+reissue contract inside
 * {@code DefaultChatServiceImpl.executeBlockingTurn(...)}:
 * <ul>
 *   <li>Bad-model classification triggers exactly one reissue against
 *       {@code default-params.yaml.model}.</li>
 *   <li>Both audit rows (the MODEL_VALIDATION_FAILURE row + the recovered CHAT/TOOL
 *       row) share the same {@code runId}.</li>
 *   <li>The saved {@code AiParameters.bodyYaml.model} is NEVER mutated by the
 *       reissue — swap is local to the reissue's {@code ChatOptions}.</li>
 *   <li>Non-bad-model exceptions propagate unchanged.</li>
 *   <li>A 5xx response does NOT trigger reissue (Pitfall 6 false-positive guard).</li>
 *   <li>A direct {@code RestClientResponseException} (not wrapped in
 *       {@code NonTransientAiException}) triggers reissue (codex HIGH Concern #8 —
 *       classifier must catch both).</li>
 *   <li>The user-visible fallback notification fires (codex HIGH Concern #9 —
 *       MODEL-02 fallback notification surface is wired in Plan 07, not deferred).</li>
 * </ul>
 *
 * <p>Boot stack mirrors {@code DefaultChatServiceImplStreamFallbackTest}.
 */
@Disabled("Phase 16 Wave 0 scaffold — Plan 07 removes @Disabled and fills in body")
@Tag("phase-16-scaffold")
class DefaultChatServiceImplModelValidationFallbackTest {

    @Test
    void badModelExceptionTriggersOneShotReissue() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 07");
    }

    @Test
    void bothAuditRowsShareRunId() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 07");
    }

    @Test
    void savedAiParametersBodyYamlModelIsNotMutated() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 07");
    }

    @Test
    void nonBadModelExceptionPropagates() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 07");
    }

    @Test
    void _5xxResponseDoesNotTriggerReissue() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 07");
    }

    @Test
    void directRestClientResponseExceptionTriggersReissue() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 07");
    }

    @Test
    void userVisibleFallbackNotificationFires() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 07");
    }
}
