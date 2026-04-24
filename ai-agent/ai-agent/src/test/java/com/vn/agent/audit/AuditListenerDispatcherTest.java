package com.vn.agent.audit;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.spi.AuditListener;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPI-06 / D-13 / D-05 proofs for the tree-lite {@link AuditListener} SPI. Asserts throwing-listener
 * isolation, fan-out on CHAT finish + TOOL + RETRIEVAL with the correct kind tag, and that CHAT
 * start does NOT fan out (D-05 — listeners only see completed roots).
 *
 * <p>The D-14 afterCommit synchronization fires fan-out only after the REQUIRES_NEW audit
 * transaction commits, so listeners observe durable rows.</p>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
@TestPropertySource(properties = "ai-agent.test.fanout-listeners=true")
class AuditListenerDispatcherTest {

    @Autowired AuditWriter auditWriter;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired CapturingListener capturingListener;

    @BeforeEach
    void resetListener() {
        capturingListener.events.clear();
    }

    @Test
    void throwingListenerDoesNotBlockOthers() {
        systemAuthenticator.runWithSystem(() -> {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.executeWithoutResult(status -> {
                auditWriter.writeToolCall(null, UUID.randomUUID(), "user-A",
                        null, "echo", "{}", "{}", 1L,
                        AiToolCallOutcome.SUCCESS, null, null);
            });
        });

        assertThat(capturingListener.events)
                .as("Recording listener must fire even when a sibling listener throws")
                .hasSize(1);
        assertThat(capturingListener.events.get(0).kind).isEqualTo(AuditKind.TOOL);
    }

    @Test
    void fanOut_firesForChatFinish() {
        systemAuthenticator.runWithSystem(() -> {
            UUID runId = UUID.randomUUID();
            UUID rootId = auditWriter.writeChatStart(runId, "user-A", null, "h");
            assertThat(capturingListener.events)
                    .as("writeChatStart MUST NOT fan out (D-05)")
                    .isEmpty();
            auditWriter.writeChatFinish(rootId, 10L, "SUCCESS", null);
            assertThat(capturingListener.events).hasSize(1);
            assertThat(capturingListener.events.get(0).kind).isEqualTo(AuditKind.CHAT);
            assertThat(capturingListener.events.get(0).auditId).isEqualTo(rootId);
        });
    }

    @Test
    void fanOut_firesForToolCall() {
        systemAuthenticator.runWithSystem(() -> {
            UUID runId = UUID.randomUUID();
            UUID toolId = auditWriter.writeToolCall(null, runId, "user-A", null, "echo",
                    "{}", "{}", 2L, AiToolCallOutcome.SUCCESS, null, null);
            assertThat(capturingListener.events).hasSize(1);
            assertThat(capturingListener.events.get(0).kind).isEqualTo(AuditKind.TOOL);
            assertThat(capturingListener.events.get(0).auditId).isEqualTo(toolId);
        });
    }

    @Test
    void fanOut_firesForRetrieval() {
        systemAuthenticator.runWithSystem(() -> {
            UUID runId = UUID.randomUUID();
            UUID rootId = auditWriter.writeChatStart(runId, "user-A", null, "h");
            capturingListener.events.clear();
            UUID retrId = auditWriter.writeRetrieval(rootId, runId, "user-A", null,
                    "what is foo?", 5, 3, 0.87, "docType == 'faq'", 12L, "SUCCESS", null);
            assertThat(capturingListener.events).hasSize(1);
            assertThat(capturingListener.events.get(0).kind).isEqualTo(AuditKind.RETRIEVAL);
            assertThat(capturingListener.events.get(0).auditId).isEqualTo(retrId);
        });
    }

    @Test
    void fanOut_doesNotFireForChatStart() {
        systemAuthenticator.runWithSystem(() -> {
            auditWriter.writeChatStart(UUID.randomUUID(), "user-A", null, "h");
            assertThat(capturingListener.events)
                    .as("writeChatStart MUST NOT fan out (D-05)")
                    .isEmpty();
        });
    }

    static final class CapturingListener implements AuditListener {
        record Event(UUID auditId, String kind) {}
        final List<Event> events = new ArrayList<>();
        @Override
        public void onEventAudited(UUID auditId, String kind) {
            events.add(new Event(auditId, kind));
        }
    }

    @TestConfiguration
    @ConditionalOnProperty(name = "ai-agent.test.fanout-listeners", havingValue = "true")
    static class TwoListeners {

        @Bean
        CapturingListener capturingListener() {
            return new CapturingListener();
        }

        @Bean
        AuditListener throwingListener() {
            return (id, kind) -> {
                throw new RuntimeException("boom");
            };
        }
    }
}
