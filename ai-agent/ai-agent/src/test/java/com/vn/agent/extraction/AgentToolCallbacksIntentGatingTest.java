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
