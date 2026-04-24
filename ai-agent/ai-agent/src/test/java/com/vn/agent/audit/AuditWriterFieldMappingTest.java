package com.vn.agent.audit;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiToolCallAudit;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.test_support.StubChatModelConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W14 - Field mapping regression guard. Proves:
 *
 * <ul>
 *   <li>{@link AuditWriter#writeChatPre} and {@link AuditWriter#writeToolCall} persist rows that,
 *       round-tripped through {@link DataManager}, return exactly the values passed in via the
 *       real {@link AiToolCallAudit} getters (runId, kind, phase, userUsername, toolName,
 *       argumentsJson, resultSummary, outcome, latencyMs, startedAt, finishedAt, promptHash,
 *       denialReason, conversation FK).</li>
 *   <li>The entity exposes NO legacy accessor names from the B1 bug — {@code inputJson},
 *       {@code outputJson}, {@code success}, {@code errorMessage}, {@code userId},
 *       {@code createdBy}, {@code createdAt}, or {@code conversationId} as a scalar setter.
 *       Reflective check catches any future re-introduction.</li>
 * </ul>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import(StubChatModelConfiguration.class)
class AuditWriterFieldMappingTest {

    @Autowired AuditWriter auditWriter;
    @Autowired DataManager dataManager;
    @Autowired Metadata metadata;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void writeChatPre_persists_with_real_field_set_only() {
        systemAuthenticator.runWithSystem(() -> {
            UUID runId = UUID.randomUUID();
            UUID convId = createTestConversation("user-A");

            UUID auditId = auditWriter.writeChatPre(runId, "user-A", convId, "abc123hash");

            AiToolCallAudit row = dataManager.load(AiToolCallAudit.class).id(auditId).one();
            assertThat(row.getRunId()).isEqualTo(runId);
            assertThat(row.getKind()).isEqualTo("CHAT");
            assertThat(row.getPhase()).isEqualTo("PRE");
            assertThat(row.getUserUsername()).isEqualTo("user-A");
            assertThat(row.getToolName()).isEqualTo(AuditWriter.CHAT_TOOL_NAME_SENTINEL);
            assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.SUCCESS);
            assertThat(row.getStartedAt()).isNotNull();
            assertThat(row.getPromptHash()).isEqualTo("abc123hash");
            assertThat(row.getConversation())
                    .isNotNull()
                    .extracting("id")
                    .isEqualTo(convId);
        });
    }

    @Test
    void writeToolCall_persists_with_real_field_set_only() {
        systemAuthenticator.runWithSystem(() -> {
            UUID runId = UUID.randomUUID();
            UUID convId = createTestConversation("user-A");

            UUID auditId = auditWriter.writeToolCall(runId, "user-A", convId, "echo",
                    "{\"in\":1}", "{\"out\":1}", 12L,
                    AiToolCallOutcome.SUCCESS, null, null, "POST");

            AiToolCallAudit row = dataManager.load(AiToolCallAudit.class).id(auditId).one();
            assertThat(row.getRunId()).isEqualTo(runId);
            assertThat(row.getKind()).isEqualTo("TOOL");
            assertThat(row.getPhase()).isEqualTo("POST");
            assertThat(row.getUserUsername()).isEqualTo("user-A");
            assertThat(row.getToolName()).isEqualTo("echo");
            // Real fields are argumentsJson / resultSummary (NOT the legacy inputJson / outputJson).
            assertThat(row.getArgumentsJson()).contains("\"in\"");
            assertThat(row.getResultSummary()).contains("\"out\"");
            assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.SUCCESS);
            assertThat(row.getLatencyMs()).isEqualTo(12L);
            assertThat(row.getStartedAt()).isNotNull();
            assertThat(row.getFinishedAt()).isNotNull(); // POST sets finishedAt
            assertThat(row.getConversation()).isNotNull().extracting("id").isEqualTo(convId);
        });
    }

    @Test
    void writeToolCall_with_BLOCKED_outcome_persists_denialReason() {
        systemAuthenticator.runWithSystem(() -> {
            UUID convId = createTestConversation("user-A");
            UUID auditId = auditWriter.writeToolCall(UUID.randomUUID(), "user-A", convId,
                    "createOrder", "{}", null, 3L,
                    AiToolCallOutcome.BLOCKED, "Insufficient role: ADMIN required", null, "POST");

            AiToolCallAudit row = dataManager.load(AiToolCallAudit.class).id(auditId).one();
            assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.BLOCKED);
            assertThat(row.getDenialReason()).isEqualTo("Insufficient role: ADMIN required");
        });
    }

    @Test
    void entity_does_not_have_legacy_field_names() {
        // AiToolCallAudit specifically does NOT extend Auditable — it has its own startedAt/finishedAt
        // timeline. Any future change introducing these getter/setter names must force a deliberate
        // code review of AuditWriter (which only sets the real field names).
        List<String> forbidden = List.of(
                "getInputJson", "getOutputJson", "getSuccess",
                "getErrorMessage", "getUserId", "getCreatedBy", "getCreatedAt",
                "setInputJson", "setOutputJson", "setSuccess",
                "setErrorMessage", "setUserId", "setCreatedBy", "setCreatedAt",
                "setConversationId");
        Set<String> actual = Arrays.stream(AiToolCallAudit.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        for (String f : forbidden) {
            assertThat(actual)
                    .as("Legacy field %s must not appear on AiToolCallAudit (B1 regression guard)", f)
                    .doesNotContain(f);
        }
    }

    private UUID createTestConversation(String owner) {
        AiConversation conv = metadata.create(AiConversation.class);
        conv.setCreatedBy(owner);
        conv.setCreatedDate(OffsetDateTime.now());
        conv.setTitle("test");
        return dataManager.save(conv).getId();
    }
}
