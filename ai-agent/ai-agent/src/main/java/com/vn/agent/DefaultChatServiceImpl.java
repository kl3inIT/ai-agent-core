package com.vn.agent;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.entity.AiToolCallOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.vn.agent.conversation.ConversationTitleEligibilityPublisher;
import com.vn.agent.guard.AgentSystemPromptRulesComposer;
import com.vn.agent.guard.GuardedToolCallingManager;
import com.vn.agent.guard.IterationCapExceededException;
import com.vn.agent.guard.IterationCounter;
import com.vn.agent.guard.OutputScannerAdvisor;
import com.vn.agent.guard.RateLimitExceededException;
import com.vn.agent.guard.RateLimitGuard;
import com.vn.agent.guard.StructuredOutputException;
import com.vn.agent.guard.TokenBudgetExhaustedException;
import com.vn.agent.guard.TokenBudgetGuard;
import com.vn.agent.orchestration.AiParametersResolver;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.orchestration.SystemPromptComposer;
import com.vn.agent.parameters.Overrides;
import com.vn.agent.rag.CancellationRegistry;
import com.vn.agent.rag.RetrievalFilterBuilder;
import com.vn.agent.rag.advisor.AuditingDocumentRetriever;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.spi.ToolVetoedException;
import com.vn.agent.taskfile.AiTaskFileMediaResolver;
import com.vn.agent.taskfile.AiTaskFileRepository;
import com.vn.agent.tools.AgentToolCallbacks;
import io.jmix.core.security.CurrentAuthentication;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

import com.vn.agent.orchestration.ConversationNotFoundException;

