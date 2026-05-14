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

    @JmixId
    @JmixGeneratedValue
    private UUID id;

    private String key;
    private String displayMessageKey;
    private boolean configured;

    public AiSecretIndicatorRow() {
    }

    /**
     * Convenience all-args constructor mirroring the previous record signature
     * for scanner call sites that pre-date the id field.
     */
    public AiSecretIndicatorRow(String key, String displayMessageKey, boolean configured) {
        this.key = key;
        this.displayMessageKey = displayMessageKey;
        this.configured = configured;
    }

    /**
     * Full constructor including the synthetic id (for tests / cloning paths
     * that want a deterministic id).
     */
    public AiSecretIndicatorRow(UUID id, String key, String displayMessageKey, boolean configured) {
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

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }
}
