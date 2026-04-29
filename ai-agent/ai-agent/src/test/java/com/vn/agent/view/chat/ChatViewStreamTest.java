package com.vn.agent.view.chat;

import com.vn.agent.ChatService;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.view.chat.fragment.StreamEventRenderer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI-01 — stream semantics test (Phase 7.1 rewrite).
 *
 * <p>Pragmatic-unit per Plan 07-07b Rule-1 tolerance: no {@code @UiTest} in the addon
 * module because the Jmix UI-test harness resolves only in the {@code jmix-app} root
 * module. Instead, this test exercises the two contracts the Fragment's streaming
 * pipeline depends on:
 * <ol>
 *   <li>{@link ChatService#stream} emits {@link StreamingEvent}s in arrival order;</li>
 *   <li>{@link StreamEventRenderer#renderStreamEvent} maps each event to a markdown
 *       fragment, and concatenating the fragments in order produces the expected
 *       assistant message (the Fragment does exactly this via
 *       {@code botMsg.appendText(md)} per event).</li>
 * </ol>
 * Full Vaadin render-path coverage is deferred to the {@code jmix-app} module's
 * future {@code @UiTest} harness (Phase 8 infra).
 */
class ChatViewStreamTest {

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
    void stream_emitsEventsInOrder_renderedMarkdownConcatenates() {
        ChatService chatService = Mockito.mock(ChatService.class);
        UUID runId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Mockito.when(chatService.stream(
                        Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Flux.just(
                        new StreamingEvent.Content("Hello "),
                        new StreamingEvent.Content("world"),
                        new StreamingEvent.ToolCall(UUID.randomUUID(), "find_records", "{}"),
                        new StreamingEvent.ToolResult(UUID.randomUUID(), "3 rows", AiToolCallOutcome.SUCCESS),
                        new StreamingEvent.Final(runId, conversationId, 150L, 10, 20)
                ));

        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        StringBuilder acc = new StringBuilder();
        chatService.stream("user-1", null, "ping", null)
                .map(e -> StreamEventRenderer.renderStreamEvent(e, labels(), state))
                .toStream()
                .forEach(acc::append);

        String out = acc.toString();
        assertThat(out).startsWith("Hello world");
        assertThat(out).doesNotContain("find_records");
        assertThat(out).doesNotContain("done");
        assertThat(out).doesNotContain("3 rows");
    }

    @Test
    void stream_citations_firstEmitsSourcesHeader_subsequentOnlyBullet() {
        ChatService chatService = Mockito.mock(ChatService.class);
        UUID docA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID docB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Mockito.when(chatService.stream(
                        Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Flux.just(
                        new StreamingEvent.Content("answer"),
                        new StreamingEvent.Citation(0, docA, "snippet A"),
                        new StreamingEvent.Citation(1, docB, "snippet B")
                ));

        StreamEventRenderer.CitationState state = new StreamEventRenderer.CitationState();
        List<String> fragments = new ArrayList<>();
        chatService.stream("user-1", null, "ping", null)
                .map(e -> StreamEventRenderer.renderStreamEvent(e, labels(), state))
                .toStream()
                .forEach(fragments::add);

        // fragments[0] = "answer"
        // fragments[1] = first citation (header + bullet, deep-link to docA)
        // fragments[2] = second citation (only bullet, deep-link to docB, no header)
        assertThat(fragments).hasSize(3);
        assertThat(fragments.get(0)).isEqualTo("answer");
        assertThat(fragments.get(1))
                .contains("**Sources**")
                .contains("/ai-agent/knowledge?documentId=" + docA);
        assertThat(fragments.get(2))
                .doesNotContain("**Sources**")
                .contains("/ai-agent/knowledge?documentId=" + docB);
    }
}
