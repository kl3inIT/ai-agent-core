package com.vn.agent.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

/**
 * Output-side injection scanner (GUARD-05, D-17/D-18).
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
 * <p><b>Flag-and-pass-through:</b> the response body is returned unmodified. This is a tripwire,
 * not a filter — it lets operators observe prompt-injection attempts without degrading the UX or
 * making the scan itself a new injection vector.</p>
 *
 * <p><b>ReDoS guard (T-06-12):</b> input text is hard-capped at {@link #MAX_SCAN_CHARS} (8 KiB)
 * before matching. The default {@code ROLE_BREAK} pattern uses a bounded quantifier; operator-
 * supplied regexes may not, so the length cap is the last-line defence.</p>
 *
 * <p>Disabled globally via {@code jmix.ai-agent.guard.output-scanner.enabled=false} — the advisor
 * still runs (so ordering in {@code ChatClientFactory} is stable) but simply returns the response
 * unchanged.</p>
 */
public class OutputScannerAdvisor implements CallAdvisor {

    /** Context-map key the orchestrator reads to detect a flagged response. */
    public static final String CONTEXT_KEY_FLAGGED_PATTERN = "outputScanner.flaggedPatternKey";

    /** Input cap for regex matching (T-06-12). 8 KiB matches the contract documented on
     *  {@code AiAgentGuardProperties.resolvedPatterns()} Javadoc. */
    public static final int MAX_SCAN_CHARS = 8192;

    private static final Logger log = LoggerFactory.getLogger(OutputScannerAdvisor.class);

    private final AiAgentGuardProperties props;
    private final List<CompiledOutputScannerPattern> patterns;

    public OutputScannerAdvisor(AiAgentGuardProperties props) {
        this.props = props;
        this.patterns = props.resolvedPatterns().stream()
                .map(raw -> {
                    try {
                        return CompiledOutputScannerPattern.from(raw);
                    } catch (PatternSyntaxException bad) {
                        log.warn("Skipping invalid scanner regex key={}: {}", raw.key(), bad.getMessage());
                        return null;
                    }
                })
                .filter(p -> p != null)
                .toList();
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
        if (!props.outputScannerEnabled() || response == null) {
            return response;
        }
        if (response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return response;
        }
        AssistantMessage output = response.chatResponse().getResult().getOutput();
        String text = output.getText();
        if (text == null || text.isEmpty()) {
            return response;
        }
        // T-06-12: cap input length before any regex matching.
        String scanned = text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
        for (CompiledOutputScannerPattern p : patterns) {
            if (p.pattern().matcher(scanned).find()) {
                writeFlag(response, p.key());
                break; // first-match wins — one FLAGGED audit row per turn
            }
        }
        return response;
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
