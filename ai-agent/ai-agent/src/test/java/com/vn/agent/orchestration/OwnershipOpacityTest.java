package com.vn.agent.orchestration;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.ChatService;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * D-09 opacity proof: the exception thrown by {@link ConversationGateway#loadOrCreate(String, UUID, String)}
 * MUST be indistinguishable between (a) a conversationId that exists but is owned by another user
 * and (b) a conversationId that does not exist at all. Any divergence would let attackers probe
 * conversation-id existence.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class OwnershipOpacityTest {

    @Autowired ChatService chatService;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void crossUserProbeReturnsSameExceptionAsMissingId() {
        systemAuthenticator.runWithSystem(() -> {
            var userA = chatService.ask("user-A", null, "hello");
            UUID nonExistent = UUID.randomUUID();

            ConversationNotFoundException eA = catchThrowableOfType(
                    () -> chatService.ask("user-B", userA.conversationId(), "probe"),
                    ConversationNotFoundException.class);
            ConversationNotFoundException eB = catchThrowableOfType(
                    () -> chatService.ask("user-B", nonExistent, "probe"),
                    ConversationNotFoundException.class);

            assertThat(eA).isNotNull();
            assertThat(eB).isNotNull();
            assertThat(eA.getClass()).isEqualTo(eB.getClass());
            assertThat(eA.getMessage()).isEqualTo(eB.getMessage());
            assertThat(eA.getMessage()).isEqualTo(ConversationNotFoundException.DEFAULT_MESSAGE);
        });
    }
}
