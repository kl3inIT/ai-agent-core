package com.vn.agent.filter;

import com.vn.agent.metadata.EffectiveSchemaComputer;
import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.querycondition.Condition;
import io.jmix.core.querycondition.LogicalCondition;
import io.jmix.core.querycondition.PropertyCondition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Structured filter DSL → Jmix {@link Condition} mapper (TOOL-05, D-05/D-06/D-08).
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Exhaustive {@code switch} over the sealed {@link FilterNode} hierarchy — the compiler
 *       enforces completeness.</li>
 *   <li>{@code NOT} expanded via DeMorgan recursively (Pitfall 6 recommendation): no
 *       raw-JPQL escape hatch in v1. The negation flag XORs through
 *       {@code AND}/{@code OR} and flips leaf operators where a mirror exists.</li>
 *   <li>Every attribute path validated per-hop against
 *       {@link EffectiveSchemaComputer#canReadAttribute}/{@link
 *       EffectiveSchemaComputer#canReadEntity} — denied hop ⇒ rejected filter (D-08).</li>
 *   <li>Depth cap from {@code jmix.ai-agent.tools.max-filter-depth} (default 3).</li>
 *   <li>Literal values coerced via {@link LiteralCoercer}; failure ⇒ structured
 *       {@link ToolUserError} (D-07). JPQL/SQL injection is impossible by construction —
 *       every leaf is {@link PropertyCondition#createWithValue} with a typed param.</li>
 * </ul>
 *
 * <p>Plan 03 consumes {@link #map(FilterNode, MetaClass)} inside each @Tool body.</p>
 */
@Component
public class FilterDslMapper {

    private final LiteralCoercer coercer;
    private final EffectiveSchemaComputer schemaComputer;
    private final int maxFilterDepth;

    public FilterDslMapper(LiteralCoercer coercer,
                           EffectiveSchemaComputer schemaComputer,
                           @Value("${jmix.ai-agent.tools.max-filter-depth:3}") int maxFilterDepth) {
        this.coercer = coercer;
        this.schemaComputer = schemaComputer;
        this.maxFilterDepth = maxFilterDepth;
    }

    /**
     * Map a {@link FilterNode} tree rooted at {@code root} against the target {@link MetaClass}.
     * Throws {@link ToolUserError} on any failure (unknown op, denied attribute, bad literal,
     * depth exceeded).
     */
    public Condition map(FilterNode root, MetaClass mc) {
        if (root == null) {
            throw new ToolUserError("invalid_filter", "filter root must not be null");
        }
        return mapInternal(root, mc, false);
    }

    private Condition mapInternal(FilterNode node, MetaClass mc, boolean negated) {
        return switch (node) {
            case AndNode a -> mapLogical(a.and(), mc, negated, true);
            case OrNode o -> mapLogical(o.or(), mc, negated, false);
            case NotNode n -> mapInternal(n.not(), mc, !negated);
            case LeafNode l -> mapLeaf(l, mc, negated);
        };
    }

    private Condition mapLogical(List<FilterNode> children, MetaClass mc,
                                 boolean negated, boolean isAnd) {
        if (children == null || children.isEmpty()) {
            throw new ToolUserError("invalid_filter",
                    "AND/OR node requires at least one child");
        }
        boolean asAnd = isAnd ^ negated; // DeMorgan: NOT(AND)→OR(NOT…), NOT(OR)→AND(NOT…)
        Condition[] mapped = new Condition[children.size()];
        for (int i = 0; i < children.size(); i++) {
            mapped[i] = mapInternal(children.get(i), mc, negated);
        }
        return asAnd ? LogicalCondition.and(mapped) : LogicalCondition.or(mapped);
    }

    private Condition mapLeaf(LeafNode l, MetaClass mc, boolean negated) {
        if (l.property() == null || l.property().isBlank()) {
            throw new ToolUserError("invalid_filter", "leaf property must not be blank");
        }
        if (l.operation() == null || l.operation().isBlank()) {
            throw new ToolUserError("invalid_filter",
                    "leaf operation must not be blank for " + l.property());
        }
        MetaProperty terminal = validatePath(l.property(), mc);
        String op = resolveOperation(l.operation(), negated);

        Object coerced;
        if (PropertyCondition.Operation.IN_LIST.equals(op)
                || PropertyCondition.Operation.NOT_IN_LIST.equals(op)) {
            coerced = coercer.coerceList(l.value(), terminal);
        } else if (PropertyCondition.Operation.IS_SET.equals(op)) {
            Boolean b = coercer.coerceBoolean(l.value(), l.property());
            coerced = negated ? !b : b;
        } else {
            coerced = coercer.coerce(l.value(), terminal);
        }
        return PropertyCondition.createWithValue(l.property(), op, coerced);
    }

    /**
     * Resolve the caller-supplied D-05 operator string (case-insensitive) to the
     * {@link PropertyCondition.Operation} constant. When {@code negated} is true, applies
     * DeMorgan leaf-level negation by returning the complementary op (e.g. {@code EQUAL} →
     * {@code NOT_EQUAL}). STARTS_WITH/ENDS_WITH under NOT is explicitly rejected (v1
     * limitation — DeMorgan cannot express it without regex).
     */
    private String resolveOperation(String dslOp, boolean negated) {
        String key = dslOp.toUpperCase(Locale.ROOT);
        return switch (key) {
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
            // D-05 DSL name "DOES_NOT_CONTAIN" maps to Jmix 2.8 op constant NOT_CONTAINS
            // (Jmix renamed the constant; DSL surface preserved for LLM).
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
                    "operator " + dslOp + " not supported",
                    List.of("EQUAL", "NOT_EQUAL", "GREATER", "GREATER_OR_EQUAL",
                            "LESS", "LESS_OR_EQUAL", "CONTAINS", "DOES_NOT_CONTAIN",
                            "STARTS_WITH", "ENDS_WITH", "IN_LIST", "NOT_IN_LIST", "IS_SET"));
        };
    }

    /**
     * Walk the dotted attribute path against {@code mc}, enforcing the depth cap (D-08) and
     * per-hop {@link EffectiveSchemaComputer#canReadAttribute}/{@link
     * EffectiveSchemaComputer#canReadEntity} checks. Returns the terminal {@link MetaProperty}
     * which the caller needs for literal coercion.
     */
    private MetaProperty validatePath(String path, MetaClass mc) {
        String[] segments = path.split("\\.");
        if (segments.length > maxFilterDepth) {
            throw new ToolUserError("filter_depth_exceeded",
                    "path " + path + " exceeds depth " + maxFilterDepth);
        }
        MetaClass currentMc = mc;
        MetaProperty mp = null;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            mp = currentMc.findProperty(segment);
            if (mp == null) {
                throw new ToolUserError("unknown_attribute",
                        "no attribute " + segment + " on " + currentMc.getName());
            }
            if (!schemaComputer.canReadAttribute(currentMc, segment)) {
                throw new ToolUserError("access_denied",
                        "attribute not readable: " + currentMc.getName() + "." + segment);
            }
            if (mp.getRange().isClass() && i < segments.length - 1) {
                currentMc = mp.getRange().asClass();
                if (!schemaComputer.canReadEntity(currentMc)) {
                    throw new ToolUserError("access_denied",
                            "entity not readable along path: " + currentMc.getName());
                }
            }
        }
        return mp;
    }
}
