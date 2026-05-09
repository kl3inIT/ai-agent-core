package com.vn.agent.extraction;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.audit.ToolCallbackAuditDecorator;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.orchestration.StreamingSinkHolder;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.core.userdetails.User;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExtractionAuditTest {

    @AfterEach
    void clearRunContext() {
        RunContext.clear();
    }

    @Test
    void prepareFormDraftToolResultCarriesStructuredPayloadWithoutDuplicateGenericAudit() {
        String payload = "{\"action\":\"open_form_with_draft\",\"draftId\":\""
                + UUID.randomUUID() + "\",\"entityName\":\"jmixapp_Customer\",\"instanceName\":\"Customer draft\"}";
        ToolCallback delegate = callback("prepare_form_draft", payload);
        AuditWriter auditWriter = mock(AuditWriter.class);
        List<StreamingEvent> events = new ArrayList<>();
        ToolCallbackAuditDecorator decorator = decorator(delegate, auditWriter, events);

        decorator.call("{}");

        StreamingEvent.ToolResult toolResult = events.stream()
                .filter(StreamingEvent.ToolResult.class::isInstance)
                .map(StreamingEvent.ToolResult.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(toolResult.toolName()).isEqualTo("prepare_form_draft");
        assertThat(toolResult.payloadJson()).isEqualTo(payload);
        verify(auditWriter, never()).writeToolCall(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                eq("prepare_form_draft"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void readyActionProposalToolResultCarriesStructuredPayloadAndUsesGenericAudit() {
        String payload = """
                {"action":"show_action_choices","status":"READY",
                 "proposal":{"proposalId":"proposal-1","targetEntityName":"jmixapp_Product","instanceName":"Desk",
                             "values":{"name":"Desk"}},
                 "choices":["create-now","prefill-form"]}
                """;
        ToolCallback delegate = callback("propose_action_choices", payload);
        AuditWriter auditWriter = mock(AuditWriter.class);
        List<StreamingEvent> events = new ArrayList<>();
        ToolCallbackAuditDecorator decorator = decorator(delegate, auditWriter, events);

        decorator.call("{}");

        StreamingEvent.ToolResult toolResult = events.stream()
                .filter(StreamingEvent.ToolResult.class::isInstance)
                .map(StreamingEvent.ToolResult.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(toolResult.toolName()).isEqualTo("propose_action_choices");
        assertThat(toolResult.payloadJson()).isEqualTo(payload);
        verify(auditWriter).writeToolCall(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                eq("alice"), org.mockito.ArgumentMatchers.any(), eq("propose_action_choices"),
                eq("{}"), eq(payload), anyLong(), eq(AiToolCallOutcome.SUCCESS),
                isNull(), isNull());
    }

    @Test
    void nonExtractionToolResultKeepsPayloadJsonNullAndStillUsesGenericAudit() {
        String output = "{\"action\":\"open_form_with_draft\"}";
        ToolCallback delegate = callback("find_records", output);
        AuditWriter auditWriter = mock(AuditWriter.class);
        List<StreamingEvent> events = new ArrayList<>();
        ToolCallbackAuditDecorator decorator = decorator(delegate, auditWriter, events);

        decorator.call("{}");

        StreamingEvent.ToolResult toolResult = events.stream()
                .filter(StreamingEvent.ToolResult.class::isInstance)
                .map(StreamingEvent.ToolResult.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(toolResult.toolName()).isEqualTo("find_records");
        assertThat(toolResult.payloadJson()).isNull();
        verify(auditWriter).writeToolCall(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                eq("alice"), org.mockito.ArgumentMatchers.any(), eq("find_records"),
                eq("{}"), eq(output), anyLong(), eq(AiToolCallOutcome.SUCCESS),
                isNull(), isNull());
    }

    private static ToolCallbackAuditDecorator decorator(ToolCallback delegate,
                                                        AuditWriter auditWriter,
                                                        List<StreamingEvent> events) {
        UUID runId = UUID.randomUUID();
        RunContext.set(runId);
        RunContext.setConversationId(UUID.randomUUID());
        StreamingSinkHolder sinkHolder = new StreamingSinkHolder();
        Sinks.Many<StreamingEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        sink.asFlux().subscribe(events::add);
        sinkHolder.register(runId, sink);

        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        when(currentAuthentication.getUser()).thenReturn(
                User.withUsername("alice").password("x").authorities("ROLE_USER").build());
        return new ToolCallbackAuditDecorator(delegate, auditWriter, currentAuthentication, sinkHolder);
    }

    private static ToolCallback callback(String toolName, String output) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(toolName)
                .description(toolName)
                .inputSchema("{}")
                .build());
        when(callback.call("{}")).thenReturn(output);
        return callback;
    }
}
