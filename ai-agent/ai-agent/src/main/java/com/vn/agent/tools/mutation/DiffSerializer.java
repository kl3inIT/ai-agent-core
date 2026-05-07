package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.audit.AiAgentAuditProperties;
import com.vn.agent.audit.AuditFieldHasher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Serializes mutation tool argumentsJson + diff JSON for {@code AiAuditEvent.argumentsJson}
 * / {@code resultSummary} (AUD-07). Sensitive attribute values are SHA-256 hashed via
 * {@link AuditFieldHasher} when the simple attribute name appears in
 * {@link AiAgentAuditProperties#resolvedSensitiveFields()} and
 * {@link AiAgentAuditProperties#resolvedHashSensitiveFields()} is true.
 *
 * <p><b>P-22 contract:</b> raw LLM-supplied attribute values NEVER reach a result/error
 * prose string. They flow through Jackson here (which JSON-escapes) or through the
 * SHA-256 hasher before being written into the audit row only.
 *
 * <p><b>Null safety on sensitive fields:</b> {@link AuditFieldHasher#sha256Hex(String)} is
 * null-tolerant and returns null on null input, so this class never throws on missing
 * sensitive values.
 */
@Component
public class DiffSerializer {

    private final ObjectMapper objectMapper;
    private final AiAgentAuditProperties auditProperties;
    private final MutationRequestHasher mutationRequestHasher;

    public DiffSerializer(ObjectMapper objectMapper,
                          AiAgentAuditProperties auditProperties,
                          MutationRequestHasher mutationRequestHasher) {
        this.objectMapper = objectMapper;
        this.auditProperties = auditProperties;
        this.mutationRequestHasher = mutationRequestHasher;
    }

    /**
     * Build the {@code argumentsJson} payload for create_record / update_record audit rows.
     * Includes the entityName, optional entity id, attribute map (sensitive values hashed),
     * and idempotencyKey. Matches the resultSummary diff key convention.
     */
    public String serializeEntityArgumentsJson(String entityName,
                                               String id,
                                               Map<String, Object> rawAttributes,
                                               String idempotencyKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityName", entityName);
        if (id != null) {
            payload.put("id", id);
        }
        payload.put("attributes", maskSensitiveValues(rawAttributes));
        payload.put("idempotencyKey", idempotencyKey);
        return writeJson(payload);
    }

    /**
     * Build the {@code argumentsJson} payload for {@code add_related_record} /
     * {@code remove_related_record} audit rows. Carries the full call shape
     * ({entityName, id, relationship, relatedId, idempotencyKey}) per AUD-07.
     * Relationship + ids are not sensitive — there's no attribute map to hash.
     */
    public String serializeRelatedArgumentsJson(String entityName,
                                                 String id,
                                                 String relationship,
                                                 String relatedId,
                                                 String idempotencyKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityName", entityName);
        payload.put("id", id);
        payload.put("relationship", relationship);
        payload.put("relatedId", relatedId);
        payload.put("idempotencyKey", idempotencyKey);
        return writeJson(payload);
    }

    /**
     * Build the {@code resultSummary} for {@code add_related_record} /
     * {@code remove_related_record}. Carries the relationship name, the verified action,
     * and the relatedId. Aligns with CONTEXT.md "argumentsJson payload format" decision.
     */
    public String serializeRelatedActionSummary(String relationship,
                                                 String action,
                                                 String relatedId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("relationship", relationship);
        summary.put("action", action);
        summary.put("relatedId", relatedId);
        return writeJson(summary);
    }

    /**
     * Build the {@code resultSummary} for create_record (no pre-image). Each post-image
     * value is SHA-256-hashed when its simple name is sensitive.
     */
    public String serializeCreatePostImage(Map<String, Object> postImage) {
        List<Map<String, Object>> diffs = new ArrayList<>(postImage.size());
        Set<String> sensitive = auditProperties.resolvedSensitiveFields();
        boolean hash = auditProperties.resolvedHashSensitiveFields();
        for (Map.Entry<String, Object> entry : postImage.entrySet()) {
            String attribute = entry.getKey();
            Object value = entry.getValue();
            String stringValue = value == null ? null : value.toString();
            if (hash && sensitive.contains(attribute)) {
                stringValue = AuditFieldHasher.sha256Hex(stringValue);
            }
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("attribute", attribute);
            diff.put("to", stringValue);
            diffs.add(diff);
        }
        return writeJson(diffs);
    }

    /**
     * Build the {@code resultSummary} for update_record. Emits one entry per attribute the
     * caller targeted; values are SHA-256-hashed when the attribute name is sensitive. Skips
     * attributes whose pre/post values are equal (no-op writes are still useful for the
     * audit row but do not need a diff entry).
     */
    public String serializeUpdateDiff(Map<String, Object> preImage, Map<String, Object> postImage) {
        List<Map<String, Object>> diffs = new ArrayList<>(postImage.size());
        Set<String> sensitive = auditProperties.resolvedSensitiveFields();
        boolean hash = auditProperties.resolvedHashSensitiveFields();
        for (String attribute : postImage.keySet()) {
            Object from = preImage.get(attribute);
            Object to = postImage.get(attribute);
            if (Objects.equals(from, to)) {
                continue;
            }
            String fromStr = from == null ? null : from.toString();
            String toStr = to == null ? null : to.toString();
            if (hash && sensitive.contains(attribute)) {
                fromStr = AuditFieldHasher.sha256Hex(fromStr);
                toStr = AuditFieldHasher.sha256Hex(toStr);
            }
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("attribute", attribute);
            diff.put("from", fromStr);
            diff.put("to", toStr);
            diffs.add(diff);
        }
        return writeJson(diffs);
    }

    /**
     * Phase 13 — argumentsJson for {@code bulk_save_records} audit rows. Carries ONLY the
     * batch shape: {@code count}, {@code entityName}, up to 3 {@code sampleHashes}, and
     * {@code idempotencyKey}. NEVER includes raw row values (P-22 / AUD-07 — Phase 11
     * invariant for mutation argumentsJson).
     *
     * <p>{@code sampleHashes} are SHA-256 over canonical JSON of records[0..min(2,N)] so
     * operators can correlate audit rows with later complaints / replays without storing
     * any user-supplied text.
     */
    public String serializeBulkArgumentsJson(String entityName,
                                             List<Map<String, Object>> records,
                                             String idempotencyKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityName", entityName);
        payload.put("count", records == null ? 0 : records.size());
        List<String> sampleHashes = new ArrayList<>();
        if (records != null) {
            int sampleCount = Math.min(records.size(), 3);
            for (int i = 0; i < sampleCount; i++) {
                sampleHashes.add(mutationRequestHasher.hashCanonical(records.get(i)));
            }
        }
        payload.put("sampleHashes", sampleHashes);
        payload.put("idempotencyKey", idempotencyKey);
        return writeJson(payload);
    }

    /**
     * Phase 13 REVIEWS HIGH-11 — resultSummary for {@code bulk_save_records} success +
     * {@code IDEMPOTENT_REPLAY}. Persisted in {@code AiMutationIntent.RESULT_SUMMARY} so a
     * replay returns the original {@code savedIds} array. UUIDs are not considered PII per
     * Phase 11 (already used in single-record DiffSerializer result summaries).
     */
    public String serializeBulkResultSummary(List<UUID> savedIds) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", savedIds == null ? 0 : savedIds.size());
        List<String> idStrings = new ArrayList<>();
        if (savedIds != null) {
            for (UUID id : savedIds) {
                idStrings.add(id == null ? null : id.toString());
            }
        }
        summary.put("savedIds", idStrings);
        return writeJson(summary);
    }

    /**
     * Phase 13 — resultSummary for {@code bulk_save_records} failure audit rows. Emits ONLY
     * the failed row index, the stable error code, and the per-row operation kind ("create"
     * for rows missing an id; "update" for rows carrying an id). NEVER echoes the failed
     * attribute name or the failed value (P-22 — Phase 11 mutation invariant).
     */
    public String serializeBulkFailureSummary(int failedRowIndex,
                                              String stableErrorCode,
                                              String operation) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("failedRowIndex", failedRowIndex);
        summary.put("errorCode", stableErrorCode);
        summary.put("operation", operation);
        return writeJson(summary);
    }

    /**
     * Replace LLM-supplied values with SHA-256 hashes for any attribute whose simple name
     * is configured sensitive. Non-sensitive values pass through to Jackson which handles
     * JSON-escape; raw strings never leak into the LLM result path because this is only
     * written into the audit row.
     */
    private Map<String, Object> maskSensitiveValues(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Set<String> sensitive = auditProperties.resolvedSensitiveFields();
        boolean hash = auditProperties.resolvedHashSensitiveFields();
        if (!hash || sensitive.isEmpty()) {
            return new LinkedHashMap<>(attributes);
        }
        Map<String, Object> masked = new LinkedHashMap<>(attributes.size());
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String attribute = entry.getKey();
            Object value = entry.getValue();
            if (sensitive.contains(attribute)) {
                String stringValue = value == null ? null : value.toString();
                masked.put(attribute, AuditFieldHasher.sha256Hex(stringValue));
            } else {
                masked.put(attribute, value);
            }
        }
        return masked;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON serialization failed in DiffSerializer", e);
        }
    }
}
