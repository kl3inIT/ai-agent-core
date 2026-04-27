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
 *       holds {@link AiAgentAdminRole#CODE} or Jmix's {@code system-full-access}, returns
 *       {@code null}. The caller (DefaultChatServiceImpl) MUST skip setting the
 *       {@code VectorStoreDocumentRetriever.FILTER_EXPRESSION} advisor param when this returns
 *       {@code null}; the retriever then runs without any filter.</li>
 *   <li><b>Non-admin</b> — returns {@code OR over (eq(embeddingModel,current) AND role_* flag)}
 *       (D-03 embedding-model drift clause + D-09 ANY role-overlap semantics).</li>
 *   <li><b>Empty roles / null authentication</b> — returns a fail-closed filter that matches
 *       zero chunks via the sentinel {@code documentId == "__none__"} (D-05).</li>
 * </ul>
 *
 * <p>Role flags use Option A flattening per PATTERNS.md — each role code becomes a
 * normalized {@code role_<normalized-code>} boolean-true metadata key. This is portable across Spring AI
 * vector-store adapters (RESEARCH Pitfall #1). The ingestion worker (Plan 05-03) MUST mirror this
 * flattening when writing {@code Document.metadata}.</p>
 *
 * <p>Jmix exposes role assignments to Spring Security as authorities like
 * {@code ROLE_AI_AGENT_USER}. Ingestion stores the underlying Jmix role code
 * ({@code ai-agent-user}), so authorities are normalised back to role codes before filter
 * construction.</p>
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
                        .map(RetrievalFilterBuilder::toRoleCode)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        // D-06: admin bypass returns null so caller omits the FILTER_EXPRESSION param entirely.
        if (ragProps.isAdminBypass()
                && (roles.contains(AiAgentAdminRole.CODE)
                || roles.contains(JMIX_SYSTEM_FULL_ACCESS_ROLE_CODE))) {
            return null;
        }

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        String currentModel = embeddingProps.resolvedModel();
        FilterExpressionBuilder.Op modelPin = b.eq(ChunkMetadata.EMBEDDING_MODEL, currentModel);

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

        // EXP-05 / D-05: entity denylist NOT IN clause. Admin bypass (above) already returned
        // null so this code only runs for authenticated non-admin users.
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
        if (!denied.isEmpty()) {
            FilterExpressionBuilder.Op notInClause = b.or(
                    b.isNull(ChunkMetadata.SOURCE_ENTITY),
                    b.nin(ChunkMetadata.SOURCE_ENTITY, new ArrayList<>(denied)));
            scopedAnyRole = (scopedAnyRole == null)
                    ? notInClause
                    : b.and(scopedAnyRole, notInClause);
        }

        return scopedAnyRole.build();
    }

    private static String toRoleCode(String authority) {
        if (authority == null || authority.isBlank()) {
            return "";
        }
        if (authority.startsWith(JMIX_ROW_LEVEL_ROLE_AUTHORITY_PREFIX)) {
            return authority.substring(JMIX_ROW_LEVEL_ROLE_AUTHORITY_PREFIX.length())
                    .toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-');
        }
        if (authority.startsWith(JMIX_RESOURCE_ROLE_AUTHORITY_PREFIX)) {
            return authority.substring(JMIX_RESOURCE_ROLE_AUTHORITY_PREFIX.length())
                    .toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-');
        }
        return authority;
    }
}
