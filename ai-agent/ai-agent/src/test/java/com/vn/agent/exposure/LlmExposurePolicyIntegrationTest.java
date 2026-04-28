package com.vn.agent.exposure;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.orchestration.BaselineContextProvider;
import com.vn.agent.rag.ChunkMetadata;
import com.vn.agent.rag.RetrievalFilterBuilder;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.BuiltInDataTools;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 10-10 Task 2 — TEST-09 four-path uniform-opacity integration suite.
 *
 * <p>Seeds an {@link AiExposureRule} for {@link #TEST_ENTITY_NAME} (deterministic — Fix R8)
 * and asserts the denylisted entity is invisible across ALL four LLM-facing surfaces:
 *
 * <ol>
 *   <li>{@code list_entities} tool — denylisted entity does not appear in the JSON list.</li>
 *   <li>{@code agent.entities} baseline-context block — same entity is not in the rendered
 *       inventory the LLM receives every turn.</li>
 *   <li>{@code find_records(denylisted)} — returns the {@code unknown_entity} JSON envelope
 *       (NOT {@code access_denied}). Phase 10 Fix R4 unified opacity contract: a denylisted
 *       entity is indistinguishable from a non-existent one (EXP-09 + Phase 3 D-08).</li>
 *   <li>RAG filter clause — {@link RetrievalFilterBuilder#buildFor(Authentication)} produces
 *       a {@link Filter.Expression} whose {@code toString} contains
 *       {@link ChunkMetadata#SOURCE_ENTITY} in a {@code NIN}-shaped clause. Plan 10-05's
 *       defensive {@code OR(isNull, nin)} form is the implementation; the unit-level
 *       {@code RetrievalFilterBuilderDenylistTest} pins the {@code ISNULL} branch
 *       separately. This test asserts the integration-time clause shape only.</li>
 * </ol>
 *
 * <p><b>Fix R8 — deterministic test fixture:</b>
 * <ul>
 *   <li>{@link #TEST_ENTITY_NAME} is hardcoded to {@code ai_AiConversation}, an on-classpath
 *       Jmix entity that the canonical {@code alice} user (granted {@code AiAgentUserRole}
 *       via {@link com.vn.agent.test_support.TestUsersConfiguration}) has READ access to
 *       WITHOUT the denylist rule. Seeding the denylist rule and asserting absence is the
 *       only behavior change between baseline and assertion.</li>
 *   <li>Authentication is built via {@link SystemAuthenticator#withUser(String, java.util.concurrent.Callable)}
 *       — the same helper that {@link com.vn.agent.security.FilteredSchemaAndExecutionDenialTest}
 *       and {@link com.vn.agent.security.RagRoleFilterNegativeTest} use. No ad-hoc
 *       {@code SimpleGrantedAuthority} guesses.</li>
 *   <li>{@link #seedDenylistRule()} uses {@link UnconstrainedDataManager} (alice has no policy
 *       on {@link AiExposureRule}); {@link #cleanUp()} removes the seeded rule by id to keep
 *       tests independent.</li>
 * </ul>
 *
 * <p>Threat-register regression gates:
 * <ul>
 *   <li>T-10-04 Information Disclosure — find_records opacity (Fix R4 unified contract).</li>
 *   <li>T-10-02 Information Disclosure — agent.entities denylist leak.</li>
 *   <li>T-10-03 Information Disclosure — RAG filter clause shape.</li>
 * </ul>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class LlmExposurePolicyIntegrationTest {

    /**
     * Fix R8: deterministic seeded entity name — pinned to {@code ai_AiConversation}, an
     * on-classpath {@link com.vn.agent.entity.AiConversation} entity that the canonical
     * test user {@code alice} (granted {@link com.vn.agent.security.AiAgentUserRole}) has
     * READ access to WITHOUT a denylist rule. NOT chosen via dynamic
     * "first non-system entity" — that approach yields nondeterministic test runs because
     * iteration order depends on metamodel registration order.
     */
    private static final String TEST_ENTITY_NAME = "ai_AiConversation";

    @Autowired BuiltInDataTools builtInDataTools;
    @Autowired BaselineContextProvider baselineContextProvider;
    @Autowired RetrievalFilterBuilder retrievalFilterBuilder;
    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired UnconstrainedDataManager unconstrainedDataManager;
    @Autowired Metadata metadata;

    /**
     * Holds the id of the rule seeded by {@link #seedDenylistRule()} so {@link #cleanUp()}
     * can remove exactly that rule (avoids deleting any host-supplied rules that may exist
     * in the agentstore).
     */
    private UUID seededRuleId;

    @BeforeEach
    void seedDenylistRule() {
        // System-context save: alice has no @EntityPolicy on AiExposureRule. UnconstrainedDataManager
        // bypasses Jmix entity-/row-level security so the test can seed an admin-only governance row.
        AiExposureRule rule = metadata.create(AiExposureRule.class);
        rule.setEntityName(TEST_ENTITY_NAME);
        rule.setMode(AiExposureRuleMode.EXCLUDE);
        rule.setEnabled(true);
        AiExposureRule saved = unconstrainedDataManager.save(rule);
        seededRuleId = saved.getId();
    }

    @AfterEach
    void cleanUp() {
        if (seededRuleId != null) {
            unconstrainedDataManager.load(AiExposureRule.class)
                    .id(seededRuleId)
                    .optional()
                    .ifPresent(unconstrainedDataManager::remove);
            seededRuleId = null;
        }
    }

    @Test
    void denylistedEntityNotInListEntities() {
        systemAuthenticator.withUser("alice", () -> {
            String json = builtInDataTools.listEntities();
            assertThat(json)
                    .as("list_entities must omit the denylisted entity %s for non-admin user alice "
                            + "(EXP-09 uniform opacity)", TEST_ENTITY_NAME)
                    .doesNotContain(TEST_ENTITY_NAME);
            return null;
        });
    }

    @Test
    void denylistedEntityNotInAgentEntities() {
        systemAuthenticator.withUser("alice", () -> {
            Map<String, Object> ctx = baselineContextProvider.compose(UUID.randomUUID());
            // agent.entities is rendered as a newline-joined "name (label)" block.
            // If the entity is denylisted it must not appear in that block at all.
            Object entitiesBlock = ctx.get("agent.entities");
            String entitiesText = entitiesBlock == null ? "" : entitiesBlock.toString();
            assertThat(entitiesText)
                    .as("agent.entities baseline block must omit the denylisted entity %s "
                            + "(T-10-02 regression gate — Plan 10-04 BaselineContextProvider swap)",
                            TEST_ENTITY_NAME)
                    .doesNotContain(TEST_ENTITY_NAME);
            return null;
        });
    }

    @Test
    void findRecordsDenylistedEntityReturnsUnknownEntityNotAccessDenied() {
        systemAuthenticator.withUser("alice", () -> {
            // Phase 10 Fix R4 — uniform opacity at every canReadEntity()==false branch in
            // BuiltInDataTools.resolveReadableEntityOrThrow. A denylisted entity surfaces
            // as "unknown_entity" exactly like a non-existent one. The error envelope
            // MUST NOT contain the literal "access_denied" anywhere.
            String result = builtInDataTools.findRecords(TEST_ENTITY_NAME, null, 10);
            assertThat(result)
                    .as("find_records on a denylisted entity must return the unknown_entity envelope "
                            + "(T-10-04 regression gate — Fix R4 unified opacity)")
                    .contains("\"error\"")
                    .contains("unknown_entity")
                    .doesNotContain("access_denied");
            return null;
        });
    }

    @Test
    void ragFilterContainsDenylistNinClause() {
        systemAuthenticator.withUser("alice", () -> {
            // Pull the actual Authentication that withUser populated — same pattern as
            // RagRoleFilterNegativeTest. RetrievalFilterBuilder.buildFor takes the
            // Authentication explicitly (no implicit thread-context variant).
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth)
                    .as("withUser must populate SecurityContextHolder with alice's authentication")
                    .isNotNull();

            Filter.Expression expr = retrievalFilterBuilder.buildFor(auth);

            assertThat(expr)
                    .as("Non-admin caller with non-empty denylist must produce a non-null filter")
                    .isNotNull();
            String rendered = expr.toString();
            assertThat(rendered)
                    .as("RAG filter must reference the source_entity metadata key when denylist is non-empty "
                            + "(T-10-03 regression gate — Plan 10-05 EXP-05 NIN clause)")
                    .contains(ChunkMetadata.SOURCE_ENTITY);
            assertThat(rendered.toUpperCase())
                    .as("RAG filter must use a NIN-shaped operator on source_entity")
                    .containsAnyOf("NIN", "NOT IN", "NOT_IN");
            // Sanity: the actual denylisted entity name appears in the NIN value list.
            assertThat(rendered).contains(TEST_ENTITY_NAME);
            return null;
        });
    }
}
