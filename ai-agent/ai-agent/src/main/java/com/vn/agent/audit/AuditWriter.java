package com.vn.agent.audit;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiToolCallAudit;
import com.vn.agent.entity.AiToolCallOutcome;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Sole {@link Transactional} surface in the audit pipeline (D-11). Each public method runs in a
 * fresh transaction ({@link Propagation#REQUIRES_NEW}) so audit rows commit independently of the
 * caller's transaction — in particular, a tool method rolling back does NOT erase the tool-call
 * audit row (AUD-02).
 *
 * <p>Callers ({@code AuditAdvisor}, {@code ToolCallbackAuditDecorator}) inject this bean and
 * invoke its methods through the Spring proxy. Per <b>Pitfall #3 (proxy self-invocation)</b>,
 * this class NEVER calls its own public methods from inside another public method — doing so
 * would bypass the proxy and silently dissolve REQUIRES_NEW.
 *
 * <p>D-14 afterCommit fan-out: {@link #writeToolCall} registers a
 * {@link TransactionSynchronization} so {@link AuditListenerFanOut#fireToolCallAudited(UUID)}
 * runs only after the REQUIRES_NEW transaction commits — listeners observe rows that are
 * already durable.
 */
@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    /** Sentinel tool name for chat-level rows (the entity's {@code toolName} column is @NotNull). */
    public static final String CHAT_TOOL_NAME_SENTINEL = "<chat>";

    private final DataManager dataManager;
    private final Metadata metadata;
    private final AuditListenerFanOut fanOut;

    public AuditWriter(DataManager dataManager, Metadata metadata, AuditListenerFanOut fanOut) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.fanOut = fanOut;
    }

    /**
     * Writes the chat-level PRE row and returns the newly-allocated audit row id.
     * Sentinel values satisfy entity {@code @NotNull} constraints (toolName, outcome).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID writeChatPre(UUID runId, String userUsername, UUID conversationId, String promptHash) {
        AiToolCallAudit row = metadata.create(AiToolCallAudit.class);
        row.setRunId(runId);
        row.setKind("CHAT");
        row.setPhase("PRE");
        row.setPromptHash(promptHash);
        row.setUserUsername(userUsername);
        if (conversationId != null) {
            AiConversation conv = dataManager.load(AiConversation.class).id(conversationId).optional().orElse(null);
            if (conv != null) {
                row.setConversation(conv);
            }
        }
        row.setToolName(CHAT_TOOL_NAME_SENTINEL);      // sentinel — entity @NotNull
        row.setOutcome(AiToolCallOutcome.SUCCESS);     // sentinel — entity @NotNull; POST re-records real outcome
        row.setStartedAt(OffsetDateTime.now());
        dataManager.save(row);
        return row.getId();
    }

    /**
     * Writes the chat-level POST row after the chat call returns (or throws).
     * Blocker 2: re-resolves conversation for correlation via {@code a.conversation.id} JPQL paths.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeChatPost(UUID runId, String userUsername, UUID conversationId,
                              long latencyMs, String errorClass) {
        AiToolCallAudit row = metadata.create(AiToolCallAudit.class);
        row.setRunId(runId);
        row.setKind("CHAT");
        row.setPhase("POST");
        row.setLatencyMs(latencyMs);
        row.setErrorClass(errorClass);
        row.setUserUsername(userUsername);
        if (conversationId != null) {
            AiConversation conv = dataManager.load(AiConversation.class).id(conversationId).optional().orElse(null);
            if (conv != null) {
                row.setConversation(conv);
            }
        }
        row.setToolName(CHAT_TOOL_NAME_SENTINEL);
        row.setOutcome(errorClass == null ? AiToolCallOutcome.SUCCESS : AiToolCallOutcome.ERROR);
        OffsetDateTime now = OffsetDateTime.now();
        row.setStartedAt(now);
        row.setFinishedAt(now);
        dataManager.save(row);
        // No fan-out for chat-level rows — SPI-06 scope is tool calls only.
    }

    /**
     * Writes a per-tool-call audit row and registers an afterCommit synchronization to fan out
     * to {@link AuditListener} beans (D-14). The REQUIRES_NEW boundary means this row survives
     * delegate-side rollback (AUD-02).
     *
     * @param phase {@code "PRE"} (written before tool invocation) or {@code "POST"} (after).
     * @return the newly-allocated audit row id
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID writeToolCall(UUID runId, String userUsername, UUID conversationId, String toolName,
                              String argumentsJson, String resultSummary, long latencyMs,
                              AiToolCallOutcome outcome, String denialReason, String errorClass,
                              String phase) {
        AiToolCallAudit row = metadata.create(AiToolCallAudit.class);
        row.setRunId(runId);
        row.setKind("TOOL");
        row.setPhase(phase);
        row.setUserUsername(userUsername);
        row.setToolName(toolName);
        row.setArgumentsJson(argumentsJson);
        row.setResultSummary(resultSummary);
        row.setLatencyMs(latencyMs);
        row.setOutcome(outcome != null ? outcome : AiToolCallOutcome.SUCCESS);
        row.setDenialReason(denialReason);
        row.setErrorClass(errorClass);
        row.setStartedAt(OffsetDateTime.now());
        if ("POST".equals(phase)) {
            row.setFinishedAt(OffsetDateTime.now());
        }
        if (conversationId != null) {
            AiConversation conv = dataManager.load(AiConversation.class).id(conversationId).optional().orElse(null);
            if (conv != null) {
                row.setConversation(conv);
            }
        }
        dataManager.save(row);
        final UUID auditId = row.getId();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fanOut.fireToolCallAudited(auditId);
                }
            });
        } else {
            // Defensive — REQUIRES_NEW should always have an active synchronization here.
            log.warn("No active synchronization for runId={} auditId={}; firing inline", runId, auditId);
            fanOut.fireToolCallAudited(auditId);
        }
        return auditId;
    }
}
