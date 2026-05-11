package com.vn.agent.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

public enum AiToolCallOutcome implements EnumClass<String> {

    SUCCESS("SUCCESS"),
    DENIED("DENIED"),
    FAILED("FAILED"),
    BLOCKED("BLOCKED"),
    ERROR("ERROR"),
    FLAGGED("FLAGGED"),
    IDEMPOTENT_REPLAY("IDEMPOTENT_REPLAY"),    // Phase 11 D-08 — mutation tool replay outcome
    COMMIT_FAILED("COMMIT_FAILED");            // Phase 11 D-08 — host save returned but finalization failed

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
