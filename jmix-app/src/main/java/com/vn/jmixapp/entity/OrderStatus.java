package com.vn.jmixapp.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.Nullable;

public enum OrderStatus implements EnumClass<String> {

    NEW("NEW"),
    CONFIRMED("CONFIRMED"),
    SHIPPED("SHIPPED"),
    CANCELLED("CANCELLED");

    private final String id;

    OrderStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static OrderStatus fromId(String id) {
        for (OrderStatus at : values()) {
            if (at.getId().equals(id)) {
                return at;
            }
        }
        return null;
    }
}
