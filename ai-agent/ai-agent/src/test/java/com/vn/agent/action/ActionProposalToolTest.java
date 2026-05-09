package com.vn.agent.action;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionProposalToolTest {

    @Test
    void toolDelegatesStructuredProposalToValidationService() {
        ActionProposalService service = mock(ActionProposalService.class);
        ActionProposalResult ready = ActionProposalResult.ready(new ActionProposal(
                "proposal-1", "create", "jmixapp_Product", "Desk",
                Map.of("name", "Desk"), List.of(), List.of(ActionIntentId.PREFILL_FORM)),
                List.of(ActionIntentId.PREFILL_FORM));
        when(service.validate(any(ActionProposal.class))).thenReturn(ready);
        ActionProposalTool tool = new ActionProposalTool(service);

        ActionProposalResult result = tool.proposeActionChoices(
                "create", "jmixapp_Product", "Desk",
                Map.of("name", "Desk"), List.of(), List.of(ActionIntentId.PREFILL_FORM));

        assertThat(result).isSameAs(ready);
        org.mockito.ArgumentCaptor<ActionProposal> proposalCaptor =
                org.mockito.ArgumentCaptor.forClass(ActionProposal.class);
        verify(service).validate(proposalCaptor.capture());
        assertThat(proposalCaptor.getValue().targetEntityName()).isEqualTo("jmixapp_Product");
        assertThat(proposalCaptor.getValue().values()).containsEntry("name", "Desk");
    }
}
