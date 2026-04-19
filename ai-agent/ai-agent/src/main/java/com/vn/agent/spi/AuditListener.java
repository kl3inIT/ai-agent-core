package com.vn.agent.spi;

/**
 * Host extension point fired after every {@code AiToolCallAudit} write for side-channel
 * observability (Slack, SIEM, metrics). Listeners run fire-and-forget in Phase 4 wiring;
 * exceptions thrown here MUST NOT fail the primary request.
 *
 * <p><b>Contract:</b> implementations must be non-blocking where possible and must swallow
 * their own failures (log + move on). The add-on wraps every invocation in a try/catch so a
 * broken listener cannot corrupt the audit write or the user-visible tool-call result.</p>
 */
public interface AuditListener {
    /** @param auditId the just-persisted {@code AiToolCallAudit.id} */
    void onToolCallAudited(java.util.UUID auditId);
}
