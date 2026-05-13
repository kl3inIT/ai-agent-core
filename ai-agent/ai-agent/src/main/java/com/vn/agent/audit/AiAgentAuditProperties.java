package com.vn.agent.audit;

import com.vn.agent.admin.config.KnobMetadata;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Configuration surface for the AUD-07 sensitive-field hashing plumbing, bound to
 * {@code jmix.ai-agent.audit.*}. Picked up by the {@code @ConfigurationPropertiesScan} on
 * {@code AIConfiguration}.
 *
 * <p><b>Phase 9 plumbing only (D-18):</b> no caller in v1.1's read-only milestone reads
 * these properties yet. Phase 11 ({@code MutationErrorTranslator} / pre/post-image diff) is the
 * planned consumer. The keys ship now so Phase 11 wiring is one-liner.
 *
 * <p>Defaults (consulted by {@code resolved*} accessors):
 * <ul>
 *   <li>{@code resolvedHashSensitiveFields() = true} — opt-in by default; hosts opt out via
 *       {@code jmix.ai-agent.audit.hash-sensitive-fields=false}.</li>
 *   <li>{@code resolvedSensitiveFields() = Set.of()} — empty default; hosts populate per
 *       deployment. Phase 9 v1.1 ships no canonical field-name list because there is no
 *       mutation surface yet to label PII fields against.</li>
 * </ul>
 *
 * <p><b>Security note:</b> no secrets are carried in this record; its {@code toString} is
 * safe to log. {@code sensitiveFields} contents are attribute-name strings, not values.
 */
@ConfigurationProperties("jmix.ai-agent.audit")
public record AiAgentAuditProperties(
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.audit.hashSensitiveFields")
        Boolean hashSensitiveFields,
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2, requiresRestart = true,
                displayMessageKey = "bootConfig.knob.audit.sensitiveFields")
        Set<String> sensitiveFields) {

    /** D-18: hash-sensitive-fields defaults to enabled when key omitted. */
    public boolean resolvedHashSensitiveFields() {
        return !Boolean.FALSE.equals(hashSensitiveFields);
    }

    /** D-18: sensitive-fields defaults to empty when key omitted; returned set is unmodifiable. */
    public Set<String> resolvedSensitiveFields() {
        return sensitiveFields == null ? Set.of() : Set.copyOf(sensitiveFields);
    }
}
