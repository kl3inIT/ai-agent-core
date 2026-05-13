package com.vn.agent.view.chat;

import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.dom.Element;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.guard.AiAgentGuardProperties;
import com.vn.agent.guard.HostPrefixPatternProvider;
import com.vn.agent.guard.ToolNamePatternProvider;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import com.vn.agent.view.chat.fragment.TurnDetailRenderer;
import io.jmix.core.Messages;
import io.jmix.core.Metadata;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.Session;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 15 TEST-19 (D-09) — the explicit UI-layer leak gate.
 *
 * <p>Reuses the Phase 9 leak-guard pattern packs <em>verbatim</em> — {@code TOOL_NAME_LEAK}
 * ({@link ToolNamePatternProvider}) and {@code HOST_PREFIX_LEAK} ({@link HostPrefixPatternProvider}),
 * constructed exactly as {@code ToolNameLeakScannerTest} / {@code HostPrefixLeakScannerTest} do —
 * and asserts that the in-fragment observability rendering path NEVER emits a {@code @Tool} method
 * name or a raw host-prefixed entity name.
 *
 * <p><strong>15-REVIEWS point #9</strong> — TEST-19 must scan a REAL rendered component, not just
 * the {@link TurnDetailRenderer} mapper output. This test does both:
 * <ul>
 *   <li>the cheap exhaustive mapper scan over every {@link StreamingEvent.ActivityKind} value + the
 *       neutral key + {TOOL, RETRIEVAL} step rows including an {@code ERROR}/rollback outcome + a
 *       null-latency row + the {@code MessageFormat}-formatted summary; and</li>
 *   <li>a RENDERED-component scan: it drives a real {@link ChatPanelFragment}'s {@code showStatus}
 *       (the actual {@code <span class="ai-agent-status">} text) and {@code appendTurnDetails} (an
 *       actual rendered {@code Details} step-row text inside the {@code .ai-agent-turn-activity}
 *       block), then runs the same Phase-9 regexes against the ACTUAL rendered DOM text. (Following
 *       the established fragment-test precedent — plain JUnit + Mockito against real Vaadin
 *       components, no {@code @UiTest}: the fragment package has no working
 *       {@code @SpringBootTest}/{@code @UiTest} boot harness — see 15-04-SUMMARY / STATE "Blockers"
 *       — so the rendered-component assertions exercise the real {@code Element}/{@code Details}
 *       DOM directly, which needs no live UI.)</li>
 * </ul>
 *
 * <p>The negative control deliberately routes a raw {@code find_records} tool name (resp. a host
 * metaclass name like {@code jmixapp_Customer}) through a status/step-row string and asserts the
 * matcher trips ({@code .find()} true) — proving the test would catch a regression.
 */
class ObservabilityLeakTest {

    private static final UUID RUN_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1");

    // Mirrors HostPrefixLeakScannerTest fixtures — a known host metamodel for the host-prefix
    // regex AND for the negative control's deliberately-routed entity name.
    private static final Set<String> HOST_METACLASS_NAMES =
            Set.of("jmixapp_Customer", "jmixapp_Order", "acme_Product");
    private static final String HOST_PREFIXED_ENTITY_NAME = "jmixapp_Customer";
    private static final String RAW_TOOL_NAME = "find_records";

    // ---- the Phase 9 pattern packs, constructed verbatim --------------------------------

    /** {@code TOOL_NAME_LEAK} — mirrors {@code ToolNameLeakScannerTest}. */
    private static Pattern toolNameRegex() {
        ToolNamePatternProvider provider = new ToolNamePatternProvider(
                List.of(), new AiAgentGuardProperties(null, null, null, null));
        provider.buildPattern();
        return Pattern.compile(provider.asPattern().orElseThrow().regex());
    }

    /** {@code HOST_PREFIX_LEAK} — mirrors {@code HostPrefixLeakScannerTest.providerFor(...)}. */
    private static Pattern hostPrefixRegex() {
        Metadata metadata = mock(Metadata.class);
        Session session = mock(Session.class);
        Set<MetaClass> classes = new LinkedHashSet<>();
        for (String n : HOST_METACLASS_NAMES) {
            MetaClass mc = mock(MetaClass.class);
            when(mc.getName()).thenReturn(n);
            classes.add(mc);
        }
        when(metadata.getSession()).thenReturn(session);
        when(session.getClasses()).thenReturn(classes);
        HostPrefixPatternProvider provider = new HostPrefixPatternProvider(
                metadata, new AiAgentGuardProperties(null, null, null, null));
        provider.buildPattern();
        return Pattern.compile(provider.asPattern().orElseThrow().regex());
    }

    private static void assertNoLeak(String text, Pattern tool, Pattern host) {
        assertThat(text).isNotNull();
        assertThat(tool.matcher(text).find())
                .as("rendered/mapped observability text must not contain a @Tool method name: <%s>", text)
                .isFalse();
        assertThat(host.matcher(text).find())
                .as("rendered/mapped observability text must not contain a host-prefixed entity name: <%s>", text)
                .isFalse();
    }

