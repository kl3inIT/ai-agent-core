package com.vn.agent.rag;

import com.vn.agent.metadata.LlmExposurePolicy;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan 18-03 Task 2 — PERF-03 once-per-retrieval call-count proxy for
 * {@link RetrievalFilterBuilder#buildFor(Authentication)}.
 *
 * <p>Pure-JUnit 5 + Mockito unit suite ({@code @Tag("unit")}, NO {@code @SpringBootTest} — sidesteps
 * the pre-existing Phase 11/13 atmosphere-runtime / agentstoreEntityManagerFactory boot regression
 * documented in {@code .planning/phases/13-.../deferred-items.md}). It constructs a REAL
 * {@link RetrievalFilterBuilder} over Mockito-mocked collaborators and asserts the PERF-03 contract
 * via a checkable proxy:
 *
 * <ol>
 *   <li><b>Once-per-retrieval lookup (call-count):</b> a single {@code buildFor} invocation issues
 *       EXACTLY ONE {@link LlmExposurePolicy#getDenylistedEntityNames()} call — the denylist read is
 *       not repeated per clause. Because Plan 18-01 (PERF-02) backs that method with the app-wide
 *       {@code denylistCache}, the single call is a memoized read, not a per-call agentstore
 *       SELECT.</li>
 *   <li><b>Verbatim-clause smoke (complementary to {@code RetrievalFilterBuilderDenylistTest}):</b>
 *       the built {@link Filter.Expression} still surfaces the NIN {@code source_entity} clause AND a
 *       per-role flag clause + the model-pin — proving the once-per-retrieval build did not drop a
 *       clause (T-18-08).</li>
 *   <li><b>Per-retrieval once-ness (not a cross-retrieval cache claim):</b> a SECOND independent
 *       {@code buildFor} call likewise issues exactly one lookup — role extraction stays
 *       request-fresh (T-18-10); the per-retrieval contract is "one lookup per retrieval," NOT
 *       "one lookup forever."</li>
 * </ol>
 *
 * <p>This complements — and must NOT disturb — the byte-for-byte
 * {@code RetrievalFilterBuilderDenylistTest} verbatim-clause guard.
 */
@Tag("unit")
class RetrievalFilterBuilderBuildOncePerRetrievalTest {

    private static final String EMBEDDING_MODEL = "qwen/qwen3-embedding-4b";
    private static final String DENIED_ENTITY = "OrderEntity";
    private static final String USER_ROLE_CODE = "ai-agent-user";

    private static AiAgentEmbeddingProperties embeddingProps() {
        return new AiAgentEmbeddingProperties(EMBEDDING_MODEL, 2000, null);
    }

    private static AiAgentRagProperties ragProps() {
        // admin-bypass false → the non-admin per-role + exposure branch is exercised.
        return new AiAgentRagProperties(false, 5, 0.5, null, null, null, null, null);
    }

    private static LlmExposurePolicy denylistPolicy() {
        LlmExposurePolicy policy = mock(LlmExposurePolicy.class);
        Set<String> denied = new LinkedHashSet<>(List.of(DENIED_ENTITY));
        when(policy.getDenylistedEntityNames()).thenReturn(denied);
        return policy;
    }

    private static Authentication userAuth() {
        return new UsernamePasswordAuthenticationToken(
                "user", "n/a", List.of(new SimpleGrantedAuthority("ROLE_AI_AGENT_USER")));
    }

    /**
     * The denylist lookup fires EXACTLY ONCE per {@code buildFor} retrieval — the read is not
     * repeated per clause; it is memoized via the Plan 18-01 (PERF-02) app-wide cache.
     */
    @Test
    void buildForIssuesExactlyOneDenylistLookupPerRetrieval() {
        LlmExposurePolicy policy = denylistPolicy();
        RetrievalFilterBuilder builder =
                new RetrievalFilterBuilder(ragProps(), embeddingProps(), policy);

        Filter.Expression expr = builder.buildFor(userAuth());

        assertThat(expr).isNotNull();
        verify(policy, times(1)).getDenylistedEntityNames();
    }

    /**
     * Verbatim-clause smoke — the once-per-retrieval build still emits the NIN {@code source_entity}
     * clause, the per-role flag clause, and the embedding-model pin (no redundant-clause removal,
     * T-18-08). Complementary to the byte-for-byte {@code RetrievalFilterBuilderDenylistTest}.
     */
    @Test
    void buildForStillEmitsNinAndRoleClausesVerbatim() {
        LlmExposurePolicy policy = denylistPolicy();
        RetrievalFilterBuilder builder =
                new RetrievalFilterBuilder(ragProps(), embeddingProps(), policy);

        Filter.Expression expr = builder.buildFor(userAuth());

        assertThat(expr).isNotNull();
        String rendered = expr.toString();

        // NIN source_entity clause survives.
        assertThat(rendered)
                .as("once-per-retrieval build must preserve the NIN source_entity clause (T-18-08)")
                .contains(ChunkMetadata.SOURCE_ENTITY);
        assertThat(rendered.toUpperCase())
                .as("denylist must still produce a NIN-shaped clause")
                .contains("NIN");
        assertThat(rendered).contains(DENIED_ENTITY);

        // Per-role flag clause + model pin survive.
        assertThat(rendered)
                .as("per-role flag clause must survive the once-per-retrieval build (T-18-08)")
                .contains(ChunkMetadata.roleFlagKey(USER_ROLE_CODE));
        assertThat(rendered).contains(ChunkMetadata.EMBEDDING_MODEL);
    }

    /**
     * Per-retrieval once-ness, NOT a cross-retrieval cache claim — a SECOND independent retrieval
     * issues exactly one further lookup (two total across two retrievals). Role extraction stays
     * request-fresh (T-18-10).
     */
    @Test
    void secondRetrievalIssuesItsOwnSingleLookup() {
        LlmExposurePolicy policy = denylistPolicy();
        RetrievalFilterBuilder builder =
                new RetrievalFilterBuilder(ragProps(), embeddingProps(), policy);

        builder.buildFor(userAuth());
        builder.buildFor(userAuth());

        // One lookup per retrieval → two total; never zero (no cross-request role/lookup cache).
        verify(policy, times(2)).getDenylistedEntityNames();
    }
}
