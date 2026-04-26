package com.vn.agent.audit;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.lang.NonNull;
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
    @Autowired CurrentAuthentication currentAuthentication;

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

    /**
     * R-02b/c: ERROR-path durability through the production decorator.
     *
     * <p>Existing {@link #toolAuditRowSurvivesOuterRollback()} asserts SUCCESS-outcome audit
     * survives outer rollback via a DIRECT {@link AuditWriter#writeToolCall} call. This test
     * exercises the ERROR-outcome path through {@link ToolCallbackAuditDecorator} — the
     * production wiring — to prove that the decorator's {@code finally}-block audit write is
     * also routed through the REQUIRES_NEW writer and survives outer rollback (R-02b).</p>
     *
     * <p>R-02d: also verifies that the surviving child row carries the correct
     * {@code parent_id} linkage (to the CHAT root) and {@code runId} consistency.</p>
     */
    @Test
    @DisplayName("R-02b/c: ERROR-path tool audit (via ToolCallbackAuditDecorator) also survives outer rollback")
    void errorPathToolAuditAlsoSurvivesOuterRollback_viaDecorator() {
        UUID runId = UUID.randomUUID();
        String stubErrorMessage = "boom-from-throwing-tool-stub";

        systemAuthenticator.runWithSystem(() -> {
            // Arrange: write a CHAT root in its own REQUIRES_NEW so the child has a parent_id
            // (R-02d). This commit is independent of the outer-tx-that-rolls-back below.
            UUID rootId = auditWriter.writeChatStart(runId, "user-A", /*conversationId*/ null, "hash");

            // Set RunContext so the decorator picks up runId + parentId, mirroring the
            // production AuditAdvisor → ToolCallbackAuditDecorator handshake (D-10).
            RunContext.set(runId);
            RunContext.setRootAuditId(rootId);
            try {
                // Stub callback whose call(...) always throws — drives the decorator's
                // catch + finally audit-then-rethrow path (ERROR outcome).
                ToolCallback throwingCallback = new ToolCallback() {
                    @Override @NonNull
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                                .name("throwing-stub")
                                .description("Always throws — used to exercise decorator ERROR path")
                                .inputSchema("{}")
                                .build();
                    }
                    @Override @NonNull
                    public String call(@NonNull String toolInput) {
                        throw new IllegalStateException(stubErrorMessage);
                    }
                    @Override @NonNull
                    public String call(@NonNull String toolInput, ToolContext toolContext) {
                        throw new IllegalStateException(stubErrorMessage);
                    }
                };

                // Production decorator wired with the real REQUIRES_NEW AuditWriter proxy.
                ToolCallbackAuditDecorator decorator = new ToolCallbackAuditDecorator(
                        throwingCallback, auditWriter, currentAuthentication);

                // Act: invoke the decorator inside an outer tx that rolls back. The stub throw
                // propagates out of the decorator (after the finally writes the audit), Spring
                // marks the outer tx as rollback-only, and TransactionTemplate rethrows.
                TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
                try {
                    txTemplate.executeWithoutResult(status -> decorator.call("{}"));
                } catch (IllegalStateException expected) {
                    assertThat(expected).hasMessage(stubErrorMessage);
                }
            } finally {
                RunContext.clear();
            }

            // Assert: AUD-02 — REQUIRES_NEW commit independent of outer rollback (R-02b).
            // Filter to TOOL kind because writeChatStart already wrote a CHAT row under runId.
            List<AiAuditEvent> rows = dataManager.load(AiAuditEvent.class)
                    .query("select a from ai_AiAuditEvent a where a.runId = :rid and a.kind = :k")
                    .parameter("rid", runId)
                    .parameter("k", AuditKind.TOOL)
                    .list();

            assertThat(rows)
                    .as("R-02b: TOOL audit row written via ToolCallbackAuditDecorator MUST survive outer rollback")
                    .hasSize(1);

            AiAuditEvent surviving = rows.get(0);

            // Outcome + errorClass: ERROR path produces a populated errorClass (FQN of the throw).
            assertThat(surviving.getOutcome()).isEqualTo(AiToolCallOutcome.ERROR);
            assertThat(surviving.getErrorClass())
                    .as("errorClass populated on ERROR-path audit (decorator captures exception class FQN)")
                    .isEqualTo(IllegalStateException.class.getName());
            assertThat(surviving.getEventName()).isEqualTo("throwing-stub");

            // R-02d: parent linkage + runId consistency on the surviving child.
            // Reload with a parent fetch plan so the lazy ManyToOne is materialized safely.
            AiAuditEvent reloaded = dataManager.load(AiAuditEvent.class)
                    .id(surviving.getId())
                    .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("parent", FetchPlan.BASE))
                    .one();
            assertThat(reloaded.getParent())
                    .as("R-02d: surviving child must reference the CHAT root parent")
                    .isNotNull();
            assertThat(reloaded.getParent().getId())
                    .as("R-02d: parent_id must equal the writeChatStart-returned root id")
                    .isEqualTo(rootId);
            assertThat(reloaded.getRunId())
                    .as("R-02d: child runId must equal the chat-start runId")
                    .isEqualTo(runId);
        });
    }
}
