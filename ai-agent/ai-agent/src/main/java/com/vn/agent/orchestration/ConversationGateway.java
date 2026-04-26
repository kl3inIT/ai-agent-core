package com.vn.agent.orchestration;

import com.vn.agent.entity.AiConversation;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-request loader/creator for {@link AiConversation} (ORCH-04).
 *
 * <p>Enforces two orthogonal rules defined in {@code 04-CONTEXT.md}:</p>
 *
 * <ul>
 *   <li><b>D-08 title rule</b> — on auto-creation the title is set to the user's first message
 *       truncated to 80 characters. Null/blank first messages leave the title null.</li>
 *   <li><b>D-09 ownership opacity</b> — when {@code conversationId != null} the row is loaded
 *       via a JPQL predicate that combines {@code c.id} AND {@code c.createdBy} in a single
 *       query. The same {@link ConversationNotFoundException} (carrying the same literal
 *       message) is thrown whether the row is missing entirely or simply owned by another user,
 *       so attackers cannot probe for conversation-id existence via exception shape.</li>
 * </ul>
 *
 * <p>The ownership field is {@link AiConversation#getCreatedBy()} — the entity has no separate
 * {@code userId} field (B3 fix from checker review).</p>
 */
@Component
public class ConversationGateway {

    /** Maximum character length of an auto-generated conversation title (D-08). */
    public static final int TITLE_MAX_LENGTH = 80;

    /**
     * Unconstrained: ConversationGateway is the sole gatekeeper that enforces D-09 ownership opacity
     * via JPQL ({@code where c.createdBy = :owner}); the entity-level CrudEntityConstraint is
     * redundant on top of that and would block legitimate same-user CRUD when the end-user role
     * lacks entity-CRUD grants on {@code ai_AiConversation}. Routing through
     * {@link UnconstrainedDataManager} keeps the manual ownership filter as the single source of
     * authorization truth — same architectural pattern as
     * {@link com.vn.agent.audit.AuditWriter} and
     * {@link ProjectingChatMemoryRepository}. See project memory
     * {@code feedback_jmix_unconstrained_for_system_writes} and
     * {@code 08-08-INVESTIGATION-2.md}.
     */
    private final UnconstrainedDataManager dataManager;
    private final Metadata metadata;

    public ConversationGateway(UnconstrainedDataManager dataManager, Metadata metadata) {
        this.dataManager = dataManager;
        this.metadata = metadata;
    }

    /**
     * Load an existing conversation (opaque ownership check) or create a new one with the first
     * user message truncated to 80 chars as the title.
     *
     * @param userId        caller identity — must be non-null / non-blank
     * @param conversationId id of an existing conversation, or {@code null} to auto-create
     * @param firstMessage  the user's current message; used to seed the title on auto-create
     * @return the loaded or freshly-saved {@link AiConversation}
     * @throws IllegalArgumentException if {@code userId} is null or blank
     * @throws ConversationNotFoundException if {@code conversationId} is non-null but the row is
     *         missing OR owned by a different user (D-09 opacity — indistinguishable)
     */
    public AiConversation loadOrCreate(String userId, UUID conversationId, String firstMessage) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be null or blank");
        }

        if (conversationId == null) {
            AiConversation fresh = metadata.create(AiConversation.class);
            fresh.setCreatedBy(userId);
            fresh.setCreatedDate(OffsetDateTime.now());
            if (firstMessage != null && !firstMessage.isBlank()) {
                String title = firstMessage.length() > TITLE_MAX_LENGTH
                        ? firstMessage.substring(0, TITLE_MAX_LENGTH)
                        : firstMessage;
                fresh.setTitle(title);
            } else {
                fresh.setTitle(null);
            }
            return dataManager.save(fresh);
        }

        // D-09 opacity — combine id AND createdBy in the SAME query so missing-row and
        // owned-by-another-user branches throw the identical exception.
        Optional<AiConversation> opt = dataManager.load(AiConversation.class)
                .query("select c from ai_AiConversation c where c.id = :id and c.createdBy = :owner")
                .parameter("id", conversationId)
                .parameter("owner", userId)
                .optional();
        return opt.orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }
}
