package com.vn.agent.view.chat.intent;

import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.extraction.DraftLoader;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.accesscontext.UiShowViewContext;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewInfo;
import io.jmix.flowui.view.ViewRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenFormWithDraftHandlerTest {

    private static final String ENTITY_NAME = "mutationTest_MutationTestFixture";

    private ViewNavigators viewNavigators;
    private AccessManager accessManager;
    private ViewRegistry viewRegistry;
    private Metadata metadata;
    private DataManager dataManager;
    private DraftLoader draftLoader;
    private OpenFormWithDraftHandler handler;
    private View<?> originView;
    private MetaClass metaClass;

    @BeforeEach
    void setUp() {
        viewNavigators = mock(ViewNavigators.class, RETURNS_DEEP_STUBS);
        accessManager = mock(AccessManager.class);
        viewRegistry = mock(ViewRegistry.class);
        metadata = mock(Metadata.class, RETURNS_DEEP_STUBS);
        dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        draftLoader = mock(DraftLoader.class);
        Messages messages = mock(Messages.class);
        Notifications notifications = mock(Notifications.class, RETURNS_DEEP_STUBS);
        originView = mock(View.class);
        metaClass = mock(MetaClass.class);
        when(messages.getMessage(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(metadata.getSession().findClass(ENTITY_NAME)).thenReturn(metaClass);
        when(metaClass.getName()).thenReturn(ENTITY_NAME);
        when(metaClass.getJavaClass()).thenAnswer(invocation -> MutationTestFixture.class);
        when(viewRegistry.getDetailViewInfo(metaClass)).thenReturn(new ViewInfo(
                "MutationTest.detail",
                TestMutationDetailView.class.getName(),
                TestMutationDetailView.class,
                "test-mutation-detail-view.xml"));
        handler = new OpenFormWithDraftHandler(viewNavigators, accessManager, viewRegistry,
                metadata, dataManager, draftLoader, messages, notifications);
    }

    @Test
    void missingDraftReturnsExpiredAndDoesNotNavigate() {
        UUID draftId = UUID.randomUUID();
        when(dataManager.load(AiExtractionDraft.class).id(draftId).optional())
                .thenReturn(Optional.empty());

        OpenFormWithDraftHandler.OpenResult result = handler.open(
                originView, draftId, ENTITY_NAME, "Fixture");

        assertThat(result.status()).isEqualTo(OpenFormWithDraftHandler.OpenStatus.EXPIRED);
        verify(viewNavigators, never()).detailView(any(View.class), any(Class.class));
    }

    @Test
    void viewPermissionDenialReturnsDeniedAndDoesNotNavigate() {
        UUID draftId = UUID.randomUUID();
        when(dataManager.load(AiExtractionDraft.class).id(draftId).optional())
                .thenReturn(Optional.of(draft(draftId)));
        denyViewPermission();

        OpenFormWithDraftHandler.OpenResult result = handler.open(
                originView, draftId, ENTITY_NAME, "Fixture");

        assertThat(result.status()).isEqualTo(OpenFormWithDraftHandler.OpenStatus.DENIED);
        verify(viewNavigators, never()).detailView(any(View.class), any(Class.class));
    }

    private AiExtractionDraft draft(UUID draftId) {
        AiExtractionDraft draft = new AiExtractionDraft();
        draft.setId(draftId);
        draft.setTargetEntityName(ENTITY_NAME);
        return draft;
    }

    private void denyViewPermission() {
        org.mockito.Mockito.doAnswer(invocation -> {
            Object context = invocation.getArgument(0);
            if (context instanceof UiShowViewContext viewContext) {
                viewContext.setDenied();
            }
            if (context instanceof EntityAttributeContext attributeContext) {
                attributeContext.setModifyDenied();
            }
            if (context instanceof CrudEntityContext crudEntityContext) {
                // Keep create permission intact; this test is specifically the view gate.
                assertThat(crudEntityContext).isNotNull();
            }
            return null;
        }).when(accessManager).applyRegisteredConstraints(any());
    }
}
