package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.AccessManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Plan 11-10 Task 3 — TEST-10 mass-assignment denial.
 *
 * <p>Pins the contract that forbidden attribute names (id / version / createdBy / createdDate /
 * unknown) BLOCK before any {@link MutationSaveExecutor#save} or {@link MutationSaveExecutor#saveAll}
 * call runs.
 *
 * <p>{@link AccessManager} is mocked as a permissive no-op so this test reaches
 * {@link MutationAttributeBinder#validateWritableProperty} and pins the planned
 * {@code validation_failed} code. {@link MutationSaveExecutor} is stubbed so the negative
 * invariant remains explicit.
 */
@SpringBootTest(classes = {AITestConfiguration.class, MutationFixturePersistenceTestConfiguration.class},
        properties = {"ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsMassAssignmentTest {

    private static final String FIXTURE_ENTITY = "mutationTest_MutationTestFixture";

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @MockitoBean
    private MutationSaveExecutor mutationSaveExecutor;

    @MockitoBean
    private AccessManager accessManager;

    @Test
    void primaryKey_id_isBlockedBeforeSave() throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("id", UUID.randomUUID().toString());
        attributes.put("name", "Alice");

        String json = mutationToolTestContext.withMutationRun("mutation-admin", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes,
                        UUID.randomUUID().toString()));

        assertValidationFailedBeforeSave(json);
        verify(mutationSaveExecutor, never()).save(any());
        verify(mutationSaveExecutor, never()).saveAll(any());
    }

    @Test
    void version_attribute_isBlockedBeforeSave() throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("version", 1);
        attributes.put("name", "Alice");

        String json = mutationToolTestContext.withMutationRun("mutation-admin", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes,
                        UUID.randomUUID().toString()));

        assertValidationFailedBeforeSave(json);
        verify(mutationSaveExecutor, never()).save(any());
        verify(mutationSaveExecutor, never()).saveAll(any());
    }

    @Test
    void createdBy_systemAuditField_isBlockedBeforeSave() throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("createdBy", "attacker");

        String json = mutationToolTestContext.withMutationRun("mutation-admin", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes,
                        UUID.randomUUID().toString()));

        assertValidationFailedBeforeSave(json);
        verify(mutationSaveExecutor, never()).save(any());
        verify(mutationSaveExecutor, never()).saveAll(any());
    }

    @Test
    void createdDate_systemAuditField_isBlockedBeforeSave() throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("createdDate", "2026-01-01T00:00:00Z");

        String json = mutationToolTestContext.withMutationRun("mutation-admin", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes,
                        UUID.randomUUID().toString()));

        assertValidationFailedBeforeSave(json);
        verify(mutationSaveExecutor, never()).save(any());
        verify(mutationSaveExecutor, never()).saveAll(any());
    }

    @Test
    void unknownAttribute_isBlockedBeforeSave() throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("doesNotExistOnFixture", "x");

        String json = mutationToolTestContext.withMutationRun("mutation-admin", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes,
                        UUID.randomUUID().toString()));

        assertValidationFailedBeforeSave(json);
        verify(mutationSaveExecutor, never()).save(any());
        verify(mutationSaveExecutor, never()).saveAll(any());
    }

    private static void assertValidationFailedBeforeSave(String json) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode parsed = objectMapper.readTree(json);
        assertThat(parsed.path("error").asText())
                .as("forbidden attribute must surface validation_failed before host save; raw=%s", json)
                .isEqualTo("validation_failed");
        assertThat(parsed.path("expected").toString())
                .as("error guidance must point the LLM to writable attributes / describe_entity")
                .contains("describe_entity");
    }
}
