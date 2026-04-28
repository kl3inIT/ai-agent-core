package com.vn.agent.orchestration;

import com.vn.agent.guard.AgentSystemPromptRules;

/**
 * Single composition point for the system prompt sent to the model.
 */
public final class SystemPromptComposer {

    private SystemPromptComposer() {
        // Utility class.
    }

    public static String compose(String baselineText, String profileSystemPrompt) {
        return (baselineText == null ? "" : baselineText)
                + AgentSystemPromptRules.PROMPT_RULES
                + (profileSystemPrompt != null && !profileSystemPrompt.isBlank()
                        ? "\n\n" + profileSystemPrompt
                        : "");
    }
}
