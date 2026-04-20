package com.vn.agent.live;

// RUN MANUALLY:
//   ./gradlew :ai-agent:ai-agent:liveTest --tests "*ChatServiceLiveSemanticTest"
// Requires environment variables:
//   OPENROUTER_API_KEY         (propagated into spring.ai.openai.api-key via test-app.properties)
//   optionally OPENROUTER_BASE_URL / OPENROUTER_MODEL
// Skipped by default:
//   - @Tag("live") tag is excluded by the ai-agent test task (see ai-agent.gradle).
//   - @EnabledIfEnvironmentVariable adds a second gate in case the task filter is bypassed.

import com.vn.agent.AITestConfiguration;
import com.vn.agent.ChatService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-05 live semantic check. Exercises the REAL OpenRouter wire through {@link ChatService} and
 * asserts a non-empty assistant response. No {@code StubChatModelConfiguration} import — this test
 * needs the autoconfigured OpenAI {@code ChatModel} from the starter.
 *
 * <p>Primary value: prove the full wire works (model resolution, OpenRouter slug, ChatClient,
 * advisor chain, audit writes, dual-layer memory, OpenRouter 200 OK). The assistant content
 * assertion is soft (non-blank + one of several common affirmative words) to tolerate model drift.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Tag("live")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class ChatServiceLiveSemanticTest {

    @Autowired ChatService chatService;

    @Test
    void livePingProducesNonEmptyResponse() {
        var resp = chatService.ask("test-live-user", null, "Reply with the single word: pong");

        assertThat(resp.content()).isNotBlank();
        assertThat(resp.runId()).isNotNull();
        assertThat(resp.conversationId()).isNotNull();

        // Soft semantic assertion — LLMs may add punctuation/casing variance. The test's PRIMARY
        // value is proving the wire works, not specific output text.
        assertThat(resp.content().toLowerCase())
                .containsAnyOf("pong", "yes", "ok", "sure");
    }
}
