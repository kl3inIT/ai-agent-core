package com.vn.agent.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.mutation.BuiltInMutationTools;
import com.vn.agent.tools.mutation.MutationFixturePersistenceTestConfiguration;
import com.vn.agent.tools.mutation.MutationToolTestContext;
import com.vn.agent.tools.mutation.MutationToolTestUsersConfiguration;
import com.vn.agent.tools.mutation.fixture.MutationStoreLinkedChildFixture;
import com.vn.agent.tools.mutation.fixture.MutationStoreLinkedParentFixture;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import net.ttddyy.dsproxy.QueryCount;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Plan 17-01 Task 3 (MUT-16) — datasource-proxy SELECT-count proxy for the batch to-one FK
 * load during {@code bulk_save_records} attribute binding. Proves the FK-resolution path is
 * "1 query, NOT N": as a K-row batch grows from K=10 to K=100 — every row pointing at the SAME
 * parent — the SELECT count must stay essentially flat (slope {@code <= 1}), not scale with K.
 *
 * <p><b>Open Question 1 resolved as option (a).</b> The fixtures
 * ({@link MutationStoreLinkedParentFixture}/{@link MutationStoreLinkedChildFixture}) carry
 * {@code @Store("agentstore")} so the EXISTING agentstore-wrapping
 * {@link QueryCountingDataSourceConfiguration} observes the FK {@code .ids(...)} SELECT without
 * widening the shared counting config. The narrowed-boot recipe is copied VERBATIM from
 * {@link ToolQueryCountBaselineTest}.
 *
 * <p>The {@code agentstore.liquibase.change-log} property points at the test-only agentstore
 * changelog that includes the production changelog PLUS the two FK fixture tables, so the global
 * {@code test-app.properties} agentstore wiring is untouched for every other test.
 *
 * <p><b>Intentional RED until Plan 03.</b> Today {@code MutationAttributeBinder} resolves each
 * to-one FK reference with a per-row {@code dataManager.load(class).id(...)}, so a K-row batch
 * issues ~K FK SELECTs and the slope {@code countLarge - countSmall} is ≈ 90. Plan 03 batch-loads
 * the distinct FK target ids with a single constrained {@code dataManager.load(class).ids(...)}
 * IN(...) query (one SELECT per target class regardless of K), flipping the slope assertion GREEN.
 *
 * <p>If the known atmosphere-runtime / agentstore boot regression blocks this {@code @SpringBootTest}
 * the same way it has since Phase 11/13, the SUMMARY records it exactly as Phase 13.1 Plan 06 did
 * and the source stays compileTestJava-green and structurally correct.
 */
@SpringBootTest(classes = {AITestConfiguration.class, MutationFixturePersistenceTestConfiguration.class},
        properties = {
                "jmix.ai-agent.tools.mutation.enabled=true",
                "jmix.ai-agent.tools.mutation.bulk-max-rows=200",
                "agentstore.liquibase.change-log=com/vn/agent/test_liquibase/test-agentstore-changelog.xml"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class,
        com.vn.autoconfigure.agent.AiToolsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        QueryCountingDataSourceConfiguration.class, MutationToolTestUsersConfiguration.class})
class MutationFkBatchLoadQueryCountTest {

    private static final String CHILD_ENTITY = "mutationTest_MutationStoreLinkedChildFixture";
    private static final String FK_ATTRIBUTE = "storeLinkedParent";
    private static final String USERNAME = "mutation-user";
    private static final String COUNTING_DS = QueryCountingDataSourceConfiguration.DATASOURCE_NAME;

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetCountsBeforeEachTest() {
        QueryCountHolder.clear();
    }

    /** Defensive read — datasource-proxy returns null when no queries have hit the holder yet. */
    private static long safeSelectCount() {
        QueryCount qc = QueryCountHolder.get(COUNTING_DS);
        return qc == null ? 0L : qc.getSelect();
    }

    /** Seed ONE agentstore parent and return its id. */
    private UUID seedParent() {
        return systemAuthenticator.withSystem(() -> {
            MutationStoreLinkedParentFixture parent =
                    unconstrainedDataManager.create(MutationStoreLinkedParentFixture.class);
            parent.setName("fk-batch-parent");
            return unconstrainedDataManager.save(parent).getId();
        });
    }

    /** Build a K-row create batch where every row's FK points at {@code parentId}. */
    private List<Map<String, Object>> buildBatch(int k, UUID parentId) {
        return IntStream.range(0, k)
                .mapToObj(i -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("label", "fk-child-" + i);
                    row.put(FK_ATTRIBUTE, parentId.toString());
                    return row;
                })
                .collect(Collectors.toList());
    }

    /**
     * Measure the SELECT count for a single {@code bulk_save_records} call of {@code k} rows,
     * each referencing {@code parentId}. Clears the counter immediately before the invocation so
     * the window is scoped to the batch bind+save only.
     */
    private long measureBatchSelects(int k, UUID parentId) {
        List<Map<String, Object>> batch = buildBatch(k, parentId);
        String idempotencyKey = UUID.randomUUID().toString();

        return mutationToolTestContext.withMutationRun(USERNAME, () -> {
            QueryCountHolder.clear();
            String json = builtInMutationTools.bulkSaveRecords(CHILD_ENTITY, batch, idempotencyKey);
            long selects = safeSelectCount();
            assertOutcomeSuccess(json, k);
            return selects;
        });
    }

    private void assertOutcomeSuccess(String json, int expectedCount) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            assertThat(parsed.path("outcome").asText())
                    .as("%d-row FK batch must succeed; raw=%s", expectedCount, json)
                    .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
            assertThat(parsed.path("count").asInt()).isEqualTo(expectedCount);
        } catch (Exception e) {
            throw new AssertionError("could not parse bulk_save_records response: " + json, e);
        }
    }

    @Test
    @DisplayName("MUT-16: batch FK load is 1 query not N — SELECT slope ~0 as K grows 10->100")
    void batchFkLoadSlopeIsConstantAsBatchGrows() {
        UUID parentId = seedParent();

        // Warm up first so EclipseLink metamodel + Jmix permission/policy caches are primed
        // BEFORE measuring slope — the slope (delta), not the absolute count, is the contract.
        measureBatchSelects(10, parentId);

        long countSmall = measureBatchSelects(10, parentId);
        long countLarge = measureBatchSelects(100, parentId);

        // True per-row FK load (today): countLarge ≈ countSmall + (100 - 10) = countSmall + 90.
        // Batch FK load (Plan 03): one IN(...) SELECT per target class regardless of K → slope ~0.
        // Tolerance ≤1 leaves room for one extra meta query in some EclipseLink paths.
        assertThat(countLarge - countSmall)
                .as("MUT-16: batch FK-load SELECT count must NOT scale with batch size "
                        + "(K=10 -> %d selects, K=100 -> %d selects). Slope > 1 means per-row FK "
                        + "SELECTs — RED until Plan 03 batch-loads distinct FK ids via "
                        + "dataManager.load(class).ids(...).", countSmall, countLarge)
                .isLessThanOrEqualTo(1L);
    }
}
