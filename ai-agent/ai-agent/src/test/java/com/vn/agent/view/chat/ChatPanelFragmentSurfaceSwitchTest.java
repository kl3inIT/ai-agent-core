package com.vn.agent.view.chat;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.ChatService;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiChatSurface;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.entity.AiUiSettings;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.view.chat.fragment.ChatPanelFragment;
import com.vn.agent.view.conversation.ConversationListView;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.flowui.DialogWindows;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.testassist.FlowuiTestAssistConfiguration;
import io.jmix.flowui.testassist.UiTest;
import io.jmix.flowui.testassist.UiTestUtils;
import io.jmix.flowui.view.DialogWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@UiTest
@SpringBootTest(classes = {AITestConfiguration.class, FlowuiTestAssistConfiguration.class})
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
@TestPropertySource(properties = "ai-agent.conversation-title.enabled=false")
class ChatPanelFragmentSurfaceSwitchTest {

    private static final String USERNAME = "alice";
    private static final String ROUTE_MESSAGE = "first route surface question";
    private static final String DIALOG_MESSAGE = "second dialog surface question";

    @Autowired
    ChatService chatService;
    @Autowired
    DialogWindows dialogWindows;
    @Autowired
    ViewNavigators viewNavigators;
    @Autowired
    ObjectProvider<AiChatSessionState> chatSessionStateProvider;
    @Autowired
    ObjectProvider<AiChatUIState> chatUIStateProvider;
    @Autowired
    AiUiSettingsService uiSettingsService;
    @Autowired
    UnconstrainedDataManager unconstrainedDataManager;
    @Autowired
    JdbcChatMemoryRepository jdbcChatMemoryRepository;
    @Autowired
    SystemAuthenticator systemAuthenticator;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        systemAuthenticator.runWithSystem(() -> {
            jdbcChatMemoryRepository.findConversationIds()
                    .forEach(jdbcChatMemoryRepository::deleteByConversationId);
            unconstrainedDataManager.load(AiAuditEvent.class)
                    .query("select e from ai_AiAuditEvent e where e.parent is null")
                    .list()
                    .forEach(unconstrainedDataManager::remove);
            unconstrainedDataManager.load(AiConversation.class)
                    .all()
                    .list()
                    .forEach(unconstrainedDataManager::remove);
            unconstrainedDataManager.load(AiUiSettings.class)
                    .all()
                    .list()
                    .forEach(unconstrainedDataManager::remove);
        });
    }

    @Test
    void routeToDialogSwitchKeepsOneConversationAndJdbcMemoryRows() {
        systemAuthenticator.runWithUser(USERNAME, () -> {
            AiChatSessionState chatSessionState = chatSessionStateProvider.getObject();
            AiChatUIState chatUIState = chatUIStateProvider.getObject();
            enableRouteAndHeaderSurfaces();

            viewNavigators.view(UiTestUtils.getCurrentView(), ChatView.class)
                    .navigate();
            ChatView chatView = UiTestUtils.getCurrentView();
            ChatPanelFragment routeFragment = UiTestUtils.getComponent(chatView, "chatPanelFragment");

            ChatResponseDto firstResponse = chatService.ask(USERNAME, null, ROUTE_MESSAGE);
            routeFragment.setConversationId(firstResponse.conversationId());
            UUID capturedConversationId = chatSessionState.getCurrentConversationId();

            assertThat(capturedConversationId)
                    .as("route fragment must publish the active conversation id into session state")
                    .isEqualTo(firstResponse.conversationId());
            assertThat(routeFragment.getConversationId()).isEqualTo(capturedConversationId);

            viewNavigators.view(chatView, ConversationListView.class)
                    .navigate();
            assertThat(chatSessionState.getCurrentConversationId())
                    .as("route detach must not clear the active session conversation id")
                    .isEqualTo(capturedConversationId);

            DialogWindow<ChatDialogView> firstDialog = openChatDialog(chatUIState);
            ChatPanelFragment firstDialogFragment = UiTestUtils.getComponent(firstDialog.getView(), "chatPanelFragment");
            assertThat(firstDialogFragment.getConversationId())
                    .as("dialog fragment must reuse the route conversation id from session state")
                    .isEqualTo(capturedConversationId);

            firstDialog.close();
            chatUIState.clearDialogInstance();
            assertThat(chatSessionState.getCurrentConversationId())
                    .as("dialog close/open lifecycle must not clear session conversation id")
                    .isEqualTo(capturedConversationId);

            DialogWindow<ChatDialogView> secondDialog = openChatDialog(chatUIState);
            ChatPanelFragment dialogFragment = UiTestUtils.getComponent(secondDialog.getView(), "chatPanelFragment");
            assertThat(dialogFragment.getConversationId()).isEqualTo(capturedConversationId);

            ChatResponseDto secondResponse = chatService.ask(USERNAME, capturedConversationId, DIALOG_MESSAGE);
            dialogFragment.setConversationId(secondResponse.conversationId());

            assertThat(secondResponse.conversationId()).isEqualTo(capturedConversationId);
            assertSingleConversationWithProjectedMessages(capturedConversationId);
            assertJdbcMemoryRows(capturedConversationId);
        });
    }

    private void enableRouteAndHeaderSurfaces() {
        AiUiSettings settings = uiSettingsService.loadCurrent();
        settings.setEnabledSurfaceSet(EnumSet.of(AiChatSurface.FULL_ROUTE, AiChatSurface.HEADER_BUTTON));
        settings.setDefaultSurface(AiChatSurface.FULL_ROUTE);
        unconstrainedDataManager.save(settings);
    }

    private DialogWindow<ChatDialogView> openChatDialog(AiChatUIState chatUIState) {
        DialogWindow<ChatDialogView> dialogWindow = dialogWindows
                .view(UiTestUtils.getCurrentView(), ChatDialogView.class)
                .build();
        chatUIState.setDialogInstance(dialogWindow);
        dialogWindow.open();
        return dialogWindow;
    }

    private void assertSingleConversationWithProjectedMessages(UUID conversationId) {
        List<AiConversation> conversations = unconstrainedDataManager.load(AiConversation.class)
                .query("select c from ai_AiConversation c where c.createdBy = :createdBy")
                .parameter("createdBy", USERNAME)
                .list();
        assertThat(conversations)
                .as("surface switch must not create a second AiConversation")
                .singleElement()
                .extracting(AiConversation::getId)
                .isEqualTo(conversationId);

        List<AiMessage> messages = unconstrainedDataManager.load(AiMessage.class)
                .query("select m from ai_AiMessage m where m.conversation.id = :conversationId "
                        + "order by m.createdDate asc, m.seq asc")
                .parameter("conversationId", conversationId)
                .list();
        assertThat(messages)
                .as("Jmix projection must carry both user/assistant turns for the same UUID")
                .hasSize(4)
                .extracting(AiMessage::getRole, AiMessage::getContent)
                .containsExactly(
                        tuple(AiMessageRole.USER, ROUTE_MESSAGE),
                        tuple(AiMessageRole.ASSISTANT, "STUB:" + ROUTE_MESSAGE),
                        tuple(AiMessageRole.USER, DIALOG_MESSAGE),
                        tuple(AiMessageRole.ASSISTANT, "STUB:" + DIALOG_MESSAGE));
    }

    private void assertJdbcMemoryRows(UUID conversationId) {
        List<Message> springMemoryRows = jdbcChatMemoryRepository.findByConversationId(
                conversationId.toString());
        assertThat(springMemoryRows)
                .as("Spring AI JDBC memory is the primary continuity gate, not only the Jmix projection")
                .hasSize(4)
                .extracting(Message::getText)
                .containsExactly(
                        ROUTE_MESSAGE,
                        "STUB:" + ROUTE_MESSAGE,
                        DIALOG_MESSAGE,
                        "STUB:" + DIALOG_MESSAGE);
    }
}
