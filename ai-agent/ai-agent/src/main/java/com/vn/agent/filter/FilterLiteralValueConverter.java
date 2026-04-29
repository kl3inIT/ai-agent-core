package com.vn.agent.filter;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.datatype.EnumClass;
import io.jmix.core.metamodel.datatype.Datatype;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
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
 *   <li>Enum attribute ({@link Range#isEnum()}): value must match either {@code enumValues[].id}
 *       from {@code describe_entity} or the Java enum constant name.</li>
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
                    List.of("hyphenated UUID string copied from a prior tool result"));
        }
        try {
            return UUID.fromString(stringValue);
        } catch (IllegalArgumentException ex) {
            throw new ToolUserError("invalid_literal", reason,
                    List.of("hyphenated UUID string copied from a prior tool result"));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convertEnumValue(Object raw, MetaProperty metaProperty) {
        if (!(raw instanceof String) && !(raw instanceof Number) && !(raw instanceof Boolean)) {
            throw new ToolUserError("invalid_literal",
                    "enum value must match enumValues[].id for " + metaProperty.getName(),
                    enumExpectedValues(metaProperty));
        }
        Class<Enum> enumClass = (Class<Enum>) metaProperty.getRange().asEnumeration().getJavaClass();
        for (Object enumValue : metaProperty.getRange().asEnumeration().getValues()) {
            Enum<?> typedEnum = (Enum<?>) enumValue;
            if (idsMatch(raw, enumId(typedEnum))) {
                return typedEnum;
            }
        }
        if (raw instanceof String stringValue) {
            try {
                return Enum.valueOf(enumClass, stringValue);
            } catch (IllegalArgumentException ignored) {
                // ID matching is the primary contract; Java enum names are fallback only.
            }
        }
        throw new ToolUserError("invalid_literal",
                "unknown enum value '" + raw + "' for " + metaProperty.getName(),
                enumExpectedValues(metaProperty));
    }

    private List<String> enumExpectedValues(MetaProperty metaProperty) {
        List<?> values = metaProperty.getRange().asEnumeration().getValues();
        List<String> names = new ArrayList<>(values.size());
        for (Object enumValue : values) {
            Enum<?> typedEnum = (Enum<?>) enumValue;
            Object id = enumId(typedEnum);
            names.add(id == null ? typedEnum.name() : id.toString());
        }
        return names;
    }

    private static Object enumId(Enum<?> enumValue) {
        if (enumValue instanceof EnumClass<?> enumClassValue) {
            return enumClassValue.getId();
        }
        return enumValue.name();
    }

    private static boolean idsMatch(Object raw, Object id) {
        if (Objects.equals(raw, id)) {
            return true;
        }
        return raw != null && id != null && raw.toString().equals(id.toString());
    }

    /**
     * Delegate scalar datatype coercion to Jmix's {@link Datatype#parse(String)}. Keeps
     * narrow special cases:
     * <ul>
     *   <li>UUID — accept only String input (JSON numbers are never UUIDs).</li>
     *   <li>Boolean — richer acceptance than {@link Datatype} (true/false strings,
     *       raw JSON boolean), needed for {@code IS_SET}.</li>
     *   <li>Numeric datatypes — pass JSON {@link Number} through without re-parsing,
     *       preserving integer/long semantics when the LLM sends a bare number.</li>
     *   <li>{@code java.time} types — parse ISO-8601 strings directly. Jmix datatype parsing
     *       is locale/display-format oriented in some runtimes, while the LLM tool contract
     *       tells models to send ISO strings such as {@code 2026-04-29}.</li>
     * </ul>
     */
    private Object convertDatatypeValue(Object raw, MetaProperty metaProperty) {
        Datatype<?> datatype = metaProperty.getRange().asDatatype();
        Class<?> javaClass = datatype.getJavaClass();
        String propertyName = metaProperty.getName();

        if (javaClass == String.class) {
            if (raw instanceof String stringValue) {
                return stringValue;
            }
            throw new ToolUserError("invalid_literal",
                    "expected String for " + propertyName,
                    List.of("String"));
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
        if (raw instanceof String stringValue) {
            Object temporalValue = parseIsoTemporalValue(stringValue, javaClass, propertyName);
            if (temporalValue != null) {
                return temporalValue;
            }
        } else if (isIsoTemporalType(javaClass)) {
            throw new ToolUserError("invalid_literal",
                    "expected ISO-8601 " + javaClass.getSimpleName() + " string for " + propertyName,
                    List.of(javaClass.getSimpleName()));
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

    private Object parseIsoTemporalValue(String stringValue, Class<?> javaClass, String propertyName) {
        try {
            if (javaClass == LocalDate.class) return LocalDate.parse(stringValue);
            if (javaClass == LocalDateTime.class) return LocalDateTime.parse(stringValue);
            if (javaClass == LocalTime.class) return LocalTime.parse(stringValue);
            if (javaClass == OffsetDateTime.class) return OffsetDateTime.parse(stringValue);
            if (javaClass == ZonedDateTime.class) return ZonedDateTime.parse(stringValue);
            if (javaClass == Instant.class) return Instant.parse(stringValue);
        } catch (DateTimeParseException ex) {
            throw new ToolUserError("invalid_literal",
                    "expected ISO-8601 " + javaClass.getSimpleName() + " string for " + propertyName,
                    List.of(javaClass.getSimpleName()));
        }
        return null;
    }

    private boolean isIsoTemporalType(Class<?> javaClass) {
        return javaClass == LocalDate.class
                || javaClass == LocalDateTime.class
                || javaClass == LocalTime.class
                || javaClass == OffsetDateTime.class
                || javaClass == ZonedDateTime.class
                || javaClass == Instant.class;
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
        if (javaClass == Integer.class) {
            return exactIntegerInRange(numberValue, javaClass, propertyName,
                    BigInteger.valueOf(Integer.MIN_VALUE), BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
        }
        if (javaClass == Long.class) {
            return exactIntegerInRange(numberValue, javaClass, propertyName,
                    BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        }
        if (javaClass == Short.class) {
            return exactIntegerInRange(numberValue, javaClass, propertyName,
                    BigInteger.valueOf(Short.MIN_VALUE), BigInteger.valueOf(Short.MAX_VALUE)).shortValue();
        }
        if (javaClass == Byte.class) {
            return exactIntegerInRange(numberValue, javaClass, propertyName,
                    BigInteger.valueOf(Byte.MIN_VALUE), BigInteger.valueOf(Byte.MAX_VALUE)).byteValue();
        }
        if (javaClass == Double.class) {
            double value = numberValue.doubleValue();
            if (!Double.isFinite(value)) {
                throw expectedNumber(javaClass, propertyName);
            }
            return value;
        }
        if (javaClass == Float.class) {
            float value = numberValue.floatValue();
            if (!Float.isFinite(value)) {
                throw expectedNumber(javaClass, propertyName);
            }
            return value;
        }
        if (javaClass == BigDecimal.class) {
            return toBigDecimal(numberValue, javaClass, propertyName);
        }
        throw new ToolUserError("invalid_literal",
                "expected " + javaClass.getSimpleName() + " for " + propertyName,
                List.of(javaClass.getSimpleName()));
    }

    private BigInteger exactIntegerInRange(Number numberValue,
                                           Class<?> javaClass,
                                           String propertyName,
                                           BigInteger min,
                                           BigInteger max) {
        BigInteger integerValue;
        try {
            integerValue = toBigDecimal(numberValue, javaClass, propertyName).toBigIntegerExact();
        } catch (ArithmeticException ex) {
            throw expectedNumber(javaClass, propertyName);
        }
        if (integerValue.compareTo(min) < 0 || integerValue.compareTo(max) > 0) {
            throw expectedNumber(javaClass, propertyName);
        }
        return integerValue;
    }

    private BigDecimal toBigDecimal(Number numberValue, Class<?> javaClass, String propertyName) {
        try {
            if (numberValue instanceof BigDecimal bigDecimal) {
                return bigDecimal;
            }
            if (numberValue instanceof BigInteger bigInteger) {
                return new BigDecimal(bigInteger);
            }
            return new BigDecimal(numberValue.toString());
        } catch (NumberFormatException ex) {
            throw expectedNumber(javaClass, propertyName);
        }
    }

    private ToolUserError expectedNumber(Class<?> javaClass, String propertyName) {
        return new ToolUserError("invalid_literal",
                "expected " + javaClass.getSimpleName() + " for " + propertyName,
                List.of(javaClass.getSimpleName()));
    }
}
