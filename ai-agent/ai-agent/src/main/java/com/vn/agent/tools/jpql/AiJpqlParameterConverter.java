package com.vn.agent.tools.jpql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Heuristic, forgiving converter for LLM-supplied JPQL parameter values.
 *
 * <p>Ported from the jmix-crm reference assistant. LLMs frequently pass values as bare strings
 * ("42", "2024-01-15", a UUID). This converter asks Spring's {@link ConversionService} to coerce
 * each string to the first plausible target type (boolean → UUID → date/time → numeric), falling
 * back to the original string so the JPA engine can handle it. {@link AiJpqlQueryService} also
 * retries once with the *unconverted* values if a converted execution fails, so over-eager
 * coercion never traps a query.
 */
@Component
public class AiJpqlParameterConverter {

    private static final Logger log = LoggerFactory.getLogger(AiJpqlParameterConverter.class);
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^[+-]?\\d+(?:\\.\\d+)?$");

    private final ConversionService conversionService;

    public AiJpqlParameterConverter(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    public Map<String, Object> convertParameters(List<JpqlParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        return parameters.stream()
                .collect(Collectors.toMap(
                        JpqlParameter::parameterName,
                        entry -> convertParameterValue(entry.parameterValue())
                ));
    }

    public Object convertParameterValue(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String stringValue)) {
            return value;
        }
        if (stringValue.isBlank()) {
            return stringValue;
        }

        // 1. Boolean (very specific).
        if ("true".equalsIgnoreCase(stringValue) || "false".equalsIgnoreCase(stringValue)) {
            return Boolean.valueOf(stringValue);
        }
        // 2. UUID pattern.
        if (stringValue.length() == 36 && stringValue.contains("-")) {
            Object uuid = tryConvertToType(stringValue, UUID.class);
            if (uuid != null) {
                return uuid;
            }
        }
        // 3. Date / date-time pattern.
        if (stringValue.length() >= 10 && Character.isDigit(stringValue.charAt(0)) && stringValue.contains("-")) {
            Object date = tryConvertDateTypes(stringValue);
            if (date != null) {
                return date;
            }
        }
        // 4. Numeric pattern (but not very long digit strings — likely ids/codes).
        if (isLikelyNumeric(stringValue)) {
            Object numeric = tryConvertNumericTypes(stringValue);
            if (numeric != null) {
                return numeric;
            }
        }
        return stringValue;
    }

    private Object tryConvertDateTypes(String stringValue) {
        List<Class<?>> dateTypes = List.of(
                LocalDate.class, LocalDateTime.class, OffsetDateTime.class,
                ZonedDateTime.class, java.util.Date.class, Timestamp.class
        );
        return dateTypes.stream()
                .filter(type -> conversionService.canConvert(String.class, type))
                .map(type -> tryConvertToType(stringValue, type))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private Object tryConvertNumericTypes(String stringValue) {
        if (stringValue.length() > 15 && !stringValue.contains(".")) {
            return null;
        }
        List<Class<?>> numericTypes = List.of(BigDecimal.class, Long.class, Integer.class);
        return numericTypes.stream()
                .filter(type -> conversionService.canConvert(String.class, type))
                .map(type -> tryConvertToType(stringValue, type))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean isLikelyNumeric(String s) {
        return NUMERIC_PATTERN.matcher(s).matches();
    }

    private Object tryConvertToType(String stringValue, Class<?> targetType) {
        try {
            return conversionService.convert(stringValue, targetType);
        } catch (Exception e) {
            log.trace("Failed to convert '{}' to {}: {}", stringValue, targetType.getSimpleName(), e.getMessage());
            return null;
        }
    }
}
