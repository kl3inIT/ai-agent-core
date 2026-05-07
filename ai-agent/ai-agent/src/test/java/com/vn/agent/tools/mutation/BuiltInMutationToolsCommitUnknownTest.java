package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * TEST-12 post-host-save finalization failure.
 *
 * <p>The real {@link MutationSaveExecutor} commits the host fixture row. A test-only
 * {@link MutationIntentRepository.MutationIntentFailureProbe} then throws from
 * {@code beforeMarkCommitted(...)} so the idempotency row cannot reach COMMITTED. The tool must
 * write a durable COMMIT_FAILED audit row, keep the intent non-reclaimable, and reject an exact
 * retry without duplicating the host write.
 */
@SpringBootTest(classes = {AITestConfiguration.class, MutationFixturePersistenceTestConfiguration.class},
        properties = {
                "ai-agent.tools.mutation.enabled=true",
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
class BuiltInMutationToolsCommitUnknownTest {

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
    private MutationIntentRepository.MutationIntentFailureProbe failureProbe;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String idempotencyKey;

    @BeforeEach
    void failBeforeMarkCommitted() {
        doThrow(new IllegalStateException("synthetic markCommitted failure"))
                .when(failureProbe)
                .beforeMarkCommitted(any(AiMutationIntent.class));
    }

    @AfterEach
    void cleanRows() {
        systemAuthenticator.runWithSystem(() -> {
            if (idempotencyKey != null) {
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :toolName and e.idempotencyKey = :key and e.userUsername = :user")
                        .parameter("toolName", "create_record")
                        .parameter("key", idempotencyKey)
                        .parameter("user", USERNAME)
                        .list()
                        .forEach(unconstrainedDataManager::remove);
            }
            unconstrainedDataManager.load(MutationTestFixture.class)
                    .query("select e from mutationTest_MutationTestFixture e where e.name = :name")
                    .parameter("name", "Commit unknown fixture")
                    .list()
                    .forEach(unconstrainedDataManager::remove);
            if (idempotencyKey != null) {
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.eventName = :eventName " +
                                "and a.argumentsJson like :key")
                        .parameter("eventName", "create_record")
                        .parameter("key", "%" + idempotencyKey + "%")
                        .list()
                        .forEach(unconstrainedDataManager::remove);
            }
        });
        idempotencyKey = null;
    }

    @Test
    void postHostSaveMarkCommittedFailure_writesCommitFailedAuditAndRetryDoesNotDuplicateHostRow()
            throws Exception {
        idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Commit unknown fixture");
        attributes.put("priority", 12);

        String firstJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes, idempotencyKey));

        JsonNode first = objectMapper.readTree(firstJson);
        assertThat(first.path("error").asText())
                .as("post-host-save finalization failure must use the stable commit-unknown code; raw=%s", firstJson)
                .isEqualTo("concurrent_modification");
        assertThat(first.path("expected").toString())
                .contains("do not retry automatically");

        List<MutationTestFixture> hostRows = loadHostRows();
        assertThat(hostRows)
                .as("the host fixture save returned before markCommitted failed")
                .hasSize(1);

        List<AiAuditEvent> auditRows = loadAuditRows(AiToolCallOutcome.COMMIT_FAILED);
        assertThat(auditRows)
                .as("exactly one durable COMMIT_FAILED audit row must be written for the first call")
                .hasSize(1);
        assertThat(auditRows.get(0).getOutcome()).isEqualTo(AiToolCallOutcome.COMMIT_FAILED);

        AiMutationIntent intent = loadIntent();
        assertThat(intent.getStatus())
                .as("commit-unknown rows are non-reclaimable: COMMIT_UNKNOWN or retained PENDING, never FAILED")
                .isIn(AiMutationIntentStatus.COMMIT_UNKNOWN, AiMutationIntentStatus.PENDING);
        assertThat(intent.getStatus()).isNotEqualTo(AiMutationIntentStatus.FAILED);

        String secondJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, new LinkedHashMap<>(attributes), idempotencyKey));
        JsonNode second = objectMapper.readTree(secondJson);
        assertThat(second.path("error").asText())
                .as("exact retry against a non-reclaimable row must not run a second host write; raw=%s", secondJson)
                .isEqualTo("concurrent_modification");

        assertThat(loadHostRows())
                .as("exact retry must not duplicate the host save")
                .hasSize(1);
    }

    private List<MutationTestFixture> loadHostRows() {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e where e.name = :name")
                        .parameter("name", "Commit unknown fixture")
                        .list());
    }

    private AiMutationIntent loadIntent() {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :toolName and e.idempotencyKey = :key and e.userUsername = :user")
                        .parameter("toolName", "create_record")
                        .parameter("key", idempotencyKey)
                        .parameter("user", USERNAME)
                        .one());
    }

    private List<AiAuditEvent> loadAuditRows(AiToolCallOutcome outcome) {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.eventName = :eventName " +
                                "and a.outcome = :outcome and a.argumentsJson like :key order by a.startedAt desc")
                        .parameter("eventName", "create_record")
                        .parameter("outcome", outcome)
                        .parameter("key", "%" + idempotencyKey + "%")
                        .list());
    }

}
