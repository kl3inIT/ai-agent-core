package com.vn.agent.rag;

import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.security.AiAgentAdminRole;
import com.vn.agent.security.AiAgentUserRole;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetrievalFilterBuilder} — the pure-function RAG-05 filter.
 *
 * <p>No Spring context. Properties records are constructed directly, Authentication is stubbed via
 * {@link UsernamePasswordAuthenticationToken}. Assertions inspect the generated Filter.Expression
 * via its auto-generated record {@code toString()} output (contains embedded key/value literals).</p>
 *
 * <p>ROADMAP Phase-5 success criterion #2: "filter-expression builder produces correct expression
 * from a set of roles". Covers CONTEXT.md decisions D-05 (empty-roles fail-closed), D-06 (admin
 * bypass toggle), D-09 (ANY role-overlap semantics), D-11 (pure-function signature).</p>
 */
class RetrievalFilterBuilderTest {

    private static final String EMBEDDING_MODEL = "qwen/qwen3-embedding-4b";

    private static AiAgentEmbeddingProperties embeddingProps() {
        return new AiAgentEmbeddingProperties(EMBEDDING_MODEL, 2000, null);
    }

    private static AiAgentRagProperties ragProps(boolean adminBypass) {
        return new AiAgentRagProperties(adminBypass, 5, 0.5, null, null, null, null, null, null);
    }

    /** Default mock returning an empty denylist — preserves pre-Plan-10-05 builder behavior. */
    private static LlmExposurePolicy emptyExposurePolicy() {
        LlmExposurePolicy mock = mock(LlmExposurePolicy.class);
        when(mock.getDenylistedEntityNames()).thenReturn(Set.of());
        return mock;
    }

    /** Mock returning a fixed denylist for EXP-05 nin-clause assertions. */
    private static LlmExposurePolicy exposurePolicyWithDenylist(String... entityNames) {
        LlmExposurePolicy mock = mock(LlmExposurePolicy.class);
        when(mock.getDenylistedEntityNames())
                .thenReturn(new java.util.LinkedHashSet<>(java.util.Arrays.asList(entityNames)));
        return mock;
    }

    private static Authentication authWith(String... authorities) {
        List<SimpleGrantedAuthority> list = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new UsernamePasswordAuthenticationToken("user", "n/a", list);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    // Test A — D-06 admin bypass ON
    @Test
    void admin_with_bypass_on_returns_null() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(ragProps(true), embeddingProps(), emptyExposurePolicy());
        Authentication auth = authWith(AiAgentAdminRole.CODE);

        assertThat(builder.buildFor(auth)).isNull();
    }

    @Test
    void jmix_prefixed_admin_authority_with_bypass_on_returns_null() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(ragProps(true), embeddingProps(), emptyExposurePolicy());
        Authentication auth = authWith("ROLE_AI_AGENT_ADMIN");

        assertThat(builder.buildFor(auth)).isNull();
    }

    @Test
    void jmix_system_full_access_with_bypass_on_returns_null() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(ragProps(true), embeddingProps(), emptyExposurePolicy());
        Authentication auth = authWith("ROLE_SYSTEM_FULL_ACCESS");

        assertThat(builder.buildFor(auth)).isNull();
    }

    // Test B — D-06 admin bypass OFF forces admin through role-overlap filter
    @Test
    void admin_with_bypass_off_gets_role_overlap_filter() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(ragProps(false), embeddingProps(), emptyExposurePolicy());
        Authentication auth = authWith(AiAgentAdminRole.CODE);

        Filter.Expression exp = builder.buildFor(auth);

        assertThat(exp).isNotNull();
        String rendered = exp.toString();
        assertThat(rendered).contains(ChunkMetadata.EMBEDDING_MODEL);
        assertThat(rendered).contains(EMBEDDING_MODEL);
        assertThat(rendered).contains(ChunkMetadata.roleFlagKey(AiAgentAdminRole.CODE));
    }

    // Test C — non-admin gets model pin + normalized role flag
    @Test
    void non_admin_gets_embedding_pin_and_role_flag() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(ragProps(true), embeddingProps(), emptyExposurePolicy());
        Authentication auth = authWith(AiAgentUserRole.CODE);

        Filter.Expression exp = builder.buildFor(auth);

        assertThat(exp).isNotNull();
        String rendered = exp.toString();
        assertThat(rendered).contains(ChunkMetadata.EMBEDDING_MODEL);
        assertThat(rendered).contains(EMBEDDING_MODEL);
        assertThat(rendered).contains(ChunkMetadata.roleFlagKey(AiAgentUserRole.CODE));
        // Non-admin path must NOT contain the fail-closed sentinel.
        assertThat(rendered).doesNotContain("__none__");
    }

    @Test
    void jmix_prefixed_non_admin_authority_maps_back_to_role_code_flag() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(ragProps(true), embeddingProps(), emptyExposurePolicy());
        Authentication auth = authWith("ROLE_AI_AGENT_USER");

        Filter.Expression exp = builder.buildFor(auth);

        assertThat(exp).isNotNull();
        String rendered = exp.toString();
        assertThat(rendered).contains(ChunkMetadata.roleFlagKey(AiAgentUserRole.CODE));
        assertThat(rendered).doesNotContain(ChunkMetadata.ROLE_FLAG_PREFIX + "role_ai_agent_user");
    }

    // Test D — D-05 empty roles fail-closed
    @Test
    void empty_roles_fail_closed_uses_sentinel() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(ragProps(true), embeddingProps(), emptyExposurePolicy());
        Authentication auth = new UsernamePasswordAuthenticationToken("anon", "n/a", List.of());

        Filter.Expression exp = builder.buildFor(auth);

        assertThat(exp).isNotNull();
        String rendered = exp.toString();
        assertThat(rendered).contains("__none__");
        assertThat(rendered).contains(ChunkMetadata.DOCUMENT_ID);
        // Embedding-model pin is still ANDed in even on the fail-closed branch.
        assertThat(rendered).contains(ChunkMetadata.EMBEDDING_MODEL);
    }

    // Test E — multi-role ANY semantics: both flags present, joined via OR
    @Test
    void multi_role_produces_or_of_role_flags() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(ragProps(true), embeddingProps(), emptyExposurePolicy());
        Authentication auth = authWith(AiAgentUserRole.CODE, "custom-host-role");

        Filter.Expression exp = builder.buildFor(auth);

        assertThat(exp).isNotNull();
        String rendered = exp.toString();
        assertThat(rendered).contains(ChunkMetadata.roleFlagKey(AiAgentUserRole.CODE));
        assertThat(rendered).contains(ChunkMetadata.roleFlagKey("custom-host-role"));
        assertThat(rendered).doesNotContain(ChunkMetadata.ROLE_FLAG_PREFIX + "custom-host-role");
        // Guard precedence sensitivity in SQL/JSONPath converters: model pin is repeated per role.
        assertThat(countOccurrences(rendered, ChunkMetadata.EMBEDDING_MODEL)).isGreaterThanOrEqualTo(2);
        // The two role clauses must be OR-ed (D-09 ANY), not AND-ed.
        assertThat(rendered).contains("OR");
    }

    // Test F — defensive: null Authentication → fail-closed empty-roles path
    @Test
    void null_authentication_falls_through_to_fail_closed() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(ragProps(true), embeddingProps(), emptyExposurePolicy());

        Filter.Expression exp = builder.buildFor(null);

        assertThat(exp).isNotNull();
        String rendered = exp.toString();
        assertThat(rendered).contains("__none__");
        assertThat(rendered).contains(ChunkMetadata.EMBEDDING_MODEL);
    }
}
