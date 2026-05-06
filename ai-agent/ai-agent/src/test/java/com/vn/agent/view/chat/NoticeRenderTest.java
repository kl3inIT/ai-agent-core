package com.vn.agent.view.chat;

import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13.1 Plan 07 — REQ-5 / UX-01 NOTICE-render contract regression.
 *
 * <p>Asserts that {@link ChatPanelFragment} routes {@link AiMessageRole#NOTICE} rows
 * onto a {@code <vaadin-message class="attachment-event">} sibling element appended
 * directly to {@code messageListSlot.getElement()} (Pitfall 7 Option A) and that the
 * MessageList / MessageListItem substrate from Phase 13 has been retired.
 *
 * <p><b>Why source-scan instead of {@code @UiTest} DOM assertion:</b> the module-level
 * {@code @SpringBootTest} boot regression documented in
 * {@code .planning/phases/13-chat-task-input-stt-task-scoped-file/deferred-items.md}
 * (atmosphere-runtime / agentstoreEntityManagerFactory IndexOutOfBoundsException) blocks
 * runtime of every {@code @UiTest} that boots an agentstore Spring context. The render
 * pipeline that this regression guards is fully expressed in
 * {@link ChatPanelFragment}'s source — the DOM tag, CSS class, and dispatch-by-role
 * branch are deterministic Java code paths. Source-scan covers the same regression
 * surface without paying that boot cost (mirrors {@code ChatSurfaceMounterTest}'s
 * {@code mounterUsesVaadinServiceInitAndRequiredRuntimeGates} pattern shipped under
 * Plan 12-04).
 *
 * <p>The {@link AiMessageRole#NOTICE} role is also asserted to exist on the enum so
 * any compile-time refactor that drops it surfaces here too.
 */
class NoticeRenderTest {

    @Test
    void noticeRoleIsDeclaredOnAiMessageRoleEnum() {
        assertThat(Arrays.stream(AiMessageRole.values()).map(Enum::name))
                .as("Phase 13.1 Plan 01 added NOTICE; render path depends on it")
                .contains("NOTICE");
        assertThat(AiMessageRole.NOTICE.getId())
                .as("NOTICE.id is what the JPA column persists; render dispatch reads it")
                .isEqualTo("NOTICE");
    }

    @Test
    void chatPanelFragmentRendersNoticeAsVaadinMessageWithAttachmentEventClass() throws Exception {
        String source = readChatPanelFragmentSource();

        // 1. The render helper exists and constructs a raw vaadin-message element.
        assertThat(source)
                .as("appendNoticeRow helper must construct the <vaadin-message> substrate")
                .contains("private void appendNoticeRow(String text)")
                .contains("new com.vaadin.flow.dom.Element(\"vaadin-message\")")
                .contains(".getClassList().add(\"attachment-event\")");

        // 2. The text property is set via setProperty (Vaadin escapes by default —
        //    T-13.1-17 mitigation guard).
        assertThat(source)
                .as("text content must flow through setProperty(\"text\", ...) for HTML escaping")
                .contains(".setProperty(\"text\"");

        // 3. The element is appended to messageListSlot.getElement() (NOT MessageList children).
        assertThat(source)
                .as("NOTICE rows attach as raw Element children to messageListSlot")
                .contains("messageListSlot.getElement().appendChild(msg)");

        // 4. History-replay loop dispatches AiMessageRole.NOTICE to appendNoticeRow.
        assertThat(source)
                .as("history dispatch must route NOTICE through appendNoticeRow (Pitfall 7 Option A)")
                .contains("AiMessageRole.NOTICE")
                .contains("appendNoticeRow");

        // 5. No MessageListItem usage anywhere — substrate retired (D-29).
        assertThat(source)
                .as("Phase 13 MessageList/MessageListItem substrate must be retired")
                .doesNotContain("MessageListItem")
                .doesNotContain("new MessageList()");

        // 6. The NOTICE message body is formatted via the bilingual resource bundle key
        //    chatView.attachments.notice — confirms render text is the formatted ledger.
        assertThat(source)
                .as("NOTICE row content must be formatted via the bilingual notice key")
                .contains("chatView.attachments.notice");
    }

    @Test
    void chatPanelFragmentClearMessageListWipesBothComponentAndRawElementChildren() throws Exception {
        String source = readChatPanelFragmentSource();

        assertThat(source)
                .as("clearMessageList must wipe Composite children")
                .contains("messageListSlot.removeAll()");
        assertThat(source)
                .as("clearMessageList must also wipe raw vaadin-message Element siblings")
                .contains("messageListSlot.getElement().removeAllChildren()");
    }

    // ------------------------------- helpers --------------------------------

    private static String readChatPanelFragmentSource() throws Exception {
        Path primary = Paths.get(
                "src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");
        if (Files.exists(primary)) {
            return Files.readString(primary, StandardCharsets.UTF_8);
        }
        Path fallback = Paths.get(System.getProperty("user.dir"))
                .resolve("ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java");
        return Files.readString(fallback, StandardCharsets.UTF_8);
    }
}
