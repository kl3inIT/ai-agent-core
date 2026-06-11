package com.vn.agent.tools.jpql;

import com.vn.agent.exposure.LlmExposurePolicy;
import io.jmix.core.AccessManager;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetadataObject;
import io.jmix.core.security.AccessDeniedException;
import io.jmix.core.security.EntityOp;
import io.jmix.data.QueryTransformerFactory;
import io.jmix.data.accesscontext.LoadValuesAccessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes LLM-authored JPQL value-queries for the {@code run_jpql_query} analytics tool.
 *
 * <p><b>Security model</b> — the thin Jmix-native model used by the jmix-crm reference assistant,
 * plus this addon's LLM-exposure overlay:
 * <ol>
 *   <li>{@link LoadValuesAccessContext} + {@link AccessManager#applyRegisteredConstraints} is the
 *       framework pre-flight gate: the query is rejected unless the current user has entity READ
 *       permission on every entity it references.</li>
 *   <li>The <b>secured</b> {@link DataManager#loadValues} applies row-level constraints from the
 *       user's row-level roles automatically.</li>
 *   <li><b>Addon overlay (new):</b> every entity the query references is additionally checked
 *       against {@link LlmExposurePolicy#canReadEntity} so entities the admin hid from the LLM
 *       surface (denylist) cannot be reached through raw JPQL even if the user could read them via
 *       a normal Jmix UI.</li>
 * </ol>
 *
 * <p>This is the ONE tool that builds a query from LLM text, so it is the ONE place that needs
 * {@link LoadValuesAccessContext}; the structured read tools use the typed {@link DataManager}
 * loader where Jmix security applies implicitly (no raw-query gate required).
 *
 * <p><b>Error posture</b> — failures return the raw JPQL/EclipseLink message to the caller so the
 * LLM can self-correct; this is a deliberate exception to the addon's sanitized-error contract for
 * the structured tools (read-only analytics power-tool; leaking referenced entity names is
 * acceptable here).
 */
@Component
public class AiJpqlQueryService {

    private static final Logger log = LoggerFactory.getLogger(AiJpqlQueryService.class);

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private final Metadata metadata;
    private final DataManager dataManager;
    private final AccessManager accessManager;
    private final LlmExposurePolicy llmExposurePolicy;
    private final JpqlResultConverter resultConverter;
    private final AiJpqlParameterConverter parameterConverter;
    private final QueryTransformerFactory queryTransformerFactory;
    private final JpqlNamedParameterParser namedParameterParser;

    public AiJpqlQueryService(Metadata metadata,
                              DataManager dataManager,
                              AccessManager accessManager,
                              LlmExposurePolicy llmExposurePolicy,
                              JpqlResultConverter resultConverter,
                              AiJpqlParameterConverter parameterConverter,
                              QueryTransformerFactory queryTransformerFactory,
                              JpqlNamedParameterParser namedParameterParser) {
        this.metadata = metadata;
        this.dataManager = dataManager;
        this.accessManager = accessManager;
        this.llmExposurePolicy = llmExposurePolicy;
        this.resultConverter = resultConverter;
        this.parameterConverter = parameterConverter;
        this.queryTransformerFactory = queryTransformerFactory;
        this.namedParameterParser = namedParameterParser;
    }

    public JpqlQueryResult executeJpqlQuery(String jpqlQuery,
                                            JpqlParameters parameters,
                                            List<String> selectAliases,
                                            Integer offset,
                                            Integer limit) {
        int effectiveOffset = (offset != null) ? Math.max(0, offset) : 0;
        int effectiveLimit = (limit != null) ? Math.clamp(limit, 1, MAX_LIMIT) : DEFAULT_LIMIT;

        // Pre-flight: Jmix entity-read gate + addon LLM-exposure overlay. Throws on denial; the
        // caller (BuiltInJpqlTool) turns the throw into a failed result for the LLM.
        ensureQueryIsPermitted(jpqlQuery);

        // First attempt: converted parameters.
        JpqlQueryResult result = executeWithParameters(
                jpqlQuery, parameters, selectAliases, effectiveOffset, effectiveLimit, true, true);
        if (!result.success()) {
            // Fallback: original (unconverted) parameters — heuristic coercion can over-eagerly
            // mistype a value the JPA engine would have handled correctly.
            log.debug("JPQL failed with converted params, retrying with original params. Error: {}",
                    result.errorMessage());
            return executeWithParameters(
                    jpqlQuery, parameters, selectAliases, effectiveOffset, effectiveLimit, false, true);
        }
        return result;
    }

    private JpqlQueryResult executeWithParameters(String jpqlQuery,
                                                  JpqlParameters parameters,
                                                  List<String> selectAliases,
                                                  int offset,
                                                  int limit,
                                                  boolean converted,
                                                  boolean retryUnknownParameter) {
        try {
            var loadValuesBuilder = dataManager.loadValues(jpqlQuery);
            Set<String> queryParameterNames = namedParameterParser.extractNames(jpqlQuery);

            Map<String, Object> parameterMap = converted
                    ? parameterConverter.convertParameters(parameters == null ? List.of() : parameters.safeParameters())
                    : rawParameterMap(parameters);

            for (Map.Entry<String, Object> entry : parameterMap.entrySet()) {
                // Ignore extra parameters the LLM provided that the JPQL does not reference.
                if (queryParameterNames.contains(entry.getKey())) {
                    loadValuesBuilder.parameter(entry.getKey(), entry.getValue());
                }
            }

            String[] propertyNames = propertyNames(selectAliases);
            if (propertyNames.length > 0) {
                loadValuesBuilder.properties(propertyNames);
            }

            loadValuesBuilder.firstResult(offset);
            loadValuesBuilder.maxResults(limit + 1); // +1 to detect hasMore

            List<KeyValueEntity> results = loadValuesBuilder.list();

            boolean hasMore = results.size() > limit;
            List<KeyValueEntity> page = hasMore ? results.subList(0, limit) : results;
            List<Map<String, Object>> resultMaps = resultConverter.convertToMapList(page, propertyNames);

            return JpqlQueryResult.success(resultMaps, hasMore, offset, limit);
        } catch (Exception e) {
            if (retryUnknownParameter) {
                String unknownParameter = namedParameterParser.extractUnknownParameterName(e.getMessage());
                if (unknownParameter != null && namedParameterParser.contains(parameters, unknownParameter)) {
                    JpqlParameters filtered = namedParameterParser.without(parameters, unknownParameter);
                    return executeWithParameters(jpqlQuery, filtered, selectAliases, offset, limit, converted, false);
                }
            }
            return JpqlQueryResult.failed(e.getMessage());
        }
    }

    private Map<String, Object> rawParameterMap(JpqlParameters parameters) {
        if (parameters == null || parameters.safeParameters().isEmpty()) {
            return Map.of();
        }
        return parameters.safeParameters().stream()
                .collect(Collectors.toMap(JpqlParameter::parameterName, JpqlParameter::parameterValue));
    }

    private String[] propertyNames(List<String> selectAliases) {
        return selectAliases != null ? selectAliases.toArray(new String[0]) : new String[0];
    }

    /**
     * Jmix entity-read gate + addon LLM-exposure overlay. Reuses the {@link LoadValuesAccessContext}
     * that {@link AccessManager} populates with the entities referenced in the query.
     */
    private void ensureQueryIsPermitted(String jpqlQuery) {
        LoadValuesAccessContext queryContext =
                new LoadValuesAccessContext(jpqlQuery, queryTransformerFactory, metadata);
        accessManager.applyRegisteredConstraints(queryContext);
        if (!queryContext.isPermitted()) {
            String entityNames = queryContext.getEntityClasses().stream()
                    .map(MetadataObject::getName)
                    .sorted()
                    .collect(Collectors.joining(","));
            String deniedResource = entityNames.isBlank() ? jpqlQuery : entityNames;
            throw new AccessDeniedException("entity", deniedResource, EntityOp.READ.getId());
        }
        // Addon overlay: block entities the admin hid from the LLM surface (denylist) even when
        // Jmix permissions would allow the user to read them in a normal UI.
        for (MetadataObject entityClass : queryContext.getEntityClasses()) {
            MetaClass metaClass = metadata.findClass(entityClass.getName());
            if (metaClass != null && !llmExposurePolicy.canReadEntity(metaClass)) {
                throw new AccessDeniedException("entity", entityClass.getName(), EntityOp.READ.getId());
            }
        }
    }
}
