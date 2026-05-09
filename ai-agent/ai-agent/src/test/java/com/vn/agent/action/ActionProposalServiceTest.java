package com.vn.agent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.html.Div;
import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.extraction.AiExtractionProperties;
import com.vn.agent.extraction.MetaClassDtoSynthesizer;
import com.vn.agent.security.AiAgentMutationRole;
import com.vn.agent.tools.ToolEntityResolver;
import com.vn.agent.tools.ToolUserError;
import com.vn.agent.tools.mutation.MutationAuthorizationService;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.view.ViewInfo;
import io.jmix.flowui.view.ViewRegistry;
import io.jmix.flowui.view.View;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.userdetails.User;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionProposalServiceTest {

    private static final String ENTITY_NAME = "jmixapp_Product";

    private ToolEntityResolver toolEntityResolver;
    private MutationAuthorizationService mutationAuthorizationService;
    private MetaClassDtoSynthesizer schemaSynthesizer;
    private AccessManager accessManager;
    private ViewRegistry viewRegistry;
    private Metadata metadata;
    private MessageTools messageTools;
    private DataManager dataManager;
    private CurrentAuthentication currentAuthentication;
    private MetaClass metaClass;
    private ActionProposalService service;

    @BeforeEach
    void setUp() {
        toolEntityResolver = mock(ToolEntityResolver.class);
        mutationAuthorizationService = mock(MutationAuthorizationService.class);
        schemaSynthesizer = mock(MetaClassDtoSynthesizer.class);
        accessManager = mock(AccessManager.class);
        viewRegistry = mock(ViewRegistry.class);
        metadata = mock(Metadata.class);
        messageTools = mock(MessageTools.class);
        dataManager = mock(DataManager.class);
        currentAuthentication = mock(CurrentAuthentication.class);
        metaClass = mock(MetaClass.class);

        AiExtractionProperties extractionProperties = new AiExtractionProperties();
        extractionProperties.setTtlSeconds(120L);

        when(toolEntityResolver.resolveCreatableEntityOrThrow(ENTITY_NAME)).thenReturn(metaClass);
        when(metaClass.getName()).thenReturn(ENTITY_NAME);
        when(metadata.getClass(ENTITY_NAME)).thenReturn(metaClass);
        when(schemaSynthesizer.buildSchema(metaClass)).thenReturn(new MetaClassDtoSynthesizer.SynthesizedSchema(
                "{}", List.of("name", "externalCode"), List.of("name")));
        when(viewRegistry.getDetailViewInfo(metaClass)).thenReturn(new ViewInfo(
                "Product.detail",
                TestProductDetailView.class.getName(),
                TestProductDetailView.class,
                "test-product-detail-view.xml"));
        when(dataManager.create(AiExtractionDraft.class))
                .thenAnswer(invocation -> mock(AiExtractionDraft.class, CALLS_REAL_METHODS));
        when(dataManager.save(any(AiExtractionDraft.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageTools.getEntityCaption(metaClass)).thenReturn("Product");
        when(currentAuthentication.getUser()).thenReturn(
                User.withUsername("alice").password("x").authorities("ROLE_USER").build());

        service = new ActionProposalService(toolEntityResolver, mutationAuthorizationService,
                schemaSynthesizer, accessManager, viewRegistry, metadata, messageTools, dataManager,
                extractionProperties, new ObjectMapper(), currentAuthentication);
    }

    @Test
    void missingRequiredFieldsReturnNoChoices() {
        ActionProposalResult result = service.validate(proposal(Map.of(), List.of(), List.of()));

        assertThat(result.status()).isEqualTo(ActionProposalResult.STATUS_MISSING_FIELDS);
        assertThat(result.missingFields()).containsExactly("name");
        assertThat(result.choices()).isEmpty();
        verify(dataManager, never()).save(any(AiExtractionDraft.class));
    }

    @Test
    void unsupportedAttributesInvalidateProposal() {
        ActionProposalResult result = service.validate(proposal(
                Map.of("name", "Desk", "internalNotes", "secret"), List.of(), List.of()));

        assertThat(result.status()).isEqualTo(ActionProposalResult.STATUS_INVALID);
        assertThat(result.messageKey()).isEqualTo("chatView.actionChoice.invalidProposal");
    }

    @Test
    void deniedEntityReturnsAccessDenied() {
        when(toolEntityResolver.resolveCreatableEntityOrThrow("missing_Entity"))
                .thenThrow(new ToolUserError("unknown_entity", "no such entity"));

        ActionProposalResult result = service.validate(new ActionProposal(
                null, "create", "missing_Entity", "Missing",
                Map.of("name", "Desk"), List.of(), List.of()));

        assertThat(result.status()).isEqualTo(ActionProposalResult.STATUS_ACCESS_DENIED);
        assertThat(result.choices()).isEmpty();
    }

    @Test
    void createPermissionDeniedRemovesCreateNowAndFailsWhenItWasTheOnlyChoice() {
        doThrow(new ToolUserError("access_denied", "missing mutation role"))
                .when(mutationAuthorizationService).enforceMutationRole(AiAgentMutationRole.CODE);

        ActionProposalResult result = service.validate(proposal(
                Map.of("name", "Desk"), List.of(), List.of(ActionIntentId.CREATE_NOW)));

        assertThat(result.status()).isEqualTo(ActionProposalResult.STATUS_ACCESS_DENIED);
        assertThat(result.choices()).isEmpty();
    }

    @Test
    void readyProposalReturnsAllowedChoicesAndCanonicalEntityName() {
        ActionProposalResult result = service.validate(proposal(
                Map.of("name", "Desk"), List.of(), List.of(ActionIntentId.CREATE_NOW, ActionIntentId.PREFILL_FORM)));

        assertThat(result.status()).isEqualTo(ActionProposalResult.STATUS_READY);
        assertThat(result.choices()).containsExactly(ActionIntentId.CREATE_NOW, ActionIntentId.PREFILL_FORM);
        assertThat(result.proposal().targetEntityName()).isEqualTo(ENTITY_NAME);
        assertThat(result.proposal().missingFields()).isEmpty();
    }

    @Test
    void createDraftPersistsValidatedPayloadWithSourceConversation() {
        UUID conversationId = UUID.randomUUID();
        UUID taskFileId = UUID.randomUUID();

        ActionProposalService.DraftResult result = service.createDraft(proposal(
                Map.of("name", "Desk"), List.of(), List.of(ActionIntentId.PREFILL_FORM)),
                conversationId, taskFileId);

        ArgumentCaptor<AiExtractionDraft> draftCaptor = ArgumentCaptor.forClass(AiExtractionDraft.class);
        verify(dataManager).save(draftCaptor.capture());
        AiExtractionDraft draft = draftCaptor.getValue();
        assertThat(result.draftId()).isEqualTo(draft.getId());
        assertThat(draft.getUserUsername()).isEqualTo("alice");
        assertThat(draft.getTargetEntityName()).isEqualTo(ENTITY_NAME);
        assertThat(draft.getIntentId()).isEqualTo("action:" + ActionIntentId.PREFILL_FORM);
        assertThat(draft.getPayloadJson()).contains("\"name\":\"Desk\"");
        assertThat(draft.getSourceConversationId()).isEqualTo(conversationId);
        assertThat(draft.getSourceTaskFileId()).isEqualTo(taskFileId);
        assertThat(Duration.between(draft.getCreatedAt(), draft.getExpiresAt()).getSeconds())
                .isEqualTo(120L);
    }

    @Test
    void createDraftFailsClosedWithoutSourceConversation() {
        assertThatThrownBy(() -> service.createDraft(proposal(
                Map.of("name", "Desk"), List.of(), List.of(ActionIntentId.PREFILL_FORM))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source conversation id");
    }

    private static ActionProposal proposal(Map<String, Object> values,
                                           List<String> missingFields,
                                           List<String> choices) {
        return new ActionProposal(null, "create", ENTITY_NAME, "Desk",
                values, missingFields, choices);
    }

    private static final class TestProductDetailView extends View<Div> {
    }
}
