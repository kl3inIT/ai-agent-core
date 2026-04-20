package com.vn.agent;

import java.util.Map;

/**
 * Phase-1 blocking response record. Superseded in Phase 4 by
 * {@link com.vn.agent.orchestration.ChatResponseDto}, which carries the pre-allocated runId,
 * effective model slug, and wall-clock latency (ORCH-01/B8). Kept temporarily for migration
 * window; to be deleted once no external callers remain.
 *
 * @param content  the assistant's text response (non-null, may be empty on edge-case LLM responses)
 * @param metadata correlation/debug info (e.g. conversationId); never null, may be empty
 * @deprecated Use {@link com.vn.agent.orchestration.ChatResponseDto}.
 */
@Deprecated(since = "0.0.4", forRemoval = true)
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
