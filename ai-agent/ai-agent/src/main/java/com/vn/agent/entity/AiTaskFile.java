package com.vn.agent.entity;

import io.jmix.core.DeletePolicy;
import io.jmix.core.FileRef;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDelete;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.PropertyDatatype;
import io.jmix.core.metamodel.annotation.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Task-scoped file uploaded by a user during a chat turn.
 *
 * <p>Phase 13.1 contract (SCHEMA-01):
 * <ul>
 *     <li>Persisted in {@code agentstore} alongside other AI-* entities.</li>
 *     <li>{@link #conversation} is a NOT NULL FK to {@code AI_AGENT_CONVERSATION}
 *         with {@link DeletePolicy#CASCADE} - deleting a conversation removes
 *         all its task files.</li>
 *     <li>{@link #storageRef} carries the {@link FileRef} blob pointer via
 *         {@link PropertyDatatype} {@code "fileRef"}; the column stays
 *         {@code varchar(1024)}.</li>
 *     <li>{@link #expiresAt} is the non-audit TTL timestamp; the cleanup job
 *         (Plan 13.1-02 / Phase 13 Plan 02) reaps rows where
 *         {@code expiresAt < now()}.</li>
 * </ul>
 *
 * <p>Phase 13.1 SCHEMA-01 dropped the prior message (AiMessage FK) and
 * injection-timestamp fields - the per-turn-all resolver (Plan 13.1-02) loads
 * every non-expired row for the conversation on every turn, so no pending-state
 * marker or message back-link is needed.
 *
 * <p>Excluded from the LLM-visible surface via {@code AiInternalEntityNames}.
 */
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "ai_AiTaskFile")
@Table(name = "AI_TASK_FILE", indexes = {
        @Index(name = "IDX_AI_TASK_FILE__ON_CONVERSATION", columnList = "CONVERSATION_ID"),
        @Index(name = "IDX_AI_TASK_FILE__EXPIRES_AT", columnList = "EXPIRES_AT")
})
public class AiTaskFile {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(DeletePolicy.CASCADE)
    @JoinColumn(name = "CONVERSATION_ID", nullable = false)
    private AiConversation conversation;

    @NotNull
    @Column(name = "USER_USERNAME", nullable = false, length = 255)
    private String userUsername;

    @InstanceName
    @NotNull
    @Column(name = "FILENAME", nullable = false, length = 1024)
    private String filename;

    @Column(name = "CONTENT_TYPE", length = 255)
    private String contentType;

    @Column(name = "SIZE_BYTES")
    private Long sizeBytes;

    @PropertyDatatype("fileRef")
    @Column(name = "STORAGE_REF", length = 1024)
    private FileRef storageRef;

    @CreatedBy
    @Column(name = "CREATED_BY")
    private String createdBy;

    @CreatedDate
    @Column(name = "CREATED_DATE", nullable = false)
    private OffsetDateTime createdDate;

    @LastModifiedBy
    @Column(name = "LAST_MODIFIED_BY")
    private String lastModifiedBy;

    @LastModifiedDate
    @Column(name = "LAST_MODIFIED_DATE")
    private OffsetDateTime lastModifiedDate;

    @NotNull
    @Column(name = "EXPIRES_AT", nullable = false)
    private OffsetDateTime expiresAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public AiConversation getConversation() {
        return conversation;
    }

    public void setConversation(AiConversation conversation) {
        this.conversation = conversation;
    }

    public String getUserUsername() {
        return userUsername;
    }

    public void setUserUsername(String userUsername) {
        this.userUsername = userUsername;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public FileRef getStorageRef() {
        return storageRef;
    }

    public void setStorageRef(FileRef storageRef) {
        this.storageRef = storageRef;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(OffsetDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public String getLastModifiedBy() {
        return lastModifiedBy;
    }

    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }

    public OffsetDateTime getLastModifiedDate() {
        return lastModifiedDate;
    }

    public void setLastModifiedDate(OffsetDateTime lastModifiedDate) {
        this.lastModifiedDate = lastModifiedDate;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
