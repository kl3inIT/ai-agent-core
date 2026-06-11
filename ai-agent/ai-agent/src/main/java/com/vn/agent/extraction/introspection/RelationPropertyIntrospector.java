package com.vn.agent.extraction.introspection;

import io.jmix.core.MessageTools;
import io.jmix.core.metamodel.model.MetaProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * To-one reference attribute → {@code {"type":"string","format":"uuid"}} (the LLM supplies the
 * target entity id). Ordered first (most specific range check). Collections are excluded upstream by
 * the coordinator, so this only sees to-one class ranges.
 */
@Component
@Order(100)
public class RelationPropertyIntrospector extends AbstractPropertyIntrospector {

    public RelationPropertyIntrospector(MessageTools messageTools) {
        super(messageTools);
    }

    @Override
    public boolean supports(MetaProperty metaProperty) {
        return metaProperty.getRange().isClass();
    }

    @Override
    public Map<String, Object> buildSchema(MetaProperty metaProperty) {
        return attributeSchema("string", "uuid", description(metaProperty));
    }
}
