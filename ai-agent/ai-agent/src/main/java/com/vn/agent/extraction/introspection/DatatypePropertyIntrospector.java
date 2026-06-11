package com.vn.agent.extraction.introspection;

import io.jmix.core.MessageTools;
import io.jmix.core.metamodel.model.MetaProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Datatype (scalar) attribute → JSON-schema {@code type}/{@code format} based on the datatype's Java
 * class. Ordered last (most general); a host with a custom {@code Datatype} contributes a narrower
 * {@link PropertyIntrospector} with an earlier {@code @Order} to override one of these mappings.
 */
@Component
@Order(300)
public class DatatypePropertyIntrospector extends AbstractPropertyIntrospector {

    public DatatypePropertyIntrospector(MessageTools messageTools) {
        super(messageTools);
    }

    @Override
    public boolean supports(MetaProperty metaProperty) {
        return metaProperty.getRange().isDatatype();
    }

    @Override
    public Map<String, Object> buildSchema(MetaProperty metaProperty) {
        Class<?> boxedClass = box(metaProperty.getRange().asDatatype().getJavaClass());
        if (boxedClass == String.class || boxedClass == Character.class) {
            return attributeSchema("string", null, description(metaProperty));
        }
        if (boxedClass == UUID.class) {
            return attributeSchema("string", "uuid", description(metaProperty));
        }
        if (boxedClass == Boolean.class) {
            return attributeSchema("boolean", null, description(metaProperty));
        }
        if (boxedClass == Byte.class || boxedClass == Short.class
                || boxedClass == Integer.class || boxedClass == Long.class
                || boxedClass == BigInteger.class) {
            return attributeSchema("integer", null, description(metaProperty));
        }
        if (boxedClass == Float.class || boxedClass == Double.class
                || boxedClass == BigDecimal.class || Number.class.isAssignableFrom(boxedClass)) {
            return attributeSchema("number", null, description(metaProperty));
        }
        if (boxedClass == LocalDate.class || boxedClass == java.sql.Date.class) {
            return attributeSchema("string", "date", description(metaProperty));
        }
        if (boxedClass == LocalTime.class || boxedClass == OffsetTime.class) {
            return attributeSchema("string", "time", description(metaProperty));
        }
        if (boxedClass == LocalDateTime.class || boxedClass == OffsetDateTime.class
                || boxedClass == ZonedDateTime.class || boxedClass == Instant.class
                || boxedClass == java.util.Date.class) {
            return attributeSchema("string", "date-time", description(metaProperty));
        }
        return Map.of();
    }

    private static Class<?> box(Class<?> javaClass) {
        if (javaClass == null || !javaClass.isPrimitive()) {
            return javaClass;
        }
        if (javaClass == boolean.class) return Boolean.class;
        if (javaClass == byte.class) return Byte.class;
        if (javaClass == short.class) return Short.class;
        if (javaClass == int.class) return Integer.class;
        if (javaClass == long.class) return Long.class;
        if (javaClass == float.class) return Float.class;
        if (javaClass == double.class) return Double.class;
        if (javaClass == char.class) return Character.class;
        return javaClass;
    }
}
