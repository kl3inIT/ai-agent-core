package com.vn.autoconfigure.agent;

import com.vn.agent.admin.config.KnobInventory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * Phase 16 D-02 — auto-configuration that registers the
 * {@link KnobInventoryScanner @EventListener(ApplicationReadyEvent)} scanner
 * and the {@link KnobScannerProperties} host-extension hook.
 *
 * <p>{@link KnobInventory} is NOT imported here: it is annotated {@code @Component}
 * and lives at {@code com.vn.agent.admin.config}, inside the package tree scanned
 * by {@code AIConfiguration}'s {@code @ComponentScan} (root: {@code com.vn.agent}).
 * Importing it AND component-scanning it triggers Spring Boot 3.x's default
 * {@code BeanDefinitionOverrideException} (CR-03). The scanner lives at
 * {@code com.vn.autoconfigure.agent} (outside the agent module's scan root), so
 * it must still be imported here.</p>
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
@Import(KnobInventoryScanner.class)
public class KnobInventoryAutoConfiguration {
}
