package com.vn.agent.conversation;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import io.jmix.core.UnconstrainedDataManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
public class ConversationTitleEligibilityPublisher {

    private static final int TITLE_MAX_LENGTH = 80;

    private final UnconstrainedDataManager dataManager;
    private final ApplicationEventPublisher eventPublisher;

    public ConversationTitleEligibilityPublisher(UnconstrainedDataManager dataManager,
                                                 ApplicationEventPublisher eventPublisher) {
        this.dataManager = dataManager;
        this.eventPublisher = eventPublisher;
    }

    public void publishIfEligible(UUID conversationId, String userUsername, UUID runId, Locale localeHint) {
        if (conversationId == null) {
            return;
        }
        Optional<AiConversation> conversation = loadConversation(conversationId);
        if (conversation.isEmpty()) {
            return;
        }
        List<AiMessage> visibleMessages = visibleMessages(loadVisibleMessages(conversationId));
        long assistantMessages = visibleMessages.stream()
                .filter(message -> message.getRole() == AiMessageRole.ASSISTANT)
                .count();
        if (assistantMessages != 1) {
            return;
        }
        Optional<String> defaultTitle = defaultTitle(visibleMessages);
        if (defaultTitle.isEmpty() || !sameTitle(conversation.get().getTitle(), defaultTitle.get())) {
            return;
        }
        publishEvent(new ConversationTitleEligibleEvent(conversationId, userUsername, runId, localeHint));
    }

    Optional<AiConversation> loadConversation(UUID conversationId) {
        return dataManager.load(AiConversation.class)
                .id(conversationId)
                .optional();
    }

    List<AiMessage> loadVisibleMessages(UUID conversationId) {
        return dataManager.load(AiMessage.class)
                .query("select m from ai_AiMessage m where m.conversation.id = :conversationId "
                        + "order by m.createdDate asc, m.seq asc")
                .parameter("conversationId", conversationId)
                .list();
    }

    void publishEvent(ConversationTitleEligibleEvent event) {
        eventPublisher.publishEvent(event);
    }

    private List<AiMessage> visibleMessages(List<AiMessage> messages) {
        return messages.stream()
                .filter(message -> message.getRole() == AiMessageRole.USER
                        || message.getRole() == AiMessageRole.ASSISTANT)
                .sorted(Comparator
                        .comparing(AiMessage::getCreatedDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AiMessage::getSeq,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private Optional<String> defaultTitle(List<AiMessage> visibleMessages) {
        return visibleMessages.stream()
                .filter(message -> message.getRole() == AiMessageRole.USER)
                .map(AiMessage::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .map(this::truncateTitle);
    }

    private String truncateTitle(String value) {
        String stripped = value == null ? "" : value.strip();
        return stripped.length() <= TITLE_MAX_LENGTH ? stripped : stripped.substring(0, TITLE_MAX_LENGTH);
    }

    private boolean sameTitle(String left, String right) {
        return normalizeTitle(left).equals(normalizeTitle(right));
    }

    private String normalizeTitle(String value) {
        return value == null ? "" : value.strip();
    }
}
