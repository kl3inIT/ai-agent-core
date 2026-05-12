package com.vn.agent.orchestration;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.audit.ToolCallbackAuditDecorator;
import com.vn.agent.rag.advisor.AuditingDocumentRetriever;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.core.userdetails.User;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 15 Plan 02 — proves the orchestration-edge emit sites push
 * {@link StreamingEvent.Activity} onto the active streaming sink, best-effort:
 * <ul>
 *   <li>{@link ToolCallbackAuditDecorator} emits {@code Activity(TOOL)} alongside its existing
 *       {@code ToolCall}/{@code ToolResult} pair.</li>
 *   <li>{@link AuditingDocumentRetriever} emits {@code Activity(RETRIEVAL)} at the start of
 *       {@code retrieve(...)}.</li>
 *   <li>A {@code null} sink holder (or a sink whose {@code tryEmitNext} throws) never breaks
 *       the tool call / retrieval.</li>
 * </ul>
 * Pure JUnit 5 + Mockito — no Spring. Mirrors {@code ToolCallbackAuditDecoratorTest} /
 * {@code AuditingDocumentRetrieverTest}.
 *
 * <p>Note: {@code Activity(CHAT)} is deliberately <em>not</em> emitted from the orchestration
 * edge (Phase 15 review point #11) — {@code DefaultChatServiceImpl} is unchanged and the UI
 * derives CHAT from the first {@code Content} event of the turn.
 */
class StreamingActivityEventTest {

    @AfterEach
    void clearRunContext() {
        RunContext.clear();
    }

    private static ToolCallback echoDelegate() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("find_records")
                .description("Find records")
                .inputSchema("{}")
                .build());
        when(delegate.call(eq("{}"))).thenReturn("ok");
        return delegate;
    }

    private static CurrentAuthentication aliceAuthentication() {
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        when(currentAuthentication.getUser()).thenReturn(
                User.withUsername("alice").password("x").authorities("ROLE_USER").build());
        return currentAuthentication;
    }

    @Test
    void toolCall_emitsActivityTool_ontoTheActiveSink() {
        UUID runId = UUID.randomUUID();
        StreamingSinkHolder sinkHolder = new StreamingSinkHolder();
        Sinks.Many<StreamingEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        List<StreamingEvent> events = new ArrayList<>();
        sink.asFlux().subscribe(events::add);
        sinkHolder.register(runId, sink);
        RunContext.set(runId);

        ToolCallbackAuditDecorator decorator = new ToolCallbackAuditDecorator(
                echoDelegate(), mock(AuditWriter.class), aliceAuthentication(), sinkHolder);

        decorator.call("{}");

        assertThat(events)
                .filteredOn(StreamingEvent.Activity.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((StreamingEvent.Activity) event).kind())
                        .isEqualTo(StreamingEvent.ActivityKind.TOOL));
        // Activity(CHAT) is never emitted from the edge.
        assertThat(events)
                .filteredOn(StreamingEvent.Activity.class::isInstance)
                .noneSatisfy(event -> assertThat(((StreamingEvent.Activity) event).kind())
                        .isEqualTo(StreamingEvent.ActivityKind.CHAT));
    }

    @Test
    void toolCall_withNullSinkHolder_stillCompletes() {
        ToolCallbackAuditDecorator decorator = new ToolCallbackAuditDecorator(
                echoDelegate(), mock(AuditWriter.class), aliceAuthentication(), null);

        assertThatNoException().isThrownBy(() -> decorator.call("{}"));
    }

    @Test
    void toolCall_withThrowingSink_stillCompletes() {
        UUID runId = UUID.randomUUID();
        StreamingSinkHolder sinkHolder = mock(StreamingSinkHolder.class);
        when(sinkHolder.currentOrForRun(any())).thenThrow(new IllegalStateException("sink boom"));
        RunContext.set(runId);

        ToolCallbackAuditDecorator decorator = new ToolCallbackAuditDecorator(
                echoDelegate(), mock(AuditWriter.class), aliceAuthentication(), sinkHolder);

        assertThatNoException().isThrownBy(() -> decorator.call("{}"));
    }

    @Test
    void retrieval_emitsActivityRetrieval_ontoTheActiveSink() {
        UUID runId = UUID.randomUUID();
        StreamingSinkHolder sinkHolder = new StreamingSinkHolder();
        Sinks.Many<StreamingEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        List<StreamingEvent> events = new ArrayList<>();
        sink.asFlux().subscribe(events::add);
        sinkHolder.register(runId, sink);
        RunContext.set(runId);

        DocumentRetriever stubDelegate = q -> List.of(Document.builder().id("doc-1").text("hello").build());
        AuditingDocumentRetriever retriever = new AuditingDocumentRetriever(
                stubDelegate, mock(AuditWriter.class), mock(CurrentAuthentication.class), sinkHolder);

        retriever.retrieve(new Query("what is foo?"));

        assertThat(events)
                .filteredOn(StreamingEvent.Activity.class::isInstance)
                .singleElement()
                .satisfies(event -> assertThat(((StreamingEvent.Activity) event).kind())
                        .isEqualTo(StreamingEvent.ActivityKind.RETRIEVAL));
    }

    @Test
    void retrieval_withNullSinkHolder_stillCompletes() {
        DocumentRetriever stubDelegate = q -> List.of(Document.builder().id("doc-1").text("hello").build());
        AuditingDocumentRetriever retriever = new AuditingDocumentRetriever(
                stubDelegate, mock(AuditWriter.class), mock(CurrentAuthentication.class));

        List<Document> docs = retriever.retrieve(new Query("what is foo?"));
        assertThat(docs).hasSize(1);
    }

    @Test
    void retrieval_withThrowingSink_stillReturnsDocuments() {
        UUID runId = UUID.randomUUID();
        StreamingSinkHolder sinkHolder = mock(StreamingSinkHolder.class);
        when(sinkHolder.currentOrForRun(any())).thenThrow(new IllegalStateException("sink boom"));
        RunContext.set(runId);

        DocumentRetriever stubDelegate = q -> List.of(Document.builder().id("doc-1").text("hello").build());
        AuditingDocumentRetriever retriever = new AuditingDocumentRetriever(
                stubDelegate, mock(AuditWriter.class), mock(CurrentAuthentication.class), sinkHolder);

        List<Document> docs = retriever.retrieve(new Query("what is foo?"));
        assertThat(docs).hasSize(1);
    }
}
