package com.vn.agent.filter;

import java.util.List;

/**
 * Conjunction of child filter nodes (D-06). Serialized as {@code {"and": [...]}}.
 * Children list is defensively copied (immutable) at construction.
 */
public record AndNode(List<FilterNode> and) implements FilterNode {
    public AndNode {
        and = and == null ? List.of() : List.copyOf(and);
    }
}
