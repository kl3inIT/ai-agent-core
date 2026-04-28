package com.vn.agent.rag;

/**
 * Outcome of {@link KnowledgeDocumentService#updatePermissionsAndReingest(java.util.UUID,
 * java.util.Collection, String)}. Carries the successful save-and-reingest status so
 * the caller (typically {@code KnowledgeBaseView}) can route a localized notification.
 *
 * <p>If reingest enqueue or chunk purge fails, the service throws and the surrounding
 * transaction rolls back the document metadata edit so stale chunks cannot be exposed
 * under newly tightened metadata.
 */
public record UpdatePermissionsResult(Status status) {

    /**
     * Result status:
     * <ul>
     *     <li>{@link #SAVED_AND_REINGESTING} — happy path: doc row saved, async reingest
     *     scheduled successfully.</li>
     * </ul>
     */
    public enum Status {
        SAVED_AND_REINGESTING
    }
}
