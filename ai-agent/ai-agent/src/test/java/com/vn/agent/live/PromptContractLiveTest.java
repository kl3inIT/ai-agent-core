package com.vn.agent.live;

// RUN MANUALLY:
//   ./gradlew :ai-agent:liveTest --tests "*PromptContractLiveTest"
// Requires environment variables:
//   OPENROUTER_API_KEY         (propagated into spring.ai.openai.api-key via test-app.properties)
//   optionally OPENROUTER_BASE_URL / OPENROUTER_MODEL
// Skipped by default:
//   - @Tag("live") tag is excluded by the ai-agent test task (see ai-agent.gradle:135).
//   - @EnabledIfEnvironmentVariable adds a second gate in case the task filter is bypassed.

import com.vn.agent.AITestConfiguration;
import com.vn.agent.ChatService;
import com.vn.agent.orchestration.ChatResponseDto;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-08 prompt-contract live variant (D-16 opt-in). Excluded from default {@code ./gradlew test}
 * by the {@code excludeTags 'live'} filter in {@code ai-agent.gradle}. Run explicitly with:
 *
 * <pre>./gradlew :ai-agent:liveTest --tests "*PromptContractLiveTest"</pre>
 *
 * <p>Requires {@code OPENROUTER_API_KEY} (or the configured equivalent) to be set; otherwise
 * skipped via {@link EnabledIfEnvironmentVariable}.
 *
 * <p>Asserts the same vocabulary contract as {@link com.vn.agent.PromptContractMockTest} but
 * against the configured real model. The real model SHOULD respect the system-prompt rule
 * landed in {@code AgentSystemPromptRules.PROMPT_RULES} and NOT quote internal entity names
 * or raw tool names; if it does, {@code OutputScannerAdvisor} flags the response (audit-only —
 * Phase 9 posture is flag-and-pass-through, not block).
 *
 * <p>Mirrors the EN+VI parameterisation of the mock test so the live model is exercised against
 * both locales the host commits to support. The user message is the same trivial "how many
 * customers do we have?" probe that does not depend on real entity data — the wire is what we
 * are validating, not the model's domain knowledge.
 *
 * <p>No {@code StubChatModelConfiguration} import — this test needs the autoconfigured OpenAI
 * {@code ChatModel} from the starter (matching the {@link ChatServiceLiveSemanticTest}
 * convention).
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Tag("live")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class PromptContractLiveTest {

    @Autowired ChatService chatService;
    @Autowired SystemAuthenticator systemAuthenticator;

    static Stream<Arguments> userMessagesByLocale() {
        return Stream.of(
                Arguments.of(Locale.ENGLISH, "How many customers do we have?"),
                Arguments.of(Locale.of("vi", "VN"), "có bao nhiêu khách hàng?")
        );
    }

    @ParameterizedTest(name = "[{0}] live model reply hides host_prefix and tool names")
    @MethodSource("userMessagesByLocale")
    void liveReply_doesNotLeakInternalVocabulary(Locale locale, String userMessage) {
        ChatResponseDto resp = systemAuthenticator.withSystem(() ->
                chatService.ask("live-test-user-" + locale, null, userMessage));

        assertThat(resp).isNotNull();
        String userVisibleText = resp.content();
        assertThat(userVisibleText).as("live reply must be non-empty").isNotBlank();

        // Host-prefix detection: any underscore-bearing identifier of the form <prefix>_<Word>.
        // Common Jmix host prefixes (sample, jmixapp, acme, ai) all need to stay out of
        // user-visible text per PROMPT-03.
        assertThat(userVisibleText)
                .as("live reply must not quote host-prefixed metaclass names like jmixapp_Customer")
                .doesNotMatch("(?s).*\\b(jmixapp|sample|acme|jmix|ai)_[A-Z][A-Za-z0-9]*\\b.*");

        // Raw tool names — the six built-ins + RETRIEVAL.
        for (String toolName : new String[]{"list_entities", "describe_entity", "find_records",
                                            "count_records", "get_record", "get_related_records"}) {
            assertThat(userVisibleText)
                    .as("live reply must not mention tool name " + toolName)
                    .doesNotContain(toolName);
        }
        assertThat(userVisibleText).doesNotContain("RETRIEVAL");
    }
}
