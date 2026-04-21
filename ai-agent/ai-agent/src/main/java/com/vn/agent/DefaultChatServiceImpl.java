package com.vn.agent;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.guard.GuardedToolCallingManager;
import com.vn.agent.guard.IterationCapExceededException;
import com.vn.agent.guard.IterationCounter;
import com.vn.agent.guard.OutputScannerAdvisor;
import com.vn.agent.guard.RateLimitExceededException;
import com.vn.agent.guard.RateLimitGuard;
import com.vn.agent.guard.TokenBudgetExhaustedException;
import com.vn.agent.guard.TokenBudgetGuard;
import com.vn.agent.orchestration.AiParametersResolver;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.parameters.Overrides;
import com.vn.agent.rag.RetrievalFilterBuilder;
import com.vn.agent.spi.ToolVetoedException;
import com.vn.agent.tools.AgentToolCallbacks;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Phase-4 default {@link ChatService} implementation (ORCH-01, ORCH-05), extended in Phase 6 with
 * guard preamble, scanner flag promotion, typed guard-denial mapping, and a structured-output
 * {@code askTyped} retry loop.
 *
 * <p>Per D-01: the {@link ChatClient} is a singleton cached bean built by
 * {@code ChatClientFactory}; each {@code ask} call reuses it via per-request {@code .prompt()}.
 * Per-turn flow (Phase 6):</p>
 *
 * <ol>
 *   <li>{@link ConversationGateway#loadOrCreate(String, UUID, String)} enforces D-09 opacity via
 *       {@code createdBy} (B3) and applies the D-08 title rule on auto-create.</li>
 *   <li><b>Guard preamble</b> — {@link RateLimitGuard#check(String)} then
 *       {@link TokenBudgetGuard#check(UUID)} run <em>before</em> any LLM work. Either denial
 *       short-circuits with a {@link ChatResponseDto#denied} carrying a stable i18n key
 *       (D-10 — never the raw exception text or numeric ceiling).</li>
 *   <li>{@link IterationCounter#start()} primes the ThreadLocal tool-round counter consumed by
 *       {@link GuardedToolCallingManager}. {@link IterationCounter#reset()} lives in the
 *       {@code finally} block so an exception from any step (including the gateway) cannot leak
 *       ThreadLocal state to the next request served on the same thread.</li>
 *   <li>{@link BaselineContextProvider#renderAsText(UUID)} produces a deterministic
 *       {@code agent.*} text block (D-15) prepended to the profile system prompt.</li>
 *   <li>{@link AiParametersResolver#effectiveModel(AiParameters, Overrides)} and
 *       {@link AiParametersResolver#effectiveSystemPrompt(AiParameters, String, UUID, UUID)}
 *       run on every turn (PARAM-01/PARAM-05/SPI-05) so {@code PromptContextContributor}
 *       fragments and {@link Overrides#model()} both land here.</li>
 *   <li>The call uses {@code .call().chatClientResponse()} so the orchestrator can inspect
 *       {@link ChatClientResponse#context()} for the scanner's
 *       {@link OutputScannerAdvisor#CONTEXT_KEY_FLAGGED_PATTERN} entry (D-17). A hit promotes to
 *       {@code flagged=true + flaggedPatternKey} on the DTO and writes a FLAGGED audit row via
 *       {@link AuditWriter#writeToolCall} — never the matched text (D-18).</li>
 *   <li>Post-response {@link TokenBudgetGuard#accumulate(UUID, long)} feeds the breaker with the
 *       LLM's reported usage so the next turn's {@code check(convId)} sees the real running
 *       total.</li>
 *   <li>{@link IterationCapExceededException} and {@link ToolVetoedException} bubbled out of
 *       the tool-calling manager are caught and mapped to {@link ChatResponseDto#denied}
 *       with the appropriate i18n keys — the per-tool audit rows are written by
 *       {@link GuardedToolCallingManager} itself so this class only maps, never duplicates.</li>
 * </ol>
 *
 * <h2>askTyped retry loop (D-19)</h2>
 * <p>Max 2 attempts — initial + 1 retry per ROADMAP §06 success criterion #4. The format hint
 * from Spring AI's {@code BeanOutputConverter} is appended to the user message on every
 * attempt; on retry the message is replaced with a stricter re-prompt. Catches are narrow:
 * {@link jakarta.validation.ConstraintViolationException} directly, plus the
 * {@code RuntimeException} wrapping Jackson's {@code JsonProcessingException} that
 * {@code BeanOutputConverter} throws on parse failure (no {@code BeanOutputParseException}
 * class exists in Spring AI 1.1.4 — RESEARCH Open Question 1 resolved). Widening to bare
 * {@code RuntimeException} is forbidden because it would swallow the guard exceptions the
 * inner {@link #ask} throws.</p>
 */
@Service
public class DefaultChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(DefaultChatServiceImpl.class);

    private final ChatClient chatClient;
    private final ConversationGateway conversationGateway;
    private final AgentToolCallbacks toolCallbacks;
    private final AiParametersResolver parametersResolver;
    private final BaselineContextProvider baselineContextProvider;
    private final RetrievalFilterBuilder retrievalFilterBuilder;
    private final CurrentAuthentication currentAuthentication;
    private final RateLimitGuard rateLimitGuard;
    private final TokenBudgetGuard tokenBudgetGuard;
    private final AuditWriter auditWriter;
    private final jakarta.validation.Validator validator;

    public DefaultChatServiceImpl(ChatClient chatClient,
                                  ConversationGateway conversationGateway,
                                  AgentToolCallbacks toolCallbacks,
                                  AiParametersResolver parametersResolver,
                                  BaselineContextProvider baselineContextProvider,
                                  RetrievalFilterBuilder retrievalFilterBuilder,
                                  CurrentAuthentication currentAuthentication,
                                  RateLimitGuard rateLimitGuard,
                                  TokenBudgetGuard tokenBudgetGuard,
                                  AuditWriter auditWriter,
                                  jakarta.validation.Validator validator) {
        this.chatClient = chatClient;
        this.conversationGateway = conversationGateway;
        this.toolCallbacks = toolCallbacks;
        this.parametersResolver = parametersResolver;
        this.baselineContextProvider = baselineContextProvider;
        this.retrievalFilterBuilder = retrievalFilterBuilder;
        this.currentAuthentication = currentAuthentication;
        this.rateLimitGuard = rateLimitGuard;
        this.tokenBudgetGuard = tokenBudgetGuard;
        this.auditWriter = auditWriter;
        this.validator = validator;
    }

    @Override
    public ChatResponseDto ask(String userId, UUID conversationId, String message) {
        return ask(userId, conversationId, message, Overrides.NONE);
    }

    @Override
    public ChatResponseDto ask(String userId, UUID conversationId, String message, Overrides overrides) {
        final Overrides effectiveOverrides = overrides == null ? Overrides.NONE : overrides;
        final UUID runId = UUID.randomUUID();
        com.vn.agent.orchestration.RunContext.set(runId);
        IterationCounter.start();
        final long startNanos = System.nanoTime();
        AiConversation conversation = null;
        try {
            // B4: pass `message` as firstMessage so auto-created conversation gets a title.
            conversation = conversationGateway.loadOrCreate(userId, conversationId, message);
            final UUID convId = conversation.getId();

            // Guard preamble — rate limit first (cheaper), then token budget.
            try {
                rateLimitGuard.check(userId);
                tokenBudgetGuard.check(convId);
            } catch (RateLimitExceededException rate) {
                auditDenial(runId, userId, convId, "rate-limit-exceeded");
                return ChatResponseDto.denied(convId, runId,
                        "ai-agent.guard.rate-limit-exceeded", Map.of("retryAfterSec", 60));
            } catch (TokenBudgetExhaustedException budget) {
                auditDenial(runId, userId, convId, "token-budget-exhausted");
                return ChatResponseDto.denied(convId, runId,
                        "ai-agent.guard.token-budget-exhausted", Map.of());
            }

            AiParameters active = parametersResolver.resolveActive();
            String model = parametersResolver.effectiveModel(active, effectiveOverrides);
            String profileSystemPrompt = parametersResolver.effectiveSystemPrompt(
                    active, userId, convId, runId);

            // B5 + B-NEW-1: baseline as deterministic TEXT (D-15) prepended to profile prompt.
            String baselineText = baselineContextProvider.renderAsText(convId);
            String composedSystemPrompt = baselineText
                    + (profileSystemPrompt != null && !profileSystemPrompt.isBlank()
                            ? "\n\n" + profileSystemPrompt
                            : "");

            // Phase 5 role-scoped retrieval (RAG-04/RAG-05). Null filter = admin-bypass; skip
            // setting FILTER_EXPRESSION so the retriever runs without any filter.
            Authentication runtimeAuth = safeGetAuthentication();
            Filter.Expression ragFilter = retrievalFilterBuilder.buildFor(runtimeAuth);

            ChatClientResponse clientResp = chatClient.prompt()
                    .system(composedSystemPrompt)
                    .user(message)
                    .toolCallbacks(toolCallbacks.callbacksFor(userId, convId))
                    .advisors(advisorSpec -> {
                        advisorSpec
                                .param(ChatMemory.CONVERSATION_ID, convId.toString())
                                .param("audit.runId", runId);
                        if (ragFilter != null) {
                            advisorSpec.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, ragFilter);
                        }
                    })
                    .options(ChatOptions.builder()
                            .model(model)
                            .temperature(parametersResolver.effectiveTemperature(active))
                            .topP(parametersResolver.effectiveTopP(active))
                            .maxTokens(parametersResolver.effectiveMaxTokens(active))
                            .build())
                    .call()
                    .chatClientResponse();

            ChatResponse springResponse = clientResp.chatResponse();
            String content = springResponse != null
                    && springResponse.getResult() != null
                    && springResponse.getResult().getOutput() != null
                            ? springResponse.getResult().getOutput().getText()
                            : "";
            if (content == null) {
                content = "";
            }

            // Post-response token accumulation for the breaker.
            long tokensUsed = usageTokens(springResponse);
            tokenBudgetGuard.accumulate(convId, tokensUsed);

            // Promote scanner flag (D-17/D-18 — pattern KEY only, never matched text).
            String flaggedKey = null;
            try {
                Map<String, Object> context = clientResp.context();
                Object contextFlag = context == null ? null
                        : context.get(OutputScannerAdvisor.CONTEXT_KEY_FLAGGED_PATTERN);
                if (contextFlag instanceof String s && !s.isBlank()) {
                    flaggedKey = s;
                    auditFlagged(runId, userId, convId, flaggedKey);
                }
            } catch (RuntimeException readCtx) {
                log.debug("Failed to read scanner context flag runId={}", runId, readCtx);
            }
            boolean flagged = flaggedKey != null;

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.debug("ChatService.ask convId={} runId={} model={} latencyMs={} flagged={}",
                    convId, runId, model, latencyMs, flagged);
            return new ChatResponseDto(convId, runId, content, model, latencyMs,
                    flagged, flaggedKey, null);
        } catch (IterationCapExceededException capped) {
            UUID convId = conversation != null ? conversation.getId() : null;
            // Iteration-cap request-level audit row is written by GuardedToolCallingManager (D-11).
            return ChatResponseDto.denied(convId, runId,
                    "ai-agent.guard.iteration-cap-exceeded", Map.of());
        } catch (ToolVetoedException veto) {
            UUID convId = conversation != null ? conversation.getId() : null;
            // PRE audit row (with real tool name + denial reason) is written by
            // GuardedToolCallingManager before the veto was thrown.
            return ChatResponseDto.denied(convId, runId,
                    "ai-agent.guard.tool-vetoed", Map.of());
        } finally {
            IterationCounter.reset();
            com.vn.agent.orchestration.RunContext.clear();
        }
    }

    @Override
    public <T> T askTyped(String userId, UUID conversationId, String message, Class<T> targetType) {
        throw new UnsupportedOperationException("Task 2b pending");
    }

    @Override
    public <T> T askTyped(String userId, UUID conversationId, String message,
                          Overrides overrides, Class<T> targetType) {
        throw new UnsupportedOperationException("Task 2b pending");
    }

    /**
     * Extracts the total-tokens counter from the Spring AI usage block. Returns 0 on any error
     * so the token-budget accumulator is never poisoned by a missing/partial metadata response.
     */
    private long usageTokens(ChatResponse r) {
        try {
            if (r == null || r.getMetadata() == null || r.getMetadata().getUsage() == null) {
                return 0L;
            }
            Integer total = r.getMetadata().getUsage().getTotalTokens();
            return total == null ? 0L : total.longValue();
        } catch (RuntimeException ex) {
            log.debug("usageTokens() failed — treating as 0", ex);
            return 0L;
        }
    }

    /**
     * Writes a request-level BLOCKED audit row when a chat-level guard denies the request before
     * any LLM call happens. The sentinel tool name matches
     * {@link GuardedToolCallingManager#CHAT_SENTINEL_TOOL_NAME} so operators can filter
     * chat-level denials uniformly in the audit UI.
     */
    private void auditDenial(UUID runId, String userUsername, UUID convId, String denialKey) {
        try {
            auditWriter.writeToolCall(runId, userUsername, convId,
                    GuardedToolCallingManager.CHAT_SENTINEL_TOOL_NAME,
                    /* argumentsJson */ null, /* resultSummary */ null, 0L,
                    AiToolCallOutcome.BLOCKED, denialKey, /* errorClass */ null, "REQUEST");
        } catch (Throwable t) {
            log.warn("Denial audit failed runId={} key={}", runId, denialKey, t);
        }
    }

    /**
     * Writes a request-level FLAGGED audit row when the {@link OutputScannerAdvisor} matched a
     * pattern on the assistant response. {@code denialReason} carries only the pattern KEY
     * (never the matched text — D-18 prompt-injection echo prevention).
     */
    private void auditFlagged(UUID runId, String userUsername, UUID convId, String patternKey) {
        try {
            auditWriter.writeToolCall(runId, userUsername, convId,
                    GuardedToolCallingManager.CHAT_SENTINEL_TOOL_NAME,
                    /* argumentsJson */ null, /* resultSummary */ null, 0L,
                    AiToolCallOutcome.FLAGGED, "flagged:" + patternKey,
                    /* errorClass */ null, "POST");
        } catch (Throwable t) {
            log.warn("Flagged audit failed runId={} key={}", runId, patternKey, t);
        }
    }

    /**
     * Null-safe bridge to {@link CurrentAuthentication#getAuthentication()}. Matches
     * {@code BaselineContextProvider#safeGetUser()}'s anonymous-caller posture: if the
     * Jmix security context is not established (anonymous runtime), we return {@code null}
     * and {@link RetrievalFilterBuilder} collapses to its fail-closed empty-roles branch.
     */
    private Authentication safeGetAuthentication() {
        try {
            return currentAuthentication.getAuthentication();
        } catch (RuntimeException anonymous) {
            return null;
        }
    }
}
