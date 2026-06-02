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

    @Test
    void bulkToolForwardsRowsAsBulkProposal() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.validate(any(ActionProposal.class))).thenReturn(
                ActionProposalResult.ready(new ActionProposal(
                        "proposal-3", "create", "jmixapp_Product", "2 Product",
                        Map.of(), List.of(Map.of("name", "Desk"), Map.of("name", "Chair")),
                        List.of(), List.of(ActionIntentId.BULK_CREATE_NOW)),
                        List.of(ActionIntentId.BULK_CREATE_NOW)));
        ActionProposalTool tool = new ActionProposalTool(service);

        tool.proposeBulkActionChoices(
                "create", "jmixapp_Product", null,
                List.of(Map.of("name", "Desk"), Map.of("name", "Chair")), List.of());

        org.mockito.ArgumentCaptor<ActionProposal> proposalCaptor =
                org.mockito.ArgumentCaptor.forClass(ActionProposal.class);
        verify(service).validate(proposalCaptor.capture());
        ActionProposal forwarded = proposalCaptor.getValue();
        assertThat(forwarded.isBulk()).isTrue();
        assertThat(forwarded.valuesList()).hasSize(2);
        assertThat(forwarded.values()).isEmpty();
    }

    @Test
    void bulkToolHandlesNullRowsAsEmptyList() {
        ActionProposalService service = mock(ActionProposalService.class);
        when(service.validate(any(ActionProposal.class))).thenReturn(
                ActionProposalResult.invalid(new ActionProposal(
                        "proposal-4", "create", "jmixapp_Product", "Product",
                        Map.of(), List.of(), List.of(), List.of()),
                        "chatView.actionChoice.invalidProposal"));
        ActionProposalTool tool = new ActionProposalTool(service);

        tool.proposeBulkActionChoices("create", "jmixapp_Product", null, null, List.of());

        org.mockito.ArgumentCaptor<ActionProposal> proposalCaptor =
                org.mockito.ArgumentCaptor.forClass(ActionProposal.class);
        verify(service).validate(proposalCaptor.capture());
        assertThat(proposalCaptor.getValue().valuesList()).isEmpty();
        assertThat(proposalCaptor.getValue().isBulk()).isFalse();
    }

    @Test
    void toolHandlesNullValuesAsEmptyMap() {
        ActionProposalService service = mock(ActionProposalService.class);
        ActionProposalResult missing = ActionProposalResult.missingFields(new ActionProposal(
                "proposal-2", "create", "jmixapp_Product", "Desk",
                Map.of(), List.of("name"), List.of()), List.of("name"));
        when(service.validate(any(ActionProposal.class))).thenReturn(missing);
        ActionProposalTool tool = new ActionProposalTool(service);

        ActionProposalResult result = tool.proposeActionChoices(
                "create", "jmixapp_Product", "Desk",
                null, List.of(), List.of(ActionIntentId.PREFILL_FORM));

        assertThat(result).isSameAs(missing);
        org.mockito.ArgumentCaptor<ActionProposal> proposalCaptor =
                org.mockito.ArgumentCaptor.forClass(ActionProposal.class);
        verify(service).validate(proposalCaptor.capture());
        assertThat(proposalCaptor.getValue().values()).isEmpty();
    }
}
