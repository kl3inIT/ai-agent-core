package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * TEST-12 post-COMMITTED audit failure.
 *
 * <p>{@link MutationIntentRepository#markCommitted} succeeds, then
 * {@link MutationCommitCoordinator#safeWriteAudit} absorbs an {@link AuditWriter} failure. The
 * tool still returns SUCCESS, the intent remains COMMITTED, and exact replay is safe.
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
@ExtendWith(OutputCaptureExtension.class)
class BuiltInMutationToolsPostCommitAuditFailureTest {

    private static final String FIXTURE_ENTITY = "mutationTest_MutationTestFixture";
    private static final String USERNAME = "mutation-user";

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @Autowired
    private MutationIntentRepository mutationIntentRepository;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    @MockitoBean
    private AuditWriter auditWriter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String idempotencyKey;
    private UUID fixtureId;
    private final java.util.List<UUID> intentIds = new java.util.ArrayList<>();

    @BeforeEach
    void failAuditWrites() {
        doThrow(new IllegalStateException("synthetic audit writer failure with secret raw arguments"))
                .when(auditWriter)
                .writeToolCall(any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any());
    }

    @AfterEach
    void cleanRows() {
        systemAuthenticator.runWithSystem(() -> {
            if (fixtureId != null) {
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .id(fixtureId)
                        .optional()
                        .ifPresent(unconstrainedDataManager::remove);
            }
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
            for (UUID intentId : intentIds) {
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .id(intentId)
                        .optional()
                        .ifPresent(unconstrainedDataManager::remove);
            }
        });
        idempotencyKey = null;
        fixtureId = null;
        intentIds.clear();
    }

    @Test
    void successAuditFailureAfterCommitted_isLoggedAndReplayDoesNotDuplicateHostWrite(CapturedOutput output)
            throws Exception {
        idempotencyKey = UUID.randomUUID().toString();
        String secretValue = "post-commit-pii-value";
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Post commit audit failure fixture");
        attributes.put("secret", secretValue);

        String firstJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes, idempotencyKey));

        JsonNode first = objectMapper.readTree(firstJson);
        assertThat(first.path("outcome").asText())
                .as("safeWriteAudit must not turn a post-COMMITTED audit failure into tool failure; raw=%s", firstJson)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        fixtureId = UUID.fromString(first.path("entityId").asText());

        AiMutationIntent committed = loadIntent();
        assertThat(committed.getStatus()).isEqualTo(AiMutationIntentStatus.COMMITTED);

        String replayJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, new LinkedHashMap<>(attributes), idempotencyKey));
        JsonNode replay = objectMapper.readTree(replayJson);
        assertThat(replay.path("outcome").asText()).isEqualTo(AiToolCallOutcome.IDEMPOTENT_REPLAY.getId());
        assertThat(UUID.fromString(replay.path("entityId").asText())).isEqualTo(fixtureId);

        long hostRows = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e where e.id = :id")
                        .parameter("id", fixtureId)
                        .list()
                        .size());
        assertThat(hostRows)
                .as("exact replay after COMMITTED audit failure must not run a second host save")
                .isEqualTo(1L);

        assertThat(output.getOut() + output.getErr())
                .contains("AI_AGENT_MUTATION_AUDIT_WRITE_FAILED")
                .doesNotContain(idempotencyKey)
                .doesNotContain(secretValue)
                .doesNotContain("synthetic audit writer failure with secret raw arguments");
    }

    @Test
    void markCommitUnknown_doesNotDowngradeCurrentCommittedRow() {
        AiMutationIntent intent = systemAuthenticator.withSystem(() ->
                mutationIntentRepository.reserveOrReplay(
                        "create_record",
                        UUID.randomUUID().toString(),
                        "repository-no-downgrade",
                        null,
                        "hash-no-downgrade",
                        java.time.Duration.ofHours(1)).intent());
        intentIds.add(intent.getId());
        UUID resultId = UUID.randomUUID();

        systemAuthenticator.runWithSystem(() ->
                mutationIntentRepository.markCommitted(intent, resultId, FIXTURE_ENTITY));
        systemAuthenticator.runWithSystem(() ->
                mutationIntentRepository.markCommitUnknown(intent, "commit_failed"));

        AiMutationIntent reloaded = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiMutationIntent.class).id(intent.getId()).one());
        assertThat(reloaded.getStatus()).isEqualTo(AiMutationIntentStatus.COMMITTED);
        assertThat(reloaded.getResultEntityId()).isEqualTo(resultId);
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
}
