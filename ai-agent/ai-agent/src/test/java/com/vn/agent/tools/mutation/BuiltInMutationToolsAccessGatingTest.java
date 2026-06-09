package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.AccessManager;
import io.jmix.core.Metadata;
import io.jmix.core.accesscontext.EntityAttributeContext;
import org.junit.jupiter.api.BeforeEach;
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
 * Plan 11-10 Task 3 — TEST-10 access gating for {@link BuiltInMutationTools}.
 *
 * <p>Three access-denial paths are pinned BEFORE any host save / saveAll / reservation runs:
 * <ol>
 *   <li><b>Per-attribute denial</b> — current user has CRUD CREATE on the entity but
 *       {@link io.jmix.core.accesscontext.EntityAttributeContext#canModify()} returns false for
 *       attribute {@code secret}. {@link MutationAuthorizationService#enforceAttributeWriteAccess}
 *       throws {@code access_denied} BEFORE the request hash is computed.</li>
 *   <li><b>Missing marker authority</b> — user has fixture CRUD but lacks
 *       {@link com.vn.agent.security.AiAgentMutationRole}. The marker-role gate fires FIRST in
 *       every {@code @Tool} method body.</li>
 *   <li><b>Fake (substring) marker authority</b> — user has an authority whose string CONTAINS
 *       {@code AiAgentMutationRole.CODE} but is NOT equal to the Jmix-emitted authority.
 *       Substring matching is forbidden by the must-haves contract.</li>
 * </ol>
 *
 * <p>{@code @MockitoBean MutationSaveExecutor} captures the negative invariant: across all three
 * denial paths, neither {@code save(Object)} nor {@code saveAll(Object...)} is ever called.
 *
 * <p>{@code @MockitoBean AccessManager}: default mock is a no-op, which means every
 * {@link io.jmix.core.accesscontext.AccessContext} keeps its permissive default
 * (Jmix's {@code EntityAttributeContext} defaults to {@code modifyPermitted=true};
 * {@code CrudEntityContext} defaults to all CRUD permitted). The stub denies modify ONLY for
 * attribute {@code secret} so the per-attribute gate fires while every other attribute and the
 * entity-level CRUD gate stay permitted.
 */
@SpringBootTest(classes = {AITestConfiguration.class, MutationFixturePersistenceTestConfiguration.class},
        properties = {
                "jmix.ai-agent.tools.mutation.enabled=true",
                "jmix.ai-agent.audit.hash-sensitive-fields=true",
                "jmix.ai-agent.audit.sensitive-fields=secret"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsAccessGatingTest {

    private static final String FIXTURE_ENTITY = "mutationTest_MutationTestFixture";

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private Metadata metadata;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @MockitoBean
    private MutationIntentRepository mutationIntentRepository;

    @MockitoBean
    private AccessManager accessManager;

    @MockitoBean
    private MutationSaveExecutor mutationSaveExecutor;

    @BeforeEach
    void wireAccessManager() {
        // Per-attribute deny: ONLY the "secret" attribute is denied modify. Every other
        // EntityAttributeContext and every CrudEntityContext keeps its permissive default.
        org.mockito.Mockito.doAnswer(invocation -> {
            Object ctx = invocation.getArgument(0);
            if (ctx instanceof EntityAttributeContext attr) {
                String pathString = attr.getPropertyPath().toString();
                if ("secret".equals(pathString)) {
                    attr.setModifyDenied();
                }
            }
            return null;
        }).when(accessManager).applyRegisteredConstraints(any());
    }

    @Test
    void perAttributeDenial_blocksBeforeReservationAndSave() throws Exception {
        // Smoke check the Plan 11-07B-owned fixtures are registered before invoking the tool.
        assertThat(metadata.getClass(FIXTURE_ENTITY)).isNotNull();
        assertThat(metadata.getClass("mutationTest_MutationParentFixture")).isNotNull();
        assertThat(metadata.getClass("mutationTest_MutationChildFixture")).isNotNull();
        assertThat(metadata.getClass("mutationTest_MutationLinkedParentFixture")).isNotNull();
        assertThat(metadata.getClass("mutationTest_MutationLinkedChildFixture")).isNotNull();

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("secret", "value");

        String json = mutationToolTestContext.withMutationRun("mutation-admin", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes,
                        UUID.randomUUID().toString()));

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode parsed = objectMapper.readTree(json);
        assertThat(parsed.path("error").asText())
                .as("per-attribute denial must surface as access_denied")
                .isEqualTo("access_denied");

        verifyNoReservationOrHostSave();
    }

    @Test
    void missingMarkerAuthority_isDeniedBeforeReservationAndSave() throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Alice");

        String json = mutationToolTestContext.withMutationRun("mutation-no-marker", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes,
                        UUID.randomUUID().toString()));

        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readTree(json).path("error").asText())
                .as("missing marker role must collapse to access_denied")
                .isEqualTo("access_denied");

        verifyNoReservationOrHostSave();
    }

    @Test
    void fakeSubstringMarkerAuthority_isDeniedBeforeReservationAndSave() throws Exception {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Bob");

        String json = mutationToolTestContext.withMutationRun("mutation-fake-marker", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes,
                        UUID.randomUUID().toString()));

        ObjectMapper objectMapper = new ObjectMapper();
        assertThat(objectMapper.readTree(json).path("error").asText())
                .as("authority whose string CONTAINS the role code (but is not the Jmix-created authority) "
                        + "must NOT pass the marker-role gate (substring/contains check forbidden)")
                .isEqualTo("access_denied");

        verifyNoReservationOrHostSave();
    }

    private void verifyNoReservationOrHostSave() {
        verify(mutationIntentRepository, never()).reserveOrReplay(
                any(), any(), any(), any(), any(), any());
        verify(mutationSaveExecutor, never()).save(any());
        verify(mutationSaveExecutor, never()).saveAll(any());
    }
}
