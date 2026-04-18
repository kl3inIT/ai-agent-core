package com.vn.jmixapp.ai;

import com.vn.agent.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Phase 1 injection proof: on host-app startup, log that {@link ChatService} resolved from the
 * add-on starter. Exercised end-to-end during the consumer-smoke procedure (see
 * {@code docs/consumer-smoke.md}). Does NOT hit the LLM — only proves bean presence.
 *
 * <p>Zero behavior in production beyond a single INFO log line; safe to keep shipped.</p>
 */
@Component
@Order(Integer.MIN_VALUE + 100) // run early but after core Jmix init
public class ChatServiceSmokeRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceSmokeRunner.class);

    private final ChatService chatService;

    public ChatServiceSmokeRunner(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public void run(String... args) {
        log.info("ChatServiceSmokeRunner: ChatService bean present: class={}",
                chatService.getClass().getName());
        // Intentionally do NOT call chatService.ask(...) here — avoids LLM cost on every boot.
        // The actual call path is exercised by ChatServiceLiveTest (Plan 03) or a manual admin action.
    }
}
