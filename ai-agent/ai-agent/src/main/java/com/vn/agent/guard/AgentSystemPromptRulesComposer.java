package com.vn.agent.guard;

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
        if (mutationProperties.resolvedEnabled()) {
            return AgentSystemPromptRules.PROMPT_RULES + AgentSystemPromptRules.MUTATION_PROMPT_RULES;
        }
        return AgentSystemPromptRules.PROMPT_RULES;
    }

    /**
     * Adds the extraction-specific rule suffix for a single named-intent turn.
     */
    public String effectiveRules(String intentId, String label) {
        String baseRules = effectiveRules();
        if (!StringUtils.hasText(intentId)) {
            return baseRules;
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
