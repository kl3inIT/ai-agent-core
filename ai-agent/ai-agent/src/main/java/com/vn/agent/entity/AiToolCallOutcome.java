package com.vn.agent.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

public enum AiToolCallOutcome implements EnumClass<String> {

    SUCCESS("SUCCESS"),
    BLOCKED("BLOCKED"),
    ERROR("ERROR"),
    FLAGGED("FLAGGED");

    private final String id;

    AiToolCallOutcome(String id) {
        this.id = id;
    }

    @Override
    @NonNull
    public String getId() {
        return id;
    }

    @Nullable
    public static AiToolCallOutcome fromId(String id) {
        for (AiToolCallOutcome at : values()) {
            if (at.getId().equals(id)) {
                return at;
            }
        }
        return null;
    }
}
