package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 11-10 Task 3 — TEST-11 idempotent replay proof.
 *
 * <p>Calls {@code create_record} twice with the SAME idempotency key and the SAME attribute
 * map. The first call writes a fixture row + a {@link AiMutationIntent} row + a SUCCESS audit
 * row. The second call must return {@link AiToolCallOutcome#IDEMPOTENT_REPLAY} with the SAME
 * {@code entityId} and write NO additional fixture row. Exactly ONE dedup row per
 * {@code (toolName, idempotencyKey, userUsername)} tuple.
 *
 * <p><b>No outer transaction</b> — neither a transactional test annotation nor a commit
 * annotation is
 * present on the class or test method. The first tool invocation must FULLY return (and its
 * REQUIRES_NEW reservation must commit) before the second invocation runs; otherwise the
 * second invocation would still see the row as PENDING in its own transaction snapshot.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsIdempotencyReplayTest {

    private static final String FIXTURE_ENTITY = "mutationTest_MutationTestFixture";

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private final java.util.List<UUID> seededFixtureIds = new java.util.ArrayList<>();
    private String idempotencyKey;
    private static final String USERNAME = "mutation-user";

    @AfterEach
    void cleanRows() {
        systemAuthenticator.runWithSystem(() -> {
            for (UUID id : seededFixtureIds) {
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
            if (idempotencyKey != null) {
                List<AiMutationIntent> intents = unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :t and e.idempotencyKey = :k and e.userUsername = :u")
                        .parameter("t", "create_record")
                        .parameter("k", idempotencyKey)
                        .parameter("u", USERNAME)
                        .list();
                for (AiMutationIntent i : intents) {
                    unconstrainedDataManager.remove(i);
                }
            }
        });
        seededFixtureIds.clear();
    }

    @Test
    void sameKeySameAttributes_returnsIdempotentReplayWithSameEntityId_oneDedupRow() throws Exception {
        idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Replay-Alice");
        attributes.put("priority", 3);

        // First call — must return SUCCESS, write a fixture row.
        String firstJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes, idempotencyKey));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode first = mapper.readTree(firstJson);
        assertThat(first.path("outcome").asText())
                .as("first invocation must SUCCEED; raw=%s", firstJson)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        UUID firstEntityId = UUID.fromString(first.path("entityId").asText());
        seededFixtureIds.add(firstEntityId);

        // Second call — same key, same attributes. Must return IDEMPOTENT_REPLAY with same id.
        String secondJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY,
                        new LinkedHashMap<>(attributes), idempotencyKey));

        JsonNode second = mapper.readTree(secondJson);
        assertThat(second.path("outcome").asText())
                .as("second invocation must surface IDEMPOTENT_REPLAY; raw=%s", secondJson)
                .isEqualTo(AiToolCallOutcome.IDEMPOTENT_REPLAY.getId());
        UUID secondEntityId = UUID.fromString(second.path("entityId").asText());
        assertThat(secondEntityId)
                .as("replay must return the SAME entityId as the first call")
                .isEqualTo(firstEntityId);

        // Exactly one dedup row for this (toolName, idempotencyKey, userUsername) tuple.
        List<AiMutationIntent> dedupRows = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :t and e.idempotencyKey = :k and e.userUsername = :u")
                        .parameter("t", "create_record")
                        .parameter("k", idempotencyKey)
                        .parameter("u", USERNAME)
                        .list());
        assertThat(dedupRows)
                .as("dedup must contain exactly one row for the (toolName, idempotencyKey, userUsername) tuple")
                .hasSize(1);
        assertThat(dedupRows.get(0).getStatus())
                .as("after a successful first call + replay, the dedup row's status is COMMITTED")
                .isEqualTo(AiMutationIntentStatus.COMMITTED);

        // Exactly one fixture row was written.
        long fixtureCount = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e where e.id = :id")
                        .parameter("id", firstEntityId)
                        .list()
                        .size());
        assertThat(fixtureCount)
                .as("replay must NOT create a second fixture row")
                .isEqualTo(1L);
    }
}
