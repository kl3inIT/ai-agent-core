package com.vn.agent.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

@JmixEntity
@Entity(name = "ai_AiKnowledgeDocument")
@Table(name = "AI_AGENT_KNOWLEDGE_DOCUMENT", indexes = {
        @Index(name = "IDX_AI_AGENT_KNOWLEDGE_DOCUMENT__ON_STATUS", columnList = "STATUS"),
        @Index(name = "IDX_AI_AGENT_KNOWLEDGE_DOCUMENT__ON_CREATED_BY", columnList = "CREATED_BY")
})
public class AiKnowledgeDocument {

    @Id
    @Column(name = "ID")
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @NotNull
    @Column(name = "FILE_NAME", nullable = false, length = 255)
    private String fileName;

    @Column(name = "MIME_TYPE", length = 128)
    private String mimeType;

    @Column(name = "SIZE_BYTES")
    private Long sizeBytes;

    @NotNull
    @Column(name = "STATUS", nullable = false, length = 16)
    private String status;

    @Column(name = "ERROR_MESSAGE", length = 1024)
    private String errorMessage;

    @Lob
    @Column(name = "ALLOWED_ROLES_JSON")
    private String allowedRolesJson;

    @Column(name = "CREATED_BY", length = 255)
    private String createdBy;

    @Column(name = "CREATED_DATE")
    private OffsetDateTime createdDate;

    @Column(name = "LAST_MODIFIED_BY", length = 255)
    private String lastModifiedBy;

    @Column(name = "LAST_MODIFIED_DATE")
    private OffsetDateTime lastModifiedDate;

    @Column(name = "INGESTED_AT")
    private OffsetDateTime ingestedAt;

    public AiKnowledgeDocumentStatus getStatus() { return status == null ? null : AiKnowledgeDocumentStatus.fromId(status); }
    public void setStatus(AiKnowledgeDocumentStatus status) { this.status = status == null ? null : status.getId(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getAllowedRolesJson() { return allowedRolesJson; }
    public void setAllowedRolesJson(String allowedRolesJson) { this.allowedRolesJson = allowedRolesJson; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedDate() { return createdDate; }
    public void setCreatedDate(OffsetDateTime createdDate) { this.createdDate = createdDate; }
    public String getLastModifiedBy() { return lastModifiedBy; }
    public void setLastModifiedBy(String lastModifiedBy) { this.lastModifiedBy = lastModifiedBy; }
    public OffsetDateTime getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(OffsetDateTime lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
    public OffsetDateTime getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(OffsetDateTime ingestedAt) { this.ingestedAt = ingestedAt; }
}
