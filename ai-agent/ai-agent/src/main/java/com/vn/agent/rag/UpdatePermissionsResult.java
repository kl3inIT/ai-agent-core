package com.vn.agent.rag;

/**
 * Outcome of {@link KnowledgeDocumentService#updatePermissionsAndReingest(java.util.UUID,
 * java.util.Collection, String)}. Carries the partial-failure status so the caller
 * (typically {@code KnowledgeBaseView}) can route a localized notification without any
 * try/catch business logic in the view layer (Plan 10-08, Fix W3 + CLAUDE.md
 * "no business logic in views").
 *
 * <p>The save-then-reingest sequence is intentionally NOT atomic from the persistence
 * point of view: reingest is async (delete chunks, re-run worker), so on enqueue
 * failure the document row is already updated correctly. The admin can retry via the
 * "Reingest" row action without losing the saved metadata changes (Phase 10 D-08).
 */
public record UpdatePermissionsResult(Status status) {

    /**
     * Result status:
     * <ul>
     *     <li>{@link #SAVED_AND_REINGESTING} — happy path: doc row saved, async reingest
     *     scheduled successfully.</li>
     *     <li>{@link #SAVED_REINGEST_FAILED} — partial failure: doc row saved, but
     *     scheduling the async reingest threw. No data loss; user can retry.</li>
     * </ul>
     */
    public enum Status {
        SAVED_AND_REINGESTING,
        SAVED_REINGEST_FAILED
    }
}
