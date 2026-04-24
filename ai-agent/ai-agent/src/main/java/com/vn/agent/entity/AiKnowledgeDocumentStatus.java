package com.vn.agent.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

public enum AiKnowledgeDocumentStatus implements EnumClass<String> {

    PENDING("PENDING"),
    PROCESSING("PROCESSING"),
    READY("READY"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED");

    private final String id;

    AiKnowledgeDocumentStatus(String id) {
        this.id = id;
    }

    @Override
    @NonNull
    public String getId() {
        return id;
    }

    @Nullable
    public static AiKnowledgeDocumentStatus fromId(String id) {
        for (AiKnowledgeDocumentStatus at : values()) {
            if (at.getId().equals(id)) {
                return at;
            }
        }
        return null;
    }
}
