package com.vn.agent.view.chat.fragment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.orchestration.StreamingEvent;

import java.util.Map;
import java.util.UUID;

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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PREPARE_FORM_DRAFT_TOOL = "prepare_form_draft";
    private static final String OPEN_FORM_WITH_DRAFT_ACTION = "open_form_with_draft";

    private StreamEventRenderer() {
        // pure-static utility
    }

    public record DraftPayload(UUID draftId, String entityName, String instanceName) {
    }

    public record RenderedStreamEvent(String markdown,
                                      DraftPayload draftPayload,
                                      boolean draftPayloadInvalid) {
        static RenderedStreamEvent markdown(String markdown) {
            return new RenderedStreamEvent(markdown == null ? "" : markdown, null, false);
        }

        static RenderedStreamEvent draftPayload(DraftPayload draftPayload) {
            return new RenderedStreamEvent("", draftPayload, false);
        }

        static RenderedStreamEvent invalidDraftPayload() {
            return new RenderedStreamEvent("", null, true);
        }
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
        return renderStreamEventDetails(event, labels, citationState).markdown();
    }

    public static RenderedStreamEvent renderStreamEventDetails(StreamingEvent event,
                                                               Map<String, String> labels,
                                                               CitationState citationState) {
        return switch (event) {
            case StreamingEvent.Content c ->
                    RenderedStreamEvent.markdown(c.markdownChunk());
            case StreamingEvent.ToolCall ignoredToolCall ->
                    RenderedStreamEvent.markdown("");
            case StreamingEvent.ToolResult toolResult ->
                    renderToolResult(toolResult);
            case StreamingEvent.Citation c -> {
                String prefix = citationState.consumeFirst()
                        ? "\n\n---\n**%s**".formatted(
                                labels.getOrDefault("chatView.stream.sources", "Sources"))
                        : "";
                if (c.documentId() == null) {
                    // A5 null-guard: emit an unlinked bullet, no NPE.
                    yield RenderedStreamEvent.markdown(prefix + "\n- source");
                }
                yield RenderedStreamEvent.markdown(prefix + "\n- [%s](/ai-agent/knowledge?documentId=%s)"
                        .formatted(c.documentId().toString(), c.documentId()));
            }
            case StreamingEvent.Error err -> {
                String errorLabel = labels.getOrDefault("chatView.stream.error", "error");
                String errorText = labels.getOrDefault(err.messageKey(), err.messageKey());
                yield RenderedStreamEvent.markdown("\n\n---\n**%s:** %s".formatted(errorLabel, errorText));
            }
            case StreamingEvent.Final ignoredFinal ->
                    // v1 — skip closing summary per RESEARCH Open Question 2.
                    RenderedStreamEvent.markdown("");
        };
    }

    private static RenderedStreamEvent renderToolResult(StreamingEvent.ToolResult toolResult) {
        if (!PREPARE_FORM_DRAFT_TOOL.equals(toolResult.toolName())) {
            return RenderedStreamEvent.markdown("");
        }
        DraftPayload draftPayload = parseOpenFormWithDraftPayload(toolResult.payloadJson());
        return draftPayload == null
                ? RenderedStreamEvent.invalidDraftPayload()
                : RenderedStreamEvent.draftPayload(draftPayload);
    }

    private static DraftPayload parseOpenFormWithDraftPayload(String payloadJson) {
        if (isBlank(payloadJson)) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payloadJson);
            if (!OPEN_FORM_WITH_DRAFT_ACTION.equals(root.path("action").asText(null))) {
                return null;
            }
            String draftIdText = root.path("draftId").asText(null);
            String entityName = root.path("entityName").asText(null);
            String instanceName = root.path("instanceName").asText(null);
            if (isBlank(draftIdText) || isBlank(entityName) || isBlank(instanceName)) {
                return null;
            }
            return new DraftPayload(UUID.fromString(draftIdText), entityName, instanceName);
        } catch (Exception failure) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
