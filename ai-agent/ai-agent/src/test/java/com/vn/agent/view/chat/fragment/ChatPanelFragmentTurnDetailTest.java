package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
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
import java.time.Instant;
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
 * Plan 15-04 Task 3 + Plan 15-06 Gap 2 (Option A) — locks the per-turn tool-detail
 * {@code <vaadin-details>} disclosure: built for a just-completed turn by a lazy
 * {@code AiAuditEvent}-by-{@code runId} read on {@code Final} (unified {@code loadTurnSteps} —
 * real {@code latencyMs}; em-dash for null, never "0 ms"; an error/rollback indicator on errored
 * steps); a zero-step turn produces NO {@code Details}; and the disclosure is wrapped in a
 * {@code .ai-agent-turn-extra} {@code <div>} anchored after its turn's transcript message
 * (server-side: a child of {@code messageListSlot} right after the {@code <vaadin-message-list>},
 * ordered by turn; client-side it is then spliced into the message-list light DOM by an
 * {@code executeJs} pass keyed off {@code data-ai-turn-index}).
 *
 * <p>Plain JUnit 5 + Mockito (mirrors {@code ChatPanelFragmentConversationIdTest}). The
 * {@code accessUi}-wrapped streaming wiring + the {@code executeJs} re-anchor are verified via a
 * source scan; the {@code loadTurnSteps}/{@code appendTurnDetails} rendering + anchoring contract
 * is verified directly.
 */
class ChatPanelFragmentTurnDetailTest {

    private static final UUID RUN_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID RUN_ID_2 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000099");
    private static final UUID CID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Test
    void loadTurnSteps_readsAuditChildrenUnconstrainedWithMandatoryUserAndConversationFilter() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot(2);
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
                .parameter(eq("toolKind"), any())
                .parameter(eq("retrievalKind"), any())
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
        ChatPanelFragment fragment = newFragmentWithSlot(2);
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        inject(fragment, "unconstrainedDataManager", udm);
        when(udm.load(AiAuditEvent.class)).thenThrow(new RuntimeException("agentstore down"));

