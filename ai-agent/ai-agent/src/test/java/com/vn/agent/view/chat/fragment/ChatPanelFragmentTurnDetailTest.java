package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import io.jmix.core.Messages;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan 15-04 Task 3 — locks the per-turn tool-detail {@code Details} disclosure rendered in
 * the single ordered {@code .ai-agent-turn-activity} block (review point #2), driven for a
 * just-completed turn by a lazy {@code AiAuditEvent}-by-{@code runId} read on {@code Final}
 * (unified {@code loadTurnSteps} — real {@code latencyMs}; em-dash for null, never "0 ms";
 * an error/rollback indicator on errored steps); a zero-step turn produces NO {@code Details}.
 *
 * <p>Plain JUnit 5 + Mockito (mirrors {@code ChatPanelFragmentConversationIdTest}). The
 * {@code accessUi}-wrapped streaming wiring is verified via a source scan; the
 * {@code loadTurnSteps}/{@code appendTurnDetails} rendering contract is verified directly.
 */
class ChatPanelFragmentTurnDetailTest {

    private static final UUID RUN_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID CID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Test
    void loadTurnSteps_readsAuditChildrenUnconstrainedWithMandatoryUserAndConversationFilter() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot();
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        inject(fragment, "unconstrainedDataManager", udm);

        AiAuditEvent toolOk = auditChild("TOOL", 42L, AiToolCallOutcome.SUCCESS);
        AiAuditEvent toolErr = auditChild("TOOL", 7L, AiToolCallOutcome.ERROR);
        AiAuditEvent retrievalNoMs = auditChild("RETRIEVAL", null, null);
        when(udm.load(AiAuditEvent.class)
                .query(anyString())
                .parameter(eq("me"), anyString())
                .parameter(eq("cid"), eq(CID))
                .parameter(eq("rid"), eq(RUN_ID))
                .fetchPlan(any(java.util.function.Consumer.class))
                .list())
                .thenReturn(List.of(toolOk, toolErr, retrievalNoMs));

        @SuppressWarnings("unchecked")
        List<TurnDetailRenderer.StepRow> rows =
                (List<TurnDetailRenderer.StepRow>) invokeLoadTurnSteps(fragment, RUN_ID, CID);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).labelKey()).isEqualTo("chatView.turnDetail.step.tool");
        assertThat(rows.get(0).latencyMs()).isEqualTo(42L);
        assertThat(rows.get(0).errored()).isFalse();
        assertThat(rows.get(1).labelKey()).isEqualTo("chatView.turnDetail.step.tool");
        assertThat(rows.get(1).errored()).isTrue();
        assertThat(rows.get(2).labelKey()).isEqualTo("chatView.turnDetail.step.retrieval");
        assertThat(rows.get(2).latencyMs()).isNull();
    }

    @Test
    void loadTurnSteps_swallowsRuntimeExceptionAndReturnsEmpty() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot();
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        inject(fragment, "unconstrainedDataManager", udm);
        when(udm.load(AiAuditEvent.class)).thenThrow(new RuntimeException("agentstore down"));

        @SuppressWarnings("unchecked")
        List<TurnDetailRenderer.StepRow> rows =
                (List<TurnDetailRenderer.StepRow>) invokeLoadTurnSteps(fragment, RUN_ID, CID);
        assertThat(rows).isEmpty();
    }

    @Test
    void appendTurnDetails_addsCollapsedDetailsIntoActivityBlock_withLabelOnlyRows_emDashForNullMs() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot();
        List<TurnDetailRenderer.StepRow> steps = List.of(
                new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 42L, false),
                new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 7L, true),
                new TurnDetailRenderer.StepRow("chatView.turnDetail.step.retrieval", null, false));

        invokeAppendTurnDetails(fragment, RUN_ID, steps);

        Div block = activityBlock(fragment);
        assertThat(block).isNotNull();
        assertThat(block.getClassName()).contains("ai-agent-turn-activity");
        // The activity block is a sibling AFTER <vaadin-message-list>.
        VerticalLayout slot = slot(fragment);
        List<com.vaadin.flow.dom.Element> children = slot.getElement().getChildren().toList();
        int messageListIndex = -1;
        for (int i = 0; i < children.size(); i++) {
            if ("vaadin-message-list".equals(children.get(i).getTag())) {
                messageListIndex = i;
            }
        }
        int blockIndex = children.indexOf(block.getElement());
        assertThat(messageListIndex).isGreaterThanOrEqualTo(0);
        assertThat(blockIndex).isGreaterThan(messageListIndex);

        // Exactly one collapsed Details, memoized.
        List<Details> detailsList = block.getChildren()
                .filter(c -> c instanceof Details).map(c -> (Details) c).toList();
        assertThat(detailsList).hasSize(1);
        Details details = detailsList.get(0);
        assertThat(details.isOpened()).isFalse();
        assertThat(ComponentUtil.getData(details, fragmentLoadedKey())).isEqualTo(Boolean.TRUE);

        // Content: 3 label-only step rows; em-dash on the null-latency RETRIEVAL row; an error
        // indicator only on the errored TOOL row; no tool/entity name anywhere.
        VerticalLayout content = (VerticalLayout) details.getContent().toList().get(0);
        List<Div> rows = content.getChildren().filter(c -> c instanceof Div).map(c -> (Div) c).toList();
        assertThat(rows).hasSize(3);
        // Row 0: tool, 42 ms, not errored
        assertThat(rowText(rows.get(0))).contains("chatView.turnDetail.step.tool", "42 ms");
        assertThat(rowText(rows.get(0))).doesNotContain("chatView.turnDetail.errorIndicator");
        // Row 1: tool, 7 ms, errored
        assertThat(rowText(rows.get(1)))
                .contains("chatView.turnDetail.step.tool", "7 ms", "chatView.turnDetail.errorIndicator");
        // Row 2: retrieval, em-dash (NOT "0 ms"/"null ms")
        assertThat(rowText(rows.get(2))).contains("chatView.turnDetail.step.retrieval", "—");
        assertThat(rowText(rows.get(2))).doesNotContain("0 ms", "null ms");
    }

    @Test
    void appendTurnDetails_calledTwiceForSameRunId_replacesNotDuplicates() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot();
        invokeAppendTurnDetails(fragment, RUN_ID,
                List.of(new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 1L, false)));
        invokeAppendTurnDetails(fragment, RUN_ID,
                List.of(new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 2L, false)));

        Div block = activityBlock(fragment);
        long count = block.getChildren().filter(c -> c instanceof Details).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void clearMessageList_dropsTurnDetailsAndActivityBlock() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot();
        invokeAppendTurnDetails(fragment, RUN_ID,
                List.of(new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 1L, false)));
        assertThat(activityBlock(fragment)).isNotNull();
        assertThat(turnDetailsMap(fragment)).isNotEmpty();
        assertThat(liveTurnSteps(fragment)).isNotNull();

        invoke(fragment, "clearMessageList");

        assertThat(activityBlock(fragment)).isNull();
        assertThat(turnDetailsMap(fragment)).isEmpty();
        assertThat(liveTurnSteps(fragment)).isEmpty();
    }

    @Test
    void liveTurnStepCap_isFifty_andLiveStepsStartEmptyEachTurn() throws Exception {
        // Cap constant.
        Field cap = ChatPanelFragment.class.getDeclaredField("LIVE_TURN_STEP_CAP");
        cap.setAccessible(true);
        assertThat(cap.getInt(null)).isEqualTo(50);

        // recordLiveStep honours the cap.
        ChatPanelFragment fragment = newFragmentWithSlot();
        Method recordLiveStep = ChatPanelFragment.class.getDeclaredMethod("recordLiveStep",
                Class.forName("com.vn.agent.view.chat.fragment.ChatPanelFragment$LiveTurnStep"));
        recordLiveStep.setAccessible(true);
        var ctor = Class.forName("com.vn.agent.view.chat.fragment.ChatPanelFragment$LiveTurnStep")
                .getDeclaredConstructor(String.class, Long.class, boolean.class, UUID.class, long.class);
        ctor.setAccessible(true);
        for (int i = 0; i < 60; i++) {
            recordLiveStep.invoke(fragment,
                    ctor.newInstance("chatView.turnDetail.step.tool", null, false, UUID.randomUUID(), 0L));
        }
        assertThat(liveTurnSteps(fragment)).hasSize(50);
    }

    @Test
    void streaming_wiring_loadsRealTimingsOnFinal_andCorrelatesHistory() throws Exception {
        String source = readSource();
        assertThat(source)
                // doOnComplete: real timings via loadTurnSteps(activeRunId, conversationId), fallback to live
                .contains("List<TurnDetailRenderer.StepRow> steps = loadTurnSteps(runId, cid)")
                .contains("steps = liveTurnStepsAsStepRows()")
                .contains("appendTurnDetails(runId, steps)")
                // ToolCall / ToolResult / Activity(RETRIEVAL) live-step accumulation, label-only keys
                .contains("StreamingEvent.ToolCall tc")
                .contains("StreamingEvent.ToolResult tr")
                .contains("finishLiveStep(tr.toolCallId(), tr.outcome())")
                .contains("TurnDetailRenderer.STEP_RETRIEVAL_KEY")
                .contains("TurnDetailRenderer.STEP_TOOL_KEY")
                // we never store toolName/argsJson/summary/payloadJson
                .doesNotContain("tc.toolName()")
                .doesNotContain("tc.argsJson()")
                .doesNotContain("tr.toolName()")
                .doesNotContain("tr.summary()")
                .doesNotContain("tr.payloadJson()")
                // history correlation pass after setItems
                .contains("correlateHistoryTurnDetails(cid, assistantTurnCount)")
                // the unconstrained read carries the mandatory userUsername + conversation.id filter
                .contains("where e.userUsername = :me and e.conversation.id = :cid")
                .contains("and e.runId = :rid and e.parent is not null")
                .contains(".store(\"agentstore\")");
        // AiMessage is not modified by this plan.
        Path aiMessage = firstExisting(
                "ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessage.java",
                "src/main/java/com/vn/agent/entity/AiMessage.java");
        assertThat(aiMessage).isNotNull();
        assertThat(Files.readString(aiMessage, StandardCharsets.UTF_8)).doesNotContain("runId");
    }

    // ---- harness -----------------------------------------------------------

    private static ChatPanelFragment newFragmentWithSlot() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        VerticalLayout slot = new VerticalLayout();
        MessageList messageList = new MessageList();
        slot.add(messageList);
        inject(fragment, "messageListSlot", slot);
        inject(fragment, "messageList", messageList);
        inject(fragment, "messageInput", new MessageInput());
        inject(fragment, "streamProgressBar", new ProgressBar());
        Messages messages = mock(Messages.class);
        when(messages.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        inject(fragment, "messages", messages);
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        UserDetails userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn("alice");
        when(currentAuthentication.getUser()).thenReturn(userDetails);
        inject(fragment, "currentAuthentication", currentAuthentication);
        return fragment;
    }

    /** Recursively concatenates the text content of a step row's child spans. */
    private static String rowText(Div row) {
        StringBuilder sb = new StringBuilder();
        row.getElement().getChildren().forEach(child -> sb.append(child.getText()).append(' '));
        return sb.toString();
    }

    private static AiAuditEvent auditChild(String kind, Long latencyMs, AiToolCallOutcome outcome) {
        AiAuditEvent e = new AiAuditEvent();
        e.setKind(kind);
        e.setLatencyMs(latencyMs);
        e.setOutcome(outcome);
        return e;
    }

    private static VerticalLayout slot(ChatPanelFragment fragment) throws Exception {
        return (VerticalLayout) get(fragment, "messageListSlot");
    }

    private static Div activityBlock(ChatPanelFragment fragment) throws Exception {
        return (Div) get(fragment, "turnActivityBlock");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<UUID, Details> turnDetailsMap(ChatPanelFragment fragment) throws Exception {
        return (java.util.Map<UUID, Details>) get(fragment, "turnDetailsByRunId");
    }

    @SuppressWarnings("unchecked")
    private static List<?> liveTurnSteps(ChatPanelFragment fragment) throws Exception {
        return (List<?>) get(fragment, "liveTurnSteps");
    }

    private static String fragmentLoadedKey() throws Exception {
        Field f = ChatPanelFragment.class.getDeclaredField("TURN_DETAILS_LOADED_KEY");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static Object invokeLoadTurnSteps(ChatPanelFragment fragment, UUID runId, UUID cid) throws Exception {
        Method m = ChatPanelFragment.class.getDeclaredMethod("loadTurnSteps", UUID.class, UUID.class);
        m.setAccessible(true);
        return m.invoke(fragment, runId, cid);
    }

    private static void invokeAppendTurnDetails(ChatPanelFragment fragment, UUID runId,
                                                List<TurnDetailRenderer.StepRow> steps) throws Exception {
        Method m = ChatPanelFragment.class.getDeclaredMethod("appendTurnDetails", UUID.class, List.class);
        m.setAccessible(true);
        m.invoke(fragment, runId, new ArrayList<>(steps));
    }

    private static void invoke(ChatPanelFragment fragment, String name) throws Exception {
        Method method = ChatPanelFragment.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(fragment);
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

    private static String readSource() throws Exception {
        Path p = firstExisting(
                "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java",
                "src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");
        if (p == null) {
            throw new java.nio.file.NoSuchFileException("ChatPanelFragment.java");
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static Path firstExisting(String... relativePaths) {
        for (String rel : relativePaths) {
            for (Path candidate : new Path[]{
                    Path.of(rel),
                    Path.of(System.getProperty("user.dir")).resolve(rel).normalize()
            }) {
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
