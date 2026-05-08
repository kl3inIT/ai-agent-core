package com.vn.jmixapp.ai;

import com.vaadin.flow.component.ComponentUtil;
import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.extraction.DraftApplyResult;
import com.vn.agent.extraction.DraftLoader;
import com.vn.agent.extraction.ExtractionDraftAccess;
import com.vn.agent.view.chat.intent.OpenFormWithDraftHandler;
import com.vn.jmixapp.entity.Customer;
import com.vn.jmixapp.view.customer.CustomerDetailView;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.view.StandardDetailView;
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
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerDraftWorkflowTest {

    private static final String ENTITY_NAME = "jmixapp_Customer";
    private static final String CORE_CUSTOMER_IMPORT = "com.vn.jmixapp.entity.Customer";

    private ViewNavigators viewNavigators;
    private ViewRegistry viewRegistry;
    private Metadata metadata;
    private DataManager dataManager;
    private ExtractionDraftAccess extractionDraftAccess;
    private DraftLoader draftLoader;
    private View<?> originView;
    private DetailViewClassNavigator<Object, View<?>> classNavigator;
    private AiExtractionDraft draft;
    private UUID draftId;
    private OpenFormWithDraftHandler handler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        viewNavigators = mock(ViewNavigators.class);
        AccessManager accessManager = mock(AccessManager.class);
        viewRegistry = mock(ViewRegistry.class);
        metadata = mock(Metadata.class, RETURNS_DEEP_STUBS);
        dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        extractionDraftAccess = mock(ExtractionDraftAccess.class);
        draftLoader = mock(DraftLoader.class);
        Messages messages = mock(Messages.class);
        Notifications notifications = mock(Notifications.class, RETURNS_DEEP_STUBS);
        originView = mock(View.class);
        MetaClass customerMetaClass = mock(MetaClass.class);
        draftId = UUID.randomUUID();
        draft = mock(AiExtractionDraft.class, CALLS_REAL_METHODS);
        draft.setId(draftId);
        draft.setTargetEntityName(ENTITY_NAME);

        when(messages.getMessage(any(String.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(metadata.getSession().findClass(ENTITY_NAME)).thenReturn(customerMetaClass);
        when(customerMetaClass.getName()).thenReturn(ENTITY_NAME);
        when(customerMetaClass.getJavaClass()).thenAnswer(invocation -> Customer.class);
        when(viewRegistry.getDetailViewInfo(customerMetaClass)).thenReturn(new ViewInfo(
                "Customer.detail",
                TestCustomerDetailView.class.getName(),
                TestCustomerDetailView.class,
                "customer-detail-view.xml"));
        when(extractionDraftAccess.loadOpenDraft(draftId)).thenReturn(Optional.of(draft));
        when(dataManager.load(AiExtractionDraft.class).id(draftId).optional())
                .thenReturn(Optional.of(draft));
        when(draftLoader.apply(eq(draftId), any())).thenAnswer(invocation -> {
            Customer customer = invocation.getArgument(1, Customer.class);
            customer.setName("Workflow Customer");
            customer.setEmail("workflow@example.test");
            customer.setPhone("090123456");
            return new DraftApplyResult(3, 0, List.of(), false);
        });

        DetailViewNavigator<Object> detailNavigator = mock(DetailViewNavigator.class);
        classNavigator = mock(DetailViewClassNavigator.class);
        when(viewNavigators.detailView(any(View.class), any(Class.class))).thenReturn(detailNavigator);
        when(detailNavigator.newEntity()).thenReturn(detailNavigator);
        when(detailNavigator.withViewClass(any(Class.class))).thenReturn(classNavigator);
        when(classNavigator.withAfterNavigationHandler(any())).thenReturn(classNavigator);

        handler = new OpenFormWithDraftHandler(viewNavigators, accessManager, viewRegistry,
                metadata, dataManager, extractionDraftAccess, draftLoader, messages, notifications);
    }

    @Test
    void referenceIntentLivesInHostAndCoreHasNoCustomerImport() throws Exception {
        String extractorSource = read("jmix-app/src/main/java/com/vn/jmixapp/ai/CustomerDraftIntentExtractor.java");
        String customerDetailSource = read("jmix-app/src/main/java/com/vn/jmixapp/view/customer/CustomerDetailView.java");

        assertThat(extractorSource)
                .contains("implements IntentExtractor<Customer>")
                .contains("customer-from-pdf")
                .contains("jmixapp_Customer")
                .contains("@ConditionalOnProperty");
        assertThat(customerDetailSource)
                .contains("@PrimaryDetailView(Customer.class)")
                .contains("@ViewController(id = \"Customer.detail\")");
        assertThat(coreCustomerImports()).isEmpty();
    }

    @Test
    void customerDraftOpensPrimaryDetailViewPrefillsAndDeletesDraftOnSave() {
        TestCustomerDetailView detailView = openAndRunAfterNavigationHandler();
        Customer editedEntity = detailView.getEditedEntity();

        assertThat(editedEntity.getName()).isEqualTo("Workflow Customer");
        assertThat(editedEntity.getEmail()).isEqualTo("workflow@example.test");
        assertThat(editedEntity.getPhone()).isEqualTo("090123456");

        ComponentUtil.fireEvent(detailView, new StandardDetailView.AfterSaveEvent(detailView, false));

        assertThat(draft.getConfirmed()).isTrue();
        verify(dataManager).save(draft);
        verify(dataManager).remove(draft);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private TestCustomerDetailView openAndRunAfterNavigationHandler() {
        OpenFormWithDraftHandler.OpenResult result = handler.open(
                originView, draftId, ENTITY_NAME, "Workflow Customer");
        assertThat(result.status()).isEqualTo(OpenFormWithDraftHandler.OpenStatus.OPENED);
        ArgumentCaptor<Consumer> handlerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(classNavigator).withAfterNavigationHandler(handlerCaptor.capture());

        TestCustomerDetailView detailView = new TestCustomerDetailView();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(eq(ViewAttributes.class), eq("Customer.detail")))
                .thenReturn(new ViewAttributes("Customer.detail"));
        when(applicationContext.getBean(ViewSupport.class)).thenReturn(mock(ViewSupport.class));
        detailView.setTestApplicationContext(applicationContext);
        handlerCaptor.getValue().accept(new SupportsAfterViewNavigationHandler.AfterViewNavigationEvent<>(
                classNavigator, detailView));
        verify(draftLoader).apply(draftId, detailView.getEditedEntity());
        return detailView;
    }

    private static List<String> coreCustomerImports() throws IOException {
        try (var paths = Files.walk(repositoryRoot().resolve("ai-agent/ai-agent/src/main/java"))) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(CustomerDraftWorkflowTest::containsCoreCustomerImport)
                    .map(Path::toString)
                    .toList();
        }
    }

    private static boolean containsCoreCustomerImport(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(CORE_CUSTOMER_IMPORT);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static String read(String repositoryPath) throws IOException {
        return Files.readString(repositoryRoot().resolve(repositoryPath), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null) {
            if (Files.isDirectory(path.resolve("ai-agent"))
                    && Files.isDirectory(path.resolve("jmix-app"))) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }

    private static final class TestCustomerDetailView extends CustomerDetailView {

        private final Customer editedEntity = mock(Customer.class, CALLS_REAL_METHODS);

        private TestCustomerDetailView() {
            setId("Customer.detail");
        }

        private void setTestApplicationContext(ApplicationContext applicationContext) {
            setApplicationContext(applicationContext);
        }

        @Override
        @NonNull
        public Customer getEditedEntity() {
            return editedEntity;
        }
    }
}
