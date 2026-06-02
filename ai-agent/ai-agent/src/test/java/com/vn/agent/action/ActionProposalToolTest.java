package com.vn.agent.action;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                SingleRecordValues.ofSingle(Map.of("name", "Desk")),
                List.of(), List.of(ActionIntentId.PREFILL_FORM));

        assertThat(result).isSameAs(ready);
        org.mockito.ArgumentCaptor<ActionProposal> proposalCaptor =
                org.mockito.ArgumentCaptor.forClass(ActionProposal.class);
        verify(service).validate(proposalCaptor.capture());
        assertThat(proposalCaptor.getValue().targetEntityName()).isEqualTo("jmixapp_Product");
        assertThat(proposalCaptor.getValue().values()).containsEntry("name", "Desk");
    }

    @Test
    void singleRecordToolReturnsCorrectiveResultInsteadOfThrowingWhenGivenMultipleRows() {
        // Iteration-2 regression guard: the model reached for the single-record tool but supplied
        // an ARRAY of rows. The array-tolerant SingleRecordValues deserializer captures them and the
        // tool returns a structured WRONG_TOOL_FOR_BULK corrective — never a ToolExecutionException.
        ActionProposalService service = mock(ActionProposalService.class);
        ActionProposalTool tool = new ActionProposalTool(service);

        SingleRecordValues multi = new SingleRecordValues(
                Map.of(),
                List.of(Map.of("name", "Bulk Test A"), Map.of("name", "Bulk Test B"),
                        Map.of("name", "Bulk Test C")),
                true);

        ActionProposalResult result = tool.proposeActionChoices(
                "create", "Customer", "Bulk Test A, Bulk Test B, Bulk Test C",
                multi, List.of(), List.of(ActionIntentId.CREATE_NOW));

        assertThat(result.status()).isEqualTo(ActionProposalResult.STATUS_WRONG_TOOL_FOR_BULK);
        assertThat(result.action()).isEqualTo(ActionProposalResult.ACTION_USE_BULK_TOOL);
        assertThat(result.messageKey()).contains("propose_bulk_action_choices");
        // It must NOT have routed an (invalid) single-record proposal into validation.
        verify(service, never()).validate(any(ActionProposal.class));
    }

    @Test
    void deserializerTurnsArrayPayloadIntoMultiRecordWithoutThrowing() throws Exception {
        // Reproduces the exact live failure shape: a JSON array bound to the single-record `values`.
        // With the array-tolerant type this deserializes cleanly (no MismatchedInputException) and is
        // flagged multiRecord. Mirrors what Spring AI's MethodToolCallback does during arg binding.
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        String arrayJson = "[{\"name\":\"Bulk Test A\"},{\"name\":\"Bulk Test B\"},"
                + "{\"name\":\"Bulk Test C\"}]";

        SingleRecordValues parsed = mapper.readValue(arrayJson, SingleRecordValues.class);

        assertThat(parsed.multiRecord()).isTrue();
        assertThat(parsed.rows()).hasSize(3);
        assertThat(parsed.map()).isEmpty();
    }

    @Test
    void deserializerKeepsObjectPayloadAsSingleRecord() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();

        SingleRecordValues parsed = mapper.readValue("{\"name\":\"Desk\"}", SingleRecordValues.class);

        assertThat(parsed.multiRecord()).isFalse();
        assertThat(parsed.map()).containsEntry("name", "Desk");
        assertThat(parsed.rows()).isEmpty();
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
