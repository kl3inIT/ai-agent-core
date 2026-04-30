package com.vn.agent.guard;

import com.vn.agent.spi.ToolContributor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 9 PROMPT-06 / D-07: validates {@link ToolNamePatternProvider} compiles a regex over the
 * union of (a) built-in tool names, (b) the {@code RETRIEVAL} advisor name, and (c) every
 * {@code @Tool} method name reachable via {@link ToolContributor} beans, and that the
 * {@link OutputScannerAdvisor} propagates a {@code TOOL_NAME_LEAK} flag when assistant text
 * mentions any of those literal names.
 */
class ToolNameLeakScannerTest {

    @Test
    void compilesBaselineRegexFromBuiltInsAndRetrievalAdvisor() {
        ToolNamePatternProvider provider = new ToolNamePatternProvider(
                List.of(),
                propsDefaults());
        provider.buildPattern();

        Optional<AiAgentGuardProperties.OutputScanner.Pattern> pattern = provider.asPattern();
        assertThat(pattern).isPresent();
        assertThat(pattern.get().key()).isEqualTo("TOOL_NAME_LEAK");
        // Alpha-sorted (TreeSet); each token Pattern.quote-wrapped.
        assertThat(pattern.get().regex())
                .isEqualTo("\\b("
                        + "\\QRETRIEVAL\\E|"
                        + "\\Qadd_related_record\\E|"
                        + "\\Qcount_records\\E|"
                        + "\\Qcreate_record\\E|"
                        + "\\Qdescribe_entity\\E|"
                        + "\\Qfind_records\\E|"
                        + "\\Qgenerate_entity_detail_link\\E|"
                        + "\\Qgenerate_entity_list_link\\E|"
                        + "\\Qget_record\\E|"
                        + "\\Qget_related_records\\E|"
                        + "\\Qlist_entities\\E|"
                        + "\\Qremove_related_record\\E|"
                        + "\\Qupdate_record\\E)\\b");
    }

    @Test
    void includesContributorToolNames() {
        ToolContributor contributor = () -> List.of(new HostBusinessTools());
        ToolNamePatternProvider provider = new ToolNamePatternProvider(
                List.of(contributor),
                propsDefaults());
        provider.buildPattern();

        String regex = provider.asPattern().orElseThrow().regex();
        // Contributor's @Tool method name "acme_business_action" is alpha-inserted.
        assertThat(regex).contains("\\Qacme_business_action\\E");
        Pattern compiled = Pattern.compile(regex);
        assertThat(compiled.matcher("I will call acme_business_action next.").find()).isTrue();
    }

    @Test
    void disabledByConfiguration() {
        AiAgentGuardProperties props = new AiAgentGuardProperties(null, null, null,
                new AiAgentGuardProperties.OutputScanner(true, null, null,
                        new AiAgentGuardProperties.OutputScanner.ToolNameLeak(false)));
        ToolNamePatternProvider provider = new ToolNamePatternProvider(List.of(), props);
        provider.buildPattern();
        assertThat(provider.asPattern())
                .as("Disabled => no pattern even with built-ins available")
                .isEmpty();
    }

    @Test
    void asPatternBuildsLazilyWhenAppReadyEventNotYetFired() {
        ToolNamePatternProvider provider = new ToolNamePatternProvider(
                List.of(),
                propsDefaults());
        // No buildPattern() call here — lazy fallback in asPattern() must materialise it.
        assertThat(provider.asPattern()).isPresent();
    }

    @Test
    void compiledRegexFlagsLiteralToolNamesButNotNaturalLanguage() {
        ToolNamePatternProvider provider = new ToolNamePatternProvider(List.of(), propsDefaults());
        provider.buildPattern();
        Pattern compiled = Pattern.compile(provider.asPattern().orElseThrow().regex());

        // Literal tool names → match
        assertThat(compiled.matcher("I will call find_records with a filter.").find()).isTrue();
        assertThat(compiled.matcher("Then describe_entity tells me the schema.").find()).isTrue();
        assertThat(compiled.matcher("RETRIEVAL was used to find supporting documents.").find())
                .isTrue();

        // Natural-language phrases → must NOT match (literal-underscore form only)
        assertThat(compiled.matcher("I will look for records that match your criteria.").find())
                .isFalse();
        assertThat(compiled.matcher("Let me describe the customer entity in plain English.").find())
                .isFalse();
        assertThat(compiled.matcher("I retrieved supporting documents for this answer.").find())
                .as("'retrieved' (lowercase mixed-case) must not match 'RETRIEVAL' (uppercase)")
                .isFalse();
    }

