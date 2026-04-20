package com.vn.agent.orchestration;

import java.util.UUID;

/**
 * Thrown by {@code ConversationGateway.loadOrCreate(...)} when the requested conversationId is
 * either absent or hidden by the row-level predicate from {@code AiAgentUserRowLevelRole}
 * (Phase 2). Per D-09 (CONTEXT.md) and Pitfall #8 (RESEARCH.md), the message string MUST be
 * identical for both branches so attackers cannot probe conversation-id existence by comparing
 * exception payloads.
 *
 * <p>Message comes from the i18n key {@code com.vn.agent.orchestration/ConversationNotFound}
 * (English: "Conversation not found", Vietnamese: "Không tìm thấy cuộc hội thoại"). The literal
 * fallback in this class is used only if the message bundle is somehow unavailable; tests assert
 * equality of the exception message between the two failure branches against the literal value.</p>
 */
public class ConversationNotFoundException extends RuntimeException {

    /** i18n key for the user-facing message. */
    public static final String MESSAGE_KEY = "com.vn.agent.orchestration/ConversationNotFound";

    /** Literal English fallback — also the default used by ConversationGateway tests. */
    public static final String DEFAULT_MESSAGE = "Conversation not found";

    private final UUID conversationId;

    public ConversationNotFoundException(UUID conversationId) {
        super(DEFAULT_MESSAGE);
        this.conversationId = conversationId;
    }

    /** Caller diagnostic only; never echoed in the message string. */
    public UUID getConversationId() { return conversationId; }
}
