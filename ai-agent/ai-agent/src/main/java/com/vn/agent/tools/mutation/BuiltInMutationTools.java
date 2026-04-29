package com.vn.agent.tools.mutation;

import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.security.AiAgentMutationRole;
import com.vn.agent.spi.MutationGuard;
import com.vn.agent.spi.MutationIntent;
import com.vn.agent.spi.ToolVetoedException;
import com.vn.agent.tools.ToolEntityResolver;
import com.vn.agent.tools.ToolResultFormatter;
import com.vn.agent.tools.ToolUserError;
import io.jmix.core.DataManager;
import io.jmix.core.MetadataTools;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LLM-mediated mutation tool surface (Phase 11). Conditional via
 * {@code @ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled",
 * havingValue="true")} — default OFF. When the bean is absent, no mutation callbacks are
 * registered with {@code AgentToolCallbacks.forCurrentUser} (TEST-13 boot assertion).
 *
 * <p><b>Plan 11-07A scope:</b> exposes ONLY {@code create_record} and {@code update_record}.
 * Plan 11-07B will add the related-write metadata resolver and the {@code add_related_record}
 * / {@code remove_related_record} tools. {@code delete_record} is NOT shipped under any flag
 * combination in v1.1 (D-07).
 *
 * <p><b>Thin orchestration only.</b> Authorization, attribute binding, request hashing,
 * commit/replay/audit handling, and host saves are delegated to per-concern collaborators:
 * <ul>
 *     <li>{@link MutationAuthorizationService} — marker-role / CRUD / per-attribute / target exposure.</li>
 *     <li>{@link MutationAttributeBinder} — mass-assignment validation + scalar/to-one coercion.</li>
 *     <li>{@link MutationRequestHasher} — canonical-JSON request hash for idempotency dedup.</li>
 *     <li>{@link MutationIntentRepository} — pre-host-save reservation, replay/violation/pending classification, finalize.</li>
 *     <li>{@link MutationSaveExecutor} — sole {@code @Transactional} save boundary (proxy-crossing; regular DataManager).</li>
 *     <li>{@link MutationCommitCoordinator} — reservation-result handling, commit-state transitions, safeWriteAudit.</li>
 *     <li>{@link MutationErrorTranslator} — Throwable → 6 stable D-04 error codes; never echoes raw exception/PII text.</li>
 *     <li>{@link DiffSerializer} — argumentsJson + diff JSON with optional sensitive-field hashing (AUD-07).</li>
 * </ul>
 *
 * <p><b>Critical contract — there is NO {@code @Transactional} annotation on this class.</b>
 * Every host save crosses the Spring proxy via {@code mutationSaveExecutor.save(...)}; a
 * private/self-invoked {@code @Transactional} method on this bean would be silently bypassed
 * (Spring self-invocation pitfall).
 *
 * <p><b>Marker-role gate (SEC-07):</b> {@link MutationAuthorizationService#enforceMutationRole}
 * compares {@link AiAgentMutationRole#CODE} with EXACT
 * {@code RoleGrantedAuthorityUtils.createResourceRoleGrantedAuthority(...).getAuthority()}
 * equality. A fake authority that merely contains the role code is rejected. The marker
 * runs FIRST — before entity resolution, idempotency reservation, or host save.
 *
 * <p><b>P-22 contract:</b> no method in this class concatenates LLM-supplied attribute values
 * into result/error prose. Audit payloads flow through {@link DiffSerializer} (which
 * Jackson-escapes and SHA-256-hashes sensitive values); error strings come from
 * {@link MutationErrorTranslator} (which rebuilds canned safe templates).
 */
@Component
@ConditionalOnProperty(prefix = "ai-agent.tools.mutation",
        name = "enabled", havingValue = "true")
public class BuiltInMutationTools {

    private static final Logger log = LoggerFactory.getLogger(BuiltInMutationTools.class);

    private final ToolEntityResolver toolEntityResolver;
    private final MutationAuthorizationService mutationAuthorizationService;
    private final MutationAttributeBinder mutationAttributeBinder;
    private final MutationRequestHasher mutationRequestHasher;
    private final MutationGuard mutationGuard;
    private final MutationSaveExecutor mutationSaveExecutor;
    private final MutationCommitCoordinator mutationCommitCoordinator;
    private final MutationIntentRepository mutationIntentRepository;
    private final MutationErrorTranslator mutationErrorTranslator;
    private final DiffSerializer diffSerializer;
    private final ToolResultFormatter toolResultFormatter;
    private final DataManager dataManager;
    private final MetadataTools metadataTools;
    private final CurrentAuthentication currentAuthentication;
    private final AiAgentMutationProperties mutationProperties;

    public BuiltInMutationTools(ToolEntityResolver toolEntityResolver,
                                MutationAuthorizationService mutationAuthorizationService,
                                MutationAttributeBinder mutationAttributeBinder,
                                MutationRequestHasher mutationRequestHasher,
                                MutationGuard mutationGuard,
                                MutationSaveExecutor mutationSaveExecutor,
                                MutationCommitCoordinator mutationCommitCoordinator,
                                MutationIntentRepository mutationIntentRepository,
                                MutationErrorTranslator mutationErrorTranslator,
                                DiffSerializer diffSerializer,
                                ToolResultFormatter toolResultFormatter,
                                DataManager dataManager,
                                MetadataTools metadataTools,
                                CurrentAuthentication currentAuthentication,
                                AiAgentMutationProperties mutationProperties) {
        this.toolEntityResolver = toolEntityResolver;
        this.mutationAuthorizationService = mutationAuthorizationService;
        this.mutationAttributeBinder = mutationAttributeBinder;
        this.mutationRequestHasher = mutationRequestHasher;
        this.mutationGuard = mutationGuard;
        this.mutationSaveExecutor = mutationSaveExecutor;
        this.mutationCommitCoordinator = mutationCommitCoordinator;
        this.mutationIntentRepository = mutationIntentRepository;
        this.mutationErrorTranslator = mutationErrorTranslator;
        this.diffSerializer = diffSerializer;
        this.toolResultFormatter = toolResultFormatter;
        this.dataManager = dataManager;
        this.metadataTools = metadataTools;
        this.currentAuthentication = currentAuthentication;
        this.mutationProperties = mutationProperties;
    }

    // ----------------------------------------------------------------------
    // create_record
    // ----------------------------------------------------------------------

    @Tool(name = "create_record", description = """
            MANDATORY WORKFLOW:
            1. Call list_entities to confirm the entity name exists and is visible.
            2. Call describe_entity to learn the mandatory attributes and their types.
            3. Generate a UUID idempotencyKey for this exact logical create call. Reuse it only for an exact retry of the same call shape.
            4. Pass attributes as a JSON object whose keys are the entity attribute names.
            5. Reference relationships by foreign-key UUID strings inside the same object.

            INPUT CONTRACT:
            - entityName: exact internal name from list_entities, NEVER a label.
            - attributes: object with attribute-name keys; values match the types from describe_entity.
            - idempotencyKey: UUID string. Same key + same call shape -> IDEMPOTENT_REPLAY. Same key + changed attributes -> idempotency_violation.

            PARAMETER FORMATS:
            - String attributes: plain JSON string.
            - Numeric: JSON number.
            - Boolean: JSON boolean.
            - UUID/relationship: hyphenated 36-char UUID string.
            - Date/time: ISO 8601 string (see describe_entity attributeType).
            - Enum: the enum's stable id string from describe_entity enumValues.

            ERROR HANDLING:
            - access_denied: per-CRUD or per-attribute denial. Do NOT retry; surface to user.
            - validation_failed: required attribute missing or constraint violated. Re-read describe_entity; if you change values, retry with a FRESH idempotencyKey.
            - parameter_conversion_error: a value didn't parse as the expected type. Re-read describe_entity attributeType; retry corrected values with a FRESH idempotencyKey.
            - idempotency_violation: same idempotencyKey, different call shape. Use a fresh idempotencyKey.
            - unknown_entity: entity is hidden or unknown. Call list_entities and retry with a valid name.
            - concurrent_modification: an identical operation is in-flight or commit outcome is unknown. Call get_record/find_records to verify state; do NOT retry automatically.

            STRICTNESS + EXAMPLES:
            CORRECT: create_record("sample_Customer", {"name":"Alice","email":"alice@example.com","region":"7f3c..."}, "f1a2b3c4-...")
            INCORRECT: create_record("Customer", ...)  (label, not internal name)
            INCORRECT: create_record("sample_Customer", {"name":"Alice"}, null)  (idempotencyKey required)
            INCORRECT: change attributes while reusing the same idempotencyKey  (idempotency_violation)
            """)
    public String createRecord(
            @ToolParam(description = "Exact entity name from list_entities") String entityName,
            @ToolParam(description = "Attribute name -> value object; FK relationships by UUID string") Map<String, Object> attributes,
            @ToolParam(description = "UUID for this exact call shape; use a fresh key if values change") String idempotencyKey) {

        long startedAt = System.currentTimeMillis();
        MetaClass metaClass = null;
        String userUsername = currentAuthentication.getUser().getUsername();
        Map<String, Object> safeAttributes = attributes == null ? Map.of() : attributes;
        MutationIntentRepository.ReservationResult reservation = null;
        MutationCommitState commitState = MutationCommitState.NO_HOST_WRITE;

        try {
            mutationAuthorizationService.enforceMutationRole(AiAgentMutationRole.CODE);

            // Resolve entity (Phase 10 R4 unknown-entity opacity preserved + create-side gate).
            metaClass = toolEntityResolver.resolveCreatableEntityOrThrow(entityName);

            // Jmix per-CRUD + per-attribute checks BEFORE reservation/save.
            mutationAuthorizationService.enforceCreatePermission(metaClass);
            mutationAuthorizationService.enforceAttributeWriteAccess(metaClass, safeAttributes.keySet());

            // Canonical raw-call-shape request hash + pre-save reservation/replay.
            String requestHash = mutationRequestHasher.hash(
                    "create_record", entityName, null, null, null, safeAttributes);
            reservation = mutationIntentRepository.reserveOrReplay(
                    "create_record", idempotencyKey, userUsername, RunContext.getConversationId(),
                    requestHash, mutationProperties.resolvedIdempotencyTtl());
            if (reservation.state() != MutationIntentRepository.ReservationState.RESERVED) {
                return mutationCommitCoordinator.handleReservationResult(
                        reservation, "create_record", startedAt, userUsername,
                        diffSerializer.serializeEntityArgumentsJson(entityName, null, safeAttributes, idempotencyKey));
            }

            // Coerce + validate BEFORE the guard so guards see typed values.
            Map<String, Object> coercedAttributes = mutationAttributeBinder.coerceAttributes(metaClass, safeAttributes);

            // Host MutationGuard SPI veto point.
            mutationGuard.check(new MutationIntent(
                    "create_record", metaClass, null, coercedAttributes));

            // Build entity in-memory; SAVE via separate @Component (proxy crossed -> real @Transactional).
            Object entity = dataManager.create(metaClass.getJavaClass());
            Map<String, Object> postImage = mutationAttributeBinder.applyAttributes(metaClass, entity, coercedAttributes);
            Object saved = mutationSaveExecutor.save(entity);
            commitState = MutationCommitState.HOST_SAVE_RETURNED;
            UUID savedId = mutationAttributeBinder.requireUuidId(EntityValues.getId(saved));
            String instanceName = metadataTools.getInstanceName(saved);

            String argumentsJson = diffSerializer.serializeEntityArgumentsJson(
                    entityName, null, safeAttributes, idempotencyKey);
            String diffJson = diffSerializer.serializeCreatePostImage(postImage);

            // Finalize idempotency BEFORE externally reporting success.
            mutationIntentRepository.markCommitted(reservation.intent(), savedId, metaClass.getName());
            commitState = MutationCommitState.INTENT_COMMITTED;

            mutationCommitCoordinator.safeWriteAudit("create_record", argumentsJson, diffJson,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.SUCCESS, null, null, userUsername);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outcome", AiToolCallOutcome.SUCCESS.getId());
            result.put("entityId", savedId);
            result.put("instanceName", instanceName);
            return toolResultFormatter.toJson(result);

        } catch (ToolVetoedException ve) {
            ToolUserError translated = mutationErrorTranslator.translate(new ToolUserError("access_denied",
                    "operation blocked by host policy",
                    List.of("do not retry; surface to user")), "create_record", metaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            // P-22 / AUD-07: do NOT echo or audit ve.getMessage(); host veto text may carry PII.
            mutationCommitCoordinator.safeWriteAudit("create_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, null, safeAttributes, idempotencyKey),
                    null,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.BLOCKED, "mutation_guard_vetoed", ve.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (ToolUserError tue) {
            ToolUserError translated = mutationErrorTranslator.translate(tue, "create_record", metaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteAudit("create_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, null, safeAttributes, idempotencyKey),
                    null,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.ERROR, null, tue.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (Throwable t) {
            ToolUserError translated = mutationCommitCoordinator
                    .translateThrowableAfterReservation(t, commitState, "create_record", metaClass);
            AiToolCallOutcome outcome = mutationCommitCoordinator.auditOutcome(t, commitState);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteAudit("create_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, null, safeAttributes, idempotencyKey),
                    null,
                    System.currentTimeMillis() - startedAt,
                    outcome, null, t.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        }
    }

    // ----------------------------------------------------------------------
    // update_record
    // ----------------------------------------------------------------------

    @Tool(name = "update_record", description = """
            MANDATORY WORKFLOW:
            1. Call describe_entity for the attribute types you intend to change.
            2. Call get_record (or find_records) FIRST to read current values; this lets you avoid overwriting values the user did not ask to change.
            3. Generate a UUID idempotencyKey for this exact update call. Reuse it only for an exact retry of the same call shape.
            4. Pass ONLY the attributes you intend to change.

            INPUT CONTRACT:
            - entityName: exact internal name.
            - id: 36-char UUID of the existing entity.
            - attributes: ONLY the keys to change.
            - idempotencyKey: UUID string. Same key + same call shape -> IDEMPOTENT_REPLAY. Same key + changed attributes -> idempotency_violation.

            PARAMETER FORMATS:
            - Same as create_record. Date/time ISO 8601, enums by stable id.

            ERROR HANDLING:
            - not_found: id is a valid UUID but no row exists. Surface to user.
            - parameter_conversion_error: id was not parseable as UUID. Fix client side, retry.
            - concurrent_modification: this save detected an optimistic-lock conflict, an identical operation is in-flight, or commit outcome is unknown. Re-read with get_record/find_records; if the result says commit outcome is unknown, do NOT retry automatically.
            - access_denied / validation_failed / idempotency_violation: same as create_record.

            STRICTNESS + EXAMPLES:
            CORRECT: update_record("sample_Order", "9b2f...", {"status":"SHIPPED"}, "uuid-...")
            INCORRECT: include unchanged attributes (wastes diff)
            INCORRECT: change attributes while reusing the same idempotencyKey (idempotency_violation)
            """)
    public String updateRecord(
            @ToolParam(description = "Exact entity name from list_entities") String entityName,
            @ToolParam(description = "Existing entity UUID (hyphenated 36-char)") String id,
            @ToolParam(description = "Attribute name -> new value; ONLY changed attributes") Map<String, Object> attributes,
            @ToolParam(description = "UUID for this exact call shape; use a fresh key if values change") String idempotencyKey) {

        long startedAt = System.currentTimeMillis();
        MetaClass metaClass = null;
        String userUsername = currentAuthentication.getUser().getUsername();
        Map<String, Object> safeAttributes = attributes == null ? Map.of() : attributes;
        MutationIntentRepository.ReservationResult reservation = null;
        MutationCommitState commitState = MutationCommitState.NO_HOST_WRITE;

        try {
            mutationAuthorizationService.enforceMutationRole(AiAgentMutationRole.CODE);

            // Resolve entity update-side. Phase 10 R4 opacity for unknown; access_denied for visible-but-update-denied.
            metaClass = toolEntityResolver.resolveUpdatableEntityOrThrow(entityName);

            // id parse — distinguishes parameter_conversion_error vs not_found.
            UUID parsedId = mutationAttributeBinder.requireUuidId(
                    toolEntityResolver.parseEntityId(id, metaClass));

            // Jmix per-CRUD + per-attribute checks.
            mutationAuthorizationService.enforceUpdatePermission(metaClass);
            mutationAuthorizationService.enforceAttributeWriteAccess(metaClass, safeAttributes.keySet());

            // Canonical raw-call-shape request hash + pre-save reservation/replay.
            String requestHash = mutationRequestHasher.hash(
                    "update_record", entityName, id, null, null, safeAttributes);
            reservation = mutationIntentRepository.reserveOrReplay(
                    "update_record", idempotencyKey, userUsername, RunContext.getConversationId(),
                    requestHash, mutationProperties.resolvedIdempotencyTtl());
            if (reservation.state() != MutationIntentRepository.ReservationState.RESERVED) {
                return mutationCommitCoordinator.handleReservationResult(
                        reservation, "update_record", startedAt, userUsername,
                        diffSerializer.serializeEntityArgumentsJson(entityName, id, safeAttributes, idempotencyKey));
            }

            // Coerce + validate BEFORE the guard so guards see typed values.
            Map<String, Object> coercedAttributes = mutationAttributeBinder.coerceAttributes(metaClass, safeAttributes);

            // Host MutationGuard SPI veto point.
            mutationGuard.check(new MutationIntent(
                    "update_record", metaClass, parsedId, coercedAttributes));

            // Load existing by id; no explicit fetch plan keeps the graph minimal.
            final MetaClass loadedMetaClass = metaClass;
            final String idForError = id;
            Object existingEntity = dataManager.load(metaClass.getJavaClass())
                    .id(parsedId)
                    .optional()
                    .orElseThrow(() -> mutationErrorTranslator.notFound(loadedMetaClass, idForError));

            // Capture pre-image for the attribute keys the caller is changing.
            Map<String, Object> preImage = mutationAttributeBinder.capturePreImage(
                    existingEntity, coercedAttributes.keySet());

            // Apply attributes.
            Map<String, Object> postImage = mutationAttributeBinder.applyAttributes(
                    metaClass, existingEntity, coercedAttributes);

            // SAVE via separate @Component (proxy crossed -> real @Transactional).
            Object saved = mutationSaveExecutor.save(existingEntity);
            commitState = MutationCommitState.HOST_SAVE_RETURNED;
            UUID savedId = mutationAttributeBinder.requireUuidId(EntityValues.getId(saved));
            String instanceName = metadataTools.getInstanceName(saved);

            String argumentsJson = diffSerializer.serializeEntityArgumentsJson(
                    entityName, id, safeAttributes, idempotencyKey);
            String diffJson = diffSerializer.serializeUpdateDiff(preImage, postImage);

            // Finalize idempotency BEFORE externally reporting success.
            mutationIntentRepository.markCommitted(reservation.intent(), savedId, metaClass.getName());
            commitState = MutationCommitState.INTENT_COMMITTED;

            mutationCommitCoordinator.safeWriteAudit("update_record", argumentsJson, diffJson,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.SUCCESS, null, null, userUsername);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outcome", AiToolCallOutcome.SUCCESS.getId());
            result.put("entityId", savedId);
            result.put("instanceName", instanceName);
            result.put("diffSummary", diffJson);
            return toolResultFormatter.toJson(result);

        } catch (ToolVetoedException ve) {
            ToolUserError translated = mutationErrorTranslator.translate(new ToolUserError("access_denied",
                    "operation blocked by host policy",
                    List.of("do not retry; surface to user")), "update_record", metaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteAudit("update_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, id, safeAttributes, idempotencyKey),
                    null,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.BLOCKED, "mutation_guard_vetoed", ve.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (ToolUserError tue) {
            ToolUserError translated = mutationErrorTranslator.translate(tue, "update_record", metaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteAudit("update_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, id, safeAttributes, idempotencyKey),
                    null,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.ERROR, null, tue.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (Throwable t) {
            ToolUserError translated = mutationCommitCoordinator
                    .translateThrowableAfterReservation(t, commitState, "update_record", metaClass);
            AiToolCallOutcome outcome = mutationCommitCoordinator.auditOutcome(t, commitState);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteAudit("update_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, id, safeAttributes, idempotencyKey),
                    null,
                    System.currentTimeMillis() - startedAt,
                    outcome, null, t.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        }
    }
}
