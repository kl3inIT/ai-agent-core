package com.vn.agent;

import java.util.UUID;

/**
 * Primary entry point for the AI Agent add-on.
 *
 * <p>Phase 1 contract (per D-03 in 01-CONTEXT.md): blocking single-turn ask, no memory, no tools, no RAG.
 * Future phases will extend with streaming, memory, tools, and advisor chain without changing this signature.</p>
 *
 * @since 0.0.1
 */
public interface ChatService {

    /**
     * Send a single user message to the configured LLM and receive a blocking response.
     *
     * @param message        the user's message (non-null, non-blank)
     * @param conversationId correlation id for this conversation; recorded in response metadata, not persisted in Phase 1
     * @param userKey        caller identity; nullable in Phase 1 (anonymous flow). Phase 2+ wires to {@code CurrentAuthentication}.
     * @return the assistant's response with content and metadata
     */
    ChatResponse ask(String message, UUID conversationId, String userKey);
}
