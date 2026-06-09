package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;

/**
 * Regression coverage for a pre-reservation mutation failure: Spring AI sees a normal tool
 * return, but operators still need a non-blank sanitized audit result and a safe host-log
 * marker to correlate the unexpected exception class.
 */
@SpringBootTest(classes = {AITestConfiguration.class, MutationFixturePersistenceTestConfiguration.class},
        properties = {
                "jmix.ai-agent.tools.mutation.enabled=true",
                "main.liquibase.change-log=com/vn/agent/test_liquibase/test-main-changelog.xml",
                "jmix.ai-agent.audit.hash-sensitive-fields=true",
                "jmix.ai-agent.audit.sensitive-fields=secret"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class BuiltInMutationToolsPreReservationFailureAuditTest {

    private static final String FIXTURE_ENTITY = "mutationTest_MutationTestFixture";
    private static final String USERNAME = "mutation-user";

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @MockitoBean
    private MutationRequestHasher mutationRequestHasher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String idempotencyKey;
    private String secretValue;

    @BeforeEach
    void failBeforeReservation() {
        secretValue = "pre-reservation-secret@example.test";
        doThrow(new IllegalArgumentException("synthetic hash failure with " + secretValue))
                .when(mutationRequestHasher)
                .hash(eq("create_record"), eq(FIXTURE_ENTITY), isNull(), isNull(), isNull(), anyMap());
    }

    @AfterEach
    void cleanRows() {
        if (idempotencyKey == null) {
            return;
        }
        systemAuthenticator.runWithSystem(() -> {
            unconstrainedDataManager.load(AiAuditEvent.class)
                    .query("select a from ai_AiAuditEvent a where a.eventName = :eventName " +
                            "and a.argumentsJson like :key")
                    .parameter("eventName", "create_record")
                    .parameter("key", "%" + idempotencyKey + "%")
                    .list()
                    .forEach(unconstrainedDataManager::remove);
            unconstrainedDataManager.load(AiMutationIntent.class)
                    .query("select e from aiMutation_AiMutationIntent e " +
                            "where e.toolName = :toolName and e.idempotencyKey = :key and e.userUsername = :user")
                    .parameter("toolName", "create_record")
                    .parameter("key", idempotencyKey)
                    .parameter("user", USERNAME)
                    .list()
                    .forEach(unconstrainedDataManager::remove);
        });
        idempotencyKey = null;
    }

    @Test
    void unexpectedPreReservationFailure_writesSanitizedAuditSummaryAndHostLogMarker(CapturedOutput output)
            throws Exception {
        idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Pre reservation failure fixture");
        attributes.put("secret", secretValue);

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes, idempotencyKey));

        JsonNode parsed = objectMapper.readTree(json);
        assertThat(parsed.path("error").asText()).isEqualTo("validation_failed");
        assertThat(countIntentRows()).isZero();

        List<AiAuditEvent> errorRows = loadAuditRows();
        assertThat(errorRows).hasSize(1);
        AiAuditEvent row = errorRows.stream().findFirst().orElseThrow();
        assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.ERROR);
        assertThat(row.getErrorClass()).isEqualTo(IllegalArgumentException.class.getName());
        assertThat(row.getDenialReason()).isNull();
        assertThat(row.getResultSummary())
                .contains("\"error\":\"validation_failed\"")
                .doesNotContain(secretValue)
                .doesNotContain("synthetic hash failure");

        assertThat(output.getOut() + output.getErr())
                .contains("AI_AGENT_MUTATION_TOOL_UNEXPECTED_FAILURE")
                .contains(IllegalArgumentException.class.getName())
                .doesNotContain(secretValue)
                .doesNotContain("synthetic hash failure")
                .doesNotContain(idempotencyKey);
    }

    private List<AiAuditEvent> loadAuditRows() {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.eventName = :eventName " +
                                "and a.outcome = :outcome and a.argumentsJson like :key")
                        .parameter("eventName", "create_record")
                        .parameter("outcome", AiToolCallOutcome.ERROR)
                        .parameter("key", "%" + idempotencyKey + "%")
                        .list());
    }

    private int countIntentRows() {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :toolName and e.idempotencyKey = :key and e.userUsername = :user")
                        .parameter("toolName", "create_record")
                        .parameter("key", idempotencyKey)
                        .parameter("user", USERNAME)
                        .list()
                        .size());
    }
}
