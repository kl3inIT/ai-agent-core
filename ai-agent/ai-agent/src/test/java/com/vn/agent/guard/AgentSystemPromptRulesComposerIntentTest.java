package com.vn.agent.guard;

import com.vn.agent.action.ActionIntentId;
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

    @Test
    void planningRulesRouteMultiRecordRequestsToBulkActionProposalTool() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(true, null, null, null, null));

        String rules = composer.effectiveRules();

        assertThat(rules)
                .contains("propose_bulk_action_choices")
                .contains("TWO OR MORE records of the SAME entity");
    }

    @Test
    void planningRulesMakeBulkTheUnambiguousMandatoryPathAndForbidArraysInSingleTool() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(true, null, null, null, null));

        String rules = composer.effectiveRules();

        assertThat(rules)
                // single tool is explicitly single-record and forbids arrays in values
                .contains("EXACTLY ONE record -> call propose_action_choices")
                .contains("NEVER pass an array/list to values")
                // bulk is the mandatory path for >=2 records
                .contains("you MUST call propose_bulk_action_choices ONCE")
                .contains("the ONLY correct path for multiple records")
                // self-correction guidance when the model slips
                .contains("WRONG_TOOL_FOR_BULK")
                .contains("re-issue the request via propose_bulk_action_choices");
    }

    @Test
    void bulkCreateNowActionRulesInstructBulkSaveRecordsExactlyOnce() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(true, null, null, null, null));

        String rules = composer.effectiveActionRules(ActionIntentId.BULK_CREATE_NOW);

        assertThat(rules)
                .contains("Selected action intent rules:")
                .contains("bulk-create-now")
                .contains("Call bulk_save_records EXACTLY ONCE")
                .contains("single fresh UUID v4 idempotencyKey");
    }

    @Test
    void createNowActionRulesRemainSingleRecordCreate() {
        AgentSystemPromptRulesComposer composer = new AgentSystemPromptRulesComposer(
                new AiAgentMutationProperties(true, null, null, null, null));

        String rules = composer.effectiveActionRules(ActionIntentId.CREATE_NOW);

        assertThat(rules)
                .contains("The user selected create-now.")
                .contains("Call create_record only for the selected target entity")
                .doesNotContain("Call bulk_save_records EXACTLY ONCE");
    }
}
