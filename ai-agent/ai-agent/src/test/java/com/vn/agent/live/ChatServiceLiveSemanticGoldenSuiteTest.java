package com.vn.agent.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.ChatService;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.spi.AuditKind;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-05 completion — live-tier semantic golden suite (Phase 08-04).
 *
 * <p>CONTEXT D-08: capability-coverage questions (now SEVEN per R-04b split).</p>
 * <p>CONTEXT D-09: containsAnyOf semantic anchors only (no exact-match contracts).</p>
 * <p>CONTEXT D-11: dual-gated by {@link Tag}("live") AND
 * {@link EnabledIfEnvironmentVariable @EnabledIfEnvironmentVariable("OPENROUTER_API_KEY")}.</p>
 * <p>CONTEXT D-12: uses the active AiParameters profile model (no overrides).</p>
 *
 * <p>Fixture: {@code src/test/resources/golden-questions.yaml}.</p>
 *
 * <p>Default {@code ./gradlew test} excludes {@code @Tag("live")}; run via
 * {@code ./gradlew :ai-agent:ai-agent:liveTest} with {@code OPENROUTER_API_KEY} set.</p>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Tag("live")
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
class ChatServiceLiveSemanticGoldenSuiteTest {

    @Autowired ChatService chatService;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator systemAuthenticator;

    /**
     * R-04e visible-skip indicator. JUnit's {@link EnabledIfEnvironmentVariable} produces a
     * standard "disabled" marker, but in CI logs that can be confused with "green by
     * default-task-excludes-live". This {@link BeforeAll} prints an unambiguous one-liner
     * that distinguishes the two skip reasons.
     */
    @BeforeAll
    static void announceLiveSuiteState() {
        // WR-009: do NOT print prefix/suffix fragments of the API key — GitHub Actions
        // log masking covers the full secret value but is not reliable for partial
        // fragments, which can leak a stable credential fingerprint. Match the workflow
        // preflight (`OPENROUTER_API_KEY length: ${#OPENROUTER_API_KEY}`) and emit only a
        // boolean enable signal plus the key length.
        String key = System.getenv("OPENROUTER_API_KEY");
        boolean enabled = key != null && !key.isBlank();
        String suffix = enabled
                ? "ENABLED — key present, length=" + key.length()
                : "SKIPPED — no OPENROUTER_API_KEY in environment";
        System.out.println("Live suite: " + suffix);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loadQuestions")
    @DisplayName("Live-tier golden capability coverage")
    void liveSemanticAnchors(GoldenQuestion question) {
        UUID conversationId = null;

        // Replay any prior turns (multi-turn-memory case).
        if (question.multiTurnPrior() != null) {
            for (String prior : question.multiTurnPrior()) {
                ChatResponseDto priorResponse = chatService.ask("test-live-golden", conversationId, prior);
                conversationId = priorResponse.conversationId();
            }
        }

        ChatResponseDto response = chatService.ask("test-live-golden", conversationId, question.prompt());
        String content = response.content() == null ? "" : response.content();
        String normalized = content.toLowerCase(Locale.ROOT);

        // Anchors: pass if ANY anchor token appears (case-insensitive).
        assertThat(question.anchors())
                .as("question %s — at least one anchor must appear in response: %s", question.id(), content)
                .anyMatch(anchor -> normalized.contains(anchor.toLowerCase(Locale.ROOT)));

        // notAnchors: NONE may appear.
        if (question.notAnchors() != null && !question.notAnchors().isEmpty()) {
            for (String forbidden : question.notAnchors()) {
                assertThat(normalized)
                        .as("question %s — response must NOT contain forbidden token '%s'", question.id(), forbidden)
                        .doesNotContain(forbidden.toLowerCase(Locale.ROOT));
            }
        }

        // WR-007: assert expectedTools were actually invoked. Without this, a model
        // could produce the right anchor words by hallucination without exercising
        // the production tool path (list_entities / find_records / count_records).
        // Load AiAuditEvent rows for this run with kind = TOOL and verify the
        // captured eventName values include every name in question.expectedTools().
        // Wrapped in SystemAuthenticator.withSystem so the audit-store load is not
        // blocked by the runtime user context (the live test_user role does not
        // necessarily grant ai_AiAuditEvent read).
        if (question.expectedTools() != null && !question.expectedTools().isEmpty()) {
            UUID runId = response.runId();
            assertThat(runId)
                    .as("question %s — ChatResponseDto.runId() must be present to verify tool invocations",
                            question.id())
                    .isNotNull();
            List<String> invokedToolNames = systemAuthenticator.withSystem(() ->
                    dataManager.load(AiAuditEvent.class)
                            .query("select a from ai_AiAuditEvent a where a.runId = :rid and a.kind = :k")
                            .parameter("rid", runId)
                            .parameter("k", AuditKind.TOOL)
                            .list()
                            .stream()
                            .map(AiAuditEvent::getEventName)
                            .toList());
            assertThat(invokedToolNames)
                    .as("question %s — expected tools %s must all appear in TOOL audit rows for run %s "
                            + "(observed: %s)",
                            question.id(), question.expectedTools(), runId, invokedToolNames)
                    .containsAll(question.expectedTools());
        }
    }

    // ----- YAML loader -----

    static List<GoldenQuestion> loadQuestions() throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream in = ChatServiceLiveSemanticGoldenSuiteTest.class
                .getClassLoader()
                .getResourceAsStream("golden-questions.yaml")) {
            if (in == null) {
                throw new IllegalStateException(
                        "golden-questions.yaml not found on test classpath");
            }
            return List.of(mapper.readValue(in, GoldenQuestion[].class));
        }
    }

    record GoldenQuestion(
            String id,
            String prompt,
            List<String> anchors,
            List<String> notAnchors,
            List<String> expectedTools,
            List<String> multiTurnPrior,
            String precondition,
            String notes) {

        @Override
        public String toString() {
            return id;
        }
    }
}
