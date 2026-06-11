package com.vn.agent.filter.literal;

import com.vn.agent.tools.ToolUserError;
import io.jmix.core.metamodel.datatype.EnumClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Enum attribute ({@link Range#isEnum()}): the value must match either {@code enumValues[].id}
 * from {@code describe_entity} ({@link EnumClass#getId()}) or — as a fallback — the Java enum
 * constant name. ID matching is the primary contract; Java enum names are fallback only.
 */
@Component
@Order(200)
public class EnumLiteralValueConverter extends AbstractLiteralValueConverter {

    @Override
    public boolean supports(MetaProperty metaProperty) {
        return metaProperty.getRange().isEnum();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object convert(Object raw, MetaProperty metaProperty) {
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
}
