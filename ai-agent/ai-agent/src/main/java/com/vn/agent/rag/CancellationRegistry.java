package com.vn.agent.rag;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generation-aware cancellation registry keyed by document UUID (D-20, WR-01 fix).
 *
 * <p><b>Race fixed:</b> the original single-boolean design let
 * {@code KnowledgeDocumentService.reingest()} call {@code cancel()} immediately followed
 * by {@code clear()} — if a prior {@link AsyncIngestionWorker} was still polling, its
 * next {@code isCancelled()} returned {@code false} and stale chunks could land in the
 * store after the purge. Cancellation is now expressed as a monotonic generation: when a
 * worker starts it captures the current generation (via {@link #currentGeneration}) and
 * each batch boundary checks {@link #isCancelled(UUID, long)}. Bumping the generation
 * (via {@link #cancel(UUID)} or {@link #bumpGeneration(UUID)}) marks every older worker
 * as cancelled without racing the new worker's startup.</p>
 *
 * <p>Single-JVM scope is acceptable for v1 — the add-on has no clustering story yet
 * (D-04). If horizontal scaling is introduced later, this registry becomes a Redis /
 * DB-polled generation counter without changing the worker's polling contract.</p>
 *
 * <p>Thread-safety: backed by {@link ConcurrentHashMap} with per-key {@link AtomicLong}
 * generation counters, so concurrent {@code cancel} / {@code isCancelled} / {@code clear}
 * calls from request threads and the ingest executor are safe.</p>
 */
@Component
public class CancellationRegistry {

    /** Per-document monotonic generation counter. */
    private final Map<UUID, AtomicLong> generations = new ConcurrentHashMap<>();
    /** Generation observed as cancelled — workers with this (or older) generation must abort. */
    private final Map<UUID, Long> cancelledAtOrBelow = new ConcurrentHashMap<>();
    /** Legacy single-flag support for callers that have not migrated to generations yet. */
    private final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();

    /**
     * Reserved for future metrics (e.g., counting in-flight ingestions). Currently a
     * no-op: the worker does not need to {@code register} in order for {@link #cancel}
     * to fire — cancellation is a passive flag, not an active registration.
     */
    public void register(UUID id) {
        // no-op placeholder; see javadoc
    }

    /**
     * Capture the current generation counter for {@code id} so a worker can later check
     * {@link #isCancelled(UUID, long)} against a stable point-in-time value. Creates the
     * counter lazily if this is the first observation.
     */
    public long currentGeneration(UUID id) {
        if (id == null) return 0L;
        return generations.computeIfAbsent(id, k -> new AtomicLong()).get();
    }

    /**
     * Bump the generation without setting the legacy boolean flag — used by {@code
     * reingest()} so prior-generation workers see cancellation while the newly scheduled
     * worker (captured post-bump) is unaffected.
     */
    public long bumpGeneration(UUID id) {
        if (id == null) return 0L;
        long next = generations.computeIfAbsent(id, k -> new AtomicLong()).incrementAndGet();
        cancelledAtOrBelow.put(id, next - 1);
        return next;
    }

    /**
     * Idempotent — calling twice on the same id does not throw. Cancels any worker
     * whose captured generation is less than or equal to the generation at call time
     * AND sets the legacy boolean flag for backward-compatible callers.
     */
    public void cancel(UUID id) {
        if (id == null) return;
        long gen = generations.computeIfAbsent(id, k -> new AtomicLong()).get();
        cancelledAtOrBelow.merge(id, gen, Math::max);
        cancelled.add(id);
    }

    /** Legacy single-flag check — kept for callers that have not captured a generation. */
    public boolean isCancelled(UUID id) {
        return id != null && cancelled.contains(id);
    }

    /**
     * Generation-aware cancellation check. {@code true} if any {@link #cancel} /
     * {@link #bumpGeneration} call has marked a generation greater-than-or-equal-to
     * the worker's captured one.
     */
    public boolean isCancelled(UUID id, long capturedGeneration) {
        if (id == null) return false;
        Long marker = cancelledAtOrBelow.get(id);
        if (marker != null && capturedGeneration <= marker) {
            return true;
        }
        return cancelled.contains(id);
    }

    /** Worker calls this on terminal state (READY / FAILED / CANCELLED) to avoid leaks. */
    public void clear(UUID id) {
        if (id != null) {
            cancelled.remove(id);
        }
    }
}
