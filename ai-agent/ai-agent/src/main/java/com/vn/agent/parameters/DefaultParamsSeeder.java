package com.vn.agent.parameters;

import com.vn.agent.entity.AiParameters;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.security.SystemAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Seeds a single {@link AiParameters} row with {@code profileName="default"} loaded from
 * {@code classpath:default-params.yaml} on first boot (PARAM-04, D-04).
 *
 * <p>Idempotent: probes the existing row count via a limited-size {@code .list()} check and
 * no-ops if any rows are already present (including a non-default profile an operator may
 * have created). Runs once per boot under {@code ApplicationReadyEvent}, wrapped in
 * {@link SystemAuthenticator#runWithSystem} so {@code DataManager} writes proceed under the
 * system identity (no authenticated principal is bound when the event fires). The idiom
 * matches every other startup path in the codebase — tests (FoundationsBootSmokeTest,
 * AuditListenerDispatcherTest, etc.) all use {@code runWithSystem}.</p>
 */
@Component
@ConditionalOnProperty(
        name = "jmix.ai-agent.parameters.seed-default",
        havingValue = "true",
        matchIfMissing = true)
public class DefaultParamsSeeder {

    private static final Logger log = LoggerFactory.getLogger(DefaultParamsSeeder.class);
    private static final String RESOURCE_PATH = "classpath:default-params.yaml";
    private static final String DEFAULT_PROFILE_NAME = "default";

    private final DataManager dataManager;
    private final Metadata metadata;
    private final AiParametersBodyYamlMapper yamlMapper;
    private final ResourceLoader resourceLoader;
    private final SystemAuthenticator systemAuthenticator;

    public DefaultParamsSeeder(DataManager dataManager,
                               Metadata metadata,
                               AiParametersBodyYamlMapper yamlMapper,
                               ResourceLoader resourceLoader,
                               SystemAuthenticator systemAuthenticator) {
        this.dataManager = dataManager;
        this.metadata = metadata;
        this.yamlMapper = yamlMapper;
        this.resourceLoader = resourceLoader;
        this.systemAuthenticator = systemAuthenticator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        systemAuthenticator.runWithSystem(this::doSeedIfEmpty);
    }

    private void doSeedIfEmpty() {
        // Idempotency guard — probe for any existing row with a typed fluent query.
        // Some environments can reject JPQL entity-name strings during startup even though
        // the class itself is available. Keep startup resilient: log and skip seeding on
        // probe/save failures instead of aborting the application.
        try {
            boolean hasExistingRows = !dataManager.load(AiParameters.class)
                    .all()
                    .maxResults(1)
                    .list()
                    .isEmpty();
            if (hasExistingRows) {
                log.debug("AiParameters already populated — skipping default seed");
                return;
            }
        } catch (RuntimeException probeFailure) {
            log.warn("Unable to probe AiParameters rows; skipping default seed to keep startup alive: {}",
                    probeFailure.getMessage());
            log.debug("AiParameters probe failure details", probeFailure);
            return;
        }

        Resource resource = resourceLoader.getResource(RESOURCE_PATH);
        if (!resource.exists()) {
            log.warn("Default params resource {} not found on classpath — skipping seed",
                    RESOURCE_PATH);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            AiParametersBody body = yamlMapper.readValue(in);
            String canonical = yamlMapper.writeAsYaml(body);
            AiParameters row = metadata.create(AiParameters.class);
            row.setProfileName(DEFAULT_PROFILE_NAME);
            row.setBodyYaml(canonical);
            row.setActive(Boolean.TRUE);
            try {
                dataManager.save(row);
            } catch (RuntimeException saveFailure) {
                log.warn("Unable to persist default AiParameters profile; skipping seed: {}",
                        saveFailure.getMessage());
                log.debug("AiParameters default-seed save failure details", saveFailure);
                return;
            }
            log.info("Seeded default AiParameters profile from {}", RESOURCE_PATH);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load default parameter profile from " + RESOURCE_PATH, e);
        }
    }
}
