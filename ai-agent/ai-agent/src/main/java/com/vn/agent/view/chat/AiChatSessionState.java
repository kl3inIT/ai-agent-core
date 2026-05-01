package com.vn.agent.view.chat;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.spring.annotation.VaadinSessionScope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
@VaadinSessionScope
public class AiChatSessionState {

    private final List<ConversationIdListenerRegistration> listenerRegistrations = new CopyOnWriteArrayList<>();

    private volatile UUID currentConversationId;

    public UUID getCurrentConversationId() {
        return currentConversationId;
    }

    public void setCurrentConversationId(UUID currentConversationId) {
        if (Objects.equals(this.currentConversationId, currentConversationId)) {
            return;
        }

        this.currentConversationId = currentConversationId;
        notifyConversationIdChanged(currentConversationId);
    }

    public Registration addConversationIdChangeListener(Consumer<UUID> listener) {
        return addConversationIdChangeListener(null, listener);
    }

    public Registration addConversationIdChangeListener(UI ui, Consumer<UUID> listener) {
        Objects.requireNonNull(listener, "listener must not be null");

        ConversationIdListenerRegistration listenerRegistration =
                new ConversationIdListenerRegistration(ui, listener);
        listenerRegistrations.add(listenerRegistration);
        return () -> listenerRegistrations.remove(listenerRegistration);
    }

    private void notifyConversationIdChanged(UUID conversationId) {
        for (ConversationIdListenerRegistration listenerRegistration : listenerRegistrations) {
            listenerRegistration.notifyConversationIdChanged(conversationId);
        }
    }

    private record ConversationIdListenerRegistration(UI ui, Consumer<UUID> listener) {

        private void notifyConversationIdChanged(UUID conversationId) {
            if (ui == null) {
                listener.accept(conversationId);
                return;
            }

            if (ui.isAttached()) {
                ui.access(() -> listener.accept(conversationId));
            }
        }
    }
}
