package com.vn.agent.admin.config;

import org.springframework.context.ApplicationEvent;

/**
 * Phase 16 D-04 — typed application event published when admin-managed AI settings
 * change. Carries a {@link Kind} payload so Phase 18 cache consumers can subscribe
 * selectively (parameters-only eviction vs ui-settings-only eviction).
 *
 * <p>Two kinds are defined, in this fixed order: {@link Kind#PARAMETERS} (per-profile
 * {@code AiParameters} row, only published when the saved row has {@code active=true}),
 * {@link Kind#UI_SETTINGS} (singleton {@code AiUiSettings} row, published on every save).
 *
 * <p>Publish sites: AiParametersEntityListener and AiUiSettingsEntityListener ONLY —
 * views/services NEVER inject ApplicationEventPublisher for this event (Plan 10-06 R2
 * invariant; SEC-08 second leg enforces).
 *
 * <p>One publish site per entity, with a source-scan test guarding against view/service drift.
 */
public class AiSettingsChangedEvent extends ApplicationEvent {

    /**
     * Settings cluster identifier. Order locked at D-04: PARAMETERS first, UI_SETTINGS second.
     */
    public enum Kind {
        /** Per-profile {@code AiParameters} row save (active profile only). */
        PARAMETERS,
        /** Singleton {@code AiUiSettings} row save. */
        UI_SETTINGS
    }

    private final Kind kind;

    public AiSettingsChangedEvent(Object source, Kind kind) {
        super(source);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
