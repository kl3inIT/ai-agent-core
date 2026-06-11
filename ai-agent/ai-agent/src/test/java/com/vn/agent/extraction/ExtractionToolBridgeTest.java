package com.vn.agent.extraction;

import com.vn.agent.action.ActionProposalService;
import com.vn.agent.action.ActionProposalTool;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.audit.MutationArgumentSanitizer;
import com.vn.agent.audit.ToolCallbackAuditDecorator;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.tools.AgentToolCallbacks;
import com.vn.agent.tools.BuiltInDataTools;
import com.vn.agent.tools.jpql.BuiltInJpqlTool;
import com.vn.agent.tools.link.BuiltInLinkTools;
import com.vn.agent.tools.mutation.BuiltInMutationTools;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.userdetails.User;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtractionToolBridgeTest {

    @Test
    void prepareFormDraft_returnsLockedPayloadShape() {
        UUID draftId = UUID.randomUUID();
        ExtractionService extractionService = mock(ExtractionService.class);
        when(extractionService.prepare("customer-from-source", Map.of()))
                .thenReturn(new ExtractionResult(draftId, "jmixapp_Customer", "Customer draft " + draftId));

        ExtractionToolBridge bridge = new ExtractionToolBridge(extractionService);

        Map<String, Object> payload = bridge.prepareFormDraft("customer-from-source", Map.of());

        assertThat(payload.keySet())
                .containsExactly("action", "draftId", "entityName", "instanceName");
        assertThat(payload)
                .containsEntry("action", "open_form_with_draft")
                .containsEntry("draftId", draftId)
                .containsEntry("entityName", "jmixapp_Customer")
                .containsEntry("instanceName", "Customer draft " + draftId);
    }

    @Test
    void prepareFormDraft_carriesRunContextSourceTextsIntoScopedInput() {
        UUID draftId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID taskFileId = UUID.randomUUID();
        ExtractionSourceText sourceText = new ExtractionSourceText(
                "customer.txt", "Customer Acme email billing@example.test", false);
        ExtractionService extractionService = mock(ExtractionService.class);
        when(extractionService.prepare(eq("customer-from-source"), any(ExtractionInput.class)))
                .thenReturn(new ExtractionResult(draftId, "jmixapp_Customer", "Customer draft"));
        ExtractionToolBridge bridge = new ExtractionToolBridge(extractionService);

        RunContext.setExtractionTurn("customer-from-source", conversationId, "prepare draft",
                List.of(taskFileId), List.of(), List.of(sourceText));
        try {
            bridge.prepareFormDraft("ignored-intent", Map.of());
        } finally {
            RunContext.clear();
        }

        ArgumentCaptor<ExtractionInput> inputCaptor = ArgumentCaptor.forClass(ExtractionInput.class);
        verify(extractionService).prepare(eq("customer-from-source"), inputCaptor.capture());
        assertThat(inputCaptor.getValue().sourceTexts()).containsExactly(sourceText);
    }

    @Test
    void toolDescription_containsRequiredRichSections() throws NoSuchMethodException {
        Method method = ExtractionToolBridge.class.getMethod("prepareFormDraft", String.class, Map.class);
        Tool tool = method.getAnnotation(Tool.class);

        assertThat(tool).isNotNull();
        assertThat(tool.name()).isEqualTo("prepare_form_draft");
        assertThat(tool.description())
                .contains("MANDATORY WORKFLOW:")
                .contains("INPUT CONTRACT:")
                .contains("PARAMETER FORMATS:")
                .contains("ERROR HANDLING:")
                .contains("STRICTNESS + EXAMPLES:");
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentToolCallbacks_includesPrepareFormDraftForCurrentUser() {
        ExtractionService extractionService = mock(ExtractionService.class);
        ExtractionToolBridge bridge = new ExtractionToolBridge(extractionService);
        ObjectProvider<BuiltInMutationTools> mutationToolsProvider = mock(ObjectProvider.class);
        when(mutationToolsProvider.getIfAvailable()).thenReturn(null);
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        when(currentAuthentication.getUser()).thenReturn(
                User.withUsername("alice").password("x").authorities("ROLE_USER").build());

        AgentToolCallbacks callbacks = new AgentToolCallbacks(
                mock(BuiltInDataTools.class),
                mock(BuiltInLinkTools.class),
                mock(BuiltInJpqlTool.class),
                bridge,
                new ActionProposalTool(mock(ActionProposalService.class)),
                mutationToolsProvider,
                List.of(),
                mock(AuditWriter.class),
                currentAuthentication,
                mock(StreamingSinkHolder.class),
                mock(MutationArgumentSanitizer.class));

        List<String> names = Arrays.stream(callbacks.forCurrentUser())
                .map(ToolCallback::getToolDefinition)
                .map(ToolDefinition::name)
                .collect(Collectors.toList());

        assertThat(names).contains("prepare_form_draft");
        ToolCallback prepareFormDraft = Arrays.stream(callbacks.forCurrentUser())
                .filter(callback -> "prepare_form_draft".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
        assertThat(prepareFormDraft).isInstanceOf(ToolCallbackAuditDecorator.class);
    }
}
