package com.vn.agent.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Phase 16 D-06 — curated open-weights chat-model catalog binding.
 *
 * <p>Bound to {@code jmix.ai-agent.models.*}. Picked up automatically by the
 * {@code @ConfigurationPropertiesScan} on
 * {@link com.vn.agent.AIConfiguration} — no {@code @Component} needed on the
 * record itself; the {@link ChatModelCatalog @Component} is the consumer that
 * validates and exposes the resolved view.</p>
 *
 * <p>Property shape (default seed in {@code module.properties} — see SPEC
 * criterion 4):</p>
 * <pre>
 * jmix.ai-agent.models.catalog[0].id=qwen/qwen3.6-35b-a3b
 * jmix.ai-agent.models.catalog[0].label-message-key=chatModelCatalog.qwen3_35b
 * jmix.ai-agent.models.catalog[0].is-default=true
 * </pre>
 *
 * <p>{@link Entry#isDefault()} uses the boxed {@link Boolean} so a missing key
 * binds to {@code null} and is treated as {@code false} by
 * {@link ChatModelCatalog#validate()}. {@link ChatModelCatalog} requires
 * exactly one entry with {@code isDefault=true}; the validator fails boot on
 * any other count.</p>
 *
 * <p>The marked-default entry's {@code id} MUST equal the value of
 * {@code default-params.yaml#model} — the drift gate is enforced both at boot
 * (in {@link ChatModelCatalog#validate()}) and at build time by
 * {@code ChatModelCatalogAllowlistTest#defaultMatchesDefaultParamsYaml()}
 * (TEST-20).</p>
 *
 * <p><b>Security note</b>: no secrets are carried in this record; its
 * {@code toString} is safe to log. Catalog content is a public taxonomy tag,
 * not credential material.</p>
 *
 * <p>Per project memory {@code feedback_pragmatic_modules}, this record is
 * intentionally minimal — no speculative SPI fields or host-extension hooks
 * until a concrete consumer is named.</p>
 */
@ConfigurationProperties("jmix.ai-agent.models")
public record ChatModelCatalogProperties(
        @KnobMetadata(tier = KnobMetadata.Tier.TIER_2,
                requiresRestart = true,
                displayMessageKey = "aiUiSettings.bootConfig.modelCatalog")
        List<Entry> catalog) {

    /**
     * One curated catalog row. The {@code labelMessageKey} resolves through
     * {@code io.jmix.core.Messages} at render time (per project memory
     * {@code feedback_jmix_messages_over_spring} — root-bundle keys only).
     *
     * <p>{@code isDefault} is {@link Boolean} (not primitive) so a missing
     * property binds to {@code null} and the boot-time validator can detect
     * the absent-vs-explicit-false case cleanly.</p>
     */
    public record Entry(String id, String labelMessageKey, Boolean isDefault) {
    }
}
