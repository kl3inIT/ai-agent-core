package com.vn.agent;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Persists the user {@link AiMessage} row for a chat turn BEFORE the chat client is
 * invoked, so the caller can capture a stable {@code AiMessage.id} and thread it into
 * {@link com.vn.agent.taskfile.AiTaskFileRepository#markInjected} without a SELECT-back
 * "latest USER message" lookup (Plan 13-04 REVIEWS HIGH-14 — the SELECT-back races
 * under concurrent tabs / rapid sends in the same conversation).
 *
 * <p>This persister exists ONLY to give the Phase 13 task-file flow a stable user-message
 * id seam. The chat-memory advisor's own {@link AiMessage} projection still runs inside
 * {@code chatClient.prompt(...).call()/.stream()} for memory continuity and RAG context;
 * this class does NOT interfere with that path. The two writes target the same agentstore
 * but the chat-memory advisor uses its own {@code JdbcChatMemoryRepository} contract that
 * deletes/reinserts AiMessage rows on every {@code saveAll}, so the row this persister
 * writes may be replaced by the time {@code markInjected} runs — that is by design and is
 * tolerated by {@link com.vn.agent.taskfile.AiTaskFileRepository#markInjected} (the
 * authoritative pending marker is {@code injectedAt}, not the {@code message} FK).
 *
 * <p>Concurrency contract: writes go through {@link UnconstrainedDataManager} inside a
 * REQUIRES_NEW agentstore transaction so the row commits independently of any rollback in
 * the surrounding chat path. Mirrors the Phase 11 {@code AuditWriter.writeToolCall}
 * REQUIRES_NEW invariant.
 */
@Component
public class UserMessagePersister {

    private final UnconstrainedDataManager unconstrainedDataManager;
    private final Metadata metadata;
    private final TransactionTemplate agentstoreRequiresNew;

    public UserMessagePersister(UnconstrainedDataManager unconstrainedDataManager,
                                Metadata metadata,
                                @Qualifier("agentstoreTransactionManager")
                                PlatformTransactionManager agentstoreTransactionManager) {
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.metadata = metadata;
        this.agentstoreRequiresNew = new TransactionTemplate(agentstoreTransactionManager);
        this.agentstoreRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Persists a USER {@link AiMessage} in REQUIRES_NEW and returns the just-assigned id.
     * The returned id is stable for the duration of the surrounding chat turn; the
     * caller threads it directly into
     * {@link com.vn.agent.taskfile.AiTaskFileRepository#markInjected}.
     *
     * @param conversationId conversation FK; must not be {@code null}.
     * @param content        user message text; stored verbatim.
     * @param username       attribution user; persisted via {@code @CreatedBy} / audit
     *                       columns when the agentstore audit listeners run, but not
     *                       set on a dedicated column on AiMessage (it carries no
     *                       {@code userUsername} field — the conversation FK is the
     *                       attribution path).
     * @return the {@link AiMessage#getId()} of the row just written; never {@code null}.
     */
    public UUID persistUserMessage(UUID conversationId, String content, String username) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        UUID result = agentstoreRequiresNew.execute(status -> {
            AiConversation conversation = unconstrainedDataManager.load(AiConversation.class)
                    .id(conversationId)
                    .one();
            AiMessage row = metadata.create(AiMessage.class);
            row.setConversation(conversation);
            row.setRole(AiMessageRole.USER);
            row.setContent(content == null ? "" : content);
            AiMessage saved = unconstrainedDataManager.save(row);
            return saved.getId();
        });
        if (result == null) {
            throw new IllegalStateException("UserMessagePersister returned null id (transaction returned null)");
        }
        return result;
    }
}
