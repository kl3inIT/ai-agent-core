package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 11-10 Task 3 — TEST-11 same-key + different-shape -> idempotency_violation.
 *
 * <p>First {@code create_record} succeeds with attributes {@code {"name":"Alice"}} and
 * idempotency key {@code K}. Second call uses the SAME key {@code K} but a different attribute
 * map ({@code {"name":"Bob"}}). The second response must contain
 * {@code "error":"idempotency_violation"}; no second fixture row may be written; the dedup
 * row count remains exactly 1; correction guidance must mention a fresh idempotency key.
 */
@MutationIntegrationTest
class BuiltInMutationToolsIdempotencyViolationTest {

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

    private final java.util.List<UUID> seededFixtureIds = new java.util.ArrayList<>();
    private String idempotencyKey;

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
    void sameKeyDifferentAttributes_returnsIdempotencyViolation_noSecondHostWrite() throws Exception {
        idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> firstAttrs = new LinkedHashMap<>();
        firstAttrs.put("name", "Alice");

        String firstJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, firstAttrs, idempotencyKey));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode first = mapper.readTree(firstJson);
        assertThat(first.path("outcome").asText())
                .as("first invocation must succeed for the violation assertion to be meaningful; raw=%s",
                        firstJson)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        UUID firstEntityId = UUID.fromString(first.path("entityId").asText());
        seededFixtureIds.add(firstEntityId);

        // Second call — same key, DIFFERENT attribute value -> idempotency_violation.
        Map<String, Object> secondAttrs = new LinkedHashMap<>();
        secondAttrs.put("name", "Bob");

        String secondJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, secondAttrs, idempotencyKey));

        JsonNode second = mapper.readTree(secondJson);
        assertThat(second.path("error").asText())
                .as("same key + different shape -> idempotency_violation; raw=%s", secondJson)
                .isEqualTo("idempotency_violation");
        // Correction guidance must point at a fresh idempotencyKey (must-haves contract).
        assertThat(second.path("expected").toString())
                .as("violation guidance must instruct the LLM to use a fresh idempotencyKey; raw=%s",
                        secondJson)
                .contains("fresh idempotencyKey");

        // Exactly one fixture row exists. The second call MUST NOT create one.
        long fixtureCount = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e " +
                                "where e.name = :n1 or e.name = :n2")
                        .parameter("n1", "Alice")
                        .parameter("n2", "Bob")
                        .list()
                        .size());
        assertThat(fixtureCount)
                .as("violation must NOT trigger a second host save")
                .isEqualTo(1L);

        // Exactly one dedup row for the (tool, key, user) tuple.
        long dedupCount = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :t and e.idempotencyKey = :k and e.userUsername = :u")
                        .parameter("t", "create_record")
                        .parameter("k", idempotencyKey)
                        .parameter("u", USERNAME)
                        .list()
                        .size());
        assertThat(dedupCount).isEqualTo(1L);
    }
}
