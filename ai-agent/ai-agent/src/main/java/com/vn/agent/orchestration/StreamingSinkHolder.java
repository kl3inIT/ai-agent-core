package com.vn.agent.orchestration;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Request-scoped-by-runId sink registry for in-flight streaming runs (RESEARCH Open Q#2
 * RESOLVED). Keyed by {@link UUID} runId; entries are installed by
 * {@code DefaultChatServiceImpl.stream(...)} before the upstream subscription starts, looked
 * up by {@link com.vn.agent.audit.ToolCallbackAuditDecorator} via {@link RunContext} to emit
 * {@link StreamingEvent.ToolCall} / {@link StreamingEvent.ToolResult} events into the same
 * Flux the ChatView is subscribed to, and removed in {@code doFinally}.
 *
 * <p><b>Thread-safety.</b> Backed by a {@link ConcurrentHashMap} sized for simultaneous
 * streaming runs (typically one per browser tab). Non-streaming calls (blocking
 * {@code ChatService.ask} path, tools invoked outside a chat run entirely) see
 * {@link Optional#empty()} from {@link #current()} and skip emission — zero runtime overhead
 * when the feature is disabled (D-04 graceful fallback).</p>
 */
@Component
public class StreamingSinkHolder {

    private final ConcurrentMap<UUID, Sinks.Many<StreamingEvent>> sinksByRunId = new ConcurrentHashMap<>();

    /**
     * Install a sink for the given runId. Called by {@code DefaultChatServiceImpl.stream(...)}
     * before subscription so tool events emitted during early pre-flight still land in the Flux.
     */
    public void register(UUID runId, Sinks.Many<StreamingEvent> sink) {
        if (runId == null || sink == null) {
            return;
        }
        sinksByRunId.put(runId, sink);
    }

    /** Remove a sink when the streaming run terminates (from {@code doFinally}). */
    public void unregister(UUID runId) {
        if (runId == null) {
            return;
        }
        sinksByRunId.remove(runId);
    }

    /**
     * Lookup the sink for the current thread's runId (via {@link RunContext#get()}). Returns
     * {@link Optional#empty()} when no streaming run is active — blocking {@code ask(...)}
     * path or tools invoked outside {@code ChatService} entirely. Callers use
     * {@code .ifPresent(sink -> sink.tryEmitNext(...))} to make emission opt-in.
     */
    public Optional<Sinks.Many<StreamingEvent>> current() {
        UUID runId = RunContext.get();
        if (runId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sinksByRunId.get(runId));
    }

    /** Direct lookup by runId — used by tests and diagnostics. */
    public Optional<Sinks.Many<StreamingEvent>> forRun(UUID runId) {
        if (runId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sinksByRunId.get(runId));
    }

    /**
     * Prefer the explicit run id supplied by Spring AI {@code ToolContext}, then fall back to
     * the current thread's {@link RunContext}. Tool callbacks may execute with ThreadLocal state
     * restored differently from the outer stream subscriber, but the stream sink is still keyed
     * by the stable run id.
     */
    public Optional<Sinks.Many<StreamingEvent>> currentOrForRun(UUID runId) {
        Optional<Sinks.Many<StreamingEvent>> sink = forRun(runId);
        return sink.isPresent() ? sink : current();
    }
}
