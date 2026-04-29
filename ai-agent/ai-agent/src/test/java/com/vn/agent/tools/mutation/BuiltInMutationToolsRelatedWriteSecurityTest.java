package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.mutation.fixture.MutationChildFixture;
import com.vn.agent.tools.mutation.fixture.MutationLinkedChildFixture;
import com.vn.agent.tools.mutation.fixture.MutationLinkedParentFixture;
import com.vn.agent.tools.mutation.fixture.MutationParentFixture;
import io.jmix.core.AccessManager;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Related-write success and fail-closed security matrix.
 *
 * <p>Positive cases use the Plan 11-07B non-composition fixtures. Rejection cases use the
 * composition fixtures and mocked access/exposure gates to prove denied paths stop before
 * {@link MutationSaveExecutor}.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {
                "ai-agent.tools.mutation.enabled=true",
                "main.liquibase.change-log=com/vn/agent/test_liquibase/test-main-changelog.xml",
                "jmix.ai-agent.audit.hash-sensitive-fields=true",
                "jmix.ai-agent.audit.sensitive-fields=secret"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsRelatedWriteSecurityTest {

    private static final String LINKED_PARENT_ENTITY = "mutationTest_MutationLinkedParentFixture";
    private static final String COMPOSITION_PARENT_ENTITY = "mutationTest_MutationParentFixture";
    private static final String RELATIONSHIP = "linkedChildren";
    private static final String COMPOSITION_RELATIONSHIP = "children";
    private static final String USERNAME = "mutation-user";

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @MockitoBean
    private AccessManager accessManager;

    @MockitoBean
    private LlmExposurePolicy llmExposurePolicy;

    @MockitoBean
    private MutationSaveExecutor mutationSaveExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<UUID> linkedParentIds = new ArrayList<>();
    private final List<UUID> linkedChildIds = new ArrayList<>();
    private final List<UUID> compositionParentIds = new ArrayList<>();
    private final List<UUID> compositionChildIds = new ArrayList<>();
    private final List<String> idempotencyKeys = new ArrayList<>();
    private DenialGate denialGate = DenialGate.NONE;

    @BeforeEach
    void configureDefaults() {
        denialGate = DenialGate.NONE;
        when(llmExposurePolicy.canReadEntity(any())).thenAnswer(invocation -> {
            MetaClass metaClass = invocation.getArgument(0);
            return denialGate != DenialGate.CHILD_LLM_EXPOSURE
                    || !metaClass.getName().equals("mutationTest_MutationLinkedChildFixture");
        });
        when(llmExposurePolicy.canModify(any())).thenAnswer(invocation -> {
            MetaClass metaClass = invocation.getArgument(0);
            return denialGate != DenialGate.CHILD_LLM_EXPOSURE
                    || !metaClass.getName().equals("mutationTest_MutationLinkedChildFixture");
        });
        when(llmExposurePolicy.canCreate(any())).thenReturn(true);
        when(llmExposurePolicy.canUpdate(any())).thenReturn(true);

        doAnswer(invocation -> {
            Object context = invocation.getArgument(0);
            if (context instanceof CrudEntityContext crud) {
                String entityName = crud.getEntityClass().getName();
                if (denialGate == DenialGate.PARENT_UPDATE
                        && entityName.equals("mutationTest_MutationLinkedParentFixture")) {
                    crud.setUpdateDenied();
                }
                if (denialGate == DenialGate.CHILD_READ
                        && entityName.equals("mutationTest_MutationLinkedChildFixture")) {
                    crud.setReadDenied();
                }
                if (denialGate == DenialGate.CHILD_UPDATE
                        && entityName.equals("mutationTest_MutationLinkedChildFixture")) {
                    crud.setUpdateDenied();
                }
            }
            if (context instanceof EntityAttributeContext attr) {
                String propertyPath = attr.getPropertyPath().toString();
                if (denialGate == DenialGate.RELATIONSHIP_ATTRIBUTE
                        && RELATIONSHIP.equals(propertyPath)) {
                    attr.setModifyDenied();
                }
                if (denialGate == DenialGate.INVERSE_ATTRIBUTE
                        && "linkedParent".equals(propertyPath)) {
                    attr.setModifyDenied();
                }
            }
            return null;
        }).when(accessManager).applyRegisteredConstraints(any());

        doAnswer(invocation -> {
            Object parent = invocation.getArgument(0);
            Object child = invocation.getArgument(1);
            return unconstrainedDataManager.save(parent, child);
        }).when(mutationSaveExecutor).saveAll(any(), any());
        doAnswer(invocation -> {
            Object entity = invocation.getArgument(0);
            return unconstrainedDataManager.save(entity);
        }).when(mutationSaveExecutor).save(any());
    }

    @AfterEach
    void cleanRows() {
        systemAuthenticator.runWithSystem(() -> {
            for (UUID id : linkedChildIds) {
                unconstrainedDataManager.load(MutationLinkedChildFixture.class)
                        .id(id)
                        .optional()
                        .ifPresent(unconstrainedDataManager::remove);
            }
            for (UUID id : linkedParentIds) {
                unconstrainedDataManager.load(MutationLinkedParentFixture.class)
                        .id(id)
                        .optional()
                        .ifPresent(unconstrainedDataManager::remove);
            }
            for (UUID id : compositionChildIds) {
                unconstrainedDataManager.load(MutationChildFixture.class)
                        .id(id)
                        .optional()
                        .ifPresent(unconstrainedDataManager::remove);
            }
            for (UUID id : compositionParentIds) {
                unconstrainedDataManager.load(MutationParentFixture.class)
                        .id(id)
                        .optional()
                        .ifPresent(unconstrainedDataManager::remove);
            }
            for (String key : idempotencyKeys) {
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e where e.idempotencyKey = :key")
                        .parameter("key", key)
                        .list()
                        .forEach(unconstrainedDataManager::remove);
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.argumentsJson like :key")
                        .parameter("key", "%" + key + "%")
                        .list()
                        .forEach(unconstrainedDataManager::remove);
            }
        });
        linkedChildIds.clear();
        linkedParentIds.clear();
        compositionChildIds.clear();
        compositionParentIds.clear();
        idempotencyKeys.clear();
    }

    @Test
    void addRelatedRecord_supportedNonCompositionRelationship_succeedsAndAuditsFullArguments()
            throws Exception {
        FixtureIds ids = seedLinkedFixtures(false);
        String key = rememberKey();

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.addRelatedRecord(
                        LINKED_PARENT_ENTITY,
                        ids.parentId().toString(),
                        RELATIONSHIP,
                        ids.childId().toString(),
                        key));

        JsonNode result = objectMapper.readTree(json);
        assertThat(result.path("outcome").asText()).isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        assertThat(UUID.fromString(result.path("parentId").asText())).isEqualTo(ids.parentId());
        assertThat(UUID.fromString(result.path("relatedId").asText())).isEqualTo(ids.childId());
        assertThat(result.path("relationship").asText()).isEqualTo(RELATIONSHIP);

        MutationLinkedChildFixture child = loadLinkedChild(ids.childId());
        assertThat(child.getLinkedParent()).isNotNull();
        assertThat(child.getLinkedParent().getId()).isEqualTo(ids.parentId());

        List<AiAuditEvent> rows = successAuditRows("add_related_record", key);
        assertThat(rows).hasSize(1);
        JsonNode args = objectMapper.readTree(rows.get(0).getArgumentsJson());
        assertThat(args.path("entityName").asText()).isEqualTo(LINKED_PARENT_ENTITY);
        assertThat(args.path("id").asText()).isEqualTo(ids.parentId().toString());
        assertThat(args.path("relationship").asText()).isEqualTo(RELATIONSHIP);
        assertThat(args.path("relatedId").asText()).isEqualTo(ids.childId().toString());
        assertThat(args.path("idempotencyKey").asText()).isEqualTo(key);
        verify(mutationSaveExecutor).saveAll(any(), any());
    }

    @Test
    void removeRelatedRecord_supportedNonCompositionRelationship_succeedsAndClearsInverse()
            throws Exception {
        FixtureIds ids = seedLinkedFixtures(true);
        String key = rememberKey();

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.removeRelatedRecord(
                        LINKED_PARENT_ENTITY,
                        ids.parentId().toString(),
                        RELATIONSHIP,
                        ids.childId().toString(),
                        key));

        JsonNode result = objectMapper.readTree(json);
        assertThat(result.path("outcome").asText()).isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        assertThat(UUID.fromString(result.path("parentId").asText())).isEqualTo(ids.parentId());
        assertThat(UUID.fromString(result.path("relatedId").asText())).isEqualTo(ids.childId());

        MutationLinkedChildFixture child = loadLinkedChild(ids.childId());
        assertThat(child.getLinkedParent()).isNull();

        List<AiAuditEvent> rows = successAuditRows("remove_related_record", key);
        assertThat(rows).hasSize(1);
        JsonNode args = objectMapper.readTree(rows.get(0).getArgumentsJson());
        assertThat(args.path("entityName").asText()).isEqualTo(LINKED_PARENT_ENTITY);
        assertThat(args.path("id").asText()).isEqualTo(ids.parentId().toString());
        assertThat(args.path("relationship").asText()).isEqualTo(RELATIONSHIP);
        assertThat(args.path("relatedId").asText()).isEqualTo(ids.childId().toString());
        assertThat(args.path("idempotencyKey").asText()).isEqualTo(key);
        verify(mutationSaveExecutor).saveAll(any(), any());
    }

    @Test
    void parentUpdateDenial_blocksBeforeExecutor() throws Exception {
        assertDeniedGate(DenialGate.PARENT_UPDATE, "add_related_record");
    }

    @Test
    void removeParentUpdateDenial_blocksBeforeExecutor() throws Exception {
        assertDeniedGate(DenialGate.PARENT_UPDATE, "remove_related_record");
    }

    @Test
    void relationshipAttributeDenial_blocksBeforeExecutor() throws Exception {
        assertDeniedGate(DenialGate.RELATIONSHIP_ATTRIBUTE, "add_related_record");
    }

    @Test
    void childReadDenial_blocksBeforeExecutor() throws Exception {
        assertDeniedGate(DenialGate.CHILD_READ, "add_related_record");
    }

    @Test
    void childUpdateDenial_blocksBeforeExecutor() throws Exception {
        assertDeniedGate(DenialGate.CHILD_UPDATE, "add_related_record");
    }

    @Test
    void inverseAttributeDenial_blocksBeforeExecutor() throws Exception {
        assertDeniedGate(DenialGate.INVERSE_ATTRIBUTE, "add_related_record");
    }

    @Test
    void childLlmExposureDenial_blocksBeforeExecutor() throws Exception {
        assertDeniedGate(DenialGate.CHILD_LLM_EXPOSURE, "add_related_record");
    }

    @Test
    void removeCompositionOrOrphanRemovalRelationship_returnsValidationFailedBeforeExecutor()
            throws Exception {
        CompositionFixtureIds ids = seedCompositionFixtures();
        String key = rememberKey();
        clearInvocations(mutationSaveExecutor);

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.removeRelatedRecord(
                        COMPOSITION_PARENT_ENTITY,
                        ids.parentId().toString(),
                        COMPOSITION_RELATIONSHIP,
                        ids.childId().toString(),
                        key));

        assertThat(objectMapper.readTree(json).path("error").asText()).isEqualTo("validation_failed");
        verifyExecutorNotCalled();
    }

    @Test
    void unsupportedRelationshipShape_returnsValidationFailedBeforeExecutor() throws Exception {
        FixtureIds ids = seedLinkedFixtures(false);
        String key = rememberKey();
        clearInvocations(mutationSaveExecutor);

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.addRelatedRecord(
                        LINKED_PARENT_ENTITY,
                        ids.parentId().toString(),
                        "name",
                        ids.childId().toString(),
                        key));

        assertThat(objectMapper.readTree(json).path("error").asText()).isEqualTo("validation_failed");
        verifyExecutorNotCalled();
    }

    private void assertDeniedGate(DenialGate gate, String toolName) throws Exception {
        FixtureIds ids = seedLinkedFixtures("remove_related_record".equals(toolName));
        String key = rememberKey();
        denialGate = gate;
        clearInvocations(mutationSaveExecutor);

        String json = mutationToolTestContext.withMutationRun(USERNAME, () -> {
            if ("remove_related_record".equals(toolName)) {
                return builtInMutationTools.removeRelatedRecord(
                        LINKED_PARENT_ENTITY,
                        ids.parentId().toString(),
                        RELATIONSHIP,
                        ids.childId().toString(),
                        key);
            }
            return builtInMutationTools.addRelatedRecord(
                    LINKED_PARENT_ENTITY,
                    ids.parentId().toString(),
                    RELATIONSHIP,
                    ids.childId().toString(),
                    key);
        });

        assertThat(objectMapper.readTree(json).path("error").asText())
                .as("%s must surface access_denied; raw=%s", gate, json)
                .isEqualTo("access_denied");
        verifyExecutorNotCalled();
    }

    private FixtureIds seedLinkedFixtures(boolean linked) {
        return systemAuthenticator.withSystem(() -> {
            MutationLinkedParentFixture parent = unconstrainedDataManager.create(MutationLinkedParentFixture.class);
            parent.setName("related-parent-" + UUID.randomUUID());
            MutationLinkedParentFixture savedParent = unconstrainedDataManager.save(parent);
            linkedParentIds.add(savedParent.getId());

            MutationLinkedChildFixture child = unconstrainedDataManager.create(MutationLinkedChildFixture.class);
            child.setLabel("related-child-" + UUID.randomUUID());
            if (linked) {
                child.setLinkedParent(savedParent);
            }
            MutationLinkedChildFixture savedChild = unconstrainedDataManager.save(child);
            linkedChildIds.add(savedChild.getId());
            return new FixtureIds(savedParent.getId(), savedChild.getId());
        });
    }

    private CompositionFixtureIds seedCompositionFixtures() {
        return systemAuthenticator.withSystem(() -> {
            MutationParentFixture parent = unconstrainedDataManager.create(MutationParentFixture.class);
            parent.setName("composition-parent-" + UUID.randomUUID());
            MutationParentFixture savedParent = unconstrainedDataManager.save(parent);
            compositionParentIds.add(savedParent.getId());

            MutationChildFixture child = unconstrainedDataManager.create(MutationChildFixture.class);
            child.setLabel("composition-child-" + UUID.randomUUID());
            child.setParent(savedParent);
            MutationChildFixture savedChild = unconstrainedDataManager.save(child);
            compositionChildIds.add(savedChild.getId());
            return new CompositionFixtureIds(savedParent.getId(), savedChild.getId());
        });
    }

    private MutationLinkedChildFixture loadLinkedChild(UUID childId) {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(MutationLinkedChildFixture.class)
                        .id(childId)
                        .one());
    }

    private List<AiAuditEvent> successAuditRows(String eventName, String key) {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.eventName = :eventName " +
                                "and a.outcome = :outcome and a.argumentsJson like :key")
                        .parameter("eventName", eventName)
                        .parameter("outcome", AiToolCallOutcome.SUCCESS)
                        .parameter("key", "%" + key + "%")
                        .list());
    }

    private String rememberKey() {
        String key = UUID.randomUUID().toString();
        idempotencyKeys.add(key);
        return key;
    }

    private void verifyExecutorNotCalled() {
        verify(mutationSaveExecutor, never()).save(any());
        verify(mutationSaveExecutor, never()).saveAll(any(), any());
    }

    private record FixtureIds(UUID parentId, UUID childId) {
    }

    private record CompositionFixtureIds(UUID parentId, UUID childId) {
    }

    private enum DenialGate {
        NONE,
        PARENT_UPDATE,
        RELATIONSHIP_ATTRIBUTE,
        CHILD_LLM_EXPOSURE,
        CHILD_READ,
        CHILD_UPDATE,
        INVERSE_ATTRIBUTE
    }
}
