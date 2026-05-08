package com.vn.agent.guard;

import com.vn.agent.tools.mutation.AiAgentMutationProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSystemPromptRulesComposerIntentTest {

    @Test
    void autoIntentKeepsBaselineRulesWithoutExtractionToolInstruction() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(null, null, null, null, null));

        String rules = composer.effectiveRules(null, "Customer");

        assertThat(rules)
                .isEqualTo(composer.effectiveRules())
                .doesNotContain("Named extraction intent rules:")
                .doesNotContain("prepare_form_draft");
    }

    @Test
    void namedIntentAppendsPrepareFormDraftRuleWithoutRawPayloadTerms() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(null, null, null, null, null));

        String rules = composer.effectiveRules("customer-from-source", "Customer draft");

        assertThat(rules)
                .startsWith(AgentSystemPromptRules.PROMPT_RULES)
                .contains("Named extraction intent rules:")
                .contains("prepare_form_draft(\"customer-from-source\", contextRefs)")
                .contains("Call prepare_form_draft at most once for this turn")
                .contains("ask the user for the missing information instead of inventing values")
                .contains("Draft promotion happens only after the user opens the Jmix detail view")
                .doesNotContain("payloadJson")
                .doesNotContain("raw file content");
    }
}
