package com.vn.agent.admin.config;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wave 0 scaffold — Plan 16-02 implements the bodies (added per codex MEDIUM fix to
 * align scaffold drift with Plan 02's inventory).
 *
 * <p>{@code @Disabled} at class level until that plan; each method currently calls
 * {@code fail()} as a defense-in-depth gate.
 *
 * <p>Disabled until Plan 02 fills in the body — see Phase 16 inter-plan branch-green
 * strategy (16-REVIEWS codex HIGH concern #3).
 *
 * <p>Asserts the Jakarta bean-validation bounds on every new Tier-1 column on
 * {@code AiUiSettings}, including the sentinel {@code -1} carve-out for task-file
 * cluster knobs (Phase 13.1 invariant) and the new {@code taskFileMaxFileSizeBytes}
 * column (codex HIGH Concern #4 — distinct from {@code uploadMaxFileSizeBytes}).
 * Boot stack mirrors {@code TtlConfigTest} (Pattern I).
 */
@Disabled("Phase 16 Wave 0 scaffold — Plan 02 removes @Disabled and fills in body")
@Tag("phase-16-scaffold")
class AiUiSettingsBeanValidationTest {

    @Test
    void taskFileTtlBelowMinusOneRejected() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }

    @Test
    void taskFileTtlMinusOneSentinelAccepted() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }

    @Test
    void taskFileTtlAboveMaxRejected() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }

    @Test
    void mutationBulkMaxRowsBelowMinRejected() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }

    @Test
    void mutationBulkMaxRowsAboveMaxRejected() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }

    @Test
    void uploadMaxFileSizeBytesBelowFloorRejected() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }

    @Test
    void mutationConfirmationRequiredAcceptsAnyBooleanIncludingNull() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }

    @Test
    void taskFileMaxFileSizeBytesBelowFloorRejected() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 02");
    }
}
