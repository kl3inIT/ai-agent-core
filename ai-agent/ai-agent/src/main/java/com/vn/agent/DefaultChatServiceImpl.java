package com.vn.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 1 stub impl. Calls {@link ChatClient#prompt()}{@code .user(message).call().content()} and
 * wraps the result into a {@link ChatResponse}. No advisors, no memory, no tools, no RAG — per D-03
 * in 01-CONTEXT.md. Future phases swap via {@code @ConditionalOnMissingBean}.
 */
@Service
public class DefaultChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    // CLAUDE.md: constructor injection only.
    public DefaultChatServiceImpl(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public ChatResponse ask(String message, UUID conversationId, String userKey) {
        String content = chatClient.prompt()
                .user(message)
                .call()
                .content();
        return new ChatResponse(
                content == null ? "" : content,
                Map.of("conversationId", String.valueOf(conversationId))
        );
    }
}
