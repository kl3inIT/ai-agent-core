package com.vn.agent.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

@JmixEntity
@Entity(name = "ai_AiParameters")
@Table(name = "AI_AGENT_PARAMETERS", indexes = {
        @Index(name = "IDX_AI_AGENT_PARAMETERS__ON_PROFILE_NAME", columnList = "PROFILE_NAME", unique = true)
})
public class AiParameters {

    @Id
    @Column(name = "ID")
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @NotNull
    @Column(name = "PROFILE_NAME", nullable = false, length = 128)
    private String profileName;

    @NotNull
    @Column(name = "ACTIVE_", nullable = false)
    private Boolean active = Boolean.FALSE;

    @Lob
    @Column(name = "BODY_YAML")
    private String bodyYaml;

    @Column(name = "CREATED_BY", length = 255)
    private String createdBy;

    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @Column(name = "LAST_MODIFIED_BY", length = 255)
    private String lastModifiedBy;

    @Column(name = "LAST_MODIFIED_DATE")
    private OffsetDateTime lastModifiedDate;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getProfileName() { return profileName; }
    public void setProfileName(String profileName) { this.profileName = profileName; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public String getBodyYaml() { return bodyYaml; }
    public void setBodyYaml(String bodyYaml) { this.bodyYaml = bodyYaml; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedDate() { return createdDate; }
    public void setCreatedDate(OffsetDateTime createdDate) { this.createdDate = createdDate; }
    public String getLastModifiedBy() { return lastModifiedBy; }
    public void setLastModifiedBy(String lastModifiedBy) { this.lastModifiedBy = lastModifiedBy; }
    public OffsetDateTime getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(OffsetDateTime lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
