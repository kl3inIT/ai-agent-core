package com.vn.agent;

import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.orchestration.ConversationNotFoundException;

import java.util.UUID;

/**
 * Primary entry point for the AI Agent add-on (ORCH-01).
 *
 * <p>Phase 4 contract: blocking single-turn {@code ask} that runs through the cached
 * {@code ChatClient} plus the verified advisor chain (audit → memory → tool-calling), writes
 * dual-layer persistence (Spring AI's {@code SPRING_AI_CHAT_MEMORY} + projected {@code AiMessage}
 * rows in the same REQUIRED transaction — D-08), and emits per-run audit rows correlated by a
 * pre-allocated {@code runId} (B8).</p>
 *
 * @since 0.0.1
 */
public interface ChatService {

    /**
     * Send a single user message to the configured LLM and receive a blocking response.
     *
     * @param userId         caller identity (non-null, non-blank); drives conversation ownership
     *                       and row-level security predicates
     * @param conversationId existing conversation id, or {@code null} to auto-create a new
     *                       conversation seeded with {@code message} as title (D-05/D-08)
     * @param message        the user's message (non-null, non-blank)
     * @return the assistant's response with conversationId, runId, content, model, latencyMs
     * @throws ConversationNotFoundException if {@code conversationId} is non-null but the row is
     *         missing OR owned by a different user (D-09 opacity)
     */
    ChatResponseDto ask(String userId, UUID conversationId, String message);
}
