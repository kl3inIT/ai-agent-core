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
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan 15-04 Task 3 + Plan 15-06 Gap 2 (Option A) — locks the post-navigation
 * {@code AiAuditEvent} correlation pass: after the history-replay loop calls
 * {@code messageList.setItems(...)}, the fragment loads the conversation's CHAT-root
 * {@code runId}s + child counts and zips them 1:1 against the replayed ASSISTANT turns ONLY
 * when the counts match — anchoring a collapsed {@code <vaadin-details>} (wrapped in a
 * {@code .ai-agent-turn-extra} {@code <div>} after that turn's transcript message) only for
 * roots with &ge;1 child, lazily + memoizedly re-reading that root's children on first expand;
 * on a count mismatch it renders NO disclosures (no guess).
 *
 * <p>Plain JUnit 5 + Mockito (mirrors {@code ChatPanelFragmentConversationIdTest}); the
 * {@code correlateHistoryTurnDetails} / {@code appendHistoryTurnDetails} contract is invoked
 * directly (the {@code setConversationIdInternal} path needs full Jmix wiring; that the replay
 * loop calls the re-anchor pass after {@code setItems} is asserted via a source scan).
 */
class ChatPanelFragmentTurnDetailHistoryTest {

    private static final UUID CID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID RUN_A = UUID.fromString("11111111-0000-0000-0000-00000000000a");
    private static final UUID RUN_B = UUID.fromString("22222222-0000-0000-0000-00000000000b");
    private static final UUID RUN_C = UUID.fromString("33333333-0000-0000-0000-00000000000c");

    // 3 ASSISTANT turns at transcript indices 1, 3, 5 (a U/A pair each); 6 items total.
    private static final List<Integer> THREE_ASSISTANT_INDICES = List.of(1, 3, 5);

    @Test
    void correlateHistory_matchingCounts_anchorsExtrasOnlyForRootsWithChildren_noPlaceholderForZeroChild() throws Exception {
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        stubRootRunIds(udm, List.of(RUN_A, RUN_B, RUN_C));
        stubChildCounts(udm, List.of(kv(RUN_A, 2L), kv(RUN_C, 1L))); // B absent => 0
        ChatPanelFragment fragment = newFragment(udm, 6);

        invokeCorrelateHistory(fragment, CID, THREE_ASSISTANT_INDICES);

        // A and C get a disclosure; B (zero-child) does not.
        assertThat(turnDetailsMap(fragment).keySet()).containsExactlyInAnyOrder(RUN_A, RUN_C);
        assertThat(turnDetailWrapperByRunId(fragment).keySet()).containsExactlyInAnyOrder(RUN_A, RUN_C);
        // Wrappers anchored at the assistant turn's index (RUN_A→1, RUN_C→5), after the message list.
        Div wA = turnDetailWrapperByRunId(fragment).get(RUN_A);
        Div wC = turnDetailWrapperByRunId(fragment).get(RUN_C);
        assertThat(wA.getElement().getAttribute("data-ai-turn-index")).isEqualTo("1");
        assertThat(wC.getElement().getAttribute("data-ai-turn-index")).isEqualTo("5");
        List<com.vaadin.flow.dom.Element> children = slot(fragment).getElement().getChildren().toList();
        int mlIdx = -1;
        for (int i = 0; i < children.size(); i++) {
            if ("vaadin-message-list".equals(children.get(i).getTag())) {
                mlIdx = i;
            }
        }
        assertThat(children.indexOf(wA.getElement())).isGreaterThan(mlIdx);
        assertThat(children.indexOf(wC.getElement())).isGreaterThan(mlIdx);
        assertThat(children.indexOf(wA.getElement())).isLessThan(children.indexOf(wC.getElement()));
        // collapsed disclosures
        for (Details d : List.of(detailsFor(fragment, RUN_A), detailsFor(fragment, RUN_C))) {
            assertThat(d.isOpened()).isFalse();
            assertThat(d.getClassNames()).contains("ai-agent-turn-activity");
        }
    }

    @Test
    void expandingHistoryDetails_lazilyLoadsStepsOnce_thenMemoizes() throws Exception {
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        stubRootRunIds(udm, List.of(RUN_A));
        stubChildCounts(udm, List.of(kv(RUN_A, 2L)));
        AtomicInteger queryCount = new AtomicInteger();
        when(udm.load(AiAuditEvent.class)
                .query(anyString())
                .parameter(eq("me"), anyString())
                .parameter(eq("cid"), eq(CID))
                .parameter(eq("rid"), eq(RUN_A))
                .parameter(eq("toolKind"), any())
                .parameter(eq("retrievalKind"), any())
                .fetchPlan(any(java.util.function.Consumer.class))
                .list())
                .thenAnswer(inv -> {
                    queryCount.incrementAndGet();
                    return List.of(
                            auditChild("TOOL", 13L, AiToolCallOutcome.SUCCESS),
                            auditChild("RETRIEVAL", null, null));
                });
        ChatPanelFragment fragment = newFragment(udm, 2);
        invokeCorrelateHistory(fragment, CID, List.of(1));

        Details details = detailsFor(fragment, RUN_A);
        assertThat(details).isNotNull();
        assertThat(queryCount.get()).isZero(); // not yet read — collapsed

        details.setOpened(true);
        assertThat(queryCount.get()).isEqualTo(1);
        assertThat(ComponentUtil.getData(details, loadedKey())).isEqualTo(Boolean.TRUE);
        VerticalLayout content = (VerticalLayout) details.getContent().toList().get(0);
        List<Div> rows = content.getChildren().filter(c -> c instanceof Div).map(c -> (Div) c).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getClassNames()).contains("ai-agent-turn-activity__step--tool");
        assertThat(rowText(rows.get(0))).contains("chatView.turnDetail.step.tool", "13 ms");
        assertThat(rows.get(1).getClassNames()).contains("ai-agent-turn-activity__step--retrieval");
        assertThat(rowText(rows.get(1))).contains("chatView.turnDetail.step.retrieval", "—");
        assertThat(rowText(rows.get(1))).doesNotContain("0 ms", "null ms");

        details.setOpened(false);
        details.setOpened(true);
        assertThat(queryCount.get()).isEqualTo(1);
    }

    @Test
    void correlateHistory_countMismatch_rendersNoExtras_noException() throws Exception {
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        stubRootRunIds(udm, List.of(RUN_A, RUN_B)); // only 2 roots, but 3 assistant turns
        stubChildCounts(udm, List.of(kv(RUN_A, 2L), kv(RUN_B, 1L)));
        ChatPanelFragment fragment = newFragment(udm, 6);

        invokeCorrelateHistory(fragment, CID, THREE_ASSISTANT_INDICES);

        assertThat(turnDetailsMap(fragment)).isEmpty();
        assertThat(turnDetailWrapperByRunId(fragment)).isEmpty();
        assertThat(turnExtras(fragment)).isEmpty();
    }

    @Test
    void correlateHistory_swallowsRuntimeException() throws Exception {
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        when(udm.loadValues(anyString())).thenThrow(new RuntimeException("agentstore down"));
        ChatPanelFragment fragment = newFragment(udm, 4);

        invokeCorrelateHistory(fragment, CID, List.of(1, 3)); // must not throw

        assertThat(turnDetailsMap(fragment)).isEmpty();
    }

    @Test
    void replayLoop_anchorsNoticeRowsByTurn_andReanchorsAfterSetItems() throws Exception {
        // The history-replay loop body + the re-anchor pass are asserted via a source scan
        // (setConversationIdInternal needs full Jmix wiring).
        String source = readSource();
        assertThat(source)
                .contains("assistantTurnIndices.add(items.size() - 1)")
                .contains("appendNoticeRow(m.getContent())")
                .contains("correlateHistoryTurnDetails(cid, assistantTurnIndices)")
                // re-anchor pass at the end of history replay (after all extras exist)
                .contains("reanchorAllExtras();");
    }

    // ---- harness -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void stubRootRunIds(UnconstrainedDataManager udm, List<UUID> runIds) {
        List<KeyValueEntity> rows = new ArrayList<>();
        for (UUID runId : runIds) {
            KeyValueEntity row = new KeyValueEntity();
            row.setValue("runId", runId);
            rows.add(row);
        }
        when(udm.loadValues(org.mockito.ArgumentMatchers.contains("e.parent is null"))
                .store("agentstore")
                .properties("runId")
                .parameter(eq("me"), anyString())
                .parameter(eq("cid"), eq(CID))
                .parameter(eq("chatKind"), any())
                .list())
                .thenReturn(rows);
    }

    private static void stubChildCounts(UnconstrainedDataManager udm, List<KeyValueEntity> rows) {
        when(udm.loadValues(org.mockito.ArgumentMatchers.contains("c.parent is not null"))
                .store("agentstore")
                .properties("runId", "cnt")
                .parameter(eq("me"), anyString())
                .parameter(eq("cid"), eq(CID))
                .parameter(eq("toolKind"), any())
                .parameter(eq("retrievalKind"), any())
                .list())
                .thenReturn(rows);
    }

    private static KeyValueEntity kv(UUID runId, long count) {
        KeyValueEntity row = new KeyValueEntity();
        row.setValue("runId", runId);
        row.setValue("cnt", count);
        return row;
    }

    private static AiAuditEvent auditChild(String kind, Long latencyMs, AiToolCallOutcome outcome) {
        AiAuditEvent e = new AiAuditEvent();
        e.setKind(kind);
        e.setLatencyMs(latencyMs);
        e.setOutcome(outcome);
        return e;
    }

    private static ChatPanelFragment newFragment(UnconstrainedDataManager udm, int itemCount) throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        VerticalLayout slot = new VerticalLayout();
        MessageList messageList = new MessageList();
        slot.add(messageList);
        inject(fragment, "messageListSlot", slot);
        inject(fragment, "messageList", messageList);
        inject(fragment, "messageInput", new MessageInput());
        inject(fragment, "streamProgressBar", new ProgressBar());
        io.jmix.core.Messages messages = mock(io.jmix.core.Messages.class);
        when(messages.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        inject(fragment, "messages", messages);
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        UserDetails userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn("alice");
        when(currentAuthentication.getUser()).thenReturn(userDetails);
        inject(fragment, "currentAuthentication", currentAuthentication);
        inject(fragment, "unconstrainedDataManager", udm);
        @SuppressWarnings("unchecked")
        List<MessageListItem> items = (List<MessageListItem>) get(fragment, "items");
        for (int i = 0; i < itemCount; i++) {
            items.add(new MessageListItem("m" + i, Instant.now(), "u"));
        }
        messageList.setItems(new ArrayList<>(items));
        return fragment;
    }

    private static String rowText(Div row) {
        StringBuilder sb = new StringBuilder();
        row.getElement().getChildren().forEach(child -> sb.append(child.getText()).append(' '));
        return sb.toString();
    }

    private static VerticalLayout slot(ChatPanelFragment fragment) throws Exception {
        return (VerticalLayout) get(fragment, "messageListSlot");
    }

    private static Details detailsFor(ChatPanelFragment fragment, UUID runId) throws Exception {
        return turnDetailsMap(fragment).get(runId);
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

    private static String loadedKey() throws Exception {
        Field f = ChatPanelFragment.class.getDeclaredField("TURN_DETAILS_LOADED_KEY");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static void invokeCorrelateHistory(ChatPanelFragment fragment, UUID cid, List<Integer> assistantTurnIndices)
            throws Exception {
        Method m = ChatPanelFragment.class.getDeclaredMethod("correlateHistoryTurnDetails", UUID.class, List.class);
        m.setAccessible(true);
        m.invoke(fragment, cid, assistantTurnIndices);
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
        for (String rel : new String[]{
                "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java",
                "src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java"}) {
            for (java.nio.file.Path candidate : new java.nio.file.Path[]{
                    java.nio.file.Path.of(rel),
                    java.nio.file.Path.of(System.getProperty("user.dir")).resolve(rel).normalize()}) {
                if (java.nio.file.Files.exists(candidate)) {
                    return java.nio.file.Files.readString(candidate, java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        throw new java.nio.file.NoSuchFileException("ChatPanelFragment.java");
    }
}