import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private final AiAgentRagProperties ragProperties;
    private final CurrentAuthentication currentAuthentication;
    private final RateLimitGuard rateLimitGuard;
    private final TokenBudgetGuard tokenBudgetGuard;
    private final AuditWriter auditWriter;
    private final jakarta.validation.Validator validator;
    private final Scheduler chatStreamingScheduler;
    private final CancellationRegistry cancellationRegistry;
    private final StreamingSinkHolder streamingSinkHolder;
    private final AgentSystemPromptRulesComposer agentSystemPromptRulesComposer;
    private final ConversationTitleEligibilityPublisher titleEligibilityPublisher;
    // Phase 13 Plan 04 — single-turn Media injection (D-01) + two-phase markInjected
    // (D-03 / REVIEWS HIGH-1 / HIGH-14).
    private final AiTaskFileMediaResolver taskFileMediaResolver;
    private final AiTaskFileRepository taskFileRepository;
    private final UserMessagePersister userMessagePersister;

    public DefaultChatServiceImpl(ChatClient chatClient,
                                  ConversationGateway conversationGateway,
                                  AgentToolCallbacks toolCallbacks,
                                  AiParametersResolver parametersResolver,
                                  BaselineContextProvider baselineContextProvider,
                                  RetrievalFilterBuilder retrievalFilterBuilder,
                                  AiAgentRagProperties ragProperties,
                                  CurrentAuthentication currentAuthentication,
                                  RateLimitGuard rateLimitGuard,
                                  TokenBudgetGuard tokenBudgetGuard,
                                  AuditWriter auditWriter,
                                  jakarta.validation.Validator validator,
                                  @Qualifier("chatStreamingScheduler") Scheduler chatStreamingScheduler,
                                  CancellationRegistry cancellationRegistry,
                                  StreamingSinkHolder streamingSinkHolder,
                                  AgentSystemPromptRulesComposer agentSystemPromptRulesComposer,
                                  ConversationTitleEligibilityPublisher titleEligibilityPublisher,
                                  AiTaskFileMediaResolver taskFileMediaResolver,
                                  AiTaskFileRepository taskFileRepository,
                                  UserMessagePersister userMessagePersister) {
        this.chatClient = chatClient;
        this.conversationGateway = conversationGateway;
        this.toolCallbacks = toolCallbacks;
        this.parametersResolver = parametersResolver;
        this.baselineContextProvider = baselineContextProvider;
        this.retrievalFilterBuilder = retrievalFilterBuilder;
        this.ragProperties = ragProperties;
        this.currentAuthentication = currentAuthentication;
        this.rateLimitGuard = rateLimitGuard;
        this.tokenBudgetGuard = tokenBudgetGuard;
        this.auditWriter = auditWriter;
        this.validator = validator;
        this.chatStreamingScheduler = chatStreamingScheduler;
        this.cancellationRegistry = cancellationRegistry;
        this.streamingSinkHolder = streamingSinkHolder;
        this.agentSystemPromptRulesComposer = agentSystemPromptRulesComposer;
        this.titleEligibilityPublisher = titleEligibilityPublisher;
        this.taskFileMediaResolver = taskFileMediaResolver;
        this.taskFileRepository = taskFileRepository;
        this.userMessagePersister = userMessagePersister;
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
            // WR-01: rate-limit BEFORE touching the conversation gateway so a denied first turn
            // does not persist an AiConversation row (loadOrCreate saves when conversationId==null).
            try {
                rateLimitGuard.check(userId);
            } catch (RateLimitExceededException rate) {
                auditDenial(runId, userId, conversationId, "rate-limit-exceeded");
                return ChatResponseDto.denied(conversationId, runId,
                        "ai-agent.guard.rate-limit-exceeded", Map.of("retryAfterSec", 60));
            }

            // B4: pass `message` as firstMessage so auto-created conversation gets a title.
            conversation = conversationGateway.loadOrCreate(userId, conversationId, message);
            final UUID convId = conversation.getId();

            // Token-budget gate runs AFTER loadOrCreate because it is strictly per-conversation.
            try {
                tokenBudgetGuard.check(convId);
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
            // Phase 9 PROMPT-03 + D-15: vocabulary + unknown_entity-retry rules are inserted
            // between the deterministic baseline (agent.entities + agent.permissions) and the
            // host's profile prompt so they apply on every turn — even when the host has not
            // configured a profile prompt and even when the LLM has not yet seen any tool error
            // in the current conversation.
            // Phase 11 MUT-10: when ai-agent.tools.mutation.enabled=true the composer additionally
            // appends MUTATION_PROMPT_RULES (idempotency / access-denied / concurrent-modification
            // / verify-link guidance). Resolved per-turn so a host config flip lights up on the
            // very next chat turn without a restart.
            String baselineText = baselineContextProvider.renderAsText(convId);
            String composedSystemPrompt = SystemPromptComposer.compose(
                    baselineText,
                    profileSystemPrompt,
                    agentSystemPromptRulesComposer.effectiveRules());

            // Phase 5 role-scoped retrieval (RAG-04/RAG-05). Null filter = admin-bypass; skip
            // setting FILTER_EXPRESSION so the retriever runs without any filter.
            Authentication runtimeAuth = safeGetAuthentication();
            Filter.Expression ragFilter = retrievalFilterBuilder.buildFor(runtimeAuth);

            // 7.2 D-09/D-10: keep retrieval params in RunContext for audit fallback and pass
            // them through advisor context below so the RAG retriever uses the per-request values.
            // Cleared by AuditAdvisor.finally via RunContext.clear() (and defensively again by this
            // method's outer finally).
            // null filter overwrite is intentional defense-in-depth against stale ThreadLocal state
            // on pooled Vaadin request threads (T-07.2-05).
            int retrievalTopK = parametersResolver.effectiveRagTopK(active, ragProperties.resolvedTopK());
            double retrievalSimilarityThreshold = parametersResolver.effectiveRagSimilarityThreshold(
                    active, ragProperties.resolvedSimilarityThreshold());
            RunContext.setRetrievalTopK(retrievalTopK);
            RunContext.setRetrievalSimilarityThreshold(retrievalSimilarityThreshold);
            RunContext.setRetrievalFiltersJson(ragFilter == null ? null : ragFilter.toString());

            // Phase 13 Plan 04 — opportunistic TTL cleanup. Best-effort; never blocks a chat
            // turn (CONTEXT.md "TTL cleanup hourly + opportunistic on chat-send"). Wrapped in
            // try/catch so a transient cleanup-job failure cannot fail the user's request.
            try {
                taskFileRepository.deleteAllExpired(java.time.OffsetDateTime.now());
            } catch (Exception cleanupEx) {
                log.debug("Opportunistic task-file cleanup skipped: {}", cleanupEx.toString());
            }

            // Phase 13 Plan 04 — single-turn Media injection (D-01 / REVIEWS HIGH-1).
            // Resolver scans pending rows where injectedAt IS NULL AND expiresAt > now;
            // returns Media + the row ids the caller threads into markInjected after the
            // turn completes.
            final AiTaskFileMediaResolver.Resolved resolvedMedia =
                    taskFileMediaResolver.resolvePending(convId);

            // REVIEWS HIGH-14 — explicit user-message persist BEFORE chatClient invocation,
            // so we have a stable AiMessage.id to thread into markInjected. Replaces the
            // race-prone SELECT-back "latest USER message" lookup. Only runs when there ARE
            // pending Media to stamp; without media there is nothing to link.
            UUID userMessageId = null;
            if (!resolvedMedia.isEmpty()) {
                try {
                    userMessageId = userMessagePersister.persistUserMessage(convId, message, userId);
                } catch (RuntimeException persistEx) {
                    log.warn("UserMessagePersister failed; continuing without explicit message FK", persistEx);
                }
            }
            final UUID userMessageIdFinal = userMessageId;

            return executeBlockingTurn(userId, convId, message, effectiveOverrides,
                    resolvedMedia, userMessageIdFinal, composedSystemPrompt, model, active,
                    ragFilter, retrievalTopK, retrievalSimilarityThreshold, runId, startNanos);
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

    /**
     * Executes the blocking-call portion of a chat turn against an ALREADY-RESOLVED
     * state (Media + persisted user-message id). Used by both the public {@link #ask}
     * entrypoint AND the {@link #stream} catch-block fallback (D-04 graceful
     * streaming fallback) so the user message and Media injection happen exactly
     * ONCE per turn — fixing BLK-01 from 13-HUMAN-UAT.md (the recursive ask(...)
     * call from the streaming catch double-persisted user messages).
     *
     * @param userId attribution user (for audit/run-context)
     * @param convId conversation id (already loaded by caller)
     * @param message raw user-message text (verbatim — already persisted by caller)
     * @param effectiveOverrides resolved overrides (NEVER null; caller normalizes)
     * @param resolvedMedia pre-resolved Media + ids; may be empty but never null
     * @param userMessageIdAlreadyPersisted id returned by
     *        {@link UserMessagePersister#persistUserMessage}, or {@code null} when
     *        {@code resolvedMedia.isEmpty()} (no media -> no persist was attempted)
     * @param composedSystemPrompt system prompt already composed by caller
     * @param model resolved model id (caller already applied Overrides + AiParameters)
     * @param active resolved {@link AiParameters} snapshot (caller already loaded)
     * @param ragFilter RAG filter expression or {@code null} (admin-bypass)
     * @param retrievalTopK already-resolved RAG top-K
     * @param retrievalSimilarityThreshold already-resolved similarity threshold
     * @param runId already-allocated run id (caller manages RunContext lifecycle)
     * @param startNanos start instant for latency calculation
     * @return blocking ChatResponseDto for the turn
     */
    private ChatResponseDto executeBlockingTurn(String userId,
                                                UUID convId,
                                                String message,
                                                Overrides effectiveOverrides,
                                                AiTaskFileMediaResolver.Resolved resolvedMedia,
                                                UUID userMessageIdAlreadyPersisted,
                                                String composedSystemPrompt,
                                                String model,
                                                AiParameters active,
                                                Filter.Expression ragFilter,
                                                int retrievalTopK,
                                                double retrievalSimilarityThreshold,
                                                UUID runId,
                                                long startNanos) {
        ChatClientResponse clientResp = chatClient.prompt()
                .system(composedSystemPrompt)
                .user(u -> {
                    // AI-SPEC pitfall 6: lambda form is REQUIRED when injecting Media.
                    u.text(message);
                    if (!resolvedMedia.isEmpty()) {
                        u.media(resolvedMedia.media().toArray(new Media[0]));
                    }
                })
                .toolCallbacks(toolCallbacks.callbacksFor(userId, convId))
                .toolContext(auditToolContext(runId, convId))
                .advisors(advisorSpec -> {
                    advisorSpec
                            .param(ChatMemory.CONVERSATION_ID, convId.toString())
                            .param("audit.runId", runId)
                            .param(AuditingDocumentRetriever.TOP_K_CONTEXT_KEY, retrievalTopK)
                            .param(AuditingDocumentRetriever.SIMILARITY_THRESHOLD_CONTEXT_KEY,
                                    retrievalSimilarityThreshold);
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

        // BLK-01 fix (gap-closure plan 13-06): markInjected is invoked EXACTLY ONCE here
        // against the already-persisted userMessageIdAlreadyPersisted. Wrapped in try/catch
        // so a markInjected failure cannot fail the user's response — Plan 04 invariant.
        if (!resolvedMedia.isEmpty()) {
            try {
                taskFileRepository.markInjected(resolvedMedia.taskFileIds(),
                        userMessageIdAlreadyPersisted, java.time.OffsetDateTime.now());
            } catch (RuntimeException stampEx) {
                log.warn("markInjected failed for conv={} ids={}", convId,
                        resolvedMedia.taskFileIds(), stampEx);
            }
        }

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
            Map<String, Object> ctx = clientResp.context();
            Object contextFlag = ctx == null ? null
                    : ctx.get(OutputScannerAdvisor.CONTEXT_KEY_FLAGGED_PATTERN);
            if (contextFlag instanceof String s && !s.isBlank()) {
                flaggedKey = s;
                auditFlagged(runId, userId, convId, flaggedKey);
            }
        } catch (RuntimeException readCtx) {
            log.debug("Failed to read scanner context flag runId={}", runId, readCtx);
        }
        boolean flagged = flaggedKey != null;

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.debug("ChatService.executeBlockingTurn convId={} runId={} model={} latencyMs={} flagged={}",
                convId, runId, model, latencyMs, flagged);
        publishTitleEligibilityIfAssistantReply(convId, userId, runId, content);
        return new ChatResponseDto(convId, runId, content, model, latencyMs,
                flagged, flaggedKey, null);
    }

    @Override
    public Flux<StreamingEvent> stream(String userId, UUID conversationId, String message, Overrides overrides) {
        final Overrides effectiveOverrides = overrides == null ? Overrides.NONE : overrides;
        final UUID runId = UUID.randomUUID();
        final long startNanos = System.nanoTime();

        return Flux.defer(() -> {
                    RunContext.set(runId);
                    // CR-01 fix: prime the per-thread iteration counter at subscribe time so
                    // GuardedToolCallingManager sees clean ThreadLocal state regardless of
                    // transport mode. Counterpart reset() lives in the .doFinally below
                    // (mirrors ask()'s start() at line 196 + reset() in finally at line 433).
                    IterationCounter.start();

                    // CR-01 fix: guard preamble — rate-limit BEFORE conversationGateway so a
                    // denied first turn does not persist an AiConversation row (mirrors
                    // ask()'s WR-01 ordering at lines 200-208). On denial: emit a terminal
                    // Error + Final event pair and skip all streaming machinery.
                    try {
                        rateLimitGuard.check(userId);
                    } catch (RateLimitExceededException rate) {
                        auditDenial(runId, userId, conversationId, "rate-limit-exceeded");
                        long denyLatencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                        return Flux.<StreamingEvent>just(
                                new StreamingEvent.Error("ai-agent.guard.rate-limit-exceeded",
                                        Map.of("retryAfterSec", 60)),
                                new StreamingEvent.Final(runId, conversationId, denyLatencyMs, 0, 0));
                    }

                    // Tool-event sink — decorator emits ToolCall/ToolResult here; merged with content Flux below.
                    Sinks.Many<StreamingEvent> toolSink = Sinks.many().unicast().onBackpressureBuffer();
                    streamingSinkHolder.register(runId, toolSink);

                    final AiConversation conversation = conversationGateway.loadOrCreate(userId, conversationId, message);
                    final UUID convId = conversation.getId();

                    // CR-01 fix: token-budget gate runs AFTER loadOrCreate because it is
                    // strictly per-conversation (mirrors ask() at lines 215-221).
                    try {
                        tokenBudgetGuard.check(convId);
                    } catch (TokenBudgetExhaustedException budget) {
                        auditDenial(runId, userId, convId, "token-budget-exhausted");
                        long denyLatencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                        return Flux.<StreamingEvent>just(
                                new StreamingEvent.Error("ai-agent.guard.token-budget-exhausted", Map.of()),
                                new StreamingEvent.Final(runId, convId, denyLatencyMs, 0, 0));
                    }

                    final AtomicBoolean assistantContentSeen = new AtomicBoolean(false);
                    final AtomicBoolean titlePublicationHandled = new AtomicBoolean(false);

                    AiParameters active = parametersResolver.resolveActive();
                    String model = parametersResolver.effectiveModel(active, effectiveOverrides);
                    String profileSystemPrompt = parametersResolver.effectiveSystemPrompt(active, userId, convId, runId);
                    String baselineText = baselineContextProvider.renderAsText(convId);
                    // Phase 9 PROMPT-03 + D-15: same composition seam as the blocking ask(...)
                    // path; rules apply on every streaming turn so the vocabulary + retry
                    // contract is enforced regardless of transport mode.
                    // Phase 11 MUT-10: streaming path also consumes the conditional composer so
                    // mutation rules are appended on every streaming turn when the property is on.
                    String composedSystemPrompt = SystemPromptComposer.compose(
                            baselineText,
                            profileSystemPrompt,
                            agentSystemPromptRulesComposer.effectiveRules());
                    Authentication runtimeAuth = safeGetAuthentication();
                    Filter.Expression ragFilter = retrievalFilterBuilder.buildFor(runtimeAuth);

                    // 7.2 D-09/D-10: keep retrieval params in RunContext for audit fallback and
                    // pass them through advisor context below so the RAG retriever uses the
                    // per-request values.
                    int retrievalTopK = parametersResolver.effectiveRagTopK(active, ragProperties.resolvedTopK());
                    double retrievalSimilarityThreshold = parametersResolver.effectiveRagSimilarityThreshold(
                            active, ragProperties.resolvedSimilarityThreshold());
                    RunContext.setRetrievalTopK(retrievalTopK);
                    RunContext.setRetrievalSimilarityThreshold(retrievalSimilarityThreshold);
                    RunContext.setRetrievalFiltersJson(ragFilter == null ? null : ragFilter.toString());

                    // Phase 13 Plan 04 — opportunistic TTL cleanup, hoisted INSIDE Flux.defer
                    // so it runs at subscribe time (not flux-build time). Best-effort; never
                    // blocks a chat turn.
                    try {
                        taskFileRepository.deleteAllExpired(java.time.OffsetDateTime.now());
                    } catch (Exception cleanupEx) {
                        log.debug("Opportunistic task-file cleanup skipped: {}", cleanupEx.toString());
                    }

                    // Phase 13 Plan 04 — single-turn Media injection (D-01 / REVIEWS HIGH-1).
                    // RESEARCH Pitfall 8: hoisted INSIDE Flux.defer so readAllBytes does not
                    // run on the calling thread before subscription. REVIEWS HIGH-14:
                    // user-message persist also runs INSIDE the defer, capturing the id into
                    // an AtomicReference closed over by doOnComplete.
                    final AiTaskFileMediaResolver.Resolved resolvedMedia =
                            taskFileMediaResolver.resolvePending(convId);
                    final AtomicReference<UUID> userMessageIdRef = new AtomicReference<>();
                    if (!resolvedMedia.isEmpty()) {
                        try {
                            userMessageIdRef.set(userMessagePersister.persistUserMessage(
                                    convId, message, userId));
                        } catch (RuntimeException persistEx) {
                            log.warn("UserMessagePersister failed; continuing without explicit message FK",
                                    persistEx);
                        }
                    }

                    Flux<StreamingEvent> content;
                    try {
                        content = chatClient.prompt()
                                .system(composedSystemPrompt)
                                .user(u -> {
                                    // AI-SPEC pitfall 6: lambda form REQUIRED when media is non-empty.
                                    u.text(message);
                                    if (!resolvedMedia.isEmpty()) {
                                        u.media(resolvedMedia.media().toArray(new Media[0]));
                                    }
                                })
                                .toolCallbacks(toolCallbacks.callbacksFor(userId, convId))
                                .toolContext(auditToolContext(runId, convId))
                                .advisors(advisorSpec -> {
                                    advisorSpec
                                            .param(ChatMemory.CONVERSATION_ID, convId.toString())
                                            .param("audit.runId", runId)
                                            .param(AuditingDocumentRetriever.TOP_K_CONTEXT_KEY, retrievalTopK)
                                            .param(AuditingDocumentRetriever.SIMILARITY_THRESHOLD_CONTEXT_KEY,
                                                    retrievalSimilarityThreshold);
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
                                .stream()
                                .chatResponse()
                                .<StreamingEvent>concatMap(chunk -> {
                                    AssistantMessage am = chunk != null && chunk.getResult() != null
                                            ? chunk.getResult().getOutput() : null;
                                    String text = am != null ? am.getText() : null;
                                    if (text == null || text.isEmpty()) {
                                        return Flux.empty();
                                    }
                                    assistantContentSeen.set(true);
                                    return Flux.just(new StreamingEvent.Content(text));
                                })
                                // REVIEWS HIGH-1 + HIGH-14: stamp injectedAt only on successful
                                // completion. doOnComplete (NOT doOnSubscribe / doOnNext) so a
                                // cancelled stream does NOT mark files as injected — D-03
                                // two-phase semantics: cancelled = retry works.
                                .doOnComplete(() -> {
                                    if (!resolvedMedia.isEmpty()) {
                                        try {
                                            taskFileRepository.markInjected(
                                                    resolvedMedia.taskFileIds(),
                                                    userMessageIdRef.get(),
                                                    java.time.OffsetDateTime.now());
                                        } catch (Exception stampEx) {
                                            log.warn("markInjected failed for conv={} ids={}", convId,
                                                    resolvedMedia.taskFileIds(), stampEx);
                                        }
                                    }
                                })
                                .doOnComplete(toolSink::tryEmitComplete)
                                .doOnError(ex -> toolSink.tryEmitComplete());
                    } catch (UnsupportedOperationException nonStreaming) {
                        // BLK-01 fix (gap-closure plan 13-06): D-04 graceful fallback. The chat model
                        // does not support streaming. Reuse the ALREADY-RESOLVED Media + ALREADY-PERSISTED
                        // user-message id by delegating to executeBlockingTurn(...) — do NOT recurse
                        // through ask(...) (which would re-resolve and double-persist the user message,
                        // polluting ChatMemory replay).
                        ChatResponseDto blocking = executeBlockingTurn(userId, convId, message, effectiveOverrides,
                                resolvedMedia, userMessageIdRef.get(), composedSystemPrompt, model, active,
                                ragFilter, retrievalTopK, retrievalSimilarityThreshold, runId, startNanos);
                        titlePublicationHandled.set(true);
                        toolSink.tryEmitComplete();
                        content = Flux.just(
                                new StreamingEvent.Content(blocking.content() == null ? "" : blocking.content()));
                    }

                    Flux<StreamingEvent> merged = toolSink.asFlux().mergeWith(content);
                    return merged
                    .concatWith(Flux.defer(() -> {
                        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                        if (assistantContentSeen.get() && !titlePublicationHandled.get()) {
                            publishTitleEligibilityIfAssistantReply(convId, userId, runId, "streamed");
                        }
                        return Flux.<StreamingEvent>just(
                                new StreamingEvent.Final(runId, convId, latencyMs, 0, 0));
                    }));
                })
        .subscribeOn(chatStreamingScheduler)
        // Capture caller ThreadLocals (including SecurityContext) before subscribeOn moves
        // subscription to the streaming scheduler, then restore them on downstream hops.
        .contextCapture()
        // D-03: register the subscription-cancel callback BEFORE tokens flow. Disposable is a
        // @FunctionalInterface, so a () -> subscription.cancel() lambda satisfies the registry
        // contract. cancellationRegistry.cancel(runId) disposes -> cancels the upstream -> tears
        // down the whole pipeline (tool sink + content Flux).
        .doOnSubscribe(subscription ->
                cancellationRegistry.register(runId, subscription::cancel))
        .onErrorResume(ex -> Flux.just(mapToStreamingError(ex)))
        .doFinally(signalType -> {
            // CR-01 fix: reset iteration counter on every terminal signal (complete,
            // cancel, error). Pairs with IterationCounter.start() at the top of Flux.defer
            // — mirrors ask()'s finally at line 433.
            IterationCounter.reset();
            RunContext.clear();
            streamingSinkHolder.unregister(runId);
            cancellationRegistry.clearDisposable(runId);
        });
    }

    /**
     * Maps an exception thrown during streaming into a terminal {@link StreamingEvent.Error}
     * with a stable i18n message key — NEVER the raw provider text (T-07-05 opacity, D-10).
     */
    private StreamingEvent mapToStreamingError(Throwable ex) {
        String key;
        if (ex instanceof RateLimitExceededException) {
            key = "ai-agent.guard.rate-limit-exceeded";
        } else if (ex instanceof TokenBudgetExhaustedException) {
            key = "ai-agent.guard.token-budget-exhausted";
        } else if (ex instanceof IterationCapExceededException) {
            key = "ai-agent.guard.iteration-cap-exceeded";
        } else if (ex instanceof ToolVetoedException) {
            key = "ai-agent.guard.tool-vetoed";
        } else if (ex instanceof ConversationNotFoundException) {
            key = "chatView.error.conversationNotFound";
        } else {
            log.debug("stream() failure — mapping to chatView.error.generic", ex);
            key = "chatView.error.generic";
        }
        return new StreamingEvent.Error(key, Map.of());
    }

    @Override
    public <T> T askTyped(String userId, UUID conversationId, String message, Class<T> targetType) {
        return askTyped(userId, conversationId, message, Overrides.NONE, targetType);
    }

    @Override
    public <T> T askTyped(String userId, UUID conversationId, String message,
                          Overrides overrides, Class<T> targetType) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(targetType);
        String formatHint = converter.getFormat();
        final int maxAttempts = 2; // D-19 / ROADMAP §06 success criterion #4: initial + 1 retry.
        String lastRaw = null;
        String enrichedUserMessage = message + "\n\n" + formatHint;
        // WR-01: thread the conversation id produced by the first attempt forward into retries
        // so a parse failure does not spawn a second conversation (which would lose memory
        // continuity and reset conversation-scoped token budgeting).
        UUID currentConversationId = conversationId;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ChatResponseDto resp = ask(userId, currentConversationId, enrichedUserMessage, overrides);
            currentConversationId = resp.conversationId();
            if (resp.guardDenial() != null) {
                // Guard denial short-circuits typed calls — surface as a typed exception
                // keyed off the denial message key (D-10 / AI-SPEC §4.4).
                throw mapDenialToTypedException(resp.guardDenial(), targetType);
            }
            lastRaw = resp.content();
            try {
                T parsed = converter.convert(lastRaw);
                var violations = validator.validate(parsed);
                if (!violations.isEmpty()) {
                    throw new ConstraintViolationException(violations);
                }
                return parsed;
            } catch (ConstraintViolationException validation) {
                log.warn("askTyped validation attempt {}/{} failed for {}: {}",
                        attempt, maxAttempts, targetType.getSimpleName(), validation.getMessage());
            } catch (RuntimeException ex) {
                // Spring AI 1.1.4 BeanOutputConverter wraps Jackson JsonProcessingException in a
                // plain RuntimeException (no BeanOutputParseException class exists — RESEARCH
                // Open Question 1 resolved). Narrow-by-cause so guard exceptions thrown by the
                // inner ask() bubble out cleanly instead of being swallowed by the retry loop.
                if (!(ex.getCause() instanceof JsonProcessingException)) {
                    throw ex;
                }
                log.warn("askTyped parse attempt {}/{} failed for {}: {}",
                        attempt, maxAttempts, targetType.getSimpleName(), ex.getMessage());
                enrichedUserMessage = message
                        + "\n\nYour previous reply could not be parsed. Strictly follow this format:\n"
                        + formatHint;
            }
        }
        throw new StructuredOutputException(lastRaw, targetType);
    }

    /**
     * Maps a {@link ChatResponseDto.GuardDenialInfo} produced by the inner {@link #ask} call
     * into a typed guard exception so {@code askTyped} callers can use per-exception-type
     * {@code catch} clauses. The synthesised exceptions carry audit-only ceilings of {@code 0}
     * (the real numeric ceilings stay at the guard layer — D-10).
     */
    private RuntimeException mapDenialToTypedException(ChatResponseDto.GuardDenialInfo info,
                                                        Class<?> targetType) {
        return switch (info.messageKey()) {
            case "ai-agent.guard.rate-limit-exceeded"    -> new RateLimitExceededException(0);
            case "ai-agent.guard.token-budget-exhausted" -> new TokenBudgetExhaustedException(0L);
            case "ai-agent.guard.iteration-cap-exceeded" -> new IterationCapExceededException(0);
            case "ai-agent.guard.tool-vetoed"            -> new ToolVetoedException("tool-vetoed");
            default                                       -> new StructuredOutputException(null, targetType);
        };
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
            auditWriter.writeToolCall(RunContext.getRootAuditId(), runId, userUsername, convId,
                    GuardedToolCallingManager.CHAT_SENTINEL_TOOL_NAME,
                    /* argumentsJson */ null, /* resultSummary */ null, 0L,
                    AiToolCallOutcome.BLOCKED, denialKey, /* errorClass */ null);
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
            auditWriter.writeToolCall(RunContext.getRootAuditId(), runId, userUsername, convId,
                    GuardedToolCallingManager.CHAT_SENTINEL_TOOL_NAME,
                    /* argumentsJson */ null, /* resultSummary */ null, 0L,
                    AiToolCallOutcome.FLAGGED, "flagged:" + patternKey,
                    /* errorClass */ null);
        } catch (Throwable t) {
            log.warn("Flagged audit failed runId={} key={}", runId, patternKey, t);
        }
    }

    private static Map<String, Object> auditToolContext(UUID runId, UUID conversationId) {
        return Map.of(
                RunContext.TOOL_CONTEXT_RUN_ID_KEY, runId,
                RunContext.TOOL_CONTEXT_CONVERSATION_ID_KEY, conversationId);
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

    private void publishTitleEligibilityIfAssistantReply(UUID conversationId,
                                                         String userId,
                                                         UUID runId,
                                                         String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        try {
            titleEligibilityPublisher.publishIfEligible(conversationId, userId, runId, safeGetLocale());
        } catch (RuntimeException failure) {
            log.warn("Conversation title eligibility publication failed runId={} convId={}",
                    runId, conversationId, failure);
        }
    }

    private Locale safeGetLocale() {
        try {
            Locale locale = currentAuthentication.getLocale();
            return locale == null ? Locale.getDefault() : locale;
        } catch (RuntimeException anonymous) {
            return Locale.getDefault();
        }
    }
}