    // ---- (a) mapper-output scans -----------------------------------------------------------

    @Test
    void statusLineMapperNeverEmitsInternalNames() {
        Pattern tool = toolNameRegex();
        Pattern host = hostPrefixRegex();
        Messages messages = passthroughMessages();

        // Every ActivityKind value + the neutral key.
        for (StreamingEvent.ActivityKind kind : StreamingEvent.ActivityKind.values()) {
            assertNoLeak(messages.getMessage(TurnDetailRenderer.statusKeyFor(kind)), tool, host);
        }
        assertNoLeak(messages.getMessage(TurnDetailRenderer.statusKeyFor(null)), tool, host);
        assertNoLeak(messages.getMessage(TurnDetailRenderer.neutralStatusKey()), tool, host);
    }

    @Test
    void turnDetailMapperNeverEmitsInternalNames() {
        Pattern tool = toolNameRegex();
        Pattern host = hostPrefixRegex();
        Messages messages = passthroughMessages();

        // {TOOL, RETRIEVAL} step rows including an ERROR/rollback outcome and a null-latency row.
        List<TurnDetailRenderer.StepRow> rows = new ArrayList<>();
        rows.add(TurnDetailRenderer.stepRow(StreamingEvent.ActivityKind.TOOL, 42L, AiToolCallOutcome.SUCCESS));
        rows.add(TurnDetailRenderer.stepRow(StreamingEvent.ActivityKind.TOOL, 7L, AiToolCallOutcome.ERROR));
        rows.add(TurnDetailRenderer.stepRow(StreamingEvent.ActivityKind.RETRIEVAL, null, null));
        rows.add(TurnDetailRenderer.stepRow("TOOL", 1L, AiToolCallOutcome.COMMIT_FAILED));
        rows.add(TurnDetailRenderer.stepRow("RETRIEVAL", 980L, AiToolCallOutcome.SUCCESS));

        for (TurnDetailRenderer.StepRow row : rows) {
            assertNoLeak(messages.getMessage(row.labelKey()), tool, host);
            String msText = row.latencyMs() == null
                    ? messages.getMessage(TurnDetailRenderer.unknownDurationKey())
                    : row.latencyMs() + " ms";
            assertNoLeak(msText, tool, host);
            assertNoLeak(TurnDetailRenderer.UNKNOWN_DURATION_TEXT, tool, host);
        }
        assertNoLeak(messages.getMessage(TurnDetailRenderer.errorIndicatorKey()), tool, host);

        // The MessageFormat-formatted Details summary.
        String summary = MessageFormat.format(
                messages.getMessage(TurnDetailRenderer.summaryKey()),
                TurnDetailRenderer.summaryArgs(rows.size(), 1234L));
        assertNoLeak(summary, tool, host);
    }

    // ---- (b) RENDERED-component scan (review point #9) -------------------------------------

    @Test
    void renderedFragmentNeverEmitsInternalNames() throws Exception {
        Pattern tool = toolNameRegex();
        Pattern host = hostPrefixRegex();
        ChatPanelFragment fragment = newFragmentWithSlot();

        // Drive the fragment as a turn would: neutral -> Activity(TOOL) status, then a per-turn
        // Details with TOOL + RETRIEVAL + an errored step. We use the same helper methods the
        // streaming wiring calls (showStatus / appendTurnDetails), so the ACTUAL rendered DOM is
        // exercised — no @Tool name / entity name is ever passed into them by construction.
        invokeShowStatus(fragment, TurnDetailRenderer.neutralStatusKey());
        invokeShowStatus(fragment, TurnDetailRenderer.statusKeyFor(StreamingEvent.ActivityKind.TOOL));

        List<TurnDetailRenderer.StepRow> steps = List.of(
                new TurnDetailRenderer.StepRow(TurnDetailRenderer.STEP_TOOL_KEY, 42L, false),
                new TurnDetailRenderer.StepRow(TurnDetailRenderer.STEP_TOOL_KEY, 7L, true),
                new TurnDetailRenderer.StepRow(TurnDetailRenderer.STEP_RETRIEVAL_KEY, null, false));
        invokeAppendTurnDetails(fragment, RUN_ID, steps);

        // The ACTUAL rendered <span class="ai-agent-status"> text.
        Element statusSpan = (Element) get(fragment, "statusRow");
        assertThat(statusSpan).isNotNull();
        assertThat(statusSpan.getClassList()).contains("ai-agent-status");
        assertNoLeak(statusSpan.getText(), tool, host);

        // The ACTUAL rendered Details inside the .ai-agent-turn-extra wrapper (Phase 15-06 Gap 2).
        @SuppressWarnings("unchecked")
        java.util.Map<UUID, Div> wrappers = (java.util.Map<UUID, Div>) get(fragment, "turnDetailWrapperByRunId");
        Div wrapper = wrappers.get(RUN_ID);
        assertThat(wrapper).isNotNull();
        assertThat(wrapper.getClassName()).contains("ai-agent-turn-extra");
        Details details = wrapper.getChildren()
                .filter(c -> c instanceof Details).map(c -> (Details) c).findFirst().orElseThrow();
        assertThat(details.getClassNames()).contains("ai-agent-turn-activity");
        // Summary text.
        assertNoLeak(details.getSummaryText(), tool, host);
        // Every rendered step-row text inside the Details.
        VerticalLayout content = (VerticalLayout) details.getContent().toList().get(0);
        List<String> rowTexts = content.getChildren()
                .map(c -> {
                    StringBuilder sb = new StringBuilder();
                    c.getElement().getChildren().forEach(child -> sb.append(child.getText()).append(' '));
                    return sb.toString();
                }).toList();
        assertThat(rowTexts).isNotEmpty();
        for (String rowText : rowTexts) {
            assertNoLeak(rowText, tool, host);
        }
    }

