package com.vn.agent.orchestration;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Primary;
import org.springframework.lang.NonNull;
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
    /**
     * Unconstrained: chat-memory pruning is system-internal bookkeeping that must succeed
     * regardless of caller authentication. {@link #saveAll} and {@link #deleteByConversationId}
     * mirror {@link JdbcChatMemoryRepository}'s delete-then-insert semantics on the projected
     * {@code ai_AiMessage} rows; end-user roles (e.g. AiAgentUserRole) intentionally do NOT grant
     * UPDATE/DELETE on {@code ai_AiMessage} (memory pruning is invisible to users).
     *
     * <p><b>Cross-user safety:</b> the unconstrained scope does NOT enable cross-user row access.
     * Every JPQL query in this class is scoped to a {@code conversationId} UUID parameter
     * supplied by Spring AI's {@code MessageWindowChatMemory} (which receives it from the
     * {@link org.springframework.ai.chat.client.ChatClient} call in {@code DefaultChatServiceImpl},
     * which derives it from the authenticated principal). The UUID is opaque and unguessable, so
     * pruning a conversation requires already possessing its id — which is bounded by the
     * conversation listing path (covered by {@code ReadEntityQueryConstraint} via
     * {@code AiAgentUserRowLevelRole}'s row-level policy). T-08-08-07 disposition: accept.
     *
     * <p>See /jmix-framework/jmix-context7 data-manager.html, project memory
     * feedback_jmix_unconstrained_for_system_writes, and 08-08-INVESTIGATION-2.md.
     */
    private final UnconstrainedDataManager dataManager;
    private final Metadata metadata;

    public ProjectingChatMemoryRepository(JdbcChatMemoryRepository delegate,
                                          UnconstrainedDataManager dataManager,
                                          Metadata metadata) {
        this.delegate = delegate;
        this.dataManager = dataManager;
        this.metadata = metadata;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<Message> findByConversationId(@NonNull String conversationId) {
        return delegate.findByConversationId(conversationId);
    }

    @Override
    @Transactional
    public void saveAll(@NonNull String conversationId, @NonNull List<Message> messages) {
        delegate.saveAll(conversationId, messages);
        if (messages == null) {
            return;
        }
        UUID convUuid = UUID.fromString(conversationId);
        // Mirror JdbcChatMemoryRepository.saveAll semantics: delete-then-insert the whole
        // conversation. MessageWindowChatMemory calls saveAll with the CUMULATIVE list each turn,
        // so an append-only projection would duplicate rows; JDBC replaces the set atomically.
        dataManager.load(AiMessage.class)
                .query("select m from ai_AiMessage m where m.conversation.id = :cid")
                .parameter("cid", convUuid)
                .list()
                .forEach(dataManager::remove);
        if (messages.isEmpty()) {
            return;
        }
        AiConversation conv = dataManager.load(AiConversation.class).id(convUuid).one();
        OffsetDateTime base = OffsetDateTime.now();
        int seq = 0;
        for (Message m : messages) {
            AiMessage row = metadata.create(AiMessage.class);
            row.setConversation(conv);
            row.setContent(m.getText());
            // Preserve insertion order by giving each row a monotonically increasing timestamp.
            // MessageWindowChatMemory supplies the cumulative list in conversation order, so the
            // ORDER BY createdDate query in DualLayerParityTest lines up with JDBC's native order.
            row.setCreatedDate(base.plusNanos(seq));
            row.setSeq(seq);
            // AiMessage exposes role only through the AiMessageRole enum setter; the message's
            // MessageType value (USER/ASSISTANT/SYSTEM/TOOL) maps 1:1 by uppercase id.
            row.setRole(resolveRole(m));
            dataManager.save(row);
            seq++;
        }
    }

    @Override
    @Transactional
    public void deleteByConversationId(@NonNull String conversationId) {
        delegate.deleteByConversationId(conversationId);
        UUID convUuid = UUID.fromString(conversationId);
        dataManager.load(AiMessage.class)
                .query("select m from ai_AiMessage m where m.conversation.id = :cid")
                .parameter("cid", convUuid)
                .list()
                .forEach(dataManager::remove);
    }

    @Override
    @NonNull
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
