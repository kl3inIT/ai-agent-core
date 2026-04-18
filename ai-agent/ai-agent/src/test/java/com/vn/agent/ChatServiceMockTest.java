package com.vn.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 unit test: verify {@link DefaultChatServiceImpl} wraps a mocked {@link ChatModel}
 * response into our {@link ChatResponse} DTO.
 *
 * <p>Pattern per D-04 in 01-CONTEXT.md: {@code Mockito.mock(ChatModel.class)}. Pure unit
 * test (no {@code @SpringBootTest}, no live tag), mirrors jmix-ai-backend's
 * {@code RerankerTest} Mockito-on-model pattern.</p>
 *
 * <p>Complements the existing {@link DefaultChatServiceImplTest} (plan 01-02), which mocks
 * at the {@code ChatClient.Builder} layer. This class mocks one layer deeper at the
 * {@link ChatModel} boundary, exercising the real {@code ChatClient} fluent chain end-to-end
 * against a fake transport.</p>
 */
class ChatServiceMockTest {

    @Test
    void askWrapsChatClientContentIntoChatResponse() {
        ChatModel mockModel = Mockito.mock(ChatModel.class);

        // Stub the mocked ChatModel to return a canned response for any Prompt.
        org.springframework.ai.chat.model.ChatResponse cannedResponse =
                new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("hello from mock"))));

        Mockito.when(mockModel.call(ArgumentMatchers.any(Prompt.class)))
                .thenReturn(cannedResponse);

        ChatClient.Builder builder = ChatClient.builder(mockModel);
        DefaultChatServiceImpl svc = new DefaultChatServiceImpl(builder);

        UUID convId = UUID.randomUUID();
        ChatResponse response = svc.ask("hi", convId, null);

        assertThat(response.content()).isEqualTo("hello from mock");
        assertThat(response.metadata())
                .containsEntry("conversationId", convId.toString());
    }

    @Test
    void askAcceptsNullUserKey() {
        ChatModel mockModel = Mockito.mock(ChatModel.class);
        Mockito.when(mockModel.call(ArgumentMatchers.any(Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                        List.of(new Generation(new AssistantMessage("ok")))));

        DefaultChatServiceImpl svc = new DefaultChatServiceImpl(ChatClient.builder(mockModel));

        // D-03: userKey accepts null in Phase 1 (anonymous flow).
        ChatResponse response = svc.ask("hi", UUID.randomUUID(), null);
        assertThat(response.content()).isEqualTo("ok");
    }
}
