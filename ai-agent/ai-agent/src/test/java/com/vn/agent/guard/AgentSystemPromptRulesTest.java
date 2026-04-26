package com.vn.agent.guard;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 PROMPT-03 + D-15 contract: the hardcoded English vocabulary + unknown-entity-retry
 * rules carried by {@link AgentSystemPromptRules#PROMPT_RULES} must include the verbatim
 * substrings TEST-08 (Plan 09-06) cross-asserts against {@code BuiltInDataTools.UNKNOWN_ENTITY_HINTS}.
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
        // These exact substrings must match BuiltInDataTools.UNKNOWN_ENTITY_HINTS — TEST-08 bar
        // that both the system prompt AND ToolErrorDto.expected[] carry the same wording.
        assertThat(AgentSystemPromptRules.PROMPT_RULES)
                .as("D-15 hint #1 verbatim")
                .contains("call list_entities exactly once");
        assertThat(AgentSystemPromptRules.PROMPT_RULES)
                .as("D-15 hint #2 verbatim — retry-on-match clause")
                .contains("if a name in list_entities matches your intent, retry the original tool with that exact name");
        assertThat(AgentSystemPromptRules.PROMPT_RULES)
                .as("D-15 hint #3 verbatim — em dash U+2014 preserved on the give-up clause")
                .contains("if no entity in list_entities matches, tell the user no such entity exists — do not guess");
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
    void agentSystemPromptRules_isFinalConstantsHolder() throws Exception {
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
