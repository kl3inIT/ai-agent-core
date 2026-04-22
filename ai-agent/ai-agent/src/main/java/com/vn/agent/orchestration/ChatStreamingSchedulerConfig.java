package com.vn.agent.orchestration;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
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
 *
 * <p><b>Reactor context propagation:</b> the {@link #enableReactorContextPropagation()} hook is
 * required so Jmix security context survives scheduler hops during streaming. Spring AI's
 * {@code ChatClient.stream()} pipeline executes the advisor chain (including
 * {@code MessageChatMemoryAdvisor}, which persists entities via Jmix {@code DataManager}) on
 * internal Reactor scheduler threads — different from the outer {@code chatStreamingScheduler}
 * thread where {@code systemAuthenticator.begin(userId)} populates the
 * {@code SecurityContextHolder}. Without automatic context propagation the advisor runs with an
 * empty {@code SecurityContext} and Jmix's {@code CurrentAuthenticationImpl.getAuthentication()}
 * throws {@code IllegalStateException: Authentication is not set}.</p>
 *
 * <p>The hook relies on {@code io.micrometer:context-propagation} (on the classpath
 * transitively via Reactor 3.5+) and Spring Security's
 * {@code SecurityContextHolderThreadLocalAccessor}, registered via
 * {@code META-INF/services/io.micrometer.context.ThreadLocalAccessor} in
 * {@code spring-security-core} 6.x.</p>
 */
@Configuration
public class ChatStreamingSchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamingSchedulerConfig.class);

    /**
     * Enable Reactor automatic context propagation exactly once per JVM. Idempotent: calling
     * {@link Hooks#enableAutomaticContextPropagation()} multiple times is harmless, but we guard
     * with {@code @PostConstruct} on a single bean to keep startup logs clean.
     *
     * <p>After this hook is enabled, Reactor captures all registered {@code ThreadLocalAccessor}
     * values (including Spring Security's {@code SecurityContext}) at subscription time on the
     * subscribing thread and restores them on every scheduler worker thread that runs a downstream
     * operator. This carries Jmix's {@code SecurityContextHolder} authentication — established by
     * {@code systemAuthenticator.begin(userId)} inside {@code DefaultChatServiceImpl.stream()}'s
     * {@code Flux.using} resource factory — across the scheduler boundary into Spring AI's
     * streaming HTTP client and advisor chain.</p>
     */
    @PostConstruct
    public void enableReactorContextPropagation() {
        Hooks.enableAutomaticContextPropagation();
        log.info("Reactor automatic context propagation enabled "
                + "(propagates Spring Security SecurityContext across streaming scheduler hops).");
    }

    /**
     * Boundary parameters tuned for the add-on's expected traffic shape (human chat,
     * sub-minute responses). Pool bounds mirror the {@code boundedElastic} default proportions.
     */
    @Bean(name = "chatStreamingScheduler", destroyMethod = "dispose")
    public Scheduler chatStreamingScheduler() {
        return Schedulers.newBoundedElastic(20, 1000, "ai-agent-stream");
    }
}
