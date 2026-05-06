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
 * <p>Phase 13 contract (CONTEXT.md D-03):
 * <ul>
 *     <li>Persisted in {@code agentstore} alongside other AI-* entities.</li>
 *     <li>{@link #conversation} is a NOT NULL FK to {@code AI_AGENT_CONVERSATION}
 *         with {@link DeletePolicy#CASCADE} — deleting a conversation removes
 *         all its task files.</li>
 *     <li>{@link #message} is an OPTIONAL FK to {@code AI_AGENT_MESSAGE} with
 *         {@link DeletePolicy#UNLINK} (ON DELETE SET NULL at the DB level via
 *         the Liquibase {@code addForeignKeyConstraint onDelete="SET NULL"}
 *         changeSet). The {@code message} field is a soft display-link populated
 *         by the two-phase write in Plan 04 — it is NEVER read by the resolver
 *         predicate (REVIEWS HIGH-1).</li>
 *     <li>{@link #injectedAt} is the AUTHORITATIVE pending-state marker
 *         (REVIEWS HIGH-1). The resolver predicate is
 *         {@code injectedAt IS NULL AND expiresAt > :now}; {@code markInjected}
 *         sets this once per row in Plan 02. Do NOT confuse with
 *         {@link #message} — that is a separate optional UI-display link, not
 *         authoritative pending state.</li>
 *     <li>{@link #storageRef} carries the {@link FileRef} blob pointer via
 *         {@link PropertyDatatype} {@code "fileRef"}; the column stays
 *         {@code varchar(1024)}.</li>
 *     <li>{@link #expiresAt} is the non-audit TTL timestamp; the cleanup job
 *         (Plan 02) reaps rows where {@code expiresAt < now()}.</li>
 * </ul>
 *
 * <p>Excluded from the LLM-visible surface via {@code AiInternalEntityNames}.
 */
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "ai_AiTaskFile")
@Table(name = "AI_TASK_FILE", indexes = {
        @Index(name = "IDX_AI_TASK_FILE__ON_CONVERSATION", columnList = "CONVERSATION_ID"),
        @Index(name = "IDX_AI_TASK_FILE__ON_MESSAGE", columnList = "MESSAGE_ID"),
        @Index(name = "IDX_AI_TASK_FILE__EXPIRES_AT", columnList = "EXPIRES_AT"),
        @Index(name = "IDX_AI_TASK_FILE__INJECTED_AT", columnList = "INJECTED_AT")
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

    /**
     * Optional UI-display link to the AiMessage row that consumed this task file.
     * Populated by the two-phase write in Plan 04 after the user message is
     * persisted; NEVER read by the resolver predicate (REVIEWS HIGH-1).
     * ON DELETE SET NULL via the Liquibase {@code addForeignKeyConstraint}
     * changeSet (REVIEWS HIGH-2).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(DeletePolicy.UNLINK)
    @JoinColumn(name = "MESSAGE_ID")
    private AiMessage message;

    /**
     * Authoritative pending-state marker (REVIEWS HIGH-1). When {@code null} the
     * row is pending injection and the resolver returns it; once set by
     * {@code markInjected} (Plan 02) the row is excluded from the resolver
     * predicate {@code injectedAt IS NULL AND expiresAt > :now}.
     * NEVER overwritten by chat-memory persistence.
     */
    @Column(name = "INJECTED_AT")
    private OffsetDateTime injectedAt;

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

    public AiMessage getMessage() {
        return message;
    }

    public void setMessage(AiMessage message) {
        this.message = message;
    }

    public OffsetDateTime getInjectedAt() {
        return injectedAt;
    }

    public void setInjectedAt(OffsetDateTime injectedAt) {
        this.injectedAt = injectedAt;
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
