package com.vn.agent.view.chat.fragment;

import com.vn.agent.orchestration.StreamingEvent;

/**
 * Maps the closed {@link StreamingEvent.ActivityKind} enum to the {@code msg://} key
 * for the ephemeral streaming-status line (OBS-01) — the only observability surface
 * that survived the turn-trace removal. Intentionally Vaadin-free and Spring-free so
 * the mapping contract is locked under a plain JUnit test.
 *
 * <p><strong>Structural no-leak guarantee.</strong> The input alphabet is the closed
 * {@link StreamingEvent.ActivityKind} enum; every label this class emits is a
 * {@code msg://} key, never free text.
 */
public final class TurnDetailRenderer {

    /** {@code msg://} key for the neutral typing indicator shown before any Activity/Content. */
    public static final String STATUS_NEUTRAL_KEY = "chatView.status.neutral";
    /** {@code msg://} key for the CHAT status ("thinking…"). */
    public static final String STATUS_CHAT_KEY = "chatView.status.chat";
    /** {@code msg://} key for the TOOL status ("searching data…"). */
    public static final String STATUS_TOOL_KEY = "chatView.status.tool";
    /** {@code msg://} key for the RETRIEVAL status ("retrieving documents…"). */
    public static final String STATUS_RETRIEVAL_KEY = "chatView.status.retrieval";

    private TurnDetailRenderer() {
        // pure-static utility
    }

    /**
     * Maps the closed {@link StreamingEvent.ActivityKind} to the {@code msg://}
     * key for the ephemeral streaming-status line.
     */
    public static String statusKeyFor(StreamingEvent.ActivityKind kind) {
        if (kind == null) {
            return STATUS_NEUTRAL_KEY;
        }
        return switch (kind) {
            case CHAT -> STATUS_CHAT_KEY;
            case TOOL -> STATUS_TOOL_KEY;
            case RETRIEVAL -> STATUS_RETRIEVAL_KEY;
        };
    }

    /** {@code msg://} key for the neutral typing indicator shown before any Activity/Content. */
    public static String neutralStatusKey() {
        return STATUS_NEUTRAL_KEY;
    }
}
