package com.vn.agent.rag;

import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.security.AiAgentAdminRole;
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
import static org.mockito.Mockito.when;

/**
 * Plan 10-10 Task 1 — TEST-09 unit-level RAG filter assertion (Fix R6 / D-06 cross-link).
 *
 * <p>Pure-Mockito unit suite (no Spring context) that pins the {@code Filter.Expression}
 * shape produced by {@link RetrievalFilterBuilder#buildFor(Authentication)} when
 * {@link LlmExposurePolicy#getDenylistedEntityNames()} returns a non-empty set,
 * complementing the four-path Spring-boot integration suite in
 * {@code LlmExposurePolicyIntegrationTest}.
 *
 * <p>Four assertions:
 * <ol>
 *   <li>Non-empty denylist → expression contains the {@code source_entity} key in a
 *       {@code NIN}-shaped clause (operator name appears verbatim in the {@link Filter.Expression}
 *       record {@code toString}).</li>
 *   <li>Empty denylist → expression contains no {@code source_entity} reference (zero-overhead
 *       short-circuit, EXP-05).</li>
 *   <li>Admin user → admin bypass skips role-overlap filtering only; denylist filtering still
 *       applies when denylist contents are present.</li>
 *   <li><b>Fix R6 / D-06 legacy-doc carve-out:</b> a chunk with NO {@code source_entity}
 *       metadata key remains visible. Spring AI 1.1.4 pgvector does not support
 *       {@code ISNULL}, so this unit suite pins the pgvector-compatible {@code NIN} shape;
 *       the Docker-backed RAG integration tests prove missing-key chunks still pass.</li>
 * </ol>
 */
class RetrievalFilterBuilderDenylistTest {

    private static final String EMBEDDING_MODEL = "qwen/qwen3-embedding-4b";

    private static AiAgentEmbeddingProperties embeddingProps() {
        return new AiAgentEmbeddingProperties(EMBEDDING_MODEL, 2000, null);
    }

    private static AiAgentRagProperties ragProps(boolean adminBypass) {
        return new AiAgentRagProperties(adminBypass, 5, 0.5, null, null, null, null, null, null);
    }

    private static LlmExposurePolicy emptyExposurePolicy() {
        LlmExposurePolicy mock = mock(LlmExposurePolicy.class);
        when(mock.getDenylistedEntityNames()).thenReturn(Set.of());
        return mock;
    }

    private static LlmExposurePolicy exposurePolicyWithDenylist(String... entityNames) {
        LlmExposurePolicy mock = mock(LlmExposurePolicy.class);
        Set<String> denied = new LinkedHashSet<>(java.util.Arrays.asList(entityNames));
        when(mock.getDenylistedEntityNames()).thenReturn(denied);
        return mock;
    }

    private static Authentication authWith(String... authorities) {
        List<SimpleGrantedAuthority> list = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new UsernamePasswordAuthenticationToken("user", "n/a", list);
    }

    /**
     * Test 1 — Non-empty denylist produces a NIN clause keyed on {@code source_entity}.
     * The Spring AI 1.1.4 {@code Filter.Expression} record renders its operator type via
     * the enum literal in its auto-generated {@code toString}; for a NIN expression the
     * substring {@code NIN} appears verbatim.
     */
    @Test
    void whenDenylistNonEmpty_thenFilterContainsNinClause() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(
                ragProps(true), embeddingProps(),
                exposurePolicyWithDenylist("OrderEntity"));
        Authentication auth = authWith("ROLE_AI_AGENT_USER");

        Filter.Expression expr = builder.buildFor(auth);

        assertThat(expr).isNotNull();
        String rendered = expr.toString();
        assertThat(rendered)
                .as("Non-empty denylist must surface the source_entity metadata key in the filter")
                .contains(ChunkMetadata.SOURCE_ENTITY);
        // Spring AI Filter.ExpressionType enum literal NIN renders verbatim in record toString.
        assertThat(rendered.toUpperCase())
                .as("Non-empty denylist must produce a NIN-shaped clause")
                .containsAnyOf("NIN", "NOT IN", "NOT_IN");
        // Sanity: the denylisted entity name itself appears as a value in the NIN list.
        assertThat(rendered).contains("OrderEntity");
    }

    /**
     * Test 2 — Empty denylist: the EXP-05 short-circuit in
     * {@link RetrievalFilterBuilder#buildFor(Authentication)} skips the entire NIN clause,
     * so {@code source_entity} must not appear anywhere in the expression.
     */
    @Test
    void whenDenylistEmpty_thenNoSourceEntityClause() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(
                ragProps(true), embeddingProps(), emptyExposurePolicy());
        Authentication auth = authWith("ROLE_AI_AGENT_USER");

        Filter.Expression expr = builder.buildFor(auth);

        assertThat(expr).isNotNull();
        String rendered = expr.toString();
        assertThat(rendered)
                .as("Empty denylist short-circuit must not introduce any source_entity clause "
                        + "(zero-overhead invariant — EXP-05 + Plan 10-05 SUMMARY)")
                .doesNotContain(ChunkMetadata.SOURCE_ENTITY);
    }

    /**
     * Test 3 — Admin user with bypass enabled still receives exposure denylist filtering.
     * Admin bypass only skips role-overlap checks; it must not widen the LLM-visible surface
     * beyond the exposure policy.
     */
    @Test
    void whenAdminUserAndDenylistNonEmpty_thenBuildForReturnsExposureFilter() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(
                ragProps(true), embeddingProps(),
                exposurePolicyWithDenylist("OrderEntity"));
        Authentication auth = authWith("ROLE_AI_AGENT_ADMIN");

        Filter.Expression expr = builder.buildFor(auth);

        assertThat(expr)
                .as("Admin bypass must not skip exposure denylist filtering")
                .isNotNull();
        String rendered = expr.toString();
        assertThat(rendered).contains(ChunkMetadata.EMBEDDING_MODEL);
        assertThat(rendered).contains(ChunkMetadata.SOURCE_ENTITY);
        assertThat(rendered).contains("OrderEntity");
        assertThat(rendered)
                .as("Admin exposure filter must bypass role-overlap metadata")
                .doesNotContain(ChunkMetadata.roleFlagKey(AiAgentAdminRole.CODE));
    }

    /**
     * Test 4 (Fix R6 / D-06 legacy-doc carve-out) - Spring AI 1.1.4 pgvector
     * supports {@code NIN} but rejects {@code ISNULL}. This unit test keeps the
     * generated expression within the converter's supported subset; Docker-backed
     * integration tests prove the missing {@code source_entity} metadata case still passes.
     */
    @Test
    void whenDenylistNonEmpty_thenUsesPgVectorCompatibleNinWithoutIsNull() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(
                ragProps(true), embeddingProps(),
                exposurePolicyWithDenylist("OrderEntity"));
        Authentication auth = authWith("ROLE_AI_AGENT_USER");

        Filter.Expression expr = builder.buildFor(auth);

        assertThat(expr).isNotNull();
        String rendered = expr.toString();

        // Spring AI 1.1.4 PgVectorFilterExpressionConverter supports NIN but not ISNULL.
        // The integration tests cover the missing-key visibility contract against real pgvector.
        assertThat(rendered.toUpperCase())
                .as("The expression must stay pgvector-compatible; ISNULL is not supported by "
                        + "Spring AI 1.1.4 PgVectorFilterExpressionConverter")
                .doesNotContain("ISNULL")
                .contains("NIN");
        assertThat(rendered).contains(ChunkMetadata.SOURCE_ENTITY);
    }
}
