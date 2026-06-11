package com.vn.agent.filter;

import com.vn.agent.filter.literal.AbstractLiteralValueConverter;
import com.vn.agent.filter.literal.LiteralValueConverter;
import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.datatype.Datatype;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Strict, fail-closed filter-literal conversion (D-07). Converts a raw JSON-deserialized value
 * ({@code Object}) to the Java type expected by a {@link MetaProperty}, throwing
 * {@link ToolUserError} with a structured DTO on any failure — NEVER logs a stack trace or
 * leaks internals to the LLM.
 *
 * <p>Coordinator over a list of {@link LiteralValueConverter} strategy beans (mirrors
 * {@code MetaClassDtoSynthesizer} over {@code PropertyIntrospector}): the entity-generic glue
 * — null handling, list/boolean special operators, the {@code unsupported_type} fallback — lives
 * here; each property's per-{@link Range} coercion is produced by the first strategy whose
 * {@link LiteralValueConverter#supports} matches (reference/enum first, datatype last via
 * {@code @Order}). Hosts add a property type by contributing a
 * {@code @Component LiteralValueConverter}; this coordinator never changes.</p>
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Association attribute ({@link Range#isClass()}): value must be a String parseable by
 *       {@link java.util.UUID#fromString}.</li>
 *   <li>Enum attribute ({@link Range#isEnum()}): value must match either {@code enumValues[].id}
 *       from {@code describe_entity} or the Java enum constant name.</li>
 *   <li>Datatype attribute ({@link Range#isDatatype()}): delegate to
 *       {@link Datatype#parse(String)} — Jmix's canonical parser covering every registered
 *       datatype, including user-defined ones — plus the narrow UUID/Boolean/number/ISO-temporal
 *       special cases.</li>
 *   <li>Anything else → {@code unsupported_type}.</li>
 * </ul>
 *
 * <p>Special {@link #convertListValues} for {@code IN_LIST}/{@code NOT_IN_LIST} converts each
 * element separately. {@link #convertBooleanValue} is used for {@code IS_SET} and takes no
 * MetaProperty.</p>
 */
@Component
public class FilterLiteralValueConverter {

    private final List<LiteralValueConverter> converters;

    public FilterLiteralValueConverter(List<LiteralValueConverter> converters) {
        this.converters = converters;
    }

    /**
     * Convert a single scalar value against {@code metaProperty}. Throws {@link ToolUserError}
     * on mismatch. Must not be called with a {@link Collection}; use
     * {@link #convertListValues}.
     */
    public Object convertValue(Object raw, MetaProperty metaProperty) {
        if (raw == null) {
            throw new ToolUserError("invalid_literal",
                    "value must not be null for " + metaProperty.getName());
        }
        for (LiteralValueConverter converter : converters) {
            if (converter.supports(metaProperty)) {
                return converter.convert(raw, metaProperty);
            }
        }
        throw new ToolUserError("unsupported_type",
                "no literal conversion for attribute " + metaProperty.getName());
    }

    /**
     * Coerce a {@link Collection} or array to a {@link List} of coerced elements. Used by
     * {@code IN_LIST}/{@code NOT_IN_LIST} operators.
     */
    public List<Object> convertListValues(Object raw, MetaProperty metaProperty) {
        if (raw == null) {
            throw new ToolUserError("invalid_literal",
                    "IN_LIST/NOT_IN_LIST value must not be null for " + metaProperty.getName());
        }
        Collection<?> sourceValues;
        if (raw instanceof Collection<?> collection) {
            sourceValues = collection;
        } else if (raw instanceof Object[] array) {
            sourceValues = Arrays.asList(array);
        } else {
            throw new ToolUserError("invalid_literal",
                    "IN_LIST/NOT_IN_LIST value must be a JSON array for " + metaProperty.getName(),
                    List.of("JSON array of values, e.g. [1,2,3]"));
        }
        if (sourceValues.isEmpty()) {
            throw new ToolUserError("invalid_literal",
                    "IN_LIST/NOT_IN_LIST requires a non-empty array for " + metaProperty.getName());
        }
        List<Object> convertedValues = new ArrayList<>(sourceValues.size());
        for (Object element : sourceValues) {
            convertedValues.add(convertValue(element, metaProperty));
        }
        return convertedValues;
    }

    /**
     * Coerce a value to {@link Boolean} for the {@code IS_SET} operator. Accepts
     * {@link Boolean} or the strings {@code "true"}/{@code "false"} (case-insensitive). Shares the
     * acceptance contract with the datatype strategy via
     * {@link AbstractLiteralValueConverter#convertBooleanValue}.
     */
    public Boolean convertBooleanValue(Object raw, String propertyName) {
        return AbstractLiteralValueConverter.convertBooleanValue(raw, propertyName);
    }
}
