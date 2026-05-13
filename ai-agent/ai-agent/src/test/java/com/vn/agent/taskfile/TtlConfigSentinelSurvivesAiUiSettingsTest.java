package com.vn.agent.taskfile;

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
 * <p>Mirrors {@code TtlConfigSentinelSkipsCleanupTest} (Phase 13.1) but reads the
 * sentinel through the new {@code AiUiSettings.TASK_FILE_TTL_SECONDS} column added
 * by Plan 02 instead of through {@code module.properties}. Asserts that:
 * <ul>
 *   <li>Sentinel {@code -1} in the {@code AiUiSettings} row skips cleanup (Phase
 *       13.1 invariant preserved when source flips DB → property).</li>
 *   <li>Null column falls through to the {@code module.properties} value.</li>
 *   <li>Null column with sentinel-{@code -1} property still skips cleanup.</li>
 * </ul>
 * Boot stack per Pattern I; sentinel set via the new column added in Plan 02.
 */
@Disabled("Phase 16 Wave 0 scaffold — Plan 03 removes @Disabled and fills in body")
@Tag("phase-16-scaffold")
class TtlConfigSentinelSurvivesAiUiSettingsTest {

    @Test
    void sentinelMinusOneInUiSettingsRowSkipsCleanup() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }

    @Test
    void nullColumnFallsThroughToPropertyValue() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }

    @Test
    void nullColumnFallsThroughToPropertySentinel() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 03");
    }
}
