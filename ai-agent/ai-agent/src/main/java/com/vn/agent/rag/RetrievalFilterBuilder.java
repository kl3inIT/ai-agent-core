package com.vn.agent.rag;

import com.vn.agent.rag.config.AiAgentEmbeddingProperties;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.security.AiAgentAdminRole;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure-function bean: {@code Filter.Expression buildFor(Authentication)} — D-11.
 *
 * <ul>
 *   <li><b>Admin bypass (D-06)</b> — when {@code admin-bypass=true} (default) AND the caller
 *       holds {@link AiAgentAdminRole#CODE}, returns {@code null}. The caller (DefaultChatServiceImpl)
 *       MUST skip setting the {@code VectorStoreDocumentRetriever.FILTER_EXPRESSION} advisor param
 *       when this returns {@code null}; the retriever then runs without any filter.</li>
 *   <li><b>Non-admin</b> — returns {@code eq(embeddingModel, current) AND (OR over role_* flags)}
 *       (D-03 embedding-model drift clause + D-09 ANY role-overlap semantics).</li>
 *   <li><b>Empty roles / null authentication</b> — returns a fail-closed filter that matches
 *       zero chunks via the sentinel {@code documentId == "__none__"} (D-05).</li>
 * </ul>
 *
 * <p>Role flags use Option A flattening per PATTERNS.md — each role code becomes a
 * {@code role_<lowercase-code>} boolean-true metadata key. This is portable across Spring AI
 * vector-store adapters (RESEARCH Pitfall #1). The ingestion worker (Plan 05-03) MUST mirror this
 * flattening when writing {@code Document.metadata}.</p>
 */
@Component
public class RetrievalFilterBuilder {

    private final AiAgentRagProperties ragProps;
    private final AiAgentEmbeddingProperties embeddingProps;

    public RetrievalFilterBuilder(AiAgentRagProperties ragProps,
                                  AiAgentEmbeddingProperties embeddingProps) {
        this.ragProps = ragProps;
        this.embeddingProps = embeddingProps;
    }

    public Filter.Expression buildFor(Authentication auth) {
        Set<String> roles = auth == null
                ? Set.of()
                : auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toCollection(LinkedHashSet::new));

        // D-06: admin bypass returns null so caller omits the FILTER_EXPRESSION param entirely.
        if (ragProps.isAdminBypass() && roles.contains(AiAgentAdminRole.CODE)) {
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
        FilterExpressionBuilder.Op roleOverlap = null;
        for (String role : roles) {
            String key = ChunkMetadata.ROLE_FLAG_PREFIX + role.toLowerCase(Locale.ROOT);
            FilterExpressionBuilder.Op clause = b.eq(key, true);
            roleOverlap = (roleOverlap == null) ? clause : b.or(roleOverlap, clause);
        }

        return b.and(modelPin, roleOverlap).build();
    }
}
