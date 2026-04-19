package com.vn.agent.filter;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.datatype.Datatype;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Strict, fail-closed filter-literal conversion (D-07). Converts a raw JSON-deserialized value
 * ({@code Object}) to the Java type expected by a {@link MetaProperty}, throwing
 * {@link ToolUserError} with a structured DTO on any failure — NEVER logs a stack trace or
 * leaks internals to the LLM.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Association attribute ({@link Range#isClass()}): value must be a String parseable by
 *       {@link UUID#fromString}.</li>
 *   <li>Enum attribute ({@link Range#isEnum()}): value must be a String matching an enum
 *       constant by name; valid names included in {@code expected}.</li>
 *   <li>Datatype attribute ({@link Range#isDatatype()}): delegate to
 *       {@link Datatype#parse(String)} — Jmix's canonical parser covering every registered
 *       datatype, including user-defined ones. {@link ParseException} → {@link ToolUserError}.</li>
 *   <li>Anything else → {@code unsupported_type}.</li>
 * </ul>
 *
 * <p>Special {@link #convertListValues} for {@code IN_LIST}/{@code NOT_IN_LIST} converts each
 * element separately. {@link #convertBooleanValue} is used for {@code IS_SET} and takes no
 * MetaProperty.</p>
 */
@Component
public class FilterLiteralValueConverter {

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
        Range range = metaProperty.getRange();
        if (range.isClass()) {
            return convertUuidString(raw, "expected UUID id for " + metaProperty.getName());
        }
        if (range.isEnum()) {
            return convertEnumValue(raw, metaProperty);
        }
        if (range.isDatatype()) {
            return convertDatatypeValue(raw, metaProperty);
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
     * {@link Boolean} or the strings {@code "true"}/{@code "false"} (case-insensitive).
     */
    public Boolean convertBooleanValue(Object raw, String propertyName) {
        if (raw instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (raw instanceof String stringValue) {
            String lowercaseValue = stringValue.toLowerCase(java.util.Locale.ROOT);
            if ("true".equals(lowercaseValue)) {
                return Boolean.TRUE;
            }
            if ("false".equals(lowercaseValue)) {
                return Boolean.FALSE;
            }
        }
        throw new ToolUserError("invalid_literal",
                "IS_SET value must be a Boolean for " + propertyName,
                List.of("true", "false"));
    }

    // ---- internals -------------------------------------------------------

    private Object convertUuidString(Object raw, String reason) {
        if (!(raw instanceof String stringValue)) {
            throw new ToolUserError("invalid_literal", reason,
                    List.of("UUID string, e.g. '4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88'"));
        }
        try {
            return UUID.fromString(stringValue);
        } catch (IllegalArgumentException ex) {
            throw new ToolUserError("invalid_literal", reason,
                    List.of("UUID string, e.g. '4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88'"));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convertEnumValue(Object raw, MetaProperty metaProperty) {
        if (!(raw instanceof String stringValue)) {
            throw new ToolUserError("invalid_literal",
                    "enum value must be a String name for " + metaProperty.getName(),
                    enumConstantNames(metaProperty));
        }
        Class<?> enumClass = metaProperty.getRange().asEnumeration().getJavaClass();
        try {
            return Enum.valueOf((Class<Enum>) enumClass, stringValue);
        } catch (IllegalArgumentException ex) {
            throw new ToolUserError("invalid_literal",
                    "unknown enum constant '" + stringValue + "' for " + metaProperty.getName(),
                    enumConstantNames(metaProperty));
        }
    }

    private List<String> enumConstantNames(MetaProperty metaProperty) {
        List<?> values = metaProperty.getRange().asEnumeration().getValues();
        List<String> names = new ArrayList<>(values.size());
        for (Object enumValue : values) {
            names.add(((Enum<?>) enumValue).name());
        }
        return names;
    }

    /**
     * Delegate scalar datatype coercion to Jmix's {@link Datatype#parse(String)}. Keeps
     * three narrow special cases:
     * <ul>
     *   <li>UUID — accept only String input (JSON numbers are never UUIDs).</li>
     *   <li>Boolean — richer acceptance than {@link Datatype} (true/false strings,
     *       raw JSON boolean), needed for {@code IS_SET}.</li>
     *   <li>Numeric datatypes — pass JSON {@link Number} through without re-parsing,
     *       preserving integer/long semantics when the LLM sends a bare number.</li>
     * </ul>
     */
    private Object convertDatatypeValue(Object raw, MetaProperty metaProperty) {
        Datatype<?> datatype = metaProperty.getRange().asDatatype();
        Class<?> javaClass = datatype.getJavaClass();
        String propertyName = metaProperty.getName();

        if (javaClass == String.class) {
            // Avoid an unnecessary Datatype.parse round-trip for String-typed attributes.
            return raw.toString();
        }
        if (javaClass == UUID.class) {
            return convertUuidString(raw, "expected UUID for " + propertyName);
        }
        if (javaClass == Boolean.class) {
            return convertBooleanValue(raw, propertyName);
        }
        if (raw instanceof Number numberValue && Number.class.isAssignableFrom(box(javaClass))) {
            return narrowNumber(numberValue, javaClass, propertyName);
        }

        String stringValue = raw.toString();
        try {
            Object parsed = datatype.parse(stringValue);
            if (parsed == null) {
                throw new ToolUserError("invalid_literal",
                        "expected " + javaClass.getSimpleName() + " for " + propertyName,
                        List.of(javaClass.getSimpleName()));
            }
            return parsed;
        } catch (ParseException ex) {
            throw new ToolUserError("invalid_literal",
                    "expected " + javaClass.getSimpleName() + " for " + propertyName,
                    List.of(javaClass.getSimpleName()));
        }
    }

    private static Class<?> box(Class<?> javaClass) {
        if (javaClass == int.class) return Integer.class;
        if (javaClass == long.class) return Long.class;
        if (javaClass == short.class) return Short.class;
        if (javaClass == double.class) return Double.class;
        if (javaClass == float.class) return Float.class;
        if (javaClass == byte.class) return Byte.class;
        return javaClass;
    }

    private Object narrowNumber(Number numberValue, Class<?> javaClass, String propertyName) {
        if (javaClass == Integer.class) return numberValue.intValue();
        if (javaClass == Long.class) return numberValue.longValue();
        if (javaClass == Short.class) return numberValue.shortValue();
        if (javaClass == Double.class) return numberValue.doubleValue();
        if (javaClass == Float.class) return numberValue.floatValue();
        if (javaClass == java.math.BigDecimal.class) {
            return new java.math.BigDecimal(numberValue.toString());
        }
        throw new ToolUserError("invalid_literal",
                "expected " + javaClass.getSimpleName() + " for " + propertyName,
                List.of(javaClass.getSimpleName()));
    }
}
