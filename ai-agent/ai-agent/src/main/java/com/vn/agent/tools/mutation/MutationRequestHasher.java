package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.vn.agent.audit.AuditFieldHasher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes a stable SHA-256 request hash over the raw LLM-supplied call shape for the
 * idempotency unique-row contract (CONTEXT D-02). The hash is canonical: keys are
 * lexicographically sorted and value types are preserved (a JSON string {@code "7"} and
 * a JSON number {@code 7} produce different hashes even if both later coerce to the same
 * Integer). Hashing the RAW shape is intentional — see Plan 11-07 STEP G "Required helper
 * behavior".
 *
 * <p>Package-private bean (Plan 11-07A): both tools and tests use this class directly so
 * the hashing contract is testable and not buried as a private method.
 *
 * <p><b>Thread-safe:</b> the {@link ObjectMapper} is configured with
 * {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} once and used read-only. Jackson's
 * {@link ObjectMapper} is documented thread-safe after configuration.
 */
@Component
public class MutationRequestHasher {

    private final ObjectMapper canonicalMapper;

    public MutationRequestHasher() {
        this.canonicalMapper = new ObjectMapper();
        this.canonicalMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * Hash the canonical JSON of {@code (toolName, entityName, id, relationship, relatedId,
     * attributes)}. Uses a {@link LinkedHashMap} of fixed key order; nested maps inherit
     * key-sorting from {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}.
     */
    public String hash(String toolName,
                       String entityName,
                       String id,
                       String relationship,
                       String relatedId,
                       Map<String, Object> attributes) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("toolName", toolName);
        envelope.put("entityName", entityName);
        envelope.put("id", id);
        envelope.put("relationship", relationship);
        envelope.put("relatedId", relatedId);
        envelope.put("attributes", attributes == null ? Map.of() : attributes);
        try {
            String canonicalJson = canonicalMapper.writeValueAsString(envelope);
            return AuditFieldHasher.sha256Hex(canonicalJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to compute mutation request hash", e);
        }
    }

    /**
     * Phase 13 — canonical SHA-256 of an arbitrary value (e.g. one bulk-save record). Used
     * by {@link DiffSerializer#serializeBulkArgumentsJson} to produce the {@code sampleHashes}
     * array in the bulk argumentsJson without leaking raw row values (P-22).
     *
     * <p>Reuses the same {@link ObjectMapper} configured with
     * {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS} so identical row content always
     * produces the same hash regardless of LLM key emission order.
     */
    public String hashCanonical(Object value) {
        try {
            String canonicalJson = canonicalMapper.writeValueAsString(value);
            return AuditFieldHasher.sha256Hex(canonicalJson);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to compute canonical hash", e);
        }
    }
}
