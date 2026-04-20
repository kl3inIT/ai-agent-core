package com.vn.agent.rag;

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

    /** Prefix for flattened per-role boolean flags (Option A / Pitfall #1 mitigation). */
    public static final String ROLE_FLAG_PREFIX = "role_";

    private ChunkMetadata() {
    }
}
