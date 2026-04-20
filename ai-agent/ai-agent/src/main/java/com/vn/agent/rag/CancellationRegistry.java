package com.vn.agent.rag;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cancellation flag keyed by document UUID (D-20). Used by
 * {@code KnowledgeDocumentService.delete} (Plan 05-04) to signal an in-flight
 * {@link AsyncIngestionWorker} that it should abort at the next chunk-batch boundary.
 *
 * <p>Single-JVM scope is acceptable for v1 — the add-on has no clustering story yet
 * (D-04). If horizontal scaling is introduced later, this registry becomes a Redis /
 * DB-polled flag without changing the worker's polling contract.
 *
 * <p>Thread-safety: backed by {@link ConcurrentHashMap#newKeySet()}, so concurrent
 * {@code register} / {@code cancel} / {@code isCancelled} / {@code clear} from
 * request threads and the ingest executor are safe.
 */
@Component
public class CancellationRegistry {

    private final Set<UUID> cancelled = ConcurrentHashMap.newKeySet();

    /**
     * Reserved for future metrics (e.g., counting in-flight ingestions). Currently a
     * no-op: the worker does not need to {@code register} in order for {@link #cancel}
     * to fire — cancellation is a passive flag, not an active registration.
     */
    public void register(UUID id) {
        // no-op placeholder; see javadoc
    }

    /** Idempotent — calling twice on the same id does not throw. */
    public void cancel(UUID id) {
        if (id != null) {
            cancelled.add(id);
        }
    }

    public boolean isCancelled(UUID id) {
        return id != null && cancelled.contains(id);
    }

    /** Worker calls this on terminal state (READY / FAILED / CANCELLED) to avoid leaks. */
    public void clear(UUID id) {
        if (id != null) {
            cancelled.remove(id);
        }
    }
}
