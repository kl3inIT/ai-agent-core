package com.vn.agent.orchestration;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.ChatService;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiToolCallAudit;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end happy path through {@link ChatService} against the deterministic stub ChatModel.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Auto-creates an {@link AiConversation} when {@code conversationId == null}, titled with
 *       the first user message (D-08) and owned by the caller via {@code createdBy} (B3).</li>
 *   <li>Two asks on the same conversationId produce exactly 2 chat-level audit rows each
 *       (PRE + POST) keyed by {@code runId}, and all rows carry the conversation FK so
 *       {@code a.conversation.id = :cid} JPQL paths work end to end (Blocker 2 reverse check).</li>
 * </ul>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class OrchestrationIntegrationTest {

    @Autowired ChatService chatService;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void askWithoutConversationIdCreatesConversation() {
        systemAuthenticator.runWithSystem(() -> {
            var resp = chatService.ask("user-A", null, "hello");

            assertThat(resp.conversationId()).isNotNull();
            assertThat(resp.content()).startsWith("STUB:");
            assertThat(resp.runId()).isNotNull();

            AiConversation conv = dataManager.load(AiConversation.class)
                    .id(resp.conversationId()).one();
            // B3: ownership is createdBy, NOT userId.
            assertThat(conv.getCreatedBy()).isEqualTo("user-A");
            // B4 / D-08: title seeded from firstMessage truncated to 80 chars.
            assertThat(conv.getTitle()).isEqualTo("hello");
        });
    }

    @Test
    void askTwiceSameConversationProducesTwoChatAuditPairs() {
        systemAuthenticator.runWithSystem(() -> {
            var first = chatService.ask("user-A", null, "msg1");
            var second = chatService.ask("user-A", first.conversationId(), "msg2");

            // Count by runId (W11: locked at planning time, one runId per ask -> 2 rows PRE+POST).
            List<AiToolCallAudit> firstAskRows = dataManager.load(AiToolCallAudit.class)
                    .query("select a from ai_AiToolCallAudit a where a.kind = 'CHAT' and a.runId = :rid order by a.startedAt")
                    .parameter("rid", first.runId())
                    .list();
            List<AiToolCallAudit> secondAskRows = dataManager.load(AiToolCallAudit.class)
                    .query("select a from ai_AiToolCallAudit a where a.kind = 'CHAT' and a.runId = :rid order by a.startedAt")
                    .parameter("rid", second.runId())
                    .list();

            assertThat(firstAskRows).hasSize(2);
            assertThat(secondAskRows).hasSize(2);

            assertThat(firstAskRows).extracting(AiToolCallAudit::getPhase).containsExactly("PRE", "POST");
            assertThat(secondAskRows).extracting(AiToolCallAudit::getPhase).containsExactly("PRE", "POST");

            // Blocker 2 reverse check: BOTH PRE and POST chat rows carry the conversation FK.
            assertThat(firstAskRows).allSatisfy(r ->
                    assertThat(r.getConversation())
                            .isNotNull()
                            .extracting("id")
                            .isEqualTo(first.conversationId()));

            // Conversation-scoped JPQL path a.conversation.id works (NOT a.conversationId).
            List<AiToolCallAudit> byConv = dataManager.load(AiToolCallAudit.class)
                    .query("select a from ai_AiToolCallAudit a where a.kind = 'CHAT' and a.conversation.id = :cid order by a.startedAt")
                    .parameter("cid", first.conversationId())
                    .list();
            assertThat(byConv).hasSize(4);
        });
    }
}
