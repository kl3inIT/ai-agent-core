package com.vn.agent.view.chat.fragment;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.orchestration.ConversationGateway;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private static void injectConversationGateway(ChatPanelFragment fragment,
                                                  ConversationGateway conversationGateway) throws Exception {
        Field field = ChatPanelFragment.class.getDeclaredField("conversationGateway");
        field.setAccessible(true);
        field.set(fragment, conversationGateway);
    }
}
