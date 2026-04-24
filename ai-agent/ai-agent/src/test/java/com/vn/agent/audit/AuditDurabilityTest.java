package com.vn.agent.audit;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiToolCallAudit;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.test_support.StubChatModelConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUD-02 durability proof: {@link AuditWriter#writeToolCall} runs in its own REQUIRES_NEW
 * transaction, so the row it writes MUST survive even when the enclosing (outer) transaction
 * rolls back. This is the mechanical guarantee that tool-call audit rows are never lost when a
 * tool throws and its own transaction is discarded.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import(StubChatModelConfiguration.class)
class AuditDurabilityTest {

    @Autowired AuditWriter auditWriter;
    @Autowired DataManager dataManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void toolAuditRowSurvivesOuterRollback() {
        UUID runId = UUID.randomUUID();

        systemAuthenticator.runWithSystem(() -> {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
            txTemplate.executeWithoutResult(status -> {
                auditWriter.writeToolCall(runId, "user-A", /*conversationId*/ null, "echo",
                        "{\"in\":1}", "{\"out\":1}", 5L,
                        AiToolCallOutcome.SUCCESS,
                        /*denialReason*/ null, /*errorClass*/ null, "POST");
                status.setRollbackOnly();
            });

            List<AiToolCallAudit> rows = dataManager.load(AiToolCallAudit.class)
                    .query("select a from ai_AiToolCallAudit a where a.runId = :rid")
                    .parameter("rid", runId)
                    .list();

            assertThat(rows)
                    .as("REQUIRES_NEW must commit independently of the rolled-back outer tx (AUD-02)")
                    .hasSize(1);
            AiToolCallAudit row = rows.get(0);
            assertThat(row.getKind()).isEqualTo("TOOL");
            assertThat(row.getPhase()).isEqualTo("POST");
            assertThat(row.getToolName()).isEqualTo("echo");
            assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.SUCCESS);
            assertThat(row.getLatencyMs()).isEqualTo(5L);
        });
    }
}
