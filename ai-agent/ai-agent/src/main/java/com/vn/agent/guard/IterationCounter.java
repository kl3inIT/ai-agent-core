package com.vn.agent.guard;

/**
 * Per-turn {@code ThreadLocal<Integer>} counter for tool-execution iterations (GUARD-03, D-16).
 *
 * <p>Owned by {@link GuardedToolCallingManager}: Plan 04's {@code DefaultChatServiceImpl} calls
 * {@link #start()} once at the top of a chat turn and {@link #reset()} in its {@code finally}
 * block; the decorator itself calls {@link #increment()} before each delegate dispatch and reads
 * via {@link #current()}. Lifecycle discipline mirrors
 * {@link com.vn.agent.orchestration.RunContext}.</p>
 *
 * <p>Not a Spring bean — a tiny static holder. Avoids passing an {@code AtomicInteger} through
 * every advisor context map; phase 6 is sync-only so a {@code ThreadLocal} suffices.</p>
 */
public final class IterationCounter {

    private static final ThreadLocal<Integer> COUNT = new ThreadLocal<>();

    private IterationCounter() {
    }

    /** Reset the counter to zero for the current thread. Must be called at the start of a turn. */
    public static void start() {
        COUNT.set(0);
    }

    /** Current iteration count for the thread (0 when {@link #start()} has not been called). */
    public static int current() {
        Integer v = COUNT.get();
        return v == null ? 0 : v;
    }

    /** Increment the per-thread counter and return the new value. */
    public static int increment() {
        int next = current() + 1;
        COUNT.set(next);
        return next;
    }

    /** Remove the thread-local entry. MUST be called in a {@code finally} block by the caller. */
    public static void reset() {
        COUNT.remove();
    }
}
