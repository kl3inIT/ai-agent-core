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
        // Mutation-only verbose handling rules must NOT be appended in read-only config. The
        // error-code tokens (parameter_conversion_error, concurrent_modification) now appear in the
        // always-on no-leak rule as examples the model must hide, so they are no longer mutation-
        // only markers; idempotencyKey + the UUID-fabrication guidance remain mutation-only.
        assertThat(rules)
                .doesNotContain("idempotencyKey")
                .doesNotContain("fresh random UUID v4")
                .doesNotContain("Never copy UUID-looking values")
                .doesNotContain("On 'parameter_conversion_error' re-read describe_entity")
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
    void composedRulesForbidLeakingToolNamesErrorCodesAndReasoning() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(true, null, null, null, null));

        String rules = composer.effectiveRules();

        assertThat(rules)
                .contains("Do not leak internals")
                .contains("NEVER reveal internal tool names")
                .contains("create_record")
                .contains("bulk_save_records")
                .contains("NEVER show raw stable error codes")
                .contains("access_denied")
                .contains("idempotency_violation")
                .contains("NEVER narrate step-by-step tool-call reasoning");
    }

    @Test
    void noLeakRuleAppliesEvenWhenMutationToolsDisabled() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(null, null, null, null, null));

        String rules = composer.effectiveRules();

        assertThat(rules)
                .contains("Do not leak internals")
                .contains("NEVER reveal internal tool names")
                .contains("NEVER show raw stable error codes");
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
