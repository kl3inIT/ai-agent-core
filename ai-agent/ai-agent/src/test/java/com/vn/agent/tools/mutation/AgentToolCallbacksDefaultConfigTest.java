package com.vn.agent.tools.mutation;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.AgentToolCallbacks;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 11-10 Task 1 — TEST-13 default-off proof.
 *
 * <p>Boots the Spring context with {@code jmix.ai-agent.tools.mutation.enabled} unset, then asserts
 * that {@link AgentToolCallbacks#forCurrentUser()} exposes the 6 read tools, 2 link tools,
 * prepare_form_draft, and the safe action-proposal tool. Mutation callbacks
 * (create/update/add_related/remove_related) and the forbidden {@code delete_record}
 * (D-07 absolute) must be absent.
 *
 * <p>Count assertion is exact (9) because no production {@link com.vn.agent.spi.ToolContributor}
 * beans live on the test classpath; if a future contributor is added, the count line stays as
 * an early-warning diagnostic and the name-based assertions remain authoritative.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AgentToolCallbacksDefaultConfigTest {

    @Autowired
    private AgentToolCallbacks agentToolCallbacks;

    @Test
    void defaultConfig_exposesReadAndLinkToolsButNoMutationOrDelete() {
        ToolCallback[] callbacks = agentToolCallbacks.forCurrentUser();
        List<String> names = Arrays.stream(callbacks)
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toList());

        // Diagnostic: when mutation is OFF and there are no host ToolContributors, exactly
        // 6 read + 2 link + 1 extraction + 2 action-proposal (single + bulk) callbacks should
        // appear. If a future contributor is added, the name-based assertions below remain
        // authoritative.
        assertThat(names).hasSize(11);

        // Read tools (D-09 default surface).
        assertThat(names).contains(
                "list_entities", "describe_entity", "find_records",
                "count_records", "get_record", "get_related_records");

        // Link tools (Plan 11-08, always-on per D-05).
        assertThat(names).contains("generate_entity_list_link", "generate_entity_detail_link");

        // Phase 14 safe planning and draft tools + Workstream A bulk action proposal.
        assertThat(names).contains(
                "prepare_form_draft", "propose_action_choices", "propose_bulk_action_choices");

        // Mutation callbacks must NOT be present when jmix.ai-agent.tools.mutation.enabled is unset.
        assertThat(names).doesNotContain(
                "create_record", "update_record", "add_related_record",
                "remove_related_record", "delete_record");
    }
}