    // ---- negative control -----------------------------------------------------------------

    @Test
    void negativeControl_routingAToolNameTrips() {
        Pattern tool = toolNameRegex();
        Pattern host = hostPrefixRegex();

        // A raw @Tool method name deliberately routed into a status-line-shaped string.
        String leakedToolName = "I will call " + RAW_TOOL_NAME + " next";
        assertThat(tool.matcher(leakedToolName).find())
                .as("TOOL_NAME_LEAK regex MUST match a deliberately-routed tool name "
                        + "(proves the leak test would catch a regression)")
                .isTrue();

        // A host-prefixed entity name deliberately routed into a step-row-shaped string.
        String leakedEntityName = "Searched " + HOST_PREFIXED_ENTITY_NAME + " — 42 ms";
        assertThat(host.matcher(leakedEntityName).find())
                .as("HOST_PREFIX_LEAK regex MUST match a deliberately-routed host-prefixed entity name "
                        + "(proves the leak test would catch a regression)")
                .isTrue();

        // Sanity: the benign rendered labels we assert on above do NOT trip either regex.
        Messages messages = passthroughMessages();
        assertThat(tool.matcher(messages.getMessage(TurnDetailRenderer.STEP_TOOL_KEY)).find()).isFalse();
        assertThat(host.matcher(messages.getMessage(TurnDetailRenderer.STEP_RETRIEVAL_KEY)).find()).isFalse();
    }

    // ---- harness (mirrors ChatPanelFragmentStatusLineTest / ...TurnDetailTest) ------------

    private static Messages passthroughMessages() {
        Messages messages = mock(Messages.class);
        when(messages.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return messages;
    }

    private static ChatPanelFragment newFragmentWithSlot() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        VerticalLayout slot = new VerticalLayout();
        MessageList messageList = new MessageList();
        slot.add(messageList);
        inject(fragment, "messageListSlot", slot);
        inject(fragment, "messageList", messageList);
        inject(fragment, "messageInput", new MessageInput());
        inject(fragment, "streamProgressBar", new ProgressBar());
        inject(fragment, "messages", passthroughMessages());
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        UserDetails userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn("alice");
        when(currentAuthentication.getUser()).thenReturn(userDetails);
        inject(fragment, "currentAuthentication", currentAuthentication);
        // Phase 15-06 Gap 2 — appendTurnDetails anchors the disclosure after the current turn's
        // transcript message; seed 2 items (USER + ASSISTANT) so the anchor exists.
        @SuppressWarnings("unchecked")
        java.util.List<com.vaadin.flow.component.messages.MessageListItem> items =
                (java.util.List<com.vaadin.flow.component.messages.MessageListItem>) get(fragment, "items");
        items.add(new com.vaadin.flow.component.messages.MessageListItem("u", java.time.Instant.now(), "u"));
        items.add(new com.vaadin.flow.component.messages.MessageListItem("a", java.time.Instant.now(), "a"));
        messageList.setItems(new java.util.ArrayList<>(items));
        return fragment;
    }

    private static void invokeShowStatus(ChatPanelFragment fragment, String key) throws Exception {
        Method m = ChatPanelFragment.class.getDeclaredMethod("showStatus", String.class);
        m.setAccessible(true);
        m.invoke(fragment, key);
    }

    private static void invokeAppendTurnDetails(ChatPanelFragment fragment, UUID runId,
                                                List<TurnDetailRenderer.StepRow> steps) throws Exception {
        Method m = ChatPanelFragment.class.getDeclaredMethod("appendTurnDetails", UUID.class, List.class);
        m.setAccessible(true);
        m.invoke(fragment, runId, new ArrayList<>(steps));
    }

    private static void inject(ChatPanelFragment fragment, String fieldName, Object value) throws Exception {
        Field field = ChatPanelFragment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(fragment, value);
    }

    private static Object get(ChatPanelFragment fragment, String fieldName) throws Exception {
        Field field = ChatPanelFragment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(fragment);
    }
}
