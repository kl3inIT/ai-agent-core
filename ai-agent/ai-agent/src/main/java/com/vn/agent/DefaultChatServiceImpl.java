package com.vn.agent;

import com.vn.agent.orchestration.ChatResponseDto;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase-4 transitional stub — Task 1 of Plan 04-04 updated the {@link ChatService} interface to
 * the new {@code ChatResponseDto} surface; the full orchestration body (cached client + baseline
 * composition + B8 runId pre-allocation + dual-layer persistence) is wired in Task 3.
 */
@Service
public class DefaultChatServiceImpl implements ChatService {

    private final ChatClient chatClient;

    public DefaultChatServiceImpl(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public ChatResponseDto ask(String userId, UUID conversationId, String message) {
        String content = chatClient.prompt()
                .user(message)
                .call()
                .content();
        return new ChatResponseDto(
                conversationId,
                UUID.randomUUID(),
                content == null ? "" : content,
                "unknown",
                0L);
    }
}
