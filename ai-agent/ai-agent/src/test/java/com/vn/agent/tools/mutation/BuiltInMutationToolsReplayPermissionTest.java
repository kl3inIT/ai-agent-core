package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.AccessManager;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.accesscontext.CrudEntityContext;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * TEST-12 exact replay under current read denial.
 *
 * <p>The first mutation commits normally. Before exact retry, Jmix read permission is denied
 * through {@link AccessManager}. The retry must still short-circuit through idempotency replay:
 * entityId is preserved, instanceName is null, and no record fields leak.
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
class BuiltInMutationToolsReplayPermissionTest {

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
    private LlmExposurePolicy llmExposurePolicy;

    @MockitoBean
    private AccessManager accessManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean denyReadAfterFirstCall = new AtomicBoolean(false);
    private final AtomicBoolean failReplayReadPermission = new AtomicBoolean(false);
    private final AtomicInteger crudCallsAfterReplayFailureArmed = new AtomicInteger();
    private String idempotencyKey;
    private UUID fixtureId;
    private String replayReadFailureMessage;

    @BeforeEach
    void configureSecurity() {
        denyReadAfterFirstCall.set(false);
        failReplayReadPermission.set(false);
        crudCallsAfterReplayFailureArmed.set(0);
        replayReadFailureMessage = null;
        when(llmExposurePolicy.canReadEntity(any())).thenReturn(true);
        when(llmExposurePolicy.canCreate(any())).thenReturn(true);
        when(llmExposurePolicy.canUpdate(any())).thenReturn(true);
        when(llmExposurePolicy.canModify(any())).thenReturn(true);
        doAnswer(invocation -> {
            Object context = invocation.getArgument(0);
            if (failReplayReadPermission.get() && context instanceof CrudEntityContext) {
                int crudCall = crudCallsAfterReplayFailureArmed.incrementAndGet();
                if (crudCall > 1) {
                    throw new IllegalStateException(replayReadFailureMessage);
                }
            }
            if (denyReadAfterFirstCall.get() && context instanceof CrudEntityContext crud) {
                crud.setReadDenied();
            }
            return null;
        }).when(accessManager).applyRegisteredConstraints(any());
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
        fixtureId = null;
    }

    @Test
    void exactReplayWhenReadNowDenied_returnsReplayWithEntityIdAndNoInstanceNameOrRecordFields()
            throws Exception {
        idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Replay permission fixture");
        attributes.put("secret", "should-not-leak-on-replay");

        String firstJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes, idempotencyKey));
        JsonNode first = objectMapper.readTree(firstJson);
        assertThat(first.path("outcome").asText())
                .as("first mutation must succeed before replay denial is meaningful; raw=%s", firstJson)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        fixtureId = UUID.fromString(first.path("entityId").asText());

        denyReadAfterFirstCall.set(true);

        String replayJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, new LinkedHashMap<>(attributes), idempotencyKey));
        JsonNode replay = objectMapper.readTree(replayJson);

        assertThat(replay.path("outcome").asText())
                .as("exact retry must replay instead of duplicating the host write; raw=%s", replayJson)
                .isEqualTo(AiToolCallOutcome.IDEMPOTENT_REPLAY.getId());
        assertThat(UUID.fromString(replay.path("entityId").asText())).isEqualTo(fixtureId);
        assertThat(replay.path("instanceName").isMissingNode() || replay.path("instanceName").isNull())
                .as("current read denial must suppress instanceName on replay")
                .isTrue();
        assertThat(replay.has("name")).isFalse();
        assertThat(replay.has("secret")).isFalse();
        assertThat(replayJson).doesNotContain("Replay permission fixture", "should-not-leak-on-replay");

        long hostRows = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e where e.id = :id")
                        .parameter("id", fixtureId)
                        .list()
                        .size());
        assertThat(hostRows).isEqualTo(1L);
    }

    @Test
    void replayInstanceNameResolutionFailure_isLoggedWithoutLeakingRawExceptionMessage(CapturedOutput output)
            throws Exception {
        idempotencyKey = UUID.randomUUID().toString();
        String secretValue = "replay-resolution-pii";
        String secretExceptionMessage = "synthetic replay failure with " + secretValue;
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Replay resolution fixture");
        attributes.put("secret", secretValue);

        String firstJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes, idempotencyKey));
        JsonNode first = objectMapper.readTree(firstJson);
        assertThat(first.path("outcome").asText())
                .as("first mutation must succeed before replay logging can be verified; raw=%s", firstJson)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        fixtureId = UUID.fromString(first.path("entityId").asText());

        replayReadFailureMessage = secretExceptionMessage;
        failReplayReadPermission.set(true);

        String replayJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, new LinkedHashMap<>(attributes), idempotencyKey));
        JsonNode replay = objectMapper.readTree(replayJson);

        assertThat(replay.path("outcome").asText())
                .as("replay must remain valid even when live instance-name resolution fails; raw=%s", replayJson)
                .isEqualTo(AiToolCallOutcome.IDEMPOTENT_REPLAY.getId());
        assertThat(UUID.fromString(replay.path("entityId").asText())).isEqualTo(fixtureId);
        assertThat(replay.path("instanceName").isMissingNode() || replay.path("instanceName").isNull())
                .isTrue();

        assertThat(output.getOut() + output.getErr())
                .contains("AI_AGENT_MUTATION_REPLAY_INSTANCE_NAME_RESOLUTION_FAILED")
                .contains(IllegalStateException.class.getName())
                .doesNotContain(secretExceptionMessage)
                .doesNotContain(secretValue)
                .doesNotContain(idempotencyKey);
    }
}
