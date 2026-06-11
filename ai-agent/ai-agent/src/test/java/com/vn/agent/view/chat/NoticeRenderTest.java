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
 * Inline-attachments render contract regression.
 *
 * <p>Asserts that {@link ChatPanelFragment} renders uploaded files as inline
 * {@code AiTaskFileInlineCard}s anchored under their turn (the retired right-pane
 * Attachments split + the old {@code <div class="ai-agent-attachment-notice">} text row
 * are gone), that {@link AiMessageRole#NOTICE} rows are STILL persisted as the model's
 * upload ledger but no longer rendered, and that USER/ASSISTANT turns use Vaadin
 * {@link com.vaadin.flow.component.messages.MessageList} +
 * {@link com.vaadin.flow.component.messages.MessageListItem} (Phase 7.1 baseline).
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
    void chatPanelFragmentRendersUploadedFilesAsInlineCards() throws Exception {
        String source = readChatPanelFragmentSource();

        // 1. Uploaded files render as inline AiTaskFileInlineCard, anchored under the turn.
        assertThat(source)
                .as("appendTaskFileCard helper must build the inline attachment card")
                .contains("private void appendTaskFileCard(AiTaskFile file)")
                .contains("new AiTaskFileInlineCard(messages)")
                .contains("anchorExtra(items.isEmpty() ? 0 : items.size() - 1, card.getElement())");

        // 2. The old NOTICE-as-div render substrate is gone (files now render as cards).
        assertThat(source)
                .as("the retired NOTICE-as-div substrate must not return")
                .doesNotContain("appendNoticeRow")
                .doesNotContain("ai-agent-attachment-notice");

        // 3. NOTICE AiMessage rows are STILL persisted as the model's upload ledger
        //    (ProjectingChatMemoryRepository D-A1) — just not rendered in the UI.
        assertThat(source)
                .as("NOTICE row is still written for model memory via the bilingual notice key")
                .contains("AiMessageRole.NOTICE")
                .contains("chatView.attachments.notice");

        // 4. Replay merges the conversation's uploaded files as inline cards by createdDate.
        assertThat(source)
                .as("history replay must render uploaded files as inline cards")
                .contains("renderReplayTaskFileCards");

        // 5. USER/ASSISTANT turns use Vaadin MessageList + MessageListItem (Phase 7.1 baseline).
        assertThat(source)
                .as("USER/ASSISTANT turns must use Vaadin MessageList substrate")
                .contains("MessageList")
                .contains("MessageListItem")
                .contains("setUserColorIndex");
        assertThat(source)
                .as("MessageBubbleComponent must not be referenced from the live chat path")
                .doesNotContain("MessageBubbleComponent");
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
