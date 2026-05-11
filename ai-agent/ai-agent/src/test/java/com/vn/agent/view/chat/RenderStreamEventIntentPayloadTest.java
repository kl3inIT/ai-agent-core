package com.vn.agent.view.chat;

import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.view.chat.fragment.StreamEventRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RenderStreamEventIntentPayloadTest {

    private static final UUID TOOL_CALL_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DRAFT_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void prepareFormDraftValidPayloadReturnsDraftPayloadMarker() {
        StreamingEvent.ToolResult event = new StreamingEvent.ToolResult(
                TOOL_CALL_ID,
                "prepare_form_draft",
                "human summary ignored",
                AiToolCallOutcome.SUCCESS,
                """
                        {
                          "action": "open_form_with_draft",
                          "draftId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                          "entityName": "jmixapp_Customer",
                          "instanceName": "Acme Inc."
                        }
                        """);

        StreamEventRenderer.RenderedStreamEvent rendered = StreamEventRenderer.renderStreamEventDetails(
                event, labels(), new StreamEventRenderer.CitationState());

        assertThat(rendered.markdown()).isEmpty();
        assertThat(rendered.draftPayloadInvalid()).isFalse();
        assertThat(rendered.draftPayload()).isNotNull();
        assertThat(rendered.draftPayload().draftId()).isEqualTo(DRAFT_ID);
        assertThat(rendered.draftPayload().entityName()).isEqualTo("jmixapp_Customer");
        assertThat(rendered.draftPayload().instanceName()).isEqualTo("Acme Inc.");
    }

    @Test
    void prepareFormDraftDoesNotParseHumanReadableSummary() {
        StreamingEvent.ToolResult event = new StreamingEvent.ToolResult(
                TOOL_CALL_ID,
                "prepare_form_draft",
                """
                        {"action":"open_form_with_draft","draftId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","entityName":"jmixapp_Customer","instanceName":"Acme Inc."}
                        """,
                AiToolCallOutcome.SUCCESS,
                null);

        StreamEventRenderer.RenderedStreamEvent rendered = StreamEventRenderer.renderStreamEventDetails(
                event, labels(), new StreamEventRenderer.CitationState());

        assertThat(rendered.markdown()).isEmpty();
        assertThat(rendered.draftPayload()).isNull();
        assertThat(rendered.draftPayloadInvalid()).isTrue();
    }

    @Test
    void malformedPrepareFormDraftPayloadIsVisibleContractErrorMarker() {
        StreamingEvent.ToolResult event = new StreamingEvent.ToolResult(
                TOOL_CALL_ID,
                "prepare_form_draft",
                "summary",
                AiToolCallOutcome.SUCCESS,
                "{not-json");

        StreamEventRenderer.RenderedStreamEvent rendered = StreamEventRenderer.renderStreamEventDetails(
                event, labels(), new StreamEventRenderer.CitationState());

        assertThat(rendered.markdown()).isEmpty();
        assertThat(rendered.draftPayload()).isNull();
        assertThat(rendered.draftPayloadInvalid()).isTrue();
    }

    @Test
    void malformedNonExtractionPayloadStaysSilent() {
        StreamingEvent.ToolResult event = new StreamingEvent.ToolResult(
                TOOL_CALL_ID,
                "find_records",
                "summary",
                AiToolCallOutcome.SUCCESS,
                "{not-json");

        StreamEventRenderer.RenderedStreamEvent rendered = StreamEventRenderer.renderStreamEventDetails(
                event, labels(), new StreamEventRenderer.CitationState());

        assertThat(rendered.markdown()).isEmpty();
        assertThat(rendered.draftPayload()).isNull();
        assertThat(rendered.draftPayloadInvalid()).isFalse();
    }

    @Test
    void wrongExtractionActionIsContractErrorMarker() {
        StreamingEvent.ToolResult event = new StreamingEvent.ToolResult(
                TOOL_CALL_ID,
                "prepare_form_draft",
                "summary",
                AiToolCallOutcome.SUCCESS,
                """
                        {"action":"other","draftId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","entityName":"jmixapp_Customer","instanceName":"Acme Inc."}
                        """);

        StreamEventRenderer.RenderedStreamEvent rendered = StreamEventRenderer.renderStreamEventDetails(
                event, labels(), new StreamEventRenderer.CitationState());

        assertThat(rendered.draftPayload()).isNull();
        assertThat(rendered.draftPayloadInvalid()).isTrue();
    }

    private static Map<String, String> labels() {
        return Map.of(
                "chatView.stream.sources", "Sources",
                "chatView.stream.error", "error");
    }
}
