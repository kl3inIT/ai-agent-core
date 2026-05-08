package com.vn.agent.view.chat.intent;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.ComponentEventListener;
import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.extraction.DraftApplyResult;
import com.vn.agent.extraction.DraftLoader;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.StandardOutcome;
import io.jmix.flowui.view.View;
import io.jmix.flowui.view.ViewAttributes;
import io.jmix.flowui.view.ViewInfo;
import io.jmix.flowui.view.ViewRegistry;
import io.jmix.flowui.view.navigation.DetailViewClassNavigator;
import io.jmix.flowui.view.navigation.DetailViewNavigator;
import io.jmix.flowui.view.navigation.SupportsAfterViewNavigationHandler;
import io.jmix.flowui.sys.ViewSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SaveDeletesDraftTest {

    private static final String ENTITY_NAME = "mutationTest_MutationTestFixture";

    private ViewNavigators viewNavigators;
    private AccessManager accessManager;
    private ViewRegistry viewRegistry;
    private Metadata metadata;
    private DataManager dataManager;
    private DraftLoader draftLoader;
    private OpenFormWithDraftHandler handler;
    private View<?> originView;
    private DetailViewClassNavigator<Object, View<?>> classNavigator;
    private AiExtractionDraft draft;
    private UUID draftId;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        viewNavigators = mock(ViewNavigators.class);
        accessManager = mock(AccessManager.class);
        viewRegistry = mock(ViewRegistry.class);
        metadata = mock(Metadata.class, RETURNS_DEEP_STUBS);
        dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        draftLoader = mock(DraftLoader.class);
        Messages messages = mock(Messages.class);
        Notifications notifications = mock(Notifications.class, RETURNS_DEEP_STUBS);
        originView = mock(View.class);
        MetaClass metaClass = mock(MetaClass.class);
        draftId = UUID.randomUUID();
        draft = new AiExtractionDraft();
        draft.setId(draftId);
        draft.setTargetEntityName(ENTITY_NAME);

        when(messages.getMessage(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(metadata.getSession().findClass(ENTITY_NAME)).thenReturn(metaClass);
        when(metaClass.getName()).thenReturn(ENTITY_NAME);
        when(metaClass.getJavaClass()).thenAnswer(invocation -> MutationTestFixture.class);
        when(viewRegistry.getDetailViewInfo(metaClass)).thenReturn(new ViewInfo(
                "MutationTest.detail",
                TestMutationDetailView.class.getName(),
                TestMutationDetailView.class,
                "test-mutation-detail-view.xml"));
        when(dataManager.load(AiExtractionDraft.class).id(draftId).optional())
                .thenReturn(Optional.of(draft));
        when(draftLoader.apply(any(UUID.class), any())).thenReturn(
                new DraftApplyResult(1, 0, List.of(), false));

        DetailViewNavigator<Object> detailNavigator = mock(DetailViewNavigator.class);
        classNavigator = mock(DetailViewClassNavigator.class);
        when(viewNavigators.detailView(any(View.class), any(Class.class))).thenReturn(detailNavigator);
        when(detailNavigator.newEntity()).thenReturn(detailNavigator);
        when(detailNavigator.withViewClass(any(Class.class))).thenReturn(classNavigator);
        when(classNavigator.withAfterNavigationHandler(any())).thenReturn(classNavigator);

        handler = new OpenFormWithDraftHandler(viewNavigators, accessManager, viewRegistry,
                metadata, dataManager, draftLoader, messages, notifications);
    }

    @Test
    void successfulSaveMarksConfirmedAndDeletesDraft() {
        TestMutationDetailView detailView = openAndRunAfterNavigationHandler();

        ComponentUtil.fireEvent(detailView, new StandardDetailView.AfterSaveEvent(detailView, false));

        assertThat(draft.getConfirmed()).isTrue();
        verify(dataManager).save(draft);
        verify(dataManager).remove(draft);
    }

    @Test
    void closeWithoutSaveLeavesDraftForTtlCleanup() {
        TestMutationDetailView detailView = openAndRunAfterNavigationHandler();

        int listenerCountBeforeClose = ComponentUtil.getListeners(
                detailView, View.AfterCloseEvent.class).size();
        closeListener(detailView).onComponentEvent(new View.AfterCloseEvent(
                detailView, StandardOutcome.CLOSE.getCloseAction()));

        assertThat(draft.getConfirmed()).isNotEqualTo(Boolean.TRUE);
        verify(dataManager, never()).remove(draft);
        verify(dataManager, never()).save(draft);
        assertThat(ComponentUtil.getListeners(detailView, View.AfterCloseEvent.class).size())
                .isLessThan(listenerCountBeforeClose);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private TestMutationDetailView openAndRunAfterNavigationHandler() {
        OpenFormWithDraftHandler.OpenResult result = handler.open(
                originView, draftId, ENTITY_NAME, "Fixture");
        assertThat(result.status()).isEqualTo(OpenFormWithDraftHandler.OpenStatus.OPENED);
        ArgumentCaptor<Consumer> handlerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(classNavigator).withAfterNavigationHandler(handlerCaptor.capture());
        TestMutationDetailView detailView = new TestMutationDetailView();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(eq(ViewAttributes.class), eq("TestMutation.detail")))
                .thenReturn(new ViewAttributes("TestMutation.detail"));
        when(applicationContext.getBean(ViewSupport.class)).thenReturn(mock(ViewSupport.class));
        detailView.setTestApplicationContext(applicationContext);
        handlerCaptor.getValue().accept(new SupportsAfterViewNavigationHandler.AfterViewNavigationEvent<>(
                classNavigator, detailView));
        verify(draftLoader).apply(draftId, detailView.getEditedEntity());
        return detailView;
    }

    @SuppressWarnings("unchecked")
    private ComponentEventListener<View.AfterCloseEvent> closeListener(TestMutationDetailView detailView) {
        List<?> listeners = List.copyOf(ComponentUtil.getListeners(detailView, View.AfterCloseEvent.class));
        return (ComponentEventListener<View.AfterCloseEvent>) listeners.get(listeners.size() - 1);
    }
}
