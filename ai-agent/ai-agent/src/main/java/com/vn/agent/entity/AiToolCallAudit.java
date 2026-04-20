package com.vn.agent.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.DependsOnProperties;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

@JmixEntity
@Entity(name = "ai_AiToolCallAudit")
@Table(name = "AI_AGENT_TOOL_CALL_AUDIT", indexes = {
        @Index(name = "IDX_AI_AGENT_TOOL_CALL_AUDIT__ON_CONVERSATION", columnList = "CONVERSATION_ID"),
        @Index(name = "IDX_AI_AGENT_TOOL_CALL_AUDIT__ON_STARTED_AT", columnList = "STARTED_AT")
})
public class AiToolCallAudit {

    @Id
    @Column(name = "ID")
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CONVERSATION_ID")
    private AiConversation conversation;

    @Column(name = "USER_USERNAME", length = 255)
    private String userUsername;

    @NotNull
    @Column(name = "TOOL_NAME", nullable = false, length = 128)
    private String toolName;

    @Lob
    @Column(name = "ARGUMENTS_JSON")
    private String argumentsJson;

    @Lob
    @Column(name = "RESULT_SUMMARY")
    private String resultSummary;

    @NotNull
    @Column(name = "OUTCOME", nullable = false, length = 16)
    private String outcome;

    @Column(name = "DENIAL_REASON", length = 512)
    private String denialReason;

    @Column(name = "LATENCY_MS")
    private Long latencyMs;

    @NotNull
    @Column(name = "STARTED_AT", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "FINISHED_AT")
    private OffsetDateTime finishedAt;

    @Column(name = "RUN_ID")
    private UUID runId;

    @Column(name = "KIND", length = 8)
    private String kind;

    @Column(name = "PHASE", length = 8)
    private String phase;

    @Column(name = "PROMPT_HASH", length = 64)
    private String promptHash;

    @Column(name = "ERROR_CLASS", length = 255)
    private String errorClass;

    @InstanceName
    @DependsOnProperties({"toolName", "outcome"})
    public String getDisplayName() {
        return toolName + " / " + outcome;
    }

    public AiToolCallOutcome getOutcome() { return outcome == null ? null : AiToolCallOutcome.fromId(outcome); }
    public void setOutcome(AiToolCallOutcome outcome) { this.outcome = outcome == null ? null : outcome.getId(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public AiConversation getConversation() { return conversation; }
    public void setConversation(AiConversation conversation) { this.conversation = conversation; }
    public String getUserUsername() { return userUsername; }
    public void setUserUsername(String userUsername) { this.userUsername = userUsername; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArgumentsJson() { return argumentsJson; }
    public void setArgumentsJson(String argumentsJson) { this.argumentsJson = argumentsJson; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public String getDenialReason() { return denialReason; }
    public void setDenialReason(String denialReason) { this.denialReason = denialReason; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getPromptHash() { return promptHash; }
    public void setPromptHash(String promptHash) { this.promptHash = promptHash; }
    public String getErrorClass() { return errorClass; }
    public void setErrorClass(String errorClass) { this.errorClass = errorClass; }
}
