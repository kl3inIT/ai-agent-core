package com.vn.agent.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

public enum AiChatSurface implements EnumClass<String> {

    FULL_ROUTE("FULL_ROUTE"),
    HEADER_BUTTON("HEADER_BUTTON"),
    SIDEBAR("SIDEBAR");

    private final String id;

    AiChatSurface(String id) {
        this.id = id;
    }

    @Override
    @NonNull
    public String getId() {
        return id;
    }

    @Nullable
    public static AiChatSurface fromId(String id) {
        for (AiChatSurface surface : values()) {
            if (surface.getId().equals(id)) {
                return surface;
            }
        }
        return null;
    }
}
