package com.vn.agent.guard;

import com.vn.agent.tools.mutation.AiAgentMutationProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSystemPromptRulesComposerTest {

    @Test
    void defaultConfigUsesBaselineRulesWithoutMutationOnlyTerms() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(null, null, null, null, null));

        String rules = composer.effectiveRules();

        assertThat(rules)
                .startsWith(AgentSystemPromptRules.PROMPT_RULES)
                .contains("Vocabulary rules:")
                .contains("Action intent rules:")
                .contains("propose_action_choices")
                .contains("Wait for the user to choose an action intent before performing a side effect.");
        assertThat(rules)
                .doesNotContain("idempotencyKey")
                .doesNotContain("parameter_conversion_error")
                .doesNotContain("concurrent_modification")
                .contains("generate_entity_detail_link")
                .doesNotContain("prepare_form_draft");
    }

    @Test
    void mutationEnabledConfigAppendsMutationRulesWithoutForwardReferenceTools() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(true, null, null, null, null));

        String rules = composer.effectiveRules();

        assertThat(rules).startsWith(AgentSystemPromptRules.PROMPT_RULES);
        assertThat(rules).contains(AgentSystemPromptRules.MUTATION_PROMPT_RULES);
        assertThat(rules)
                .contains("Action intent rules:")
                .contains("propose_action_choices")
                .contains("idempotencyKey")
                .contains("fresh random UUID v4")
                .contains("third group is '4'")
                .contains("fourth group is one of '8', '9', 'a', or 'b'")
                .contains("Never copy UUID-looking values")
                .contains("access_denied")
                .contains("parameter_conversion_error")
                .contains("concurrent_modification")
                .contains("generate_entity_detail_link")
                .contains("immediately call generate_entity_detail_link")
                .doesNotContain("prepare_form_draft");
    }

    @Test
    void namedIntentAppendsExtractionRulesOnlyForNamedIntent() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(null, null, null, null, null));

        assertThat(composer.effectiveRules(null, "Customer"))
                .isEqualTo(composer.effectiveRules());

        String rules = composer.effectiveRules("customer-from-source", "Customer");

        assertThat(rules)
                .startsWith(AgentSystemPromptRules.PROMPT_RULES)
                .contains("Named extraction intent rules:")
                .contains("prepare_form_draft(\"customer-from-source\", contextRefs)")
                .contains("Call prepare_form_draft at most once")
                .contains("ask the user for the missing information instead of inventing values")
                .contains("Jmix detail view")
                .doesNotContain("payloadJson");
    }
}
