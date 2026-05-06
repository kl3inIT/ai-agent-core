package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.spi.MutationGuard;
import com.vn.agent.spi.MutationIntent;
import com.vn.agent.spi.ToolVetoedException;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.AccessManager;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 13 Plan 13-05 Task 2B — pins {@code bulk_save_records} rollback-all
 * semantics for the four per-row failure modes:
 * <ol>
 *   <li>{@code MutationGuard} veto on a single row → entire batch rolls back;
 *       audit outcome {@link AiToolCallOutcome#BLOCKED} (REVIEWS HIGH-6 — the
 *       enum has no FAILED member; veto = BLOCKED, validation = ERROR).</li>
 *   <li>{@code AccessManager.applyRegisteredConstraints(CrudEntityContext)}
 *       per-row denial → outcome BLOCKED (REVIEWS HIGH-13 — proves the per-row
 *       CrudEntityContext check actually runs; entity-level once-per-batch
 *       checks would miss row-state-dependent denial).</li>
 *   <li>Validation failure on first row (non-numeric String for Integer
 *       attribute) → outcome {@link AiToolCallOutcome#ERROR} (NEVER FAILED).</li>
 *   <li>Explicit {@code id: null} in records → outcome ERROR with
 *       {@code validation_failed} (REVIEWS HIGH-12 — distinguishes from the
 *       valid id-key-omitted CREATE).</li>
 * </ol>
 *
 * <p>All four tests assert ZERO rows persisted in the main store
 * (rollback-all) and exactly ONE audit row per batch.
 */
@SpringBootTest(classes = AITestConfiguration.class,
        properties = {"ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsBulkSavePartialFailureTest {

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
    private final java.util.List<String> seededIdempotencyKeys = new java.util.ArrayList<>();

    @AfterEach
    void cleanRows() {
        // Belt-and-braces: rollback-all should leave nothing behind, but if a future
        // regression breaks rollback we want the cleanup to find the orphans.
        systemAuthenticator.runWithSystem(() -> {
            unconstrainedDataManager.load(MutationTestFixture.class)
                    .query("select e from mutationTest_MutationTestFixture e " +
                            "where e.name like 'partial-fail-%'")
                    .list()
                    .forEach(unconstrainedDataManager::remove);
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
        seededIdempotencyKeys.clear();
    }

    /**
     * Scenario 1 — REVIEWS HIGH-6 + HIGH-7. A {@code MutationGuard} test bean
     * vetos the row whose {@code priority == 999}. The rest of the batch is
     * valid; the veto must roll back ALL rows (zero persisted) and the audit
     * row must record outcome {@link AiToolCallOutcome#BLOCKED}.
     */
    @Test
    void oneRowMutationGuardVetoRollsBackEntireBatch() throws Exception {
        long beforeCount = countFixtures();
        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        // 9 valid rows + row at index 4 with priority=999 (the veto trigger).
        List<Map<String, Object>> records = IntStream.range(0, 10).mapToObj(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "partial-fail-veto-" + i);
            row.put("priority", i == 4 ? 999 : i + 1);
            return row;
        }).collect(Collectors.toList());

        // Inject a guard that vetoes the row whose coerced priority equals 999.
        // Wrapped via @TestConfiguration would force a static helper; the spy below
        // is reused per test class via @MockitoBean below. We use a doAnswer here
        // instead so the guard sees the per-test trigger value.
        doAnswer(invocation -> {
            MutationIntent intent = invocation.getArgument(0, MutationIntent.class);
            Object priority = intent.attributes().get("priority");
            if (priority instanceof Integer p && p == 999) {
                throw new ToolVetoedException("partial-fail veto trigger");
            }
            return null;
        }).when(mutationGuard).check(any(MutationIntent.class));

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, records, idempotencyKey));

        // Tool returns ToolErrorDto JSON: {error: "access_denied", reason, expected}.
        // ToolVetoedException is translated to access_denied per Phase 11 catch ladder.
        JsonNode parsed = objectMapper.readTree(json);
        assertThat(parsed.path("error").asText())
                .as("guard veto must surface as access_denied; raw=%s", json)
                .isEqualTo("access_denied");

        // Audit row outcome MUST be BLOCKED (REVIEWS HIGH-6 — never "FAILED").
        List<AiAuditEvent> auditRows = loadAuditRows(idempotencyKey);
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.get(0).getOutcome())
                .as("REVIEWS HIGH-6 — guard veto -> BLOCKED, never FAILED")
                .isEqualTo(AiToolCallOutcome.BLOCKED);

        // Rollback-all: zero rows persisted.
        long afterCount = countFixtures();
        assertThat(afterCount)
                .as("rollback-all — guard veto on one row must persist ZERO fixture rows")
                .isEqualTo(beforeCount);
    }

    /**
     * Scenario 2 — REVIEWS HIGH-13. Per-row {@code AccessManager.applyRegisteredConstraints
     * (new CrudEntityContext(metaClass))} must run for EVERY row after the entity is
     * loaded/created. We deny on the 9th total {@code CrudEntityContext} call: 1 entity-level
     * (from {@code MutationAuthorizationService.enforceCreatePermission}) + 7 per-row succeed
     * + 1 per-row deny → row index 7 fails. Without the per-row check (entity-level
     * once-per-batch only) this denial mode could not be exercised at all.
     */
    @Test
    void oneRowAccessDeniedByCrudEntityContextRollsBackBatch() throws Exception {
        long beforeCount = countFixtures();
        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        // Allow @MockitoBean AccessManager to no-op normally for EntityAttributeContext
        // (so per-attribute write gate passes), but COUNT CrudEntityContext invocations
        // and call setCreateDenied() on the 9th one.
        AtomicInteger crudCallCount = new AtomicInteger();
        doAnswer(invocation -> {
            Object ctx = invocation.getArgument(0);
            if (ctx instanceof CrudEntityContext crud) {
                int n = crudCallCount.incrementAndGet();
                if (n == 9) {  // 1 entity-level + 7 per-row pass + this 8th per-row denies
                    crud.setCreateDenied();
                }
            } else if (ctx instanceof EntityAttributeContext) {
                // permissive — per-attribute write gate stays open
            }
            return null;
        }).when(accessManager).applyRegisteredConstraints(any());

        List<Map<String, Object>> records = IntStream.range(0, 10).mapToObj(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "partial-fail-acl-" + i);
            row.put("priority", i + 1);
            return row;
        }).collect(Collectors.toList());

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, records, idempotencyKey));

        JsonNode parsed = objectMapper.readTree(json);
        assertThat(parsed.path("error").asText())
                .as("per-row CrudEntityContext deny must surface as access_denied; raw=%s", json)
                .isEqualTo("access_denied");

        List<AiAuditEvent> auditRows = loadAuditRows(idempotencyKey);
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.get(0).getOutcome())
                .as("REVIEWS HIGH-13 — per-row AccessDenied -> BLOCKED")
                .isEqualTo(AiToolCallOutcome.BLOCKED);

        long afterCount = countFixtures();
        assertThat(afterCount)
                .as("rollback-all — per-row access deny must persist ZERO rows")
                .isEqualTo(beforeCount);

        // Sanity: the per-row check actually ran (>= 1 entity-level + N per-row).
        // Without REVIEWS HIGH-13 the count would be exactly 1 (entity-level only) and the
        // 9th-invocation deny would never fire.
        assertThat(crudCallCount.get())
                .as("REVIEWS HIGH-13 — per-row CrudEntityContext check must run more than once "
                        + "(entity-level once-per-batch checks alone cannot exercise this denial mode)")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * Scenario 3 — REVIEWS HIGH-6. A non-numeric String for Integer attribute
     * triggers {@code parameter_conversion_error} during type coercion. Audit
     * outcome is {@link AiToolCallOutcome#ERROR} (the validation/ToolUserError
     * path), NOT FAILED.
     */
    @Test
    void validationFailureOnFirstRowRollsBackBatch() throws Exception {
        long beforeCount = countFixtures();
        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        // Row 0: "priority" is a non-numeric String — triggers parameter_conversion_error.
        Map<String, Object> badRow = new LinkedHashMap<>();
        badRow.put("name", "partial-fail-validation-0");
        badRow.put("priority", "not-a-number");

        List<Map<String, Object>> records = new java.util.ArrayList<>();
        records.add(badRow);
        for (int i = 1; i < 5; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "partial-fail-validation-" + i);
            row.put("priority", i + 1);
            records.add(row);
        }

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, records, idempotencyKey));

        JsonNode parsed = objectMapper.readTree(json);
        // parameter_conversion_error is one of the 6 stable error codes for create/update;
        // either it surfaces directly or the translator collapses to validation_failed.
        // Both are acceptable per the tool contract docstring (lines 803-805).
        String errorCode = parsed.path("error").asText();
        assertThat(errorCode)
                .as("non-numeric value for Integer attribute must surface as a validation/coercion error; raw=%s", json)
                .isIn("parameter_conversion_error", "validation_failed");

        List<AiAuditEvent> auditRows = loadAuditRows(idempotencyKey);
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.get(0).getOutcome())
                .as("REVIEWS HIGH-6 — ToolUserError validation -> ERROR, never FAILED")
                .isEqualTo(AiToolCallOutcome.ERROR);

        long afterCount = countFixtures();
        assertThat(afterCount)
                .as("rollback-all — first-row validation failure must persist ZERO rows")
                .isEqualTo(beforeCount);
    }

    /**
     * Scenario 4 — REVIEWS HIGH-12. Explicit {@code "id": null} in a row is
     * rejected as {@code validation_failed} BEFORE any save. Distinguishes from
     * the omitted-id CREATE shape: a follow-up call with the same records but
     * row 1 = {@code {"name":"rogue"}} (id key OMITTED) MUST succeed.
     */
    @Test
    void explicitIdNullRejectedAsValidationFailed() throws Exception {
        long beforeCount = countFixtures();
        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        Map<String, Object> okRow = new LinkedHashMap<>();
        okRow.put("name", "partial-fail-idnull-ok");

        Map<String, Object> rogueRow = new LinkedHashMap<>();
        rogueRow.put("id", null);                   // explicit null — REVIEWS HIGH-12 rejects
        rogueRow.put("name", "partial-fail-idnull-rogue");

        Map<String, Object> okRow2 = new LinkedHashMap<>();
        okRow2.put("name", "partial-fail-idnull-ok2");

        List<Map<String, Object>> records = List.of(okRow, rogueRow, okRow2);

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, records, idempotencyKey));

        JsonNode parsed = objectMapper.readTree(json);
        assertThat(parsed.path("error").asText())
                .as("REVIEWS HIGH-12 — explicit id:null must be rejected as validation_failed; raw=%s", json)
                .isEqualTo("validation_failed");

        List<AiAuditEvent> auditRows = loadAuditRows(idempotencyKey);
        assertThat(auditRows).hasSize(1);
        assertThat(auditRows.get(0).getOutcome())
                .as("explicit id:null rejection -> ERROR (validation, not BLOCKED)")
                .isEqualTo(AiToolCallOutcome.ERROR);

        long afterCount = countFixtures();
        assertThat(afterCount)
                .as("rollback-all — id:null rejection happens BEFORE dispatch, ZERO rows persisted")
                .isEqualTo(beforeCount);

        // Distinguish from omitted-id case: same records but rogueRow has the "id" KEY removed
        // must SUCCEED (proves the rejection is specific to the explicit-null case, not all
        // create rows).
        String idempotencyKeyB = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKeyB);
        Map<String, Object> rogueRowFixed = new LinkedHashMap<>();
        rogueRowFixed.put("name", "partial-fail-idnull-fixed");  // NO "id" key at all
        List<Map<String, Object>> recordsB = List.of(okRow, rogueRowFixed, okRow2);

        String jsonB = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, recordsB, idempotencyKeyB));

        JsonNode parsedB = objectMapper.readTree(jsonB);
        assertThat(parsedB.path("outcome").asText())
                .as("same shape with id KEY OMITTED (vs explicit null) must SUCCEED; raw=%s", jsonB)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        // Cleanup: collect created ids so the @AfterEach finds them via the name-prefix query.
    }

    @MockitoBean
    private MutationGuard mutationGuard;

    @MockitoBean
    private AccessManager accessManager;

    @BeforeEach
    void wirePermissiveMockDefaults() throws ToolVetoedException {
        // Default permissive: no veto from the guard, no deny from AccessManager.
        // Per-test overrides are applied inside each @Test.
        doAnswer(invocation -> null).when(mutationGuard).check(any(MutationIntent.class));
        doAnswer(invocation -> null).when(accessManager).applyRegisteredConstraints(any());
    }

    // ---------- helpers ----------

    private long countFixtures() {
        return systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e " +
                                "where e.name like 'partial-fail-%'")
                        .list()
                        .size());
    }

    private List<AiAuditEvent> loadAuditRows(String idempotencyKey) {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a " +
                                "where a.eventName = :n and a.argumentsJson like :arg")
                        .parameter("n", "bulk_save_records")
                        .parameter("arg", "%" + idempotencyKey + "%")
                        .list());
    }
}
