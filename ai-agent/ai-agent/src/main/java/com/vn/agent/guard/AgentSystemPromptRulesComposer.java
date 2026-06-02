package com.vn.agent.guard;

import com.vn.agent.action.ActionIntentId;
import com.vn.agent.tools.mutation.AiAgentMutationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Phase 11 MUT-10 sibling top-level composer that selects between
 * {@link AgentSystemPromptRules#PROMPT_RULES} (read-only baseline, applied on every chat turn)
 * and {@code PROMPT_RULES + MUTATION_PROMPT_RULES} (when
 * {@code ai-agent.tools.mutation.enabled=true}).
 *
 * <p>Wave-4 prompt composer call sites (currently {@code DefaultChatServiceImpl} blocking
 * {@code ask(...)} AND streaming {@code stream(...)} per Phase 9 P05) inject this and call
 * {@link #effectiveRules()} where they previously read the bare
 * {@link AgentSystemPromptRules#PROMPT_RULES} constant.
 *
 * <p><b>Sibling top-level (NOT nested static)</b> because the codebase has no precedent for
 * nested {@code @Component} static classes; sibling top-level matches existing structure
 * ({@link AgentSystemPromptRules} stays a constants holder; the conditional Spring component
 * lives next to it). This also avoids loader-ordering quirks where a nested
 * {@code @Component} on a constants class could be picked up by component scanning before its
 * outer class is fully initialized.
 *
 * <p><b>Forward-reference safety:</b> {@link AgentSystemPromptRules#MUTATION_PROMPT_RULES} does
 * not reference any v1.2+ tool name (e.g. {@code prepare_form_draft}). Verified by
 * acceptance-criteria grep on the constant text in Plan 11-09 Task 2.
 */
@Component
public class AgentSystemPromptRulesComposer {

    private static final String ACTION_PROPOSAL_RULES = String.join("\n",
            "",
            "Action intent rules:",
            "- For create or update requests, gather missing required fields first.",
            "- If the user asks for an ambiguous record count (for example, '2 or 3 records'), ask a clarification question before proposing actions.",
            "- COUNT THE RECORDS FIRST, then pick the tool:",
            "  - EXACTLY ONE record -> call propose_action_choices. Its values argument is a single JSON object. NEVER pass an array/list to values.",
            "  - TWO OR MORE records of the SAME entity in one request (a batch / 'hàng loạt' / 'cùng lúc' request) -> you MUST call propose_bulk_action_choices ONCE with the target entity and the full array of row objects in valuesList. This is the ONLY correct path for multiple records.",
            "- Do NOT split a multi-record batch into several propose_action_choices calls, and do NOT stuff multiple rows into propose_action_choices. propose_action_choices is strictly single-record; propose_bulk_action_choices is the batch path.",
            "- If propose_action_choices returns status WRONG_TOOL_FOR_BULK, you sent multiple records to the single-record tool. Immediately re-issue the request via propose_bulk_action_choices with all rows in valuesList.",
            "- Do not call create_record, update_record, or bulk_save_records during the planning turn. Proposing choices is the only side-effect-free planning step.",
            "- Wait for the user to choose an action intent before performing a side effect.",
            "");

    private final AiAgentMutationProperties mutationProperties;

    public AgentSystemPromptRulesComposer(AiAgentMutationProperties mutationProperties) {
        this.mutationProperties = mutationProperties;
    }

    /**
     * @return the read-only {@link AgentSystemPromptRules#PROMPT_RULES} baseline, with
     *         {@link AgentSystemPromptRules#MUTATION_PROMPT_RULES} appended when
     *         {@code ai-agent.tools.mutation.enabled=true}. Both constants begin and end with
     *         {@code "\n"} so concatenation yields clean blank-line separators without callers
     *         needing to know the joining convention.
     */
    public String effectiveRules() {
        String baseRules;
        if (mutationProperties.resolvedEnabled()) {
            baseRules = AgentSystemPromptRules.PROMPT_RULES + AgentSystemPromptRules.MUTATION_PROMPT_RULES;
        } else {
            baseRules = AgentSystemPromptRules.PROMPT_RULES;
        }
        return baseRules + ACTION_PROPOSAL_RULES;
    }

    /**
     * Adds the extraction-specific rule suffix for a single named-intent turn.
     */
    public String effectiveRules(String intentId, String label) {
        String baseRules = mutationProperties.resolvedEnabled()
                ? AgentSystemPromptRules.PROMPT_RULES + AgentSystemPromptRules.MUTATION_PROMPT_RULES
                : AgentSystemPromptRules.PROMPT_RULES;
        if (!StringUtils.hasText(intentId)) {
            return effectiveRules();
        }
        String safeIntentId = escapePromptLiteral(intentId.trim());
        String safeLabel = sanitizePromptLabel(StringUtils.hasText(label) ? label.trim() : intentId.trim());
        return baseRules + String.join("\n",
                "",
                "Named extraction intent rules:",
                "- The user selected the named extraction intent '" + safeLabel + "'.",
                "- To fulfill this named-intent turn, you MUST call prepare_form_draft(\""
                        + safeIntentId + "\", contextRefs).",
                "- Call prepare_form_draft at most once for this turn.",
                "- If extracted or generated values are incomplete or ambiguous, ask the user"
                        + " for the missing information instead of inventing values.",
                "- Draft promotion happens only after the user opens the Jmix detail view and"
                        + " clicks Save.",
                ""
        );
    }

    /**
     * Adds the selected post-proposal action rule suffix.
     */
    public String effectiveActionRules(String actionIntentId) {
        String baseRules = effectiveRules();
        if (ActionIntentId.CREATE_NOW.equals(actionIntentId)) {
            return baseRules + String.join("\n",
                    "",
                    "Selected action intent rules:",
                    "- The user selected create-now.",
                    "- Use the collected proposal values from the private per-turn action context.",
                    "- Call create_record only for the selected target entity and only with those collected values.",
                    "- Do not call prepare_form_draft in this turn.",
                    "");
        }
        if (ActionIntentId.BULK_CREATE_NOW.equals(actionIntentId)) {
            return baseRules + String.join("\n",
                    "",
                    "Selected action intent rules:",
                    "- The user selected bulk-create-now (the user already confirmed the whole batch).",
                    "- Use the collected batch rows from the private per-turn action context.",
                    "- Call bulk_save_records EXACTLY ONCE for the selected target entity with the full"
                            + " array of rows and a single fresh UUID v4 idempotencyKey for the batch.",
                    "- Do not call create_record per row, do not split the batch, and do not call"
                            + " propose_bulk_action_choices or prepare_form_draft in this turn.",
                    "");
        }
        if (ActionIntentId.PREFILL_FORM.equals(actionIntentId)) {
            return baseRules + String.join("\n",
                    "",
                    "Selected action intent rules:",
                    "- The user selected prefill-form.",
                    "- Prepare a draft/form path only.",
                    "- Do not call create_record, update_record, or bulk_save_records in this turn.",
                    "");
        }
        return baseRules;
    }

    private static String escapePromptLiteral(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static String sanitizePromptLabel(String value) {
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace("'", "\\'");
    }
}
