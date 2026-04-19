package com.vn.agent.filter;

/**
 * Leaf of the structured filter shape (D-06). Serialized as
 * {@code {"property": "name", "operation": "EQUAL", "value": ...}}.
 *
 * <p>{@code operation} is a case-insensitive String matching one of the 13 D-05 operator names
 * (see {@code StructuredFilterConditionMapper}). {@code value} may be a primitive, String, {@code List},
 * or {@code null} (only for {@code IS_SET} with Boolean argument — converted in
 * {@code FilterLiteralValueConverter}).</p>
 */
public record LeafNode(String property, String operation, Object value) implements FilterNode {
}
