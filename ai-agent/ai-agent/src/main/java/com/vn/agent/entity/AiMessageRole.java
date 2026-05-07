package com.vn.agent.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

public enum AiMessageRole implements EnumClass<String> {

    USER("USER"),
    ASSISTANT("ASSISTANT"),
    SYSTEM("SYSTEM"),
    TOOL("TOOL"),
    NOTICE("NOTICE"); // Phase 13.1 UX-01

    private final String id;

    AiMessageRole(String id) {
        this.id = id;
    }

    @Override
    @NonNull
    public String getId() {
        return id;
    }

    @Nullable
    public static AiMessageRole fromId(String id) {
        for (AiMessageRole at : values()) {
            if (at.getId().equals(id)) {
                return at;
            }
        }
        return null;
    }
}
