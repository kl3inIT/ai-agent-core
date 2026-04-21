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
 *   <li>{@link Final} — terminal event carrying run id + usage metrics.</li>
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

    record ToolResult(UUID toolCallId, String summary, AiToolCallOutcome outcome) implements StreamingEvent {}

    record Citation(int index, UUID documentId, String snippet) implements StreamingEvent {}

    record Final(UUID runId, long latencyMs, int promptTokens, int completionTokens) implements StreamingEvent {}

    record Error(String messageKey, Map<String, Object> params) implements StreamingEvent {}
}
