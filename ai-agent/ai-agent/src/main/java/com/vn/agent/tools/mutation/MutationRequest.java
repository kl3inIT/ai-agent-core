package com.vn.agent.tools.mutation;

import java.util.List;
import java.util.Map;

/**
 * Sealed request hierarchy for the five LLM-mediated mutation tools (Phase 17, D-02). Each
 * nested record carries ONLY the divergent inputs its corresponding {@code @Tool} method
 * receives plus the per-call idempotency key; the shared fail-closed gate sequence
 * (role &rarr; resolve &rarr; authorize &rarr; reserve &rarr; coerce &rarr; guard &rarr;
 * save &rarr; finalize) lives once in {@link MutationGateChain}.
 *
 * <p><b>Why sealed dispatch over chain-of-responsibility (D-01/D-02):</b> mirroring the
 * rationale recorded on {@code com.vn.agent.filter.FilterNode}, a {@code sealed interface}
 * with a fixed set of permitted records lets {@link MutationGateChain} dispatch the two real
 * divergence points (the {@code Bulk} per-row coerce/{@code CrudEntityContext}/guard loop +
 * {@code SaveContext}/{@code bulkSave}; and the per-tool {@code argumentsJson} serializer +
 * success-result shape) through an exhaustive {@code switch} — the Java compiler enforces
 * exhaustiveness, so adding a sixth variant without handling it everywhere fails the build
 * rather than silently falling through a generic branch.
 *
 * <p>This is a pure model file: no behavior, no {@code @Component}, and deliberately NO
 * Jackson {@code @JsonTypeInfo} — every variant is built in-process by the {@code @Tool}
 * adapters in {@link BuiltInMutationTools} and is never deserialized from the wire.
 */
public sealed interface MutationRequest
        permits MutationRequest.Create,
                MutationRequest.Update,
                MutationRequest.AddRelated,
                MutationRequest.RemoveRelated,
                MutationRequest.Bulk {

    /** Idempotency key carried by every variant; the chain's reserve gate uses it uniformly. */
    String idempotencyKey();

    /** {@code create_record}: a single new row of {@code attributes} on {@code entityName}. */
    record Create(String entityName,
                  Map<String, Object> attributes,
                  String idempotencyKey) implements MutationRequest {}

    /** {@code update_record}: change {@code attributes} on the existing {@code id} row. */
    record Update(String entityName,
                  String id,
                  Map<String, Object> attributes,
                  String idempotencyKey) implements MutationRequest {}

    /** {@code add_related_record}: link an existing child {@code relatedId} into the parent collection. */
    record AddRelated(String parentEntityName,
                      String parentId,
                      String relationship,
                      String relatedId,
                      String idempotencyKey) implements MutationRequest {}

    /** {@code remove_related_record}: unlink {@code relatedId} from the parent by clearing the child FK. */
    record RemoveRelated(String parentEntityName,
                         String parentId,
                         String relationship,
                         String relatedId,
                         String idempotencyKey) implements MutationRequest {}

    /** {@code bulk_save_records}: a mixed create/update batch of {@code records} on {@code entityName}. */
    record Bulk(String entityName,
                List<Map<String, Object>> records,
                String idempotencyKey) implements MutationRequest {}
}
