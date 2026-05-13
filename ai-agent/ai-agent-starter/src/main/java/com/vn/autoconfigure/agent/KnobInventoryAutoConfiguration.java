package com.vn.autoconfigure.agent;

import com.vn.agent.admin.config.KnobInventory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Phase 16 D-02 — auto-configuration that registers the {@link KnobInventory}
 * holder, the {@link KnobInventoryScanner @EventListener(ApplicationReadyEvent)}
 * scanner, and the {@link KnobScannerProperties} host-extension hook.
 *
 * <p>Runs after {@link AIAutoConfiguration} so the core context (including all
 * {@code @ConfigurationProperties} beans discovered by the
 * {@code @ConfigurationPropertiesScan} on {@code AIConfiguration}) is fully
 * registered before the scanner's {@code ApplicationReadyEvent} listener walks
 * them.</p>
 */
@AutoConfiguration
@AutoConfigureAfter(AIAutoConfiguration.class)
@EnableConfigurationProperties(KnobScannerProperties.class)
@Import({KnobInventory.class, KnobInventoryScanner.class})
public class KnobInventoryAutoConfiguration {
}
