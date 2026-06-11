package com.vn.agent.filter;

import com.vn.agent.metadata.LlmExposurePolicy;
import com.vn.agent.orchestration.AiUiSettingsResolver;
import com.vn.agent.tools.ToolUserError;
import io.jmix.core.QueryUtils;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.querycondition.Condition;
import io.jmix.core.querycondition.JpqlCondition;
import io.jmix.core.querycondition.LogicalCondition;
import io.jmix.core.querycondition.PropertyCondition;
import io.jmix.core.querycondition.PropertyConditionUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Structured filter → Jmix {@link Condition} mapper (TOOL-05, D-05/D-06/D-08).
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Exhaustive {@code switch} over the sealed {@link FilterNode} hierarchy — the compiler
 *       enforces completeness.</li>
 *   <li>{@code NOT} expanded via DeMorgan recursively (Pitfall 6 recommendation): no
 *       raw-JPQL escape hatch in v1. The negation flag XORs through
 *       {@code AND}/{@code OR} and flips leaf operators where a mirror exists.</li>
 *   <li>Every attribute path validated per-hop against
 *       {@link LlmExposurePolicy#canReadAttribute}/{@link LlmExposurePolicy#canReadEntity}
 *       — denied hop ⇒ rejected filter (D-08 + Phase 10 exposure opacity).</li>
 *   <li>Depth cap from {@code jmix.ai-agent.tools.max-filter-depth} (default 3).</li>
 *   <li>Literal values converted via {@link FilterLiteralValueConverter}; failure ⇒ structured
 *       {@link ToolUserError} (D-07). JPQL/SQL injection is impossible by construction —
 *       every leaf uses either {@link PropertyCondition#createWithValue} or a fixed
 *       {@link JpqlCondition} template with a validated metadata path and typed params.</li>
 * </ul>
 *
 * <p>Plan 03 consumes {@link #map(FilterNode, MetaClass)} inside each @Tool body.</p>
 */
@Component
public class StructuredFilterConditionMapper {

    private final FilterLiteralValueConverter filterLiteralValueConverter;
    private final LlmExposurePolicy llmExposurePolicy;
    private final AiUiSettingsResolver aiUiSettingsResolver;

    public StructuredFilterConditionMapper(FilterLiteralValueConverter filterLiteralValueConverter,
                                           LlmExposurePolicy llmExposurePolicy,
                                           AiUiSettingsResolver aiUiSettingsResolver) {
        this.filterLiteralValueConverter = filterLiteralValueConverter;
        this.llmExposurePolicy = llmExposurePolicy;
        this.aiUiSettingsResolver = aiUiSettingsResolver;
    }

    /**
     * Map a {@link FilterNode} tree rooted at {@code root} against the target {@link MetaClass}.
     * Throws {@link ToolUserError} on any failure (unknown op, denied attribute, bad literal,
     * depth exceeded).
     */
    public Condition map(FilterNode root, MetaClass rootMetaClass) {
        if (root == null) {
            throw new ToolUserError("invalid_filter", "filter root must not be null");
        }
        return mapInternal(root, rootMetaClass, false);
    }

    private Condition mapInternal(FilterNode node, MetaClass rootMetaClass, boolean negated) {
        return switch (node) {
            case AndNode(List<FilterNode> children) -> mapLogical(children, rootMetaClass, negated, true);
            case OrNode(List<FilterNode> children) -> mapLogical(children, rootMetaClass, negated, false);
            case NotNode(FilterNode negatedNode) -> mapInternal(negatedNode, rootMetaClass, !negated);
            case LeafNode(String property, String operation, Object value) ->
                    mapLeaf(property, operation, value, rootMetaClass, negated);
        };
    }

    private Condition mapLogical(List<FilterNode> children, MetaClass rootMetaClass,
                                 boolean negated, boolean isAnd) {
        if (children == null || children.isEmpty()) {
            throw new ToolUserError("invalid_filter",
                    "AND/OR node requires at least one child");
        }
        boolean asAnd = isAnd ^ negated; // DeMorgan: NOT(AND)→OR(NOT…), NOT(OR)→AND(NOT…)
        Condition[] mappedConditions = new Condition[children.size()];
        for (int i = 0; i < children.size(); i++) {
            mappedConditions[i] = mapInternal(children.get(i), rootMetaClass, negated);
        }
        return asAnd ? LogicalCondition.and(mappedConditions) : LogicalCondition.or(mappedConditions);
    }

    private Condition mapLeaf(String property, String operationName, Object value,
                              MetaClass rootMetaClass, boolean negated) {
        if (property == null || property.isBlank()) {
            throw new ToolUserError("invalid_filter", "leaf property must not be blank");
        }
        if (operationName == null || operationName.isBlank()) {
            throw new ToolUserError("invalid_filter",
                    "leaf operation must not be blank for " + property);
        }
        MetaProperty terminalProperty = validatePath(property, rootMetaClass);
        String propertyConditionOperation = resolveOperation(operationName, negated);

        Object coerced;
        if (PropertyCondition.Operation.IN_LIST.equals(propertyConditionOperation)
                || PropertyCondition.Operation.NOT_IN_LIST.equals(propertyConditionOperation)) {
            coerced = filterLiteralValueConverter.convertListValues(value, terminalProperty);
        } else if (PropertyCondition.Operation.IS_SET.equals(propertyConditionOperation)) {
            Boolean booleanValue = filterLiteralValueConverter.convertBooleanValue(value, property);
            coerced = negated != booleanValue;
        } else {
            coerced = filterLiteralValueConverter.convertValue(value, terminalProperty);
        }
        if (isStringLikeOperation(propertyConditionOperation, terminalProperty)) {
            if (coerced instanceof String stringValue) {
                return stringLikeCondition(property, propertyConditionOperation, stringValue);
            }
            throw new ToolUserError("invalid_literal",
                    "expected String for " + terminalProperty.getName(),
                    List.of("String"));
        }
        return PropertyCondition.createWithValue(property, propertyConditionOperation, coerced);
    }

    private boolean isStringLikeOperation(String operation, MetaProperty terminalProperty) {
        if (!terminalProperty.getRange().isDatatype()
                || terminalProperty.getRange().asDatatype().getJavaClass() != String.class) {
            return false;
        }
        return PropertyCondition.Operation.CONTAINS.equals(operation)
                || PropertyCondition.Operation.NOT_CONTAINS.equals(operation)
                || PropertyCondition.Operation.STARTS_WITH.equals(operation)
                || PropertyCondition.Operation.ENDS_WITH.equals(operation);
    }

    private Condition stringLikeCondition(String property, String operation, String value) {
        String parameterName = PropertyConditionUtils.generateParameterName(property);
        String patternExpression = switch (operation) {
            case PropertyCondition.Operation.CONTAINS, PropertyCondition.Operation.NOT_CONTAINS ->
                    "concat('%', concat(lower(:" + parameterName + "), '%'))";
            case PropertyCondition.Operation.STARTS_WITH ->
                    "concat(lower(:" + parameterName + "), '%')";
            case PropertyCondition.Operation.ENDS_WITH ->
                    "concat('%', lower(:" + parameterName + "))";
            default -> throw new IllegalArgumentException("Unsupported string LIKE operation: " + operation);
        };
        String jpqlOperation = PropertyCondition.Operation.NOT_CONTAINS.equals(operation)
                ? "not like"
                : "like";
        String where = "lower({E}." + property + ") " + jpqlOperation + " "
                + patternExpression + " escape '\\'";
        return JpqlCondition.createWithParameters(
                where,
                null,
                Map.of(parameterName, QueryUtils.escapeForLike(value)));
    }

    /**
     * Resolve the caller-supplied structured-filter operator string (case-insensitive) to the
     * {@link PropertyCondition.Operation} constant. When {@code negated} is true, applies
     * DeMorgan leaf-level negation by returning the complementary op (e.g. {@code EQUAL} →
     * {@code NOT_EQUAL}). STARTS_WITH/ENDS_WITH under NOT is explicitly rejected (v1
     * limitation — DeMorgan cannot express it without regex).
     */
    private String resolveOperation(String operationName, boolean negated) {
        String normalizedOperationName = operationName.toUpperCase(Locale.ROOT);
        return switch (normalizedOperationName) {
            case "EQUAL" -> negated ? PropertyCondition.Operation.NOT_EQUAL
                    : PropertyCondition.Operation.EQUAL;
            case "NOT_EQUAL" -> negated ? PropertyCondition.Operation.EQUAL
                    : PropertyCondition.Operation.NOT_EQUAL;
            case "GREATER" -> negated ? PropertyCondition.Operation.LESS_OR_EQUAL
                    : PropertyCondition.Operation.GREATER;
            case "GREATER_OR_EQUAL" -> negated ? PropertyCondition.Operation.LESS
                    : PropertyCondition.Operation.GREATER_OR_EQUAL;
            case "LESS" -> negated ? PropertyCondition.Operation.GREATER_OR_EQUAL
                    : PropertyCondition.Operation.LESS;
            case "LESS_OR_EQUAL" -> negated ? PropertyCondition.Operation.GREATER
                    : PropertyCondition.Operation.LESS_OR_EQUAL;
            case "CONTAINS" -> negated ? PropertyCondition.Operation.NOT_CONTAINS
                    : PropertyCondition.Operation.CONTAINS;
            // D-05 structured-filter name "DOES_NOT_CONTAIN" maps to Jmix 2.8 op constant
            // NOT_CONTAINS (Jmix renamed the constant; tool input stays stable for the LLM).
            case "DOES_NOT_CONTAIN", "NOT_CONTAINS" -> negated ? PropertyCondition.Operation.CONTAINS
                    : PropertyCondition.Operation.NOT_CONTAINS;
            case "STARTS_WITH" -> {
                if (negated) {
                    throw new ToolUserError("not_negatable",
                            "NOT over STARTS_WITH is not supported in v1; rewrite filter");
                }
                yield PropertyCondition.Operation.STARTS_WITH;
            }
            case "ENDS_WITH" -> {
                if (negated) {
                    throw new ToolUserError("not_negatable",
                            "NOT over ENDS_WITH is not supported in v1; rewrite filter");
                }
                yield PropertyCondition.Operation.ENDS_WITH;
            }
            case "IN_LIST" -> negated ? PropertyCondition.Operation.NOT_IN_LIST
                    : PropertyCondition.Operation.IN_LIST;
            case "NOT_IN_LIST" -> negated ? PropertyCondition.Operation.IN_LIST
                    : PropertyCondition.Operation.NOT_IN_LIST;
            case "IS_SET" -> PropertyCondition.Operation.IS_SET;
            default -> throw new ToolUserError("unknown_operation",
                    "operator " + operationName + " not supported",
                    List.of("EQUAL", "NOT_EQUAL", "GREATER", "GREATER_OR_EQUAL",
                            "LESS", "LESS_OR_EQUAL", "CONTAINS", "DOES_NOT_CONTAIN",
                            "STARTS_WITH", "ENDS_WITH", "IN_LIST", "NOT_IN_LIST", "IS_SET"));
        };
    }

    /**
     * Walk the dotted attribute path against {@code rootMetaClass}, enforcing the depth cap
     * (D-08) and per-hop {@link LlmExposurePolicy#canReadAttribute}/{@link
     * LlmExposurePolicy#canReadEntity} checks. Returns the terminal
     * {@link MetaProperty} which the caller needs for literal coercion.
     */
    private MetaProperty validatePath(String path, MetaClass rootMetaClass) {
        String[] segments = path.split("\\.");
        // Phase 16 CFG-01 — read depth cap via AiUiSettingsResolver so admin edits on
        // AiUiSettings.toolsMaxFilterDepth take effect without a restart. Null column
        // falls through to jmix.ai-agent.tools.max-filter-depth (default 3).
        int maxFilterDepth = aiUiSettingsResolver.resolveToolsMaxFilterDepth();
        if (segments.length > maxFilterDepth) {
            throw new ToolUserError("filter_depth_exceeded",
                    "path " + path + " exceeds depth " + maxFilterDepth);
        }
        MetaClass currentMetaClass = rootMetaClass;
        MetaProperty currentProperty = null;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            currentProperty = currentMetaClass.findProperty(segment);
            if (currentProperty == null) {
                throw new ToolUserError("unknown_attribute",
                        "no attribute " + segment + " on " + currentMetaClass.getName());
            }
            if (!llmExposurePolicy.canReadAttribute(currentMetaClass, segment)) {
                throw new ToolUserError("unknown_attribute",
                        "no attribute " + segment + " on " + currentMetaClass.getName());
            }
            if (currentProperty.getRange().isClass()) {
                MetaClass nextMetaClass = currentProperty.getRange().asClass();
                if (!llmExposurePolicy.canReadEntity(nextMetaClass)) {
                    throw new ToolUserError("unknown_attribute",
                            "no attribute " + segment + " on " + currentMetaClass.getName());
                }
                if (i < segments.length - 1) {
                    currentMetaClass = nextMetaClass;
                }
            }
        }
        return currentProperty;
    }
}
