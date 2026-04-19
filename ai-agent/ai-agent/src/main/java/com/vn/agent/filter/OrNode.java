package com.vn.agent.filter;

import java.util.List;

/**
 * Disjunction of child filter nodes (D-06). Serialized as {@code {"or": [...]}}.
 * Children list is defensively copied (immutable) at construction.
 */
public record OrNode(List<FilterNode> or) implements FilterNode {
    public OrNode {
        or = or == null ? List.of() : List.copyOf(or);
    }
}
