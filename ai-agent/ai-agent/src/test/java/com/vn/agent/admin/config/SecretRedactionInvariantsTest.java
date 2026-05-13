package com.vn.agent.admin.config;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wave 0 scaffold — Plan 16-06 implements the bodies (SEC-08).
 *
 * <p>{@code @Disabled} at class level until that plan; each method currently calls
 * {@code fail()} as a defense-in-depth gate.
 *
 * <p>Disabled until Plan 06 fills in the body — see Phase 16 inter-plan branch-green
 * strategy (16-REVIEWS codex HIGH concern #3).
 *
 * <p>Three legs of SEC-08:
 * <ul>
 *   <li>{@link #noSecretBoundEditable()} — XML scan for property names matching
 *       {@code .*\.api-key|.*\.password|.*\.secret|.*\.token} bound to editable
 *       Vaadin components (HasValue subclasses without setReadOnly(true)).</li>
 *   <li>{@link #noConditionalOnPropertyToggleBoundEditable()} — classpath scan for
 *       {@code @ConditionalOnProperty} keys cross-referenced with XML
 *       {@code property=} attributes; toggle keys must not bind to editable
 *       components (Tier-2 boot-time only).</li>
 *   <li>{@link #noPublisherOutsideTwoEntityListeners()} — single-publish-site
 *       invariant: {@code ApplicationEventPublisher.publishEvent(new
 *       AiSettingsChangedEvent(...))} substring appears ONLY in
 *       {@code AiParametersEntityListener} and {@code AiUiSettingsEntityListener}
 *       source files (mirrors {@code AiExposureRuleEntityListener} precedent from
 *       Plan 10-06 R2).</li>
 * </ul>
 *
 * <p>Uses {@code java.nio.file.Files.walk(Paths.get("ai-agent/ai-agent/src/main"))}
 * shape; no Reflections dependency.
 */
@Disabled("Phase 16 Wave 0 scaffold — Plan 06 removes @Disabled and fills in body")
@Tag("phase-16-scaffold")
class SecretRedactionInvariantsTest {

    @Test
    void noSecretBoundEditable() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 06");
    }

    @Test
    void noConditionalOnPropertyToggleBoundEditable() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 06");
    }

    @Test
    void noPublisherOutsideTwoEntityListeners() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 06");
    }
}
