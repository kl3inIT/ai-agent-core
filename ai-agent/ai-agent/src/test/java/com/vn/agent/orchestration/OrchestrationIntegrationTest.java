package com.vn.agent.orchestration;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.ChatService;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.spi.AuditKind;
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
 * Updated for Phase 7.2 tree-lite: each ask produces ONE root CHAT {@link AiAuditEvent} row
 * (writeChatStart INSERT + writeChatFinish UPDATE on the same id), not a PRE/POST pair.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Auto-creates an {@link AiConversation} when {@code conversationId == null}, titled with
 *       the first user message (D-08) and owned by the caller via {@code createdBy} (B3).</li>
 *   <li>Two asks on the same conversationId produce exactly 2 CHAT root rows (one per runId)
 *       carrying the conversation FK so {@code a.conversation.id = :cid} JPQL paths work end
 *       to end (Blocker 2 reverse check), with finishedAt populated after writeChatFinish.</li>
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
    void askTwiceSameConversationProducesTwoChatRootRows() {
        systemAuthenticator.runWithSystem(() -> {
            var first = chatService.ask("user-A", null, "msg1");
            var second = chatService.ask("user-A", first.conversationId(), "msg2");

            // Phase 7.2: one CHAT root per runId (writeChatStart + writeChatFinish UPDATE same row).
            List<AiAuditEvent> firstAskRows = dataManager.load(AiAuditEvent.class)
                    .query("select a from ai_AiAuditEvent a where a.kind = :kind and a.runId = :rid order by a.startedAt")
                    .parameter("kind", AuditKind.CHAT)
                    .parameter("rid", first.runId())
                    .list();
            List<AiAuditEvent> secondAskRows = dataManager.load(AiAuditEvent.class)
                    .query("select a from ai_AiAuditEvent a where a.kind = :kind and a.runId = :rid order by a.startedAt")
                    .parameter("kind", AuditKind.CHAT)
                    .parameter("rid", second.runId())
                    .list();

            assertThat(firstAskRows).hasSize(1);
            assertThat(secondAskRows).hasSize(1);

            // Post-finish: each root has finishedAt + outcome populated, parent is null.
            assertThat(firstAskRows).allSatisfy(r -> {
                assertThat(r.getParent()).isNull();
                assertThat(r.getFinishedAt()).isNotNull();
                assertThat(r.getOutcomeRaw()).isNotNull();
            });
            assertThat(secondAskRows).allSatisfy(r -> {
                assertThat(r.getParent()).isNull();
                assertThat(r.getFinishedAt()).isNotNull();
            });

            // Blocker 2 reverse check: CHAT root rows carry the conversation FK.
            assertThat(firstAskRows).allSatisfy(r ->
                    assertThat(r.getConversation())
                            .isNotNull()
                            .extracting("id")
                            .isEqualTo(first.conversationId()));

            // Conversation-scoped JPQL path a.conversation.id works (NOT a.conversationId).
            List<AiAuditEvent> byConv = dataManager.load(AiAuditEvent.class)
                    .query("select a from ai_AiAuditEvent a where a.kind = :kind and a.conversation.id = :cid order by a.startedAt")
                    .parameter("kind", AuditKind.CHAT)
                    .parameter("cid", first.conversationId())
                    .list();
            assertThat(byConv).hasSize(2);
        });
    }
}
