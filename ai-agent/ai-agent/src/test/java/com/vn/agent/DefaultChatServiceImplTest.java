package com.vn.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultChatServiceImpl}. Verifies constructor wiring and that
 * {@code ask(...)} calls the Spring AI fluent chain and echoes the conversationId in metadata.
 */
class DefaultChatServiceImplTest {

    @Test
    void askReturnsContentFromChatClient() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("hello from mock");

        DefaultChatServiceImpl svc = new DefaultChatServiceImpl(builder);
        UUID convId = UUID.randomUUID();

        ChatResponse resp = svc.ask("hi", convId, null);

        assertEquals("hello from mock", resp.content());
        assertNotNull(resp.metadata());
        assertEquals(convId.toString(), resp.metadata().get("conversationId"));
    }

    @Test
    void askHandlesNullContentFromClient() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(null);

        DefaultChatServiceImpl svc = new DefaultChatServiceImpl(builder);

        ChatResponse resp = svc.ask("hi", UUID.randomUUID(), "userA");

        assertEquals("", resp.content());
    }
}
