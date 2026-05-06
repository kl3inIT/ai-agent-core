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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 Plan 13-05 Task 2C — pins {@code bulk_save_records} idempotency contract:
 *
 * <ol>
 *   <li>{@code replayWithSameKeyReturnsIdempotentReplayWithOriginalSavedIds} —
 *       REVIEWS HIGH-11. Same idempotencyKey + byte-identical canonical-JSON of
 *       records returns {@link AiToolCallOutcome#IDEMPOTENT_REPLAY} with the
 *       FULL original {@code savedIds} array (not just one entityId). Without
 *       Plan 13-03's {@code AiMutationIntent.RESULT_SUMMARY} column the replay
 *       would only echo the first saved id and break the bulk contract.</li>
 *   <li>{@code differentBytesSameKeyReturnsIdempotencyViolation} — same key +
 *       different bytes (one priority changed) returns {@code idempotency_violation}
 *       (REVIEWS HIGH-6 — error code, NOT outcome=FAILED).</li>
 *   <li>{@code differentRowOrderSameKeyReturnsIdempotencyViolation} —
 *       canonical-JSON-in-submission-order; reordering rows must produce a
 *       different request hash and therefore a violation (Pitfall 5).</li>
 * </ol>
 *
 * <p>REVIEWS HIGH-7 — uses the EXISTING {@code mutationTest_MutationTestFixture}
 * fixture entity with fields {@code name}, {@code secret}, {@code priority}.
 *
 * <p><b>No outer transaction</b> — the first invocation must FULLY commit (and
 * its REQUIRES_NEW reservation must commit) before the second call sees the
 * row as COMMITTED. A test-level transaction would hold the row in PENDING
 * snapshot.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsBulkSaveIdempotencyTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<UUID> seededFixtureIds = new ArrayList<>();
    private final List<String> seededIdempotencyKeys = new ArrayList<>();

    @AfterEach
    void cleanRows() {
        systemAuthenticator.runWithSystem(() -> {
            for (UUID id : seededFixtureIds) {
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
            for (String key : seededIdempotencyKeys) {
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :t and e.idempotencyKey = :k")
                        .parameter("t", "bulk_save_records")
                        .parameter("k", key)
                        .list()
                        .forEach(unconstrainedDataManager::remove);
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a " +
                                "where a.eventName = :n and a.argumentsJson like :arg")
                        .parameter("n", "bulk_save_records")
                        .parameter("arg", "%" + key + "%")
                        .list()
                        .forEach(unconstrainedDataManager::remove);
            }
        });
        seededFixtureIds.clear();
        seededIdempotencyKeys.clear();
    }

    @Test
    void replayWithSameKeyReturnsIdempotentReplayWithOriginalSavedIds() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        List<Map<String, Object>> records = IntStream.range(0, 5).mapToObj(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "idempotent-replay-" + i);
            row.put("secret", "secret-" + i);
            row.put("priority", i + 1);
            return row;
        }).collect(Collectors.toList());

        // First call — must SUCCEED with 5 saved ids.
        String firstJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY,
                        deepCopyRecords(records), idempotencyKey));

        JsonNode first = objectMapper.readTree(firstJson);
        assertThat(first.path("outcome").asText())
                .as("first call must SUCCEED; raw=%s", firstJson)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        JsonNode firstSavedIdsNode = first.path("savedIds");
        assertThat(firstSavedIdsNode).hasSize(5);
        List<UUID> firstSavedIds = new ArrayList<>();
        firstSavedIdsNode.forEach(idNode -> firstSavedIds.add(UUID.fromString(idNode.asText())));
        seededFixtureIds.addAll(firstSavedIds);

        // Second call — same key, deep-copy of same records (canonical-JSON identical).
        String secondJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY,
                        deepCopyRecords(records), idempotencyKey));

        JsonNode second = objectMapper.readTree(secondJson);
        assertThat(second.path("outcome").asText())
                .as("byte-identical replay must surface IDEMPOTENT_REPLAY; raw=%s", secondJson)
                .isEqualTo(AiToolCallOutcome.IDEMPOTENT_REPLAY.getId());

        // REVIEWS HIGH-11 — replay must echo ALL original savedIds via resultSummary, not just one.
        JsonNode resultSummary = second.path("resultSummary");
        assertThat(resultSummary.isObject())
                .as("replay must include resultSummary with the original savedIds array")
                .isTrue();
        assertThat(resultSummary.path("count").asInt()).isEqualTo(5);
        JsonNode replaySavedIdsNode = resultSummary.path("savedIds");
        assertThat(replaySavedIdsNode.isArray()).isTrue();
        assertThat(replaySavedIdsNode).hasSize(5);
        List<UUID> replaySavedIds = new ArrayList<>();
        replaySavedIdsNode.forEach(idNode -> replaySavedIds.add(UUID.fromString(idNode.asText())));
        assertThat(replaySavedIds)
                .as("REVIEWS HIGH-11 — replay must echo the ORIGINAL savedIds in the SAME order; " +
                        "without RESULT_SUMMARY column it would only echo the first id")
                .containsExactlyElementsOf(firstSavedIds);

        // Still only 5 fixture rows (the replay did not write any new row).
        long fixtureCount = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e " +
                                "where e.id in :ids")
                        .parameter("ids", firstSavedIds)
                        .list().size());
        assertThat(fixtureCount).as("replay must NOT create extra rows").isEqualTo(5L);

        // Still only one AiMutationIntent row for the (toolName, idempotencyKey, user) tuple.
        List<AiMutationIntent> intents = loadIntents(idempotencyKey);
        assertThat(intents)
                .as("intent reservation is one-per-batch; replay reuses the same row")
                .hasSize(1);
        assertThat(intents.get(0).getStatus())
                .as("after a successful first call + replay, the intent is COMMITTED")
                .isEqualTo(AiMutationIntentStatus.COMMITTED);

        // Two audit rows total — Phase 11 invariant: the replay event itself is durably audited
        // alongside the original SUCCESS row (mirrors BuiltInMutationToolsIdempotencyReplayTest
        // for create_record).
        List<AiAuditEvent> auditRows = loadAuditRows(idempotencyKey);
        assertThat(auditRows)
                .as("first call + replay must both be durably audited")
                .hasSize(2);
        assertThat(auditRows)
                .extracting(AiAuditEvent::getOutcome)
                .containsExactlyInAnyOrder(AiToolCallOutcome.SUCCESS, AiToolCallOutcome.IDEMPOTENT_REPLAY);
    }

    @Test
    void differentBytesSameKeyReturnsIdempotencyViolation() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        List<Map<String, Object>> records = IntStream.range(0, 5).mapToObj(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "idempotent-bytes-" + i);
            row.put("priority", 100 + i);
            return row;
        }).collect(Collectors.toList());

        String firstJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY,
                        deepCopyRecords(records), idempotencyKey));
        JsonNode first = objectMapper.readTree(firstJson);
        assertThat(first.path("outcome").asText()).isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        first.path("savedIds").forEach(idNode -> seededFixtureIds.add(UUID.fromString(idNode.asText())));

        // Mutate row 0 — same key, different bytes.
        List<Map<String, Object>> mutated = deepCopyRecords(records);
        mutated.get(0).put("priority", 999);

        String secondJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, mutated, idempotencyKey));
        JsonNode second = objectMapper.readTree(secondJson);
        // Tool returns ToolErrorDto JSON: {error: "idempotency_violation", ...}.
        assertThat(second.path("error").asText())
                .as("REVIEWS HIGH-6 — same key + different bytes returns idempotency_violation, NOT outcome=FAILED; raw=%s", secondJson)
                .isEqualTo("idempotency_violation");

        // Still 5 rows from the first call; nothing from the violation.
        long fixtureCount = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e " +
                                "where e.id in :ids")
                        .parameter("ids", seededFixtureIds)
                        .list().size());
        assertThat(fixtureCount).isEqualTo(5L);
    }

    /**
     * Pitfall 5 — submission-order canonical hash. Reordering records changes
     * the request hash even when the SET of rows is identical.
     */
    @Test
    void differentRowOrderSameKeyReturnsIdempotencyViolation() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", "idempotent-order-A");
        a.put("priority", 1);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", "idempotent-order-B");
        b.put("priority", 2);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", "idempotent-order-C");
        c.put("priority", 3);

        // First call: [A, B, C].
        List<Map<String, Object>> firstOrder = List.of(deepCopy(a), deepCopy(b), deepCopy(c));
        String firstJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, firstOrder, idempotencyKey));
        JsonNode first = objectMapper.readTree(firstJson);
        assertThat(first.path("outcome").asText()).isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        first.path("savedIds").forEach(idNode -> seededFixtureIds.add(UUID.fromString(idNode.asText())));

        // Second call: same content [C, B, A] — different submission order.
        List<Map<String, Object>> reordered = List.of(deepCopy(c), deepCopy(b), deepCopy(a));
        String secondJson = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, reordered, idempotencyKey));
        JsonNode second = objectMapper.readTree(secondJson);
        assertThat(second.path("error").asText())
                .as("Pitfall 5 — canonical-JSON-in-submission-order makes reorder a different hash; raw=%s", secondJson)
                .isEqualTo("idempotency_violation");
    }

    // ---------- helpers ----------

    /** Deep-copy a list of map records so the second call's list/map identity is fresh. */
    private static List<Map<String, Object>> deepCopyRecords(List<Map<String, Object>> records) {
        List<Map<String, Object>> copy = new ArrayList<>(records.size());
        for (Map<String, Object> row : records) {
            copy.add(deepCopy(row));
        }
        return copy;
    }

    private static Map<String, Object> deepCopy(Map<String, Object> row) {
        return new LinkedHashMap<>(row);
    }

    private List<AiAuditEvent> loadAuditRows(String idempotencyKey) {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a " +
                                "where a.eventName = :n and a.argumentsJson like :arg " +
                                "order by a.startedAt asc")
                        .parameter("n", "bulk_save_records")
                        .parameter("arg", "%" + idempotencyKey + "%")
                        .list());
    }

    private List<AiMutationIntent> loadIntents(String idempotencyKey) {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :t and e.idempotencyKey = :k")
                        .parameter("t", "bulk_save_records")
                        .parameter("k", idempotencyKey)
                        .list());
    }
}
