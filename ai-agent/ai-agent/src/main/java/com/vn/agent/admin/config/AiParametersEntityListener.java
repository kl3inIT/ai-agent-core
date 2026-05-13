package com.vn.agent.admin.config;

import com.vn.agent.entity.AiParameters;
import io.jmix.core.DataManager;
import io.jmix.core.event.AttributeChanges;
import io.jmix.core.event.EntityChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Phase 16 D-04 — single publish site for {@link AiSettingsChangedEvent} with
 * {@link AiSettingsChangedEvent.Kind#PARAMETERS}. Mirrors the
 * {@code AiExposureRuleEntityListener} precedent (one publish site per entity,
 * source-scan invariant in {@code AiSettingsChangedEventListenerInvariantTest}).
 *
 * <p>Publishes IFF the change transitioned the <em>effective settings</em>:</p>
 * <ul>
 *   <li>CREATED row with {@code active=true} (a new effective profile).</li>
 *   <li>DELETED row whose previous {@code active=true} (effective profile gone).</li>
 *   <li>UPDATED row that is now (or was) the effective profile — three sub-cases:
 *     <ul>
 *       <li>{@code active} not in change set AND current row is {@code active=true}: an
 *           attribute of the active profile changed.</li>
 *       <li>{@code active} flipped {@code false → true}: ACTIVATION.</li>
 *       <li>{@code active} flipped {@code true → false}: DEACTIVATION (codex HIGH
 *           Concern #6 — previously-active profile is no longer effective, downstream
 *           caches in Phase 18 MUST invalidate).</li>
 *       <li>{@code active} stayed {@code false}: no effective-settings transition;
 *           do NOT publish.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>This is the SINGLE publish site for {@code AiSettingsChangedEvent} with
 * kind {@code PARAMETERS}. Views/services NEVER inject
 * {@link ApplicationEventPublisher} for this event (Plan 10-06 R2 invariant;
 * {@code SecretRedactionInvariantsTest} second leg + the source-scan in
 * {@code AiSettingsChangedEventListenerInvariantTest.singlePublishSiteSourceScan()}
 * enforce it at build time).</p>
 */
@Component
public class AiParametersEntityListener {

    private static final String ACTIVE_ATTRIBUTE = "active";

    private final ApplicationEventPublisher eventPublisher;
    private final DataManager dataManager;

    public AiParametersEntityListener(ApplicationEventPublisher eventPublisher,
                                      DataManager dataManager) {
        this.eventPublisher = eventPublisher;
        this.dataManager = dataManager;
    }

    @EventListener
    public void onAiParametersChanged(EntityChangedEvent<AiParameters> event) {
        if (shouldPublish(event)) {
            eventPublisher.publishEvent(
                    new AiSettingsChangedEvent(this, AiSettingsChangedEvent.Kind.PARAMETERS));
        }
    }

    private boolean shouldPublish(EntityChangedEvent<AiParameters> event) {
        AttributeChanges changes = event.getChanges();
        switch (event.getType()) {
            case DELETED:
                // CASE 1: deleting a previously-active profile transitions the effective
                // settings; for DELETED, getOldValue("active") holds the value at load time.
                Boolean oldActiveDeleted = changes.getOldValue(ACTIVE_ATTRIBUTE);
                return Boolean.TRUE.equals(oldActiveDeleted);

            case CREATED:
                // CASE 2: re-read the saved row to find its current `active` value.
                // For CREATED, getOldValue("active") is null per AttributeChanges contract.
                AiParameters createdRow = dataManager.load(AiParameters.class)
                        .id(event.getEntityId().getValue())
                        .optional()
                        .orElse(null);
                return createdRow != null && Boolean.TRUE.equals(createdRow.getActive());

            case UPDATED:
                return shouldPublishOnUpdate(event, changes);

            default:
                return false;
        }
    }

    private boolean shouldPublishOnUpdate(EntityChangedEvent<AiParameters> event,
                                          AttributeChanges changes) {
        boolean activeFieldChanged = changes.isChanged(ACTIVE_ATTRIBUTE);
        if (!activeFieldChanged) {
            // CASE 3a — `active` not in change set; effective-settings change IFF the
            // row IS currently active.
            AiParameters current = dataManager.load(AiParameters.class)
                    .id(event.getEntityId().getValue())
                    .optional()
                    .orElse(null);
            return current != null && Boolean.TRUE.equals(current.getActive());
        }

        Boolean oldActive = changes.getOldValue(ACTIVE_ATTRIBUTE);
        boolean wasActive = Boolean.TRUE.equals(oldActive);

        // CASE 3b — ACTIVATION (oldValue=false → newValue=true): the row IS now the
        // effective profile, publish.
        // CASE 3c — DEACTIVATION (oldValue=true → newValue=false; codex HIGH Concern #6):
        // the previously-effective profile is no longer effective, publish so Phase 18
        // caches invalidate.
        // CASE 3d — inactive→inactive: no effective-settings transition, do not publish.
        //
        // (Detecting the *new* value requires re-reading the row; we publish unless
        // both old AND new are non-active.)
        if (!wasActive) {
            // oldActive is false-or-null. Need the new value to distinguish 3b (activate)
            // from 3d (no-op).
            AiParameters current = dataManager.load(AiParameters.class)
                    .id(event.getEntityId().getValue())
                    .optional()
                    .orElse(null);
            return current != null && Boolean.TRUE.equals(current.getActive());
        }
        // wasActive == true; new value must be either still-true (3a path, covered above
        // when the field is not in change set, so unreachable here) OR false (3c
        // DEACTIVATION). Either way, the effective profile transitioned — publish.
        return true;
    }
}
