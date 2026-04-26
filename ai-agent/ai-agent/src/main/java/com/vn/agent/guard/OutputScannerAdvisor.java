package com.vn.agent.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

/**
 * Output-side injection scanner (GUARD-05, D-17/D-18; Phase 9 PROMPT-06).
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} {@code + 400} so it executes AFTER the chain has
 * produced a response but INSIDE the outer {@code AuditAdvisor} envelope — this guarantees that a
 * flagged response still produces a POST audit row. Each configured regex is compiled once at
 * construction time via {@link CompiledOutputScannerPattern} and matched against the final
 * assistant text; on the first hit the advisor writes the pattern's KEY (never the matched text,
 * D-17) into {@link ChatClientResponse#context()} under
 * {@value #CONTEXT_KEY_FLAGGED_PATTERN}. Plan 04's orchestrator promotes this entry into
 * {@code ChatResponseDto.flagged + flaggedPatternKey} and writes an
 * {@link com.vn.agent.entity.AiToolCallOutcome#FLAGGED} audit row.</p>
 *
 * <p><b>Phase 9 pattern packs:</b> alongside the bundled defaults
 * ({@code IGNORE_PREVIOUS_INSTRUCTIONS}, {@code SYSTEM_TAG_LEAK}, {@code ROLE_BREAK}), this
 * advisor merges in two startup-snapshotted dynamic patterns when their providers report a
 * pattern: {@link HostPrefixPatternProvider#PATTERN_KEY HOST_PREFIX_LEAK} (host-prefixed entity
 * names like {@code jmixapp_Customer}) and {@link ToolNamePatternProvider#PATTERN_KEY TOOL_NAME_LEAK}
 * (the six built-in tool names + {@code RETRIEVAL} + every {@code ToolContributor} tool name
 * snapshot). Dynamic merge happens lazily on the first call so provider compilation can complete
 * after the advisor singleton is constructed.</p>
 *
 * <p><b>Flag-and-pass-through:</b> the response body is returned unmodified. This is a tripwire,
 * not a filter — it lets operators observe prompt-injection attempts without degrading the UX or
 * making the scan itself a new injection vector.</p>
 *
 * <p><b>Streaming coverage (Phase 9):</b> implements {@link StreamAdvisor} alongside
 * {@link CallAdvisor}. Streamed responses are aggregated through Spring AI's
 * {@link ChatClientMessageAggregator} so the same scan-and-flag logic runs over the final
 * assembled assistant text — the operator gets identical FLAGGED audit rows for blocking and
 * streaming chat.</p>
 *
 * <p><b>ReDoS guard (T-06-12):</b> input text is hard-capped at {@link #MAX_SCAN_CHARS} (8 KiB)
 * before matching. The default {@code ROLE_BREAK} pattern uses a bounded quantifier; operator-
 * supplied regexes may not, so the length cap is the last-line defence. Phase 9 dynamic patterns
 * are also wrapped in {@link java.util.regex.Pattern#quote(String)} per token (T-09-22).</p>
 *
 * <p>Disabled globally via {@code jmix.ai-agent.guard.output-scanner.enabled=false} — the advisor
 * still runs (so ordering in {@code ChatClientFactory} is stable) but simply returns the response
 * unchanged.</p>
 */
public class OutputScannerAdvisor implements CallAdvisor, StreamAdvisor {

    /** Context-map key the orchestrator reads to detect a flagged response. */
    public static final String CONTEXT_KEY_FLAGGED_PATTERN = "outputScanner.flaggedPatternKey";

    /** Input cap for regex matching (T-06-12). 8 KiB matches the contract documented on
     *  {@code AiAgentGuardProperties.resolvedPatterns()} Javadoc. */
    public static final int MAX_SCAN_CHARS = 8192;

    private static final Logger log = LoggerFactory.getLogger(OutputScannerAdvisor.class);

    private final AiAgentGuardProperties props;
    private final HostPrefixPatternProvider hostPrefixProvider;
    private final ToolNamePatternProvider toolNameProvider;

    /** Static patterns (bundled defaults + operator-supplied): compiled once at construction. */
    private final List<CompiledOutputScannerPattern> staticPatterns;

    /** Dynamic patterns (provider-supplied): compiled on first scan via {@link #ensureDynamicCompiled()}. */
    private volatile List<CompiledOutputScannerPattern> dynamicPatterns;
    private volatile boolean dynamicCompiled;
    private final Object dynamicLock = new Object();

    public OutputScannerAdvisor(AiAgentGuardProperties props,
                                HostPrefixPatternProvider hostPrefixProvider,
                                ToolNamePatternProvider toolNameProvider) {
        this.props = props;
        this.hostPrefixProvider = hostPrefixProvider;
        this.toolNameProvider = toolNameProvider;
        this.staticPatterns = compile(props.resolvedPatterns());
    }

