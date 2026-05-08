package com.vn.agent.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.spi.IntentExtractor;
import com.vn.agent.taskfile.AiTaskFileMediaResolver;
import io.jmix.core.DataManager;
import io.jmix.core.MessageTools;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtractionServiceTest {

    private static final String INTENT_ID = "customer-from-source";
    private static final String ENTITY_NAME = "jmixapp_Customer";

    private IntentRegistry intentRegistry;
    private DataManager dataManager;
    private Metadata metadata;
    private MetadataTools metadataTools;
    private MessageTools messageTools;
    private LlmExposurePolicy llmExposurePolicy;
    private AuditWriter auditWriter;
    private CurrentAuthentication currentAuthentication;
    private AiExtractionProperties extractionProperties;
    private IntentExtractor<Object> extractor;
    private MetaClass metaClass;
    private ExtractionService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        intentRegistry = mock(IntentRegistry.class);
        dataManager = mock(DataManager.class);
        metadata = mock(Metadata.class, RETURNS_DEEP_STUBS);
        metadataTools = mock(MetadataTools.class);
        messageTools = mock(MessageTools.class);
        llmExposurePolicy = mock(LlmExposurePolicy.class);
        auditWriter = mock(AuditWriter.class);
        currentAuthentication = mock(CurrentAuthentication.class);
        extractionProperties = new AiExtractionProperties();
        extractionProperties.setTtlSeconds(120L);
        extractor = mock(IntentExtractor.class);
        metaClass = mock(MetaClass.class);

        when(intentRegistry.find(INTENT_ID)).thenReturn(Optional.of(extractor));
        when(extractor.intentId()).thenReturn(INTENT_ID);
        when(extractor.entityName()).thenReturn(ENTITY_NAME);
        when(metadata.getSession().findClass(ENTITY_NAME)).thenReturn(metaClass);
        when(metaClass.getName()).thenReturn(ENTITY_NAME);
        when(llmExposurePolicy.canReadEntity(metaClass)).thenReturn(true);
        when(llmExposurePolicy.canCreate(metaClass)).thenReturn(true);
        when(dataManager.create(AiExtractionDraft.class))
                .thenAnswer(invocation -> mock(AiExtractionDraft.class, CALLS_REAL_METHODS));
        when(dataManager.save(any(AiExtractionDraft.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageTools.getEntityCaption(metaClass)).thenReturn("Customer");
        when(currentAuthentication.getUser()).thenReturn(
                User.withUsername("alice").password("x").authorities("ROLE_USER").build());

        service = new ExtractionService(intentRegistry, dataManager, metadata, metadataTools,
                messageTools, llmExposurePolicy, auditWriter, currentAuthentication,
                extractionProperties, mock(AiTaskFileMediaResolver.class), new ObjectMapper());
    }

    @Test
    void prepare_success_persistsOneDraftWithOwnerTtlAndSafeAuditSummary() {
        UUID conversationId = UUID.randomUUID();
        UUID firstTaskFileId = UUID.randomUUID();
        LinkedHashMap<String, Object> extracted = new LinkedHashMap<>();
        extracted.put("name", "Acme");
        extracted.put("email", "billing@example.test");
        when(extractor.extract(any())).thenReturn(extracted);

        ExtractionResult result = service.prepare(INTENT_ID,
                new ExtractionInput(INTENT_ID, conversationId, "source text",
                        List.of(firstTaskFileId, UUID.randomUUID()), List.of()));

        org.mockito.ArgumentCaptor<AiExtractionDraft> draftCaptor =
                org.mockito.ArgumentCaptor.forClass(AiExtractionDraft.class);
        verify(dataManager).save(draftCaptor.capture());
        AiExtractionDraft draft = draftCaptor.getValue();
        assertThat(result.draftId()).isEqualTo(draft.getId());
        assertThat(draft.getUserUsername()).isEqualTo("alice");
        assertThat(draft.getTargetEntityName()).isEqualTo(ENTITY_NAME);
        assertThat(draft.getSourceConversationId()).isEqualTo(conversationId);
        assertThat(draft.getSourceTaskFileId()).isEqualTo(firstTaskFileId);
        assertThat(Duration.between(draft.getCreatedAt(), draft.getExpiresAt()).getSeconds())
                .isEqualTo(120L);
        assertThat(result.instanceName()).isEqualTo("Customer draft " + draft.getId());

        org.mockito.ArgumentCaptor<String> summaryCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditWriter).writeToolCall(
                any(), any(), eq("alice"), eq(conversationId), eq("prepare_form_draft"),
                any(), summaryCaptor.capture(), anyLong(), eq(AiToolCallOutcome.SUCCESS),
                isNull(), isNull());
        assertThat(summaryCaptor.getValue())
                .contains("\"draftId\"")
                .contains("\"entityName\"")
                .contains("\"extractedFieldCount\":2")
                .doesNotContain("billing@example.test");
    }

    @Test
    void prepare_exposureDenied_writesDeniedAuditAndNoDraft() {
        UUID conversationId = UUID.randomUUID();
        when(llmExposurePolicy.canReadEntity(metaClass)).thenReturn(false);

        assertThatThrownBy(() -> service.prepare(INTENT_ID,
                new ExtractionInput(INTENT_ID, conversationId, null, List.of(), List.of())))
                .isInstanceOf(ExtractionDeniedException.class);

        verify(extractor, never()).extract(any());
        verify(dataManager, never()).save(any(AiExtractionDraft.class));
        verify(auditWriter).writeToolCall(
                any(), any(), eq("alice"), eq(conversationId), eq("prepare_form_draft"),
                any(), any(), anyLong(), eq(AiToolCallOutcome.DENIED),
                eq("exposure_rule"), eq(ExtractionDeniedException.class.getName()));
    }

    @Test
    void prepare_schemaFailure_writesFailedAuditWithoutRawValuesAndNoDraft() {
        UUID conversationId = UUID.randomUUID();
        LinkedHashMap<String, Object> malformed = new LinkedHashMap<>();
        malformed.put("", "secret@example.test");
        when(extractor.extract(any())).thenReturn(malformed);

        assertThatThrownBy(() -> service.prepare(INTENT_ID,
                new ExtractionInput(INTENT_ID, conversationId, null, List.of(), List.of())))
                .isInstanceOf(ExtractionSchemaException.class);

        verify(dataManager, never()).save(any(AiExtractionDraft.class));
        org.mockito.ArgumentCaptor<String> argumentsCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> summaryCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditWriter).writeToolCall(
                any(), any(), eq("alice"), eq(conversationId), eq("prepare_form_draft"),
                argumentsCaptor.capture(), summaryCaptor.capture(), anyLong(), eq(AiToolCallOutcome.FAILED),
                isNull(), eq(ExtractionSchemaException.class.getName()));
        assertThat(argumentsCaptor.getValue()).doesNotContain("secret@example.test");
        assertThat(summaryCaptor.getValue())
                .contains("\"failureCode\":\"validation_failed\"")
                .doesNotContain("secret@example.test");
    }
}
