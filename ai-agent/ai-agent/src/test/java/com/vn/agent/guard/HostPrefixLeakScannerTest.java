package com.vn.agent.guard;

import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.Session;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 9 PROMPT-06 / D-06: validates {@link HostPrefixPatternProvider} compiles a regex over
 * the host's metaclass prefix tokens at startup and that the {@link OutputScannerAdvisor}
 * propagates a {@code HOST_PREFIX_LEAK} flag when assistant text quotes a host-prefixed entity
 * name (e.g. {@code jmixapp_Customer}).
 */
class HostPrefixLeakScannerTest {

    @Test
    void buildsRegexFromMetamodelPrefixes() {
        HostPrefixPatternProvider provider = providerFor(
                Set.of("jmixapp_Customer", "jmixapp_Order", "acme_Product"),
                /* enabled */ true);

        provider.buildPattern();

        Optional<AiAgentGuardProperties.OutputScanner.Pattern> pattern = provider.asPattern();
        assertThat(pattern).isPresent();
        assertThat(pattern.get().key()).isEqualTo("HOST_PREFIX_LEAK");
        // TreeSet sort: "acme" < "jmixapp" alphabetically; tokens are Pattern.quote-wrapped.
        assertThat(pattern.get().regex())
                .isEqualTo("\\b(\\Qacme\\E|\\Qjmixapp\\E)_\\w+\\b");
    }

    @Test
    void emptyMetamodelYieldsNoPattern() {
        HostPrefixPatternProvider provider = providerFor(Set.of(), true);
        provider.buildPattern();
        assertThat(provider.asPattern()).isEmpty();
    }

    @Test
    void underscorelessMetaclassesYieldNoPattern() {
        HostPrefixPatternProvider provider = providerFor(
                Set.of("Customer", "Order", "Product"),
                true);
        provider.buildPattern();
        assertThat(provider.asPattern())
                .as("Names with no underscore are not host-prefixed")
                .isEmpty();
    }

    @Test
    void disabledByConfiguration() {
        HostPrefixPatternProvider provider = providerFor(
                Set.of("jmixapp_Customer"),
                /* enabled */ false);
        provider.buildPattern();
        assertThat(provider.asPattern())
                .as("Provider disabled => no pattern even with prefix-bearing classes")
                .isEmpty();
    }

    @Test
    void asPatternBuildsLazilyWhenAppReadyEventNotYetFired() {
        // Simulate the case where OutputScannerAdvisor calls asPattern() BEFORE the
        // ApplicationReadyEvent listener has run (eager singleton ordering edge case).
        HostPrefixPatternProvider provider = providerFor(
                Set.of("jmixapp_Customer"),
                true);
        // No buildPattern() call here — the lazy fallback in asPattern() must materialise it.
        assertThat(provider.asPattern()).isPresent();
    }

    @Test
    void compiledRegexFlagsHostPrefixedEntityNamesInAssistantText() {
        HostPrefixPatternProvider provider = providerFor(
                Set.of("jmixapp_Customer", "jmixapp_Order"),
                true);
        provider.buildPattern();
        Pattern compiled = Pattern.compile(provider.asPattern().orElseThrow().regex());

        assertThat(compiled.matcher("The jmixapp_Customer table has 42 rows.").find()).isTrue();
        assertThat(compiled.matcher("Looking at jmixapp_Order, shipping is empty.").find()).isTrue();
        // Benign controls
        assertThat(compiled.matcher("I will look for records that match your criteria.").find())
                .isFalse();
        assertThat(compiled.matcher("Customer satisfaction is high this quarter.").find()).isFalse();
        assertThat(compiled.matcher("The jmix application has many entities.").find())
                .as("'jmix' alone (no underscore-suffix) must not match 'jmixapp_<X>' regex")
                .isFalse();
    }

    @Test
    void regexUsesPatternQuoteSoIdentifierMetaCharsCannotProducePathologicalRegex() {
        // Theoretical: a host metaclass name containing regex meta-chars. Pattern.quote wraps
        // the raw token in \Q...\E so "." stays literal.
        HostPrefixPatternProvider provider = providerFor(Set.of("a.b_X"), true);
        provider.buildPattern();
        Optional<AiAgentGuardProperties.OutputScanner.Pattern> pattern = provider.asPattern();
        assertThat(pattern).isPresent();
        // \Qa.b\E is the literal-quoted form; the resulting compiled regex must reject
        // accidental "axb_X" matches (proving '.' was escaped, not treated as wildcard).
        Pattern compiled = Pattern.compile(pattern.get().regex());
        assertThat(compiled.matcher("a.b_X").find()).isTrue();
        assertThat(compiled.matcher("axb_X").find()).isFalse();
    }

