package com.vn.agent.filter;

/**
 * Negation of a single child filter node (D-06). Serialized as {@code {"not": child}}.
 *
 * <p>Logical NOT is expanded via DeMorgan recursively inside {@code FilterDslMapper}
 * (Pitfall 6): there is no raw-JPQL escape hatch in v1.</p>
 */
public record NotNode(FilterNode not) implements FilterNode {
}
