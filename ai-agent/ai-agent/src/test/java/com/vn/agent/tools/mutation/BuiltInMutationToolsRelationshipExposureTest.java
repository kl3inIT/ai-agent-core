package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TEST-10 relationship-target LLM exposure denial.
 *
 * <p>Hides {@code mutationTest_MutationLinkedChildFixture} from the LLM surface via the static
 * schema-visibility denylist ({@code jmix.ai-agent.tools.hidden-entities}). Calls
 * {@code add_related_record} on
 * {@link com.vn.agent.tools.mutation.fixture.MutationLinkedParentFixture}'s {@code linkedChildren}
 * relationship; the resolver succeeds at the metadata level (the parent is visible), but the
 * child-target LLM exposure check
 * ({@link MutationAuthorizationService#enforceLlmRelationshipTargetExposure}) denies — Jmix
 * {@code AccessManager} would otherwise allow the read/update.
 *
 * <p>Asserts the response is {@code access_denied} and {@code MutationSaveExecutor.save/saveAll}
 * is NEVER called.
 */
@SpringBootTest(classes = {AITestConfiguration.class, MutationFixturePersistenceTestConfiguration.class},
        properties = {
                "jmix.ai-agent.tools.mutation.enabled=true",
                "jmix.ai-agent.tools.hidden-entities=mutationTest_MutationLinkedChildFixture"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsRelationshipExposureTest {

    private static final String LINKED_PARENT_ENTITY = "mutationTest_MutationLinkedParentFixture";

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @MockitoBean
    private MutationSaveExecutor mutationSaveExecutor;

    @Test
    void hiddenRelationshipTarget_blocksAddRelatedRecord_beforeAnySave() throws Exception {
        // We don't need the parent/child rows to actually exist — the exposure gate fires
        // before load() runs. The supplied UUIDs are syntactically valid but never read.
        UUID parentId = UUID.randomUUID();
        UUID relatedId = UUID.randomUUID();

        String json = mutationToolTestContext.withMutationRun("mutation-admin", () ->
                builtInMutationTools.addRelatedRecord(
                        LINKED_PARENT_ENTITY,
                        parentId.toString(),
                        "linkedChildren",
                        relatedId.toString(),
                        UUID.randomUUID().toString()));

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode parsed = objectMapper.readTree(json);
        assertThat(parsed.path("error").asText())
                .as("hidden relationship target must surface as access_denied (LlmExposurePolicy "
                        + "gates child entities even when Jmix AccessManager would allow read/update)")
                .isEqualTo("access_denied");

        verify(mutationSaveExecutor, never()).save(any());
        verify(mutationSaveExecutor, never()).saveAll(any());
    }
}
