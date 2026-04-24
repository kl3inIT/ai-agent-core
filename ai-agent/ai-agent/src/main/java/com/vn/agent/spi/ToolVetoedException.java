package com.vn.agent.spi;

/**
 * Thrown by {@link ToolGuard#check(String, java.util.Map)} to veto a tool invocation.
 * Unchecked so tool bodies are not forced to declare {@code throws}.
 *
 * <p><b>Contract:</b> the message is persisted verbatim as
 * {@code AiAuditEvent.denialReason} and surfaced to operators in the audit UI. Avoid
 * leaking sensitive data in the message — treat it as user-visible.</p>
 */
public class ToolVetoedException extends RuntimeException {
    public ToolVetoedException(String message) { super(message); }
    public ToolVetoedException(String message, Throwable cause) { super(message, cause); }
}
