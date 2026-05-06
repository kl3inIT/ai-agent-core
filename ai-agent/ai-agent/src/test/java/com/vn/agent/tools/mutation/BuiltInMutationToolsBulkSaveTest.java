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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 Plan 13-05 Task 2A — pins the {@code bulk_save_records} happy-path
 * D-02 contract. A 10-row create batch must produce:
 * <ul>
 *   <li>10 fixture rows persisted in the main store.</li>
 *   <li>Exactly ONE {@link AiAuditEvent} row (event {@code bulk_save_records},
 *       outcome {@link AiToolCallOutcome#SUCCESS}).</li>
 *   <li>Exactly ONE {@link AiMutationIntent} row whose
 *       {@link AiMutationIntent#getResultSummary} JSON parses to
 *       {@code {count, savedIds}} (REVIEWS HIGH-11 — the column added in Plan
 *       13-03 so {@code IDEMPOTENT_REPLAY} can return the original savedIds
 *       array instead of just one entityId).</li>
 *   <li>{@code argumentsJson} on the audit row carries
 *       {@code {entityName, count, sampleHashes, idempotencyKey}} and contains
 *       NO raw user-supplied values (PII safety).</li>
 * </ul>
 *
 * <p>REVIEWS HIGH-7 — uses the EXISTING {@code mutationTest_MutationTestFixture}
 * fixture entity with fields {@code name}, {@code secret}, {@code priority}.
 * The original plan-fiction {@code AiTestCustomer} entity does not exist.
 *
 * <p>Test 2 covers the mixed create/update dispatch (id-presence dispatch);
 * Test 3 the {@code bulk-max-rows} DoS guard.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {
                "ai-agent.tools.mutation.enabled=true",
                "ai-agent.tools.mutation.bulk-max-rows=100"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsBulkSaveTest {

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
    private final java.util.List<UUID> seededFixtureIds = new java.util.ArrayList<>();
    private final java.util.List<String> seededIdempotencyKeys = new java.util.ArrayList<>();

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
    void tenValidCreateRecordsProduceTenRowsOneAuditOneIntent() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        List<Map<String, Object>> records = IntStream.range(0, 10)
                .mapToObj(i -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", "fixture-" + i);
                    row.put("secret", "secret-" + i);
                    row.put("priority", i + 1);
                    return row;
                })
                .collect(Collectors.toList());

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, records, idempotencyKey));

        JsonNode parsed = objectMapper.readTree(json);
        assertThat(parsed.path("outcome").asText())
                .as("10-row create batch must succeed; raw=%s", json)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        assertThat(parsed.path("count").asInt()).isEqualTo(10);
        JsonNode savedIdsNode = parsed.path("savedIds");
        assertThat(savedIdsNode.isArray()).isTrue();
        assertThat(savedIdsNode).hasSize(10);
        savedIdsNode.forEach(idNode ->
                seededFixtureIds.add(UUID.fromString(idNode.asText())));

        // Exactly 10 fixture rows persisted in main store.
        long fixtureCount = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e " +
                                "where e.id in :ids")
                        .parameter("ids", seededFixtureIds)
                        .list().size());
        assertThat(fixtureCount).as("all 10 records must be persisted").isEqualTo(10L);

        // Exactly 1 audit row, outcome=SUCCESS.
        List<AiAuditEvent> auditRows = loadAuditRows(idempotencyKey);
        assertThat(auditRows)
                .as("bulk_save_records must produce exactly one audit row per batch")
                .hasSize(1);
        assertThat(auditRows.get(0).getOutcome()).isEqualTo(AiToolCallOutcome.SUCCESS);

        // argumentsJson shape: {entityName, count, sampleHashes, idempotencyKey} +
        // PII safety — never contains raw user values.
        String argumentsJson = auditRows.get(0).getArgumentsJson();
        JsonNode args = objectMapper.readTree(argumentsJson);
        assertThat(args.path("entityName").asText()).isEqualTo(FIXTURE_ENTITY);
        assertThat(args.path("count").asInt()).isEqualTo(10);
        assertThat(args.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
        assertThat(args.path("sampleHashes").isArray()).isTrue();
        assertThat(args.path("sampleHashes").size())
                .as("sampleHashes is bounded to 3 entries")
                .isLessThanOrEqualTo(3);
        // PII grep gate — argumentsJson must not echo raw user-supplied values.
        for (int i = 0; i < 10; i++) {
            assertThat(argumentsJson)
                    .as("argumentsJson must not contain raw fixture name 'fixture-%d'", i)
                    .doesNotContain("fixture-" + i);
            assertThat(argumentsJson)
                    .as("argumentsJson must not contain raw secret 'secret-%d'", i)
                    .doesNotContain("secret-" + i);
        }

        // Exactly 1 intent row with parseable RESULT_SUMMARY JSON containing savedIds (HIGH-11).
        List<AiMutationIntent> intents = loadIntents(idempotencyKey);
        assertThat(intents)
                .as("bulk_save_records must produce exactly one intent reservation per batch")
                .hasSize(1);
        String resultSummary = intents.get(0).getResultSummary();
        assertThat(resultSummary)
                .as("REVIEWS HIGH-11 — RESULT_SUMMARY column must persist {count, savedIds} so " +
                        "IDEMPOTENT_REPLAY can return the original array, not just one id")
                .isNotNull();
        JsonNode summaryNode = objectMapper.readTree(resultSummary);
        assertThat(summaryNode.path("count").asInt()).isEqualTo(10);
        assertThat(summaryNode.path("savedIds").isArray()).isTrue();
        assertThat(summaryNode.path("savedIds")).hasSize(10);
    }

    @Test
    void mixedBatchUpdateAndCreateDispatchedById() throws Exception {
        // Pre-seed one fixture row to be updated.
        UUID existingId = systemAuthenticator.withSystem(() -> {
            MutationTestFixture fixture = unconstrainedDataManager.create(MutationTestFixture.class);
            fixture.setName("existing");
            fixture.setSecret("existing-secret");
            fixture.setPriority(99);
            return unconstrainedDataManager.save(fixture).getId();
        });
        seededFixtureIds.add(existingId);

        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        Map<String, Object> updateRow = new LinkedHashMap<>();
        updateRow.put("id", existingId.toString());
        updateRow.put("priority", 5);

        Map<String, Object> createRow = new LinkedHashMap<>();
        createRow.put("name", "new-row");
        createRow.put("secret", "x");
        createRow.put("priority", 1);

        // bulk_save_records requires >= 2 of the SAME entity; this batch satisfies
        // that with one update + one create.
        List<Map<String, Object>> records = List.of(updateRow, createRow);

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, records, idempotencyKey));

        JsonNode parsed = objectMapper.readTree(json);
        assertThat(parsed.path("outcome").asText())
                .as("mixed update/create batch must succeed; raw=%s", json)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        assertThat(parsed.path("count").asInt()).isEqualTo(2);

        JsonNode savedIdsNode = parsed.path("savedIds");
        assertThat(savedIdsNode).hasSize(2);
        // First savedId is the updated row (existingId); second is the newly-created row.
        UUID firstSavedId = UUID.fromString(savedIdsNode.get(0).asText());
        UUID secondSavedId = UUID.fromString(savedIdsNode.get(1).asText());
        assertThat(firstSavedId)
                .as("savedIds[0] must echo the updated existing row's id (input-order contract)")
                .isEqualTo(existingId);
        seededFixtureIds.add(secondSavedId);

        // Existing row's priority must be updated to 5; new row exists with name="new-row".
        Integer updatedPriority = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .id(existingId).one().getPriority());
        assertThat(updatedPriority).isEqualTo(5);
        String newRowName = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .id(secondSavedId).one().getName());
        assertThat(newRowName).isEqualTo("new-row");

        // Single audit + single intent for the batch.
        assertThat(loadAuditRows(idempotencyKey)).hasSize(1);
        assertThat(loadIntents(idempotencyKey)).hasSize(1);
    }

    @Test
    void batchAboveBulkMaxRowsRejectedAsValidationFailed() throws Exception {
        // Property-driven 100 cap (set on the class). Submitting 101 rows must fail at
        // the DoS guard BEFORE any DB work — fixture count before == fixture count after.
        long beforeCount = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e")
                        .list().size());

        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        List<Map<String, Object>> records = IntStream.range(0, 101)
                .mapToObj(i -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", "guard-" + i);
                    row.put("priority", i + 1);
                    return row;
                })
                .collect(Collectors.toList());

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, records, idempotencyKey));

        JsonNode parsed = objectMapper.readTree(json);
        // Error path returns ToolErrorDto JSON: {error, reason, expected}. No top-level outcome.
        assertThat(parsed.path("error").asText())
                .as("bulk-max-rows DoS guard must reject batch as validation_failed; raw=%s", json)
                .isEqualTo("validation_failed");

        long afterCount = systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e")
                        .list().size());
        assertThat(afterCount)
                .as("oversized batch must persist ZERO rows (rejection happens before any save)")
                .isEqualTo(beforeCount);
    }

    // ---------- helpers ----------

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
