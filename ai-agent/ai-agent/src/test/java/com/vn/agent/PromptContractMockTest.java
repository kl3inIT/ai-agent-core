package com.vn.agent;

import com.vn.agent.guard.AgentSystemPromptRules;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.BuiltInDataTools;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-08 prompt-contract regression suite (D-16 mock variant). Locks the four Phase 9 contracts:
 * <ul>
 *   <li>PROMPT-03 vocabulary rule present in composed system prompt.</li>
 *   <li>PROMPT-05 / D-14 unknown_entity hints in {@code ToolErrorDto.expected[]}.</li>
 *   <li>PROMPT-06 OutputScannerAdvisor flags {@code HOST_PREFIX_LEAK} and {@code TOOL_NAME_LEAK}.</li>
 *   <li>D-17 cross-locale assertion: contract holds for English AND Vietnamese.</li>
 * </ul>
 *
 * <p>Runs in default {@code ./gradlew :ai-agent:ai-agent:test}. The live variant is at
 * {@code com.vn.agent.live.PromptContractLiveTest} ({@code @Tag("live")}, opt-in only).
 *
 * <p><b>Entity-prefix fixture (Rule 3 deviation from plan example):</b> the planner's example
 * scripts {@code "The jmixapp_Customer table has 12 rows."} as the leaky reply. The test
 * JmixModule (AITestConfiguration) only registers entities under the {@code ai} prefix
 * ({@code ai_AiAuditEvent}, {@code ai_AiMessage}, {@code ai_AiConversation}, ...). The
 * {@link com.vn.agent.guard.HostPrefixPatternProvider} therefore compiles its regex as
 * {@code \b(ai)_\w+\b} at startup, which does NOT match {@code jmixapp_Customer}. To preserve
 * the planner intent of asserting against the dynamic regex (rather than a synthetic operator-
 * configured pattern), the test uses {@code ai_Customer} as the leaky entity-prefix string —
 * a token shaped like a real Jmix metaclass under this host's prefix that does NOT exist in
 * the test metamodel. The {@code HOST_PREFIX_LEAK} flag triggers for the same reason it
 * would in production: the LLM emitted an internal {@code <prefix>_<Name>} identifier in
 * user-visible text.
 *
 * <p>The same scripted-reply mechanism is used in both EN and VI parameterized iterations to
 * prove the OutputScannerAdvisor + ChatResponseDto promotion contract is locale-independent.
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({PromptContractMockTest.PromptContractStubChatModelConfiguration.class,
        StubVectorStoreConfiguration.class})
class PromptContractMockTest {

    @Autowired ChatService chatService;
    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired BuiltInDataTools builtInDataTools;
    @Autowired PromptContractStubChatModelConfiguration.Recorder recorder;

    static Stream<Arguments> locales() {
        return Stream.of(
                Arguments.of(Locale.ENGLISH, "How many customers?"),
                Arguments.of(Locale.of("vi", "VN"), "có bao nhiêu khách hàng?")
        );
    }

    // ---------- Test 1: PROMPT_RULES constant is well-formed ----------

    @Test
    void composedSystemPrompt_carriesPhase9PromptRules() {
        assertThat(AgentSystemPromptRules.PROMPT_RULES).isNotNull();
        assertThat(AgentSystemPromptRules.PROMPT_RULES).isNotEmpty();
        assertThat(AgentSystemPromptRules.PROMPT_RULES).contains("do NOT use internal entity names");
        assertThat(AgentSystemPromptRules.PROMPT_RULES).contains("do NOT mention tool names");
        assertThat(AgentSystemPromptRules.PROMPT_RULES).contains("call list_entities exactly once");
        assertThat(AgentSystemPromptRules.PROMPT_RULES).contains("do not guess");
    }

    // ---------- Test 2: unknown_entity hints in tool error (D-14 cross-assertion) ----------

    @Test
    void unknownEntityToolError_carriesThreeProceduralHintsVerbatim() {
        String json = systemAuthenticator.withSystem(() ->
                builtInDataTools.describeEntity("nope_does_not_exist"));
        assertThat(json).contains("\"error\":\"unknown_entity\"");
        assertThat(json).contains("\"call list_entities exactly once\"");
        assertThat(json).contains(
                "\"if a name in list_entities matches your intent, retry the original tool with that exact name\"");
        assertThat(json).contains(
                "\"if no entity in list_entities matches, tell the user no such entity exists — do not guess\"");
    }

