package com.vn.agent.filter.literal;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.datatype.Datatype;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.springframework.core.annotation.Order;
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
import java.util.List;
import java.util.UUID;

/**
 * Datatype attribute ({@link Range#isDatatype()}): delegate scalar coercion to Jmix's
 * {@link Datatype#parse(String)} — the canonical parser covering every registered datatype,
 * including user-defined ones — while keeping narrow special cases ahead of it:
 * <ul>
 *   <li>String — accept only String input.</li>
 *   <li>UUID — accept only String input (JSON numbers are never UUIDs).</li>
 *   <li>Boolean — richer acceptance than {@link Datatype} (true/false strings, raw JSON boolean).</li>
 *   <li>Numeric datatypes — pass JSON {@link Number} through without re-parsing, narrowing to the
 *       target box with exact/in-range checks, preserving integer/long semantics.</li>
 *   <li>{@code java.time} types — parse ISO-8601 strings directly. Jmix datatype parsing is
 *       locale/display-format oriented in some runtimes, while the LLM tool contract tells models to
 *       send ISO strings such as {@code 2026-04-29}.</li>
 * </ul>
 * Ordered last (most general); a host with a custom {@link Datatype} contributes a narrower
 * {@link LiteralValueConverter} with an earlier {@code @Order} to override one of these mappings.
 */
@Component
@Order(300)
public class DatatypeLiteralValueConverter extends AbstractLiteralValueConverter {

    @Override
    public boolean supports(MetaProperty metaProperty) {
        return metaProperty.getRange().isDatatype();
    }

    @Override
    public Object convert(Object raw, MetaProperty metaProperty) {
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
