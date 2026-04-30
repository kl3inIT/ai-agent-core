package com.vn.agent.orchestration;

import com.vn.agent.guard.AgentSystemPromptRules;

/**
 * Single composition point for the system prompt sent to the model.
 *
 * <p>The 2-argument {@link #compose(String, String)} overload is preserved for static preview
 * callers ({@code AiConfigurationView}, {@code BaselineContextView}) and existing tests that
 * pre-date Phase 11; it always uses {@link AgentSystemPromptRules#PROMPT_RULES} (read-only
 * baseline) so previews never leak the mutation-rule paragraph regardless of property state.
 *
 * <p>The 3-argument {@link #compose(String, String, String)} overload is the Phase 11 MUT-10
 * conditional composition seam — chat execution paths
 * ({@code DefaultChatServiceImpl.ask} blocking + {@code DefaultChatServiceImpl.stream})
 * pass {@code AgentSystemPromptRulesComposer.effectiveRules()} which appends
 * {@link AgentSystemPromptRules#MUTATION_PROMPT_RULES} when
 * {@code ai-agent.tools.mutation.enabled=true}.
 */
public final class SystemPromptComposer {

    private SystemPromptComposer() {
        // Utility class.
    }

    /**
     * Static 2-arg overload — uses the read-only {@link AgentSystemPromptRules#PROMPT_RULES}
     * baseline. Kept stable for diagnostics views and pre-Phase-11 tests.
     */
    public static String compose(String baselineText, String profileSystemPrompt) {
        return compose(baselineText, profileSystemPrompt, AgentSystemPromptRules.PROMPT_RULES);
    }

    /**
     * Phase 11 MUT-10 conditional overload. Composition order and whitespace rules are identical
     * to the 2-arg overload, except the hardcoded {@code AgentSystemPromptRules.PROMPT_RULES}
     * token is replaced by the caller-supplied {@code promptRules} string.
     */
    public static String compose(String baselineText, String profileSystemPrompt, String promptRules) {
        return (baselineText == null ? "" : baselineText)
                + (promptRules == null ? "" : promptRules)
                + (profileSystemPrompt != null && !profileSystemPrompt.isBlank()
                        ? "\n\n" + profileSystemPrompt
                        : "");
    }
}
