package com.vn.agent.extraction;

import com.vn.agent.action.ActionIntentId;
import com.vn.agent.action.ActionProposalService;
import com.vn.agent.action.ActionProposalTool;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.audit.MutationArgumentSanitizer;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.spi.ToolContributor;
import com.vn.agent.tools.AgentToolCallbacks;
import com.vn.agent.tools.BuiltInDataTools;
import com.vn.agent.tools.link.BuiltInLinkTools;
import com.vn.agent.tools.mutation.BuiltInMutationTools;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.userdetails.User;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolCallbacksIntentGatingTest {

    @Test
    void autoIntentReturnsBaselineCallbacks() {
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        List<String> names = names(callbacks.callbacksFor("alice", UUID.randomUUID(), null));

        assertThat(names)
                .contains("propose_action_choices", "find_records")
                .doesNotContain("prepare_form_draft");
    }

    @Test
    void namedIntentReturnsOnlyPrepareFormDraft() {
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        ToolCallback[] namedIntentCallbacks = callbacks.callbacksFor(
                "alice", UUID.randomUUID(), "customer-from-source");

        assertThat(names(namedIntentCallbacks))
                .containsExactly("prepare_form_draft");
    }

    @Test
    void createNowActionAddsMutationCallbacksOnlyAfterSelection() {
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        List<String> planningNames = names(callbacks.callbacksFor("alice", UUID.randomUUID(), null));
        List<String> createNowNames = names(callbacks.callbacksFor("alice", UUID.randomUUID(),
                ActionIntentId.selectionParameter(ActionIntentId.CREATE_NOW)));

        assertThat(planningNames).doesNotContain("bulk_save_records");
        assertThat(createNowNames).contains("bulk_save_records");
    }

    @Test
    void bulkCreateNowActionAddsMutationCallbacksIncludingBulkSaveRecords() {
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        List<String> bulkCreateNowNames = names(callbacks.callbacksFor("alice", UUID.randomUUID(),
                ActionIntentId.selectionParameter(ActionIntentId.BULK_CREATE_NOW)));

        assertThat(bulkCreateNowNames).contains("bulk_save_records");
    }

    @Test
    void planningTurnExposesBothSingleAndBulkActionProposalTools() {
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        List<String> planningNames = names(callbacks.callbacksFor("alice", UUID.randomUUID(), null));

        assertThat(planningNames)
                .contains("propose_action_choices", "propose_bulk_action_choices");
    }

    @Test
    void singleProposalToolInputSchemaKeepsValuesAsObjectAfterArrayTolerantTypeChange() {
        // Iteration-2 guard: swapping `values` to the array-tolerant SingleRecordValues must NOT
        // degrade the advertised schema. The model must still see `values` as a JSON object so the
        // single-record happy path is unchanged; the array tolerance is a runtime safety net only.
        AgentToolCallbacks callbacks = callbacksWithContributorTools();
        ToolCallback[] planning = callbacks.callbacksFor("alice", UUID.randomUUID(), null);

        ToolDefinition single = Arrays.stream(planning)
                .map(ToolCallback::getToolDefinition)
                .filter(def -> "propose_action_choices".equals(def.name()))
                .findFirst()
                .orElseThrow();

        String schema = single.inputSchema();
        assertThat(schema).isNotBlank();
        // The whole input schema must be a JSON object (parses) and declare a `values` property that
        // is typed as an object, never an array.
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("propose_action_choices input schema is not valid JSON: " + schema, e);
        }
        com.fasterxml.jackson.databind.JsonNode valuesNode = root.path("properties").path("values");
        assertThat(valuesNode.isMissingNode()).isFalse();
        String valuesType = valuesNode.path("type").asText("");
        // Must be an object schema (or at least not an array) so the model is steered to a single
        // object for one record.
        assertThat(valuesType).isNotEqualTo("array");
    }

    @Test
    void enabledToolsAllowlistRestrictsBusinessToolsButNeverOrchestrationTools() {
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        // Allowlist contains only the read tool. Business tools NOT listed (e.g. find_records is
        // listed, bulk_save_records is not) are gated; the proposal/orchestration tools are exempt
        // and always survive regardless of the allowlist.
        List<String> restricted = names(callbacks.callbacksFor("alice", UUID.randomUUID(), null,
                List.of("find_records")));

        assertThat(restricted).contains("find_records");
        // Orchestration tools are exempt — present even though the allowlist omits them.
        assertThat(restricted).contains("propose_action_choices", "propose_bulk_action_choices");
    }

    @Test
    void orchestrationToolsExemptFromAllowlistEvenWhenAllowlistOmitsBulkProposal() {
        // Iteration-3 regression: reproduces the collision between Workstream A (added
        // propose_bulk_action_choices) and Workstream B (enabledTools allowlist enforcement). A
        // realistic seeded allowlist predates the bulk proposal tool, so it lists the single-record
        // proposal tool but NOT propose_bulk_action_choices, and crucially does NOT list every
        // business tool. The planning turn MUST still expose propose_bulk_action_choices (exempt
        // orchestration tool), while a non-exempt business tool absent from the allowlist is
        // correctly excluded — proving the exemption does NOT re-widen business/data tools.
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        // Seeded allowlist that predates the bulk proposal tool AND omits the business read tool.
        List<String> seededAllowlistMissingBulkProposalAndFindRecords = List.of("propose_action_choices");

        List<String> planning = names(callbacks.callbacksFor("alice", UUID.randomUUID(), null,
                seededAllowlistMissingBulkProposalAndFindRecords));

        // The exempt orchestration tool survives despite being absent from the allowlist — this is
        // the fix for the live UAT fallback-to-per-row failure.
        assertThat(planning).contains("propose_bulk_action_choices");
        // The single-record proposal tool is present (listed AND exempt).
        assertThat(planning).contains("propose_action_choices");
        // A non-exempt business tool absent from the allowlist (find_records) is still gated out —
        // the exemption does NOT re-widen business/data tools (Workstream B security intent preserved).
        assertThat(planning).doesNotContain("find_records");
    }

    @Test
    void enabledToolsAllowlistRestrictsMutationSurfaceOnActionTurn() {
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        List<String> unrestricted = names(callbacks.callbacksFor("alice", UUID.randomUUID(),
                ActionIntentId.selectionParameter(ActionIntentId.BULK_CREATE_NOW)));
        // Allowlist that omits bulk_save_records must hide it even on the bulk-create-now turn.
        List<String> restricted = names(callbacks.callbacksFor("alice", UUID.randomUUID(),
                ActionIntentId.selectionParameter(ActionIntentId.BULK_CREATE_NOW),
                List.of("find_records")));

        assertThat(unrestricted).contains("bulk_save_records");
        assertThat(restricted).doesNotContain("bulk_save_records");
        assertThat(restricted).isNotEmpty();
        assertThat(restricted).allMatch("find_records"::equals);
    }

    @Test
    void enabledToolsAllowlistCannotWidenBeyondIntentRouting() {
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        // Listing a tool that the planning routing does not assemble must not introduce it.
        List<String> restricted = names(callbacks.callbacksFor("alice", UUID.randomUUID(), null,
                List.of("propose_action_choices", "delete_record", "not_a_real_tool")));

        // The allowlist can only narrow; names it lists that the routing never assembled are not
        // introduced. The orchestration tools are always present (exempt), but delete_record and
        // an unknown tool name must NOT appear — the allowlist cannot widen the surface.
        assertThat(restricted).doesNotContain("delete_record", "not_a_real_tool");
        // find_records was assembled by the routing but is a non-exempt business tool absent from the
        // allowlist, so it is gated out.
        assertThat(restricted).doesNotContain("find_records");
        // propose_action_choices is both listed and exempt; the bulk proposal tool is exempt.
        assertThat(restricted).contains("propose_action_choices", "propose_bulk_action_choices");
    }

    @Test
    void nullAllowlistPreservesDefaultBehavior() {
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        List<String> withNull = names(callbacks.callbacksFor("alice", UUID.randomUUID(), null, null));
        List<String> withEmpty = names(callbacks.callbacksFor("alice", UUID.randomUUID(), null, List.of()));
        List<String> unfiltered = names(callbacks.callbacksFor("alice", UUID.randomUUID(), null));

        assertThat(withNull).isEqualTo(unfiltered);
        assertThat(withEmpty).isEqualTo(unfiltered);
    }

    @Test
    void prefillActionReturnsOnlyPrepareFormDraft() {
        AgentToolCallbacks callbacks = callbacksWithContributorTools();

        ToolCallback[] prefillCallbacks = callbacks.callbacksFor("alice", UUID.randomUUID(),
                ActionIntentId.selectionParameter(ActionIntentId.PREFILL_FORM));

        assertThat(names(prefillCallbacks)).containsExactly("prepare_form_draft");
    }

    @Test
    void namedIntentFailsClosedWhenPrepareFormDraftIsDuplicated() {
        AgentToolCallbacks callbacks = callbacksWithDuplicatePrepareFormDraftTool();

        assertThatThrownBy(() ->
                        callbacks.callbacksFor("alice", UUID.randomUUID(), "customer-from-source"))
                .isInstanceOf(AgentToolCallbacks.ToolConfigurationException.class)
                .satisfies(throwable -> assertThat((AgentToolCallbacks.ToolConfigurationException) throwable)
                        .extracting(AgentToolCallbacks.ToolConfigurationException::getMatchingCallbackCount)
                        .isEqualTo(2));
    }

    private static AgentToolCallbacks callbacksWithContributorTools() {
        ExtractionService extractionService = mock(ExtractionService.class);
        when(extractionService.prepare("customer-from-source", Map.of()))
                .thenReturn(new ExtractionResult(UUID.randomUUID(), "jmixapp_Customer", "Customer draft"));
        return callbacks(new ExtractionToolBridge(extractionService));
    }

    private static AgentToolCallbacks callbacksWithDuplicatePrepareFormDraftTool() {
        ExtractionService extractionService = mock(ExtractionService.class);
        return callbacks(new ExtractionToolBridge(extractionService), List.of(new DuplicatePrepareFormDraftTools()));
    }

    private static AgentToolCallbacks callbacks(ExtractionToolBridge extractionToolBridge) {
        return callbacks(extractionToolBridge, List.of(new ContributorTools()));
    }

    @SuppressWarnings("unchecked")
    private static AgentToolCallbacks callbacks(ExtractionToolBridge extractionToolBridge,
                                                List<ToolContributor> contributors) {
        ObjectProvider<BuiltInMutationTools> mutationToolsProvider = mock(ObjectProvider.class);
        when(mutationToolsProvider.getIfAvailable()).thenReturn(null);
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        when(currentAuthentication.getUser()).thenReturn(
                User.withUsername("alice").password("x").authorities("ROLE_USER").build());

        return new AgentToolCallbacks(
                mock(BuiltInDataTools.class),
                mock(BuiltInLinkTools.class),
                extractionToolBridge,
                new ActionProposalTool(mock(ActionProposalService.class)),
                mutationToolsProvider,
                contributors,
                mock(AuditWriter.class),
                currentAuthentication,
                mock(StreamingSinkHolder.class),
                mock(MutationArgumentSanitizer.class));
    }

    private static List<String> names(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(ToolCallback::getToolDefinition)
                .map(ToolDefinition::name)
                .toList();
    }

    private static final class ContributorTools implements ToolContributor {

        @Override
        public List<Object> contribute() {
            return List.of(this);
        }

        @Tool(name = "find_records", description = "test read-like contributor tool")
        public String findRecords() {
            return "[]";
        }

        @Tool(name = "bulk_save_records", description = "test mutation-like contributor tool")
        public String bulkSaveRecords() {
            return "{}";
        }
    }

    private static final class DuplicatePrepareFormDraftTools implements ToolContributor {

        @Override
        public List<Object> contribute() {
            return List.of(this);
        }

        @Tool(name = "prepare_form_draft", description = "duplicate test extraction tool")
        public String prepareFormDraft() {
            return "{}";
        }
    }
}
