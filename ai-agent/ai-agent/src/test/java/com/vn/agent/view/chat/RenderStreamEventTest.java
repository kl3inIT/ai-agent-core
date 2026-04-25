package com.vn.agent.view.chat;

import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.view.chat.fragment.StreamEventRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 07.1-02 — locks the markdown-format contract for
 * {@link StreamEventRenderer#renderStreamEvent} before Plan 03 consumes it.
 *
 * <p>Pure JUnit 5 + AssertJ. No Vaadin, no Spring. Each test instantiates a
 * fresh {@link StreamEventRenderer.CitationState} so first-of-turn semantics
 * are deterministic.
 */
class RenderStreamEventTest {

    private static final UUID DOC_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC_ID_2 =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TOOL_CALL_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID RUN_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    private Map<String, String> labels() {
        return Map.of(
                "chatView.stream.sources", "Sources",
                "chatView.stream.outcome.SUCCESS", "done",
                "chatView.stream.outcome.BLOCKED", "blocked",
                "chatView.stream.outcome.ERROR", "error",
                "chatView.stream.outcome.FLAGGED", "flagged",
                "chatView.stream.error", "error");
    }

    @Test
    void content_plainText_returnsVerbatim() {
        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        String out = StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.Content("hello"), labels(), state);
        assertThat(out).isEqualTo("hello");
    }

    @Test
    void content_null_returnsEmptyString() {
        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        String out = StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.Content(null), labels(), state);
        assertThat(out).isEqualTo("");
    }

    @Test
    void toolCall_rendersMarkdownHeader_withToolNameAndShortArgs() {
        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        String out = StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.ToolCall(TOOL_CALL_ID, "find_records", "{\"entity\":\"Order\"}"),
                labels(), state);
        assertThat(out)
                .contains("**find_records**")
                .contains("{\"entity\":\"Order\"}");
        // Short args: ≤80 chars after whitespace collapse. Verify no ellipsis
        // for this small payload.
        assertThat(out).doesNotContain("...");
    }

    @Test
    void toolResult_rendersItalicBlock_withOutcomeLabel_andTrailingSeparator() {
        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        String out = StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.ToolResult(TOOL_CALL_ID, "3 rows", AiToolCallOutcome.SUCCESS),
                labels(), state);
        assertThat(out)
                .contains("_")          // italicised block
                .contains("done")       // outcome label looked up from labels
                .contains("3 rows")
                .contains("---");       // trailing separator
    }

    @Test
    void citation_firstOfTurn_emitsSourcesHeader_andDeepLink() {
        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        String out = StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.Citation(0, DOC_ID, "snippet discarded"),
                labels(), state);
        assertThat(out)
                .contains("**Sources**")
                .contains("/ai-agent/knowledge?documentId=11111111-1111-1111-1111-111111111111");
    }

    @Test
    void citation_subsequentOfTurn_emitsOnlyBulletLink_noHeader() {
        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        // First citation consumes the "first-of-turn" flag.
        StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.Citation(0, DOC_ID, "first"), labels(), state);
        // Second citation should NOT re-emit the header.
        String out = StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.Citation(1, DOC_ID_2, "second"), labels(), state);
        assertThat(out)
                .doesNotContain("**Sources**")
                .contains("/ai-agent/knowledge?documentId=22222222-2222-2222-2222-222222222222");
    }

    @Test
    void citation_nullDocumentId_emitsUnlinkedBullet_noNpe() {
        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        String out = StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.Citation(0, null, "snippet"), labels(), state);
        assertThat(out)
                .doesNotContain("documentId=")
                .contains("-"); // a plain bullet is still emitted
    }

    @Test
    void error_rendersErrorMarkerAndMessageKey() {
        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        String out = StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.Error("chatView.error.rateLimited", Map.of()),
                labels(), state);
        assertThat(out).contains("chatView.error.rateLimited");
    }

    @Test
    void final_emitsEmptyString_v1() {
        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        String out = StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.Final(RUN_ID, UUID.randomUUID(), 1234L, 50, 200), labels(), state);
        assertThat(out).isEqualTo("");
    }

    @Test
    void content_htmlPayload_passesThroughVerbatim_serverSide() {
        String payload = "<script>alert(1)</script>";
        String out = StreamEventRenderer.renderStreamEvent(
                new StreamingEvent.Content(payload), labels(),
                new StreamEventRenderer.CitationState());
        assertThat(out).isEqualTo(payload);
    }
}
