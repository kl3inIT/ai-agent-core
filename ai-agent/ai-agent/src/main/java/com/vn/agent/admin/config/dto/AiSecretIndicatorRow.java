package com.vn.agent.admin.config.dto;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.JmixId;
import io.jmix.core.metamodel.annotation.JmixEntity;

import java.util.UUID;

/**
 * Plan 16-08 gap-closure fix for UAT test 4 — replaces nested record
 * {@code KnobInventory$SecretIndicatorRow} so the Jmix metamodel can resolve
 * {@code <collection class=...>} bindings at view-init time.
 *
 * <p><b>SEC-08 invariant:</b> this DTO carries the {@link #configured} boolean
 * ONLY — never the raw secret value. There is NO {@code value} / {@code raw} /
 * {@code secret} field on this class. The {@code configured} boolean IS the
 * indicator surface; the raw value lives only inside the scanner's
 * {@code isConfigured(...)} method and never escapes into a renderable field.</p>
 *
 * <p>Non-persistent Jmix DTO entity: carries {@code @JmixEntity} only (NO
 * {@code @Entity} / {@code @Store} / {@code @Table}) — this row is never
 * persisted. The {@code id} field exists solely to satisfy the Jmix
 * metamodel id contract for non-persistent DTOs.</p>
 *
 * <p><b>No record-compat accessors.</b> All caller sites (renderers, tests)
 * use JavaBeans-form ({@link #getKey()}, {@link #getDisplayMessageKey()},
 * {@link #isConfigured()}) to avoid Jmix metamodel duplicate-property scan
 * warnings.</p>
 */
@JmixEntity(name = "ai_AiSecretIndicatorRow")
public class AiSecretIndicatorRow {

    // Self-initialised: scanner constructs rows with `new AiSecretIndicatorRow(...)`
    // rather than `metadata.create(...)`, so @JmixGeneratedValue never fires.
    // Without a non-null id the Jmix metamodel throws "Generated ID is null"
    // when the DTO is bound to a CollectionContainer.
    @JmixId
    @JmixGeneratedValue
    private UUID id = UUID.randomUUID();

    private String key;
    private String displayMessageKey;
    // Jmix DTO enhancer rejects primitive boolean fields ("Use type Boolean").
    private Boolean configured;

    public AiSecretIndicatorRow() {
    }

    /**
     * Convenience all-args constructor mirroring the previous record signature
     * for scanner call sites that pre-date the id field.
     */
    public AiSecretIndicatorRow(String key, String displayMessageKey, Boolean configured) {
        this.key = key;
        this.displayMessageKey = displayMessageKey;
        this.configured = configured;
    }

    /**
     * Full constructor including the synthetic id (for tests / cloning paths
     * that want a deterministic id).
     */
    public AiSecretIndicatorRow(UUID id, String key, String displayMessageKey, Boolean configured) {
        this.id = id;
        this.key = key;
        this.displayMessageKey = displayMessageKey;
        this.configured = configured;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getDisplayMessageKey() {
        return displayMessageKey;
    }

    public void setDisplayMessageKey(String displayMessageKey) {
        this.displayMessageKey = displayMessageKey;
    }

    public Boolean getConfigured() {
        return configured;
    }

    public void setConfigured(Boolean configured) {
        this.configured = configured;
    }

    /**
     * Non-bean convenience predicate (no {@code is}/{@code get} prefix to avoid
     * Jmix duplicate-property scan). Returns {@code false} for {@code null}.
     */
    public boolean configured() {
        return Boolean.TRUE.equals(configured);
    }
}
