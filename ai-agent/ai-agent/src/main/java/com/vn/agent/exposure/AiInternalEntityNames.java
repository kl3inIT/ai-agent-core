package com.vn.agent.exposure;

import java.util.Set;

/**
 * Entity names owned by the AI add-on itself. These are implementation/governance
 * tables, not business entities for the LLM tool surface.
 */
public final class AiInternalEntityNames {

    private static final Set<String> NAMES = Set.of(
            "ai_AiAuditEvent",
            "ai_AiConversation",
            "ai_AiMessage",
            "ai_AiKnowledgeDocument",
            "ai_AiParameters",
            "aiExposure_AiExposureRule",
            "aiMutation_AiMutationIntent"
    );

    private AiInternalEntityNames() {
    }

    public static boolean contains(String entityName) {
        return NAMES.contains(entityName);
    }

    public static Set<String> all() {
        return NAMES;
    }
}
