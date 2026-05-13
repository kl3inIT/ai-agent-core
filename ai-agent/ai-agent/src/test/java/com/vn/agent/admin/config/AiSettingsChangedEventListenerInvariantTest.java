package com.vn.agent.admin.config;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wave 0 scaffold — Plan 16-04 implements the bodies.
 *
 * <p>{@code @Disabled} at class level until that plan; each method currently calls
 * {@code fail()} as a defense-in-depth gate.
 *
 * <p>Disabled until Plan 04 fills in the body — see Phase 16 inter-plan branch-green
 * strategy (16-REVIEWS codex HIGH concern #3).
 *
 * <p>Asserts the D-04 publish contract for {@link AiSettingsChangedEvent}:
 * <ul>
 *   <li>Active {@code AiParameters} save publishes exactly one
 *       {@code AiSettingsChangedEvent(kind=PARAMETERS)}.</li>
 *   <li>Inactive {@code AiParameters} save publishes ZERO events.</li>
 *   <li>{@code AiUiSettings} save publishes exactly one
 *       {@code AiSettingsChangedEvent(kind=UI_SETTINGS)}.</li>
 *   <li>Source scan: {@code AiParametersEntityListener} and
 *       {@code AiUiSettingsEntityListener} are the only files that contain the
 *       {@code publishEvent(new AiSettingsChangedEvent} substring.</li>
 * </ul>
 *
 * <p>Boot stack per Pattern I; use {@code @RecordApplicationEvents} +
 * {@code ApplicationEvents} injection to capture events.
 */
@Disabled("Phase 16 Wave 0 scaffold — Plan 04 removes @Disabled and fills in body")
@Tag("phase-16-scaffold")
class AiSettingsChangedEventListenerInvariantTest {

    @Test
    void activeParametersSavePublishesPARAMETERS() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 04");
    }

    @Test
    void inactiveParametersSavePublishesZero() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 04");
    }

    @Test
    void uiSettingsSavePublishesUI_SETTINGS() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 04");
    }

    @Test
    void singlePublishSiteSourceScan() {
        fail("Phase 16 Wave 0 scaffold — implemented by Plan 04");
    }
}
