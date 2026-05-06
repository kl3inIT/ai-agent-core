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
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.EntitySet;
import io.jmix.core.FetchPlan;
import io.jmix.core.MetadataTools;
import io.jmix.core.SaveContext;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.entity.EntityValues;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LLM-mediated mutation tool surface (Phase 11). Conditional via
 * {@code @ConditionalOnProperty(prefix="ai-agent.tools.mutation", name="enabled",
 * havingValue="true")} — default OFF. When the bean is absent, no mutation callbacks are
 * registered with {@code AgentToolCallbacks.forCurrentUser} (TEST-13 boot assertion).
 *
 * <p><b>Plan 11-07A/B scope:</b> exposes {@code create_record}, {@code update_record},
 * {@code add_related_record}, and {@code remove_related_record}. {@code delete_record}
 * is NOT shipped under any flag combination in v1.1 (D-07). Plan 11-07B added the related
 * writes which delegate ALL relationship-metadata interpretation to
 * {@link RelatedWriteMetadataResolver}; this class never reads JPA annotations or walks the
 * inverse pointer directly. Related writes mutate ONLY the verified child-side inverse and
 * save through {@link MutationSaveExecutor#saveAll}; they never rewrite parent collections
 * and never call delete (D-07).
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

    public BuiltInMutationTools(ToolEntityResolver toolEntityResolver,
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
                                AccessManager accessManager) {
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
    }

    // ----------------------------------------------------------------------
    // create_record
    // ----------------------------------------------------------------------

    @Tool(name = "create_record", description = """
            MANDATORY WORKFLOW:
            1. Call list_entities to confirm the entity name exists and is visible.
            2. Call describe_entity to learn the mandatory attributes and their types.
            3. Generate a fresh random UUID v4 idempotencyKey for this exact logical create call. Reuse it only for an exact retry of the same call shape.
               UUID v4 means the first character of the third group is '4' and the first character of the fourth group is one of '8', '9', 'a', or 'b'.
               Never copy UUID-looking values from examples, previous tool calls, or prior messages for a new operation.
            4. Pass attributes as a JSON object whose keys are the entity attribute names.
            5. Reference to-one relationships by foreign-key UUID strings inside the same object.
               Do not assign collection relationships through attributes.
            6. After outcome SUCCESS or IDEMPOTENT_REPLAY, immediately call generate_entity_detail_link with this same entityName and the returned entityId before replying to the user. If link generation returns unknown_entity, say the record was saved but no detail link is available.

            INPUT CONTRACT:
            - entityName: exact internal name from list_entities, NEVER a label.
            - attributes: object with writable attribute-name keys; omit primary key, version,
              audit/system fields, read-only fields, transient/calculated fields, and collection relationships.
              Values must match the types from describe_entity.
            - idempotencyKey: UUID v4 string. Same key + same call shape -> IDEMPOTENT_REPLAY. Same key + changed attributes -> idempotency_violation.

            PARAMETER FORMATS:
            - String attributes: plain JSON string.
            - Numeric: JSON number.
            - Boolean: JSON boolean.
            - UUID/relationship: hyphenated 36-char UUID string.
            - Date/time: ISO 8601 string (see describe_entity attributeType).
            - Enum: use enumValues[].id from describe_entity. enumValues[].name is accepted as a fallback. Never use the localized label.

            ERROR HANDLING:
            - access_denied: per-CRUD or per-attribute denial. Do NOT retry; surface to user.
            - validation_failed: required attribute missing or constraint violated. Re-read describe_entity; if you change values, retry with a FRESH idempotencyKey.
            - parameter_conversion_error: a value didn't parse as the expected type. Re-read describe_entity attributeType; retry corrected values with a FRESH idempotencyKey.
            - idempotency_violation: same idempotencyKey, different call shape. Use a fresh idempotencyKey.
            - unknown_entity: entity is hidden or unknown. Call list_entities once and retry only with an exact returned name.
            - concurrent_modification: an identical operation is in-flight or commit outcome is unknown. Call get_record/find_records to verify state; do NOT retry automatically.

            STRICTNESS + EXAMPLES:
            CORRECT SHAPE: create_record("<entity-name-from-list_entities>", {"<writable-attribute>":"<value>"}, "<fresh-random-uuid-v4>")
            INCORRECT: create_record("<display-label>", ...)  (label, not internal name)
            INCORRECT: create_record("<entity-name-from-list_entities>", {"id":"<uuid>"}, "<fresh-random-uuid-v4>")  (primary key is not writable)
            INCORRECT: create_record("<entity-name-from-list_entities>", {"<attribute>":"<value>"}, null)  (idempotencyKey required)
            INCORRECT: change attributes while reusing the same idempotencyKey  (idempotency_violation)
            """)
    public String createRecord(
            @ToolParam(description = "Exact entity name from list_entities") String entityName,
            @ToolParam(description = "Attribute name -> value object; FK relationships by UUID string") Map<String, Object> attributes,
            @ToolParam(description = "Fresh random UUID v4 for this exact call shape; use a fresh key if values change") String idempotencyKey) {

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
            mutationCommitCoordinator.safeWriteErrorAudit("create_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, null, safeAttributes, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.BLOCKED, "mutation_guard_vetoed", ve.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (ToolUserError tue) {
            ToolUserError translated = mutationErrorTranslator.translate(tue, "create_record", metaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteErrorAudit("create_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, null, safeAttributes, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.ERROR, null, tue.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (Throwable t) {
            ToolUserError translated = mutationCommitCoordinator
                    .translateThrowableAfterReservation(t, commitState, "create_record", metaClass);
            AiToolCallOutcome outcome = mutationCommitCoordinator.auditOutcome(commitState);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.logUnexpectedThrowable("create_record", t, translated, outcome, commitState);
            mutationCommitCoordinator.safeWriteErrorAudit("create_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, null, safeAttributes, idempotencyKey),
                    translated,
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
            3. Generate a fresh random UUID v4 idempotencyKey for this exact update call. Reuse it only for an exact retry of the same call shape.
               UUID v4 means the first character of the third group is '4' and the first character of the fourth group is one of '8', '9', 'a', or 'b'.
               Never copy UUID-looking values from examples, previous tool calls, or prior messages for a new operation.
            4. Pass ONLY the attributes you intend to change.
            5. After outcome SUCCESS or IDEMPOTENT_REPLAY, immediately call generate_entity_detail_link with this same entityName and the returned entityId before replying to the user. If link generation returns unknown_entity, say the record was saved but no detail link is available.

            INPUT CONTRACT:
            - entityName: exact internal name from list_entities.
            - id: 36-char UUID of the existing entity.
            - attributes: ONLY writable keys to change; omit unchanged values, primary key, version,
              audit/system fields, read-only fields, transient/calculated fields, and collection relationships.
            - idempotencyKey: UUID v4 string. Same key + same call shape -> IDEMPOTENT_REPLAY. Same key + changed attributes -> idempotency_violation.

            PARAMETER FORMATS:
            - Same as create_record. Date/time ISO 8601, enums by enumValues[].id.

            ERROR HANDLING:
            - not_found: id is a valid UUID but no row exists. Surface to user.
            - parameter_conversion_error: id was not parseable as UUID. Fix client side, retry.
            - concurrent_modification: this save detected an optimistic-lock conflict, an identical operation is in-flight, or commit outcome is unknown. Re-read with get_record/find_records; if the result says commit outcome is unknown, do NOT retry automatically.
            - access_denied / validation_failed / idempotency_violation: same as create_record.

            STRICTNESS + EXAMPLES:
            CORRECT SHAPE: update_record("<entity-name-from-list_entities>", "<existing-record-uuid>", {"<writable-attribute>":"<new-value>"}, "<fresh-random-uuid-v4>")
            INCORRECT: include unchanged attributes (wastes diff)
            INCORRECT: change attributes while reusing the same idempotencyKey (idempotency_violation)
            """)
    public String updateRecord(
            @ToolParam(description = "Exact entity name from list_entities") String entityName,
            @ToolParam(description = "Existing entity UUID (hyphenated 36-char)") String id,
            @ToolParam(description = "Attribute name -> new value; ONLY changed attributes") Map<String, Object> attributes,
            @ToolParam(description = "Fresh random UUID v4 for this exact call shape; use a fresh key if values change") String idempotencyKey) {

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
            mutationCommitCoordinator.safeWriteErrorAudit("update_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, id, safeAttributes, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.BLOCKED, "mutation_guard_vetoed", ve.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (ToolUserError tue) {
            ToolUserError translated = mutationErrorTranslator.translate(tue, "update_record", metaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteErrorAudit("update_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, id, safeAttributes, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.ERROR, null, tue.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (Throwable t) {
            ToolUserError translated = mutationCommitCoordinator
                    .translateThrowableAfterReservation(t, commitState, "update_record", metaClass);
            AiToolCallOutcome outcome = mutationCommitCoordinator.auditOutcome(commitState);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.logUnexpectedThrowable("update_record", t, translated, outcome, commitState);
            mutationCommitCoordinator.safeWriteErrorAudit("update_record",
                    diffSerializer.serializeEntityArgumentsJson(entityName, id, safeAttributes, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    outcome, null, t.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        }
    }

    // ----------------------------------------------------------------------
    // add_related_record
    // ----------------------------------------------------------------------

    @Tool(name = "add_related_record", description = """
            MANDATORY WORKFLOW:
            1. Call describe_entity on the parent entity to choose the relationship name.
               The tool itself validates support: v1.1 supports only non-composition parent
               @OneToMany(mappedBy) with a child-side @ManyToOne or @OneToOne inverse.
               Composition, many-to-many, unidirectional, and orphanRemoval/delete-capable
               relationships return validation_failed.
            2. Verify both the parent record (id) and the related child record (relatedId) already exist via get_record or find_records. This tool does NOT create the related record; it only links an existing related record.
            3. Verify the child is currently unlinked or already linked to this same parent.
               This tool does not reparent a child from one parent to another.
            4. Generate a fresh random UUID v4 idempotencyKey for this exact link call.
               UUID v4 means the first character of the third group is '4' and the first character of the fourth group is one of '8', '9', 'a', or 'b'.
               Never copy UUID-looking values from examples, previous tool calls, or prior messages for a new operation.

            INPUT CONTRACT:
            - entityName: exact internal name of the PARENT entity from list_entities, NEVER a label.
            - id: 36-char UUID of the PARENT entity.
            - relationship: exact parent-side relationship attribute name; must be the supported parent collection.
            - relatedId: 36-char UUID of the EXISTING child to link. The child must already be readable by the agent and must not belong to another parent.
            - idempotencyKey: UUID v4. Same key + same call shape -> IDEMPOTENT_REPLAY. Same key + changed shape -> idempotency_violation.

            PARAMETER FORMATS:
            - All ids: hyphenated 36-char UUID strings.
            - relationship: exact attribute name from describe_entity, NEVER a label.

            ERROR HANDLING:
            - access_denied: parent update, parent relationship modify, child read, child update, or child inverse modify denied. Do NOT retry; surface to user.
            - validation_failed: relationship not supported (composition, many-to-many, unidirectional, orphanRemoval/delete-capable, or ambiguous metadata), child already belongs to another parent, OR JPA constraint violated. Re-read describe_entity; do not reuse unsupported relationships.
            - parameter_conversion_error: id or relatedId did not parse as UUID. Fix client side, retry with a fresh idempotencyKey.
            - not_found: id or relatedId is a valid UUID but no row exists. Surface to user.
            - idempotency_violation: same idempotencyKey, different call shape. Use a fresh idempotencyKey.
            - unknown_entity: parent entity is hidden or unknown.
            - concurrent_modification: an identical operation is in-flight or commit outcome is unknown. Call get_record to verify current link state; do NOT retry automatically.

            STRICTNESS + EXAMPLES:
            CORRECT SHAPE: add_related_record("<parent-entity-name-from-list_entities>", "<existing-parent-uuid>", "<relationship-attribute>", "<existing-unlinked-child-uuid>", "<fresh-random-uuid-v4>")
            INCORRECT: add_related_record on a @Composition parent collection  (validation_failed)
            INCORRECT: add_related_record with a relatedId that does not yet exist  (use create_record first)
            INCORRECT: add_related_record with a child currently linked to a different parent  (validation_failed)
            INCORRECT: change relationship or relatedId while reusing the same idempotencyKey  (idempotency_violation)
            """)
    public String addRelatedRecord(
            @ToolParam(description = "Exact parent entity name from list_entities") String entityName,
            @ToolParam(description = "Existing parent entity UUID (hyphenated 36-char)") String id,
            @ToolParam(description = "Parent-side relationship attribute name from describe_entity") String relationship,
            @ToolParam(description = "Existing child entity UUID to link (hyphenated 36-char)") String relatedId,
            @ToolParam(description = "Fresh random UUID v4 for this exact call shape; use a fresh key if values change") String idempotencyKey) {

        return executeRelatedWrite(
                "add_related_record", entityName, id, relationship, relatedId, idempotencyKey, false);
    }

    // ----------------------------------------------------------------------
    // remove_related_record
    // ----------------------------------------------------------------------

    @Tool(name = "remove_related_record", description = """
            MANDATORY WORKFLOW:
            1. Call describe_entity on the parent entity to confirm the relationship is supported (non-composition parent @OneToMany(mappedBy) with a child-side @ManyToOne or @OneToOne inverse). v1.1 unlinks ONLY by clearing the child-side foreign key; it NEVER deletes the child row. orphanRemoval, composition, required-inverse (NOT NULL FK), many-to-many, and unidirectional relationships return validation_failed.
            2. Verify the child currently belongs to the parent via get_record or find_records. If the child is not currently linked to this parent, this tool returns not_found.
            3. Generate a fresh random UUID v4 idempotencyKey for this exact unlink call.
               UUID v4 means the first character of the third group is '4' and the first character of the fourth group is one of '8', '9', 'a', or 'b'.
               Never copy UUID-looking values from examples, previous tool calls, or prior messages for a new operation.

            INPUT CONTRACT:
            - entityName: exact internal name of the PARENT entity from list_entities, NEVER a label.
            - id: 36-char UUID of the PARENT entity.
            - relationship: exact parent-side relationship attribute name; must be the supported parent collection.
            - relatedId: 36-char UUID of the child currently linked to the parent.
            - idempotencyKey: UUID v4. Same key + same call shape -> IDEMPOTENT_REPLAY. Same key + changed shape -> idempotency_violation.

            PARAMETER FORMATS:
            - All ids: hyphenated 36-char UUID strings.
            - relationship: exact attribute name from describe_entity, NEVER a label.

            ERROR HANDLING:
            - access_denied: parent update, parent relationship modify, child read, child update, or child inverse modify denied. Do NOT retry; surface to user.
            - validation_failed: relationship not supported (composition, many-to-many, unidirectional, orphanRemoval, required/NOT-NULL inverse, or ambiguous metadata). v1.1 cannot clear a not-null inverse without deleting the child, and v1.1 does not delete children.
            - parameter_conversion_error: id or relatedId did not parse as UUID. Fix client side, retry with a fresh idempotencyKey.
            - not_found: id or relatedId is a valid UUID but no row exists, OR the child is not currently linked to this parent. Call get_record to verify the link state.
            - idempotency_violation: same idempotencyKey, different call shape. Use a fresh idempotencyKey.
            - unknown_entity: parent entity is hidden or unknown.
            - concurrent_modification: an identical operation is in-flight or commit outcome is unknown. Call get_record to verify current link state; do NOT retry automatically.

            STRICTNESS + EXAMPLES:
            CORRECT SHAPE: remove_related_record("<parent-entity-name-from-list_entities>", "<existing-parent-uuid>", "<relationship-attribute>", "<currently-linked-child-uuid>", "<fresh-random-uuid-v4>")
            INCORRECT: remove_related_record on a @Composition parent collection  (validation_failed)
            INCORRECT: remove_related_record on a relationship whose child FK is NOT NULL  (validation_failed; v1.1 cannot clear)
            INCORRECT: change relationship or relatedId while reusing the same idempotencyKey  (idempotency_violation)
            """)
    public String removeRelatedRecord(
            @ToolParam(description = "Exact parent entity name from list_entities") String entityName,
            @ToolParam(description = "Existing parent entity UUID (hyphenated 36-char)") String id,
            @ToolParam(description = "Parent-side relationship attribute name from describe_entity") String relationship,
            @ToolParam(description = "Currently-linked child entity UUID (hyphenated 36-char)") String relatedId,
            @ToolParam(description = "Fresh random UUID v4 for this exact call shape; use a fresh key if values change") String idempotencyKey) {

        return executeRelatedWrite(
                "remove_related_record", entityName, id, relationship, relatedId, idempotencyKey, true);
    }

    // ----------------------------------------------------------------------
    // shared related-write orchestration
    // ----------------------------------------------------------------------

    /**
     * Shared orchestration body for {@code add_related_record} and {@code remove_related_record}.
     *
     * <p>Gate sequence (fail-closed before host save):
     * <ol>
     *     <li>{@link AiAgentMutationRole#CODE} marker-role authority (exact equality).</li>
     *     <li>Parent entity update-side resolution (LLM exposure + visible-but-denied → access_denied).</li>
     *     <li>Parent id parse (parameter_conversion_error vs not_found).</li>
     *     <li>Parent CRUD update + parent relationship attribute {@code canModify}.</li>
     *     <li>{@link RelatedWriteMetadataResolver#resolveSupportedRelatedWriteRelationship} —
     *         narrow v1.1 support matrix: non-composition parent {@code @OneToMany(mappedBy)} +
     *         child-side single-valued {@code @ManyToOne}/{@code @OneToOne} inverse only.</li>
     *     <li>Child LLM read+modify exposure + Jmix child read+update + child inverse attribute
     *         {@code canModify}.</li>
     *     <li>Child id parse (parameter_conversion_error vs not_found).</li>
     *     <li>For remove: {@link RelatedWriteMetadataResolver#ensureInverseClearable} (validation_failed
     *         when inverse is required / not-null).</li>
     *     <li>Idempotency reserve/replay; on RESERVED: load parent (children-free fetch plan,
     *         Pitfall #1) and child, MutationGuard veto point, mutate the child-side inverse only
     *         (never rewrite parent collection), saveAll via {@link MutationSaveExecutor#saveAll},
     *         finalize idempotency, audit success.</li>
     * </ol>
     *
     * <p><b>Pitfall #1 — children-free parent fetch plan:</b> the parent is loaded with
     * {@link FetchPlan#BASE} so its {@code @OneToMany} children are NOT pulled into the persistence
     * context. Loading children would re-save them with bumped {@code @Version}, which is exactly
     * what {@code AuditWriter} hit upstream. Mutating only the child-side inverse keeps the change
     * narrow and avoids that footgun.
     *
     * <p><b>P-22:</b> No path here concatenates {@code relationship}, {@code id}, or
     * {@code relatedId} into result/error prose; audit payloads flow through {@link DiffSerializer}.
     */
    private String executeRelatedWrite(String toolName,
                                       String entityName,
                                       String id,
                                       String relationship,
                                       String relatedId,
                                       String idempotencyKey,
                                       boolean isRemove) {

        long startedAt = System.currentTimeMillis();
        MetaClass parentMetaClass = null;
        String userUsername = currentAuthentication.getUser().getUsername();
        MutationIntentRepository.ReservationResult reservation = null;
        MutationCommitState commitState = MutationCommitState.NO_HOST_WRITE;

        try {
            mutationAuthorizationService.enforceMutationRole(AiAgentMutationRole.CODE);

            // Resolve parent entity (Phase 10 R4 unknown-entity opacity preserved + update-side gate).
            parentMetaClass = toolEntityResolver.resolveUpdatableEntityOrThrow(entityName);

            // Parent id parse — distinguishes parameter_conversion_error vs not_found.
            UUID parsedParentId = mutationAttributeBinder.requireUuidId(
                    toolEntityResolver.parseEntityId(id, parentMetaClass));

            // Jmix per-CRUD update on parent + per-attribute modify on the relationship.
            mutationAuthorizationService.enforceUpdatePermission(parentMetaClass);
            mutationAuthorizationService.enforceAttributeWriteAccess(
                    parentMetaClass, java.util.Set.of(relationship));

            // Verified relationship-metadata authority — narrow v1.1 support matrix.
            RelatedWriteMetadataResolver.SupportedRelatedRelationship supported =
                    relatedWriteMetadataResolver.resolveSupportedRelatedWriteRelationship(
                            parentMetaClass, relationship);
            MetaClass childMetaClass = supported.childMetaClass();

            // Child-side LLM exposure read+modify (related writes mutate the child inverse).
            mutationAuthorizationService.enforceLlmRelationshipTargetExposure(childMetaClass, true);
            // Jmix child read+update + child-side inverse attribute canModify.
            mutationAuthorizationService.enforceReadPermission(childMetaClass);
            mutationAuthorizationService.enforceUpdatePermission(childMetaClass);
            mutationAuthorizationService.enforceAttributeWriteAccess(
                    childMetaClass, java.util.Set.of(supported.childInverseProperty().getName()));

            // Child id parse (parameter_conversion_error vs not_found).
            UUID parsedRelatedId = mutationAttributeBinder.requireUuidId(
                    toolEntityResolver.parseEntityId(relatedId, childMetaClass));

            // remove-time required-inverse check (validation_failed before reservation).
            if (isRemove) {
                relatedWriteMetadataResolver.ensureInverseClearable(supported);
            }

            // Canonical raw-call-shape request hash + pre-save reservation/replay.
            String requestHash = mutationRequestHasher.hash(
                    toolName, entityName, id, relationship, relatedId, java.util.Map.of());
            reservation = mutationIntentRepository.reserveOrReplay(
                    toolName, idempotencyKey, userUsername, RunContext.getConversationId(),
                    requestHash, mutationProperties.resolvedIdempotencyTtl());
            if (reservation.state() != MutationIntentRepository.ReservationState.RESERVED) {
                return mutationCommitCoordinator.handleReservationResult(
                        reservation, toolName, startedAt, userUsername,
                        diffSerializer.serializeRelatedArgumentsJson(
                                entityName, id, relationship, relatedId, idempotencyKey));
            }

            // Pitfall #1 — load parent with the children-free FetchPlan.BASE; never pull the
            // @OneToMany collection into the persistence context (would re-save children with
            // bumped @Version). Related writes mutate ONLY the child-side inverse.
            final MetaClass parentMetaClassFinal = parentMetaClass;
            Object parent = dataManager.load(parentMetaClass.getJavaClass())
                    .id(parsedParentId)
                    .fetchPlan(FetchPlan.BASE)
                    .optional()
                    .orElseThrow(() -> mutationErrorTranslator.notFound(parentMetaClassFinal, id));

            // Load child by id; the child carries the FK so the child is what we save.
            final MetaClass childMetaClassFinal = childMetaClass;
            final String relatedIdForError = relatedId;
            Object child = dataManager.load(childMetaClass.getJavaClass())
                    .id(parsedRelatedId)
                    .optional()
                    .orElseThrow(() -> mutationErrorTranslator.notFound(childMetaClassFinal, relatedIdForError));

            if (!isRemove && relatedWriteMetadataResolver.childBelongsToDifferentParent(supported, parent, child)) {
                throw new ToolUserError("validation_failed",
                        "child is already linked to another parent",
                        List.of("call get_record to verify the child relationship before retrying"));
            }

            // Host MutationGuard SPI veto point — guards see typed parent/child via attributes.
            // attributes map carries the loaded entity references (not raw ids) per D-03 contract.
            java.util.Map<String, Object> guardAttributes = new LinkedHashMap<>();
            guardAttributes.put(relationship, child);
            mutationGuard.check(new MutationIntent(
                    toolName, parentMetaClass, parsedParentId, java.util.Map.copyOf(guardAttributes)));

            // Mutate ONLY the child-side inverse — never rewrite the parent collection.
            String action;
            if (isRemove) {
                // remove: child must currently belong to this parent; otherwise not_found
                // (treat non-member like missing-row so the LLM re-reads via get_record).
                if (!relatedWriteMetadataResolver.childBelongsToParent(supported, parent, child)) {
                    throw mutationErrorTranslator.notFound(childMetaClass, relatedId);
                }
                relatedWriteMetadataResolver.clearInverseReference(supported, child);
                action = "removed";
            } else {
                relatedWriteMetadataResolver.wireInverseReference(supported, parent, child);
                action = "added";
            }

            // saveAll within MutationSaveExecutor's @Transactional boundary (proxy-crossed).
            // Saving both parent and child keeps optimistic-lock parity with the parent.
            mutationSaveExecutor.saveAll(parent, child);
            commitState = MutationCommitState.HOST_SAVE_RETURNED;

            String argumentsJson = diffSerializer.serializeRelatedArgumentsJson(
                    entityName, id, relationship, relatedId, idempotencyKey);
            String resultSummary = diffSerializer.serializeRelatedActionSummary(
                    relationship, action, relatedId);

            // Finalize idempotency BEFORE externally reporting success; persist parent id +
            // parent metaClass so replay re-resolves parent instanceName.
            mutationIntentRepository.markCommitted(reservation.intent(), parsedParentId, parentMetaClass.getName());
            commitState = MutationCommitState.INTENT_COMMITTED;

            mutationCommitCoordinator.safeWriteAudit(toolName, argumentsJson, resultSummary,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.SUCCESS, null, null, userUsername);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outcome", AiToolCallOutcome.SUCCESS.getId());
            result.put("parentId", parsedParentId);
            result.put("relationship", relationship);
            result.put("relatedId", parsedRelatedId);
            return toolResultFormatter.toJson(result);

        } catch (ToolVetoedException ve) {
            ToolUserError translated = mutationErrorTranslator.translate(new ToolUserError("access_denied",
                    "operation blocked by host policy",
                    List.of("do not retry; surface to user")), toolName, parentMetaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            // P-22 / AUD-07: do NOT echo or audit ve.getMessage().
            mutationCommitCoordinator.safeWriteErrorAudit(toolName,
                    diffSerializer.serializeRelatedArgumentsJson(
                            entityName, id, relationship, relatedId, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.BLOCKED, "mutation_guard_vetoed", ve.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (ToolUserError tue) {
            ToolUserError translated = mutationErrorTranslator.translate(tue, toolName, parentMetaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteErrorAudit(toolName,
                    diffSerializer.serializeRelatedArgumentsJson(
                            entityName, id, relationship, relatedId, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.ERROR, null, tue.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (Throwable t) {
            ToolUserError translated = mutationCommitCoordinator
                    .translateThrowableAfterReservation(t, commitState, toolName, parentMetaClass);
            AiToolCallOutcome outcome = mutationCommitCoordinator.auditOutcome(commitState);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.logUnexpectedThrowable(toolName, t, translated, outcome, commitState);
            mutationCommitCoordinator.safeWriteErrorAudit(toolName,
                    diffSerializer.serializeRelatedArgumentsJson(
                            entityName, id, relationship, relatedId, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    outcome, null, t.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        }
    }

    // ----------------------------------------------------------------------
    // bulk_save_records (Phase 13 D-02)
    // ----------------------------------------------------------------------

    @Tool(name = "bulk_save_records", description = """
            MANDATORY WORKFLOW:
            1. Call list_entities to confirm the entity name exists and is visible.
            2. Call describe_entity to learn writable attributes and their types.
            3. Echo to the user the row count + first 3 sample rows BEFORE invoking this tool.
            4. Generate ONE fresh random UUID v4 idempotencyKey for this entire batch.
               UUID v4 means the first character of the third group is '4' and the first character
               of the fourth group is one of '8', '9', 'a', or 'b'.
               Never copy UUID-looking values from examples, previous tool calls, or prior messages
               for a new operation.
            5. Call this tool with all rows in a single invocation.
            6. After SUCCESS, call generate_entity_detail_link for the first 3 saved ids.

            INPUT CONTRACT:
            - entityName: exact internal name from list_entities, NEVER a label.
            - records: array of objects. For each row:
                * include 'id' (UUID string) to UPDATE that row
                * omit 'id' to CREATE a new row
                * NEVER include 'id: null' — omit the key entirely
            - idempotencyKey: UUID v4. Same key + byte-identical canonical-JSON of records in the
              SAME submission order -> IDEMPOTENT_REPLAY (no new rows; original savedIds returned).
              Same key + ANY changed bytes -> idempotency_violation (generate a fresh key).

            FORMATS:
            - UUID: hyphenated 36-char string with v4 marker.
            - Dates: ISO-8601 (yyyy-MM-dd or yyyy-MM-ddTHH:mm:ss). NEVER dd/mm/yyyy or mm/dd/yyyy.
            - Decimals: dot separator (1234.56). NEVER comma decimal (1234,56) or thousand separators.
            - Booleans: true/false. NEVER 1/0 or 'có'/'không'.
            - To-one relationship: foreign-key UUID string in the same row object
              (call find_records first if needed).
            - Collections: do NOT assign through attributes; use add_related_record /
              remove_related_record instead.

            ERROR HANDLING:
            - Per-row failure rolls back the ENTIRE batch (zero rows persisted).
              Result carries: {outcome: ERROR | BLOCKED | COMMIT_FAILED, failedRowIndex: N,
              errorCode: <one-of-6-stable-codes>}.
            - Stable error codes: unknown_entity, access_denied, validation_failed,
              parameter_conversion_error, idempotency_violation, concurrent_modification.
            - Do NOT retry on access_denied or idempotency_violation — surface to the user.
            - For a corrected retry after a row-level failure, generate a FRESH idempotencyKey.

            STRICTNESS:
            - Use bulk_save_records ONLY when records >= 2 of the SAME entity. For one record,
              call create_record / update_record.
            - NEVER mix entity types in one batch — make separate calls.
            - NEVER include user-supplied text values inside argumentsJson — audit stores hashes only.
            - Echo the row count + 3 sample rows back to the user BEFORE calling.

            EXAMPLES:

            Example 1 — xlsx onboarding (3 new customers, all create):
                entityName="Customer"
                records=[
                  {"email":"a@x.com","fullName":"An Nguyen","phone":"+84901234567"},
                  {"email":"b@x.com","fullName":"Binh Tran","phone":"+84901234568"},
                  {"email":"c@x.com","fullName":"Cuong Le","phone":"+84901234569"}
                ]
                idempotencyKey="<fresh UUID v4>"
                Result on SUCCESS: {"outcome":"SUCCESS","count":3,"savedIds":[...]}

            Example 2 — PDF-driven mixed batch (1 update, 2 create):
                entityName="Customer"
                records=[
                  {"id":"e7f3...","phone":"+84901111111"},
                  {"email":"d@x.com","fullName":"Dat Pham","phone":"..."},
                  {"email":"e@x.com","fullName":"Em Vu","phone":"..."}
                ]
                idempotencyKey="<fresh UUID v4>"
                Result on row-2 validation failure:
                {"outcome":"ERROR","failedRowIndex":1,"errorCode":"validation_failed"}
                — entire batch rolled back; row 0 update NOT applied.
            """)
    public String bulkSaveRecords(
            @ToolParam(description = "Exact internal entity name from list_entities (e.g. 'Customer'). NEVER a label.")
            String entityName,
            @ToolParam(description = "Array of record objects. Each object's keys are entity attribute names. "
                    + "Include 'id' (UUID string) to UPDATE that row; OMIT 'id' to CREATE a new row. "
                    + "NEVER include 'id: null' — omit the key entirely.")
            List<Map<String, Object>> records,
            @ToolParam(description = "UUID v4 idempotency key. Same key + byte-identical canonical-JSON of records "
                    + "in the SAME submission order returns IDEMPOTENT_REPLAY (no new rows). "
                    + "Same key + ANY changed bytes returns idempotency_violation — generate a fresh key.")
            String idempotencyKey) {

        long startedAt = System.currentTimeMillis();
        String userUsername = currentAuthentication.getUser().getUsername();
        MetaClass metaClass = null;
        MutationIntentRepository.ReservationResult reservation = null;
        MutationCommitState commitState = MutationCommitState.NO_HOST_WRITE;
        Integer failedRowIndex = null;
        // Snapshot for argumentsJson + per-row dispatch detection. Treat null as empty so the
        // serializer produces {count:0, sampleHashes:[]} rather than NPE.
        final List<Map<String, Object>> safeRecords = records == null ? List.of() : records;

        try {
            mutationAuthorizationService.enforceMutationRole(AiAgentMutationRole.CODE);

            // DoS guard — REVIEWS HIGH bulk-max-rows. Reject empty + oversized batches BEFORE any
            // entity resolution, hash, or DB work.
            int maxRows = mutationProperties.resolvedBulkMaxRows();
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
                    failedRowIndex = i;
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
                metaClass = toolEntityResolver.resolveCreatableEntityOrThrow(entityName);
            }
            if (anyUpdate) {
                metaClass = toolEntityResolver.resolveUpdatableEntityOrThrow(entityName);
            }

            // Entity-level CRUD gate.
            if (anyCreate) {
                mutationAuthorizationService.enforceCreatePermission(metaClass);
            }
            if (anyUpdate) {
                mutationAuthorizationService.enforceUpdatePermission(metaClass);
            }

            // Per-attribute write gate — UNION across rows, excluding id.
            Set<String> writtenKeys = safeRecords.stream()
                    .flatMap(r -> r.keySet().stream())
                    .filter(k -> !"id".equals(k))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            mutationAuthorizationService.enforceAttributeWriteAccess(metaClass, writtenKeys);

            // Hash + reservation — Stripe-style byte-identical retry over records in submission order.
            Map<String, Object> batchEnvelope = new LinkedHashMap<>();
            batchEnvelope.put("records", safeRecords);
            String requestHash = mutationRequestHasher.hash(
                    "bulk_save_records", entityName, null, null, null, batchEnvelope);
            reservation = mutationIntentRepository.reserveOrReplay(
                    "bulk_save_records", idempotencyKey, userUsername, RunContext.getConversationId(),
                    requestHash, mutationProperties.resolvedIdempotencyTtl());
            if (reservation.state() != MutationIntentRepository.ReservationState.RESERVED) {
                // REVIEWS HIGH-11: handleReservationResult reads AiMutationIntent.RESULT_SUMMARY
                // and surfaces savedIds on IDEMPOTENT_REPLAY. Without this, bulk replay would
                // only echo the first saved id, breaking the LLM's expectation of an array.
                return mutationCommitCoordinator.handleReservationResult(
                        reservation, "bulk_save_records", startedAt, userUsername,
                        diffSerializer.serializeBulkArgumentsJson(entityName, safeRecords, idempotencyKey));
            }

            // Per-row coerce + per-row CrudEntityContext + guard + build SaveContext.
            SaveContext saveContext = new SaveContext();
            List<Object> entitiesInOrder = new ArrayList<>(safeRecords.size());
            for (int i = 0; i < safeRecords.size(); i++) {
                failedRowIndex = i;
                Map<String, Object> row = safeRecords.get(i);
                boolean isUpdate = row.containsKey("id");
                Object rowId = isUpdate ? row.get("id") : null;
                Map<String, Object> rowAttrs = row.entrySet().stream()
                        .filter(e -> !"id".equals(e.getKey()))
                        .collect(Collectors.toMap(
                                Map.Entry::getKey, Map.Entry::getValue,
                                (a, b) -> b, LinkedHashMap::new));
                Map<String, Object> coerced = mutationAttributeBinder.coerceAttributes(metaClass, rowAttrs);

                Object entity;
                if (!isUpdate) {
                    entity = dataManager.create(metaClass.getJavaClass());
                } else {
                    final MetaClass loadedMetaClass = metaClass;
                    final String idForError = rowId.toString();
                    UUID parsed = mutationAttributeBinder.requireUuidId(
                            toolEntityResolver.parseEntityId(idForError, metaClass));
                    entity = dataManager.load(metaClass.getJavaClass())
                            .id(parsed)
                            .optional()
                            .orElseThrow(() -> mutationErrorTranslator.notFound(loadedMetaClass, idForError));
                }

                // REVIEWS HIGH-13 — per-row CrudEntityContext AFTER entity load/create so any
                // row-state-dependent constraints (row-level policies that gate on entity.status,
                // tenant predicates, etc.) see live row state. Phase 11
                // MutationAuthorizationService runs entity-level checks once per batch; this is
                // the per-row evaluator MUT-14 calls for explicitly.
                CrudEntityContext crudCtx = new CrudEntityContext(metaClass);
                accessManager.applyRegisteredConstraints(crudCtx);
                if (isUpdate && !crudCtx.isUpdatePermitted()) {
                    throw new AccessDeniedException(
                            "update denied for entity " + metaClass.getName() + " row " + i);
                }
                if (!isUpdate && !crudCtx.isCreatePermitted()) {
                    throw new AccessDeniedException(
                            "create denied for entity " + metaClass.getName() + " row " + i);
                }

                mutationGuard.check(new MutationIntent(
                        "bulk_save_records", metaClass,
                        isUpdate ? (UUID) EntityValues.getId(entity) : null,
                        coerced));
                mutationAttributeBinder.applyAttributes(metaClass, entity, coerced);
                saveContext.saving(entity);
                entitiesInOrder.add(entity);
            }
            failedRowIndex = null; // all rows passed pre-save validation

            // Single proxy-crossed @Transactional save — rollback-all on RuntimeException.
            EntitySet saved = mutationSaveExecutor.bulkSave(saveContext);
            commitState = MutationCommitState.HOST_SAVE_RETURNED;
            // Use entitiesInOrder (input order is the contract), not the unordered EntitySet
            // iterator, so savedIds[i] aligns with records[i] for the LLM.
            List<UUID> savedIds = entitiesInOrder.stream()
                    .map(e -> (UUID) EntityValues.getId(e))
                    .toList();
            // Defensive: ensure all entities are present in the saved EntitySet (host policy /
            // listener cannot drop a row silently).
            for (Object e : entitiesInOrder) {
                if (!saved.optional(e).isPresent()) {
                    throw new IllegalStateException(
                            "DataManager.save dropped entity for row in bulk batch");
                }
            }

            String argumentsJson = diffSerializer.serializeBulkArgumentsJson(
                    entityName, safeRecords, idempotencyKey);
            String resultSummaryJson = diffSerializer.serializeBulkResultSummary(savedIds);

            // Finalize idempotency BEFORE audit (REVIEWS HIGH-11 — persist savedIds for replay).
            UUID firstId = savedIds.isEmpty() ? null : savedIds.get(0);
            mutationIntentRepository.markCommitted(
                    reservation.intent(), firstId, metaClass.getName(), resultSummaryJson);
            commitState = MutationCommitState.INTENT_COMMITTED;

            mutationCommitCoordinator.safeWriteAudit("bulk_save_records",
                    argumentsJson, resultSummaryJson,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.SUCCESS, null, null, userUsername);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outcome", AiToolCallOutcome.SUCCESS.getId());
            result.put("count", savedIds.size());
            result.put("savedIds", savedIds);
            return toolResultFormatter.toJson(result);

        } catch (ToolVetoedException ve) {
            ToolUserError translated = mutationErrorTranslator.translate(new ToolUserError("access_denied",
                    "operation blocked by host policy",
                    List.of("do not retry; surface to user")), "bulk_save_records", metaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteErrorAudit("bulk_save_records",
                    diffSerializer.serializeBulkArgumentsJson(entityName, safeRecords, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.BLOCKED, "mutation_guard_vetoed", ve.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (AccessDeniedException ade) {
            // REVIEWS HIGH-13 row-level deny short-circuits to BLOCKED.
            ToolUserError translated = mutationErrorTranslator.translate(ade, "bulk_save_records", metaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteErrorAudit("bulk_save_records",
                    diffSerializer.serializeBulkArgumentsJson(entityName, safeRecords, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.BLOCKED, "row_access_denied", ade.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (ToolUserError tue) {
            ToolUserError translated = mutationErrorTranslator.translate(tue, "bulk_save_records", metaClass);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.safeWriteErrorAudit("bulk_save_records",
                    diffSerializer.serializeBulkArgumentsJson(entityName, safeRecords, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    AiToolCallOutcome.ERROR, null, tue.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        } catch (Throwable t) {
            ToolUserError translated = mutationCommitCoordinator
                    .translateThrowableAfterReservation(t, commitState, "bulk_save_records", metaClass);
            AiToolCallOutcome outcome = mutationCommitCoordinator.auditOutcome(commitState);
            mutationCommitCoordinator.markFailedIfReserved(reservation, translated, commitState);
            mutationCommitCoordinator.logUnexpectedThrowable("bulk_save_records", t, translated, outcome, commitState);
            mutationCommitCoordinator.safeWriteErrorAudit("bulk_save_records",
                    diffSerializer.serializeBulkArgumentsJson(entityName, safeRecords, idempotencyKey),
                    translated,
                    System.currentTimeMillis() - startedAt,
                    outcome, null, t.getClass().getName(), userUsername);
            return toolResultFormatter.error(translated);
        }
    }
}
