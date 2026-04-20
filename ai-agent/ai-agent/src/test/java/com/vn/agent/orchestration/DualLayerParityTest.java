package com.vn.agent.orchestration;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.ChatService;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-08 dual-layer parity proof: after a {@link ChatService#ask} round-trip, the Spring AI primary
 * store ({@link JdbcChatMemoryRepository} -> {@code SPRING_AI_CHAT_MEMORY}) and the Jmix projection
 * ({@link AiMessage} rows in {@code AI_AGENT_MESSAGE}) MUST contain the same messages in the same
 * order. {@link ProjectingChatMemoryRepository} writes both layers in the SAME REQUIRED transaction;
 * any drift surfaces here.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class DualLayerParityTest {

    @Autowired ChatService chatService;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator systemAuthenticator;
    /** Raw JDBC repo (not the @Primary ProjectingChatMemoryRepository) — injected by concrete type. */
    @Autowired JdbcChatMemoryRepository jdbcChatMemoryRepository;

    @Test
    void afterAskBothLayersHaveSameMessageCount() {
        systemAuthenticator.runWithSystem(() -> {
            var resp = chatService.ask("user-A", null, "hello");

            List<Message> springRows = jdbcChatMemoryRepository.findByConversationId(
                    resp.conversationId().toString());
            List<AiMessage> jmixRows = dataManager.load(AiMessage.class)
                    .query("select m from ai_AiMessage m where m.conversation.id = :cid order by m.createdDate")
                    .parameter("cid", resp.conversationId())
                    .list();

            assertThat(jmixRows)
                    .as("Jmix projection row count must equal Spring AI primary-store row count (D-08)")
                    .hasSize(springRows.size());
            assertThat(jmixRows).isNotEmpty();

            for (int i = 0; i < jmixRows.size(); i++) {
                AiMessage jmix = jmixRows.get(i);
                Message spring = springRows.get(i);
                // Jmix role enum id is uppercase (USER/ASSISTANT/SYSTEM/TOOL) — matches MessageType#name().
                assertThat(jmix.getRole())
                        .as("role of projected row %s must match Spring AI MessageType", i)
                        .isNotNull();
                assertThat(jmix.getRole().getId()).isEqualTo(spring.getMessageType().name());
                assertThat(jmix.getContent()).isEqualTo(spring.getText());
            }
        });
    }
}
