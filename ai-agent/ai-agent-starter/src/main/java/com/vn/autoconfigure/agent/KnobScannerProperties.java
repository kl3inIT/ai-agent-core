package com.vn.autoconfigure.agent;

import com.vn.agent.admin.config.KnobMetadata;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Phase 16 D-02 — host-extension hook for the {@code KnobInventoryScanner} prefix
 * filter (opencode HIGH Concern #2). The scanner walks only
 * {@code @ConfigurationProperties} beans whose prefix starts with one of the
 * built-in {@code jmix.ai-agent.} / {@code ai-agent.} roots OR any prefix listed
 * in {@link #additionalPrefixes()}. Without the filter the reflective walk would
 * touch 60-80 Spring / third-party beans on a typical Jmix app (200-500ms boot
 * cost); with the filter it stays bounded to ~10 records.
 *
 * <p>Hosts shipping their own {@code @ConfigurationProperties} records under a
 * custom prefix opt them into the admin inventory via
 * {@code ai-agent.admin.knob-scanner.additional-prefixes=mycompany.custom.}
 * (trailing dot recommended for prefix safety).</p>
 *
 * <p>Self-introspection: this record is itself annotated as Tier-2 so the
 * scanner reports its own additional-prefixes knob alongside the rest.</p>
 */
@ConfigurationProperties(prefix = "ai-agent.admin.knob-scanner")
public record KnobScannerProperties(
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.knobScanner.additionalPrefixes")
        Set<String> additionalPrefixes) {

    /** Never returns {@code null}; an absent / empty configured value resolves to an empty set. */
    public Set<String> resolvedAdditionalPrefixes() {
        return additionalPrefixes == null ? Set.of() : Set.copyOf(additionalPrefixes);
    }
}
