package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.exposure.AiExposureRule;
import com.vn.agent.exposure.AiExposureRuleMode;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Plan 11-10 Task 3 — TEST-10 relationship-target LLM exposure denial.
 *
 * <p>Seeds an {@link AiExposureRule} that hides
 * {@code mutationTest_MutationLinkedChildFixture} from the LLM surface (Phase 10 R4 / EXP-09).
 * Calls {@code add_related_record} on
 * {@link com.vn.agent.tools.mutation.fixture.MutationLinkedParentFixture}'s {@code linkedChildren}
 * relationship; the resolver succeeds at the metadata level (the parent is visible), but the
 * child-target LLM exposure check
 * ({@link MutationAuthorizationService#enforceLlmRelationshipTargetExposure}) denies — Jmix
 * {@code AccessManager} would otherwise allow the read/update.
 *
 * <p>Asserts the response is {@code access_denied} and {@code MutationSaveExecutor.save/saveAll}
 * is NEVER called.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsRelationshipExposureTest {

    private static final String LINKED_PARENT_ENTITY = "mutationTest_MutationLinkedParentFixture";
    private static final String LINKED_CHILD_ENTITY = "mutationTest_MutationLinkedChildFixture";

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private Metadata metadata;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @MockitoBean
    private MutationSaveExecutor mutationSaveExecutor;

    private UUID denylistRuleId;

    @BeforeEach
    void seedDenylistOnLinkedChild() {
        // Hide the relationship target from the LLM surface. The parent is left visible so the
        // resolver reaches the child-exposure gate (must-haves contract: child gating proves
        // LlmExposurePolicy is enforced on top of Jmix AccessManager).
        systemAuthenticator.runWithSystem(() -> {
            AiExposureRule rule = metadata.create(AiExposureRule.class);
            rule.setEntityName(LINKED_CHILD_ENTITY);
            rule.setMode(AiExposureRuleMode.EXCLUDE);
            rule.setEnabled(true);
            AiExposureRule saved = unconstrainedDataManager.save(rule);
            denylistRuleId = saved.getId();
        });
    }

    @AfterEach
    void cleanDenylist() {
        if (denylistRuleId == null) return;
        UUID id = denylistRuleId;
        denylistRuleId = null;
        systemAuthenticator.runWithSystem(() ->
                unconstrainedDataManager.load(AiExposureRule.class)
                        .id(id)
                        .optional()
                        .ifPresent(unconstrainedDataManager::remove));
    }

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
