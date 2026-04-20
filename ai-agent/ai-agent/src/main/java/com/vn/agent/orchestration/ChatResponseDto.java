package com.vn.agent.orchestration;

import java.util.UUID;

/**
 * Response DTO returned by {@code ChatService.ask(userId, conversationId, message)} (ORCH-01).
 *
 * <p>Carries (a) the (possibly-freshly-created) conversation id so callers can continue the
 * thread in subsequent turns, (b) the pre-allocated {@code runId} correlating to the chat-level
 * audit rows written by {@code AuditAdvisor} (B8 — pre-allocation eliminates the lifecycle race
 * where {@code RunContext.get()} returned {@code null} at the service layer), (c) the assistant's
 * text content, (d) the effective model slug used for this turn (from {@code AiParametersResolver}),
 * and (e) the wall-clock latency in milliseconds measured around the cached
 * {@code ChatClient.prompt()...call()} invocation.
 *
 * @param conversationId id of the conversation this turn was committed to (never null)
 * @param runId          pre-allocated run id; matches {@code AiToolCallAudit.runId} for this chat
 * @param content        assistant's text response (may be empty on edge-case LLM responses)
 * @param model          OpenRouter {@code provider/model} slug actually used this turn
 * @param latencyMs      wall-clock time across {@code chatClient.prompt().call()}
 */
public record ChatResponseDto(
        UUID conversationId,
        UUID runId,
        String content,
        String model,
        long latencyMs) {
}
