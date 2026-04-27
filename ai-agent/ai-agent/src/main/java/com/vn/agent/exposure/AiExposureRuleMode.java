package com.vn.agent.exposure;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

/**
 * Mode for an {@link AiExposureRule}.
 *
 * <p>v1.1.0 ships only {@link #EXCLUDE}. There is no ALLOW value: rules can only
 * narrow the LLM-visible surface, never widen it. Composition with the user's
 * Jmix permissions is {@code userVisible AND NOT excluded}.
 *
 * <p>Adding a new mode in a future milestone is intentionally a deliberate
 * decision — see Phase 10 CONTEXT.md (decisions D-01..D-15) before adding any
 * value here.
 */
public enum AiExposureRuleMode implements EnumClass<String> {

    EXCLUDE("EXCLUDE");

    private final String id;

    AiExposureRuleMode(String id) {
        this.id = id;
    }

    @Override
    @NonNull
    public String getId() {
        return id;
    }

    @Nullable
    public static AiExposureRuleMode fromId(String id) {
        for (AiExposureRuleMode v : values()) {
            if (v.getId().equals(id)) {
                return v;
            }
        }
        return null;
    }
}
