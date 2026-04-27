package com.vn.agent.rag;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chunk-metadata key constants. Single source of truth for the ingestion writer
 * (AsyncIngestionWorker, Plan 05-03) and the retrieval filter reader
 * (RetrievalFilterBuilder). A rename here is a BREAKING change requiring full
 * knowledge-base reingest — see CONTEXT.md §specifics (D-03 embedding-model
 * drift clause + D-10 JSON metadata contract).
 */
public final class ChunkMetadata {

    /** Source label — filename for uploads, ingester-specific label for SPI sources. */
    public static final String SOURCE = "source";

    /** Stable AiKnowledgeDocument id — used by atomic delete (D-21). */
    public static final String DOCUMENT_ID = "documentId";

    /** Embedding model slug — used by D-03 drift filter in RetrievalFilterBuilder. */
    public static final String EMBEDDING_MODEL = "embeddingModel";

    /** JSON list of Jmix role codes — stored for audit/debug; retrieval uses flattened flags. */
    public static final String ALLOWED_ROLES = "allowedRoles";

    /**
     * Jmix MetaClass name of the entity this document describes.
     * Used by EXP-05 NOT IN denylist filter in {@code RetrievalFilterBuilder}.
     * Ingestion writer MUST mirror this key at write time (Phase 10 D-07).
     * A rename here is a BREAKING change requiring full knowledge-base reingest.
     */
    public static final String SOURCE_ENTITY = "source_entity";

    /** Prefix for flattened per-role boolean flags (Option A / Pitfall #1 mitigation). */
    public static final String ROLE_FLAG_PREFIX = "role_";

    /**
     * Normalized role-code charset for metadata keys and vector-store filter fields.
     *
     * <p>PostgreSQL JSONPath member access in Spring AI's generated predicate uses dot-form
     * accessor (for example {@code $.role_code}). Keys that contain punctuation like {@code -}
     * can produce invalid JSONPath and fail at runtime. We therefore normalize role codes to
     * lowercase {@code [a-z0-9_]} and replace all other characters with underscore.
     */
    private static final Pattern NON_KEY_CHAR_PATTERN = Pattern.compile("[^a-z0-9_]");

    private ChunkMetadata() {
    }

    /**
     * Build a stable per-role flattened metadata key used by both ingestion and retrieval.
     */
    public static String roleFlagKey(String roleCode) {
        return ROLE_FLAG_PREFIX + normalizeRoleCode(roleCode);
    }

    /**
     * Normalize a role code for JSON metadata key usage.
     */
    public static String normalizeRoleCode(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return "";
        }
        String lowered = roleCode.toLowerCase(Locale.ROOT);
        return NON_KEY_CHAR_PATTERN.matcher(lowered).replaceAll("_");
    }
}
