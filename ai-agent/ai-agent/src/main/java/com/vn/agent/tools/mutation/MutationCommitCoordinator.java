package com.vn.agent.tools.mutation;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.tools.ToolResultFormatter;
import com.vn.agent.tools.ToolUserError;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.model.MetaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encapsulates reservation-result handling, commit-state transitions, replay-result
 * construction, and the non-throwing {@code safeWriteAudit} for {@code BuiltInMutationTools}.
 * Plan 11-07A introduced this collaborator; Plan 11-07B added related-write replay support;
 * Plan 11-07C is the final hardening pass and locks in the invariants below.
 *
 * <p><b>Plan 11-07C invariants (enforced and must remain green):</b>
 * <ul>
 *     <li>{@code auditWriter.writeToolCall} is only invoked from {@link #safeWriteAudit}
 *         in this class; {@code BuiltInMutationTools} contains zero direct calls. Verified
 *         by {@code MutationToolInvariantsTest}.</li>
 *     <li>{@code COMMIT_FAILED} captions must not say "database commit failed". Both
 *         locales render "Commit outcome unknown" because the host save may or may not
 *         have committed — see {@link MutationErrorTranslator#commitFailed}.</li>
 *     <li>{@link #safeWriteAudit} catches {@link RuntimeException}, never alters
 *         {@link MutationCommitState}, and logs the marker
 *         {@code AI_AGENT_MUTATION_AUDIT_WRITE_FAILED} with sanitized context only
 *         (tool name, outcome, runId, rootAuditId, exception class) — never
 *         {@code argumentsJson} or user-supplied attribute values.</li>
 *     <li>Replay loads use {@link io.jmix.core.FetchPlan#INSTANCE_NAME} and re-resolve
 *         {@code instanceName} live under current locale + security; if exposure or read
 *         denies loading, the row's {@code entityId} is preserved and {@code instanceName}
 *         is omitted/null. Full result JSON is never stored.</li>
 *     <li>{@link MutationIntentRepository#markCommitUnknown} re-reads the current dedup
 *         row inside its own {@code REQUIRES_NEW} transaction and never downgrades a
 *         {@code COMMITTED} row.</li>
 *     <li>{@link MutationCommitState#INTENT_COMMITTED} failures (post-success audit /
 *         result-build failures) never downgrade the idempotency row; the next exact
 *         retry returns {@code IDEMPOTENT_REPLAY}.</li>
 * </ul>
 *
 * <p><b>Commit-state contract:</b>
 * <ul>
 *     <li>{@link MutationCommitState#NO_HOST_WRITE} → catch ladder may mark
 *         {@code FAILED} (reclaimable for same-hash retry).</li>
 *     <li>{@link MutationCommitState#HOST_SAVE_RETURNED} → catch ladder may mark
 *         {@code COMMIT_UNKNOWN} (host write may have committed; never mark FAILED
 *         because that would let a duplicate retry duplicate the host write).</li>
 *     <li>{@link MutationCommitState#INTENT_COMMITTED} → catch ladder must NOT mutate
 *         the dedup row at all (success was already finalized).</li>
 * </ul>
 */
@Component
public class MutationCommitCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MutationCommitCoordinator.class);

    private final MutationIntentRepository mutationIntentRepository;
    private final MutationErrorTranslator mutationErrorTranslator;
    private final AuditWriter auditWriter;
    private final ToolResultFormatter toolResultFormatter;
    private final Metadata metadata;
    private final MetadataTools metadataTools;
    private final DataManager dataManager;
    private final LlmExposurePolicy llmExposurePolicy;
    private final MutationAuthorizationService mutationAuthorizationService;

    public MutationCommitCoordinator(MutationIntentRepository mutationIntentRepository,
                                     MutationErrorTranslator mutationErrorTranslator,
                                     AuditWriter auditWriter,
                                     ToolResultFormatter toolResultFormatter,
                                     Metadata metadata,
                                     MetadataTools metadataTools,
                                     DataManager dataManager,
                                     LlmExposurePolicy llmExposurePolicy,
                                     MutationAuthorizationService mutationAuthorizationService) {
        this.mutationIntentRepository = mutationIntentRepository;
        this.mutationErrorTranslator = mutationErrorTranslator;
        this.auditWriter = auditWriter;
        this.toolResultFormatter = toolResultFormatter;
        this.metadata = metadata;
        this.metadataTools = metadataTools;
        this.dataManager = dataManager;
        this.llmExposurePolicy = llmExposurePolicy;
        this.mutationAuthorizationService = mutationAuthorizationService;
    }

    /**
     * Three-way classifier for non-RESERVED reservation results.
     * <ul>
     *     <li>{@code REPLAY} — re-resolve fresh {@code instanceName} live and return
     *         {@code IDEMPOTENT_REPLAY} JSON.</li>
     *     <li>{@code VIOLATION} — return {@code idempotency_violation}.</li>
     *     <li>{@code PENDING} — return {@code concurrent_modification} with a "do not retry
     *         automatically" hint; the row is in-flight or commit-unknown on this same key.</li>
     * </ul>
     * Caller is expected to guard {@code reservation.state() != RESERVED} before invoking.
     */
    public String handleReservationResult(MutationIntentRepository.ReservationResult reservation,
                                          String toolName,
                                          long startedAt,
                                          String userUsername,
                                          String argumentsJson) {
        AiMutationIntent intent = reservation.intent();
        switch (reservation.state()) {
            case REPLAY -> {
                return replayResult(intent, toolName, startedAt, userUsername, argumentsJson);
            }
            case VIOLATION -> {
                MetaClass mc = resolveMetaClassOrNull(intent.getResultEntityName());
                ToolUserError err = mutationErrorTranslator.idempotencyViolation(mc);
                safeWriteAudit(toolName, argumentsJson, null,
                        System.currentTimeMillis() - startedAt,
                        AiToolCallOutcome.ERROR, null, err.getClass().getName(), userUsername);
                return toolResultFormatter.error(err);
            }
            case PENDING -> {
                // In-flight on the same key OR row sits in COMMIT_UNKNOWN. Either way the LLM
                // must not retry automatically — verify state first via get_record/find_records.
                MetaClass mc = resolveMetaClassOrNull(intent.getResultEntityName());
                ToolUserError err = mutationErrorTranslator.commitFailed(mc);
                safeWriteAudit(toolName, argumentsJson, null,
                        System.currentTimeMillis() - startedAt,
                        AiToolCallOutcome.ERROR, null, err.getClass().getName(), userUsername);
                return toolResultFormatter.error(err);
            }
            default ->
                    throw new IllegalStateException(
                            "handleReservationResult must not be called with RESERVED");
        }
    }

    /**
     * Build the {@code IDEMPOTENT_REPLAY} JSON for an already-COMMITTED dedup row. Re-resolves
     * {@code instanceName} live (Phase 11 specifics: replay returns FRESH instance name under
     * current locale + security, not the literal first-call string). If the entity is no
     * longer readable (LLM exposure denied OR Jmix read denied OR row deleted), keeps the
     * outcome and {@code entityId} but omits/nulls {@code instanceName}.
     */
    private String replayResult(AiMutationIntent existing,
                                String toolName,
                                long startedAt,
                                String userUsername,
                                String argumentsJson) {
        String freshName = null;
        try {
            if (existing.getResultEntityName() != null && existing.getResultEntityId() != null) {
                MetaClass mc = metadata.getClass(existing.getResultEntityName());
                if (llmExposurePolicy.canReadEntity(mc)) {
                    try {
                        mutationAuthorizationService.enforceReadPermission(mc);
                        Object loaded = dataManager.load(mc.getJavaClass())
                                .id(existing.getResultEntityId())
                                .fetchPlan(FetchPlan.INSTANCE_NAME)
                                .optional()
                                .orElse(null);
                        if (loaded != null) {
                            freshName = metadataTools.getInstanceName(loaded);
                        }
                    } catch (ToolUserError ignoredReadDenied) {
                        // current user can no longer read this entity; entityId is preserved
                        // but instanceName stays absent.
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // entity may have been deleted, metaClass may have been removed, or load failed;
            // freshName stays null. Idempotent replay outcome is still valid.
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", AiToolCallOutcome.IDEMPOTENT_REPLAY.getId());
        result.put("entityId", existing.getResultEntityId());
        result.put("instanceName", freshName);

        safeWriteAudit(toolName, argumentsJson, null,
                System.currentTimeMillis() - startedAt,
                AiToolCallOutcome.IDEMPOTENT_REPLAY, null, null, userUsername);
        return toolResultFormatter.toJson(result);
    }

    /**
     * Mark a RESERVED row FAILED (NO_HOST_WRITE) or COMMIT_UNKNOWN (HOST_SAVE_RETURNED).
     * For {@link MutationCommitState#INTENT_COMMITTED} the row is already final and this
     * helper no-ops — see Plan 11-07 STEP G "Required helper behavior".
     *
     * <p>Wraps {@link MutationIntentRepository#markFailed} / {@code markCommitUnknown} in a
     * try/catch so the original mutation error path is preserved even if finalization fails.
     */
    public void markFailedIfReserved(MutationIntentRepository.ReservationResult reservation,
                                     ToolUserError translated,
                                     MutationCommitState commitState) {
        if (reservation == null
                || reservation.state() != MutationIntentRepository.ReservationState.RESERVED) {
            return;
        }
        try {
            if (commitState == MutationCommitState.NO_HOST_WRITE) {
                mutationIntentRepository.markFailed(reservation.intent(), translated.toDto().error());
            } else if (commitState == MutationCommitState.HOST_SAVE_RETURNED) {
                mutationIntentRepository.markCommitUnknown(reservation.intent(), translated.toDto().error());
            }
            // INTENT_COMMITTED: already final, do not mutate.
        } catch (RuntimeException finalizationFailure) {
            // Preserve the original mutation error/audit path; do not leak details to the LLM.
            // If host save returned but markCommitted failed, leaving the row PENDING is safer
            // than marking FAILED: a replay returns concurrent_modification instead of
            // duplicating the host write.
            log.warn("Mutation intent finalization failed for state={} intentId={} class={}",
                    commitState, reservation.intent().getId(),
                    finalizationFailure.getClass().getName());
        }
    }

    /**
     * Translate a Throwable in the catch-all branch, choosing
     * {@link MutationErrorTranslator#commitFailed} when the host save already returned
     * (commit-unknown contract) and routing through the standard translator otherwise.
     */
    public ToolUserError translateThrowableAfterReservation(Throwable thrown,
                                                            MutationCommitState commitState,
                                                            String toolName,
                                                            MetaClass metaClass) {
        if (commitState == MutationCommitState.HOST_SAVE_RETURNED) {
            return mutationErrorTranslator.commitFailed(metaClass);
        }
        return mutationErrorTranslator.translate(thrown, toolName, metaClass);
    }

    /**
     * Map (Throwable, commitState) to the audit row outcome value. Save-time
     * optimistic-lock / validation / data-integrity failures occur while
     * {@code commitState=NO_HOST_WRITE} and surface as {@code ERROR}. Post-host-save
     * finalization failures surface as {@code COMMIT_FAILED} per AUD-06.
     */
    public AiToolCallOutcome auditOutcome(Throwable thrown, MutationCommitState commitState) {
        if (commitState == MutationCommitState.HOST_SAVE_RETURNED) {
            return AiToolCallOutcome.COMMIT_FAILED;
        }
        return AiToolCallOutcome.ERROR;
    }

    /**
     * Non-throwing audit row write. Logs at ERROR with marker
     * {@code AI_AGENT_MUTATION_AUDIT_WRITE_FAILED} and only sanitized context if the
     * underlying audit transaction fails. Does NOT pass the thrown exception as a log
     * argument and does NOT log {@code argumentsJson} — see Plan 11-07 STEP G.
     */
    public void safeWriteAudit(String eventName,
                               String argumentsJson,
                               String resultSummary,
                               long latencyMs,
                               AiToolCallOutcome outcome,
                               String denialReason,
                               String errorClass,
                               String userUsername) {
        try {
            auditWriter.writeToolCall(
                    RunContext.getRootAuditId(),
                    RunContext.get(),
                    userUsername,
                    RunContext.getConversationId(),
                    eventName,
                    argumentsJson,
                    resultSummary,
                    latencyMs,
                    outcome,
                    denialReason,
                    errorClass);
        } catch (RuntimeException auditFailure) {
            log.error("AI_AGENT_MUTATION_AUDIT_WRITE_FAILED eventName={} outcome={} runId={} rootAuditId={} exceptionClass={}",
                    eventName,
                    outcome,
                    RunContext.get(),
                    RunContext.getRootAuditId(),
                    auditFailure.getClass().getName());
        }
    }

    /**
     * Resolve a metaClass from its name without throwing — used in error/replay paths where
     * the dedup row's resultEntityName may reference an entity that has since been removed.
     */
    private MetaClass resolveMetaClassOrNull(String entityName) {
        if (entityName == null) {
            return null;
        }
        try {
            return metadata.getClass(entityName);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
