package com.vn.agent.guard;

/**
 * Thrown by the tool-calling iteration cap (GUARD-02, D-16) when
 * {@code ToolCallingManager.maxIterations} is exhausted without a terminal assistant message.
 *
 * <p><b>Information-disclosure posture (T-06-01, D-10):</b> the {@link #getMessage() message}
 * passed to {@code super()} is a <em>stable key</em> — never the numeric cap. The cap itself
 * is stored on {@link #getMaxIterations()} for audit metadata only and MUST NOT be surfaced
 * to end users. The service mapper translates this exception into {@code ChatResponseDto}
 * with the i18n key {@code ai-agent.guard.iteration-cap-exceeded}.</p>
 */
public class IterationCapExceededException extends RuntimeException {

    private final int maxIterations;

    public IterationCapExceededException(String message) {
        super(message);
        this.maxIterations = -1;
    }

    public IterationCapExceededException(String message, Throwable cause) {
        super(message, cause);
        this.maxIterations = -1;
    }

    /**
     * Audit-only constructor. The {@code super()} message is the stable key
     * {@code "iteration-cap-exceeded"} — the numeric cap is captured on the private field
     * and exposed via {@link #getMaxIterations()} for the audit writer only.
     */
    public IterationCapExceededException(int maxIterations) {
        super("iteration-cap-exceeded");
        this.maxIterations = maxIterations;
    }

    public int getMaxIterations() {
        return maxIterations;
    }
}
