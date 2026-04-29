package com.vn.agent.audit;

import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.spi.AuditListener;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
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
 * caller's transaction. A tool method rolling back does NOT erase the tool-call audit row (AUD-02).
 *
 * <p>Phase 07.2 rewrite: tree-lite over {@link AiAuditEvent}. One mutable root CHAT row per chat
 * turn (INSERT via {@link #writeChatStart} + UPDATE in place via {@link #writeChatFinish}), plus
 * append-only TOOL and RETRIEVAL children under {@code parent_id = rootAuditId}.
 *
 * <p>Callers ({@code AuditAdvisor}, {@code ToolCallbackAuditDecorator}, RAG advisor) inject this
 * bean and invoke its methods through the Spring proxy.
 *
 * <p><b>Pitfall #1 (cascade re-save of children on root UPDATE).</b>
 * {@link AiAuditEvent} uses {@code @Composition @OneToMany(mappedBy="parent", cascade=ALL,
 * orphanRemoval=true)}. Loading the root with its {@code children} attached and re-saving it in
 * {@link #writeChatFinish} would cascade-update every child row (bumping their {@code VERSION}s)
 * and any child INSERTed concurrently after we loaded but before we saved would be orphan-removed.
 * The fetch plan in {@link #writeChatFinish} is explicit and OMITS {@code children} — only the
 * root row is dirtied.
 *
 * <p><b>Pitfall #2 (RunContext cleared too early).</b> {@link #writeChatFinish} runs in the
 * advisor's {@code finally} — {@code AuditAdvisor} clears
 * {@link com.vn.agent.orchestration.RunContext} AFTER this call returns, never before.
 *
 * <p><b>Pitfall #3 (proxy self-invocation).</b> This class NEVER calls its own public methods
 * from inside another public method. Doing so would bypass the proxy and silently dissolve
 * REQUIRES_NEW.
 *
 * <p>D-14 afterCommit fan-out: {@link #writeChatFinish}, {@link #writeToolCall}, and
 * {@link #writeRetrieval} register a {@link TransactionSynchronization} so
 * {@link AuditListenerDispatcher#dispatchEventAudited(UUID, String)} runs only after the
 * REQUIRES_NEW transaction commits — listeners observe rows that are already persisted.
 * {@link #writeChatStart} explicitly does NOT fan out (D-05): listeners only see completed roots.
 */
@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    /**
     * Unconstrained: audit-event writes are system infrastructure that must persist regardless
     * of caller authentication. End-user roles (e.g. AiAgentUserRole) intentionally do NOT grant
     * CREATE on ai_AiAuditEvent — the audit log is tamper-evident. Switching from DataManager
     * to UnconstrainedDataManager is the canonical Jmix pattern for system-internal persistence
     * (see /jmix-framework/jmix-context7 data-manager.html) and avoids the role-grant brittleness
     * of runWithSystem (which is itself policy-gated under jmix-security-data). See project memory
     * feedback_jmix_unconstrained_for_system_writes and 08-08-INVESTIGATION.md.
     */
    private final UnconstrainedDataManager dataManager;
    private final Metadata metadata;
    private final AuditListenerDispatcher dispatcher;

    public AuditWriter(UnconstrainedDataManager dataManager, Metadata metadata, AuditListenerDispatcher dispatcher) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.dispatcher = dispatcher;
    }

    /**
     * INSERT the root CHAT row for a chat turn. {@code eventName}, {@code outcome}, and
     * {@code finishedAt} remain {@code null} until {@link #writeChatFinish} closes the run.
     * Does NOT fan out to {@link AuditListener} beans (D-05 — listeners only see completed roots).
     *
     * @return the newly-allocated root audit id (to be stashed on
     *         {@link com.vn.agent.orchestration.RunContext})
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID writeChatStart(UUID runId, String userUsername, UUID conversationId, String promptHash) {
        AiAuditEvent row = metadata.create(AiAuditEvent.class);
        row.setRunId(runId);
        row.setKind(AuditKind.CHAT);
        row.setUserUsername(userUsername);
        row.setPromptHash(promptHash);
        if (conversationId != null) {
            AiConversation conv = dataManager.load(AiConversation.class).id(conversationId).optional().orElse(null);
            if (conv != null) {
                row.setConversation(conv);
            } else {
                log.warn("writeChatStart: conversation {} not found; row will have null FK (runId={})",
                        conversationId, runId);
            }
        }
        row.setStartedAt(OffsetDateTime.now());
        dataManager.save(row);
        // D-05: NO fan-out on start — listeners only see completed roots.
        return row.getId();
    }

    /**
     * UPDATE the root CHAT row in place with latency + outcome + errorClass, then fan out. The
     * fetch plan is CHILDREN-FREE (Pitfall #1): we never hydrate the {@code children} collection
     * so re-save cannot cascade-update or orphan-remove any TOOL/RETRIEVAL children that were
     * appended concurrently.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeChatFinish(UUID rootId, long latencyMs, String outcome, String errorClass) {
        AiAuditEvent root = dataManager.load(AiAuditEvent.class)
                .id(rootId)
                .fetchPlan(fp -> fp.add("kind")
                        .add("outcome")
                        .add("latencyMs")
                        .add("finishedAt")
                        .add("errorClass")
                        .add("startedAt"))   // CRITICAL: NEVER include "children" — Pitfall #1
                .optional()
                .orElse(null);
        if (root == null) {
            log.warn("writeChatFinish: root {} not found; lost", rootId);
            return;
        }
        root.setFinishedAt(OffsetDateTime.now());
        root.setLatencyMs(latencyMs);
        root.setOutcomeRaw(outcome);
        root.setErrorClass(errorClass);
        dataManager.save(root);

        registerAfterCommit(rootId, AuditKind.CHAT);   // D-05: CHAT becomes complete here -> fan out
    }

    /**
     * INSERT a TOOL child row under {@code parentId} (the run's root audit id), append-only
     * (D-01 — both {@code startedAt} and {@code finishedAt} are stamped at insert; no PRE/POST
     * pair). {@code parentId} may be {@code null} defensively for calls made outside an advisor
     * chain; the row is then an orphan but still persisted.
     *
     * @return the newly-allocated audit row id
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID writeToolCall(UUID parentId, UUID runId, String userUsername, UUID conversationId,
                              String toolName, String argumentsJson, String resultSummary, long latencyMs,
                              AiToolCallOutcome outcome, String denialReason, String errorClass) {
        AiAuditEvent row = metadata.create(AiAuditEvent.class);
        row.setRunId(runId);
        row.setKind(AuditKind.TOOL);
        row.setEventName(toolName);                        // no more <chat> sentinel — eventName = tool name
        row.setUserUsername(userUsername);
        row.setArgumentsJson(argumentsJson);
        row.setResultSummary(resultSummary);
        row.setLatencyMs(latencyMs);
        row.setOutcome(outcome != null ? outcome : AiToolCallOutcome.SUCCESS);
        row.setDenialReason(denialReason);
        row.setErrorClass(errorClass);
        if (conversationId != null) {
            AiConversation conv = dataManager.load(AiConversation.class).id(conversationId).optional().orElse(null);
            if (conv != null) {
                row.setConversation(conv);
            }
        }
        UUID effectiveParentId = parentId != null ? parentId : findChatRootId(runId);
        if (effectiveParentId != null) {
            AiAuditEvent parent = dataManager.load(AiAuditEvent.class)
                    .id(effectiveParentId)
                    .fetchPlan(fp -> fp.add("id"))   // no children fetch — Pitfall #1
                    .optional().orElse(null);
            if (parent != null) {
                row.setParent(parent);
            } else {
                log.warn("writeToolCall: parent {} not found; tool row will be orphan (runId={}, tool={})",
                        effectiveParentId, runId, toolName);
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        row.setStartedAt(now.minusNanos(latencyMs * 1_000_000L));   // D-01: children carry both timestamps at insert
        row.setFinishedAt(now);
        dataManager.save(row);
        final UUID auditId = row.getId();
        registerAfterCommit(auditId, AuditKind.TOOL);
        return auditId;
    }

    /**
     * INSERT a RETRIEVAL child row under {@code parentId} (the run's root audit id). Captures
     * {@code queryText}, {@code topK}, {@code hitCount}, {@code topScore}, and {@code filtersJson}
     * (Filter.Expression#toString only — T-07.2-03 forbids raw prompt forwarding).
     *
     * @return the newly-allocated audit row id
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID writeRetrieval(UUID parentId, UUID runId, String userUsername, UUID conversationId,
                               String queryText, Integer topK, Integer hitCount, Double topScore,
                               String filtersJson, String retrievalHitsJson,
                               long latencyMs, String outcome, String errorClass) {
        AiAuditEvent row = metadata.create(AiAuditEvent.class);
        row.setRunId(runId);
        row.setKind(AuditKind.RETRIEVAL);
        row.setUserUsername(userUsername);
        row.setQueryText(queryText);
        row.setTopK(topK);
        row.setHitCount(hitCount);
        row.setTopScore(topScore);
        row.setFiltersJson(filtersJson);
        row.setRetrievalHitsJson(retrievalHitsJson);
        row.setLatencyMs(latencyMs);
        row.setOutcomeRaw(outcome);
        row.setErrorClass(errorClass);
        if (conversationId != null) {
            AiConversation conv = dataManager.load(AiConversation.class).id(conversationId).optional().orElse(null);
            if (conv != null) {
                row.setConversation(conv);
            }
        }
        UUID effectiveParentId = parentId != null ? parentId : findChatRootId(runId);
        if (effectiveParentId != null) {
            AiAuditEvent parent = dataManager.load(AiAuditEvent.class)
                    .id(effectiveParentId)
                    .fetchPlan(fp -> fp.add("id"))
                    .optional().orElse(null);
            if (parent != null) {
                row.setParent(parent);
            } else {
                log.warn("writeRetrieval: parent {} not found; retrieval row will be orphan (runId={})",
                        effectiveParentId, runId);
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        row.setStartedAt(now.minusNanos(latencyMs * 1_000_000L));
        row.setFinishedAt(now);
        dataManager.save(row);
        final UUID auditId = row.getId();
        registerAfterCommit(auditId, AuditKind.RETRIEVAL);
        return auditId;
    }

    UUID findChatRootId(UUID runId) {
        if (runId == null) {
            return null;
        }
        return dataManager.loadValue(
                        "select e.id from ai_AiAuditEvent e "
                                + "where e.runId = :runId and e.kind = :kind and e.parent is null",
                        UUID.class)
                .store("agentstore")
                .parameter("runId", runId)
                .parameter("kind", AuditKind.CHAT)
                .optional()
                .orElse(null);
    }

    /**
     * Register a Spring afterCommit synchronization that fans the event out to
     * {@link AuditListener} beans once the REQUIRES_NEW transaction commits. If no
     * synchronization is active (shouldn't happen — REQUIRES_NEW always has one), fire inline
     * as a defensive fallback.
     */
    private void registerAfterCommit(UUID auditId, String kind) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new AuditAfterCommitSynchronization(dispatcher, auditId, kind));
        } else {
            log.warn("No active synchronization for auditId={} kind={}; firing inline", auditId, kind);
            dispatcher.dispatchEventAudited(auditId, kind);
        }
    }
}

final class AuditAfterCommitSynchronization implements TransactionSynchronization {

    private final AuditListenerDispatcher dispatcher;
    private final UUID auditId;
    private final String kind;

    AuditAfterCommitSynchronization(AuditListenerDispatcher dispatcher, UUID auditId, String kind) {
        this.dispatcher = dispatcher;
        this.auditId = auditId;
        this.kind = kind;
    }

    @Override
    public void afterCommit() {
        dispatcher.dispatchEventAudited(auditId, kind);
    }
}
