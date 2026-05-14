package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.dom.Element;
import io.jmix.core.Messages;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan 15-04 Task 2 — locks the ephemeral KIND-keyed streaming-status line (OBS-01):
 * the {@code <span class="ai-agent-status">} is a sibling AFTER {@code <vaadin-message-list>}
 * in {@code messageListSlot}, shows the neutral text at turn start, flips to CHAT on the first
 * Content event (review point #6), updates on Activity events, and is GONE in every
 * terminal/teardown site (review point #7) — never concatenated into the assistant bubble.
 *
 * <p>Plain JUnit 5 + Mockito (mirrors {@code ChatPanelFragmentLoadingIndicatorTest} /
 * {@code ChatPanelFragmentConversationIdTest}): the streaming {@code doOnNext} wiring is
 * verified via a source scan (the {@code accessUi}-wrapped UI mutations need a live UI to run);
 * the {@code showStatus}/{@code removeStatusRow}/{@code finishStreamInternal}/{@code clearMessageList}
 * teardown contract is verified directly against a real {@code VerticalLayout} slot.
 */
class ChatPanelFragmentStatusLineTest {

    @Test
    void showStatus_appendsSpanSiblingAfterMessageList_withRoleAndAriaLive() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot();
        invokeShowStatus(fragment, "chatView.status.neutral");

        Element span = statusSpan(fragment);
        assertThat(span).isNotNull();
        assertThat(span.getTag()).isEqualTo("span");
        assertThat(span.getClassList()).contains("ai-agent-status");
        assertThat(span.getAttribute("role")).isEqualTo("status");
        assertThat(span.getAttribute("aria-live")).isEqualTo("polite");
        assertThat(span.getText()).isEqualTo("chatView.status.neutral");

        // It is a sibling of <vaadin-message-list>, appended AFTER it.
        VerticalLayout slot = slot(fragment);
        List<Element> children = slot.getElement().getChildren().toList();
        int messageListIndex = indexOfTag(children, "vaadin-message-list");
        int spanIndex = children.indexOf(span);
        assertThat(messageListIndex).isGreaterThanOrEqualTo(0);
        assertThat(spanIndex).isGreaterThan(messageListIndex);
    }

    @Test
    void showStatus_updatesTextInPlace_onSubsequentCalls() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot();
        invokeShowStatus(fragment, "chatView.status.neutral");
        Element first = statusSpan(fragment);
        invokeShowStatus(fragment, "chatView.status.retrieval");
        Element afterRetrieval = statusSpan(fragment);
        invokeShowStatus(fragment, "chatView.status.chat");

        assertThat(afterRetrieval).isSameAs(first); // same element, text updated in place
        assertThat(statusSpan(fragment).getText()).isEqualTo("chatView.status.chat");
        // Exactly one .ai-agent-status span exists.
        assertThat(slot(fragment).getElement().getChildren()
                .filter(e -> e.getClassList().contains("ai-agent-status")).count())
                .isEqualTo(1);
    }

    @Test
    void removeStatusRow_detachesSpanAndNullsField_idempotent() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot();
        invokeShowStatus(fragment, "chatView.status.tool");
        assertThat(statusSpan(fragment)).isNotNull();

        invokeRemoveStatusRow(fragment);
        assertThat(statusSpan(fragment)).isNull();
        assertThat(slot(fragment).getElement().getChildren()
                .anyMatch(e -> e.getClassList().contains("ai-agent-status"))).isFalse();

        // Idempotent.
        invokeRemoveStatusRow(fragment);
        assertThat(statusSpan(fragment)).isNull();
    }

    @Test
    void finishStreamInternal_removesStatusRow() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot();
        // finishStreamInternal touches setStreamingUiState (needs no extra wiring — null-guarded),
        // cancellationRegistry (only when activeRunId != null — leave it null), then removeStatusRow.
        invokeShowStatus(fragment, "chatView.status.chat");
        assertThat(statusSpan(fragment)).isNotNull();

        invoke(fragment, "finishStreamInternal");

        assertThat(statusSpan(fragment)).isNull();
    }

    @Test
    void clearMessageList_nullsStatusRowAndTurnContentSeen() throws Exception {
        ChatPanelFragment fragment = newFragmentWithSlot();
        invokeShowStatus(fragment, "chatView.status.tool");
        setBoolean(fragment, "turnContentSeen", true);
        assertThat(statusSpan(fragment)).isNotNull();

        invoke(fragment, "clearMessageList");

        assertThat(statusSpan(fragment)).isNull();
        assertThat(getBoolean(fragment, "turnContentSeen")).isFalse();
    }

    @Test
    void doOnNext_wiring_handlesActivityAndFirstContentImpliesChat_andSkipsBubbleForActivity() throws Exception {
        String source = readSource();
        // Activity branch BEFORE the renderStreamEventDetails call, skipping the markdown path.
        assertThat(source)
                .contains("if (evt instanceof StreamingEvent.Activity a)")
                .contains("showStatus(TurnDetailRenderer.statusKeyFor(a.kind()))")
                .contains("TurnDetailRenderer.neutralStatusKey()")
                // first-Content-implies-CHAT flip (review point #6)
                .contains("if (!turnContentSeen)")
                .contains("TurnDetailRenderer.statusKeyFor(StreamingEvent.ActivityKind.CHAT)")
                // status removed in every teardown site
                .contains("removeStatusRow()");
        // The status text is NEVER concatenated into the bubble — the only botMsg append stays the
        // unchanged markdown chunk.
        assertThat(source).contains("botMsg.appendText(md)");
        assertThat(source).doesNotContain("botMsg.appendText(messages.getMessage");
    }

    // ---- harness -----------------------------------------------------------

    private static ChatPanelFragment newFragmentWithSlot() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        VerticalLayout slot = new VerticalLayout();
        // Mirror onReady: a real MessageList added first; the status span / activity block come after.
        MessageList messageList = new MessageList();
        slot.add(messageList);
        inject(fragment, "messageListSlot", slot);
        inject(fragment, "messageList", messageList);
        inject(fragment, "messageInput", new MessageInput());
        inject(fragment, "streamProgressBar", new ProgressBar());
        Messages messages = mock(Messages.class);
        when(messages.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
        inject(fragment, "messages", messages);
        return fragment;
    }

    private static VerticalLayout slot(ChatPanelFragment fragment) throws Exception {
        return (VerticalLayout) get(fragment, "messageListSlot");
    }

    private static Element statusSpan(ChatPanelFragment fragment) throws Exception {
        Object value = get(fragment, "statusRow");
        return (Element) value;
    }

    private static int indexOfTag(List<Element> children, String tag) {
        for (int i = 0; i < children.size(); i++) {
            if (tag.equals(children.get(i).getTag())) {
                return i;
            }
        }
        return -1;
    }

    private static void invokeShowStatus(ChatPanelFragment fragment, String key) throws Exception {
        Method method = ChatPanelFragment.class.getDeclaredMethod("showStatus", String.class);
        method.setAccessible(true);
        method.invoke(fragment, key);
    }

    private static void invokeRemoveStatusRow(ChatPanelFragment fragment) throws Exception {
        invoke(fragment, "removeStatusRow");
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

    private static void setBoolean(ChatPanelFragment fragment, String fieldName, boolean value) throws Exception {
        Field field = ChatPanelFragment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(fragment, value);
    }

    private static boolean getBoolean(ChatPanelFragment fragment, String fieldName) throws Exception {
        Field field = ChatPanelFragment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(fragment);
    }

    private static String readSource() throws Exception {
        for (Path candidate : new Path[]{
                Path.of("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java"),
                Path.of("src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java"),
                Path.of(System.getProperty("user.dir"))
                        .resolve("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java")
                        .normalize(),
                Path.of(System.getProperty("user.dir"))
                        .resolve("src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java")
                        .normalize()
        }) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new java.nio.file.NoSuchFileException("ChatPanelFragment.java");
    }

    @SuppressWarnings("unused")
    private static Optional<Element> firstByClass(VerticalLayout slot, String cssClass) {
        return slot.getElement().getChildren().filter(e -> e.getClassList().contains(cssClass)).findFirst();
    }
}