    // ---------- Test 3 + 4 + 5: per-locale scanner contract ----------

    @ParameterizedTest(name = "[{0}] entityPrefixLeak triggers HOST_PREFIX_LEAK")
    @MethodSource("locales")
    void entityPrefixLeak_triggersHostPrefixLeakFlag(Locale locale, String userMessage) {
        // Use ai_Customer (rather than the planner-example jmixapp_Customer) so the dynamically-
        // derived HostPrefixPatternProvider regex \b(ai)_\w+\b actually triggers in this test
        // metamodel (Rule 3 deviation; rationale documented at class-level Javadoc).
        recorder.scriptReply("HOST_PREFIX_LEAK", "The ai_Customer table has 12 rows.");
        runChatTurnAndAssertFlag(locale, userMessage, "HOST_PREFIX_LEAK");
    }

    @ParameterizedTest(name = "[{0}] toolNameLeak triggers TOOL_NAME_LEAK")
    @MethodSource("locales")
    void toolNameLeak_triggersToolNameLeakFlag(Locale locale, String userMessage) {
        recorder.scriptReply("TOOL_NAME_LEAK", "I will call find_records to look them up.");
        runChatTurnAndAssertFlag(locale, userMessage, "TOOL_NAME_LEAK");
    }

    @ParameterizedTest(name = "[{0}] benign natural-language reply does not trigger")
    @MethodSource("locales")
    void benignReply_doesNotTriggerScanner(Locale locale, String userMessage) {
        String benign = locale.getLanguage().equals("vi")
                ? "Bạn có 12 khách hàng."
                : "You have 12 customers.";
        recorder.scriptReply("BENIGN", benign);
        runChatTurnAndAssertNoFlag(locale, userMessage);
    }

    // ---------- Test 6: rules flow through to LLM Prompt ----------

    @Test
    void systemPromptRulesAreCarriedThroughToLLM() {
        recorder.scriptReply("PASSTHROUGH", "Anything.");
        systemAuthenticator.runWithSystem(() ->
                chatService.ask("test-user", null, "ping"));
        Prompt captured = recorder.lastPrompt();
        assertThat(captured).as("ChatModel must have been invoked at least once").isNotNull();
        String systemText = extractSystemText(captured);
        assertThat(systemText).contains("do NOT use internal entity names");
        assertThat(systemText).contains("do NOT mention tool names");
        assertThat(systemText).contains("call list_entities exactly once");
        assertThat(systemText).contains("do not guess");
    }

    // ---------- Test 7: cross-locale agent.locale token (D-17 lock) ----------

    /**
     * D-17 cross-locale guarantee: prove the EN and VI parameterised iterations actually
     * execute under DIFFERENT effective locales by capturing the system prompt for each
     * iteration and asserting the {@code agent.locale=} token differs. Without this
     * assertion the locale parameterisation would be cosmetic.
     */
    @Test
    void systemPromptCarriesDifferentAgentLocaleTokenPerIteration() {
        recorder.scriptReply("PASSTHROUGH", "Anything.");

        recorder.setLocale(Locale.ENGLISH);
        systemAuthenticator.runWithSystem(() ->
                chatService.ask("test-user-en", null, "How many customers?"));
        String enSystemText = extractSystemText(recorder.lastPrompt());

        recorder.setLocale(Locale.of("vi", "VN"));
        systemAuthenticator.runWithSystem(() ->
                chatService.ask("test-user-vi", null, "co bao nhieu khach hang?"));
        String viSystemText = extractSystemText(recorder.lastPrompt());

        // BaselineContextProvider emits "agent.locale=<locale>" sourced from
        // CurrentAuthentication.getLocale(). Locale.ENGLISH.toString() == "en";
        // Locale.of("vi","VN").toString() == "vi_VN".
        assertThat(enSystemText).contains("agent.locale=en");
        assertThat(viSystemText).contains("agent.locale=vi_VN");
        assertThat(enSystemText).doesNotContain("agent.locale=vi_VN");
        // Match a stricter VI->EN guard: "agent.locale=en\n" cannot appear in VI text. Use the
        // newline-suffixed token so the substring check does not coincidentally match the inner
        // "en" in "agent.locale=vi_VN" or any future locale that begins with "en".
        assertThat(viSystemText).doesNotContain("agent.locale=en\n");
    }

