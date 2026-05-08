package com.vn.agent.orchestration;

import com.vn.agent.entity.AiToolCallOutcome;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 7 streaming DTO consumed by ChatView. Bridges Spring AI
 * {@code Flux<ChatResponse>} content chunks with out-of-band tool and
 * citation events emitted via {@code Sinks.Many} (D-01, RESEARCH Pattern 1).
 *
 * <p>Six variants cover the full surface of a single run:
 * <ul>
 *   <li>{@link Content} — an incremental markdown chunk for the assistant bubble.</li>
 *   <li>{@link ToolCall} — a tool invocation was started by the model.</li>
 *   <li>{@link ToolResult} — the matching tool completed (success, blocked, or error).</li>
 *   <li>{@link Citation} — a retrieval citation marker to render inline.</li>
 *   <li>{@link Final} — terminal event carrying run id, conversation id, and usage metrics.</li>
 *   <li>{@link Error} — terminal error event carrying an i18n message key.</li>
 * </ul>
 */
public sealed interface StreamingEvent
        permits StreamingEvent.Content,
                StreamingEvent.ToolCall,
                StreamingEvent.ToolResult,
                StreamingEvent.Citation,
                StreamingEvent.Final,
                StreamingEvent.Error {

    record Content(String markdownChunk) implements StreamingEvent {}

    record ToolCall(UUID toolCallId, String toolName, String argsJson) implements StreamingEvent {}

    record ToolResult(UUID toolCallId,
                      String toolName,
                      String summary,
                      AiToolCallOutcome outcome,
                      String payloadJson) implements StreamingEvent {
        /** Backwards-compatible constructor for callers that do not emit structured payloads. */
        public ToolResult(UUID toolCallId, String summary, AiToolCallOutcome outcome) {
            this(toolCallId, null, summary, outcome, null);
        }
    }

    record Citation(int index, UUID documentId, String snippet) implements StreamingEvent {}

    /**
     * Terminal streaming event. Carries the run id + conversation id + usage metrics for the
     * just-completed turn. Phase 13.1 D-D1: {@code budgetExceeded} mirrors the
     * {@code ChatResponseDto.budgetExceeded} flag on the blocking path so the UI can render a
     * toast on either transport mode.
     */
    record Final(UUID runId, UUID conversationId, long latencyMs, int promptTokens, int completionTokens,
                 boolean budgetExceeded)
            implements StreamingEvent {
        /** Back-compat factory for callers that pre-date the {@code budgetExceeded} field. */
        public Final(UUID runId, UUID conversationId, long latencyMs, int promptTokens, int completionTokens) {
            this(runId, conversationId, latencyMs, promptTokens, completionTokens, false);
        }
    }

    record Error(String messageKey, Map<String, Object> params) implements StreamingEvent {}
}
