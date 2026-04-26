package com.vn.agent.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration surface for the guardrail stack, bound to {@code jmix.ai-agent.guard.*}.
 * Picked up by the {@code @ConfigurationPropertiesScan} on {@code AIConfiguration} — no
 * {@code @Component} needed.
 *
 * <p>Default values (consulted by {@code resolved*} accessors when the corresponding property is
 * absent):
 * <ul>
 *   <li>D-13: {@code resolvedRequestsPerMinute() = 10} (per-user chat rate limiter ceiling).</li>
 *   <li>D-14: {@code resolvedTokenCeiling() = 100_000} (per-conversation rolling token ceiling).</li>
 *   <li>D-16: {@code resolvedMaxIterations() = 6} (tool-calling iteration cap).</li>
 *   <li>D-18: {@code resolvedPatterns()} — three bundled regex defaults covering prompt-injection
 *       canaries, system-tag leakage, and role-break attempts.</li>
 * </ul>
 * Each section has an {@code enabled} boolean that defaults to {@code true} when omitted; hosts
 * set the key to {@code false} to opt out without removing the nested block.</p>
 *
 * <p><b>Security note:</b> no secrets are carried in this record; its {@code toString} is safe
 * to log. Patterns are regex <em>sources</em> — operator-provided values must survive compile in
 * {@link java.util.regex.Pattern#compile(String)}; the consumer {@code OutputScannerAdvisor}
 * (Plan 04) catches {@link java.util.regex.PatternSyntaxException} and skips malformed entries.</p>
 */
@ConfigurationProperties("jmix.ai-agent.guard")
public record AiAgentGuardProperties(
        RateLimit rateLimit,
        TokenBreaker tokenBreaker,
        IterationCap iterationCap,
        OutputScanner outputScanner) {

    /** Per-user chat rate limiter config (D-13, GUARD-04). */
    public record RateLimit(Boolean enabled, Integer requestsPerMinute) {
    }

    /** Per-conversation rolling token circuit breaker config (D-14, GUARD-03). */
    public record TokenBreaker(Boolean enabled, Long ceiling) {
    }

    /** Tool-calling iteration cap config (D-16, GUARD-02). */
    public record IterationCap(Integer maxIterations) {
    }

    /** Output-side injection-pattern scanner config (D-17/D-18, GUARD-05; Phase 9 PROMPT-06). */
    public record OutputScanner(Boolean enabled,
                                List<Pattern> patterns,
                                HostPrefixLeak hostPrefixLeak,
                                ToolNameLeak toolNameLeak) {

        /** One scanner pattern: a stable {@code key} for audit rows + the regex {@code source}. */
        public record Pattern(String key, String regex) {
        }

        /** Phase 9 D-08: host-prefix leak pattern toggle (default enabled). */
        public record HostPrefixLeak(Boolean enabled) {
        }

        /** Phase 9 D-08: tool-name leak pattern toggle (default enabled). */
        public record ToolNameLeak(Boolean enabled) {
        }
    }

    /** D-13: rate-limit defaults to enabled when block omitted. */
    public boolean rateLimitEnabled() {
        return rateLimit == null || !Boolean.FALSE.equals(rateLimit.enabled());
    }

    /** D-13: default ceiling 10 requests/min per user. */
    public int resolvedRequestsPerMinute() {
        return rateLimit == null || rateLimit.requestsPerMinute() == null ? 10 : rateLimit.requestsPerMinute();
    }

    /** D-14: token breaker defaults to enabled when block omitted. */
    public boolean tokenBreakerEnabled() {
        return tokenBreaker == null || !Boolean.FALSE.equals(tokenBreaker.enabled());
    }

    /** D-14: default ceiling 100_000 tokens per conversation. */
    public long resolvedTokenCeiling() {
        return tokenBreaker == null || tokenBreaker.ceiling() == null ? 100_000L : tokenBreaker.ceiling();
    }

    /** D-16: default iteration cap 6 (matches {@code ToolCallingManager.maxIterations}). */
    public int resolvedMaxIterations() {
        return iterationCap == null || iterationCap.maxIterations() == null ? 6 : iterationCap.maxIterations();
    }

    /** D-17: output scanner defaults to enabled when block omitted. */
    public boolean outputScannerEnabled() {
        return outputScanner == null || !Boolean.FALSE.equals(outputScanner.enabled());
    }

    /**
     * Returns the scanner patterns, defaulting to the D-18 bundled set when operator config omits them.
     * <p><b>Consumer contract</b>: OutputScannerAdvisor MUST hard-cap input to 8 KiB (8192 chars) before
     * applying any {@link java.util.regex.Pattern#matcher}; defaults ship with bounded quantifiers but
     * operator-supplied custom regexes may not — the length cap is the last-line defence against ReDoS
     * (T-06-12 mitigation). The bundled ROLE_BREAK pattern uses a non-greedy, bounded inner quantifier
     * ({@code .{0,2048}?}) so catastrophic backtracking is impossible on any input length.
     */
    public List<OutputScanner.Pattern> resolvedPatterns() {
        if (outputScanner == null || outputScanner.patterns() == null || outputScanner.patterns().isEmpty()) {
            return List.of(
                    new OutputScanner.Pattern("IGNORE_PREVIOUS_INSTRUCTIONS", "(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
                    new OutputScanner.Pattern("SYSTEM_TAG_LEAK", "(?i)</?system>"),
                    new OutputScanner.Pattern("ROLE_BREAK", "(?i)(^|\\s)(assistant:|user:).{0,2048}?(assistant:|user:)")
            );
        }
        return outputScanner.patterns();
    }

    /**
     * Phase 9 D-08: host-prefix-leak pattern pack defaults to enabled when the nested block is
     * absent. Operator opts out via {@code jmix.ai-agent.guard.output-scanner.host-prefix-leak.enabled=false}
     * without disabling the parent {@code outputScanner} or affecting the bundled defaults.
     */
    public boolean hostPrefixLeakEnabled() {
        if (outputScanner == null || outputScanner.hostPrefixLeak() == null) {
            return true;
        }
        return !Boolean.FALSE.equals(outputScanner.hostPrefixLeak().enabled());
    }

    /**
     * Phase 9 D-08: tool-name-leak pattern pack defaults to enabled when the nested block is
     * absent. Operator opts out via {@code jmix.ai-agent.guard.output-scanner.tool-name-leak.enabled=false}.
     */
    public boolean toolNameLeakEnabled() {
        if (outputScanner == null || outputScanner.toolNameLeak() == null) {
            return true;
        }
        return !Boolean.FALSE.equals(outputScanner.toolNameLeak().enabled());
    }
}
