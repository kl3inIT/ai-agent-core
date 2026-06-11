package com.vn.agent.extraction.introspection;

import io.jmix.core.metamodel.model.MetaProperty;

import java.util.Map;

/**
 * Strategy that turns ONE entity attribute into its prompt-side JSON-schema fragment, mirroring
 * jmix-crm's {@code MetaPropertyIntrospector} contributor pattern. Implementations are Spring beans
 * auto-collected into a {@code List} by {@code MetaClassDtoSynthesizer}; the first whose
 * {@link #supports(MetaProperty)} returns {@code true} wins (order via {@code @Order}).
 *
 * <p>A host app teaches the LLM schema about a custom property type (e.g. a custom {@code Datatype},
 * JSON column, money/geo type) by contributing its own {@code @Component PropertyIntrospector} —
 * no fork of the add-on, no edit to the coordinator. Give the host bean a narrower
 * {@link #supports} plus an earlier {@code @Order} to override a built-in.</p>
 */
public interface PropertyIntrospector {

    /** Whether this introspector produces the schema fragment for the given attribute. */
    boolean supports(MetaProperty metaProperty);

    /**
     * The JSON-schema fragment for the attribute (e.g. {@code {"type":"string","format":"uuid"}}).
     * Returns an empty map when the attribute yields no schema (the coordinator then skips it).
     */
    Map<String, Object> buildSchema(MetaProperty metaProperty);
}
