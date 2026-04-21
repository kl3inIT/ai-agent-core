package com.vn.agent.guard;

/**
 * Thrown by {@code ChatService.askTyped} (GUARD-06, D-19/D-20) when the structured-output
 * parser fails to produce a valid instance of the requested target type after the bounded
 * retry budget is exhausted.
 *
 * <p><b>Payload contract:</b> carries the last raw assistant text plus the target class so
 * callers can log or present a diagnostic internally. The {@link #getMessage() message} is
 * a stable composite key ({@code "structured-output-failed:&lt;SimpleName&gt;"}) — the service
 * mapper translates this into {@code ChatResponseDto} with the i18n key
 * {@code ai-agent.guard.structured-output-failed}. The raw text MUST NOT be surfaced to end
 * users verbatim; it may contain prompt-injection echoes or partially-valid JSON fragments.</p>
 */
public class StructuredOutputException extends RuntimeException {

    private final String lastRaw;
    private final Class<?> targetType;

    public StructuredOutputException(String lastRaw, Class<?> targetType) {
        super("structured-output-failed:" + (targetType == null ? "null" : targetType.getSimpleName()));
        this.lastRaw = lastRaw;
        this.targetType = targetType;
    }

    public String getLastRaw() {
        return lastRaw;
    }

    public Class<?> getTargetType() {
        return targetType;
    }
}
