package com.vn.agent.admin.config;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wave 0 scaffold — Plan 16-02 implements the bodies.
 *
 * <p>{@code @Disabled} at class level until that plan; each method currently calls
 * {@code fail()} as a defense-in-depth gate (so a future plan that accidentally
 * removes {@code @Disabled} without filling in the body FAILS rather than silently
 * passing).
 *
 * <p>Disabled until Plan 02 fills in the body — see Phase 16 inter-plan branch-green
 * strategy (16-REVIEWS codex HIGH concern #3).
 *
 * <p>TEST-20 covers: catalog subset of allowlist, exactly one default entry, and
 * the drift gate that the catalog's default matches {@code default-params.yaml.model}
 * (D-06 / SPEC criterion 4). Pure static-list checks against
 * {@code ChatModelCatalog.SELF_HOSTABLE_OPEN_WEIGHTS_ALLOWLIST} — no Spring context.
 */
@Disabled("Phase 16 Wave 0 scaffold — Plan 02 removes @Disabled and fills in body")
@Tag("phase-16-scaffold")
class ChatModelCatalogAllowlistTest {

    @Test
    void catalogSubsetOfAllowlist() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }

    @Test
    void exactlyOneDefault() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }

    @Test
    void defaultMatchesDefaultParamsYaml() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }
}
