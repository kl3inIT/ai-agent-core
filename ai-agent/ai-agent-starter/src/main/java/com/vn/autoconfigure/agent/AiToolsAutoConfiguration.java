package com.vn.autoconfigure.agent;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;

/**
 * Auto-configuration anchor for Phase 3 metadata-first runtime + six built-in tools.
 *
 * <p>Registered in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * alongside {@link AIAutoConfiguration} and {@link SpiDefaultsAutoConfiguration}.
 *
 * <p>Runs AFTER {@link AIAutoConfiguration} so {@code ChatClient.Builder} is present, and
 * AFTER {@link SpiDefaultsAutoConfiguration} so the no-op {@code ToolContributor} default is
 * available when {@code AgentToolCallbacks} is wired.
 *
 * <p>The Phase 3 beans ({@code CurrentUserSchemaAccess},
 * {@code StructuredFilterConditionMapper}, {@code FilterLiteralValueConverter},
 * {@code ToolResultFormatter},
 * {@code BuiltInDataTools}, {@code AgentToolCallbacks}) are discovered via the
 * {@code @ComponentScan} on {@code com.vn.agent.AIConfiguration} (base package
 * {@code com.vn.agent}). This class exists solely for the explicit {@code @AutoConfigureAfter}
 * ordering and as an entry point host apps can swap via their own {@code @AutoConfigureAfter}.
 */
@AutoConfiguration
@AutoConfigureAfter({AIAutoConfiguration.class, SpiDefaultsAutoConfiguration.class})
public class AiToolsAutoConfiguration {
}
