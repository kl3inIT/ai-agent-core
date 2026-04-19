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
 * Strict, fail-closed literal coercion (D-07). Converts a raw JSON-deserialized value
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
 * <p>Special {@link #coerceList} for {@code IN_LIST}/{@code NOT_IN_LIST} coerces each element
 * separately. {@link #coerceBoolean} is used for {@code IS_SET} and takes no MetaProperty.</p>
 */
@Component
public class LiteralCoercer {

    /**
     * Coerce a single scalar value against {@code mp}. Throws {@link ToolUserError} on
     * mismatch. Must not be called with a {@link Collection}; use {@link #coerceList}.
     */
    public Object coerce(Object raw, MetaProperty mp) {
        if (raw == null) {
            throw new ToolUserError("invalid_literal",
                    "value must not be null for " + mp.getName());
        }
        Range range = mp.getRange();
        if (range.isClass()) {
            return coerceUuidString(raw, mp.getName(), "expected UUID id for " + mp.getName());
        }
        if (range.isEnum()) {
            return coerceEnum(raw, mp);
        }
        if (range.isDatatype()) {
            return coerceDatatype(raw, mp);
        }
        throw new ToolUserError("unsupported_type",
                "no literal coercion for attribute " + mp.getName());
    }

    /**
     * Coerce a {@link Collection} or array to a {@link List} of coerced elements. Used by
     * {@code IN_LIST}/{@code NOT_IN_LIST} operators.
     */
    public List<Object> coerceList(Object raw, MetaProperty mp) {
        if (raw == null) {
            throw new ToolUserError("invalid_literal",
                    "IN_LIST/NOT_IN_LIST value must not be null for " + mp.getName());
        }
        Collection<?> src;
        if (raw instanceof Collection<?> c) {
            src = c;
        } else if (raw instanceof Object[] arr) {
            src = Arrays.asList(arr);
        } else {
            throw new ToolUserError("invalid_literal",
                    "IN_LIST/NOT_IN_LIST value must be a JSON array for " + mp.getName(),
                    List.of("JSON array of values, e.g. [1,2,3]"));
        }
        if (src.isEmpty()) {
            throw new ToolUserError("invalid_literal",
                    "IN_LIST/NOT_IN_LIST requires a non-empty array for " + mp.getName());
        }
        List<Object> out = new ArrayList<>(src.size());
        for (Object el : src) {
            out.add(coerce(el, mp));
        }
        return out;
    }

    /**
     * Coerce a value to {@link Boolean} for the {@code IS_SET} operator. Accepts
     * {@link Boolean} or the strings {@code "true"}/{@code "false"} (case-insensitive).
     */
    public Boolean coerceBoolean(Object raw, String propName) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            String lower = s.toLowerCase();
            if ("true".equals(lower)) {
                return Boolean.TRUE;
            }
            if ("false".equals(lower)) {
                return Boolean.FALSE;
            }
        }
        throw new ToolUserError("invalid_literal",
                "IS_SET value must be a Boolean for " + propName,
                List.of("true", "false"));
    }

    // ---- internals -------------------------------------------------------

    private Object coerceUuidString(Object raw, String propName, String reason) {
        if (!(raw instanceof String s)) {
            throw new ToolUserError("invalid_literal", reason,
                    List.of("UUID string, e.g. '4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88'"));
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            throw new ToolUserError("invalid_literal", reason,
                    List.of("UUID string, e.g. '4f2b8a90-9c8a-4f74-9a5f-3b7a2e4c1d88'"));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object coerceEnum(Object raw, MetaProperty mp) {
        if (!(raw instanceof String s)) {
            throw new ToolUserError("invalid_literal",
                    "enum value must be a String name for " + mp.getName(),
                    enumConstantNames(mp));
        }
        Class<?> enumClass = mp.getRange().asEnumeration().getJavaClass();
        try {
            return Enum.valueOf((Class<Enum>) enumClass, s);
        } catch (IllegalArgumentException ex) {
            throw new ToolUserError("invalid_literal",
                    "unknown enum constant '" + s + "' for " + mp.getName(),
                    enumConstantNames(mp));
        }
    }

    private List<String> enumConstantNames(MetaProperty mp) {
        List<?> values = mp.getRange().asEnumeration().getValues();
        List<String> names = new ArrayList<>(values.size());
        for (Object c : values) {
            names.add(((Enum<?>) c).name());
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
    private Object coerceDatatype(Object raw, MetaProperty mp) {
        Datatype<?> dt = mp.getRange().asDatatype();
        Class<?> jc = dt.getJavaClass();
        String propName = mp.getName();

        if (jc == String.class) {
            // Avoid an unnecessary Datatype.parse round-trip for String-typed attributes.
            return raw.toString();
        }
        if (jc == UUID.class) {
            return coerceUuidString(raw, propName, "expected UUID for " + propName);
        }
        if (jc == Boolean.class) {
            return coerceBoolean(raw, propName);
        }
        if (raw instanceof Number n && Number.class.isAssignableFrom(box(jc))) {
            return narrowNumber(n, jc, propName);
        }

        String asString = raw.toString();
        try {
            Object parsed = dt.parse(asString);
            if (parsed == null) {
                throw new ToolUserError("invalid_literal",
                        "expected " + jc.getSimpleName() + " for " + propName,
                        List.of(jc.getSimpleName()));
            }
            return parsed;
        } catch (ParseException ex) {
            throw new ToolUserError("invalid_literal",
                    "expected " + jc.getSimpleName() + " for " + propName,
                    List.of(jc.getSimpleName()));
        }
    }

    private static Class<?> box(Class<?> c) {
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == short.class) return Short.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == byte.class) return Byte.class;
        return c;
    }

    private Object narrowNumber(Number n, Class<?> jc, String propName) {
        if (jc == Integer.class) return n.intValue();
        if (jc == Long.class) return n.longValue();
        if (jc == Short.class) return n.shortValue();
        if (jc == Double.class) return n.doubleValue();
        if (jc == Float.class) return n.floatValue();
        if (jc == java.math.BigDecimal.class) {
            return new java.math.BigDecimal(n.toString());
        }
        throw new ToolUserError("invalid_literal",
                "expected " + jc.getSimpleName() + " for " + propName,
                List.of(jc.getSimpleName()));
    }
}
