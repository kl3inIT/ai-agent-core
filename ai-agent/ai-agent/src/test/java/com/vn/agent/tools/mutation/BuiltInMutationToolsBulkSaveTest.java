package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 Plan 13-05 — {@code bulk_save_records} happy-path (D-02) AND idempotency contract.
 *
 * <p>Consolidated from the former {@code BuiltInMutationToolsBulkSaveTest} (happy path / mixed
 * dispatch / DoS cap) and {@code BuiltInMutationToolsBulkSaveIdempotencyTest} (replay / violation /
 * row-order) — both booted an identical real-bean context, so they now share one context via
 * {@link MutationIntegrationTest}. The two failure-mode suites that alter the context
 * ({@code BuiltInMutationToolsBulkSavePartialFailureTest} with {@code @MockitoBean} guard/AccessManager,
 * and {@code BuiltInMutationToolsBulkSaveListenerRollbackTest} with a throwing entity listener) are
 * intentionally NOT merged here — merging them would replace the real gating beans this suite
 * exercises.
 *
 * <p>Happy-path coverage:
 * <ul>
 *   <li>10-row create batch → 10 rows, ONE {@link AiAuditEvent} (SUCCESS), ONE
 *       {@link AiMutationIntent} whose RESULT_SUMMARY parses to {@code {count, savedIds}}
 *       (REVIEWS HIGH-11); {@code argumentsJson} carries {@code {entityName, count, sampleHashes,
 *       idempotencyKey}} with NO raw user values (PII safety).</li>
 *   <li>Mixed create/update dispatch by id-presence.</li>
 *   <li>{@code bulk-max-rows} DoS guard (cap pinned to 100 via {@link TestPropertySource}).</li>
 * </ul>
 *
 * <p>Idempotency coverage:
 * <ul>
 *   <li>Same key + byte-identical canonical-JSON → {@link AiToolCallOutcome#IDEMPOTENT_REPLAY}
 *       echoing the FULL original {@code savedIds} array (REVIEWS HIGH-11).</li>
 *   <li>Same key + different bytes → {@code idempotency_violation} (REVIEWS HIGH-6 — error code,
 *       NOT outcome=FAILED).</li>
 *   <li>Same key + reordered rows → {@code idempotency_violation} (Pitfall 5 — canonical-JSON in
 *       submission order).</li>
 * </ul>
 *
 * <p>REVIEWS HIGH-7 — uses the EXISTING {@code mutationTest_MutationTestFixture} fixture entity
 * with fields {@code name}, {@code secret}, {@code priority}.
 *
 * <p><b>No outer transaction</b> — the idempotency tests require the first invocation (and its
 * REQUIRES_NEW reservation) to FULLY commit before the second call sees the row as COMMITTED.
 */
@MutationIntegrationTest
@TestPropertySource(properties = "ai-agent.tools.mutation.bulk-max-rows=100")
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

    // ------------------------------------------------------------------
    // happy path (former BuiltInMutationToolsBulkSaveTest)
    // ------------------------------------------------------------------

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
        // Property-driven 100 cap (set via @TestPropertySource). Submitting 101 rows must fail at
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

    // ------------------------------------------------------------------
    // idempotency (former BuiltInMutationToolsBulkSaveIdempotencyTest)
    // ------------------------------------------------------------------

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
