package com.vn.agent.extraction;

import java.util.List;

/**
 * Counts produced when an extraction draft is applied to a Jmix detail-view entity.
 */
public record DraftApplyResult(int appliedFieldCount,
                               int deniedAttributeCount,
                               List<String> deniedAttributeNames,
                               boolean deniedAttributeNamesTruncated) {

    public DraftApplyResult {
        deniedAttributeNames = deniedAttributeNames == null
                ? List.of()
                : List.copyOf(deniedAttributeNames);
    }
}
