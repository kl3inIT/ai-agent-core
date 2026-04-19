package com.vn.agent.filter;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Structured filter root (TOOL-05, D-06). JSON shapes:
 * <ul>
 *   <li>{@link AndNode}  — {@code {"and": [child, child, ...]}}</li>
 *   <li>{@link OrNode}   — {@code {"or":  [child, child, ...]}}</li>
 *   <li>{@link NotNode}  — {@code {"not": child}}</li>
 *   <li>{@link LeafNode} — {@code {"property": "name", "operation": "EQUAL", "value": ...}}</li>
 * </ul>
 * Jackson uses {@link JsonTypeInfo.Id#DEDUCTION} polymorphic deserialization via the
 * {@link JsonSubTypes} registered here: the presence of the distinguishing property
 * ({@code and}, {@code or}, {@code not}, or {@code property}) selects the record type.
 *
 * <p>Sealed: exactly four permitted subtypes. The
 * {@code StructuredFilterConditionMapper} relies on exhaustive {@code switch} over this
 * hierarchy; the Java compiler enforces exhaustiveness.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
        @JsonSubTypes.Type(AndNode.class),
        @JsonSubTypes.Type(OrNode.class),
        @JsonSubTypes.Type(NotNode.class),
        @JsonSubTypes.Type(LeafNode.class)
})
public sealed interface FilterNode permits AndNode, OrNode, NotNode, LeafNode {
}
