package com.vn.agent;

import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.orchestration.ConversationNotFoundException;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.parameters.Overrides;
import reactor.core.publisher.Flux;

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
 * <p>Phase 6 extensions (ORCH-01 carry-over + PARAM-01 + GUARD-06): per-conversation
 * {@link Overrides} support plus a structured-output {@code askTyped} contract. All additions
 * are additive — the original 3-arg {@link #ask(String, UUID, String)} signature remains.</p>
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

    /**
     * Variant of {@link #ask(String, UUID, String)} that applies per-conversation parameter
     * overrides (PARAM-01, D-01). A {@code null} value or {@link Overrides#NONE} is equivalent
     * to calling the three-arg overload.
     *
     * <p>Phase 6 guards are applied before the LLM call: rate limit → token budget →
     * iteration counter start. Denials short-circuit with a {@link ChatResponseDto} whose
     * {@code guardDenial} field carries a stable i18n message key (never the raw exception
     * text — D-10 opacity).</p>
     */
    ChatResponseDto ask(String userId, UUID conversationId, String message, Overrides overrides);

    /**
     * Intent-aware blocking variant used by the Phase 14 picker. A blank intent id, or the
     * UI's Auto option, is equivalent to the default chat path.
     */
    ChatResponseDto ask(String userId, UUID conversationId, String message,
                        Overrides overrides, String intentId);

    /**
     * Structured-output variant. Requests the LLM produce JSON matching the shape of
     * {@code targetType} (GUARD-06). The response is parsed via Spring AI's
     * {@code BeanOutputConverter} and validated via Jakarta Bean Validation.
     *
     * <p>On repeated parse/validation failure across the bounded retry window (D-19 — 2
     * attempts = initial + 1 retry) the add-on throws
     * {@link com.vn.agent.guard.StructuredOutputException} carrying the last raw text and the
     * target type. Guard-level denials during {@code askTyped} propagate as their own typed
     * exceptions (rate-limit / token-budget / iteration-cap / tool-vetoed) so the caller's
     * {@code try/catch} can map each precisely — typed callers that need the richer
     * {@link ChatResponseDto} denial metadata can call {@link #ask} instead.</p>
     *
     * @throws com.vn.agent.guard.StructuredOutputException         when parse + validation fails on every retry
     * @throws com.vn.agent.guard.RateLimitExceededException        when the per-user rate limit denies the request
     * @throws com.vn.agent.guard.TokenBudgetExhaustedException     when the per-conversation token breaker denies
     * @throws com.vn.agent.guard.IterationCapExceededException     when the tool-calling iteration cap is breached
     * @throws com.vn.agent.spi.ToolVetoedException                 when a {@code ToolGuard} vetoes the invocation
     */
    <T> T askTyped(String userId, UUID conversationId, String message, Class<T> targetType);

    /**
     * askTyped with per-conversation {@link Overrides} (GUARD-06 + D-01). A {@code null}
     * {@code overrides} or {@link Overrides#NONE} is equivalent to
     * {@link #askTyped(String, UUID, String, Class)}.
     */
    <T> T askTyped(String userId, UUID conversationId, String message, Overrides overrides, Class<T> targetType);

    /**
     * Streaming variant (D-01, D-04). Returns a Flux of {@link StreamingEvent} values
     * interleaving content chunks, tool-call events (via the Sinks.Many bridge — Spring
     * AI GH #5167 workaround), citations, and a terminal Final event. Errors during the
     * stream are delivered as {@link StreamingEvent.Error} with a stable i18n message
     * key — raw exception text is never emitted.
     *
     * <p>Cancellation: the active {@code Flux} subscription is registered in
     * {@code CancellationRegistry} keyed on the pre-allocated {@code runId}; a
     * {@code cancel(runId)} call disposes the subscription and audits the run with
     * outcome {@code CANCELLED} (D-03).</p>
     *
     * <p>Non-streaming providers (D-04 graceful fallback) get a single Content chunk
     * followed by Final — callers do not need to branch on capability.</p>
     *
     * @throws ConversationNotFoundException same ownership semantics as {@link #ask}
     */
    Flux<StreamingEvent> stream(String userId, UUID conversationId, String message, Overrides overrides);

    /**
     * Intent-aware streaming variant. Existing callers can keep using the four-arg overload.
     */
    Flux<StreamingEvent> stream(String userId, UUID conversationId, String message,
                                Overrides overrides, String intentId);
}
