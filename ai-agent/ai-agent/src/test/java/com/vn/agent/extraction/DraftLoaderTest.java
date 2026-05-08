package com.vn.agent.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.filter.FilterLiteralValueConverter;
import com.vn.agent.tools.mutation.fixture.MutationChildFixture;
import com.vn.agent.tools.mutation.fixture.MutationParentFixture;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.MetadataTools;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DraftLoaderTest {

    private static final String FIXTURE_ENTITY = "mutationTest_MutationTestFixture";
    private static final String CHILD_ENTITY = "mutationTest_MutationChildFixture";
    private static final String PARENT_ENTITY = "mutationTest_MutationParentFixture";

    private final UUID conversationId = UUID.randomUUID();
    private DataManager dataManager;
    private ExtractionDraftAccess extractionDraftAccess;
    private Metadata metadata;
    private MetadataTools metadataTools;
    private AccessManager accessManager;
    private LlmExposurePolicy llmExposurePolicy;
    private FilterLiteralValueConverter filterLiteralValueConverter;
    private AuditWriter auditWriter;
    private CurrentAuthentication currentAuthentication;
    private DraftLoader draftLoader;

    @BeforeEach
    void setUp() {
        dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        extractionDraftAccess = mock(ExtractionDraftAccess.class);
        metadata = mock(Metadata.class);
        metadataTools = mock(MetadataTools.class);
        accessManager = mock(AccessManager.class);
        llmExposurePolicy = mock(LlmExposurePolicy.class);
        filterLiteralValueConverter = mock(FilterLiteralValueConverter.class);
        auditWriter = mock(AuditWriter.class);
        currentAuthentication = mock(CurrentAuthentication.class);
        when(currentAuthentication.getUser()).thenReturn(
                User.withUsername("alice").password("x").authorities("ROLE_USER").build());
        draftLoader = new DraftLoader(dataManager, extractionDraftAccess, metadata, metadataTools, accessManager,
                llmExposurePolicy, filterLiteralValueConverter, auditWriter,
                currentAuthentication, new ObjectMapper());
    }

    @Test
    void permittedFieldAppliesAndDeniedFieldStaysUnchanged() {
        UUID draftId = UUID.randomUUID();
        MutationTestFixture editingEntity = new MutationTestFixture();
        editingEntity.setSecret("unchanged");
        MetaClass metaClass = metaClass(FIXTURE_ENTITY, MutationTestFixture.class);
        MetaProperty nameProperty = scalarProperty("name");
        MetaProperty secretProperty = scalarProperty("secret");
        when(metadata.getClass(editingEntity)).thenReturn(metaClass);
        when(metaClass.findProperty("name")).thenReturn(nameProperty);
        when(metaClass.findProperty("secret")).thenReturn(secretProperty);
        when(filterLiteralValueConverter.convertValue("Applied", nameProperty)).thenReturn("Applied");
        denySecondAttribute();
        draftRow(draftId, FIXTURE_ENTITY, "{\"name\":\"Applied\",\"secret\":\"hidden\"}");

        DraftApplyResult result = draftLoader.apply(draftId, editingEntity);

        assertThat(editingEntity.getName()).isEqualTo("Applied");
        assertThat(editingEntity.getSecret()).isEqualTo("unchanged");
        assertThat(result.appliedFieldCount()).isEqualTo(1);
        assertThat(result.deniedAttributeCount()).isEqualTo(1);
        assertThat(result.deniedAttributeNames()).containsExactly("secret");
        verifyAudit(draftId, "\"appliedFieldCount\":1", "\"deniedAttributeCount\":1");
    }

    @Test
    void unknownPayloadAttributeIsSkippedAndCounted() {
        UUID draftId = UUID.randomUUID();
        MutationTestFixture editingEntity = new MutationTestFixture();
        MetaClass metaClass = metaClass(FIXTURE_ENTITY, MutationTestFixture.class);
        when(metadata.getClass(editingEntity)).thenReturn(metaClass);
        when(metaClass.findProperty("unknown")).thenReturn(null);
        draftRow(draftId, FIXTURE_ENTITY, "{\"unknown\":\"value\"}");

        DraftApplyResult result = draftLoader.apply(draftId, editingEntity);

        assertThat(result.appliedFieldCount()).isZero();
        assertThat(result.deniedAttributeCount()).isEqualTo(1);
        assertThat(result.deniedAttributeNames()).containsExactly("unknown");
        verifyAudit(draftId, "\"appliedFieldCount\":0", "\"deniedAttributeCount\":1");
    }

    @Test
    void uuidStringRelationshipValueLoadsReferenceThroughSecuredDataManager() {
        UUID draftId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        MutationChildFixture editingEntity = new MutationChildFixture();
        MutationParentFixture parent = new MutationParentFixture();
        MetaClass childMetaClass = metaClass(CHILD_ENTITY, MutationChildFixture.class);
        MetaClass parentMetaClass = metaClass(PARENT_ENTITY, MutationParentFixture.class);
        MetaProperty parentProperty = relationshipProperty("parent", parentMetaClass);
        when(metadata.getClass(editingEntity)).thenReturn(childMetaClass);
        when(childMetaClass.findProperty("parent")).thenReturn(parentProperty);
        when(llmExposurePolicy.canReadEntity(parentMetaClass)).thenReturn(true);
        when(dataManager.load(MutationParentFixture.class).id(parentId).optional())
                .thenReturn(Optional.of(parent));
        draftRow(draftId, CHILD_ENTITY, "{\"parent\":\"" + parentId + "\"}");

        DraftApplyResult result = draftLoader.apply(draftId, editingEntity);

        assertThat(editingEntity.getParent()).isSameAs(parent);
        assertThat(result.appliedFieldCount()).isEqualTo(1);
        assertThat(result.deniedAttributeCount()).isZero();
    }

    @Test
    void expiredDraftIsRejectedBeforeApply() {
        UUID draftId = UUID.randomUUID();
        when(extractionDraftAccess.loadOpenDraft(draftId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> draftLoader.apply(draftId, new MutationTestFixture()))
                .isInstanceOf(DraftNotFoundException.class);
    }

    @Test
    void confirmedDraftIsRejectedBeforeApply() {
        UUID draftId = UUID.randomUUID();
        when(extractionDraftAccess.loadOpenDraft(draftId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> draftLoader.apply(draftId, new MutationTestFixture()))
                .isInstanceOf(DraftNotFoundException.class);
    }

    private void draftRow(UUID draftId, String targetEntityName, String payloadJson) {
        AiExtractionDraft draft = new AiExtractionDraft();
        draft.setId(draftId);
        draft.setTargetEntityName(targetEntityName);
        draft.setIntentId("fixture-intent");
        draft.setSourceConversationId(conversationId);
        draft.setPayloadJson(payloadJson);
        draft.setCreatedAt(OffsetDateTime.now());
        draft.setExpiresAt(OffsetDateTime.now().plusHours(1));
        when(extractionDraftAccess.loadOpenDraft(draftId)).thenReturn(Optional.of(draft));
    }

    private MetaClass metaClass(String name, Class<?> javaClass) {
        MetaClass metaClass = mock(MetaClass.class);
        when(metaClass.getName()).thenReturn(name);
        when(metaClass.getJavaClass()).thenAnswer(invocation -> javaClass);
        return metaClass;
    }

    private MetaProperty scalarProperty(String name) {
        MetaProperty property = mock(MetaProperty.class, RETURNS_DEEP_STUBS);
        Range range = mock(Range.class);
        when(property.getName()).thenReturn(name);
        when(property.getRange()).thenReturn(range);
        when(range.getCardinality()).thenReturn(Range.Cardinality.NONE);
        when(range.isClass()).thenReturn(false);
        when(property.isReadOnly()).thenReturn(false);
        when(metadataTools.isJpa(property)).thenReturn(true);
        return property;
    }

    private MetaProperty relationshipProperty(String name, MetaClass targetMetaClass) {
        MetaProperty property = mock(MetaProperty.class);
        Range range = mock(Range.class);
        when(property.getName()).thenReturn(name);
        when(property.getRange()).thenReturn(range);
        when(range.getCardinality()).thenReturn(Range.Cardinality.MANY_TO_ONE);
        when(range.isClass()).thenReturn(true);
        when(range.asClass()).thenReturn(targetMetaClass);
        when(property.isReadOnly()).thenReturn(false);
        when(metadataTools.isJpa(property)).thenReturn(true);
        return property;
    }

    private void denySecondAttribute() {
        AtomicInteger attributeChecks = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            Object context = invocation.getArgument(0);
            if (context instanceof EntityAttributeContext attributeContext
                    && attributeChecks.incrementAndGet() == 2) {
                attributeContext.setModifyDenied();
            }
            return null;
        }).when(accessManager).applyRegisteredConstraints(any());
    }

    private void verifyAudit(UUID draftId, String appliedSnippet, String deniedSnippet) {
        org.mockito.ArgumentCaptor<String> summaryCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditWriter).writeToolCall(
                any(), any(), eq("alice"), eq(conversationId), eq("extraction.draft_applied"),
                org.mockito.ArgumentMatchers.contains(draftId.toString()), summaryCaptor.capture(),
                anyLong(), eq(AiToolCallOutcome.SUCCESS), isNull(), isNull());
        assertThat(summaryCaptor.getValue())
                .contains(appliedSnippet)
                .contains(deniedSnippet)
                .doesNotContain("Applied")
                .doesNotContain("hidden")
                .doesNotContain("value");
    }
}