    private String extractSystemText(Prompt captured) {
        assertThat(captured).isNotNull();
        return captured.getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.SYSTEM)
                .map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    // ---------- helpers ----------

    private void runChatTurnAndAssertFlag(Locale locale, String userMessage, String expectedKey) {
        // Locked locale-injection mechanism (D-17 cross-locale guarantee):
        // PromptContractStubChatModelConfiguration registers a @Bean @Primary
        // CurrentAuthentication mock whose getLocale() returns whatever was last set via
        // recorder.setLocale(...). BaselineContextProvider reads currentAuthentication.getLocale()
        // on every chat turn and writes the result into the "agent.locale" key of the baseline
        // text that flows into the system prompt. By mutating the recorder's locale per
        // parameterised iteration, the EN and VI iterations DO see distinct effective locales,
        // distinct "agent.locale" tokens, and distinct system prompts. Verified by
        // systemPromptCarriesDifferentAgentLocaleTokenPerIteration.
        recorder.setLocale(locale);
        ChatResponseDto resp = systemAuthenticator.withSystem(() ->
                chatService.ask("test-user-" + locale, null, userMessage));
        assertThat(resp).isNotNull();
        assertThat(resp.content()).isEqualTo(recorder.scriptedReply());
        assertThat(resp.flagged()).isTrue();
        assertThat(resp.flaggedPatternKey()).isEqualTo(expectedKey);
    }

    private void runChatTurnAndAssertNoFlag(Locale locale, String userMessage) {
        recorder.setLocale(locale);
        ChatResponseDto resp = systemAuthenticator.withSystem(() ->
                chatService.ask("test-user-" + locale, null, userMessage));
        assertThat(resp.content()).isEqualTo(recorder.scriptedReply());
        assertThat(resp.flagged()).isFalse();
        assertThat(resp.flaggedPatternKey()).isNull();
    }

    // ---------- @TestConfiguration: scripted ChatModel + recorder ----------

    @TestConfiguration
    public static class PromptContractStubChatModelConfiguration {

        @Bean
        public Recorder recorder() {
            return new Recorder();
        }

        /**
         * Remove the {@code stubChatModel} bean discovered by the add-on's {@code @ComponentScan}
         * over {@code com.vn.agent.test_support} so this test's
         * {@code promptContractScriptedChatModel} (also marked {@code @Primary}) is the sole
         * candidate that survives autowire resolution. Without this post-processor two
         * {@code @Primary ChatModel} beans coexist and {@code ChatClientFactory} fails with
         * {@code NoUniqueBeanDefinitionException}.
         */
        @Bean
        public static org.springframework.beans.factory.config.BeanFactoryPostProcessor
                removeAddOnStubChatModel() {
            return beanFactory -> {
                if (beanFactory instanceof
                        org.springframework.beans.factory.support.BeanDefinitionRegistry registry
                        && registry.containsBeanDefinition("stubChatModel")) {
                    registry.removeBeanDefinition("stubChatModel");
                }
            };
        }

