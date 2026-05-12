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
 * onto a plain {@code <div class="ai-agent-attachment-notice">} sibling element
 * appended directly to {@code messageListSlot.getElement()} (Phase 13.1 UAT-fix
 * substrate; UI-SPEC §138 allows custom inline element) and that USER/ASSISTANT
 * turns use Vaadin {@link com.vaadin.flow.component.messages.MessageList} +
 * {@link com.vaadin.flow.component.messages.MessageListItem} (Phase 7.1 baseline).
 *
 * <p>The Plan 13.1-05 mixed substrate ({@code MessageBubbleComponent} for chat turns
 * plus {@code <vaadin-message class="attachment-event">} for NOTICE rows) was reverted
 * during UAT because the default {@code <vaadin-avatar>} slot rendered an upload-arrow
 * fallback when no userName was set; this test guards against drift back to that shape.
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
    void chatPanelFragmentRendersNoticeAsPlainDivWithAttachmentNoticeClass() throws Exception {
        String source = readChatPanelFragmentSource();

        // 1. The render helper exists and constructs a plain <div> element with the
        //    ai-agent-attachment-notice class (Phase 13.1 UAT-fix substrate).
        assertThat(source)
                .as("appendNoticeRow helper must construct the <div> NOTICE substrate")
                .contains("private void appendNoticeRow(String text)")
                .contains("new com.vaadin.flow.dom.Element(\"div\")")
                .contains(".getClassList().add(\"ai-agent-attachment-notice\")");

        // 2. The text content flows through Element.setText (HTML-escaped by default —
        //    T-13.1-17 mitigation guard).
        assertThat(source)
                .as("text content must flow through Element.setText(...) for HTML escaping")
                .contains(".setText(text)");

        // 3. Phase 15-06 Gap 2 (Option A) — the NOTICE element is anchored inline after the
        //    current turn's transcript message via anchorExtra(...) (server-side a child of
        //    messageListSlot right after the <vaadin-message-list>; client-side spliced into the
        //    message-list light DOM), no longer blindly appended at the messageListSlot tail.
        assertThat(source)
                .as("NOTICE rows are anchored inline per turn via anchorExtra(...)")
                .contains("anchorExtra(items.isEmpty() ? 0 : items.size() - 1, notice)");

        // 4. History-replay loop dispatches AiMessageRole.NOTICE to appendNoticeRow.
        assertThat(source)
                .as("history dispatch must route NOTICE through appendNoticeRow")
                .contains("AiMessageRole.NOTICE")
                .contains("appendNoticeRow");

        // 5. USER/ASSISTANT turns use Vaadin MessageList + MessageListItem (Phase 7.1
        //    baseline restored by UAT-fix). MessageBubbleComponent is no longer used
        //    on the live chat path.
        assertThat(source)
                .as("USER/ASSISTANT turns must use Vaadin MessageList substrate")
                .contains("MessageList")
                .contains("MessageListItem")
                .contains("setUserColorIndex");
        assertThat(source)
                .as("MessageBubbleComponent must not be referenced from the live chat path")
                .doesNotContain("MessageBubbleComponent");

        // 6. The NOTICE message body is formatted via the bilingual resource bundle key
        //    chatView.attachments.notice — confirms render text is the formatted ledger.
        //    The no-group formatMessage form is used (Phase 13.1 UAT-fix: the prior
        //    "com.vn.agent" group form returned the literal key).
        assertThat(source)
                .as("NOTICE row content must be formatted via the bilingual notice key")
                .contains("chatView.attachments.notice");
        assertThat(source)
                .as("formatMessage must use the no-group form (memory feedback_jmix_messages_over_spring)")
                .doesNotContain("formatMessage(\"com.vn.agent\"");
    }

    @Test
    void chatPanelFragmentClearMessageListWipesBothComponentAndRawElementChildren() throws Exception {
        String source = readChatPanelFragmentSource();

        assertThat(source)
                .as("clearMessageList must wipe Composite children")
                .contains("messageListSlot.removeAll()");
        assertThat(source)
                .as("clearMessageList must also wipe raw <div> NOTICE Element siblings")
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
