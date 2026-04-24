package com.vn.agent.entity;

import io.jmix.core.DeletePolicy;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDelete;
import io.jmix.core.metamodel.annotation.Composition;
import io.jmix.core.metamodel.annotation.DependsOnProperties;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Store(name = "agentstore")
@JmixEntity
@Entity(name = "ai_AiAuditEvent")
@Table(name = "AI_AGENT_AUDIT_EVENT", indexes = {
        @Index(name = "IDX_AI_AGENT_AUDIT_EVENT__ON_PARENT", columnList = "PARENT_ID"),
        @Index(name = "IDX_AI_AGENT_AUDIT_EVENT__ON_CONVERSATION", columnList = "CONVERSATION_ID"),
        @Index(name = "IDX_AI_AGENT_AUDIT_EVENT__ON_STARTED_AT", columnList = "STARTED_AT"),
        @Index(name = "IDX_AI_AGENT_AUDIT_EVENT__ON_RUN_ID", columnList = "RUN_ID")
})
public class AiAuditEvent {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PARENT_ID")
    private AiAuditEvent parent;

    @Composition
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AiAuditEvent> children;

    @ManyToOne(fetch = FetchType.LAZY)
    @OnDelete(DeletePolicy.CASCADE)
    @JoinColumn(name = "CONVERSATION_ID")
    private AiConversation conversation;

    @Column(name = "USER_USERNAME", length = 255)
    private String userUsername;

    @Column(name = "RUN_ID")
    private UUID runId;

    @NotNull
    @Column(name = "KIND", nullable = false, length = 16)
    private String kind;

    @Column(name = "EVENT_NAME", length = 128)
    private String eventName;

    @Lob
    @Column(name = "ARGUMENTS_JSON")
    private String argumentsJson;

    @Lob
    @Column(name = "RESULT_SUMMARY")
    private String resultSummary;

    @Lob
    @Column(name = "QUERY_TEXT")
    private String queryText;

    @Column(name = "TOP_K")
    private Integer topK;

    @Column(name = "HIT_COUNT")
    private Integer hitCount;

    @Column(name = "TOP_SCORE")
    private Double topScore;

    @Lob
    @Column(name = "FILTERS_JSON")
    private String filtersJson;

    @Column(name = "OUTCOME", length = 16)
    private String outcome;

    @Column(name = "DENIAL_REASON", length = 512)
    private String denialReason;

    @Column(name = "ERROR_CLASS", length = 255)
    private String errorClass;

    @Column(name = "PROMPT_HASH", length = 64)
    private String promptHash;

    @Column(name = "LATENCY_MS")
    private Long latencyMs;

    @NotNull
    @Column(name = "STARTED_AT", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "FINISHED_AT")
    private OffsetDateTime finishedAt;

    @InstanceName
    @DependsOnProperties({"kind", "eventName", "outcome"})
    public String getDisplayName() {
        String name = eventName == null ? "-" : eventName;
        String oc = outcome == null ? "..." : outcome;
        return kind + " / " + name + " / " + oc;
    }

    /**
     * Enum-flavoured view of the OUTCOME column. Nullable — CHAT root rows
     * have no outcome until the run finishes (D-01).
     */
    public AiToolCallOutcome getOutcome() {
        return outcome == null ? null : AiToolCallOutcome.fromId(outcome);
    }

    public void setOutcome(AiToolCallOutcome outcome) {
        this.outcome = outcome == null ? null : outcome.getId();
    }

    /** Raw String access to OUTCOME for writers that pass literal kind values. */
    public String getOutcomeRaw() { return outcome; }
    public void setOutcomeRaw(String outcome) { this.outcome = outcome; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public AiAuditEvent getParent() { return parent; }
    public void setParent(AiAuditEvent parent) { this.parent = parent; }
    public List<AiAuditEvent> getChildren() { return children; }
    public void setChildren(List<AiAuditEvent> children) { this.children = children; }
    public AiConversation getConversation() { return conversation; }
    public void setConversation(AiConversation conversation) { this.conversation = conversation; }
    public String getUserUsername() { return userUsername; }
    public void setUserUsername(String userUsername) { this.userUsername = userUsername; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }
    public String getArgumentsJson() { return argumentsJson; }
    public void setArgumentsJson(String argumentsJson) { this.argumentsJson = argumentsJson; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Integer getHitCount() { return hitCount; }
    public void setHitCount(Integer hitCount) { this.hitCount = hitCount; }
    public Double getTopScore() { return topScore; }
    public void setTopScore(Double topScore) { this.topScore = topScore; }
    public String getFiltersJson() { return filtersJson; }
    public void setFiltersJson(String filtersJson) { this.filtersJson = filtersJson; }
    public String getDenialReason() { return denialReason; }
    public void setDenialReason(String denialReason) { this.denialReason = denialReason; }
    public String getErrorClass() { return errorClass; }
    public void setErrorClass(String errorClass) { this.errorClass = errorClass; }
    public String getPromptHash() { return promptHash; }
    public void setPromptHash(String promptHash) { this.promptHash = promptHash; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
