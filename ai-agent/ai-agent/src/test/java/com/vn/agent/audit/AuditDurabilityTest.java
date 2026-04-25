package com.vn.agent.audit;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
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
 * AUD-02 durability proof + Phase 7.2 Pitfall #1 regression guard. Two invariants:
 *
 * <ol>
 *   <li>{@link AuditWriter#writeToolCall} runs in its own REQUIRES_NEW transaction, so the row it
 *       writes MUST survive even when the enclosing (outer) transaction rolls back. This is the
 *       mechanical guarantee that tool-call audit rows are never lost when a tool throws and its
 *       own transaction is discarded.</li>
 *   <li>{@link AuditWriter#writeChatFinish} UPDATEs the root row with a children-less fetch plan
 *       so children INSERTed in separate REQUIRES_NEW transactions are NOT orphan-removed or
 *       re-saved (A7 assumption verification from RESEARCH §Pitfall #1).</li>
 * </ol>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
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
                auditWriter.writeToolCall(/*parentId*/ null, runId, "user-A", /*conversationId*/ null,
                        "echo", "{\"in\":1}", "{\"out\":1}", 5L,
                        AiToolCallOutcome.SUCCESS,
                        /*denialReason*/ null, /*errorClass*/ null);
                status.setRollbackOnly();
            });

            List<AiAuditEvent> rows = dataManager.load(AiAuditEvent.class)
                    .query("select a from ai_AiAuditEvent a where a.runId = :rid")
                    .parameter("rid", runId)
                    .list();

            assertThat(rows)
                    .as("REQUIRES_NEW must commit independently of the rolled-back outer tx (AUD-02)")
                    .hasSize(1);
            AiAuditEvent row = rows.get(0);
            assertThat(row.getKind()).isEqualTo(AuditKind.TOOL);
            assertThat(row.getEventName()).isEqualTo("echo");
            assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.SUCCESS);
            assertThat(row.getLatencyMs()).isEqualTo(5L);
        });
    }

    /**
     * Pitfall #1 regression guard (A7 verification — RESEARCH §Assumptions Log): inserting a root
     * via {@link AuditWriter#writeChatStart}, then inserting a TOOL child under that root via
     * {@link AuditWriter#writeToolCall} (separate REQUIRES_NEW tx — committed), then calling
     * {@link AuditWriter#writeChatFinish} (which UPDATEs the root) MUST NOT orphan-remove or
     * re-save the child. {@code writeChatFinish}'s fetch plan explicitly omits {@code children},
     * so the collection is never loaded and cascade/orphanRemoval semantics never trigger.
     *
     * <p>Sabotage verification: temporarily add {@code fp.add("children", ...)} to the
     * writeChatFinish fetch plan — this test MUST go red (the child version bumps on cascade
     * re-save, or the row is orphan-removed). Revert — this test MUST go green.</p>
     */
    @Test
    void writeChatFinish_doesNotOrphanChildren_writtenInSeparateRequiresNew() {
        systemAuthenticator.runWithSystem(() -> {
            UUID runId = UUID.randomUUID();

            // Arrange: insert root (REQUIRES_NEW — committed)
            UUID rootId = auditWriter.writeChatStart(runId, "user-A", /*conversationId*/ null, "hash");

            // Arrange: insert a TOOL child row in its own REQUIRES_NEW (committed before finish)
            UUID childId = auditWriter.writeToolCall(rootId, runId, "user-A", null, "echo",
                    "{}", "{}", 3L, AiToolCallOutcome.SUCCESS, null, null);

            // Sanity: child is visible BEFORE finish, via the composition collection
            AiAuditEvent beforeFinish = dataManager.load(AiAuditEvent.class)
                    .id(rootId)
                    .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("children", FetchPlan.BASE))
                    .one();
            assertThat(beforeFinish.getChildren()).hasSize(1);
            assertThat(beforeFinish.getChildren().get(0).getId()).isEqualTo(childId);

            // Act: finish the chat — this UPDATEs the root (children-less fetch plan; Pitfall #1)
            auditWriter.writeChatFinish(rootId, 50L, "SUCCESS", null);

            // Assert: child MUST still be there — not orphan-removed, not re-saved by cascade
            AiAuditEvent afterFinish = dataManager.load(AiAuditEvent.class)
                    .id(rootId)
                    .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("children", FetchPlan.BASE))
                    .one();
            assertThat(afterFinish.getChildren())
                    .as("writeChatFinish must NOT orphan children appended in separate REQUIRES_NEW tx (Pitfall #1)")
                    .hasSize(1);
            assertThat(afterFinish.getChildren().get(0).getId()).isEqualTo(childId);
            assertThat(afterFinish.getFinishedAt()).isNotNull();
            assertThat(afterFinish.getOutcomeRaw()).isEqualTo("SUCCESS");

            // And the child row itself is fully preserved — kind + eventName survive
            AiAuditEvent reloadedChild = dataManager.load(AiAuditEvent.class).id(childId).one();
            assertThat(reloadedChild.getKind()).isEqualTo(AuditKind.TOOL);
            assertThat(reloadedChild.getEventName()).isEqualTo("echo");
        });
    }
}