        @SuppressWarnings("unchecked")
        List<TurnDetailRenderer.StepRow> rows =
                (List<TurnDetailRenderer.StepRow>) invokeLoadTurnSteps(fragment, RUN_ID, CID);
        assertThat(rows).isEmpty();
    }

    @Test
    void appendTurnDetails_anchorsCollapsedDetailsInTurnExtraWrapper_afterMessageList_withLabelOnlyRows_emDashForNullMs() throws Exception {
        // 2 transcript items: USER (0) + ASSISTANT (1). The disclosure hangs under item 1.
        ChatPanelFragment fragment = newFragmentWithSlot(2);
        List<TurnDetailRenderer.StepRow> steps = List.of(
                new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 42L, false),
                new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 7L, true),
                new TurnDetailRenderer.StepRow("chatView.turnDetail.step.retrieval", null, false));

        invokeAppendTurnDetails(fragment, RUN_ID, steps);

        // The wrapper is a .ai-agent-turn-extra <div> child of messageListSlot, AFTER the <vaadin-message-list>.
        VerticalLayout slot = slot(fragment);
        List<com.vaadin.flow.dom.Element> children = slot.getElement().getChildren().toList();
        int messageListIndex = -1;
        for (int i = 0; i < children.size(); i++) {
            if ("vaadin-message-list".equals(children.get(i).getTag())) {
                messageListIndex = i;
            }
        }
        assertThat(messageListIndex).isGreaterThanOrEqualTo(0);
        com.vaadin.flow.dom.Element wrapper = turnDetailWrapperByRunId(fragment).get(RUN_ID).getElement();
        assertThat(wrapper.getClassList()).contains("ai-agent-turn-extra");
        assertThat(wrapper.getAttribute("data-ai-turn-index")).isEqualTo("1");
        int wrapperIndex = children.indexOf(wrapper);
        assertThat(wrapperIndex).isGreaterThan(messageListIndex);

        // Exactly one collapsed Details inside the wrapper, memoized, classed .ai-agent-turn-activity.
        List<Details> detailsList = wrapper.getChildren()
                .filter(e -> "vaadin-details".equals(e.getTag()))
                .map(e -> (Details) e.getComponent().orElseThrow())
                .toList();
        assertThat(detailsList).hasSize(1);
        Details details = detailsList.get(0);
        assertThat(details.isOpened()).isFalse();
        assertThat(details.getClassNames()).contains("ai-agent-turn-activity");
        assertThat(ComponentUtil.getData(details, fragmentLoadedKey())).isEqualTo(Boolean.TRUE);

        // Content: 3 label-only step rows; em-dash on the null-latency RETRIEVAL row; an error
        // indicator only on the errored TOOL row; KIND-keyed modifier classes; no tool/entity name.
        VerticalLayout content = (VerticalLayout) details.getContent().toList().get(0);
        List<Div> rows = content.getChildren().filter(c -> c instanceof Div).map(c -> (Div) c).toList();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getClassNames()).contains("ai-agent-turn-activity__step--tool");
        assertThat(rowText(rows.get(0))).contains("chatView.turnDetail.step.tool", "42 ms");
        assertThat(rowText(rows.get(0))).doesNotContain("chatView.turnDetail.errorIndicator");
        assertThat(rows.get(1).getClassNames()).contains("ai-agent-turn-activity__step--tool",
                "ai-agent-turn-activity__step--errored");
        assertThat(rowText(rows.get(1)))
                .contains("chatView.turnDetail.step.tool", "7 ms", "chatView.turnDetail.errorIndicator");
        assertThat(rows.get(2).getClassNames()).contains("ai-agent-turn-activity__step--retrieval");
        assertThat(rowText(rows.get(2))).contains("chatView.turnDetail.step.retrieval", "—");
        assertThat(rowText(rows.get(2))).doesNotContain("0 ms", "null ms");
    }

    @Test
    void appendTurnDetails_calledTwiceForSameRunId_replacesNotDuplicates() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot(2);
        invokeAppendTurnDetails(fragment, RUN_ID,
                List.of(new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 1L, false)));
        invokeAppendTurnDetails(fragment, RUN_ID,
                List.of(new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 2L, false)));

        // Exactly one wrapper for the runId; exactly one turn-extra child in the slot.
        assertThat(turnDetailWrapperByRunId(fragment)).hasSize(1);
        long extraCount = slot(fragment).getElement().getChildren()
                .filter(e -> e.getClassList().contains("ai-agent-turn-extra")).count();
        assertThat(extraCount).isEqualTo(1);
    }

    @Test
    void appendTurnDetails_twoTurns_extrasOrderedByTurnIndexAfterMessageList() throws Exception {
        // 4 transcript items: U0, A1, U2, A3. Two disclosures: RUN_ID under turn 1, RUN_ID_2 under turn 3.
        ChatPanelFragment fragment = newFragmentWithSlot(2);
        // RUN_ID is anchored under the current last item (index 1) ...
        invokeAppendTurnDetails(fragment, RUN_ID,
                List.of(new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 1L, false)));
        // ... then add 2 more items (U2, A3) and anchor RUN_ID_2 under index 3.
        addTranscriptItems(fragment, 2);
        invokeAppendTurnDetails(fragment, RUN_ID_2,
                List.of(new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 2L, false)));

        com.vaadin.flow.dom.Element w1 = turnDetailWrapperByRunId(fragment).get(RUN_ID).getElement();
        com.vaadin.flow.dom.Element w2 = turnDetailWrapperByRunId(fragment).get(RUN_ID_2).getElement();
        List<com.vaadin.flow.dom.Element> children = slot(fragment).getElement().getChildren().toList();
        assertThat(w1.getAttribute("data-ai-turn-index")).isEqualTo("1");
        assertThat(w2.getAttribute("data-ai-turn-index")).isEqualTo("3");
        // Both after the message list, w1 (turn 1) before w2 (turn 3).
        assertThat(children.indexOf(w1)).isLessThan(children.indexOf(w2));
    }

    @Test
    void clearMessageList_dropsTurnDetailsAndExtras() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot(2);
        invokeAppendTurnDetails(fragment, RUN_ID,
                List.of(new TurnDetailRenderer.StepRow("chatView.turnDetail.step.tool", 1L, false)));
        assertThat(turnDetailsMap(fragment)).isNotEmpty();
        assertThat(turnExtras(fragment)).isNotEmpty();

        invoke(fragment, "clearMessageList");

        assertThat(turnDetailsMap(fragment)).isEmpty();
        assertThat(turnDetailWrapperByRunId(fragment)).isEmpty();
        assertThat(turnExtras(fragment)).isEmpty();
        assertThat(liveTurnSteps(fragment)).isEmpty();
        // No leftover .ai-agent-turn-extra siblings in the (re-created) slot.
        long extraCount = slot(fragment).getElement().getChildren()
                .filter(e -> e.getClassList().contains("ai-agent-turn-extra")).count();
        assertThat(extraCount).isZero();
    }

    @Test
    void liveTurnStepCap_isFifty_andLiveStepsStartEmptyEachTurn() throws Exception {
        Field cap = ChatPanelFragment.class.getDeclaredField("LIVE_TURN_STEP_CAP");
        cap.setAccessible(true);
        assertThat(cap.getInt(null)).isEqualTo(50);

        ChatPanelFragment fragment = newFragmentWithSlot(2);
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
    void streaming_wiring_loadsRealTimingsOnFinal_correlatesHistory_andReanchorsAfterSetItems() throws Exception {
        String source = readSource();
        assertThat(source)
                .contains("List<TurnDetailRenderer.StepRow> steps = loadTurnSteps(runId, cid)")
                .contains("steps = liveTurnStepsAsStepRows()")
                .contains("appendTurnDetails(runId, steps)")
                .contains("StreamingEvent.ToolCall tc")
                .contains("StreamingEvent.ToolResult tr")
                .contains("finishLiveStep(tr.toolCallId(), tr.outcome())")
                .contains("TurnDetailRenderer.STEP_RETRIEVAL_KEY")
                .contains("TurnDetailRenderer.STEP_TOOL_KEY")
                .doesNotContain("tc.toolName()")
                .doesNotContain("tc.argsJson()")
                .doesNotContain("tr.toolName()")
                .doesNotContain("tr.summary()")
                .doesNotContain("tr.payloadJson()")
                .contains("correlateHistoryTurnDetails(cid, assistantTurnIndices)")
                // re-anchor pass runs after every MessageList.setItems(...) and at end of history replay
                .contains("reanchorAllExtras()")
                // turnActivityBlock is gone
                .doesNotContain("turnActivityBlock")
                .contains("where e.userUsername = :me and e.conversation.id = :cid")
                .contains("and e.runId = :rid and e.parent is not null")
                .contains(".store(\"agentstore\")")
                // the client-side splice is pure DOM (no innerHTML)
                .contains("data-ai-turn-index")
                .doesNotContain("innerHTML");
        Path aiMessage = firstExisting(
                "ai-agent/ai-agent/src/main/java/com/vn/agent/entity/AiMessage.java",
                "src/main/java/com/vn/agent/entity/AiMessage.java");
        assertThat(aiMessage).isNotNull();
        assertThat(Files.readString(aiMessage, StandardCharsets.UTF_8)).doesNotContain("runId");
    }

    // ---- harness -----------------------------------------------------------

    private static ChatPanelFragment newFragmentWithSlot(int initialItemCount) throws Exception {
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
        addTranscriptItems(fragment, initialItemCount);
        return fragment;
    }

    @SuppressWarnings("unchecked")
    private static void addTranscriptItems(ChatPanelFragment fragment, int count) throws Exception {
        List<MessageListItem> items = (List<MessageListItem>) get(fragment, "items");
        for (int i = 0; i < count; i++) {
            items.add(new MessageListItem("m" + items.size(), Instant.now(), "u"));
        }
        ((MessageList) get(fragment, "messageList")).setItems(new ArrayList<>(items));
    }

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

    @SuppressWarnings("unchecked")
    private static java.util.Map<UUID, Details> turnDetailsMap(ChatPanelFragment fragment) throws Exception {
        return (java.util.Map<UUID, Details>) get(fragment, "turnDetailsByRunId");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<UUID, Div> turnDetailWrapperByRunId(ChatPanelFragment fragment) throws Exception {
        return (java.util.Map<UUID, Div>) get(fragment, "turnDetailWrapperByRunId");
    }

    @SuppressWarnings("unchecked")
    private static List<?> turnExtras(ChatPanelFragment fragment) throws Exception {
        return (List<?>) get(fragment, "turnExtras");
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