    @Test
    void scannerWritesAuditKeyOnlyNeverMatchedSubstring() {
        HostPrefixPatternProvider provider = providerFor(Set.of("jmixapp_Customer"), true);
        ToolNamePatternProvider toolNameProvider = noOpToolNameProvider();
        provider.buildPattern();
        toolNameProvider.buildPattern();

        OutputScannerAdvisor advisor = new OutputScannerAdvisor(
                propsWithDefaultsAndPhase9Toggles(true, true),
                provider,
                toolNameProvider);

        Map<String, Object> contextMap = new HashMap<>();
        ChatClientResponse response = stubResponse(
                "I just queried jmixapp_Customer to get the count.", contextMap);
        ChatClientRequest req = mock(ChatClientRequest.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(req)).thenReturn(response);

        advisor.adviseCall(req, chain);

        Object flag = contextMap.get(OutputScannerAdvisor.CONTEXT_KEY_FLAGGED_PATTERN);
        assertThat(flag).isEqualTo("HOST_PREFIX_LEAK");
        // Audit-key only contract: the matched substring must not appear in context values
        // (T-09-20 mitigation: writeFlag persists the pattern KEY only, never the matched text).
        assertThat(contextMap.values())
                .as("Matched substring must NEVER be persisted in context map (T-09-20)")
                .allSatisfy(v -> assertThat(String.valueOf(v))
                        .doesNotContain("jmixapp_Customer"));
    }

    // ---- Fixtures -------------------------------------------------------------------------

    /** Build a provider against a mocked Jmix metamodel and the given enable flag. */
    private static HostPrefixPatternProvider providerFor(Set<String> metaclassNames, boolean enabled) {
        Metadata metadata = mock(Metadata.class);
        Session session = mock(Session.class);
        Set<MetaClass> classes = new LinkedHashSet<>();
        for (String n : metaclassNames) {
            MetaClass mc = mock(MetaClass.class);
            when(mc.getName()).thenReturn(n);
            classes.add(mc);
        }
        when(metadata.getSession()).thenReturn(session);
        when(session.getClasses()).thenReturn(classes);
        AiAgentGuardProperties props = enabled
                ? new AiAgentGuardProperties(null, null, null, null) // defaults => enabled
                : new AiAgentGuardProperties(null, null, null,
                        new AiAgentGuardProperties.OutputScanner(true, null,
                                new AiAgentGuardProperties.OutputScanner.HostPrefixLeak(false),
                                null));
        return new HostPrefixPatternProvider(metadata, props);
    }

    private static AiAgentGuardProperties propsWithDefaultsAndPhase9Toggles(boolean hostPrefixOn,
                                                                              boolean toolNameOn) {
        return new AiAgentGuardProperties(null, null, null,
                new AiAgentGuardProperties.OutputScanner(true, null,
                        new AiAgentGuardProperties.OutputScanner.HostPrefixLeak(hostPrefixOn),
                        new AiAgentGuardProperties.OutputScanner.ToolNameLeak(toolNameOn)));
    }

    private static ToolNamePatternProvider noOpToolNameProvider() {
        // Empty contributor list => only the seven D-07 baseline names. Provider is always
        // present, but we keep this helper here so we can compose it with the host-prefix
        // assertions independently.
        return new ToolNamePatternProvider(java.util.List.of(),
                new AiAgentGuardProperties(null, null, null, null));
    }

    /** Real AssistantMessage + real ChatResponse; ChatClientResponse mocked for context map. */
    private static ChatClientResponse stubResponse(String text, Map<String, Object> contextMap) {
        AssistantMessage msg = new AssistantMessage(text);
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse stubbed = mock(ChatClientResponse.class);
        when(stubbed.chatResponse()).thenReturn(chatResponse);
        when(stubbed.context()).thenReturn(contextMap);
        return stubbed;
    }
}
