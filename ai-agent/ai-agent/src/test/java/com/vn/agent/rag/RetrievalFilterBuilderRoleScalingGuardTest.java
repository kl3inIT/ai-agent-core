package com.vn.agent.rag;

import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
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
 * WARNING-11 regression gate.
 *
 * <p>{@link RetrievalFilterBuilder} composes the role clause as
 * {@code (model AND roleA) OR (model AND roleB) OR ...} — the model pin is intentionally
 * duplicated per role to avoid relying on Spring AI converter parenthesization of an
 * outer {@code AND(model, OR(roleA, roleB))} form. The trade-off is linear growth in
 * expression size: a user with N roles produces N copies of the model-pin clause.
 *
 * <p>This test pins the per-role multiplication for a five-role caller. If a future
 * refactor flattens the OR (e.g. {@code model AND (roleA OR roleB OR roleC OR ...)})
 * the model-pin count drops to 1 and this test fails — the maintainer is then forced
 * to update both the implementation comment and the class Javadoc scaling note in a
 * single, deliberate change.
 */
class RetrievalFilterBuilderRoleScalingGuardTest {

    private static final String EMBEDDING_MODEL = "qwen/qwen3-embedding-4b";

    private static AiAgentEmbeddingProperties embeddingProps() {
        return new AiAgentEmbeddingProperties(EMBEDDING_MODEL, 2000, null);
    }

    private static AiAgentRagProperties ragProps() {
        return new AiAgentRagProperties(true, 5, 0.5, null, null, null, null, null, null);
    }

    private static LlmExposurePolicy emptyExposurePolicy() {
        LlmExposurePolicy mock = mock(LlmExposurePolicy.class);
        when(mock.getDenylistedEntityNames()).thenReturn(Set.of());
        return mock;
    }

    private static Authentication authWithRoles(String... roleCodes) {
        // Mirror Jmix's authority shape: ROLE_<UPPER_SNAKE> for each resource role.
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roleCodes)
                .map(code -> "ROLE_" + code.replace('-', '_').toUpperCase())
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new UsernamePasswordAuthenticationToken("alice", null, authorities);
    }

    @Test
    void fiveRolesProduceFiveModelPinClauses() {
        RetrievalFilterBuilder builder = new RetrievalFilterBuilder(
                ragProps(), embeddingProps(), emptyExposurePolicy());

        Authentication auth = authWithRoles(
                "ai-agent-user",
                "ai-agent-reader",
                "ai-agent-writer",
                "ai-agent-reviewer",
                "ai-agent-analyst");

        Filter.Expression expr = builder.buildFor(auth);
        assertThat(expr).isNotNull();

        String rendered = expr.toString();
        // The embedding-model literal appears once per (model AND role) conjunction.
        // For 5 roles we expect 5 occurrences. If a future refactor pulls the model-pin
        // out of the OR — e.g. model AND (roleA OR roleB OR ...) — this count drops to 1.
        long modelPinCount = countOccurrences(rendered, EMBEDDING_MODEL);
        assertThat(modelPinCount)
                .as("Model pin must repeat once per role under the (model AND role) OR ... shape "
                        + "(WARNING-11 scaling guard). If you intentionally flattened the OR, "
                        + "update the class Javadoc scaling note and adjust this assertion.")
                .isEqualTo(5L);
    }

    private static long countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0L;
        }
        long count = 0L;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
