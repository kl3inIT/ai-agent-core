package com.vn.agent.filter.literal;

import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.metamodel.model.Range;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Association attribute ({@link Range#isClass()}) → the value must be a String parseable by
 * {@link java.util.UUID#fromString} (the target entity id). Ordered first (most specific range
 * check); collections never reach the literal coercer.
 */
@Component
@Order(100)
public class ReferenceLiteralValueConverter extends AbstractLiteralValueConverter {

    @Override
    public boolean supports(MetaProperty metaProperty) {
        return metaProperty.getRange().isClass();
    }

    @Override
    public Object convert(Object raw, MetaProperty metaProperty) {
        return convertUuidString(raw, "expected UUID id for " + metaProperty.getName());
    }
}
