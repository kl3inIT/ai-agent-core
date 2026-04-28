package com.vn.agent.exposure;

import org.springframework.context.ApplicationEvent;

/**
 * Published when any {@link AiExposureRule} is created, updated, or deleted.
 * No current consumer in v1.1 — wired for Phase 12+ caching consumers.
 * In-process Spring event only; clustered deployments see no cross-node propagation.
 *
 * <p>SINGLE publish site: {@link AiExposureRuleEntityListener#onExposureRuleChanged}.
 * View controllers must NOT publish this event directly — rely on the entity listener.</p>
 *
 * @see AiExposureRuleEntityListener
 */
public class LlmExposureChangedEvent extends ApplicationEvent {
    public LlmExposureChangedEvent(Object source) {
        super(source);
    }
}
