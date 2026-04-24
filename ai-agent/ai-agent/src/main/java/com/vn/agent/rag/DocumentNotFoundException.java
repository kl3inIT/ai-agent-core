package com.vn.agent.rag;

import java.util.UUID;

/**
 * Thrown by {@link KnowledgeDocumentService} when an operation (delete, reingest)
 * targets a document id that no longer exists. Surfaces the missing id so callers
 * can render a localized message via the
 * {@code com.vn.agent.rag/DocumentNotFoundException.message} key.
 */
public class DocumentNotFoundException extends RuntimeException {

    private final UUID id;

    public DocumentNotFoundException(UUID id) {
        super("Knowledge document not found: " + id);
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}
