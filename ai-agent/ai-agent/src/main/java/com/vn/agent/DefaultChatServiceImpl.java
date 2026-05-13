package com.vn.agent;

import com.vn.agent.action.ActionIntentId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.conversation.ConversationTitleEligibilityPublisher;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.extraction.ExtractionSourceText;
import com.vn.agent.extraction.IntentOption;
import com.vn.agent.extraction.IntentRegistry;
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
import com.vn.agent.orchestration.ChatModelFallbackAppliedEvent;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.orchestration.SystemPromptComposer;
import com.vn.agent.spi.AuditKind;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

import com.vn.agent.orchestration.ConversationNotFoundException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final String AUTO_INTENT_ID = "auto";

    /**
     * Phase 16 D-05 / MODEL-02 — HTTP status filter for the bad-model classifier. ONLY 4xx
     * statuses that semantically signal "the requested model is invalid" trigger the one-shot
     * fallback reissue:
     * <ul>
     *   <li>{@code 400 Bad Request} — provider rejected the model param shape outright</li>
     *   <li>{@code 404 Not Found} — provider does not know the model id</li>
     *   <li>{@code 422 Unprocessable Entity} — model accepted lexically but semantically
     *       rejected (e.g. context window mismatch)</li>
     * </ul>
     *
     * <p>5xx and 429 are intentionally EXCLUDED (RESEARCH Pitfall 6) — they signal transient
     * provider issues that the {@code spring-ai-retry} advisor already handles; treating them
     * as bad-model would create reissue loops on flaky upstream networks.
     */
    private static final Set<Integer> BAD_MODEL_STATUS_CODES = Set.of(400, 404, 422);

    /**
     * Phase 16 CR-02 — Jackson mapper for serializing {@code argumentsJson} payloads in
     * {@code MODEL_VALIDATION_FAILURE} audit rows. The offending model id is admin-input via a
     * ComboBox with {@code allowCustomValue=true} (Plan 16-05), so a value containing {@code "},
     * {@code \}, or a newline must be properly escaped — string concatenation produces malformed
     * JSON which downstream audit consumers cannot parse. Mirrors the
     * {@code STRUCTURED_PAYLOAD_OBJECT_MAPPER} constant pattern in
     * {@code com.vn.agent.audit.ToolCallbackAuditDecorator}.
     */
    private static final ObjectMapper AUDIT_ARGUMENTS_OBJECT_MAPPER = new ObjectMapper();

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
    private final IntentRegistry intentRegistry;
    private final ConversationTitleEligibilityPublisher titleEligibilityPublisher;
    // Phase 13.1 Plan 03 — per-turn-all Media injection via resolveActive(...). The Phase 13
    // single-turn pending-state stamp and the standalone two-phase user-message persist seam
    // are gone; the chat-memory advisor's own AiMessage projection is now the sole user-message
    // persistence path (RES-01 + project memory feedback_jmix_unconstrained_for_system_writes).
    private final AiTaskFileMediaResolver taskFileMediaResolver;
    private final AiTaskFileRepository taskFileRepository;
    /**
     * Phase 16 D-05 / MODEL-02 — publisher for {@link ChatModelFallbackAppliedEvent} after a
     * successful bad-model-catch + one-shot reissue. NOT used for {@code AiSettingsChangedEvent}
     * (that single-publish-site invariant is owned by Plan 16-05's two entity listeners, which
     * the SecretRedactionInvariantsTest source-scan locks down at build time). This publisher is
     * a fresh injection for the new MODEL-02 notification surface only.
     */
    private final ApplicationEventPublisher eventPublisher;

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
                                  IntentRegistry intentRegistry,
                                  ConversationTitleEligibilityPublisher titleEligibilityPublisher,
                                  AiTaskFileMediaResolver taskFileMediaResolver,
                                  AiTaskFileRepository taskFileRepository,
                                  ApplicationEventPublisher eventPublisher) {
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
        this.intentRegistry = intentRegistry;
        this.titleEligibilityPublisher = titleEligibilityPublisher;
        this.taskFileMediaResolver = taskFileMediaResolver;
        this.taskFileRepository = taskFileRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public ChatResponseDto ask(String userId, UUID conversationId, String message) {
        return ask(userId, conversationId, message, Overrides.NONE);
    }

    @Override
    public ChatResponseDto ask(String userId, UUID conversationId, String message, Overrides overrides) {
        return ask(userId, conversationId, message, overrides, null);
    }

    @Override
    public ChatResponseDto ask(String userId, UUID conversationId, String message,
                               Overrides overrides, String intentId) {
        return ask(userId, conversationId, message, overrides, intentId, null);
    }

    @Override
    public ChatResponseDto ask(String userId, UUID conversationId, String message,
                               Overrides overrides, String intentId, String privateSystemAppendix) {
        final Overrides effectiveOverrides = overrides == null ? Overrides.NONE : overrides;
        final String normalizedIntentId = normalizeIntentId(intentId);
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

            IntentOption selectedIntent = resolveSelectedIntent(normalizedIntentId);
            if (isNamedExtractionIntent(normalizedIntentId) && selectedIntent == null) {
                return ChatResponseDto.denied(convId, runId,
                        "chatView.intent.unknownIntent", Map.of());
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
                    effectiveRules(normalizedIntentId, selectedIntent));
            composedSystemPrompt = appendPrivateSystemAppendix(composedSystemPrompt, privateSystemAppendix);

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

            // Phase 13.1 Plan 03 RES-01 — per-turn-all Media injection. The resolver
            // loads ALL non-expired AiTaskFile rows for the conversation in DESC order,
            // applies the per-turn caps, and returns Resolved(media, budgetExceeded).
            // The Phase 13 single-turn pending-state stamp and the two-phase write seam
            // are gone; the chat-memory advisor's own AiMessage projection is now the
            // sole user-message persistence path.
            final AiTaskFileMediaResolver.Resolved resolvedMedia =
                    taskFileMediaResolver.resolveActive(convId);

            return executeBlockingTurn(userId, convId, message, effectiveOverrides,
                    resolvedMedia, composedSystemPrompt, model, active,
                    ragFilter, retrievalTopK, retrievalSimilarityThreshold,
                    extractionIntentId(normalizedIntentId), normalizedIntentId, runId, startNanos);
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
        } catch (AgentToolCallbacks.ToolConfigurationException configurationFailure) {
            UUID convId = conversation != null ? conversation.getId() : null;
            return ChatResponseDto.denied(convId, runId,
                    "chatView.intent.configurationError", Map.of());
        } finally {
            IterationCounter.reset();
            com.vn.agent.orchestration.RunContext.clear();
        }
    }

    /**
     * Executes the blocking-call portion of a chat turn against an ALREADY-RESOLVED
     * Media list. Used by both the public {@link #ask} entrypoint AND the
     * {@link #stream} catch-block fallback (D-04 graceful streaming fallback) so
     * the resolver runs exactly ONCE per turn — the BLK-01 invariant from Phase 13
     * (gap-closure plan 13-06). Phase 13.1 Plan 03 dropped the
     * {@code userMessageIdAlreadyPersisted} parameter because the per-turn-all
     * resolver no longer needs a pending-state marker, so there is nothing left
     * for an external persister to thread back here.
     *
     * @param userId attribution user (for audit/run-context)
     * @param convId conversation id (already loaded by caller)
     * @param message raw user-message text (verbatim)
     * @param effectiveOverrides resolved overrides (NEVER null; caller normalizes)
     * @param resolvedMedia pre-resolved Media + budgetExceeded; may be empty but never null
     * @param composedSystemPrompt system prompt already composed by caller
     * @param model resolved model id (caller already applied Overrides + AiParameters)
     * @param active resolved {@link AiParameters} snapshot (caller already loaded)
     * @param ragFilter RAG filter expression or {@code null} (admin-bypass)
     * @param retrievalTopK already-resolved RAG top-K
     * @param retrievalSimilarityThreshold already-resolved similarity threshold
     * @param runId already-allocated run id (caller manages RunContext lifecycle)
     * @param startNanos start instant for latency calculation
     * @return blocking ChatResponseDto for the turn (carries
     *         {@code budgetExceeded} from the resolver per D-D1)
     */
    private ChatResponseDto executeBlockingTurn(String userId,
                                                UUID convId,
                                                String message,
                                                Overrides effectiveOverrides,
                                                AiTaskFileMediaResolver.Resolved resolvedMedia,
                                                String composedSystemPrompt,
                                                String model,
                                                AiParameters active,
                                                Filter.Expression ragFilter,
                                                int retrievalTopK,
                                                double retrievalSimilarityThreshold,
                                                String extractionIntentId,
                                                String toolSurfaceIntentId,
                                                UUID runId,
                                                long startNanos) {
        // Phase 13.1 UAT-fix-02 — Tika-extracted document text rides the SYSTEM prompt,
        // NOT the user message text. Spring AI's chat memory only persists USER +
        // ASSISTANT messages, so per-turn rebuilt system prompts never pollute history
        // replay. The earlier attempt prepended documents into the user message and the
        // "=== End === / User message:" scaffolding leaked into next-session UI replay.
        final String systemPromptWithDocs = appendDocumentBlocks(
                composedSystemPrompt, resolvedMedia.documentTexts());
        RunContext.setExtractionTurn(extractionIntentId, convId, message,
                resolvedMedia.taskFileIds(), resolvedMedia.media(),
                sourceTexts(resolvedMedia.documentTexts()));

        // Phase 16 D-05 / MODEL-02 — bad-model catch + one-shot reissue. Catches
        // RuntimeException (broadest viable type — codex HIGH Concern #8) and applies the
        // classifier to the cause chain; non-bad-model RuntimeExceptions are rethrown
        // unchanged. The saved AiParameters.bodyYaml.model is NEVER mutated by this path.
        ChatClientResponse clientResp;
        String effectiveModel = model;
        try {
            clientResp = invokeBlockingChatClient(systemPromptWithDocs, message, resolvedMedia,
                    userId, convId, toolSurfaceIntentId, runId,
                    retrievalTopK, retrievalSimilarityThreshold, ragFilter,
                    model, active);
        } catch (RuntimeException providerEx) {
            if (!isBadModelException(providerEx)) {
                throw providerEx;
            }
            String fallback = parametersResolver.fallbackModel();
            // Defensive guard: if the configured fallback IS the offending model (admin set
            // defaults.model = same bad id), do NOT loop — rethrow the original exception so
            // the streaming/blocking caller surfaces it via the existing error path.
            if (fallback == null || fallback.equals(model)) {
                throw providerEx;
            }
            writeModelValidationFailureAudit(runId, userId, convId, model, providerEx);
            log.warn("MODEL_VALIDATION_FAILURE convId={} runId={} offendingModel={} fallback={} status={}",
                    convId, runId, model, fallback, extractBadModelStatus(providerEx));
            clientResp = invokeBlockingChatClient(systemPromptWithDocs, message, resolvedMedia,
                    userId, convId, toolSurfaceIntentId, runId,
                    retrievalTopK, retrievalSimilarityThreshold, ragFilter,
                    fallback, active);
            effectiveModel = fallback;
            // MODEL-02 user-visible fallback notification surface (codex HIGH Concern #9 —
            // wired in this phase, not deferred). ChatPanelFragment subscribes via
            // @EventListener and filters by conversationId before rendering the toast.
            try {
                eventPublisher.publishEvent(new ChatModelFallbackAppliedEvent(
                        this, runId, convId, model, fallback));
            } catch (RuntimeException publishFailure) {
                // Notification surface failure must not break a successfully-recovered turn.
                log.warn("ChatModelFallbackAppliedEvent publication failed runId={}", runId,
                        publishFailure);
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
        // Phase 16 D-05 — the model id reported on the DTO is the one the successful response
        // came from: original on the happy path, fallback after a bad-model reissue.
        log.debug("ChatService.executeBlockingTurn convId={} runId={} model={} latencyMs={} flagged={}",
                convId, runId, effectiveModel, latencyMs, flagged);
        publishTitleEligibilityIfAssistantReply(convId, userId, runId, content);
        return new ChatResponseDto(convId, runId, content, effectiveModel, latencyMs,
                flagged, flaggedKey, null, resolvedMedia.budgetExceeded());
    }

    @Override
    public Flux<StreamingEvent> stream(String userId, UUID conversationId, String message, Overrides overrides) {
        return stream(userId, conversationId, message, overrides, null);
    }

    @Override
    public Flux<StreamingEvent> stream(String userId, UUID conversationId, String message,
                                       Overrides overrides, String intentId) {
        return stream(userId, conversationId, message, overrides, intentId, null);
    }

    @Override
    public Flux<StreamingEvent> stream(String userId, UUID conversationId, String message,
                                       Overrides overrides, String intentId, String privateSystemAppendix) {
        final Overrides effectiveOverrides = overrides == null ? Overrides.NONE : overrides;
        final String normalizedIntentId = normalizeIntentId(intentId);
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

                    IntentOption selectedIntent = resolveSelectedIntent(normalizedIntentId);
                    if (isNamedExtractionIntent(normalizedIntentId) && selectedIntent == null) {
                        long denyLatencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                        return Flux.<StreamingEvent>just(
                                new StreamingEvent.Error("chatView.intent.unknownIntent", Map.of()),
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
                            effectiveRules(normalizedIntentId, selectedIntent));
                    composedSystemPrompt = appendPrivateSystemAppendix(composedSystemPrompt, privateSystemAppendix);
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

                    // Phase 13.1 Plan 03 RES-01 — per-turn-all Media injection.
                    // RESEARCH Pitfall 8: hoisted INSIDE Flux.defer so readAllBytes does not
                    // run on the calling thread before subscription. The Phase 13 single-turn
                    // pending-state stamp and the two-phase write seam are gone; the chat-memory
                    // advisor's own AiMessage projection is the sole user-message persistence
                    // path going forward.
                    final AiTaskFileMediaResolver.Resolved resolvedMedia =
                            taskFileMediaResolver.resolveActive(convId);
                    // Phase 13.1 UAT-fix-02 — system-prompt injection of document blocks
                    // (see blocking path comment for rationale).
                    final String systemPromptWithDocs = appendDocumentBlocks(
                            composedSystemPrompt, resolvedMedia.documentTexts());
                    RunContext.setExtractionTurn(extractionIntentId(normalizedIntentId), convId, message,
                            resolvedMedia.taskFileIds(), resolvedMedia.media(),
                            sourceTexts(resolvedMedia.documentTexts()));

                    Flux<StreamingEvent> content;
                    try {
                        content = chatClient.prompt()
                                .system(systemPromptWithDocs)
                                .user(u -> {
                                    // AI-SPEC pitfall 6: lambda form REQUIRED when media is non-empty.
                                    u.text(message);
                                    if (!resolvedMedia.media().isEmpty()) {
                                        u.media(resolvedMedia.media().toArray(new Media[0]));
                                    }
                                })
                                .toolCallbacks(toolCallbacks.callbacksFor(userId, convId, normalizedIntentId))
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
                                .doOnComplete(toolSink::tryEmitComplete)
                                .doOnError(ex -> toolSink.tryEmitComplete());
                    } catch (UnsupportedOperationException nonStreaming) {
                        // BLK-01 fix (gap-closure plan 13-06): D-04 graceful fallback. The chat model
                        // does not support streaming. Reuse the ALREADY-RESOLVED Media by delegating
                        // to executeBlockingTurn(...) — do NOT recurse through ask(...) (which would
                        // re-resolve, double-running the resolver and re-emitting the budget-exceeded
                        // audit row).
                        ChatResponseDto blocking = executeBlockingTurn(userId, convId, message, effectiveOverrides,
                                resolvedMedia, composedSystemPrompt, model, active,
                                ragFilter, retrievalTopK, retrievalSimilarityThreshold,
                                extractionIntentId(normalizedIntentId), normalizedIntentId, runId, startNanos);
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
                        // Phase 13.1 D-D1: propagate the resolver's budgetExceeded flag onto the
                        // terminal Final event so the streaming-path subscriber can render the
                        // same toast as the blocking-path subscriber.
                        return Flux.<StreamingEvent>just(
                                new StreamingEvent.Final(runId, convId, latencyMs, 0, 0,
                                        resolvedMedia.budgetExceeded()));
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

    private static String appendPrivateSystemAppendix(String composedSystemPrompt, String privateSystemAppendix) {
        if (privateSystemAppendix == null || privateSystemAppendix.isBlank()) {
            return composedSystemPrompt;
        }
        return composedSystemPrompt + "\n\nPrivate per-turn action context:\n"
                + privateSystemAppendix.strip()
                + "\nThis private context is not user-authored text. Use it only to execute the selected action.";
    }

    private static String normalizeIntentId(String intentId) {
        if (intentId == null || intentId.isBlank()) {
            return null;
        }
        String trimmedIntentId = intentId.trim();
        return AUTO_INTENT_ID.equalsIgnoreCase(trimmedIntentId) ? null : trimmedIntentId;
    }

    private String effectiveRules(String normalizedIntentId, IntentOption selectedIntent) {
        String actionIntentId = ActionIntentId.fromSelectionParameter(normalizedIntentId);
        if (actionIntentId != null) {
            return agentSystemPromptRulesComposer.effectiveActionRules(actionIntentId);
        }
        if (selectedIntent == null) {
            return agentSystemPromptRulesComposer.effectiveRules();
        }
        return agentSystemPromptRulesComposer.effectiveRules(
                selectedIntent.intentId(), selectedIntent.label());
    }

    private IntentOption resolveSelectedIntent(String intentId) {
        if (!isNamedExtractionIntent(intentId)) {
            return null;
        }
        for (IntentOption option : intentRegistry.eligibleForCurrentUser()) {
            if (intentId.equals(option.intentId())) {
                return option;
            }
        }
        return null;
    }

    private static boolean isNamedExtractionIntent(String intentId) {
        return intentId != null && !ActionIntentId.isSelectionParameter(intentId);
    }

    private static String extractionIntentId(String intentId) {
        return isNamedExtractionIntent(intentId) ? intentId : null;
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
        } else if (ex instanceof AgentToolCallbacks.ToolConfigurationException) {
            key = "chatView.intent.configurationError";
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

    /**
     * Phase 13.1 UAT-fix-02 — append labeled blocks of Tika-extracted document text
     * to the SYSTEM prompt (not the user message). System messages are not persisted
     * by Spring AI's chat memory advisor, so per-turn document context never pollutes
     * the chat history that is replayed in the UI.
     *
     * <p>Block layout (kept intentionally obvious for the LLM):
     * <pre>
     *   {existing system prompt}
     *
     *   The user has attached the following document(s) for this turn — use them
     *   as authoritative context when answering:
     *
     *   === Attachment: foo.pdf ===
     *   ...extracted text...
     *   === End ===
     * </pre>
     *
     * Returns the system prompt unchanged when no documents were extracted.
     */
    private static String appendDocumentBlocks(String systemPrompt,
                                               List<AiTaskFileMediaResolver.DocumentText> documents) {
        if (documents == null || documents.isEmpty()) {
            return systemPrompt == null ? "" : systemPrompt;
        }
        StringBuilder sb = new StringBuilder(systemPrompt == null ? 0 : systemPrompt.length() + 512);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            sb.append(systemPrompt).append("\n\n");
        }
        sb.append("The user has attached the following document(s) for this turn — " +
                "use them as authoritative context when answering:\n\n");
        for (AiTaskFileMediaResolver.DocumentText d : documents) {
            sb.append("=== Attachment: ").append(d.filename()).append(" ===\n");
            sb.append(d.text());
            if (d.truncated()) {
                sb.append("\n[...truncated to fit token budget...]");
            }
            sb.append("\n=== End ===\n\n");
        }
        return sb.toString();
    }

    private static List<ExtractionSourceText> sourceTexts(
            List<AiTaskFileMediaResolver.DocumentText> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .map(document -> new ExtractionSourceText(
                        document.filename(), document.text(), document.truncated()))
                .toList();
    }

    // ====================================================================
    // Phase 16 D-05 / MODEL-02 — bad-model catch + one-shot reissue helpers
    // ====================================================================

    /**
     * Builds and invokes the {@code chatClient.prompt()...call().chatClientResponse()} chain
     * with the supplied {@code chosenModel} as the {@link ChatOptions} model id. Extracted from
     * {@code executeBlockingTurn} so the bad-model reissue can hit the same code path with a
     * different model id without duplicating the prompt-shape builder.
     */
    private ChatClientResponse invokeBlockingChatClient(String systemPromptWithDocs,
                                                        String message,
                                                        AiTaskFileMediaResolver.Resolved resolvedMedia,
                                                        String userId,
                                                        UUID convId,
                                                        String toolSurfaceIntentId,
                                                        UUID runId,
                                                        int retrievalTopK,
                                                        double retrievalSimilarityThreshold,
                                                        Filter.Expression ragFilter,
                                                        String chosenModel,
                                                        AiParameters active) {
        return chatClient.prompt()
                .system(systemPromptWithDocs)
                .user(u -> {
                    // AI-SPEC pitfall 6: lambda form is REQUIRED when injecting Media.
                    u.text(message);
                    if (!resolvedMedia.media().isEmpty()) {
                        u.media(resolvedMedia.media().toArray(new Media[0]));
                    }
                })
                .toolCallbacks(toolCallbacks.callbacksFor(userId, convId, toolSurfaceIntentId))
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
                        .model(chosenModel)
                        .temperature(parametersResolver.effectiveTemperature(active))
                        .topP(parametersResolver.effectiveTopP(active))
                        .maxTokens(parametersResolver.effectiveMaxTokens(active))
                        .build())
                .call()
                .chatClientResponse();
    }

    /**
     * Phase 16 D-05 / MODEL-02 — classifier for the bad-model catch + reissue path. Walks the
     * cause chain (depth-bounded at 5) and returns {@code true} IFF the chain contains either:
     * <ul>
     *   <li>A direct {@link RestClientResponseException} whose status matches
     *       {@link #BAD_MODEL_STATUS_CODES} AND whose body/message contains the substring
     *       {@code "model"} (case-insensitive), OR</li>
     *   <li>A {@link org.springframework.ai.retry.NonTransientAiException} wrapping a
     *       {@link RestClientResponseException} that matches the same shape.</li>
     * </ul>
     *
     * <p>Package-private + static for unit-test isolation (opencode Suggestion #4): the suite
     * can construct synthetic exceptions and exercise the classifier without booting the chat
     * pipeline. Catches BOTH the direct {@link RestClientResponseException} (Spring AI 1.x
     * bare-bones provider path) AND the NonTransientAiException wrapping (Spring AI 1.x
     * normalized error path) per codex HIGH Concern #8.
     *
     * <p>5xx is intentionally excluded — those are transient provider failures that
     * {@code spring-ai-retry} handles via the retry advisor. Substring-only matches without a
     * trusted structural status code are ALSO excluded (Pitfall 6 — the message "model
     * unavailable" on a generic 500 is not a bad-model signal).
     */
    static boolean isBadModelException(Throwable t) {
        Throwable cursor = t;
        for (int depth = 0; cursor != null && depth < 5; depth++, cursor = cursor.getCause()) {
            // Case A: direct RestClientResponseException (Spring AI 1.x bare-bones provider path)
            if (cursor instanceof RestClientResponseException rcre) {
                if (matchesBadModelShape(rcre)) {
                    return true;
                }
                continue;
            }
            // Case B: NonTransientAiException wrapping a RestClientResponseException
            // (Spring AI 1.x normalized provider error path). Walk the wrapped cause.
            if (cursor instanceof org.springframework.ai.retry.NonTransientAiException) {
                Throwable inner = cursor.getCause();
                int innerDepth = 0;
                while (inner != null && innerDepth < 5) {
                    if (inner instanceof RestClientResponseException rcre) {
                        if (matchesBadModelShape(rcre)) {
                            return true;
                        }
                        break;
                    }
                    inner = inner.getCause();
                    innerDepth++;
                }
                // If NonTransientAiException has no RestClientResponseException cause but its
                // own message mentions "model" — still NOT a bad-model signal without a status
                // code we can structurally trust (Pitfall 6 false-positive guard).
            }
        }
        return false;
    }

    private static boolean matchesBadModelShape(RestClientResponseException rcre) {
        int status = rcre.getStatusCode().value();
        if (!BAD_MODEL_STATUS_CODES.contains(status)) {
            return false;
        }
        String body = rcre.getResponseBodyAsString();
        if (body != null && body.toLowerCase(Locale.ROOT).contains("model")) {
            return true;
        }
        String message = rcre.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("model");
    }

    /**
     * Extracts the HTTP status from the cause chain for audit-row stamping; returns {@code -1}
     * if no {@link RestClientResponseException} is found (defensive — callers only invoke this
     * after {@link #isBadModelException} returned true, so a status WILL be present).
     *
     * <p>WR-04: mirrors {@link #isBadModelException}'s traversal — including the descent into
     * {@link org.springframework.ai.retry.NonTransientAiException#getCause()} for the Case B
     * (wrapped RCRE) shape. The previous linear-outer-walk-only implementation returned
     * {@code -1} for chains where the RCRE was nested inside a NonTransientAiException, so the
     * audit row recorded a misleading {@code status=-1} even though the classifier had matched.</p>
     */
    private static int extractBadModelStatus(Throwable cause) {
        RestClientResponseException rcre = findCausalRcre(cause);
        return rcre == null ? -1 : rcre.getStatusCode().value();
    }

    /**
     * WR-04 — shared cause-chain walker used by {@link #extractBadModelStatus} (and tested in
     * parallel with {@link #isBadModelException}). Returns the first {@link RestClientResponseException}
     * reachable in the depth-bounded (≤ 5) outer chain OR, when the cursor is a
     * {@link org.springframework.ai.retry.NonTransientAiException}, the depth-bounded inner-chain
     * walk underneath it. Returns {@code null} if no RCRE is found.
     *
     * <p>Note: the classifier {@link #isBadModelException} additionally filters by
     * {@link #matchesBadModelShape}; this helper deliberately does NOT — its job is to surface
     * the status code for the audit row even if the body/message-substring check would have
     * failed (the audit row records what the classifier saw, including the status of a
     * shape-matched RCRE; callers only invoke it after the classifier returned true).</p>
     */
    private static RestClientResponseException findCausalRcre(Throwable t) {
        Throwable cursor = t;
        for (int depth = 0; cursor != null && depth < 5; depth++, cursor = cursor.getCause()) {
            if (cursor instanceof RestClientResponseException rcre) {
                return rcre;
            }
            if (cursor instanceof org.springframework.ai.retry.NonTransientAiException) {
                Throwable inner = cursor.getCause();
                int innerDepth = 0;
                while (inner != null && innerDepth < 5) {
                    if (inner instanceof RestClientResponseException rcre) {
                        return rcre;
                    }
                    inner = inner.getCause();
                    innerDepth++;
                }
            }
        }
        return null;
    }

    /**
     * Writes the {@code MODEL_VALIDATION_FAILURE} audit row. Uses the
     * {@link AuditWriter#writeAuditEvent(String, UUID, UUID, String, UUID, String, String, String, long, AiToolCallOutcome, String, String)}
     * overload added by Plan 16-01 (consensus HIGH Concern #1 resolved at Wave 0). The body
     * carries HTTP status + sanitised exception class name only — P-22 / T-16-04 mitigation:
     * the raw provider response body NEVER touches the audit row. The offending model id is
     * admin-input (not user-input) and is included verbatim.
     */
    private void writeModelValidationFailureAudit(UUID runId, String userUsername, UUID convId,
                                                  String offendingModel, Throwable cause) {
        try {
            int status = extractBadModelStatus(cause);
            String errorClass = cause.getClass().getSimpleName();
            String resultSummary = String.format(Locale.ROOT,
                    "model=%s status=%d error=%s", offendingModel, status, errorClass);
            // CR-02 (P-22 sanitisation precedent): serialize the {model: offendingModel} payload
            // via Jackson so a custom-entry model id containing `"`, `\`, or newline produces
            // valid JSON. Raw string concatenation accepted admin-input verbatim and could write
            // a malformed audit row that downstream JSON parsers reject — losing forensic context.
            String argumentsJson;
            try {
                argumentsJson = AUDIT_ARGUMENTS_OBJECT_MAPPER.writeValueAsString(
                        Map.of("model", offendingModel == null ? "" : offendingModel));
            } catch (JsonProcessingException jpe) {
                argumentsJson = "{}";
            }
            auditWriter.writeAuditEvent(
                    AuditKind.MODEL_VALIDATION_FAILURE,
                    RunContext.getRootAuditId(),
                    runId,
                    userUsername,
                    convId,
                    /* toolName = sentinel — not a real @Tool method */ "model_validation",
                    argumentsJson,
                    resultSummary,
                    /* latencyMs */ 0L,
                    AiToolCallOutcome.FAILED,
                    /* denialReason */ null,
                    errorClass);
        } catch (Throwable t) {
            // Audit-row failure must NOT break the user-facing reissue path. The recovered turn
            // still ships; the operator just loses the correlated MODEL_VALIDATION_FAILURE row.
            log.warn("MODEL_VALIDATION_FAILURE audit row failed runId={} model={}",
                    runId, offendingModel, t);
        }
    }
}
