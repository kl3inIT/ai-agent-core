package com.vn.agent.extraction.introspection;

import io.jmix.core.MessageTools;
import io.jmix.core.metamodel.datatype.EnumClass;
import io.jmix.core.metamodel.model.MetaProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enum attribute → {@code {"type":"string","enum":[ids...]}} using {@link EnumClass#getId()}
 * (falling back to {@link Enum#name()}).
 */
@Component
@Order(200)
public class EnumPropertyIntrospector extends AbstractPropertyIntrospector {

    public EnumPropertyIntrospector(MessageTools messageTools) {
        super(messageTools);
    }

    @Override
    public boolean supports(MetaProperty metaProperty) {
        return metaProperty.getRange().isEnum();
    }

    @Override
    public Map<String, Object> buildSchema(MetaProperty metaProperty) {
        Map<String, Object> schema = attributeSchema("string", null, description(metaProperty));
        schema.put("enum", enumValues(metaProperty));
        return schema;
    }

    private List<String> enumValues(MetaProperty metaProperty) {
        List<String> values = new ArrayList<>();
        for (Object enumValue : metaProperty.getRange().asEnumeration().getValues()) {
            Enum<?> typedEnum = (Enum<?>) enumValue;
            Object id = typedEnum instanceof EnumClass<?> enumClassValue
                    ? enumClassValue.getId()
                    : typedEnum.name();
            values.add(id == null ? typedEnum.name() : id.toString());
        }
        return values;
    }
}
