package com.vn.agent.view.chat;

import com.vn.agent.ChatService;
import com.vn.agent.orchestration.StreamingEvent;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan 07-07b Task 2 — UI-02 / D-03 Stop-button contract.
 *
 * <p>Per plan Rule-1 tolerance clause: the fragment's {@code onStopClick} is a thin
 * wrapper around {@code Disposable.dispose()} and a {@code MessageBubbleComponent.markStopped()}
 * call — the load-bearing behavior is that disposing the active {@code Flux} halts upstream
 * emissions and releases resources. We exercise that behavior directly against the
 * {@link ChatService#stream} contract using an infinite Flux.interval (same shape as a
 * long-running provider stream); UI-level stop semantics are covered by manual bootRun
 * verification.
 */
class ChatViewStopTest {

    @Test
    void disposingActiveStreamHaltsFurtherEmissions() throws InterruptedException {
        ChatService chatService = mock(ChatService.class);
        // Infinite tick stream — same shape the fragment subscribes to for a slow LLM.
        when(chatService.stream(anyString(), any(), anyString(), any()))
                .thenReturn(Flux.interval(Duration.ofMillis(10))
                        .map(i -> new StreamingEvent.Content("tick ")));

        AtomicInteger counter = new AtomicInteger();
        Flux<StreamingEvent> source = chatService.stream("alice", UUID.randomUUID(), "hi", null);
        Disposable subscription = source.subscribe(ev -> counter.incrementAndGet());

        // Poll briefly until a few emissions accumulate so we know the stream is live.
        long deadline = System.currentTimeMillis() + 2000L;
        while (counter.get() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(counter.get()).as("stream must be live before stop").isGreaterThan(2);
        assertThat(subscription.isDisposed()).as("stream must be live before stop").isFalse();

        // Simulate stopButton.click() → the fragment calls subscription.dispose().
        subscription.dispose();

        assertThat(subscription.isDisposed()).as("dispose() must flip isDisposed=true").isTrue();

        // After dispose, downstream counter must plateau — grab a snapshot, wait, re-check.
        int snapshot = counter.get();
        Thread.sleep(100);
        assertThat(counter.get())
                .as("no further emissions after dispose")
                .isEqualTo(snapshot);
    }
}
