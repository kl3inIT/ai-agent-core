package com.vn.agent.extraction;

import com.vn.agent.entity.AiExtractionDraft;
import io.jmix.core.DataManager;
import io.jmix.core.FluentLoader;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExtractionDraftAccessTest {

    private static final String OPEN_DRAFT_QUERY =
            "e.id = :draftId and e.expiresAt > :now and e.confirmed = false";

    @Test
    void nullDraftIdReturnsEmptyWithoutLoading() {
        DataManager dataManager = mock(DataManager.class);
        ExtractionDraftAccess access = new ExtractionDraftAccess(dataManager);

        assertThat(access.loadOpenDraft(null)).isEmpty();

        verifyNoInteractions(dataManager);
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadOpenDraftUsesSecuredLiveDraftPredicate() {
        UUID draftId = UUID.randomUUID();
        AiExtractionDraft draft = mock(AiExtractionDraft.class, CALLS_REAL_METHODS);
        DataManager dataManager = mock(DataManager.class);
        FluentLoader<AiExtractionDraft> loader = mock(FluentLoader.class);
        FluentLoader.ByQuery<AiExtractionDraft> byQuery = mock(FluentLoader.ByQuery.class);
        when(dataManager.load(AiExtractionDraft.class)).thenReturn(loader);
        when(loader.query(OPEN_DRAFT_QUERY)).thenReturn(byQuery);
        when(byQuery.parameter(eq("draftId"), eq(draftId))).thenReturn(byQuery);
        when(byQuery.parameter(eq("now"), any(OffsetDateTime.class))).thenReturn(byQuery);
        when(byQuery.optional()).thenReturn(Optional.of(draft));
        ExtractionDraftAccess access = new ExtractionDraftAccess(dataManager);

        assertThat(access.loadOpenDraft(draftId)).containsSame(draft);

        verify(loader).query(OPEN_DRAFT_QUERY);
        verify(byQuery).parameter(eq("draftId"), eq(draftId));
        verify(byQuery).parameter(eq("now"), any(OffsetDateTime.class));
    }
}
