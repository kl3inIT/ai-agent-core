package com.vn.agent.tools.mutation;

import com.vn.agent.admin.config.KnobMetadata;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the AI agent's mutation-capable tool surface, bound to
 * {@code jmix.ai-agent.tools.mutation.*}. Picked up by the {@code @ConfigurationPropertiesScan} on
 * {@code AIConfiguration}.
 *
 * <p>Default values (consulted by {@code resolved*} accessors):
 * <ul>
 *   <li>{@code resolvedEnabled() = false} — mutation tools are NOT registered as tool callbacks
 *       under default config (TEST-13 boot assertion). Hosts opt in via
 *       {@code jmix.ai-agent.tools.mutation.enabled=true}.</li>
 *   <li>{@code resolvedAllowDelete() = false} — forward-signal only. v1.1 does NOT ship a
 *       {@code delete_record} tool even when this flag is true (CONTEXT D-07).</li>
 *   <li>{@code resolvedConfirmationRequired() = true} — UX hint metadata only; not enforced
 *       by the tool layer (Phase 12 chat surfaces consume this).</li>
 *   <li>{@code resolvedIdempotencyTtl() = 24h} — TTL applied to {@code AiMutationIntent} rows
 *       at write time; cleanup job reaps expired {@code COMMITTED}/{@code FAILED} rows hourly.</li>
 * </ul>
 *
 * <p><b>Phase 11 plumbing:</b> first consumers are {@code BuiltInMutationTools} (gating chain),
 * {@code MutationIntentRepository} (TTL on reservations), and the conditional callback wiring
 * in {@code AgentToolCallbacks}. Mirrors the {@code AiAgentAuditProperties} (Phase 9 D-18) shape.
 *
 * <p><b>Security note:</b> no secrets are carried in this record; its {@code toString} is safe
 * to log.
 */
@ConfigurationProperties("jmix.ai-agent.tools.mutation")
public record AiAgentMutationProperties(
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.mutation.enabled")
        Boolean enabled,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.mutation.allowDelete")
        Boolean allowDelete,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_1, requiresRestart = false,
                displayMessageKey = "bootConfig.knob.mutation.confirmationRequired")
        Boolean confirmationRequired,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_1, requiresRestart = false,
                displayMessageKey = "bootConfig.knob.mutation.idempotencyTtl")
        Duration idempotencyTtl,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_1, requiresRestart = false,
                displayMessageKey = "bootConfig.knob.mutation.bulkMaxRows")
        Integer bulkMaxRows) {

    /** Phase 13 D-02 DoS guard — default cap on rows per {@code bulk_save_records} call. */
    public static final int DEFAULT_BULK_MAX_ROWS = 100;

    /** D-07: enabled defaults to disabled when key omitted (mutation tools opt-in). */
    public boolean resolvedEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** D-07: allowDelete defaults to disabled; forward-signal only — no method ships in v1.1. */
    public boolean resolvedAllowDelete() {
        return Boolean.TRUE.equals(allowDelete);
    }

    /** UX-hint metadata; defaults to enabled when key omitted (consumed by Phase 12 chat surfaces). */
    public boolean resolvedConfirmationRequired() {
        return !Boolean.FALSE.equals(confirmationRequired);
    }

    /** D-02: idempotency TTL defaults to 24h when key omitted; applied at reservation time. */
    public Duration resolvedIdempotencyTtl() {
        return idempotencyTtl == null ? Duration.ofHours(24) : idempotencyTtl;
    }

    /**
     * Phase 13 D-02 — DoS guard for {@code bulk_save_records}. Defaults to
     * {@value #DEFAULT_BULK_MAX_ROWS} when {@code jmix.ai-agent.tools.mutation.bulk-max-rows} is
     * unset. Values <= 0 fall back to the default (a misconfigured 0 must not silently
     * disable the bulk tool).
     */
    public int resolvedBulkMaxRows() {
        if (bulkMaxRows == null || bulkMaxRows <= 0) {
            return DEFAULT_BULK_MAX_ROWS;
        }
        return bulkMaxRows;
    }
}
