package com.vn.agent.view.chat.fragment;

import com.vn.agent.orchestration.StreamingEvent;

import java.util.Map;

/**
 * Pure-function renderer mapping {@link StreamingEvent} variants to markdown
 * fragments consumed by the Vaadin {@code MessageList} component (Plan 03).
 *
 * <p>This class intentionally has <strong>zero Vaadin and zero Spring
 * dependencies</strong> so that the markdown-format contract can be locked
 * under a plain JUnit 5 unit test (Phase 7.1 decision D-08).
 *
 * <p>Key design anchors (Phase 7.1):
 * <ul>
 *   <li><b>D-09 i18n pull-through</b> — all human-visible labels are supplied
 *       via the {@code labels} map. Callers resolve keys against
 *       {@code MessageSource} and hand the rendered values in; the renderer
 *       never touches Spring.</li>
 *   <li><b>A-03 deep-link param</b> — citation links use the query parameter
 *       {@code documentId=} (not {@code docId=}) to align with the
 *       {@code KnowledgeBaseView} route contract.</li>
 *   <li><b>Sources header once per turn</b> — the first {@link StreamingEvent.Citation}
 *       of a streaming turn emits the "Sources" header; subsequent citations
 *       append only a bullet. One {@link CitationState} instance per turn
 *       (Plan 03 allocates a fresh holder on each submit).</li>
 *   <li><b>A5 null-guard</b> — a citation with {@code documentId == null}
 *       emits an unlinked bullet (no {@code NullPointerException}).</li>
 * </ul>
 */
public final class StreamEventRenderer {

    private StreamEventRenderer() {
        // pure-static utility
    }

    /**
     * Per-turn mutable holder that tracks whether the current streaming turn
     * has already emitted the "Sources" citation header. Callers MUST allocate
     * a fresh instance for each turn.
     */
    public static final class CitationState {
        private boolean first = true;

        /**
         * Returns {@code true} on the first call within a turn and
         * {@code false} on every subsequent call.
         */
        public boolean consumeFirst() {
            if (first) {
                first = false;
                return true;
            }
            return false;
        }
    }

    /**
     * Render a single {@link StreamingEvent} to its markdown fragment.
     *
     * @param event          the streaming event (never {@code null})
     * @param labels         pre-resolved i18n labels; values are used verbatim
     *                       and missing keys fall back to sensible English
     *                       defaults so the renderer stays independent of the
     *                       Fragment wiring
     * @param citationState  per-turn holder tracking first-citation semantics
     * @return markdown string (empty when the event contributes no text,
     *         e.g. {@link StreamingEvent.Final} in v1)
     */
    public static String renderStreamEvent(StreamingEvent event,
                                           Map<String, String> labels,
                                           CitationState citationState) {
        return switch (event) {
            case StreamingEvent.Content c ->
                    c.markdownChunk() == null ? "" : c.markdownChunk();
            case StreamingEvent.ToolCall tc ->
                    "\n\n**%s**: %s".formatted(tc.toolName(), shortArgs(tc.argsJson()));
            case StreamingEvent.ToolResult tr -> {
                String outcomeLabel = labels.getOrDefault(
                        "chatView.stream.outcome." + tr.outcome().name(),
                        tr.outcome().name());
                String summary = tr.summary() == null ? "" : tr.summary();
                yield "  \n_%s — %s_\n\n---\n".formatted(outcomeLabel, summary);
            }
            case StreamingEvent.Citation c -> {
                String prefix = citationState.consumeFirst()
                        ? "\n\n---\n**%s**".formatted(
                                labels.getOrDefault("chatView.stream.sources", "Sources"))
                        : "";
                if (c.documentId() == null) {
                    // A5 null-guard: emit an unlinked bullet, no NPE.
                    yield prefix + "\n- source";
                }
                yield prefix + "\n- [%s](/ai-agent/knowledge?documentId=%s)"
                        .formatted(c.documentId().toString(), c.documentId());
            }
            case StreamingEvent.Error err ->
                    "\n\n---\n**%s:** %s".formatted(
                            labels.getOrDefault("chatView.stream.error", "error"),
                            err.messageKey());
            case StreamingEvent.Final f ->
                    // v1 — skip closing summary per RESEARCH Open Question 2.
                    "";
        };
    }

    /**
     * Collapse whitespace in an arguments JSON payload and truncate to
     * 80 characters (77 + "...") so tool-call headers stay compact in the
     * streaming bubble.
     */
    static String shortArgs(String argsJson) {
        if (argsJson == null) {
            return "";
        }
        String collapsed = argsJson.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= 80 ? collapsed : collapsed.substring(0, 77) + "...";
    }
}
