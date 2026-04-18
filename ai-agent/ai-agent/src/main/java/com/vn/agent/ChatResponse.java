package com.vn.agent;

import java.util.Map;

/**
 * Blocking response from {@link ChatService#ask}.
 *
 * @param content  the assistant's text response (non-null, may be empty on edge-case LLM responses)
 * @param metadata correlation/debug info (e.g. conversationId); never null, may be empty
 */
public record ChatResponse(String content, Map<String, Object> metadata) {
    public ChatResponse {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null (use Map.of() for empty)");
        }
    }
}
