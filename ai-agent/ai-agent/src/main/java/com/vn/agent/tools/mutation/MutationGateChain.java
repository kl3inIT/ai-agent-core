package com.vn.agent.tools.mutation;

import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.AiUiSettingsResolver;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.security.AiAgentMutationRole;
import com.vn.agent.spi.MutationGuard;
import com.vn.agent.spi.MutationIntent;
import com.vn.agent.spi.ToolVetoedException;
import com.vn.agent.tools.ToolEntityResolver;
import com.vn.agent.tools.ToolResultFormatter;
import com.vn.agent.tools.ToolUserError;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Canonical fail-closed mutation gate orchestration (Phase 17, D-01/D-02). The five
 * {@code @Tool} methods on {@link BuiltInMutationTools} are thin adapters that build a
 * {@link MutationRequest} variant and call {@link #execute(MutationRequest)}; this class owns
 * the single ordered gate spine they all share. The eight named private gate methods are
 * invoked from {@link #execute} in strictly increasing source order so the
 * {@code MutationToolInvariantsTest} {@code indexOf} structural proxy proves the fail-closed
 * sequence is preserved (T-17-01):
 *
 * <pre>enforceRole &rarr; resolve &rarr; authorize &rarr; reserve &rarr; coerce &rarr; guard &rarr; save &rarr; finalize</pre>
 *
 * <p>The {@link #save} gate is the ONLY place the {@link MutationSaveExecutor} save delegate
 * is called, and it appears AFTER every authorization gate — every gate throws before the
 * transactional save crosses the proxy boundary.
 *
 * <p><b>Critical contract — there is NO {@code @Transactional} annotation on this class.</b>
 * Spring's {@code @Transactional} is implemented via a proxy; if this orchestrator declared a
 * private/self-invoked {@code @Transactional} method, the proxy would be bypassed and the
 * annotation silently ignored (Spring self-invocation pitfall). The sole transactional
 * boundary is {@link MutationSaveExecutor#save}/{@code saveAll}/{@code bulkSave}; each host
 * save crosses the proxy via the save delegate. Plan 01's reflection test
 * fails the build if {@code @Transactional} is ever added here (D-04, T-17-07).
 *
 * <p><b>Per-execute state is never an instance field (T-17-08).</b> {@code commitState},
 * {@code reservation}, {@code startedAt}, {@code userUsername}, and
 * {@code metaClass} live in a per-call {@link Context} so concurrent invocations of this
 * singleton cannot corrupt each other's commit classification or rollback decision.
 *
 * <p><b>Bulk-only row denial (T-17-09):</b> the {@code AccessDeniedException} catch arm routes
 * the {@code Bulk} variant to {@code BLOCKED}/{@code row_access_denied}; the single-row tools
 * never throw {@code AccessDeniedException} (their entity-level denial throws
 * {@code ToolUserError(access_denied)} earlier) so their three-arm ladder is preserved.
 *
 * <p>This class orchestrates the EXISTING per-concern collaborators verbatim (D-03); it never
 * rewrites {@link MutationSaveExecutor}, {@link MutationCommitCoordinator},
 * {@link MutationErrorTranslator}, or any error code / audit shape / idempotency call.
 */
@Component
public class MutationGateChain {

    private final ToolEntityResolver toolEntityResolver;
    private final MutationAuthorizationService mutationAuthorizationService;
    private final MutationAttributeBinder mutationAttributeBinder;
    private final MutationRequestHasher mutationRequestHasher;
    private final MutationGuard mutationGuard;
    private final MutationSaveExecutor mutationSaveExecutor;
    private final MutationCommitCoordinator mutationCommitCoordinator;
    private final MutationIntentRepository mutationIntentRepository;
    private final MutationErrorTranslator mutationErrorTranslator;
    private final RelatedWriteMetadataResolver relatedWriteMetadataResolver;
    private final DiffSerializer diffSerializer;
    private final ToolResultFormatter toolResultFormatter;
    private final DataManager dataManager;
    private final MetadataTools metadataTools;
    private final CurrentAuthentication currentAuthentication;
    private final AiAgentMutationProperties mutationProperties;
    private final AccessManager accessManager;
    private final AiUiSettingsResolver aiUiSettingsResolver;

    public MutationGateChain(ToolEntityResolver toolEntityResolver,
                             MutationAuthorizationService mutationAuthorizationService,
                             MutationAttributeBinder mutationAttributeBinder,
                             MutationRequestHasher mutationRequestHasher,
                             MutationGuard mutationGuard,
                             MutationSaveExecutor mutationSaveExecutor,
                             MutationCommitCoordinator mutationCommitCoordinator,
                             MutationIntentRepository mutationIntentRepository,
                             MutationErrorTranslator mutationErrorTranslator,
                             RelatedWriteMetadataResolver relatedWriteMetadataResolver,
                             DiffSerializer diffSerializer,
                             ToolResultFormatter toolResultFormatter,
                             DataManager dataManager,
                             MetadataTools metadataTools,
                             CurrentAuthentication currentAuthentication,
                             AiAgentMutationProperties mutationProperties,
                             AccessManager accessManager,
                             AiUiSettingsResolver aiUiSettingsResolver) {
        this.toolEntityResolver = toolEntityResolver;
        this.mutationAuthorizationService = mutationAuthorizationService;
        this.mutationAttributeBinder = mutationAttributeBinder;
        this.mutationRequestHasher = mutationRequestHasher;
        this.mutationGuard = mutationGuard;
        this.mutationSaveExecutor = mutationSaveExecutor;
        this.mutationCommitCoordinator = mutationCommitCoordinator;
        this.mutationIntentRepository = mutationIntentRepository;
        this.mutationErrorTranslator = mutationErrorTranslator;
        this.relatedWriteMetadataResolver = relatedWriteMetadataResolver;
        this.diffSerializer = diffSerializer;
        this.toolResultFormatter = toolResultFormatter;
        this.dataManager = dataManager;
        this.metadataTools = metadataTools;
        this.currentAuthentication = currentAuthentication;
        this.mutationProperties = mutationProperties;
        this.accessManager = accessManager;
        this.aiUiSettingsResolver = aiUiSettingsResolver;
    }

    /**
     * Per-execute mutable context (T-17-08). NEVER promoted to instance fields — the chain is a
     * singleton and concurrent tool calls must not share commit/reservation state.
     */
    private static final class Context {
        final String toolName;
        final long startedAt = System.currentTimeMillis();
        String userUsername;
        MetaClass metaClass;
        MutationIntentRepository.ReservationResult reservation;
        MutationCommitState commitState = MutationCommitState.NO_HOST_WRITE;

        // Stage hand-off state populated by coerce/save/finalize (per-variant).
        Map<String, Object> coercedAttributes;
        Map<MetaClass, Map<UUID, Object>> prefetchedReferences;
        RelatedWriteMetadataResolver.SupportedRelatedRelationship supported;
        MetaClass childMetaClass;
        UUID parsedId;
        UUID parsedRelatedId;
        Object existingEntity;
        Map<String, Object> preImage;
        Map<String, Object> postImage;
        List<Object> entitiesInOrder;
        List<UUID> savedIds;
        Object saved;
        Object parent;
        Object child;
        String relatedAction;
        List<Map<String, Object>> bulkRowAttrsInOrder;
        // Raw (string) related-write ids retained for not_found error attribution.
        String relatedParentIdRaw;
        String relatedId;

        Context(String toolName) {
            this.toolName = toolName;
        }
    }

    /**
     * Phase 16 D-01 — idempotency TTL resolved from {@code AiUiSettings} (seconds column,
     * Duration-coerced) with fallthrough to {@code mutationProperties.resolvedIdempotencyTtl()}.
     */
    private Duration resolveIdempotencyTtl() {
        return Duration.ofSeconds(aiUiSettingsResolver.resolveMutationIdempotencyTtlSeconds());
    }

    private static String toolNameOf(MutationRequest request) {
        return switch (request) {
            case MutationRequest.Create ignored -> "create_record";
            case MutationRequest.Update ignored -> "update_record";
            case MutationRequest.AddRelated ignored -> "add_related_record";
            case MutationRequest.RemoveRelated ignored -> "remove_related_record";
            case MutationRequest.Bulk ignored -> "bulk_save_records";
        };
    }

    // ----------------------------------------------------------------------
    // Canonical fail-closed gate spine — strictly increasing source order.
    // ----------------------------------------------------------------------

    public String execute(MutationRequest request) {
        Context ctx = new Context(toolNameOf(request));
        ctx.userUsername = currentAuthentication.getUser().getUsername();

        try {
            enforceRole(ctx, request);
            resolve(ctx, request);
            authorize(ctx, request);
            String replay = reserve(ctx, request);
            if (replay != null) {
                return replay;
            }
            coerce(ctx, request);
            guard(ctx, request);
            save(ctx, request);
            return finalize(ctx, request);

        } catch (ToolVetoedException ve) {
            ToolUserError translated = mutationErrorTranslator.translate(new ToolUserError("access_denied",
                    "operation blocked by host policy",
                    List.of("do not retry; surface to user")), ctx.toolName, ctx.metaClass);
            mutationCommitCoordinator.markFailedIfReserved(ctx.reservation, translated, ctx.commitState);
            // P-22 / AUD-07: do NOT echo or audit ve.getMessage(); host veto text may carry PII.
            mutationCommitCoordinator.safeWriteErrorAudit(ctx.toolName,
                    serializeArguments(request),
                    translated,
                    System.currentTimeMillis() - ctx.startedAt,
                    AiToolCallOutcome.BLOCKED, "mutation_guard_vetoed", ve.getClass().getName(), ctx.userUsername);
            return toolResultFormatter.error(translated);
        } catch (AccessDeniedException ade) {
            // REVIEWS HIGH-13 row-level deny short-circuits to BLOCKED — Bulk variant only;
            // single-row tools never throw AccessDeniedException (entity-level denial throws
            // ToolUserError(access_denied) earlier).
            ToolUserError translated = mutationErrorTranslator.translate(ade, ctx.toolName, ctx.metaClass);
            mutationCommitCoordinator.markFailedIfReserved(ctx.reservation, translated, ctx.commitState);
            mutationCommitCoordinator.safeWriteErrorAudit(ctx.toolName,
                    serializeArguments(request),
                    translated,
                    System.currentTimeMillis() - ctx.startedAt,
                    AiToolCallOutcome.BLOCKED, "row_access_denied", ade.getClass().getName(), ctx.userUsername);
            return toolResultFormatter.error(translated);
        } catch (ToolUserError tue) {
            ToolUserError translated = mutationErrorTranslator.translate(tue, ctx.toolName, ctx.metaClass);
            mutationCommitCoordinator.markFailedIfReserved(ctx.reservation, translated, ctx.commitState);
            mutationCommitCoordinator.safeWriteErrorAudit(ctx.toolName,
                    serializeArguments(request),
                    translated,
                    System.currentTimeMillis() - ctx.startedAt,
                    AiToolCallOutcome.ERROR, null, tue.getClass().getName(), ctx.userUsername);
            return toolResultFormatter.error(translated);
        } catch (Throwable t) {
            ToolUserError translated = mutationCommitCoordinator
                    .translateThrowableAfterReservation(t, ctx.commitState, ctx.toolName, ctx.metaClass);
            AiToolCallOutcome outcome = mutationCommitCoordinator.auditOutcome(ctx.commitState);
            mutationCommitCoordinator.markFailedIfReserved(ctx.reservation, translated, ctx.commitState);
            mutationCommitCoordinator.logUnexpectedThrowable(ctx.toolName, t, translated, outcome, ctx.commitState);
            mutationCommitCoordinator.safeWriteErrorAudit(ctx.toolName,
                    serializeArguments(request),
                    translated,
                    System.currentTimeMillis() - ctx.startedAt,
                    outcome, null, t.getClass().getName(), ctx.userUsername);
            return toolResultFormatter.error(translated);
        }
    }

    // ----------------------------------------------------------------------
    // Gate 1 — enforceRole: marker-role authority (SEC-07), runs FIRST.
    // ----------------------------------------------------------------------

    private void enforceRole(Context ctx, MutationRequest request) {
        mutationAuthorizationService.enforceMutationRole(AiAgentMutationRole.CODE);
    }

    // ----------------------------------------------------------------------
    // Gate 2 — resolve: entity resolution + id parse (Phase 18 memo seam, D-05).
    // ----------------------------------------------------------------------

    private void resolve(Context ctx, MutationRequest request) {
        switch (request) {
            case MutationRequest.Create create ->
                    ctx.metaClass = toolEntityResolver.resolveCreatableEntityOrThrow(create.entityName());
            case MutationRequest.Update update -> {
                ctx.metaClass = toolEntityResolver.resolveUpdatableEntityOrThrow(update.entityName());
                ctx.parsedId = mutationAttributeBinder.requireUuidId(
                        toolEntityResolver.parseEntityId(update.id(), ctx.metaClass));
            }
            case MutationRequest.AddRelated add ->
                    resolveRelated(ctx, add.parentEntityName(), add.parentId(), add.relationship(), add.relatedId());
            case MutationRequest.RemoveRelated remove ->
                    resolveRelated(ctx, remove.parentEntityName(), remove.parentId(), remove.relationship(), remove.relatedId());
            case MutationRequest.Bulk bulk -> resolveBulk(ctx, bulk);
        }
    }

    private void resolveRelated(Context ctx, String entityName, String id, String relationship, String relatedId) {
        // Retain raw string ids for not_found error attribution in later gates.
        ctx.relatedParentIdRaw = id;
        ctx.relatedId = relatedId;
        // Resolve parent entity (Phase 10 R4 unknown-entity opacity preserved + update-side gate).
        ctx.metaClass = toolEntityResolver.resolveUpdatableEntityOrThrow(entityName);
        // Parent id parse — distinguishes parameter_conversion_error vs not_found.
        ctx.parsedId = mutationAttributeBinder.requireUuidId(
                toolEntityResolver.parseEntityId(id, ctx.metaClass));
    }

    private void resolveBulk(Context ctx, MutationRequest.Bulk bulk) {
        final List<Map<String, Object>> safeRecords = bulk.records() == null ? List.of() : bulk.records();

        // DoS guard — reject empty + oversized batches BEFORE any entity resolution, hash, or DB work.
        int maxRows = aiUiSettingsResolver.resolveMutationBulkMaxRows();
        if (safeRecords.isEmpty()) {
            throw new ToolUserError("validation_failed",
                    "records must be a non-empty array",
                    List.of("supply at least one record object; for a single row use create_record or update_record"));
        }
        if (safeRecords.size() > maxRows) {
            throw new ToolUserError("validation_failed",
                    "batch size " + safeRecords.size() + " exceeds configured maximum " + maxRows,
                    List.of("split the batch into smaller calls; each call needs its own fresh idempotencyKey"));
        }

        // REVIEWS HIGH-12: reject explicit id:null up front (distinct from key-omitted CREATE).
        for (int i = 0; i < safeRecords.size(); i++) {
            Map<String, Object> row = safeRecords.get(i);
            if (row != null && row.containsKey("id") && row.get("id") == null) {
                throw new ToolUserError("validation_failed",
                        "row " + i + ": explicit id:null is not allowed; omit the id key entirely to create",
                        List.of("either omit the 'id' key for a CREATE, or supply a UUID id for an UPDATE"));
            }
        }

        // Dispatch detection — REVIEWS HIGH-12 containsKey semantics.
        boolean anyCreate = safeRecords.stream().anyMatch(r -> r != null && !r.containsKey("id"));
        boolean anyUpdate = safeRecords.stream().anyMatch(r -> r != null && r.containsKey("id") && r.get("id") != null);

        // Resolve metaClass — both checks must pass for mixed batches.
        if (anyCreate) {
            ctx.metaClass = toolEntityResolver.resolveCreatableEntityOrThrow(bulk.entityName());
        }
        if (anyUpdate) {
            ctx.metaClass = toolEntityResolver.resolveUpdatableEntityOrThrow(bulk.entityName());
        }
    }

    // ----------------------------------------------------------------------
    // Gate 3 — authorize: Jmix per-CRUD + per-attribute + target exposure.
    // ----------------------------------------------------------------------

    private void authorize(Context ctx, MutationRequest request) {
        switch (request) {
            case MutationRequest.Create create -> {
                mutationAuthorizationService.enforceCreatePermission(ctx.metaClass);
                mutationAuthorizationService.enforceAttributeWriteAccess(ctx.metaClass, safeAttributes(create.attributes()).keySet());
            }
            case MutationRequest.Update update -> {
                mutationAuthorizationService.enforceUpdatePermission(ctx.metaClass);
                mutationAuthorizationService.enforceAttributeWriteAccess(ctx.metaClass, safeAttributes(update.attributes()).keySet());
            }
            case MutationRequest.AddRelated add -> authorizeRelated(ctx, add.relationship());
            case MutationRequest.RemoveRelated remove -> authorizeRelated(ctx, remove.relationship());
            case MutationRequest.Bulk bulk -> authorizeBulk(ctx, bulk);
        }
    }

    private void authorizeRelated(Context ctx, String relationship) {
        MetaClass parentMetaClass = ctx.metaClass;

        // Jmix per-CRUD update on parent + per-attribute modify on the relationship.
        mutationAuthorizationService.enforceUpdatePermission(parentMetaClass);
        mutationAuthorizationService.enforceAttributeWriteAccess(parentMetaClass, Set.of(relationship));

        // Verified relationship-metadata authority — narrow v1.1 support matrix.
        ctx.supported = relatedWriteMetadataResolver.resolveSupportedRelatedWriteRelationship(
                parentMetaClass, relationship);
        ctx.childMetaClass = ctx.supported.childMetaClass();

        // Child-side LLM exposure read+modify (related writes mutate the child inverse).
        mutationAuthorizationService.enforceLlmRelationshipTargetExposure(ctx.childMetaClass, true);
        // Jmix child read+update + child-side inverse attribute canModify.
        mutationAuthorizationService.enforceReadPermission(ctx.childMetaClass);
        mutationAuthorizationService.enforceUpdatePermission(ctx.childMetaClass);
        mutationAuthorizationService.enforceAttributeWriteAccess(
                ctx.childMetaClass, Set.of(ctx.supported.childInverseProperty().getName()));

        // Child id parse (parameter_conversion_error vs not_found) — BEFORE reservation, matching
        // the inline gate order so a bad id throws before any idempotency row is reserved.
        ctx.parsedRelatedId = mutationAttributeBinder.requireUuidId(
                toolEntityResolver.parseEntityId(ctx.relatedId, ctx.childMetaClass));

        // remove-time required-inverse check (validation_failed before reservation).
        if (ctx.toolName.equals("remove_related_record")) {
            relatedWriteMetadataResolver.ensureInverseClearable(ctx.supported);
        }
    }

    private void authorizeBulk(Context ctx, MutationRequest.Bulk bulk) {
        final List<Map<String, Object>> safeRecords = bulk.records() == null ? List.of() : bulk.records();
        boolean anyCreate = safeRecords.stream().anyMatch(r -> r != null && !r.containsKey("id"));
        boolean anyUpdate = safeRecords.stream().anyMatch(r -> r != null && r.containsKey("id") && r.get("id") != null);

        // Entity-level CRUD gate.
        if (anyCreate) {
            mutationAuthorizationService.enforceCreatePermission(ctx.metaClass);
        }
        if (anyUpdate) {
            mutationAuthorizationService.enforceUpdatePermission(ctx.metaClass);
        }

        // Per-attribute write gate — UNION across rows, excluding id.
        Set<String> writtenKeys = safeRecords.stream()
                .flatMap(r -> r.keySet().stream())
                .filter(k -> !"id".equals(k))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        mutationAuthorizationService.enforceAttributeWriteAccess(ctx.metaClass, writtenKeys);
    }

    // ----------------------------------------------------------------------
    // Gate 4 — reserve: canonical request hash + pre-save reservation/replay.
    // Returns a non-null formatted result on a non-RESERVED reservation (early return).
    // ----------------------------------------------------------------------

    private String reserve(Context ctx, MutationRequest request) {
        String requestHash = requestHash(ctx, request);
        ctx.reservation = mutationIntentRepository.reserveOrReplay(
                ctx.toolName, request.idempotencyKey(), ctx.userUsername, RunContext.getConversationId(),
                requestHash, resolveIdempotencyTtl());
        if (ctx.reservation.state() != MutationIntentRepository.ReservationState.RESERVED) {
            return mutationCommitCoordinator.handleReservationResult(
                    ctx.reservation, ctx.toolName, ctx.startedAt, ctx.userUsername,
                    serializeArguments(request));
        }
        return null;
    }

    private String requestHash(Context ctx, MutationRequest request) {
        return switch (request) {
            case MutationRequest.Create create -> mutationRequestHasher.hash(
                    "create_record", create.entityName(), null, null, null, safeAttributes(create.attributes()));
            case MutationRequest.Update update -> mutationRequestHasher.hash(
                    "update_record", update.entityName(), update.id(), null, null, safeAttributes(update.attributes()));
            case MutationRequest.AddRelated add -> mutationRequestHasher.hash(
                    "add_related_record", add.parentEntityName(), add.parentId(), add.relationship(), add.relatedId(), Map.of());
            case MutationRequest.RemoveRelated remove -> mutationRequestHasher.hash(
                    "remove_related_record", remove.parentEntityName(), remove.parentId(), remove.relationship(), remove.relatedId(), Map.of());
            case MutationRequest.Bulk bulk -> {
                Map<String, Object> batchEnvelope = new LinkedHashMap<>();
                batchEnvelope.put("records", bulk.records() == null ? List.of() : bulk.records());
                yield mutationRequestHasher.hash(
                        "bulk_save_records", bulk.entityName(), null, null, null, batchEnvelope);
            }
        };
    }

    // ----------------------------------------------------------------------
    // Gate 5 — coerce: literal coercion / typed binding BEFORE the guard.
    // ----------------------------------------------------------------------

    private void coerce(Context ctx, MutationRequest request) {
        switch (request) {
            case MutationRequest.Create create ->
                    ctx.coercedAttributes = mutationAttributeBinder.coerceAttributes(ctx.metaClass, safeAttributes(create.attributes()));
            case MutationRequest.Update update ->
                    ctx.coercedAttributes = mutationAttributeBinder.coerceAttributes(ctx.metaClass, safeAttributes(update.attributes()));
            // Related writes carry no coerced attribute map — child id parse +
            // ensureInverseClearable already ran in authorize() (BEFORE reservation, matching
            // the inline gate order). Nothing to coerce here.
            case MutationRequest.AddRelated add -> { }
            case MutationRequest.RemoveRelated remove -> { }
            case MutationRequest.Bulk bulk -> coerceBulk(ctx, bulk);
        }
    }

    private void coerceBulk(Context ctx, MutationRequest.Bulk bulk) {
        final List<Map<String, Object>> safeRecords = bulk.records() == null ? List.of() : bulk.records();

        // MUT-16 — strip 'id' from every row's attribute map ONCE (submission order), then
        // run a SINGLE cross-row to-one FK prefetch so a K-row batch pointing at one parent
        // costs ONE constrained .ids(...) SELECT per distinct target class regardless of K
        // (slope ~0), NOT one FK load per row. The per-row bind pass below reads from this
        // map instead of issuing its own load.
        List<Map<String, Object>> rowAttrsInOrder = new ArrayList<>(safeRecords.size());
        for (Map<String, Object> row : safeRecords) {
            rowAttrsInOrder.add(row.entrySet().stream()
                    .filter(e -> !"id".equals(e.getKey()))
                    .collect(Collectors.toMap(
                            Map.Entry::getKey, Map.Entry::getValue,
                            (a, b) -> b, LinkedHashMap::new)));
        }
        ctx.coercedAttributes = null; // not used for bulk — per-row coercion happens in save
        // WR-04: prefetchReferences runs the cross-row attribute-name + FK-UUID validation HERE,
        // in gate 5, BEFORE the per-row save loop. A failure (unknown/read-only/PK attribute name,
        // malformed FK UUID) is thrown WITHOUT a per-row index — the batch is all-or-nothing, so
        // the offending row is not individually attributed; the LLM resubmits all corrected rows.
        ctx.prefetchedReferences = mutationAttributeBinder.prefetchReferences(ctx.metaClass, rowAttrsInOrder);
        ctx.entitiesInOrder = new ArrayList<>(safeRecords.size());
        // Stash the stripped row attrs for the per-row save loop via a context field.
        ctx.bulkRowAttrsInOrder = rowAttrsInOrder;
    }

    // ----------------------------------------------------------------------
    // Gate 6 — guard: host MutationGuard SPI veto point (typed values).
    // For Bulk the per-row guard runs inside save() (per-row CrudEntityContext + guard loop).
    // ----------------------------------------------------------------------

    private void guard(Context ctx, MutationRequest request) {
        switch (request) {
            case MutationRequest.Create create ->
                    mutationGuard.check(new MutationIntent("create_record", ctx.metaClass, null, ctx.coercedAttributes));
            case MutationRequest.Update update ->
                    mutationGuard.check(new MutationIntent("update_record", ctx.metaClass, ctx.parsedId, ctx.coercedAttributes));
            case MutationRequest.AddRelated add -> guardRelated(ctx, add.relationship());
            case MutationRequest.RemoveRelated remove -> guardRelated(ctx, remove.relationship());
            case MutationRequest.Bulk bulk -> {
                // Per-row guard runs inside the save() per-row loop (REVIEWS HIGH-13 ordering).
            }
        }
    }

    private void guardRelated(Context ctx, String relationship) {
        // Related-write guard needs the loaded parent/child references, so the parent/child are
        // loaded here (between authorization gates and the host save) and the guard sees typed
        // entity refs (not raw ids) per the D-03 contract.
        String id = ctx.relatedParentIdRaw;
        String relatedId = ctx.relatedId;

        // Pitfall #1 — load parent with the children-free FetchPlan.BASE; never pull the
        // @OneToMany collection into the persistence context (would re-save children with
        // bumped @Version). Related writes mutate ONLY the child-side inverse.
        final MetaClass parentMetaClassFinal = ctx.metaClass;
        ctx.parent = dataManager.load(ctx.metaClass.getJavaClass())
                .id(ctx.parsedId)
                .fetchPlan(FetchPlan.BASE)
                .optional()
                .orElseThrow(() -> mutationErrorTranslator.notFound(parentMetaClassFinal, id));

        // Load child by id; the child carries the FK so the child is what we save.
        final MetaClass childMetaClassFinal = ctx.childMetaClass;
        final String relatedIdForError = relatedId;
        ctx.child = dataManager.load(ctx.childMetaClass.getJavaClass())
                .id(ctx.parsedRelatedId)
                .optional()
                .orElseThrow(() -> mutationErrorTranslator.notFound(childMetaClassFinal, relatedIdForError));

        boolean isRemove = ctx.toolName.equals("remove_related_record");
        if (!isRemove && relatedWriteMetadataResolver.childBelongsToDifferentParent(ctx.supported, ctx.parent, ctx.child)) {
            throw new ToolUserError("validation_failed",
                    "child is already linked to another parent",
                    List.of("call get_record to verify the child relationship before retrying"));
        }

        // Host MutationGuard SPI veto point — guards see typed parent/child via attributes.
        // attributes map carries the loaded entity references (not raw ids) per D-03 contract.
        Map<String, Object> guardAttributes = new LinkedHashMap<>();
        guardAttributes.put(relationship, ctx.child);
        mutationGuard.check(new MutationIntent(
                ctx.toolName, ctx.metaClass, ctx.parsedId, Map.copyOf(guardAttributes)));
    }

    // ----------------------------------------------------------------------
    // Gate 7 — save: the ONLY proxy crossing; AFTER every authorization gate.
    // ----------------------------------------------------------------------

    private void save(Context ctx, MutationRequest request) {
        switch (request) {
            case MutationRequest.Create create -> saveCreate(ctx);
            case MutationRequest.Update update -> saveUpdate(ctx);
            case MutationRequest.AddRelated add -> saveRelated(ctx, add.relationship());
            case MutationRequest.RemoveRelated remove -> saveRelated(ctx, remove.relationship());
            case MutationRequest.Bulk bulk -> saveBulk(ctx, bulk);
        }
    }

    private void saveCreate(Context ctx) {
        // Build entity in-memory; SAVE via separate @Component (proxy crossed -> real @Transactional).
        Object entity = dataManager.create(ctx.metaClass.getJavaClass());
        ctx.postImage = mutationAttributeBinder.applyAttributes(ctx.metaClass, entity, ctx.coercedAttributes);
        ctx.saved = mutationSaveExecutor.save(entity);
        ctx.commitState = MutationCommitState.HOST_SAVE_RETURNED;
    }

    private void saveUpdate(Context ctx) {
        // Load existing by id; no explicit fetch plan keeps the graph minimal.
        final MetaClass loadedMetaClass = ctx.metaClass;
        final String idForError = ctx.parsedId.toString();
        ctx.existingEntity = dataManager.load(ctx.metaClass.getJavaClass())
                .id(ctx.parsedId)
                .optional()
                .orElseThrow(() -> mutationErrorTranslator.notFound(loadedMetaClass, idForError));

        // Capture pre-image for the attribute keys the caller is changing.
        ctx.preImage = mutationAttributeBinder.capturePreImage(
                ctx.existingEntity, ctx.coercedAttributes.keySet());

        // Apply attributes.
        ctx.postImage = mutationAttributeBinder.applyAttributes(
                ctx.metaClass, ctx.existingEntity, ctx.coercedAttributes);

        // SAVE via separate @Component (proxy crossed -> real @Transactional).
        ctx.saved = mutationSaveExecutor.save(ctx.existingEntity);
        ctx.commitState = MutationCommitState.HOST_SAVE_RETURNED;
    }

    private void saveRelated(Context ctx, String relationship) {
        boolean isRemove = ctx.toolName.equals("remove_related_record");
        String relatedId = ctx.relatedId;

        // Mutate ONLY the child-side inverse — never rewrite the parent collection.
        if (isRemove) {
            // remove: child must currently belong to this parent; otherwise not_found
            // (treat non-member like missing-row so the LLM re-reads via get_record).
            if (!relatedWriteMetadataResolver.childBelongsToParent(ctx.supported, ctx.parent, ctx.child)) {
                throw mutationErrorTranslator.notFound(ctx.childMetaClass, relatedId);
            }
            relatedWriteMetadataResolver.clearInverseReference(ctx.supported, ctx.child);
            ctx.relatedAction = "removed";
        } else {
            relatedWriteMetadataResolver.wireInverseReference(ctx.supported, ctx.parent, ctx.child);
            ctx.relatedAction = "added";
        }

        // saveAll within MutationSaveExecutor's @Transactional boundary (proxy-crossed).
        // Saving both parent and child keeps optimistic-lock parity with the parent.
        mutationSaveExecutor.saveAll(ctx.parent, ctx.child);
        ctx.commitState = MutationCommitState.HOST_SAVE_RETURNED;
    }

    private void saveBulk(Context ctx, MutationRequest.Bulk bulk) {
        final List<Map<String, Object>> safeRecords = bulk.records() == null ? List.of() : bulk.records();
        List<Map<String, Object>> rowAttrsInOrder = ctx.bulkRowAttrsInOrder;

        // Per-row coerce + per-row CrudEntityContext + guard + build SaveContext.
        SaveContext saveContext = new SaveContext();
        for (int i = 0; i < safeRecords.size(); i++) {
            Map<String, Object> row = safeRecords.get(i);
            boolean isUpdate = row.containsKey("id");
            Object rowId = isUpdate ? row.get("id") : null;
            Map<String, Object> rowAttrs = rowAttrsInOrder.get(i);
            Map<String, Object> coerced =
                    mutationAttributeBinder.coerceAttributes(ctx.metaClass, rowAttrs, ctx.prefetchedReferences);

            Object entity;
            if (!isUpdate) {
                entity = dataManager.create(ctx.metaClass.getJavaClass());
            } else {
                final MetaClass loadedMetaClass = ctx.metaClass;
                final String idForError = rowId.toString();
                UUID parsed = mutationAttributeBinder.requireUuidId(
                        toolEntityResolver.parseEntityId(idForError, ctx.metaClass));
                entity = dataManager.load(ctx.metaClass.getJavaClass())
                        .id(parsed)
                        .optional()
                        .orElseThrow(() -> mutationErrorTranslator.notFound(loadedMetaClass, idForError));
            }

            // REVIEWS HIGH-13 — per-row CrudEntityContext AFTER entity load/create so any
            // row-state-dependent constraints (row-level policies that gate on entity.status,
            // tenant predicates, etc.) see live row state. Phase 11
            // MutationAuthorizationService runs entity-level checks once per batch; this is
            // the per-row evaluator MUT-14 calls for explicitly.
            CrudEntityContext crudCtx = new CrudEntityContext(ctx.metaClass);
            accessManager.applyRegisteredConstraints(crudCtx);
            if (isUpdate && !crudCtx.isUpdatePermitted()) {
                throw new AccessDeniedException(
                        "update denied for entity " + ctx.metaClass.getName() + " row " + i);
            }
            if (!isUpdate && !crudCtx.isCreatePermitted()) {
                throw new AccessDeniedException(
                        "create denied for entity " + ctx.metaClass.getName() + " row " + i);
            }

            mutationGuard.check(new MutationIntent(
                    "bulk_save_records", ctx.metaClass,
                    isUpdate ? (UUID) EntityValues.getId(entity) : null,
                    coerced));
            mutationAttributeBinder.applyAttributes(ctx.metaClass, entity, coerced);
            saveContext.saving(entity);
            ctx.entitiesInOrder.add(entity);
        }

        // Single proxy-crossed @Transactional save — rollback-all on RuntimeException.
        // bulkSave sets SaveContext.discardSaved(true), so the returned EntitySet is empty
        // (no per-row post-save reload — MUT-16 O(1) batch contract). savedIds is therefore
        // derived from the in-memory entitiesInOrder, not the returned EntitySet.
        //
        // WR-01 — rollback-all invariant, precise scope:
        //   COVERED (in contract): any host policy, Bean Validation rule, or entity listener
        //     (BeforeInsert/BeforeUpdate/BeforeDelete) that THROWS a RuntimeException aborts the
        //     single @Transactional span and rolls back the ENTIRE batch — zero rows persisted,
        //     no partial commit. This is the documented and tested guarantee (see
        //     BuiltInMutationToolsBulkSaveListenerRollbackTest).
        //   NOT COVERED (explicitly OUT of contract): a listener that returns NORMALLY but
        //     arranges for a row to be silently skipped (e.g. removes it from the save set, or a
        //     data store that filters the batch without raising). With discardSaved(true) there is
        //     no returned EntitySet to cross-check, so savedIds[i] would report a generated UUID
        //     for a row that never hit the database. Such non-throwing silent drops are uncommon
        //     and considered out of the bulk_save_records contract; the host must signal a refusal
        //     by throwing, not by silently dropping.
        mutationSaveExecutor.bulkSave(saveContext);
        ctx.commitState = MutationCommitState.HOST_SAVE_RETURNED;
        // Use entitiesInOrder (input order is the contract), not the unordered EntitySet
        // iterator, so savedIds[i] aligns with records[i] for the LLM. The @JmixGeneratedValue
        // UUID ids are populated on the in-memory entities at create time, so no reload is
        // needed to read them back.
        ctx.savedIds = ctx.entitiesInOrder.stream()
                .map(e -> (UUID) EntityValues.getId(e))
                .toList();
    }

    // ----------------------------------------------------------------------
    // Gate 8 — finalize: idempotency mark-committed + success audit + result.
    // ----------------------------------------------------------------------

    private String finalize(Context ctx, MutationRequest request) {
        return switch (request) {
            case MutationRequest.Create create -> finalizeCreate(ctx, create);
            case MutationRequest.Update update -> finalizeUpdate(ctx, update);
            case MutationRequest.AddRelated add -> finalizeRelated(ctx, add.parentEntityName(), add.parentId(), add.relationship(), add.relatedId(), add.idempotencyKey());
            case MutationRequest.RemoveRelated remove -> finalizeRelated(ctx, remove.parentEntityName(), remove.parentId(), remove.relationship(), remove.relatedId(), remove.idempotencyKey());
            case MutationRequest.Bulk bulk -> finalizeBulk(ctx, bulk);
        };
    }

    private String finalizeCreate(Context ctx, MutationRequest.Create create) {
        UUID savedId = mutationAttributeBinder.requireUuidId(EntityValues.getId(ctx.saved));
        String instanceName = metadataTools.getInstanceName(ctx.saved);

        String argumentsJson = diffSerializer.serializeEntityArgumentsJson(
                create.entityName(), null, safeAttributes(create.attributes()), create.idempotencyKey());
        String diffJson = diffSerializer.serializeCreatePostImage(ctx.postImage);

        // Finalize idempotency BEFORE externally reporting success.
        mutationIntentRepository.markCommitted(ctx.reservation.intent(), savedId, ctx.metaClass.getName());
        ctx.commitState = MutationCommitState.INTENT_COMMITTED;

        mutationCommitCoordinator.safeWriteAudit("create_record", argumentsJson, diffJson,
                System.currentTimeMillis() - ctx.startedAt,
                AiToolCallOutcome.SUCCESS, null, null, ctx.userUsername);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", AiToolCallOutcome.SUCCESS.getId());
        result.put("entityId", savedId);
        result.put("instanceName", instanceName);
        return toolResultFormatter.toJson(result);
    }

    private String finalizeUpdate(Context ctx, MutationRequest.Update update) {
        UUID savedId = mutationAttributeBinder.requireUuidId(EntityValues.getId(ctx.saved));
        String instanceName = metadataTools.getInstanceName(ctx.saved);

        String argumentsJson = diffSerializer.serializeEntityArgumentsJson(
                update.entityName(), update.id(), safeAttributes(update.attributes()), update.idempotencyKey());
        String diffJson = diffSerializer.serializeUpdateDiff(ctx.preImage, ctx.postImage);

        // Finalize idempotency BEFORE externally reporting success.
        mutationIntentRepository.markCommitted(ctx.reservation.intent(), savedId, ctx.metaClass.getName());
        ctx.commitState = MutationCommitState.INTENT_COMMITTED;

        mutationCommitCoordinator.safeWriteAudit("update_record", argumentsJson, diffJson,
                System.currentTimeMillis() - ctx.startedAt,
                AiToolCallOutcome.SUCCESS, null, null, ctx.userUsername);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", AiToolCallOutcome.SUCCESS.getId());
        result.put("entityId", savedId);
        result.put("instanceName", instanceName);
        result.put("diffSummary", diffJson);
        return toolResultFormatter.toJson(result);
    }

    private String finalizeRelated(Context ctx, String entityName, String id, String relationship, String relatedId, String idempotencyKey) {
        String argumentsJson = diffSerializer.serializeRelatedArgumentsJson(
                entityName, id, relationship, relatedId, idempotencyKey);
        String resultSummary = diffSerializer.serializeRelatedActionSummary(
                relationship, ctx.relatedAction, relatedId);

        // Finalize idempotency BEFORE externally reporting success; persist parent id +
        // parent metaClass so replay re-resolves parent instanceName.
        mutationIntentRepository.markCommitted(ctx.reservation.intent(), ctx.parsedId, ctx.metaClass.getName());
        ctx.commitState = MutationCommitState.INTENT_COMMITTED;

        mutationCommitCoordinator.safeWriteAudit(ctx.toolName, argumentsJson, resultSummary,
                System.currentTimeMillis() - ctx.startedAt,
                AiToolCallOutcome.SUCCESS, null, null, ctx.userUsername);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", AiToolCallOutcome.SUCCESS.getId());
        result.put("parentId", ctx.parsedId);
        result.put("relationship", relationship);
        result.put("relatedId", ctx.parsedRelatedId);
        return toolResultFormatter.toJson(result);
    }

    private String finalizeBulk(Context ctx, MutationRequest.Bulk bulk) {
        final List<Map<String, Object>> safeRecords = bulk.records() == null ? List.of() : bulk.records();

        String argumentsJson = diffSerializer.serializeBulkArgumentsJson(
                bulk.entityName(), safeRecords, bulk.idempotencyKey());
        String resultSummaryJson = diffSerializer.serializeBulkResultSummary(ctx.savedIds);

        // Finalize idempotency BEFORE audit (REVIEWS HIGH-11 — persist savedIds for replay).
        UUID firstId = ctx.savedIds.isEmpty() ? null : ctx.savedIds.get(0);
        mutationIntentRepository.markCommitted(
                ctx.reservation.intent(), firstId, ctx.metaClass.getName(), resultSummaryJson);
        ctx.commitState = MutationCommitState.INTENT_COMMITTED;

        mutationCommitCoordinator.safeWriteAudit("bulk_save_records",
                argumentsJson, resultSummaryJson,
                System.currentTimeMillis() - ctx.startedAt,
                AiToolCallOutcome.SUCCESS, null, null, ctx.userUsername);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", AiToolCallOutcome.SUCCESS.getId());
        result.put("count", ctx.savedIds.size());
        result.put("savedIds", ctx.savedIds);
        return toolResultFormatter.toJson(result);
    }

    // ----------------------------------------------------------------------
    // Per-variant argumentsJson serializer selection (shared by every catch arm + reserve).
    // ----------------------------------------------------------------------

    private String serializeArguments(MutationRequest request) {
        return switch (request) {
            case MutationRequest.Create create -> diffSerializer.serializeEntityArgumentsJson(
                    create.entityName(), null, safeAttributes(create.attributes()), create.idempotencyKey());
            case MutationRequest.Update update -> diffSerializer.serializeEntityArgumentsJson(
                    update.entityName(), update.id(), safeAttributes(update.attributes()), update.idempotencyKey());
            case MutationRequest.AddRelated add -> diffSerializer.serializeRelatedArgumentsJson(
                    add.parentEntityName(), add.parentId(), add.relationship(), add.relatedId(), add.idempotencyKey());
            case MutationRequest.RemoveRelated remove -> diffSerializer.serializeRelatedArgumentsJson(
                    remove.parentEntityName(), remove.parentId(), remove.relationship(), remove.relatedId(), remove.idempotencyKey());
            case MutationRequest.Bulk bulk -> diffSerializer.serializeBulkArgumentsJson(
                    bulk.entityName(), bulk.records() == null ? List.of() : bulk.records(), bulk.idempotencyKey());
        };
    }

    private static Map<String, Object> safeAttributes(Map<String, Object> attributes) {
        return attributes == null ? Map.of() : attributes;
    }
}
