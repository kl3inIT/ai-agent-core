package com.vn.agent.filter.literal;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;

/**
 * Strategy that coerces ONE raw JSON-deserialized filter literal ({@code Object}) into the Java type
 * a single {@link MetaProperty} expects, mirroring the {@code PropertyIntrospector} contributor
 * pattern in {@code com.vn.agent.extraction.introspection}. Implementations are Spring beans
 * auto-collected into a {@code List} by {@link com.vn.agent.filter.FilterLiteralValueConverter}; the
 * first whose {@link #supports(MetaProperty)} returns {@code true} wins (order via {@code @Order} —
 * reference/enum first, datatype last).
 *
 * <p>A host app teaches the literal coercer about a custom property type (e.g. a custom
 * {@link io.jmix.core.metamodel.datatype.Datatype}, money/geo type) by contributing its own
 * {@code @Component LiteralValueConverter} — no fork of the add-on, no edit to the coordinator. Give
 * the host bean a narrower {@link #supports} plus an earlier {@code @Order} to override a built-in.</p>
 *
 * <p>Conversion is strict and fail-closed: implementations throw {@link ToolUserError} with a
 * structured DTO on any mismatch — they NEVER log a stack trace or leak internals to the LLM.</p>
 */
public interface LiteralValueConverter {

    /**
     * Whether this converter handles the given attribute. Dispatch is on the property's
     * {@link Range} kind ({@link Range#isClass()}/{@link Range#isEnum()}/{@link Range#isDatatype()}).
     */
    boolean supports(MetaProperty metaProperty);

    /**
     * Coerce a single non-null scalar value against {@code metaProperty}. Callers guarantee
     * {@code raw} is non-null (the coordinator null-checks first). Throws {@link ToolUserError} on
     * mismatch.
     */
    Object convert(Object raw, MetaProperty metaProperty);
}
