package com.vn.agent;

import com.vn.agent.conversation.AiAgentTitleProperties;
import com.vn.agent.rag.MdcPropagatingTaskDecorator;
import com.vn.agent.rag.config.AiAgentRagProperties;
import com.vn.agent.spi.MutationGuard;
import io.jmix.core.annotation.JmixModule;
import io.jmix.core.impl.scanning.AnnotationScanMetadataReaderFactory;
import io.jmix.data.DataConfiguration;
import io.jmix.eclipselink.EclipselinkConfiguration;
import io.jmix.flowui.FlowuiConfiguration;
import io.jmix.flowui.sys.ActionsConfiguration;
import io.jmix.flowui.sys.ViewControllersConfiguration;
import io.jmix.security.SecurityConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Collections;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@ComponentScan
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
@JmixModule(dependsOn = {
        DataConfiguration.class,
        SecurityConfiguration.class,
        EclipselinkConfiguration.class,
        FlowuiConfiguration.class
})
@PropertySource(name = "com.vn.agent", value = "classpath:/com/vn/agent/module.properties")
public class AIConfiguration {

    @Bean("AI_AIViewControllers")
    public ViewControllersConfiguration screens(final ApplicationContext applicationContext,
                                                final AnnotationScanMetadataReaderFactory metadataReaderFactory) {
        final ViewControllersConfiguration viewControllers
                = new ViewControllersConfiguration(applicationContext, metadataReaderFactory);
        viewControllers.setBasePackages(Collections.singletonList("com.vn.agent"));
        return viewControllers;
    }

    @Bean("AI_AIActions")
    public ActionsConfiguration actions(final ApplicationContext applicationContext,
                                        final AnnotationScanMetadataReaderFactory metadataReaderFactory) {
        final ActionsConfiguration actions
                = new ActionsConfiguration(applicationContext, metadataReaderFactory);
        actions.setBasePackages(Collections.singletonList("com.vn.agent"));
        return actions;
    }

    /**
     * Bounded {@link ThreadPoolTaskExecutor} for async KB ingestion (D-11 / D-12). All sizing
     * is host-tunable via {@code jmix.ai-agent.rag.ingest-executor.*}. Key properties:
     * <ul>
     *   <li><b>{@link ThreadPoolExecutor.CallerRunsPolicy}</b> — back-pressure on queue overflow
     *       (D-03 fail-closed posture extends to ingestion queue: callers slow down rather than
     *       drop work or OOM the JVM). Addresses threat T-05-03-03 (DoS via flood of uploads).</li>
     *   <li><b>{@link MdcPropagatingTaskDecorator}</b> — correlation-id survival across the
     *       request → worker handoff (RESEARCH Pitfall #6, threat T-05-03-04).</li>
     *   <li><b>{@code waitForTasksToCompleteOnShutdown} with 30s timeout</b> — clean shutdown
     *       so in-flight ingests either finish or go to FAILED via the worker's own catch block.</li>
     * </ul>
     *
     * <p>Named {@code aiAgentIngestExecutor} per CONTEXT D-11. {@code @ConditionalOnMissingBean}
     * by name lets hosts substitute a custom executor without touching the add-on.</p>
     */
    @Bean(name = "aiAgentIngestExecutor")
    @ConditionalOnMissingBean(name = "aiAgentIngestExecutor")
    public ThreadPoolTaskExecutor aiAgentIngestExecutor(AiAgentRagProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        AiAgentRagProperties.IngestExecutor cfg = props.ingestExecutor();
        executor.setCorePoolSize(resolveInt(cfg == null ? null : cfg.corePoolSize(), 2));
        executor.setMaxPoolSize(resolveInt(cfg == null ? null : cfg.maxPoolSize(), 4));
        executor.setQueueCapacity(resolveInt(cfg == null ? null : cfg.queueCapacity(), 64));
        executor.setKeepAliveSeconds(resolveInt(cfg == null ? null : cfg.keepAliveSeconds(), 60));
        executor.setThreadNamePrefix("ai-ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Bounded executor for fail-silent conversation title generation. The title job is deliberately
     * outside the chat reply contract, so queue overflow applies caller-runs back-pressure instead
     * of dropping work or creating an unbounded executor.
     */
    @Bean(name = "aiAgentTitleExecutor")
    @ConditionalOnMissingBean(name = "aiAgentTitleExecutor")
    public ThreadPoolTaskExecutor aiAgentTitleExecutor(AiAgentTitleProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        AiAgentTitleProperties.Executor cfg = props.resolvedExecutor();
        executor.setCorePoolSize(cfg.resolvedCorePoolSize());
        executor.setMaxPoolSize(cfg.resolvedMaxPoolSize());
        executor.setQueueCapacity(cfg.resolvedQueueCapacity());
        executor.setKeepAliveSeconds(cfg.resolvedKeepAliveSeconds());
        executor.setThreadNamePrefix("ai-title-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Default no-op {@link MutationGuard} (Phase 11 SPI-10). Hosts opt in to mutation
     * policy enforcement by declaring their own {@code @Component MutationGuard};
     * otherwise this no-op preserves the SPI's "no-op when host hasn't customized"
     * contract that Phase 9 established with {@code ToolFetchPlanCustomizer}.
     */
    @Bean
    @ConditionalOnMissingBean(MutationGuard.class)
    public MutationGuard noopMutationGuard() {
        return intent -> { /* no-op default; hosts override via own @Component */ };
    }

    private static int resolveInt(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }
}
