package com.vn.agent.audit;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.spi.AuditListener;
import com.vn.agent.test_support.StubChatModelConfiguration;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPI-06 / D-13 proof: an {@link AuditListener} that throws MUST NOT prevent other listeners from
 * firing and MUST NOT fail the audit write. The D-14 afterCommit synchronization fires the fan-out
 * only after the REQUIRES_NEW audit transaction commits, so listeners observe durable rows.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, AuditListenerFanOutTest.TwoListeners.class})
class AuditListenerFanOutTest {

    @Autowired AuditWriter auditWriter;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired AtomicReference<UUID> recordedRef;

    @Test
    void throwingListenerDoesNotBlockOthers() {
        recordedRef.set(null);

        systemAuthenticator.runWithSystem(() -> {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.executeWithoutResult(status -> {
                auditWriter.writeToolCall(UUID.randomUUID(), "user-A", /*conversationId*/ null,
                        "echo", "{}", "{}", 1L,
                        AiToolCallOutcome.SUCCESS, /*denialReason*/ null, /*errorClass*/ null, "POST");
            });
        });

        // afterCommit has fired — recording listener must have been invoked despite throwing listener.
        assertThat(recordedRef.get())
                .as("Recording listener must fire even when a sibling listener throws")
                .isNotNull();
    }

    @TestConfiguration
    static class TwoListeners {

        @Bean
        AtomicReference<UUID> recordedRef() {
            return new AtomicReference<>();
        }

        @Bean
        AuditListener throwingListener() {
            return id -> {
                throw new RuntimeException("boom");
            };
        }

        @Bean
        AuditListener recordingListener(AtomicReference<UUID> recordedRef) {
            return id -> recordedRef.set(id);
        }
    }
}
