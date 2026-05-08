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
 * Plan 11-10 Task 1 — TEST-13 mutation-enabled proof. Two contexts:
 * <ul>
 *   <li>{@code AgentToolCallbacksMutationEnabledTest} — {@code mutation.enabled=true} only.
 *       Asserts the 5 mutation callbacks plus all 6 read + 2 link + 1 extraction callbacks are exposed (14),
 *       and {@code delete_record} is absent.</li>
 *   <li>{@code AgentToolCallbacksMutationEnabledAllowDeleteTest} — {@code enabled=true} plus
 *       {@code allowDelete=true}. Asserts {@code delete_record} is STILL absent (D-07 absolute);
 *       the future-signal flag does not unlock a v1.1 delete tool because the @Tool method
 *       simply does not exist on {@code BuiltInMutationTools}.</li>
 * </ul>
 *
 * <p>Two sibling test classes (not @Nested) so each gets its own Spring context with distinct
 * properties.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AgentToolCallbacksMutationEnabledTest {

    @Autowired
    private AgentToolCallbacks agentToolCallbacks;

    @Test
    void mutationEnabled_exposesAllReadLinkAndFourMutationCallbacksButNeverDeleteRecord() {
        ToolCallback[] callbacks = agentToolCallbacks.forCurrentUser();
        List<String> names = Arrays.stream(callbacks)
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toList());

        // Diagnostic — no host ToolContributor beans on the test classpath, exact 14.
        assertThat(names).hasSize(14);

        assertThat(names).contains(
                "list_entities", "describe_entity", "find_records",
                "count_records", "get_record", "get_related_records",
                "generate_entity_list_link", "generate_entity_detail_link");

        assertThat(names).contains(
                "create_record", "update_record", "add_related_record", "remove_related_record",
                "bulk_save_records");
        assertThat(names).contains("prepare_form_draft");

        // delete_record is reserved for v1.2; never shipped in v1.1 under any flag combination.
        assertThat(names).doesNotContain("delete_record");
    }
}

/**
 * Sibling top-level test class with a SECOND property set
 * ({@code allowDelete=true}). v1.1 must still NOT expose {@code delete_record}: the tool method
 * does not exist on {@code BuiltInMutationTools} regardless of the flag.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {
                "ai-agent.tools.mutation.enabled=true",
                "ai-agent.tools.mutation.allowDelete=true"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AgentToolCallbacksMutationEnabledAllowDeleteTest {

    @Autowired
    private AgentToolCallbacks agentToolCallbacks;

    @Test
    void allowDeleteTrue_stillDoesNotExposeDeleteRecord() {
        ToolCallback[] callbacks = agentToolCallbacks.forCurrentUser();
        List<String> names = Arrays.stream(callbacks)
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toList());

        assertThat(names).hasSize(14);
        assertThat(names).contains("bulk_save_records");
        assertThat(names).contains("prepare_form_draft");
        assertThat(names).doesNotContain("delete_record");
    }
}