        /**
         * Phase 9 D-17 locale-injection mechanism (locked).
         *
         * <p>BaselineContextProvider reads {@code currentAuthentication.getLocale()} on every
         * chat turn. By overriding the {@code CurrentAuthentication} bean with a Mockito mock
         * whose {@code getLocale()} delegates to the per-test {@link Recorder}, each
         * parameterised iteration sees a distinct effective locale: the EN iteration -&gt;
         * {@code Locale.ENGLISH} -&gt; baseline emits {@code agent.locale=en}; the VI iteration
         * -&gt; {@code Locale.of("vi","VN")} -&gt; baseline emits {@code agent.locale=vi_VN}.
         */
        /**
         * Per-test {@link CurrentAuthentication} that delegates locale to the {@link Recorder}
         * (so each parameterised iteration sees a distinct effective locale via D-17), and
         * delegates {@code getAuthentication()} / {@code getUser()} to the live
         * {@code SecurityContextHolder} so Jmix's security policy stores
         * ({@code AuthenticationPolicyStore.getScope}) and AccessManager-driven CRUD checks
         * see the real system-user Authentication that
         * {@link io.jmix.core.security.SystemAuthenticator#withSystem(io.jmix.core.security.SystemAuthenticator.AuthenticatedOperation)}
         * pushes onto the security context for the duration of the chat turn.
         *
         * <p>If the chat turn is invoked outside any
         * {@code SystemAuthenticator.withSystem(...)} block ({@code SecurityContextHolder} empty),
         * the bean still returns a stable fallback user (the same shape Jmix's anonymous-user
         * authentication carries) so {@code getUser()} never returns {@code null} in any
         * pre-{@code withSystem} initialisation hook (e.g. cache warm-up, eager-singleton
         * construction).
         */
        @Bean
        @Primary
        public CurrentAuthentication recordingCurrentAuthentication(Recorder recorder) {
            CurrentAuthentication ca = org.mockito.Mockito.mock(CurrentAuthentication.class);
            org.springframework.security.core.userdetails.User fallbackUser =
                    new org.springframework.security.core.userdetails.User(
                            "prompt-contract-test-user", "x",
                            java.util.List.of(new org.springframework.security.core.authority
                                    .SimpleGrantedAuthority("ROLE_USER")));
            org.mockito.Mockito.when(ca.getLocale())
                    .thenAnswer(inv -> recorder.locale());
            org.mockito.Mockito.when(ca.getAuthentication())
                    .thenAnswer(inv -> {
                        var sec = org.springframework.security.core.context.SecurityContextHolder
                                .getContext().getAuthentication();
                        return sec != null
                                ? sec
                                : new org.springframework.security.authentication
                                        .UsernamePasswordAuthenticationToken(
                                                fallbackUser, "x", fallbackUser.getAuthorities());
                    });
            org.mockito.Mockito.when(ca.getUser())
                    .thenAnswer(inv -> {
                        var sec = org.springframework.security.core.context.SecurityContextHolder
                                .getContext().getAuthentication();
                        Object principal = sec == null ? null : sec.getPrincipal();
                        return principal instanceof
                                org.springframework.security.core.userdetails.UserDetails ud
                                ? ud
                                : fallbackUser;
                    });
            return ca;
        }

        /**
         * Use a unique bean name so the test's scripted ChatModel does NOT collide with the
         * {@code StubChatModelConfiguration.stubChatModel()} bean that the add-on's
         * {@code @ComponentScan} picks up from {@code com.vn.agent.test_support}. The conflict
         * is resolved at the post-processor stage (see
         * {@link #removeAddOnStubChatModel()}) by deregistering the scan-discovered bean before
         * autowire resolution, leaving this {@code @Primary} bean as the sole {@code ChatModel}
         * candidate (alongside the starter's {@code openAiChatModel}, which our {@code @Primary}
         * supersedes for autowire purposes).
         */
        @Bean(name = "promptContractScriptedChatModel")
        @Primary
        public ChatModel promptContractScriptedChatModel(Recorder recorder) {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    recorder.recordPrompt(prompt);
                    AssistantMessage reply = new AssistantMessage(recorder.scriptedReply());
                    return new ChatResponse(List.of(new Generation(reply)));
                }
            };
        }

        /** Per-test mutable state captured across the EN+VI iterations. */
        public static class Recorder {
            private volatile String scriptedReply = "ok";
            private volatile Prompt lastPrompt;
            private volatile Locale locale = Locale.ENGLISH;

            public void scriptReply(String key, String body) {
                this.scriptedReply = body;
            }

            public String scriptedReply() { return scriptedReply; }

            public void recordPrompt(Prompt prompt) { this.lastPrompt = prompt; }
            public Prompt lastPrompt() { return lastPrompt; }

            /** D-17: per-iteration locale read by the @Primary CurrentAuthentication mock. */
            public void setLocale(Locale locale) { this.locale = locale; }
            public Locale locale() { return locale; }
        }
    }
}
