package com.vn.agent.exposure;

import io.jmix.core.event.EntityChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges Jmix {@link EntityChangedEvent} for {@link AiExposureRule} to a Spring
 * {@link LlmExposureChangedEvent}. Uses @EventListener (NOT JPA @PostPersist) per Jmix 2.x idiom:
 * DataManager fires EntityChangedEvent; JPA lifecycle annotations are unreliable for Jmix save paths.
 *
 * <p>All three event types (CREATED, UPDATED, DELETED) publish the same invalidation event
 * because any rule change may affect the effective denylist.</p>
 *
 * <p>This is the SINGLE publish site for {@link LlmExposureChangedEvent}.
 * {@code UnconstrainedDataManager.save()} does fire {@code EntityChangedEvent}, so the toggle
 * in {@code AiExposureRuleListView} reaches this listener automatically — view controllers
 * must NOT call {@code applicationEventPublisher.publishEvent(new LlmExposureChangedEvent(...))}
 * directly, or the event fires twice per save.</p>
 */
@Component
public class AiExposureRuleEntityListener {

    private final ApplicationEventPublisher eventPublisher;

    public AiExposureRuleEntityListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onExposureRuleChanged(EntityChangedEvent<AiExposureRule> event) {
        eventPublisher.publishEvent(new LlmExposureChangedEvent(this));
    }
}