    private static List<CompiledOutputScannerPattern> compile(
            List<AiAgentGuardProperties.OutputScanner.Pattern> raws) {
        List<CompiledOutputScannerPattern> out = new ArrayList<>(raws.size());
        for (AiAgentGuardProperties.OutputScanner.Pattern raw : raws) {
            try {
                out.add(CompiledOutputScannerPattern.from(raw));
            } catch (PatternSyntaxException bad) {
                log.warn("Skipping invalid scanner regex key={}: {}", raw.key(), bad.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Lazily materialise dynamic patterns from the two providers. Compiled-once: subsequent calls
     * reuse the cached list. The providers' own {@code asPattern()} methods are themselves lazy
     * (they pre-warm at {@code ApplicationReadyEvent} but build on first call as fallback) — by
     * the time a chat actually flows, both layers are populated.
     */
    private List<CompiledOutputScannerPattern> ensureDynamicCompiled() {
        List<CompiledOutputScannerPattern> cached = this.dynamicPatterns;
        if (cached != null) {
            return cached;
        }
        synchronized (dynamicLock) {
            if (this.dynamicPatterns != null) {
                return this.dynamicPatterns;
            }
            List<AiAgentGuardProperties.OutputScanner.Pattern> raws = new ArrayList<>(2);
            if (hostPrefixProvider != null) {
                hostPrefixProvider.asPattern().ifPresent(raws::add);
            }
            if (toolNameProvider != null) {
                toolNameProvider.asPattern().ifPresent(raws::add);
            }
            List<CompiledOutputScannerPattern> compiled = compile(raws);
            this.dynamicPatterns = compiled;
            this.dynamicCompiled = true;
            return compiled;
        }
    }

    /**
     * Visible for test introspection: returns whether the dynamic-pattern compile path has run.
     * Production callers do not consult this; it lets unit tests assert eagerness without
     * reflecting into private state.
     */
    boolean dynamicPatternsCompiled() {
        return this.dynamicCompiled;
    }

    @Override
    public @NonNull String getName() {
        return "OutputScannerAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 400;
    }

    @Override
    public @NonNull ChatClientResponse adviseCall(@NonNull ChatClientRequest request,
                                                   @NonNull CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        if (response == null) {
            throw new IllegalStateException("CallAdvisorChain returned null ChatClientResponse");
        }
        if (!props.outputScannerEnabled()) {
            return response;
        }
        scanAndFlag(response);
        return response;
    }

    @Override
    public @NonNull Flux<ChatClientResponse> adviseStream(@NonNull ChatClientRequest request,
                                                          @NonNull StreamAdvisorChain chain) {
        Flux<ChatClientResponse> responses = chain.nextStream(request);
        if (!props.outputScannerEnabled()) {
            return responses;
        }
        // Aggregate the streamed chunks into a single ChatClientResponse and run the same
        // scan-and-flag pipeline against the final assistant text. The original Flux is still
        // delivered downstream unchanged (flag-and-pass-through posture preserved).
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(responses, this::scanAndFlag);
    }

    /**
     * Shared scan-and-flag helper consumed by both {@link #adviseCall} and
     * {@link #adviseStream}. Writes the pattern KEY (never the matched text) into the response's
     * context map on the first hit; first-match wins so one FLAGGED audit row per turn.
     */
    private void scanAndFlag(ChatClientResponse response) {
        if (response == null
                || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = response.chatResponse().getResult().getOutput();
        String text = output.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        // T-06-12: cap input length before any regex matching.
        String scanned = text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
        // Static patterns first (bundled defaults + operator-supplied), then dynamic Phase 9 packs.
        for (CompiledOutputScannerPattern p : staticPatterns) {
            if (p.pattern().matcher(scanned).find()) {
                writeFlag(response, p.key());
                return; // first-match wins — one FLAGGED audit row per turn
            }
        }
        for (CompiledOutputScannerPattern p : ensureDynamicCompiled()) {
            if (p.pattern().matcher(scanned).find()) {
                writeFlag(response, p.key());
                return;
            }
        }
    }

    private static void writeFlag(ChatClientResponse response, String patternKey) {
        try {
            Map<String, Object> context = response.context();
            if (context != null) {
                context.put(CONTEXT_KEY_FLAGGED_PATTERN, patternKey);
            }
        } catch (Exception ex) {
            // Map may be immutable on some response implementations — log and continue;
            // the chat still returns cleanly to the user.
            log.debug("Failed to write scanner flag to response context (pattern key={})", patternKey, ex);
        }
    }
}
