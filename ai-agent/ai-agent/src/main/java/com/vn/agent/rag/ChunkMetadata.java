package com.vn.agent.rag;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

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

    private ChunkMetadata() {
    }

    /**
     * Build a stable per-role flattened metadata key used by both ingestion and retrieval.
     *
     * <p>The role code is UTF-8 hex encoded instead of normalized by character replacement so
     * distinct valid Jmix role codes such as {@code sales-admin} and {@code sales_admin} cannot
     * collapse to the same metadata key. Hex output is still safe for Spring AI vector-store
     * metadata filter fields and PostgreSQL JSONPath member access.</p>
     */
    public static String roleFlagKey(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return ROLE_FLAG_PREFIX;
        }
        byte[] bytes = roleCode.getBytes(StandardCharsets.UTF_8);
        return ROLE_FLAG_PREFIX + HexFormat.of().formatHex(bytes);
    }
}
