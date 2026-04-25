package com.vn.agent.spi;

import java.util.UUID;

/**
 * Host extension point fired after every {@link com.vn.agent.entity.AiAuditEvent}
 * write for side-channel observability (Slack, SIEM, metrics). Listeners run
 * fire-and-forget; exceptions thrown here MUST NOT fail the primary request —
 * the add-on wraps every invocation in a try/catch so a broken listener cannot
 * corrupt the audit write or the user-visible result.
 *
 * <p><b>Contract:</b> implementations must be non-blocking where possible and must
 * swallow their own failures (log + move on). Listeners MUST be kind-tolerant —
 * additional kinds beyond the {@link AuditKind} constants may be introduced by
 * hosts without SPI changes.</p>
 */
public interface AuditListener {
    /**
     * Invoked after any audited event (CHAT / TOOL / RETRIEVAL / future kinds)
     * becomes durable (post-commit of its REQUIRES_NEW tx).
     *
     * @param auditId durable {@link com.vn.agent.entity.AiAuditEvent#getId()}
     * @param kind    one of {@link AuditKind#CHAT}, {@link AuditKind#TOOL},
     *                {@link AuditKind#RETRIEVAL}, or a host-defined value
     */
    void onEventAudited(UUID auditId, String kind);
}
