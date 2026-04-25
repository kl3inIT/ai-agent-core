package com.vn.agent.audit;

/**
 * OQ-1 closure constants. RESOLVED 2026-04-20 via javap inspection of
 * spring-ai-client-chat-1.1.4.jar (class
 * org.springframework.ai.chat.client.advisor.ToolCallAdvisor$Builder).
 *
 * <p>This class has no runtime behavior. Plan 04-04 ChatClientFactory references
 * {@link #RESOLVED_BUILDER_METHOD} symbolically so any future API drift surfaces
 * as a compile-time failure rather than a silent change.
 */
@SuppressWarnings("unused") // Symbolic drift-detection constants (see class javadoc).
public final class ToolCallAdvisorBuilderConstants {

    /** Verified FQN of ToolCallAdvisor in 1.1.4. */
    public static final String TOOL_CALL_ADVISOR_FQN =
            "org.springframework.ai.chat.client.advisor.ToolCallAdvisor";

    /** Verified Builder method that controls internal conversation history. */
    public static final String RESOLVED_BUILDER_METHOD = "conversationHistoryEnabled";

    /** Verified internal field name (used by AdvisorOrderStructuralTest reflection). */
    public static final String INTERNAL_FLAG_FIELD = "conversationHistoryEnabled";

    /** Verified order-setter method name (NOT "order"). */
    public static final String ORDER_SETTER_METHOD = "advisorOrder";

    // VERIFIED 2026-04-20 javap output (org/springframework/ai/chat/client/advisor/ToolCallAdvisor$Builder.class):
    //   public T conversationHistoryEnabled(boolean);
    //   public T disableMemory();
    //   public T advisorOrder(int);
    //   public org.springframework.ai.chat.client.advisor.ToolCallAdvisor build();

    private ToolCallAdvisorBuilderConstants() {}
}
