package com.vn.agent.audit;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7.2 — Field-mapping regression guard for the tree-lite {@link AiAuditEvent} rewrite. Proves:
 *
 * <ul>
 *   <li>{@link AuditWriter#writeChatStart} INSERTs the mutable root — {@code outcome},
 *       {@code eventName}, {@code finishedAt} remain {@code null} until
 *       {@link AuditWriter#writeChatFinish} closes the run (D-01 invariant).</li>
 *   <li>{@link AuditWriter#writeChatFinish} then populates {@code outcome}, {@code latencyMs}, and
 *       {@code finishedAt} in place on the same row id (children-less fetch plan — Pitfall #1).</li>
 *   <li>{@link AuditWriter#writeToolCall} persists TOOL children with the new signature
 *       (parentId first, no PHASE literal) and carries {@code argumentsJson}, {@code resultSummary},
 *       {@code latencyMs}, and {@code denialReason} round-trip.</li>
 * </ul>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class AuditWriterFieldMappingTest {

    @Autowired AuditWriter auditWriter;
    @Autowired DataManager dataManager;
    @Autowired Metadata metadata;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void writeChatStart_thenFinish_persistsMutableRootInvariants() {
        systemAuthenticator.runWithSystem(() -> {
            UUID runId = UUID.randomUUID();
            UUID convId = createTestConversation("user-A");

            // --- Phase 1: writeChatStart — start-time invariants (D-01 mutable root) ---
            UUID auditId = auditWriter.writeChatStart(runId, "user-A", convId, "abc123hash");
            AiAuditEvent started = dataManager.load(AiAuditEvent.class).id(auditId).one();
            assertThat(started.getRunId()).isEqualTo(runId);
            assertThat(started.getKind()).isEqualTo(AuditKind.CHAT);
            assertThat(started.getEventName()).isNull();                 // no <chat> sentinel
            assertThat(started.getOutcomeRaw()).isNull();                // outcome null until writeChatFinish
            assertThat(started.getOutcome()).isNull();
            assertThat(started.getFinishedAt()).isNull();                // finishedAt null until finish
            assertThat(started.getParent()).isNull();                    // root has no parent
            assertThat(started.getStartedAt()).isNotNull();
            assertThat(started.getUserUsername()).isEqualTo("user-A");
            assertThat(started.getPromptHash()).isEqualTo("abc123hash");
            assertThat(started.getConversation())
                    .isNotNull()
                    .extracting("id")
                    .isEqualTo(convId);

            // --- Phase 2: writeChatFinish — UPDATE in place, same id, now populated ---
            auditWriter.writeChatFinish(auditId, 42L, "SUCCESS", null);
            AiAuditEvent finished = dataManager.load(AiAuditEvent.class).id(auditId).one();
            assertThat(finished.getId()).isEqualTo(auditId);              // same row
            assertThat(finished.getOutcomeRaw()).isEqualTo("SUCCESS");
            assertThat(finished.getFinishedAt()).isNotNull();
            assertThat(finished.getLatencyMs()).isEqualTo(42L);
            assertThat(finished.getErrorClass()).isNull();
            // Preserved across the UPDATE:
            assertThat(finished.getKind()).isEqualTo(AuditKind.CHAT);
            assertThat(finished.getUserUsername()).isEqualTo("user-A");
            assertThat(finished.getPromptHash()).isEqualTo("abc123hash");
        });
    }

    @Test
    void writeToolCall_persists_with_real_field_set_only() {
        systemAuthenticator.runWithSystem(() -> {
            UUID runId = UUID.randomUUID();
            UUID convId = createTestConversation("user-A");

            UUID auditId = auditWriter.writeToolCall(/*parentId*/ null, runId, "user-A", convId,
                    "echo", "{\"in\":1}", "{\"out\":1}", 12L,
                    AiToolCallOutcome.SUCCESS, /*denialReason*/ null, /*errorClass*/ null);

            AiAuditEvent row = dataManager.load(AiAuditEvent.class).id(auditId).one();
            assertThat(row.getRunId()).isEqualTo(runId);
            assertThat(row.getKind()).isEqualTo(AuditKind.TOOL);
            assertThat(row.getEventName()).isEqualTo("echo");             // D-01: tool name goes to eventName
            assertThat(row.getUserUsername()).isEqualTo("user-A");
            // Real fields are argumentsJson / resultSummary (NOT the legacy inputJson / outputJson).
            assertThat(row.getArgumentsJson()).contains("\"in\"");
            assertThat(row.getResultSummary()).contains("\"out\"");
            assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.SUCCESS);
            assertThat(row.getLatencyMs()).isEqualTo(12L);
            assertThat(row.getStartedAt()).isNotNull();
            assertThat(row.getFinishedAt()).isNotNull();                 // D-01: children stamped at insert
            assertThat(row.getConversation()).isNotNull().extracting("id").isEqualTo(convId);
        });
    }

    @Test
    void writeToolCall_with_BLOCKED_outcome_persists_denialReason() {
        systemAuthenticator.runWithSystem(() -> {
            UUID convId = createTestConversation("user-A");
            UUID auditId = auditWriter.writeToolCall(/*parentId*/ null, UUID.randomUUID(),
                    "user-A", convId, "createOrder", "{}", null, 3L,
                    AiToolCallOutcome.BLOCKED, "Insufficient role: ADMIN required", null);

            AiAuditEvent row = dataManager.load(AiAuditEvent.class).id(auditId).one();
            assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.BLOCKED);
            assertThat(row.getDenialReason()).isEqualTo("Insufficient role: ADMIN required");
        });
    }

    private UUID createTestConversation(String owner) {
        AiConversation conv = metadata.create(AiConversation.class);
        conv.setCreatedBy(owner);
        conv.setCreatedDate(OffsetDateTime.now());
        conv.setTitle("test");
        return dataManager.save(conv).getId();
    }
}
