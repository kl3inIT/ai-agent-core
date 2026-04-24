package com.vn.agent.test_support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Test-only {@code @TestConfiguration} providing a deterministic {@link ChatModel} so Phase 4
 * orchestration tests never hit the real OpenRouter API. The stub echoes the last USER message
 * prefixed with {@code "STUB:"} so tests can assert both call-through and content preservation.
 *
 * <p>Marked {@code @Primary} to win over any auto-configured ChatModel bean (the starter's OpenAI
 * autoconfig, if accidentally pulled onto the test classpath, would otherwise produce a real
 * client). Tests tagged {@code @Tag("live")} must NOT import this config — they need the real bean.
 */
@TestConfiguration
public class StubChatModelConfiguration {

    @Bean
    @Primary
    public ChatModel stubChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                String lastUserText = extractLastUserText(prompt);
                AssistantMessage reply = new AssistantMessage("STUB:" + lastUserText);
                return new ChatResponse(List.of(new Generation(reply)));
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return ChatOptions.builder().model("stub/model").build();
            }
        };
    }

    private static String extractLastUserText(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message m = instructions.get(i);
            if (m.getMessageType() == MessageType.USER) {
                return m.getText();
            }
        }
        return instructions.isEmpty() ? "" : instructions.get(instructions.size() - 1).getText();
    }
}
