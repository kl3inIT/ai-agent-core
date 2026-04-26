package com.vn.agent.spi;

import org.springframework.security.core.userdetails.UserDetails;

import java.util.Locale;
import java.util.UUID;

/**
 * Per-request context passed to {@link ToolFetchPlanCustomizer#overrideFor}. This is a concrete
 * immutable snapshot of the static {@code RunContext} values plus the authenticated user and
 * locale. It is intentionally narrow: SPIs are for app-specific projection behavior, not a
 * parallel runtime-context layer.
 *
 * <p>Do not carry {@code RunContext} itself here: in this codebase it is a final class with a
 * private constructor and static ThreadLocal accessors. {@code FetchPlanResolver} reads those
 * accessors at the tool boundary and stores the values in this record before host code runs.
 *
 * <p>Hosts can vary fetch plans by conversation, retrieval settings, locale, or role through
 * these fields. Phase 9 deliberately does NOT expose tool arguments here — argument-shape
 * variance belongs in tool implementations, not in projection customizers.
 *
 * @param runId                         current run id from {@code RunContext.get()}
 * @param conversationId                current conversation id from {@code RunContext.getConversationId()}
 * @param retrievalTopK                 current retrieval top-k, if present
 * @param retrievalSimilarityThreshold  current retrieval similarity threshold, if present
 * @param retrievalFiltersJson          current retrieval filters JSON, if present
 * @param locale                        current Jmix locale; may be {@code Locale.ROOT} defensively
 * @param user                          authenticated user; never {@code null} when Phase 9 calls {@code overrideFor}
 */
public record FetchPlanContext(UUID runId,
                               UUID conversationId,
                               Integer retrievalTopK,
                               Double retrievalSimilarityThreshold,
                               String retrievalFiltersJson,
                               Locale locale,
                               UserDetails user) {
}
