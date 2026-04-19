package com.vn.agent.filter;

/**
 * Leaf of the filter DSL (D-06). Serialized as
 * {@code {"property": "name", "operation": "EQUAL", "value": ...}}.
 *
 * <p>{@code operation} is a case-insensitive String matching one of the 13 D-05 operator names
 * (see {@code FilterDslMapper}). {@code value} may be a primitive, String, {@code List},
 * or {@code null} (only for {@code IS_SET} with Boolean argument — coerced in
 * {@code LiteralCoercer}).</p>
 */
public record LeafNode(String property, String operation, Object value) implements FilterNode {
}
