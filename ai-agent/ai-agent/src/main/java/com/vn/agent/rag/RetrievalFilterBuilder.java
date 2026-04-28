package com.vn.agent.rag;

import com.vn.agent.exposure.LlmExposurePolicy;
import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.security.AiAgentAdminRole;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure-function bean: {@code Filter.Expression buildFor(Authentication)} — D-11.
 *
 * <ul>
 *   <li><b>Admin bypass (D-06)</b> — when {@code admin-bypass=true} (default) AND the caller
 *       holds {@link AiAgentAdminRole#CODE} or Jmix's {@code system-full-access}, role-overlap
 *       filtering is bypassed. Exposure denylist filtering still applies, because exposure rules
 *       narrow the LLM-visible surface for every user. If no denylist exists, the method returns
 *       {@code null} and the caller skips setting the
 *       {@code VectorStoreDocumentRetriever.FILTER_EXPRESSION} advisor param.</li>
 *   <li><b>Non-admin</b> — returns {@code OR over (eq(embeddingModel,current) AND role_* flag)}
 *       (D-03 embedding-model drift clause + D-09 ANY role-overlap semantics).</li>
 *   <li><b>Empty roles / null authentication</b> — returns a fail-closed filter that matches
 *       zero chunks via the sentinel {@code documentId == "__none__"} (D-05).</li>
 * </ul>
 *
 * <p>Role flags use Option A flattening per PATTERNS.md — each role code becomes a
 * {@code role_<hex-encoded-role-code>} boolean-true metadata key. This is portable across
 * Spring AI vector-store adapters (RESEARCH Pitfall #1) without collapsing distinct role codes
 * such as {@code sales-admin} and {@code sales_admin}. The ingestion worker (Plan 05-03) MUST
 * mirror this flattening when writing {@code Document.metadata}.</p>
 *
 * <p>Jmix exposes role assignments to Spring Security as authorities like
 * {@code ROLE_AI_AGENT_USER}. Ingestion stores the underlying Jmix role code
 * ({@code ai-agent-user}), so authorities are normalised back to role codes before filter
 * construction.</p>
 *
 * <p><b>WARNING-11 / Scaling note:</b> the role clause is composed as
 * {@code (model AND roleA) OR (model AND roleB) OR ...} rather than the algebraically
 * equivalent {@code model AND (roleA OR roleB OR ...)}. This duplication of the
 * model-pin clause is intentional — it avoids relying on Spring AI converter
 * parenthesization for the {@code OR} subgroup (a quirk that has bitten previous
 * Spring AI 1.x versions). The trade-off is linear growth in expression size: a user
 * with N roles produces N copies of the model-pin clause. Phase 10's expected role
 * count per user is &lt;10 so JSONPath converter token limits are not a concern; the
 * unit-level guard test {@code RetrievalFilterBuilderRoleScalingGuardTest} pins the
 * clause shape and the per-role multiplication so a future flatten-the-OR refactor
 * is detected immediately. Users with very large role sets (50+) may wish to
 * benchmark before relying on this branch.</p>
 */
@Component
public class RetrievalFilterBuilder {

    static final String JMIX_RESOURCE_ROLE_AUTHORITY_PREFIX = "ROLE_";
    static final String JMIX_ROW_LEVEL_ROLE_AUTHORITY_PREFIX = "ROW_LEVEL_ROLE_";
    static final String JMIX_SYSTEM_FULL_ACCESS_ROLE_CODE = "system-full-access";

    private final AiAgentRagProperties ragProps;
    private final AiAgentEmbeddingProperties embeddingProps;
    private final LlmExposurePolicy llmExposurePolicy;

    public RetrievalFilterBuilder(AiAgentRagProperties ragProps,
                                  AiAgentEmbeddingProperties embeddingProps,
                                  LlmExposurePolicy llmExposurePolicy) {
        this.ragProps = ragProps;
        this.embeddingProps = embeddingProps;
        this.llmExposurePolicy = llmExposurePolicy;
    }

    public Filter.Expression buildFor(Authentication auth) {
        Set<String> roles = auth == null
                ? Set.of()
                : auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .map(RetrievalFilterBuilder::toResourceRoleCode)
                        .filter(roleCode -> !roleCode.isBlank())
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        String currentModel = embeddingProps.resolvedModel();
        FilterExpressionBuilder.Op modelPin = b.eq(ChunkMetadata.EMBEDDING_MODEL, currentModel);

        // EXP-05 / D-05: entity denylist NOT IN clause. Admin bypass must not bypass this
        // clause; exposure rules are "current user visibility AND NOT excluded".
        //
        // D-06 (legacy-doc carve-out, Fix R6): chunks WITHOUT a SOURCE_ENTITY metadata key MUST
        // remain visible regardless of denylist contents. The pgvector backend stores chunk
        // metadata as JSONB and translates filter expressions into JSONPath predicates; with a
        // bare nin predicate the missing-key case is converter-dependent and Spring AI's
        // pgvector converter has historically excluded such rows (the JSONPath predicate
        // evaluates to NULL → filtered out). To guarantee D-06 is satisfied across all current
        // and future Spring AI 1.1.x converters, we use the defensive nullable form:
        //
        //     (source_entity IS NULL) OR (source_entity NOT IN <denied>)
        //
        // FilterExpressionBuilder.isNull() and nin() are both confirmed present on the generic
        // builder (Spring AI 1.1.4) and pgvector implements both predicates.
        Set<String> denied = llmExposurePolicy.getDenylistedEntityNames();
        FilterExpressionBuilder.Op exposureClause = null;
        if (!denied.isEmpty()) {
            exposureClause = b.or(
                    b.isNull(ChunkMetadata.SOURCE_ENTITY),
                    b.nin(ChunkMetadata.SOURCE_ENTITY, new ArrayList<>(denied)));
        }

        // D-06: admin bypass bypasses role-overlap checks only. If a denylist exists, keep the
        // model pin plus exposure clause; if no denylist exists, preserve the historical null
        // return so the caller omits FILTER_EXPRESSION entirely.
        if (ragProps.isAdminBypass()
                && (roles.contains(AiAgentAdminRole.CODE)
                || roles.contains(JMIX_SYSTEM_FULL_ACCESS_ROLE_CODE))) {
            return exposureClause == null ? null : b.and(modelPin, exposureClause).build();
        }

        // D-05 fail-closed: empty role set (anonymous or unauthenticated) → impossible match.
        if (roles.isEmpty()) {
            FilterExpressionBuilder.Op unreachable = b.eq(ChunkMetadata.DOCUMENT_ID, "__none__");
            return b.and(modelPin, unreachable).build();
        }

        // D-09 ANY semantics via Option A flattened role flags.
        // Compose as OR of conjunctions to avoid relying on converter parenthesization:
        // (model && roleA) || (model && roleB) || ...
        FilterExpressionBuilder.Op scopedAnyRole = null;
        for (String role : roles) {
            String key = ChunkMetadata.roleFlagKey(role);
            FilterExpressionBuilder.Op roleClause = b.eq(key, true);
            FilterExpressionBuilder.Op modelAndRole = b.and(modelPin, roleClause);
            scopedAnyRole = (scopedAnyRole == null)
                    ? modelAndRole
                    : b.or(scopedAnyRole, modelAndRole);
        }

        if (exposureClause != null) {
            scopedAnyRole = (scopedAnyRole == null)
                    ? exposureClause
                    : b.and(scopedAnyRole, exposureClause);
        }

        return scopedAnyRole.build();
    }

    private static String toResourceRoleCode(String authority) {
        if (authority == null || authority.isBlank()) {
            return "";
        }
        if (authority.startsWith(JMIX_ROW_LEVEL_ROLE_AUTHORITY_PREFIX)) {
            return "";
        }
        if (authority.startsWith(JMIX_RESOURCE_ROLE_AUTHORITY_PREFIX)) {
            return authority.substring(JMIX_RESOURCE_ROLE_AUTHORITY_PREFIX.length())
                    .toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-');
        }
        return "";
    }
}
