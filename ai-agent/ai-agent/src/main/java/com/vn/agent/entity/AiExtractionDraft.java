package com.vn.agent.entity;

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
 * Persisted draft produced by an intent extraction turn before the user opens
 * and saves the target entity form.
 *
 * <p>User-facing paths load and save this entity through secured DataManager so
 * row-level ownership policy applies. System cleanup is the only path expected
 * to bypass security, and that happens in {@code AiExtractionDraftCleanupJob}.
 *
 * <p>Excluded from the LLM-visible surface via {@code AiAgentToolsProperties} hidden-entities.
 */
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "ai_AiExtractionDraft")
@Table(name = "AI_EXTRACTION_DRAFT", indexes = {
        @Index(name = "IDX_AI_EXTRACTION_DRAFT__USER_USERNAME", columnList = "USER_USERNAME"),
        @Index(name = "IDX_AI_EXTRACTION_DRAFT__EXPIRES_AT", columnList = "EXPIRES_AT"),
        @Index(name = "IDX_AI_EXTRACTION_DRAFT__SOURCE_CONVERSATION", columnList = "SOURCE_CONVERSATION_ID")
})
public class AiExtractionDraft {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @NotNull
    @Column(name = "INSTANCE_NAME", nullable = false, length = 512)
    private String instanceName;

    @NotNull
    @Column(name = "USER_USERNAME", nullable = false, length = 255)
    private String userUsername;

    @NotNull
    @Column(name = "TARGET_ENTITY_NAME", nullable = false, length = 255)
    private String targetEntityName;

    @NotNull
    @Column(name = "INTENT_ID", nullable = false, length = 255)
    private String intentId;

    @NotNull
    @Column(name = "PAYLOAD_JSON", nullable = false, columnDefinition = "text")
    private String payloadJson;

    @NotNull
    @Column(name = "SOURCE_CONVERSATION_ID", nullable = false)
    private UUID sourceConversationId;

    @Column(name = "SOURCE_TASK_FILE_ID")
    private UUID sourceTaskFileId;

    @NotNull
    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @NotNull
    @Column(name = "EXPIRES_AT", nullable = false)
    private OffsetDateTime expiresAt;

    @NotNull
    @Column(name = "CONFIRMED", nullable = false)
    private Boolean confirmed = false;

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

    public String getInstanceName() {
        return instanceName;
    }

    public void setInstanceName(String instanceName) {
        this.instanceName = instanceName;
    }

    public String getUserUsername() {
        return userUsername;
    }

    public void setUserUsername(String userUsername) {
        this.userUsername = userUsername;
    }

    public String getTargetEntityName() {
        return targetEntityName;
    }

    public void setTargetEntityName(String targetEntityName) {
        this.targetEntityName = targetEntityName;
    }

    public String getIntentId() {
        return intentId;
    }

    public void setIntentId(String intentId) {
        this.intentId = intentId;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public UUID getSourceConversationId() {
        return sourceConversationId;
    }

    public void setSourceConversationId(UUID sourceConversationId) {
        this.sourceConversationId = sourceConversationId;
    }

    public UUID getSourceTaskFileId() {
        return sourceTaskFileId;
    }

    public void setSourceTaskFileId(UUID sourceTaskFileId) {
        this.sourceTaskFileId = sourceTaskFileId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Boolean getConfirmed() {
        return confirmed;
    }

    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }
}
