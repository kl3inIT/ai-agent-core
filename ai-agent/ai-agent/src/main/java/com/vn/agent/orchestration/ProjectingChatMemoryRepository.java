package com.vn.agent.orchestration;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Dual-layer {@link ChatMemoryRepository} decorator (D-08, ORCH-03).
 *
 * <p>Wraps the Spring AI {@link JdbcChatMemoryRepository} (primary store in the
 * {@code SPRING_AI_CHAT_MEMORY} table) and additionally projects every inbound message into the
 * Jmix-managed {@code ai_AiMessage} table inside the SAME {@link Transactional} (REQUIRED)
 * boundary — so if the projection write fails, the Spring AI row rolls back with it, and vice
 * versa.</p>
 *
 * <p>Registered as {@code @Primary} so {@code MessageWindowChatMemory.builder().chatMemoryRepository(...)}
 * (AIAutoConfiguration, Plan 04-04 Task 3) binds this decorator rather than the raw JDBC
 * repository. Reads are pass-through — projection is a one-way write duty.</p>
 */
@Primary
@Component
public class ProjectingChatMemoryRepository implements ChatMemoryRepository {

    private final JdbcChatMemoryRepository delegate;
    private final DataManager dataManager;
    private final Metadata metadata;

    public ProjectingChatMemoryRepository(JdbcChatMemoryRepository delegate,
                                          DataManager dataManager,
                                          Metadata metadata) {
        this.delegate = delegate;
        this.dataManager = dataManager;
        this.metadata = metadata;
    }

    @Override
    @Transactional
    public List<Message> findByConversationId(String conversationId) {
        return delegate.findByConversationId(conversationId);
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        delegate.saveAll(conversationId, messages);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        UUID convUuid = UUID.fromString(conversationId);
        AiConversation conv = dataManager.load(AiConversation.class).id(convUuid).one();
        for (Message m : messages) {
            AiMessage row = metadata.create(AiMessage.class);
            row.setConversation(conv);
            row.setContent(m.getText());
            row.setCreatedDate(OffsetDateTime.now());
            // AiMessage exposes role only through the AiMessageRole enum setter; the message's
            // MessageType value (USER/ASSISTANT/SYSTEM/TOOL) maps 1:1 by uppercase id.
            row.setRole(resolveRole(m));
            dataManager.save(row);
        }
    }

    @Override
    @Transactional
    public void deleteByConversationId(String conversationId) {
        delegate.deleteByConversationId(conversationId);
        UUID convUuid = UUID.fromString(conversationId);
        dataManager.load(AiMessage.class)
                .query("select m from ai_AiMessage m where m.conversation.id = :cid")
                .parameter("cid", convUuid)
                .list()
                .forEach(dataManager::remove);
    }

    @Override
    public List<String> findConversationIds() {
        return delegate.findConversationIds();
    }

    /**
     * Map a Spring AI {@link Message}'s {@code MessageType} onto the {@code AiMessageRole} enum
     * used by the projected row. The enum ids are uppercase (USER/ASSISTANT/SYSTEM/TOOL), which
     * matches {@code MessageType#name()}.
     */
    private static com.vn.agent.entity.AiMessageRole resolveRole(Message m) {
        String id = m.getMessageType() == null ? null : m.getMessageType().name();
        return id == null ? null : com.vn.agent.entity.AiMessageRole.fromId(id);
    }
}
