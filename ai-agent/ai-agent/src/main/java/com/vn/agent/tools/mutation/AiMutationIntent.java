package com.vn.agent.tools.mutation;

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
 * Concurrency-safe idempotency reservation/replay row for LLM-mediated mutation
 * tools.
 *
 * <p>Phase 11 contract (CONTEXT.md D-02):
 * <ul>
 *     <li>Composite unique index on
 *         {@code (TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME)} — same key from
 *         two users is two distinct intents (no cross-user information leak).</li>
 *     <li>The row stores {@link #requestHash} so replay can reject collisions
 *         where the call shape differs without storing the full result JSON.</li>
 *     <li>{@link AiMutationIntentStatus#PENDING} is reserved <em>before</em>
 *         the host {@code DataManager.save} so the unique index serializes
 *         concurrent duplicate writes at the database boundary.</li>
 *     <li>On replay {@link AiMutationIntentStatus#COMMITTED} returns
 *         {@code outcome=IDEMPOTENT_REPLAY} with a freshly resolved
 *         {@code instanceName} (not the stored caption) — Phase 11 specifics.</li>
 *     <li>{@link AiMutationIntentStatus#COMMIT_UNKNOWN} parks rows whose host
 *         save succeeded but post-save finalization failed; never reclaimable.</li>
 *     <li>TTL via {@code expiresAt} — hourly cleanup deletes only
 *         {@code COMMITTED} / {@code FAILED} rows.</li>
 * </ul>
 *
 * <p>Persisted in the {@code agentstore} datasource alongside other AI-* entities.
 * Excluded from the LLM-visible surface via {@code AiInternalEntityNames}.
 */
@Store(name = "agentstore")
@JmixEntity
@Entity(name = "aiMutation_AiMutationIntent")
@Table(name = "AI_MUTATION_INTENT", indexes = {
        @Index(name = "IDX_AI_MUT_INTENT_DEDUP",
                columnList = "TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME",
                unique = true),
        @Index(name = "IDX_AI_MUT_INTENT_EXPIRES_AT", columnList = "EXPIRES_AT"),
        @Index(name = "IDX_AI_MUT_INTENT_STATUS", columnList = "STATUS_")
})
public class AiMutationIntent {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    // BLOCKER-02 (mirrors AiExposureRule): uniqueness comes from the composite
    // @Index(unique=true) on (TOOL_NAME, IDEMPOTENCY_KEY, USER_USERNAME) and the
    // matching Liquibase IDX_AI_MUT_INTENT_DEDUP. Column-level unique=true is
    // intentionally NOT used.
    @InstanceName
    @NotNull
    @Column(name = "TOOL_NAME", nullable = false, length = 64)
    private String toolName;

    @NotNull
    @Column(name = "IDEMPOTENCY_KEY", nullable = false, length = 64)
    private String idempotencyKey;

    @NotNull
    @Column(name = "USER_USERNAME", nullable = false, length = 255)
    private String userUsername;

    @Column(name = "CONVERSATION_ID")
    private UUID conversationId;

    @NotNull
    @Column(name = "REQUEST_HASH", nullable = false, length = 64)
    private String requestHash;

    @NotNull
    @Column(name = "STATUS_", nullable = false, length = 32)
    private String status = AiMutationIntentStatus.PENDING.getId();

    @Column(name = "RESULT_ENTITY_ID")
    private UUID resultEntityId;

    @Column(name = "RESULT_ENTITY_NAME", length = 255)
    private String resultEntityName;

    @Column(name = "ERROR_CODE", length = 64)
    private String errorCode;

    @Column(name = "COMMITTED_AT")
    private OffsetDateTime committedAt;

    @NotNull
    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @NotNull
    @Column(name = "EXPIRES_AT", nullable = false)
    private OffsetDateTime expiresAt;

    public AiMutationIntentStatus getStatus() {
        return status == null ? null : AiMutationIntentStatus.fromId(status);
    }

    public void setStatus(AiMutationIntentStatus status) {
        this.status = status == null ? null : status.getId();
    }

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

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getUserUsername() {
        return userUsername;
    }

    public void setUserUsername(String userUsername) {
        this.userUsername = userUsername;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public UUID getResultEntityId() {
        return resultEntityId;
    }

    public void setResultEntityId(UUID resultEntityId) {
        this.resultEntityId = resultEntityId;
    }

    public String getResultEntityName() {
        return resultEntityName;
    }

    public void setResultEntityName(String resultEntityName) {
        this.resultEntityName = resultEntityName;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public OffsetDateTime getCommittedAt() {
        return committedAt;
    }

    public void setCommittedAt(OffsetDateTime committedAt) {
        this.committedAt = committedAt;
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
}
