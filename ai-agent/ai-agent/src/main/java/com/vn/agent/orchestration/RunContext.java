package com.vn.agent.orchestration;

import java.util.UUID;

/**
 * Per-thread runId carrier (D-12). Set by {@code AuditAdvisor} at the start of each chat call,
 * read by {@code ToolCallbackAuditDecorator} so tool-call audit rows can be correlated with the
 * chat-level pre/post rows. Always cleared in a {@code finally} by {@code AuditAdvisor}.
 *
 * <p>Not a Spring bean — a tiny utility holder. Avoids passing a UUID through every advisor
 * context map and avoids the noise of an {@code InheritableThreadLocal} (Phase 4 is sync-only
 * per D-16).</p>
 */
public final class RunContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private RunContext() { }

    /** Set the runId for the current thread. Replaces any existing value. */
    public static void set(UUID runId) { CURRENT.set(runId); }

    /** Returns the runId or {@code null} if no chat call is in progress on this thread. */
    public static UUID get() { return CURRENT.get(); }

    /** Remove the thread-local entry — MUST be called in a {@code finally} block. */
    public static void clear() { CURRENT.remove(); }
}
