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
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.entity.KeyValueEntity;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * Plan 15-04 Task 3 — locks the post-navigation {@code AiAuditEvent} correlation pass
 * (SPEC req 5 / CONTEXT D-07, review points #3/#13): after the history-replay loop calls
 * {@code messageList.setItems(...)}, the fragment loads the conversation's CHAT-root
 * {@code runId}s + child counts and zips them 1:1 against the replayed ASSISTANT turns ONLY
 * when the counts match — anchoring a collapsed {@code Details} (in the {@code .ai-agent-turn-activity}
 * block) only for roots with &ge;1 child, lazily + memoizedly re-reading that root's children on
 * first expand; on a count mismatch it renders NO disclosures (no guess).
 *
 * <p>Plain JUnit 5 + Mockito (mirrors {@code ChatPanelFragmentConversationIdTest}); the
 * {@code correlateHistoryTurnDetails} / {@code appendHistoryTurnDetails} contract is invoked
 * directly (the {@code setConversationIdInternal} path needs full Jmix wiring).
 */
class ChatPanelFragmentTurnDetailHistoryTest {

    private static final UUID CID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID RUN_A = UUID.fromString("11111111-0000-0000-0000-00000000000a");
    private static final UUID RUN_B = UUID.fromString("22222222-0000-0000-0000-00000000000b");
    private static final UUID RUN_C = UUID.fromString("33333333-0000-0000-0000-00000000000c");

    @Test
    void correlateHistory_matchingCounts_anchorsDetailsOnlyForRootsWithChildren_noPlaceholderForZeroChild() throws Exception {
        // 3 ASSISTANT turns; roots A (2 children), B (0 children), C (1 child).
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        stubRootRunIds(udm, List.of(RUN_A, RUN_B, RUN_C));
        stubChildCounts(udm, List.of(kv(RUN_A, 2L), kv(RUN_C, 1L))); // B absent => 0
        ChatPanelFragment fragment = newFragment(udm);

        invokeCorrelateHistory(fragment, CID, 3);

        Div block = activityBlock(fragment);
        assertThat(block).isNotNull();
        List<Details> detailsList = block.getChildren()
                .filter(c -> c instanceof Details).map(c -> (Details) c).toList();
        assertThat(detailsList).hasSize(2); // A and C only — B (zero-child) gets no Details

        // Both collapsed, anchored by runId in turnDetailsByRunId.
        for (Details d : detailsList) {
            assertThat(d.isOpened()).isFalse();
        }
        assertThat(turnDetailsMap(fragment).keySet()).containsExactlyInAnyOrder(RUN_A, RUN_C);
    }

    @Test
    void expandingHistoryDetails_lazilyLoadsStepsOnce_thenMemoizes() throws Exception {
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        stubRootRunIds(udm, List.of(RUN_A));
        stubChildCounts(udm, List.of(kv(RUN_A, 2L)));
        // Wire a query-count spy on the loadTurnSteps read for RUN_A (review point #13).
        AtomicInteger queryCount = new AtomicInteger();
        when(udm.load(AiAuditEvent.class)
                .query(anyString())
                .parameter(eq("me"), anyString())
                .parameter(eq("cid"), eq(CID))
                .parameter(eq("rid"), eq(RUN_A))
                .fetchPlan(any(java.util.function.Consumer.class))
                .list())
                .thenAnswer(inv -> {
                    queryCount.incrementAndGet();
                    return List.of(
                            auditChild("TOOL", 13L, AiToolCallOutcome.SUCCESS),
                            auditChild("RETRIEVAL", null, null));
                });
        ChatPanelFragment fragment = newFragment(udm);
        invokeCorrelateHistory(fragment, CID, 1);

        Details details = turnDetailsMap(fragment).get(RUN_A);
        assertThat(details).isNotNull();
        assertThat(queryCount.get()).isZero(); // not yet read — collapsed

        // First expand → exactly one loadTurnSteps query.
        details.setOpened(true);
        assertThat(queryCount.get()).isEqualTo(1);
        assertThat(ComponentUtil.getData(details, loadedKey())).isEqualTo(Boolean.TRUE);
        // Content: 2 label-only rows; the null-latencyMs child renders the em-dash.
        VerticalLayout content = (VerticalLayout) details.getContent().toList().get(0);
        List<Div> rows = content.getChildren().filter(c -> c instanceof Div).map(c -> (Div) c).toList();
        assertThat(rows).hasSize(2);
        assertThat(rowText(rows.get(0))).contains("chatView.turnDetail.step.tool", "13 ms");
        assertThat(rowText(rows.get(1))).contains("chatView.turnDetail.step.retrieval", "—");
        assertThat(rowText(rows.get(1))).doesNotContain("0 ms", "null ms");

        // Collapse + re-expand → still exactly one query (LOADED_KEY short-circuits).
        details.setOpened(false);
        details.setOpened(true);
        assertThat(queryCount.get()).isEqualTo(1);
    }

    @Test
    void correlateHistory_countMismatch_rendersNoDetails_noException() throws Exception {
        // 3 ASSISTANT turns but only 2 CHAT roots → render none, never throw (review point #3).
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        stubRootRunIds(udm, List.of(RUN_A, RUN_B));
        stubChildCounts(udm, List.of(kv(RUN_A, 2L), kv(RUN_B, 1L)));
        ChatPanelFragment fragment = newFragment(udm);

        invokeCorrelateHistory(fragment, CID, 3);

        // No activity block created (no Details appended), no exception.
        assertThat(activityBlock(fragment)).isNull();
        assertThat(turnDetailsMap(fragment)).isEmpty();
    }

    @Test
    void correlateHistory_swallowsRuntimeException() throws Exception {
        UnconstrainedDataManager udm = mock(UnconstrainedDataManager.class, RETURNS_DEEP_STUBS);
        when(udm.loadValues(anyString())).thenThrow(new RuntimeException("agentstore down"));
        ChatPanelFragment fragment = newFragment(udm);

        invokeCorrelateHistory(fragment, CID, 2); // must not throw

        assertThat(turnDetailsMap(fragment)).isEmpty();
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
                .list())
                .thenReturn(rows);
    }

    private static void stubChildCounts(UnconstrainedDataManager udm, List<KeyValueEntity> rows) {
        when(udm.loadValues(org.mockito.ArgumentMatchers.contains("c.parent is not null"))
                .store("agentstore")
                .properties("runId", "cnt")
                .parameter(eq("me"), anyString())
                .parameter(eq("cid"), eq(CID))
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

    private static ChatPanelFragment newFragment(UnconstrainedDataManager udm) throws Exception {
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
        return fragment;
    }

    private static String rowText(Div row) {
        StringBuilder sb = new StringBuilder();
        row.getElement().getChildren().forEach(child -> sb.append(child.getText()).append(' '));
        return sb.toString();
    }

    private static Div activityBlock(ChatPanelFragment fragment) throws Exception {
        return (Div) get(fragment, "turnActivityBlock");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<UUID, Details> turnDetailsMap(ChatPanelFragment fragment) throws Exception {
        return (java.util.Map<UUID, Details>) get(fragment, "turnDetailsByRunId");
    }

    private static String loadedKey() throws Exception {
        Field f = ChatPanelFragment.class.getDeclaredField("TURN_DETAILS_LOADED_KEY");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    private static void invokeCorrelateHistory(ChatPanelFragment fragment, UUID cid, int assistantTurnCount)
            throws Exception {
        Method m = ChatPanelFragment.class.getDeclaredMethod("correlateHistoryTurnDetails", UUID.class, int.class);
        m.setAccessible(true);
        m.invoke(fragment, cid, assistantTurnCount);
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
