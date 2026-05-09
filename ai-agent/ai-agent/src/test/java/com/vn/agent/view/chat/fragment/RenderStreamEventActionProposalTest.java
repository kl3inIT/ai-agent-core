package com.vn.agent.view.chat.fragment;

import com.vn.agent.action.ActionIntentId;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.StreamingEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RenderStreamEventActionProposalTest {

    @Test
    void readyActionProposalPayloadRendersActionMarker() {
        String payloadJson = """
                {
                  "action": "show_action_choices",
                  "status": "READY",
                  "proposal": {
                    "proposalId": "proposal-1",
                    "targetEntityName": "jmixapp_Product",
                    "instanceName": "Desk",
                    "values": {"name": "Desk"}
                  },
                  "choices": ["create-now", "prefill-form"]
                }
                """;

        StreamEventRenderer.RenderedStreamEvent rendered = StreamEventRenderer.renderStreamEventDetails(
                new StreamingEvent.ToolResult(UUID.randomUUID(), "propose_action_choices",
                        payloadJson, AiToolCallOutcome.SUCCESS, payloadJson),
                Map.of(), new StreamEventRenderer.CitationState());

        assertThat(rendered.markdown()).isEmpty();
        assertThat(rendered.draftPayload()).isNull();
        assertThat(rendered.actionProposalPayload()).isNotNull();
        assertThat(rendered.actionProposalPayload().proposalId()).isEqualTo("proposal-1");
        assertThat(rendered.actionProposalPayload().targetEntityName()).isEqualTo("jmixapp_Product");
        assertThat(rendered.actionProposalPayload().values()).containsEntry("name", "Desk");
        assertThat(rendered.actionProposalPayload().choices())
                .containsExactly(ActionIntentId.CREATE_NOW, ActionIntentId.PREFILL_FORM);
    }

    @Test
    void nonReadyActionProposalPayloadDoesNotRenderChoices() {
        String payloadJson = """
                {"action":"show_action_choices","status":"MISSING_FIELDS",
                 "proposal":{"proposalId":"proposal-1","targetEntityName":"jmixapp_Product"},
                 "choices":[]}
                """;

        StreamEventRenderer.RenderedStreamEvent rendered = StreamEventRenderer.renderStreamEventDetails(
                new StreamingEvent.ToolResult(UUID.randomUUID(), "propose_action_choices",
                        payloadJson, AiToolCallOutcome.SUCCESS, payloadJson),
                Map.of(), new StreamEventRenderer.CitationState());

        assertThat(rendered.markdown()).isEmpty();
        assertThat(rendered.actionProposalPayload()).isNull();
    }
}
