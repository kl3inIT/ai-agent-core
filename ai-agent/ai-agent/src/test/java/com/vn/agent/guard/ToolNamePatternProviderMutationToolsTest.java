package com.vn.agent.guard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ToolNamePatternProviderMutationToolsTest {

    private static final List<String> EXPECTED_BUILT_INS = List.of(
            "list_entities",
            "describe_entity",
            "find_records",
            "count_records",
            "get_record",
            "get_related_records",
            "generate_entity_list_link",
            "generate_entity_detail_link",
            "create_record",
            "update_record",
            "add_related_record",
            "remove_related_record");

    @Test
    void builtInNameListContainsReadLinkAndMutationToolsButNoDeleteRecord() {
        assertThat(ToolNamePatternProvider.BUILT_IN_TOOL_NAMES)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_BUILT_INS)
                .doesNotContain("delete_record");
    }

    @Test
    void compiledPatternMatchesAllBuiltInsAndExcludesDeleteRecord() {
        ToolNamePatternProvider provider = new ToolNamePatternProvider(
                List.of(),
                new AiAgentGuardProperties(null, null, null, null));

        Pattern pattern = Pattern.compile(provider.asPattern().orElseThrow().regex());

        for (String builtInToolName : EXPECTED_BUILT_INS) {
            assertThat(pattern.matcher("assistant mentioned " + builtInToolName + " here").find())
                    .as("scanner must catch built-in tool name %s", builtInToolName)
                    .isTrue();
        }
        assertThat(pattern.matcher("assistant mentioned delete_record here").find()).isFalse();
    }
}
