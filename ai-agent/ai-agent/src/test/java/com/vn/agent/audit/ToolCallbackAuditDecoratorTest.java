package com.vn.agent.audit;

import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.core.userdetails.User;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallbackAuditDecoratorTest {

    @AfterEach
    void clearRunContext() {
        RunContext.clear();
    }

    @Test
    void call_readsAuditCorrelationFromToolContext_whenThreadLocalIsEmpty() {
        UUID runId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();

        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("echo")
                .description("Echo")
                .inputSchema("{}")
                .build());
        when(delegate.call(eq("{\"x\":1}"), org.mockito.ArgumentMatchers.any(ToolContext.class))).thenReturn("ok");

        AuditWriter auditWriter = mock(AuditWriter.class);
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        when(currentAuthentication.getUser()).thenReturn(
                User.withUsername("alice").password("x").authorities("ROLE_USER").build());

        ToolCallbackAuditDecorator decorator =
                new ToolCallbackAuditDecorator(delegate, auditWriter, currentAuthentication);

        decorator.call("{\"x\":1}", new ToolContext(Map.of(
                RunContext.TOOL_CONTEXT_RUN_ID_KEY, runId,
                RunContext.TOOL_CONTEXT_CONVERSATION_ID_KEY, conversationId)));

        verify(auditWriter).writeToolCall(
                isNull(),
                eq(runId),
                eq("alice"),
                eq(conversationId),
                eq("echo"),
                eq("{\"x\":1}"),
                eq("ok"),
                anyLong(),
                eq(AiToolCallOutcome.SUCCESS),
                isNull(),
                isNull());
    }
}
