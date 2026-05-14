package com.vn.agent.admin.config;

import com.vn.agent.entity.AiUiSettings;
import io.jmix.core.event.EntityChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Phase 16 D-04 — single publish site for {@link AiSettingsChangedEvent} with
 * {@link AiSettingsChangedEvent.Kind#UI_SETTINGS}. Mirrors the
 * {@code AiExposureRuleEntityListener} precedent (one publish site per entity,
 * source-scan invariant in {@code AiSettingsChangedEventListenerInvariantTest}).
 *
 * <p>Singleton-id guard (codex MEDIUM Concern #6): only writes against
 * {@link AiUiSettings#SINGLETON_ID} publish the event. Any other id — host
 * extensions, accidental writes — is silently ignored. The singleton id is
 * sealed at schema; admin-edit is the only intended write path.</p>
 *
 * <p>This is the SINGLE publish site for {@code AiSettingsChangedEvent} with
 * kind {@code UI_SETTINGS}. Views/services NEVER inject
 * {@link ApplicationEventPublisher} for this event (Plan 10-06 R2 invariant;
 * {@code SecretRedactionInvariantsTest} second leg + the source-scan in
 * {@code AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan()}
 * enforce it at build time).</p>
 */
@Component
public class AiUiSettingsEntityListener {

    private final ApplicationEventPublisher eventPublisher;

    public AiUiSettingsEntityListener(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @EventListener
    public void onAiUiSettingsChanged(EntityChangedEvent<AiUiSettings> event) {
        // codex MEDIUM Concern #6 — only the sealed singleton id is the admin-edit
        // write path; non-singleton ids reach this listener only via host
        // extensions or accidental writes and must NOT trigger cache eviction.
        if (!AiUiSettings.SINGLETON_ID.equals(event.getEntityId().getValue())) {
            return;
        }
        eventPublisher.publishEvent(
                new AiSettingsChangedEvent(this, AiSettingsChangedEvent.Kind.UI_SETTINGS));
    }
}
