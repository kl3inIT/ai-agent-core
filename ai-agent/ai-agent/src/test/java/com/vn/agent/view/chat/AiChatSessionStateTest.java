package com.vn.agent.view.chat;

import com.vaadin.flow.shared.Registration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatSessionStateTest {

    @Test
    void setterAndGetterTrackCurrentConversationId() {
        AiChatSessionState state = new AiChatSessionState();
        UUID conversationId = UUID.randomUUID();

        state.setCurrentConversationId(conversationId);

        assertThat(state.getCurrentConversationId()).isEqualTo(conversationId);
    }

    @Test
    void multipleListenersReceiveUpdates() {
        AiChatSessionState state = new AiChatSessionState();
        List<UUID> firstListenerUpdates = new ArrayList<>();
        List<UUID> secondListenerUpdates = new ArrayList<>();
        UUID conversationId = UUID.randomUUID();

        state.addConversationIdChangeListener(firstListenerUpdates::add);
        state.addConversationIdChangeListener(secondListenerUpdates::add);

        state.setCurrentConversationId(conversationId);

        assertThat(firstListenerUpdates).containsExactly(conversationId);
        assertThat(secondListenerUpdates).containsExactly(conversationId);
    }

    @Test
    void unregisterPreventsLaterCallbacks() {
        AiChatSessionState state = new AiChatSessionState();
        List<UUID> updates = new ArrayList<>();
        UUID firstConversationId = UUID.randomUUID();
        UUID secondConversationId = UUID.randomUUID();

        Registration registration = state.addConversationIdChangeListener(updates::add);

        state.setCurrentConversationId(firstConversationId);
        registration.remove();
        state.setCurrentConversationId(secondConversationId);

        assertThat(updates).containsExactly(firstConversationId);
    }

    @Test
    void clearingWithNullNotifiesListeners() {
        AiChatSessionState state = new AiChatSessionState();
        List<UUID> updates = new ArrayList<>();

        state.addConversationIdChangeListener(updates::add);
        state.setCurrentConversationId(UUID.randomUUID());
        state.setCurrentConversationId(null);

        assertThat(updates).hasSize(2);
        assertThat(updates.get(1)).isNull();
        assertThat(state.getCurrentConversationId()).isNull();
    }
}
