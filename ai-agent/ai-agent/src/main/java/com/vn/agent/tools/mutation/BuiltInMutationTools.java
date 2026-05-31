package com.vn.agent.tools.mutation;

import com.vn.agent.security.AiAgentMutationRole;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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

    private final MutationGateChain mutationGateChain;

    public BuiltInMutationTools(MutationGateChain mutationGateChain) {
        this.mutationGateChain = mutationGateChain;
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

        return mutationGateChain.execute(
                new MutationRequest.Create(entityName, attributes, idempotencyKey));
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

        return mutationGateChain.execute(
                new MutationRequest.Update(entityName, id, attributes, idempotencyKey));
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

        return mutationGateChain.execute(
                new MutationRequest.AddRelated(entityName, id, relationship, relatedId, idempotencyKey));
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

        return mutationGateChain.execute(
                new MutationRequest.RemoveRelated(entityName, id, relationship, relatedId, idempotencyKey));
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

        return mutationGateChain.execute(
                new MutationRequest.Bulk(entityName, records, idempotencyKey));
    }
}
