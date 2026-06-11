package com.vn.agent.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Phase 16 D-02 (Tier-3 secret pattern, defense-in-depth) — configurable
 * pattern list consumed by the {@code KnobInventoryScanner} when computing
 * Tier-3 secret indicators.
 *
 * <p>Bound to {@code jmix.ai-agent.admin.*}. Picked up automatically by the
 * {@code @ConfigurationPropertiesScan} on
 * {@link com.vn.agent.AIConfiguration}.</p>
 *
 * <p>The locked default list per D-02 / SPEC criterion 4 is exactly
 * {@code ["*.api-key", "*.password", "*.secret", "*.token"]} — matched at
 * scan time across ALL {@code Environment} keys (annotated and
 * un-annotated). Defense-in-depth: a host adding a new {@code *.api-key}
 * field without {@code @KnobMetadata(tier=TIER_3)} still gets the value
 * classified as a secret in the inventory.</p>
 *
 * <p>Hosts that ship additional secret-bearing property shapes (e.g.
 * {@code *.private-key}, {@code *.credential}) override the list via
 * {@code jmix.ai-agent.admin.secret-property-patterns}.</p>
 *
 * <p>{@link #resolvedPatterns()} returns the locked 4-element default when
 * the property is {@code null} or empty — never returns {@code null}.</p>
 *
 * <p>Per project memory {@code feedback_pragmatic_modules}, this record is
 * intentionally minimal: no speculative SPI fields, no nested grouping
 * structure — one list, one resolver method.</p>
 *
 * <p><b>Security note</b>: this record carries pattern strings, not the
 * matched values themselves; its {@code toString} is safe to log.</p>
 */
@ConfigurationProperties("jmix.ai-agent.admin")
public record AdminSecretPatternProperties(
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2,
                requiresRestart = true,
                displayMessageKey = "aiUiSettings.bootConfig.secretPropertyPatterns")
        List<String> secretPropertyPatterns) {

    /**
     * Locked default secret-pattern list per Phase 16 D-02 / SPEC criterion 4.
     * Held as a constant so tests and the {@link #resolvedPatterns()} fallback
     * share a single source of truth.
     */
    public static final List<String> LOCKED_DEFAULT_PATTERNS = List.of(
            "*.api-key", "*.password", "*.secret", "*.token");

    /**
     * Resolved pattern list — the configured value if non-null and non-empty,
     * otherwise the locked 4-element default. Never returns {@code null}.
     */
    public List<String> resolvedPatterns() {
        return (secretPropertyPatterns == null || secretPropertyPatterns.isEmpty())
                ? LOCKED_DEFAULT_PATTERNS
                : secretPropertyPatterns;
    }
}
