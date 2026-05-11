package com.vn.agent.extraction;

import java.util.UUID;

/**
 * Draft reference returned after an extraction intent prepares a form draft.
 */
public record ExtractionResult(UUID draftId, String entityName, String instanceName) {
}
