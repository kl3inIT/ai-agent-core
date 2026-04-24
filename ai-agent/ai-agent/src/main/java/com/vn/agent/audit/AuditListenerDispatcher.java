package com.vn.agent.audit;

import com.vn.agent.spi.AuditListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Fires {@link AuditListener} beans one at a time with a per-listener {@code try/catch} so one
 * throwing listener cannot block other listeners or fail the chat thread.
 *
 * <p>Contract from SPI-06 (see {@link AuditListener}): listeners are fire-and-forget; any
 * {@link Throwable} they emit is logged at WARN and suppressed.
 *
 * <p>Per D-13: Spring auto-collects {@link AuditListener} beans into the constructor-injected
 * {@code List} (empty list when none registered — no NPE risk).
 */
@Component
public class AuditListenerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AuditListenerDispatcher.class);

    private final List<AuditListener> listeners;

    public AuditListenerDispatcher(List<AuditListener> listeners) {
        this.listeners = listeners;
    }

    /**
     * Notify every registered {@link AuditListener} that a tool-call audit row has been written.
     * Any {@link Throwable} from a listener is caught and logged — it never propagates.
     */
    public void dispatchToolCallAudited(UUID auditId) {
        for (AuditListener listener : listeners) {
            try {
                listener.onToolCallAudited(auditId);
            } catch (Throwable t) {
                log.warn("AuditListener {} threw on auditId={}; suppressed",
                        listener.getClass().getName(), auditId, t);
            }
        }
    }
}
