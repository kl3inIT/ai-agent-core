package com.vn.agent.rag;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;

import java.util.Map;

/**
 * Copies the submitting thread's {@link MDC} context onto the executor thread so correlation
 * identifiers (runId, username, etc.) survive the async handoff — addresses RESEARCH Pitfall #6
 * (MDC loss on async handoff) and threat T-05-03-04 (audit logs emitted from the worker thread
 * are still traceable to the submitting request).
 *
 * <p>Leaves the executor thread's previous MDC state restored after the task runs — prevents a
 * pooled thread from leaking context between tasks.
 */
public class MdcPropagatingTaskDecorator implements TaskDecorator {

    @Override
    @NonNull
    public Runnable decorate(@NonNull Runnable runnable) {
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (parentMdc != null) {
                    MDC.setContextMap(parentMdc);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
