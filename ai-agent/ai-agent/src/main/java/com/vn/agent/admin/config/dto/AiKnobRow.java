package com.vn.agent.admin.config.dto;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.JmixId;
import io.jmix.core.metamodel.annotation.JmixEntity;

import java.util.UUID;

/**
 * Plan 16-08 gap-closure fix for UAT test 4 — replaces nested record
 * {@code KnobInventory$KnobRow} so the Jmix metamodel can resolve
 * {@code <collection class=...>} bindings at view-init time.
 *
 * <p>Non-persistent Jmix DTO entity: carries {@code @JmixEntity} only (NO
 * {@code @Entity} / {@code @Store} / {@code @Table}) — this row is never
 * persisted. The {@code id} field exists solely to satisfy the Jmix
 * metamodel id contract for non-persistent DTOs.</p>
 *
 * <p><b>Only {@code requiresRestart()} carries a record-compat accessor;</b>
 * all other call sites use JavaBeans-form {@code getX()} to avoid Jmix
 * metamodel duplicate-property scans.</p>
 */
@JmixEntity(name = "ai_AiKnobRow")
public class AiKnobRow {

    @JmixId
    @JmixGeneratedValue
    private UUID id;

    private String key;
    private String resolvedValue;
    private String displayMessageKey;
    private boolean requiresRestart;

    public AiKnobRow() {
    }

    /**
     * Convenience all-args constructor mirroring the previous record signature
     * for scanner call sites that pre-date the id field.
     */
    public AiKnobRow(String key, String resolvedValue, String displayMessageKey, boolean requiresRestart) {
        this.key = key;
        this.resolvedValue = resolvedValue;
        this.displayMessageKey = displayMessageKey;
        this.requiresRestart = requiresRestart;
    }

    /**
     * Full constructor including the synthetic id (for tests / cloning paths
     * that want a deterministic id).
     */
    public AiKnobRow(UUID id, String key, String resolvedValue, String displayMessageKey, boolean requiresRestart) {
        this.id = id;
        this.key = key;
        this.resolvedValue = resolvedValue;
        this.displayMessageKey = displayMessageKey;
        this.requiresRestart = requiresRestart;
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

    public String getResolvedValue() {
        return resolvedValue;
    }

    public void setResolvedValue(String resolvedValue) {
        this.resolvedValue = resolvedValue;
    }

    public String getDisplayMessageKey() {
        return displayMessageKey;
    }

    public void setDisplayMessageKey(String displayMessageKey) {
        this.displayMessageKey = displayMessageKey;
    }

    public boolean isRequiresRestart() {
        return requiresRestart;
    }

    public void setRequiresRestart(boolean requiresRestart) {
        this.requiresRestart = requiresRestart;
    }

    /**
     * Record-compat boolean predicate accessor — retained so existing
     * {@code @Supply} renderer call sites that read {@code row.requiresRestart()}
     * continue to compile without churn. All OTHER property accessors are
     * JavaBeans-form ({@link #getKey()}, {@link #getResolvedValue()},
     * {@link #getDisplayMessageKey()}) to avoid Jmix metamodel duplicate-property
     * registration warnings.
     */
    public boolean requiresRestart() {
        return requiresRestart;
    }
}
