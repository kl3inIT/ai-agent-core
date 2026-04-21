package com.vn.agent.guard;

/**
 * Thrown by the per-conversation token circuit breaker (GUARD-03, D-14) when the rolling
 * token total for a conversation exceeds the configured ceiling.
 *
 * <p><b>Information-disclosure posture (T-06-01, D-10):</b> the {@link #getMessage() message}
 * passed to {@code super()} is a <em>stable key</em> — never the numeric ceiling. The ceiling
 * itself is stored on {@link #getCeiling()} for audit metadata only and MUST NOT be surfaced
 * to end users. The service mapper translates this exception into {@code ChatResponseDto}
 * with the i18n key {@code ai-agent.guard.token-budget-exhausted}.</p>
 */
public class TokenBudgetExhaustedException extends RuntimeException {

    private final long ceiling;

    public TokenBudgetExhaustedException(String message) {
        super(message);
        this.ceiling = -1L;
    }

    public TokenBudgetExhaustedException(String message, Throwable cause) {
        super(message, cause);
        this.ceiling = -1L;
    }

    /**
     * Audit-only constructor. The {@code super()} message is the stable key
     * {@code "token-budget-exhausted"} — the numeric ceiling is captured on the private field
     * and exposed via {@link #getCeiling()} for the audit writer only.
     */
    public TokenBudgetExhaustedException(long ceiling) {
        super("token-budget-exhausted");
        this.ceiling = ceiling;
    }

    public long getCeiling() {
        return ceiling;
    }
}
