package com.vn.agent.extraction;

import com.vn.agent.entity.AiExtractionDraft;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Secured user-facing access path for live extraction drafts.
 */
@Component
public class ExtractionDraftAccess {

    private final DataManager dataManager;

    public ExtractionDraftAccess(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public Optional<AiExtractionDraft> loadOpenDraft(UUID draftId) {
        if (draftId == null) {
            return Optional.empty();
        }
        return dataManager.load(AiExtractionDraft.class)
                .query("e.id = :draftId and e.expiresAt > :now and e.confirmed = false")
                .parameter("draftId", draftId)
                .parameter("now", OffsetDateTime.now())
                .optional();
    }
}
