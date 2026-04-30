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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * TEST-12 known host-save rollback.
 *
 * <p>The save executor throws before returning, so the catch ladder is still in
 * {@link MutationCommitState#NO_HOST_WRITE}. The intent may be marked FAILED, the audit outcome
 * is ERROR, and an exact same-hash retry can reclaim the row and proceed.
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
class BuiltInMutationToolsKnownRollbackTest {

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
    private MutationSaveExecutor mutationSaveExecutor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean failFirstSave = new AtomicBoolean(true);
    private String idempotencyKey;
    private UUID savedFixtureId;

    @BeforeEach
    void configureSaveExecutor() {
        failFirstSave.set(true);
        doAnswer(invocation -> {
            Object entity = invocation.getArgument(0);
            if (failFirstSave.getAndSet(false)) {
                throw new DataIntegrityViolationException("synthetic constraint violation");
            }
            return unconstrainedDataManager.save(entity);
        }).when(mutationSaveExecutor).save(any());
    }

    @AfterEach
    void cleanRows() {
        systemAuthenticator.runWithSystem(() -> {
            if (savedFixtureId != null) {
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .id(savedFixtureId)
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
        savedFixtureId = null;
    }

    @Test
    void knownSaveRollback_mapsToValidationFailedErrorAuditAndFailedIntent_thenExactRetryCanProceed()
            throws Exception {
        idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Known rollback fixture");
        attributes.put("priority", 20);

        String firstJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes, idempotencyKey));

        JsonNode first = objectMapper.readTree(firstJson);
        assertThat(first.path("error").asText())
                .as("known save-time rollback must map to validation_failed; raw=%s", firstJson)
                .isEqualTo("validation_failed");

        AiMutationIntent failedIntent = loadIntent();
        assertThat(failedIntent.getStatus()).isEqualTo(AiMutationIntentStatus.FAILED);
        List<AiAuditEvent> errorRows = loadAuditRows(AiToolCallOutcome.ERROR);
        assertThat(errorRows)
                .as("known rollback writes ERROR, not COMMIT_FAILED")
                .hasSize(1);
        String errorResultSummary = errorRows.stream()
                .map(AiAuditEvent::getResultSummary)
                .findFirst()
                .orElseThrow();
        assertThat(errorResultSummary)
                .as("audit ERROR row carries the same sanitized error envelope returned to the tool caller")
                .contains("\"error\":\"validation_failed\"")
                .doesNotContain("synthetic constraint violation");
        assertThat(loadAuditRows(AiToolCallOutcome.COMMIT_FAILED))
                .as("save-time rollback happened before host save returned")
                .isEmpty();

        String retryJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, new LinkedHashMap<>(attributes), idempotencyKey));

        JsonNode retry = objectMapper.readTree(retryJson);
        assertThat(retry.path("outcome").asText())
                .as("same-key same-hash retry can reclaim a FAILED row and proceed; raw=%s", retryJson)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        savedFixtureId = UUID.fromString(retry.path("entityId").asText());
        assertThat(loadIntent().getStatus()).isEqualTo(AiMutationIntentStatus.COMMITTED);
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
                                "and a.outcome = :outcome and a.argumentsJson like :key")
                        .parameter("eventName", "create_record")
                        .parameter("outcome", outcome)
                        .parameter("key", "%" + idempotencyKey + "%")
                        .list());
    }
}
