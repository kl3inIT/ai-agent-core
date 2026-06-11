package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.rag.CancellationRegistry;
import com.vn.agent.view.chat.AiChatSessionState;
import io.jmix.core.DataManager;
import io.jmix.core.Messages;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.flowui.kit.component.button.JmixButton;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import reactor.core.Disposable;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatPanelFragmentConversationIdTest {

    @Test
    void descriptorAddsTitleEditAndComposerUpload() throws Exception {
        Document document = readDescriptor();

        Element conversationTitle = elementById(document, "conversationTitle");
        Element editTitleButton = elementById(document, "editConversationTitleButton");

        assertThat(conversationTitle.getTagName()).isEqualTo("h3");
        assertThat(conversationTitle.getAttribute("text")).startsWith("msg://");
        assertThat(editTitleButton.getAttribute("icon")).isEqualTo("PENCIL");
        assertThat(editTitleButton.hasAttribute("text")).isFalse();
        assertThat(editTitleButton.getAttribute("title")).startsWith("msg://");

        // The right-pane Attachments split is retired; upload now lives in the composer.
        assertThat(document.getElementsByTagName("upload").getLength()).isEqualTo(1);

        Element taskFileUpload = elementById(document, "taskFileUpload");
        assertThat(taskFileUpload.getTagName()).isEqualTo("upload");
        assertThat(taskFileUpload.hasAttribute("receiverType")).isFalse();
        assertThat(taskFileUpload.getAttribute("maxFiles")).isEqualTo("10");
    }

    @Test
    void setConversationId_updatesSessionState() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        AiChatSessionState sessionState = new AiChatSessionState();
        UUID conversationId = UUID.randomUUID();
        prepareSetConversationIdDependencies(fragment, conversationId);
        inject(fragment, "chatSessionState", sessionState);

        fragment.setConversationId(conversationId);

        assertThat(sessionState.getCurrentConversationId()).isEqualTo(conversationId);
    }

    @Test
    void startNewChat_clearsSessionState() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        AiChatSessionState sessionState = new AiChatSessionState();
        JmixButton editTitleButton = new JmixButton();
        sessionState.setCurrentConversationId(UUID.randomUUID());
        inject(fragment, "chatSessionState", sessionState);
        inject(fragment, "conversationId", UUID.randomUUID());
        inject(fragment, "editConversationTitleButton", editTitleButton);

        fragment.startNewChat();

        assertThat(fragment.getConversationId()).isNull();
        assertThat(sessionState.getCurrentConversationId()).isNull();
        assertThat(editTitleButton.isVisible()).isFalse();
    }

    @Test
    void startNewChat_afterErroredTurn_forceClearsStrandedMessages() throws Exception {
        // 15-UAT Gap 1: an errored turn left conversationId == null with the user message still
        // rendered (messageCount > 0). startNewChat() must still clear, not early-return.
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
        // a stranded NOTICE-style div sibling, plus the messageCount bookkeeping
        slot.getElement().appendChild(new com.vaadin.flow.dom.Element("div"));
        inject(fragment, "conversationId", null);
        setInt(fragment, "messageCount", 1);

        fragment.startNewChat();

        assertThat(fragment.hasMessages()).isFalse();
        assertThat(getInt(fragment, "messageCount")).isZero();
        // the stale sibling row is gone; the only remaining children are the fresh
        // <vaadin-message-list> and the empty-chat starter-prompt suggestions panel
        // (rendered because the cleared conversation has no messages).
        assertThat(slot.getElement().getChildren()
                .filter(e -> "vaadin-message-list".equals(e.getTag())).count()).isEqualTo(1);
        assertThat(slot.getElement().getChildren()
                .anyMatch(e -> e.getClassList().contains("ai-agent-chat-panel__suggestions")))
                .as("empty chat re-renders the starter-prompt suggestions panel")
                .isTrue();
        assertThat(slot.getElement().getChildCount()).isEqualTo(2);
    }

    @Test
    void startNewChat_happyPathNoOp_isPreserved() throws Exception {
        // conversationId == null && messageCount == 0 → no work, no throw, stays cleared.
        ChatPanelFragment fragment = new ChatPanelFragment();
        inject(fragment, "conversationId", null);
        setInt(fragment, "messageCount", 0);

        fragment.startNewChat();

        assertThat(fragment.getConversationId()).isNull();
        assertThat(getInt(fragment, "messageCount")).isZero();
    }

    @Test
    void doOnError_keepsConversationIdInSyncFromAuditChatRoot() throws Exception {
        String source = readSource();
        assertThat(source)
                .contains(".doOnError(err -> {")
                .contains("if (activeRunId != null && conversationId == null)")
                .contains("resolveConversationIdForRun(activeRunId)")
                .contains("initializeConversationFromFinalEvent(serverConversationId)")
                // mandatory user + run filter against the unconstrained store
                .contains("where e.userUsername = :me and e.runId = :rid and e.parent is null")
                .contains(".store(\"agentstore\")");
    }

    @Test
    void detachUnregistersSessionStateListenerAndKeepsCancellationRegistryPath() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        AiChatSessionState sessionState = new AiChatSessionState();
        CancellationRegistry cancellationRegistry = mock(CancellationRegistry.class);
        Disposable activeStream = mock(Disposable.class);
        UUID activeRunId = UUID.randomUUID();
        inject(fragment, "chatSessionState", sessionState);
        inject(fragment, "cancellationRegistry", cancellationRegistry);
        inject(fragment, "activeStream", activeStream);
        inject(fragment, "activeRunId", activeRunId);
        when(activeStream.isDisposed()).thenReturn(false);

        fragment.registerConversationIdStateListener(null);

        fragment.onDetach(new DetachEvent(fragment));
        sessionState.setCurrentConversationId(UUID.randomUUID());

        assertThat(fragment.getConversationId()).isNull();
        verify(cancellationRegistry).cancel(activeRunId);
    }

    @Test
    void saveManualConversationTitle_trimsChecksOwnershipSavesAndUpdatesVisibleTitle() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        UUID conversationId = UUID.randomUUID();
        ConversationGateway conversationGateway = mock(ConversationGateway.class);
        DataManager dataManager = mock(DataManager.class);
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        UserDetails userDetails = mock(UserDetails.class);
        H3 conversationTitle = new H3();
        AiConversation conversation = mock(AiConversation.class);
        when(userDetails.getUsername()).thenReturn("alice");
        when(currentAuthentication.getUser()).thenReturn(userDetails);
        when(conversationGateway.loadOrCreate("alice", conversationId, null)).thenReturn(conversation);
        when(dataManager.save(conversation)).thenReturn(conversation);
        inject(fragment, "conversationGateway", conversationGateway);
        inject(fragment, "dataManager", dataManager);
        inject(fragment, "currentAuthentication", currentAuthentication);
        inject(fragment, "conversationTitle", conversationTitle);
        inject(fragment, "conversationId", conversationId);

        invokeSaveManualConversationTitle(fragment, "  Manual title  ");

        verify(conversationGateway).loadOrCreate("alice", conversationId, null);
        verify(conversation).setTitle("Manual title");
        verify(dataManager).save(conversation);
        assertThat(conversationTitle.getText()).isEqualTo("Manual title");
    }

    @Test
    void saveManualConversationTitle_rejectsBlankTitleBeforeSaving() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        DataManager dataManager = mock(DataManager.class);
        inject(fragment, "dataManager", dataManager);

        assertThatThrownBy(() -> invokeSaveManualConversationTitle(fragment, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chatView.editTitle.validation.required");
    }

    @Test
    void submitPassesNullableCurrentConversationIdToService() throws Exception {
        String source = readSource();

        assertThat(source)
                .doesNotContain("ensureConversationIdForSubmit(userId, text)")
                .contains("final UUID targetConversationId = conversationId")
                .contains("chatService.stream(userId, targetConversationId, modelText,")
                .contains("null, toolSurfaceIntentId, privateSystemAppendix)")
                .contains("accessUiAuthenticated(submitAuthentication")
                .contains("taskFilesDl.setParameter(\"conversationId\", conversationId)");
    }

    @Test
    void runWithAuthenticationRestoresCapturedAuthenticationForAsyncUiWork() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        Authentication previousAuthentication = mock(Authentication.class);
        Authentication capturedAuthentication = mock(Authentication.class);
        SecurityContext previousContext = SecurityContextHolder.createEmptyContext();
        previousContext.setAuthentication(previousAuthentication);
        SecurityContextHolder.setContext(previousContext);
        List<Authentication> observedAuthentications = new java.util.ArrayList<>();

        try {
            invokeRunWithAuthentication(fragment, capturedAuthentication, () ->
                    observedAuthentications.add(SecurityContextHolder.getContext().getAuthentication()));

            assertThat(observedAuthentications).containsExactly(capturedAuthentication);
            assertThat(SecurityContextHolder.getContext().getAuthentication())
                    .isSameAs(previousAuthentication);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void prepareSetConversationIdDependencies(ChatPanelFragment fragment,
                                                             UUID conversationId) throws Exception {
        ConversationGateway conversationGateway = mock(ConversationGateway.class);
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        UserDetails userDetails = mock(UserDetails.class);
        DataManager dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        Messages messages = mock(Messages.class);
        AiConversation conversation = mock(AiConversation.class);

        when(userDetails.getUsername()).thenReturn("alice");
        when(currentAuthentication.getUser()).thenReturn(userDetails);
        when(conversation.getTitle()).thenReturn("Loaded title");
        when(conversationGateway.loadOrCreate("alice", conversationId, null)).thenReturn(conversation);
        when(dataManager.load(AiMessage.class)
                .query(anyString())
                .parameter(eq("cid"), eq(conversationId))
                .list())
                .thenReturn(List.of());
        when(messages.getMessage(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        injectConversationGateway(fragment, conversationGateway);
        inject(fragment, "currentAuthentication", currentAuthentication);
        inject(fragment, "dataManager", dataManager);
        inject(fragment, "messages", messages);
    }

    private static void injectConversationGateway(ChatPanelFragment fragment,
                                                  ConversationGateway conversationGateway) throws Exception {
        inject(fragment, "conversationGateway", conversationGateway);
    }

    private static void inject(ChatPanelFragment fragment, String fieldName, Object value) throws Exception {
        Field field = ChatPanelFragment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(fragment, value);
    }

    private static void setInt(ChatPanelFragment fragment, String fieldName, int value) throws Exception {
        Field field = ChatPanelFragment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(fragment, value);
    }

    private static int getInt(ChatPanelFragment fragment, String fieldName) throws Exception {
        Field field = ChatPanelFragment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(fragment);
    }

    private static void invokeSaveManualConversationTitle(ChatPanelFragment fragment, String title) throws Exception {
        Method method = ChatPanelFragment.class.getDeclaredMethod("saveManualConversationTitle", String.class);
        method.setAccessible(true);
        try {
            method.invoke(fragment, title);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private static void invokeRunWithAuthentication(ChatPanelFragment fragment,
                                                    Authentication authentication,
                                                    Runnable action) throws Exception {
        Method method = ChatPanelFragment.class.getDeclaredMethod(
                "runWithAuthentication", Authentication.class, Runnable.class);
        method.setAccessible(true);
        method.invoke(fragment, authentication, action);
    }

    private static Document readDescriptor() throws Exception {
        try (InputStream stream = ChatPanelFragmentConversationIdTest.class.getResourceAsStream(
                "/com/vn/agent/view/chat/fragment/chat-panel-fragment.xml")) {
            assertThat(stream).isNotNull();
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static Element elementById(Document document, String id) {
        for (int i = 0; i < document.getElementsByTagName("*").getLength(); i++) {
            Element element = (Element) document.getElementsByTagName("*").item(i);
            if (id.equals(element.getAttribute("id"))) {
                return element;
            }
        }
        throw new AssertionError("Element not found: " + id);
    }

    private static String readSource() throws Exception {
        return Files.readString(resolveSourcePath(), StandardCharsets.UTF_8);
    }

    private static Path resolveSourcePath() throws Exception {
        String repositoryPath = "ai-agent/ai-agent/src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java";
        String moduleRelativePath = "src/main/java/com/vn/agent/view/chat/fragment/ChatPanelFragment.java";
        for (Path candidate : new Path[]{
                Path.of(repositoryPath),
                Path.of(moduleRelativePath),
                Path.of(System.getProperty("user.dir")).resolve(repositoryPath).normalize(),
                Path.of(System.getProperty("user.dir")).resolve(moduleRelativePath).normalize()
        }) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new java.nio.file.NoSuchFileException(repositoryPath);
    }
}
