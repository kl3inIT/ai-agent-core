package com.vn.agent.admin.config;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wave 0 scaffold — Plan 16-03 implements the bodies.
 *
 * <p>{@code @Disabled} at class level until that plan; each method currently calls
 * {@code fail()} as a defense-in-depth gate.
 *
 * <p>Disabled until Plan 03 fills in the body — see Phase 16 inter-plan branch-green
 * strategy (16-REVIEWS codex HIGH concern #3).
 *
 * <p>Asserts the D-03 read-through invariant for {@code AiUiSettingsResolver}:
 * DB column non-null wins; null column falls through to {@code @ConfigurationProperties}
 * value; absent property falls through to constant default. Covers all five Tier-1
 * clusters (task-file, mutation, prompt/tools, title, upload). Boot stack per Pattern I.
 */
@Disabled("Phase 16 Wave 0 scaffold — Plan 03 removes @Disabled and fills in body")
@Tag("phase-16-scaffold")
class AiUiSettingsResolverReadThroughTest {

    @Test
    void taskFileDbWinsOverProperty() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }

    @Test
    void taskFileNullColumnFallsThroughToProperty() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }

    @Test
    void taskFilePropertyAbsentFallsThroughToConstant() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }

    @Test
    void mutationDbWinsOverProperty() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }

    @Test
    void mutationNullColumnFallsThroughToProperty() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }

    @Test
    void promptToolsDbWinsOverProperty() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }

    @Test
    void titleDbWinsOverProperty() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }

    @Test
    void uploadDbWinsOverProperty() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }
}
