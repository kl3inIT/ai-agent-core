package com.vn.agent.view.chat.fragment;

import org.springframework.context.ApplicationEvent;

import java.io.Serial;
import java.util.UUID;

/**
 * UI-scoped event emitted after an attachment card successfully deletes its row.
 */
public class AiTaskFileDeletedUiEvent extends ApplicationEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID conversationId;

    public AiTaskFileDeletedUiEvent(Object source, UUID conversationId) {
        super(source);
        this.conversationId = conversationId;
    }

    public UUID getConversationId() {
        return conversationId;
    }
}
