package com.vn.agent.guard;

/**
 * Thrown by the per-user chat rate limiter (GUARD-04, D-13) when the caller exceeds the
 * configured requests-per-minute ceiling.
 *
 * <p><b>Information-disclosure posture (T-06-01, D-10):</b> the {@link #getMessage() message}
 * passed to {@code super()} is a <em>stable key</em> — never the numeric ceiling. The ceiling
 * itself is stored on {@link #getRequestsPerMinuteCeiling()} for audit metadata only and MUST
 * NOT be surfaced to end users. The service mapper translates this exception into
 * {@code ChatResponseDto} with the i18n key {@code ai-agent.guard.rate-limit-exceeded}.</p>
 */
public class RateLimitExceededException extends RuntimeException {

    private final int requestsPerMinuteCeiling;

    public RateLimitExceededException(String message) {
        super(message);
        this.requestsPerMinuteCeiling = -1;
    }

    public RateLimitExceededException(String message, Throwable cause) {
        super(message, cause);
        this.requestsPerMinuteCeiling = -1;
    }

    /**
     * Audit-only constructor. The {@code super()} message is the stable key
     * {@code "rate-limit-exceeded"} — the numeric ceiling is captured on the private field
     * and exposed via {@link #getRequestsPerMinuteCeiling()} for the audit writer only.
     */
    public RateLimitExceededException(int requestsPerMinuteCeiling) {
        super("rate-limit-exceeded");
        this.requestsPerMinuteCeiling = requestsPerMinuteCeiling;
    }

    public int getRequestsPerMinuteCeiling() {
        return requestsPerMinuteCeiling;
    }
}
