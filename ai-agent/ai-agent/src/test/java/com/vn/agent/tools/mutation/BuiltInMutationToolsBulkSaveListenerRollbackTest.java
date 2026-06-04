package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.event.EntitySavingEvent;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WR-01 (Phase 17) — pins the documented rollback-all invariant for
 * {@code bulk_save_records}: a host {@code EntitySavingEvent} listener that THROWS
 * (the in-transaction, pre-commit equivalent of a {@code BeforeInsertEntityListener})
 * rolls back the ENTIRE bulk batch — zero rows persisted, no partial commit.
 *
 * <p>This is the COVERED half of the invariant documented on
 * {@link MutationSaveExecutor#bulkSave} and at the bulk save call site in
 * {@link MutationGateChain}. The complementary NOT-COVERED half (a listener that
 * returns NORMALLY but silently drops a row) is explicitly declared OUT of the
 * {@code bulk_save_records} contract and is intentionally NOT exercised here — with
 * {@code SaveContext.discardSaved(true)} there is no returned {@code EntitySet} to
 * cross-check, so a non-throwing silent drop would not be detected. Hosts MUST signal
 * a refusal by throwing, never by silently dropping a row.
 *
 * <p>The listener is armed per-test via {@link ThrowingSavingListener#arm()} so it stays
 * dormant for every other test in the context.
 */
@SpringBootTest(classes = {AITestConfiguration.class, MutationFixturePersistenceTestConfiguration.class},
        properties = {"ai-agent.tools.mutation.enabled=true"})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class,
        BuiltInMutationToolsBulkSaveListenerRollbackTest.ThrowingSavingListenerConfiguration.class})
class BuiltInMutationToolsBulkSaveListenerRollbackTest {

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
    private final List<String> seededIdempotencyKeys = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        ThrowingSavingListener.disarm();
        systemAuthenticator.runWithSystem(() -> {
            unconstrainedDataManager.load(MutationTestFixture.class)
                    .query("select e from mutationTest_MutationTestFixture e " +
                            "where e.name like 'listener-rollback-%'")
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
     * A host {@code EntitySavingEvent} listener throwing inside the save transaction must
     * roll back the WHOLE batch: ZERO {@code MutationTestFixture} rows persisted, even though
     * every row passed gates 1-6 and reached the single {@code @Transactional} save.
     */
    @Test
    void throwingSavingListenerRollsBackEntireBatch() throws Exception {
        long beforeCount = countFixtures();
        String idempotencyKey = UUID.randomUUID().toString();
        seededIdempotencyKeys.add(idempotencyKey);

        // 8 fully-valid rows — nothing here trips a gate; the ONLY failure source is the
        // armed host listener throwing inside the save transaction.
        List<Map<String, Object>> records = IntStream.range(0, 8).mapToObj(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "listener-rollback-" + i);
            row.put("priority", i + 1);
            return row;
        }).collect(Collectors.toList());

        ThrowingSavingListener.arm();

        String json = mutationToolTestContext.withMutationRun(USERNAME, () ->
                builtInMutationTools.bulkSaveRecords(FIXTURE_ENTITY, records, idempotencyKey));

        // The listener throws a plain RuntimeException inside the transaction; the Phase 11
        // catch ladder classifies a non-typed host save failure as a commit failure.
        JsonNode parsed = objectMapper.readTree(json);
        assertThat(parsed.path("error").asText())
                .as("a throwing host save listener must surface as a structured error, not SUCCESS; raw=%s", json)
                .isNotEmpty()
                .isNotEqualTo("");
        assertThat(parsed.has("outcome"))
                .as("error result is a ToolErrorDto {error,reason,expected}, never a success envelope; raw=%s", json)
                .isFalse();

        // The invariant under test: rollback-all — ZERO rows persisted despite 8 valid rows
        // reaching the save, because the listener threw mid-transaction.
        long afterCount = countFixtures();
        assertThat(afterCount)
                .as("rollback-all (WR-01) — a throwing save listener must persist ZERO fixture rows")
                .isEqualTo(beforeCount);
    }

    // ---------- helpers ----------

    private long countFixtures() {
        return systemAuthenticator.withSystem(() -> (long)
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .query("select e from mutationTest_MutationTestFixture e " +
                                "where e.name like 'listener-rollback-%'")
                        .list()
                        .size());
    }

    /**
     * Host {@code EntitySavingEvent} listener that, when armed, throws on the first
     * {@link MutationTestFixture} entity it sees during a save transaction — simulating a
     * host {@code BeforeInsertEntityListener} that rejects a row by throwing. Fires inside the
     * save transaction (pre-commit), so the throw aborts the whole batch.
     */
    static final class ThrowingSavingListener {

        private static final AtomicBoolean ARMED = new AtomicBoolean(false);

        static void arm() {
            ARMED.set(true);
        }

        static void disarm() {
            ARMED.set(false);
        }

        @EventListener
        void onSaving(EntitySavingEvent<MutationTestFixture> event) {
            if (ARMED.get()) {
                throw new IllegalStateException(
                        "WR-01 test host listener rejected fixture row: " + event.getEntity().getName());
            }
        }
    }

    @TestConfiguration
    static class ThrowingSavingListenerConfiguration {

        @org.springframework.context.annotation.Bean
        ThrowingSavingListener throwingSavingListener() {
            return new ThrowingSavingListener();
        }
    }
}