    @Test
    void contributorBuildFailureDoesNotKillTheProvider() {
        ToolContributor failing = () -> {
            throw new RuntimeException("simulated bean lookup failure");
        };
        ToolNamePatternProvider provider = new ToolNamePatternProvider(
                List.of(failing),
                propsDefaults());
        provider.buildPattern();

        // Baseline tokens still present even though the contributor blew up.
        Optional<AiAgentGuardProperties.OutputScanner.Pattern> pattern = provider.asPattern();
        assertThat(pattern).isPresent();
        assertThat(pattern.get().regex()).contains("\\Qfind_records\\E");
    }

    @Test
    void scannerWritesAuditKeyOnlyNeverMatchedSubstring() {
        ToolNamePatternProvider toolNameProvider = new ToolNamePatternProvider(
                List.of(),
                propsDefaults());
        HostPrefixPatternProvider noOpHost = noOpHostProvider();
        toolNameProvider.buildPattern();
        noOpHost.buildPattern();

        OutputScannerAdvisor advisor = new OutputScannerAdvisor(
                propsDefaults(),
                noOpHost,
                toolNameProvider);

        Map<String, Object> contextMap = new HashMap<>();
        ChatClientResponse response = stubResponse(
                "I will call find_records with a filter to enumerate them.", contextMap);
        ChatClientRequest req = mock(ChatClientRequest.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(req)).thenReturn(response);

        advisor.adviseCall(req, chain);

        Object flag = contextMap.get(OutputScannerAdvisor.CONTEXT_KEY_FLAGGED_PATTERN);
        assertThat(flag).isEqualTo("TOOL_NAME_LEAK");
        // Audit-key only contract: matched substring must NEVER appear in context values.
        assertThat(contextMap.values())
                .as("Matched substring must NEVER be persisted in context map (T-09-20)")
                .allSatisfy(v -> assertThat(String.valueOf(v))
                        .doesNotContain("find_records"));
    }

    @Test
    void allSixBuiltInToolNamesPresentInRegex() {
        ToolNamePatternProvider provider = new ToolNamePatternProvider(List.of(), propsDefaults());
        provider.buildPattern();
        String regex = provider.asPattern().orElseThrow().regex();
        for (String name : new String[]{"list_entities", "describe_entity", "find_records",
                                         "count_records", "get_record", "get_related_records"}) {
            assertThat(regex)
                    .as("Built-in tool name %s must be in the union", name)
                    .contains("\\Q" + name + "\\E");
        }
    }

    // ---- Fixtures -------------------------------------------------------------------------

    private static AiAgentGuardProperties propsDefaults() {
        return new AiAgentGuardProperties(null, null, null, null);
    }

    private static HostPrefixPatternProvider noOpHostProvider() {
        // Empty metamodel => provider compiles to no host-prefix regex. Used to isolate the
        // tool-name pattern path in advisor-integration assertions.
        io.jmix.core.Metadata metadata = mock(io.jmix.core.Metadata.class);
        io.jmix.core.metamodel.model.Session session = mock(io.jmix.core.metamodel.model.Session.class);
        when(metadata.getSession()).thenReturn(session);
        when(session.getClasses()).thenReturn(java.util.Set.of());
        return new HostPrefixPatternProvider(metadata, propsDefaults());
    }

    private static ChatClientResponse stubResponse(String text, Map<String, Object> contextMap) {
        AssistantMessage msg = new AssistantMessage(text);
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse stubbed = mock(ChatClientResponse.class);
        when(stubbed.chatResponse()).thenReturn(chatResponse);
        when(stubbed.context()).thenReturn(contextMap);
        return stubbed;
    }

    /** Test-only @Tool-bearing bean used by the contributor test. */
    static class HostBusinessTools {
        @Tool(name = "acme_business_action",
                description = "Test-only contributor tool used by ToolNameLeakScannerTest.")
        public String execute() {
            return "ok";
        }
    }
}
