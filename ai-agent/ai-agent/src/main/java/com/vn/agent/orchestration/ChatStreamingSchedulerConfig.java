package com.vn.agent.orchestration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Dedicated {@link Scheduler} configuration for {@code ChatService.stream()} subscriptions.
 * Off the Tomcat servlet threads (RESEARCH Pitfall #1, #7). 20 concurrent threads, 1000 queued
 * tasks — tuned for human-typing chat traffic. {@code destroyMethod="dispose"} ensures clean
 * shutdown so Reactor's worker threads do not outlive the Spring context.
 *
 * <p>Exposed as bean {@code chatStreamingScheduler}. Referenced by
 * {@code DefaultChatServiceImpl}'s constructor via {@code @Qualifier("chatStreamingScheduler")}
 * and by the plan contract under the same name. The @Configuration class is named
 * {@code ChatStreamingSchedulerConfig} (not {@code ChatStreamingScheduler}) to avoid the
 * auto-generated class-bean id clashing with the factory-method bean id — the component-scan
 * registers the config class itself as a bean and its default name would collide with the
 * @Bean method's name.</p>
 *
 * <p>Wired into {@code DefaultChatServiceImpl.stream(...)} via {@code subscribeOn(...)}; every
 * LLM stream runs on this pool rather than the Vaadin UI thread or the Tomcat handler thread,
 * matching RESEARCH Pattern 1 (Streaming ChatView with Push).</p>
 */
@Configuration
public class ChatStreamingSchedulerConfig {

    /**
     * Boundary parameters tuned for the add-on's expected traffic shape (human chat,
     * sub-minute responses). Pool bounds mirror the {@code boundedElastic} default proportions.
     */
    @Bean(name = "chatStreamingScheduler", destroyMethod = "dispose")
    public Scheduler chatStreamingScheduler() {
        return Schedulers.newBoundedElastic(20, 1000, "ai-agent-stream");
    }
}
