package com.vn.agent.conversation;

import java.util.Locale;
import java.util.UUID;

public record ConversationTitleEligibleEvent(
        UUID conversationId,
        String userUsername,
        UUID runId,
        Locale localeHint) {

    public ConversationTitleEligibleEvent {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        userUsername = userUsername == null || userUsername.isBlank() ? "unknown" : userUsername;
    }
}
