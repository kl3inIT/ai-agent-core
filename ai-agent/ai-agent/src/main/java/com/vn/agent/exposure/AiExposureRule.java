package com.vn.agent.exposure;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Admin-governed exposure rule that narrows the LLM-visible surface BELOW the
 * current user's Jmix permissions.
 *
 * <p>v1.1.0 contract:
 * <ul>
 *     <li>Entity-level only — there is no {@code attributePath} field.
 *         Attribute-level rules are deferred to a later milestone (CONTEXT.md
 *         decision 2026-04-27).</li>
 *     <li>{@link AiExposureRuleMode#EXCLUDE} is the only mode. There is no
 *         ALLOW value. Composition is {@code userVisible AND NOT excluded}.</li>
 *     <li>One rule per entity — enforced by a unique constraint on
 *         {@code ENTITY_NAME}.</li>
 *     <li>Persisted in the {@code agentstore} datasource alongside other AI-*
 *         entities.</li>
 * </ul>
 */
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "aiExposure_AiExposureRule")
@Table(name = "AI_EXPOSURE_RULE", indexes = {
        @Index(name = "IDX_AI_EXPOSURE_RULE_ENTITY_NAME", columnList = "ENTITY_NAME", unique = true),
        @Index(name = "IDX_AI_EXPOSURE_RULE_ENABLED", columnList = "ENABLED")
})
public class AiExposureRule {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @NotNull
    // BLOCKER-02: Uniqueness is enforced by the @Index(unique=true) declaration above
    // (matching Liquibase IDX_AI_EXPOSURE_RULE_ENTITY_NAME). The column-level unique=true
    // flag was removed because some JPA providers emit it as a SEPARATE unique constraint
    // with a generated name, clashing with the Liquibase-managed index.
    @Column(name = "ENTITY_NAME", nullable = false, length = 255)
    private String entityName;

    @NotNull
    @Column(name = "MODE", nullable = false, length = 16)
    private String mode = AiExposureRuleMode.EXCLUDE.getId();

    @NotNull
    @Column(name = "ENABLED", nullable = false)
    private Boolean enabled = true;

    @Column(name = "CREATED_BY")
    private String createdBy;

    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @Column(name = "LAST_MODIFIED_BY")
    private String lastModifiedBy;

    @Column(name = "LAST_MODIFIED_DATE")
    private OffsetDateTime lastModifiedDate;

    public AiExposureRuleMode getMode() {
        return mode == null ? null : AiExposureRuleMode.fromId(mode);
    }

    public void setMode(AiExposureRuleMode mode) {
        this.mode = mode == null ? null : mode.getId();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getEntityName() { return entityName; }
    public void setEntityName(String entityName) { this.entityName = entityName; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedDate() { return createdDate; }
    public void setCreatedDate(OffsetDateTime createdDate) { this.createdDate = createdDate; }

    public String getLastModifiedBy() { return lastModifiedBy; }
    public void setLastModifiedBy(String lastModifiedBy) { this.lastModifiedBy = lastModifiedBy; }

    public OffsetDateTime getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(OffsetDateTime lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
