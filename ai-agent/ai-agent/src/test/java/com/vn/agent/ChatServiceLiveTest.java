package com.vn.agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 live smoke test — calls OpenRouter via the real wired {@link ChatService} bean.
 *
 * <p>Belt-and-suspenders skip strategy (per Pitfall 3 in 01-RESEARCH.md):</p>
 * <ul>
 *   <li>{@code @Tag("live")} — Gradle's default {@code test} task excludes this tag (see
 *       {@code ai-agent.gradle} — Plan 01 Task 2). CI never runs this test.</li>
 *   <li>{@code @EnabledIfEnvironmentVariable} — manual {@code ./gradlew liveTest} without
 *       the API key skips cleanly with no failure.</li>
 * </ul>
 *
 * <p>Requires {@code OPENROUTER_API_KEY} in env. Spring AI OpenAI starter props in
 * {@code test-app.properties} redirect the OpenAI client to OpenRouter.</p>
 */
@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class ChatServiceLiveTest {

    @Autowired
    ChatService chatService;

    @Test
    void openRouterReturnsNonBlankResponse() {
        ChatResponse response = chatService.ask(
                "Reply with exactly the word OK.",
                UUID.randomUUID(),
                null);

        assertThat(response).isNotNull();
        assertThat(response.content()).isNotBlank();
        // Do NOT assert exact text — brittle. Semantic-similarity assertions are Phase 8 (per D-04).
    }
}
