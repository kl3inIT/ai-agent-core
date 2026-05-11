package com.vn.agent.extraction;

import java.util.UUID;

/**
 * Raised when a persisted extraction draft cannot be loaded for the current user.
 */
public class DraftNotFoundException extends RuntimeException {

    private final UUID draftId;

    public DraftNotFoundException(UUID draftId) {
        super("Extraction draft not found");
        this.draftId = draftId;
    }

    public UUID getDraftId() {
        return draftId;
    }
}
