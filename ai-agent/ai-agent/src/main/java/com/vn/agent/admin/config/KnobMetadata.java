package com.vn.agent.admin.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Phase 16 D-02 — primary source-of-truth annotation that tags a single configuration
 * property component with its admin-UI tier, its restart requirement, and the locale
 * message key used to render its label in the Boot Config / Secrets surfaces.
 *
 * <p>Targets {@link ElementType#RECORD_COMPONENT} (Java records used as
 * {@code @ConfigurationProperties} carriers), {@link ElementType#FIELD} (classic
 * setter-style {@code @ConfigurationProperties} classes), and
 * {@link ElementType#METHOD} (accessor-style getters / record component accessor
 * methods) so the Phase 16 inventory scan can cover every shape that Spring Boot
 * supports for binding.
 *
 * <p>The {@link #displayMessageKey()} member is intentionally a string key — never a
 * literal label — so all label resolution flows through {@code io.jmix.core.Messages}
 * with root-bundle keys per project memory {@code feedback_jmix_messages_over_spring}.
 * Per-view bundles trip the IntelliJ Jmix plugin stale index; admin-config strings stay
 * in the root bundle.
 *
 * <p>{@link #requiresRestart()} is only meaningful when {@link #tier()} is
 * {@link Tier#TIER_2}; Tier-1 knobs are runtime-editable by definition and Tier-3
 * secrets are not displayed as editable rows at all.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface KnobMetadata {

    /** Admin-UI tier classification. */
    enum Tier {
        /** Runtime-editable operator knob; surfaced as a form field in the
         * {@code AiConfigurationView} "Tier-1 Knobs" tab. */
        TIER_1,
        /** Boot-time knob; surfaced in {@code KnobInventoryScanner} logs (no live UI consumer
         *  after admin-surface consolidation). */
        TIER_2,
        /** Secret material; classified by the inventory but never rendered — value is never read into a UI. */
        TIER_3
    }

    Tier tier();

    boolean requiresRestart() default false;

    String displayMessageKey() default "";
}
