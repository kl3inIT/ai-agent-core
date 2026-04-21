package com.vn.agent.orchestration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Dedicated {@link Scheduler} for {@code ChatService.stream()} subscriptions. Off the Tomcat
 * servlet threads (RESEARCH Pitfall #1, #7). 20 concurrent threads, 1000 queued tasks — tuned
 * for human-typing chat traffic. {@code destroyMethod="dispose"} ensures clean shutdown so
 * Reactor's worker threads do not outlive the Spring context.
 *
 * <p>Wired into {@code DefaultChatServiceImpl.stream(...)} via {@code subscribeOn(...)}; every
 * LLM stream runs on this pool rather than the Vaadin UI thread or the Tomcat handler thread,
 * matching RESEARCH Pattern 1 (Streaming ChatView with Push).</p>
 */
@Configuration
public class ChatStreamingScheduler {

    /**
     * Boundary parameters tuned for the add-on's expected traffic shape (human chat,
     * sub-minute responses). Pool bounds mirror the {@code boundedElastic} default proportions.
     */
    @Bean(destroyMethod = "dispose")
    public Scheduler chatStreamingScheduler() {
        return Schedulers.newBoundedElastic(20, 1000, "ai-agent-stream");
    }
}
