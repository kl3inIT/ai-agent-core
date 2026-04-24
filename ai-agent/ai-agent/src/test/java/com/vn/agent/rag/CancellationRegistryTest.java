package com.vn.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Plain JUnit 5 — no Spring context needed; the registry is pure in-memory state. */
class CancellationRegistryTest {

    @Test
    void cancel_is_idempotent() {
        CancellationRegistry registry = new CancellationRegistry();
        UUID id = UUID.randomUUID();

        assertThatCode(() -> {
            registry.cancel(id);
            registry.cancel(id);
        }).doesNotThrowAnyException();

        assertThat(registry.isCancelled(id)).isTrue();
    }

    @Test
    void isCancelled_returns_false_after_clear() {
        CancellationRegistry registry = new CancellationRegistry();
        UUID id = UUID.randomUUID();
        registry.cancel(id);
        assertThat(registry.isCancelled(id)).isTrue();

        registry.clear(id);

        assertThat(registry.isCancelled(id)).isFalse();
    }

    @Test
    void isCancelled_for_unknown_id_is_false() {
        CancellationRegistry registry = new CancellationRegistry();
        assertThat(registry.isCancelled(UUID.randomUUID())).isFalse();
    }

    @Test
    void null_ids_are_ignored_silently() {
        CancellationRegistry registry = new CancellationRegistry();
        assertThatCode(() -> {
            registry.register(null);
            registry.cancel(null);
            registry.clear(null);
        }).doesNotThrowAnyException();
        assertThat(registry.isCancelled(null)).isFalse();
    }

    @Test
    void bumpGeneration_marks_prior_generation_as_cancelled_but_not_new_generation() {
        // WR-01 race proof: reingest() bumps the generation. A worker that captured the
        // OLD generation sees isCancelled(id, old) == true, while the worker that captured
        // the NEW (post-bump) generation sees isCancelled(id, new) == false even after
        // clear() is called (clear only wipes the legacy boolean flag).
        CancellationRegistry registry = new CancellationRegistry();
        UUID id = UUID.randomUUID();

        long genBeforeBump = registry.currentGeneration(id);
        registry.bumpGeneration(id);
        registry.clear(id); // mirrors reingest(): clears legacy flag AFTER bump
        long genAfterBump = registry.currentGeneration(id);

        assertThat(genAfterBump).isGreaterThan(genBeforeBump);
        assertThat(registry.isCancelled(id, genBeforeBump))
                .as("old-generation worker MUST observe cancellation after bump+clear")
                .isTrue();
        assertThat(registry.isCancelled(id, genAfterBump))
                .as("new-generation worker MUST NOT observe cancellation after bump+clear")
                .isFalse();
    }

    @Test
    void concurrent_register_and_cancel_is_thread_safe() {
        CancellationRegistry registry = new CancellationRegistry();
        List<UUID> ids = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();

        CompletableFuture<?>[] futures = Stream.concat(
                ids.stream().map(id -> CompletableFuture.runAsync(() -> registry.cancel(id))),
                ids.stream().map(id -> CompletableFuture.runAsync(() -> registry.isCancelled(id)))
        ).toArray(CompletableFuture[]::new);

        assertThatCode(() -> CompletableFuture.allOf(futures).join()).doesNotThrowAnyException();
        for (UUID id : ids) {
            assertThat(registry.isCancelled(id)).isTrue();
        }
    }
}
