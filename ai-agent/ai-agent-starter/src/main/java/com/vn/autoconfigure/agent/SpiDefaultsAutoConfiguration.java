package com.vn.autoconfigure.agent;

import com.vn.agent.spi.AuditListener;
import com.vn.agent.spi.ContextContributor;
import com.vn.agent.spi.CustomIngester;
import com.vn.agent.spi.PromptContextContributor;
import com.vn.agent.spi.ToolContributor;
import com.vn.agent.spi.ToolFetchPlanCustomizer;
import com.vn.agent.spi.ToolGuard;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * No-op default beans for every ai-agent SPI so host apps can inject them without declaring
 * their own implementation. Hosts override by declaring a bean of the matching type — Spring's
 * {@link ConditionalOnMissingBean} removes the default in that case.
 *
 * <p>Registered in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * alongside {@link AIAutoConfiguration}. Runs AFTER {@code AIAutoConfiguration} so that any SPI
 * beans declared inside {@code AIConfiguration} (component-scan) are already discovered and the
 * {@code @ConditionalOnMissingBean} check sees the correct set.</p>
 */
@AutoConfiguration
@AutoConfigureAfter(AIAutoConfiguration.class)
public class SpiDefaultsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ToolContributor defaultToolContributor() {
        return Collections::emptyList;
    }

    @Bean
    @ConditionalOnMissingBean
    public ContextContributor defaultContextContributor() {
        return bag -> { /* no-op */ };
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptContextContributor defaultPromptContextContributor() {
        return () -> "";
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolGuard defaultToolGuard() {
        return (toolName, arguments) -> { /* allow all */ };
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditListener defaultAuditListener() {
        return (UUID auditId, String kind) -> { /* no-op */ };
    }

    @Bean
    @ConditionalOnMissingBean
    public CustomIngester defaultCustomIngester() {
        return new CustomIngester() {
            @Override public String getId() { return "noop"; }
            @Override public String getDisplayName() { return "No-op"; }
            @Override public List<Document> read() { return Collections.emptyList(); }
        };
    }

    /**
     * Default no-op {@link ToolFetchPlanCustomizer} (SPI-09, D-09). Returns
     * {@link Optional#empty()} for every {@code (toolName, metaClass, context)} triple so the
     * add-on falls back to {@link io.jmix.core.FetchPlan#BASE} when no host customizer matches.
     *
     * <p>Hosts override by declaring a bean implementing {@link ToolFetchPlanCustomizer};
     * Spring's {@link ConditionalOnMissingBean} removes this default in that case. Multiple
     * host customizers compose via {@code List<ToolFetchPlanCustomizer>} injection at the
     * consumer site (Plan 09-04 {@code FetchPlanIntersector}).
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolFetchPlanCustomizer defaultToolFetchPlanCustomizer() {
        return (toolName, metaClass, context) -> Optional.empty();
    }
}
