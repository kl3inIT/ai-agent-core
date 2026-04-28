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

    public DiffSerializer(ObjectMapper objectMapper, AiAgentAuditProperties auditProperties) {
        this.objectMapper = objectMapper;
        this.auditProperties = auditProperties;
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
