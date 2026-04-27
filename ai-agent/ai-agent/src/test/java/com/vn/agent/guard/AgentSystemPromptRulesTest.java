package com.vn.agent.guard;

import com.vn.agent.tools.UnknownEntityHints;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 PROMPT-03 + D-15 contract: the hardcoded English vocabulary + unknown-entity-retry
 * rules carried by {@link AgentSystemPromptRules#PROMPT_RULES} must include the verbatim
 * substrings TEST-08 (Plan 09-06) cross-asserts against {@link UnknownEntityHints}.
 *
 * <p>The test treats {@link AgentSystemPromptRules#PROMPT_RULES} as the single ground-truth
 * for both the system-prompt and tool-error-envelope wording — mismatched substrings here
 * indicate that one side drifted away from D-14 and must be reconciled before the regression
 * gate in TEST-08 fails.
 */
class AgentSystemPromptRulesTest {

    @Test
    void promptRules_forbidHostPrefixVocabulary() {
        assertThat(AgentSystemPromptRules.PROMPT_RULES)
                .as("PROMPT-03: vocabulary rule must forbid internal entity names in user-facing text")
                .contains("do NOT use internal entity names");
    }

    @Test
    void promptRules_forbidToolNameMention() {
        assertThat(AgentSystemPromptRules.PROMPT_RULES)
                .as("PROMPT-03: vocabulary rule must forbid raw tool-name mentions in user-facing text")
                .contains("do NOT mention tool names");
    }

    @Test
    void promptRules_carriesUnknownEntityRetryContract_verbatim() {
        // These exact substrings must match UnknownEntityHints, the shared D-14 source,
        // so both the system prompt AND ToolErrorDto.expected[] carry the same wording.
        for (String hint : UnknownEntityHints.AS_LIST) {
            assertThat(AgentSystemPromptRules.PROMPT_RULES)
                    .as("D-15 hint verbatim: %s", hint)
                    .contains(hint);
        }
        assertThat(AgentSystemPromptRules.PROMPT_RULES)
                .as("'do not guess' substring sanity check (matches D-14 hint #3 partial verbatim)")
                .contains("do not guess");
    }

    @Test
    void promptRules_listsAllSixBuiltInToolNames() {
        for (String name : new String[]{"list_entities", "describe_entity", "find_records",
                                        "count_records", "get_record", "get_related_records"}) {
            assertThat(AgentSystemPromptRules.PROMPT_RULES)
                    .as("Tool name %s must be enumerated in the vocabulary rule", name)
                    .contains(name);
        }
    }

    @Test
    void promptRules_mentionsRetrievalAdvisor() {
        assertThat(AgentSystemPromptRules.PROMPT_RULES)
                .as("PROMPT-03: RETRIEVAL advisor name must be in the forbidden-vocabulary list")
                .contains("RETRIEVAL");
    }

    @Test
    void promptRules_referencesAgentEntitiesInventory() {
        // Pairs the vocabulary rule with the agent.entities baseline block (Plan 09-03).
        assertThat(AgentSystemPromptRules.PROMPT_RULES)
                .as("Vocabulary rule must point the LLM at agent.entities for the human label")
                .contains("agent.entities");
    }

    @Test
    void promptRules_instructExactEntityNameForToolArguments() {
        assertThat(AgentSystemPromptRules.PROMPT_RULES)
                .as("Tool calls must copy entityName from the visible inventory, not invent prefixes")
                .contains("tool arguments named entityName")
                .contains("use exactly one entity name shown in agent.entities or returned by list_entities")
                .contains("Do NOT infer, add, or rewrite application prefixes");
    }

    @Test
    void promptRules_doNotPrimeHardCodedHostPrefixExample() {
        assertThat(AgentSystemPromptRules.PROMPT_RULES)
                .as("Concrete host-prefix examples bias the model toward invented internal names")
                .doesNotContain("jmixapp_Customer");
    }

    @Test
    void builtInDataToolsEntityNameToolParams_doNotPrimeHardCodedHostPrefixExample() throws Exception {
        Path source = Path.of("src/main/java/com/vn/agent/tools/BuiltInDataTools.java");
        if (!Files.exists(source)) {
            source = Path.of("ai-agent/ai-agent/src/main/java/com/vn/agent/tools/BuiltInDataTools.java");
        }
        String body = Files.readString(source);

        assertThat(body)
                .as("entityName tool metadata should use exact-name inventory wording")
                .contains("Exact entity name from agent.entities or list_entities")
                .contains("do not infer or add prefixes")
                .doesNotContain("jmixapp_Order");
    }

    @Test
    void agentSystemPromptRules_isFinalConstantsHolder() {
        Class<?> clazz = AgentSystemPromptRules.class;
        assertThat(Modifier.isFinal(clazz.getModifiers()))
                .as("Constants holder must be a final class")
                .isTrue();

        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        assertThat(constructors)
                .as("Constants holder must declare exactly one private constructor")
                .hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers()))
                .as("Constructor must be private to prevent accidental instantiation")
                .isTrue();
    }
}
