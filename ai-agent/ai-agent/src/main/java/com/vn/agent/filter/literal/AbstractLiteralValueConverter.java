package com.vn.agent.filter.literal;

import com.vn.agent.tools.ToolUserError;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared base for {@link LiteralValueConverter} beans: the UUID-string and Boolean coercion helpers
 * reused across the reference and datatype strategies (and the {@code IS_SET} boolean path on the
 * coordinator). Mirrors {@code AbstractPropertyIntrospector} in the introspection package — common
 * helpers hung on an abstract base so each strategy bean stays focused on its own range kind.
 */
public abstract class AbstractLiteralValueConverter implements LiteralValueConverter {

    /**
     * Coerce a value to {@link UUID}: accept only a hyphenated UUID string (JSON numbers are never
     * UUIDs). {@code reason} is the LLM-facing message used for both the non-String and the
     * unparseable cases.
     */
    protected UUID convertUuidString(Object raw, String reason) {
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

    /**
     * Coerce a value to {@link Boolean}: accept {@link Boolean} or the strings {@code "true"} /
     * {@code "false"} (case-insensitive). Static so the coordinator's {@code convertBooleanValue}
     * (used for the {@code IS_SET} operator, which has no MetaProperty) shares the identical
     * acceptance contract.
     */
    public static Boolean convertBooleanValue(Object raw, String propertyName) {
        if (raw instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (raw instanceof String stringValue) {
            String lowercaseValue = stringValue.toLowerCase(Locale.ROOT);
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
}
