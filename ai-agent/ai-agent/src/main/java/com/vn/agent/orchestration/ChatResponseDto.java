package com.vn.agent.orchestration;

import java.util.Map;
import java.util.UUID;

/**
 * Response DTO returned by {@code ChatService.ask(...)} (ORCH-01, extended in Phase 06 for
 * GUARD-05 / D-17 output-scanner flags and D-10 typed guard denials).
 *
 * <p>Carries (a) the (possibly-freshly-created) conversation id so callers can continue the
 * thread in subsequent turns, (b) the pre-allocated {@code runId} correlating to the chat-level
 * audit rows written by {@code AuditAdvisor} (B8 — pre-allocation eliminates the lifecycle race
 * where {@code RunContext.get()} returned {@code null} at the service layer), (c) the assistant's
 * text content, (d) the effective model slug used for this turn (from {@code AiParametersResolver}),
 * (e) the wall-clock latency in milliseconds measured around the cached
 * {@code ChatClient.prompt()...call()} invocation, (f) a {@code flagged} boolean raised by the
 * output-scanner advisor when the final assistant message matched a bundled/operator-defined
 * regex (D-17 flag-and-pass-through — content is still returned verbatim, UI shows a banner),
 * (g) the <em>pattern key</em> that matched (D-18 — key, never the matched text, to avoid
 * echoing prompt-injection payloads), and (h) an optional {@link GuardDenialInfo} populated
 * when a request-level guard denied the request before the LLM call (rate-limit, token budget,
 * iteration cap, structured-output parse failure).
 *
 * <p><b>Denial factory:</b> {@link #denied(UUID, UUID, String, Map)} builds a short-circuit
 * response with empty content and a {@link GuardDenialInfo} carrying only the i18n message
 * key plus typed params — never the numeric ceiling, regex, or raw exception text (T-06-01,
 * T-06-02, D-10).</p>
 *
 * <p><b>Bridge factory:</b> {@link #ok(UUID, UUID, String, String, long)} is the 5-arg
 * back-compat shortcut for call sites that have not yet been migrated to the full 8-component
 * constructor — it sets {@code flagged=false} and leaves {@code flaggedPatternKey} and
 * {@code guardDenial} null.</p>
 *
 * @param conversationId    id of the conversation this turn was committed to (never null)
 * @param runId             pre-allocated run id; matches {@code AiToolCallAudit.runId} for this chat
 * @param content           assistant's text response (may be empty on denial or edge-case LLM responses)
 * @param model             OpenRouter {@code provider/model} slug actually used this turn (null on denial)
 * @param latencyMs         wall-clock time across {@code chatClient.prompt().call()} (0 on denial)
 * @param flagged           {@code true} if the output scanner matched a pattern on the final message (D-17)
 * @param flaggedPatternKey stable key of the matched pattern when {@code flagged=true}; otherwise {@code null}
 * @param guardDenial       populated when a request-level guard denied the request; {@code null} on success
 */
public record ChatResponseDto(
        UUID conversationId,
        UUID runId,
        String content,
        String model,
        long latencyMs,
        boolean flagged,
        String flaggedPatternKey,
        GuardDenialInfo guardDenial) {

    /**
     * Typed denial payload for Phase 7 UI rendering.
     *
     * <p>{@code messageKey} is a {@code msg://ai-agent.guard.*} key (see {@code messages.properties}
     * / {@code messages_vi.properties}). {@code params} carries only i18n-safe values (e.g.
     * {@code retry-after} seconds) — NEVER raw exception text, regex patterns, or numeric
     * ceilings that could leak guard internals (T-06-02 mitigation).</p>
     */
    public record GuardDenialInfo(String messageKey, Map<String, Object> params) {
    }

    /**
     * Back-compat factory for callers that still use the original 5-arg shape. Sets
     * {@code flagged=false} and leaves {@code flaggedPatternKey} / {@code guardDenial} null.
     */
    public static ChatResponseDto ok(UUID conversationId, UUID runId, String content, String model, long latencyMs) {
        return new ChatResponseDto(conversationId, runId, content, model, latencyMs, false, null, null);
    }

    /**
     * Factory for typed guard denials. Produces a short-circuit response with empty content
     * and a {@link GuardDenialInfo} carrying the i18n key + typed params. {@code model} is
     * {@code null} and {@code latencyMs} is {@code 0} because no LLM call occurred.
     */
    public static ChatResponseDto denied(UUID conversationId, UUID runId, String messageKey, Map<String, Object> params) {
        return new ChatResponseDto(conversationId, runId, "", null, 0L, false, null, new GuardDenialInfo(messageKey, params));
    }
}
