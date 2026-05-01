package com.vn.agent.view.chat.fragment;

import com.vaadin.flow.component.DetachEvent;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.orchestration.ConversationGateway;
import com.vn.agent.rag.CancellationRegistry;
import com.vn.agent.view.chat.AiChatSessionState;
import io.jmix.core.DataManager;
import io.jmix.core.security.CurrentAuthentication;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.security.core.userdetails.UserDetails;
import reactor.core.Disposable;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ChatPanelFragmentConversationIdTest {

    @Test
    void ensureConversationIdForSubmit_createsOnce_thenReusesSameId() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        ConversationGateway gateway = mock(ConversationGateway.class);
        injectConversationGateway(fragment, gateway);

        UUID conversationId = UUID.randomUUID();
        AiConversation conversation = mock(AiConversation.class);
        when(conversation.getId()).thenReturn(conversationId);
        when(gateway.loadOrCreate("alice", null, "first question")).thenReturn(conversation);

        UUID first = fragment.ensureConversationIdForSubmit("alice", "first question");
        UUID second = fragment.ensureConversationIdForSubmit("alice", "second question");

        assertThat(first).isEqualTo(conversationId);
        assertThat(second).isEqualTo(conversationId);
        verify(gateway, times(1)).loadOrCreate("alice", null, "first question");
        verifyNoMoreInteractions(gateway);
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
    void ensureConversationIdForSubmit_updatesSessionState() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        ConversationGateway gateway = mock(ConversationGateway.class);
        AiChatSessionState sessionState = new AiChatSessionState();
        injectConversationGateway(fragment, gateway);
        inject(fragment, "chatSessionState", sessionState);

        UUID conversationId = UUID.randomUUID();
        AiConversation conversation = mock(AiConversation.class);
        when(conversation.getId()).thenReturn(conversationId);
        when(gateway.loadOrCreate("alice", null, "first question")).thenReturn(conversation);

        fragment.ensureConversationIdForSubmit("alice", "first question");

        assertThat(sessionState.getCurrentConversationId()).isEqualTo(conversationId);
    }

    @Test
    void startNewChat_clearsSessionState() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        AiChatSessionState sessionState = new AiChatSessionState();
        sessionState.setCurrentConversationId(UUID.randomUUID());
        inject(fragment, "chatSessionState", sessionState);
        inject(fragment, "conversationId", UUID.randomUUID());

        fragment.startNewChat();

        assertThat(fragment.getConversationId()).isNull();
        assertThat(sessionState.getCurrentConversationId()).isNull();
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
    void ensureConversationIdForSubmit_throwsWhenGatewayReturnsNullId() throws Exception {
        ChatPanelFragment fragment = new ChatPanelFragment();
        ConversationGateway gateway = mock(ConversationGateway.class);
        injectConversationGateway(fragment, gateway);

        AiConversation conversation = mock(AiConversation.class, withSettings().stubOnly());
        when(gateway.loadOrCreate("alice", null, "hello")).thenReturn(conversation);

        assertThatThrownBy(() -> fragment.ensureConversationIdForSubmit("alice", "hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Conversation id must not be null");
    }

    private static void prepareSetConversationIdDependencies(ChatPanelFragment fragment,
                                                             UUID conversationId) throws Exception {
        ConversationGateway conversationGateway = mock(ConversationGateway.class);
        CurrentAuthentication currentAuthentication = mock(CurrentAuthentication.class);
        UserDetails userDetails = mock(UserDetails.class);
        DataManager dataManager = mock(DataManager.class, RETURNS_DEEP_STUBS);
        MessageSource messageSource = mock(MessageSource.class);

        when(userDetails.getUsername()).thenReturn("alice");
        when(currentAuthentication.getUser()).thenReturn(userDetails);
        when(dataManager.load(AiMessage.class)
                .query(anyString())
                .parameter(eq("cid"), eq(conversationId))
                .list())
                .thenReturn(List.of());
        when(messageSource.getMessage(anyString(), isNull(), anyString(), any(Locale.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));

        injectConversationGateway(fragment, conversationGateway);
        inject(fragment, "currentAuthentication", currentAuthentication);
        inject(fragment, "dataManager", dataManager);
        inject(fragment, "messages", messageSource);
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
}
