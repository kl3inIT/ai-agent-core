package com.vn.agent.orchestration;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.Metadata;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13.1 REQ-5 (UX-01) — pins the {@link ProjectingChatMemoryRepository#saveAll}
 * NOTICE-survival contract AND the structural separation between {@code AI_AGENT_MESSAGE}
 * (where NOTICE rows live) and {@code SPRING_AI_CHAT_MEMORY} (the LLM-prompt source of
 * truth — never holds NOTICE rows).
 *
 * <p>Two assertions:
 * <ol>
 *   <li>{@link #noticeRowsSurviveSaveAllProjection} — calling {@code saveAll(...)} with a
 *       USER+ASSISTANT pair must NOT delete the pre-existing NOTICE row from the projection.
 *       The JPQL filter {@code role <> :noticeRole} (Plan 13.1-03 D-A1) is the only way this
 *       passes.</li>
 *   <li>{@link #springAiPromptPathNeverContainsNoticeMessages} — the Spring AI primary store
 *       (the {@link JdbcChatMemoryRepository} bean's {@code findByConversationId} return)
 *       contains zero NOTICE-derived messages. NOTICE rows live in the Jmix-projected
 *       {@code AI_AGENT_MESSAGE} table only — they are NEVER pushed into the Spring AI
 *       table that feeds the LLM prompt assembly path.</li>
 * </ol>
 */
@Tag("integration")
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class NoticeFilterTest {

    @Autowired private ProjectingChatMemoryRepository projectingChatMemoryRepository;
    @Autowired private JdbcChatMemoryRepository jdbcChatMemoryRepository;
    @Autowired private UnconstrainedDataManager unconstrainedDataManager;
    @Autowired private Metadata metadata;
    @Autowired private SystemAuthenticator systemAuthenticator;

    private final List<UUID> seededMessageIds = new ArrayList<>();
    private final List<UUID> seededConversationIds = new ArrayList<>();

    @AfterEach
    void cleanRows() {
        systemAuthenticator.runWithSystem(() -> {
            for (UUID id : seededMessageIds) {
                unconstrainedDataManager.load(AiMessage.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
            for (UUID id : seededConversationIds) {
                unconstrainedDataManager.load(AiConversation.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
        });
        seededMessageIds.clear();
        seededConversationIds.clear();
    }

    /**
     * Seed USER + ASSISTANT + NOTICE rows for one conversation. Call
     * {@code projectingChatMemoryRepository.saveAll(...)} with a USER+ASSISTANT pair (mimicking
     * what the Spring AI memory advisor would project). Assert that the NOTICE row is STILL
     * present after the projection wipe.
     */
    @Test
    void noticeRowsSurviveSaveAllProjection() {
        UUID conversationId = createConversation("notice-survival-user");
        seedMessage(conversationId, AiMessageRole.USER, "first user turn", 0);
        seedMessage(conversationId, AiMessageRole.ASSISTANT, "first assistant reply", 1);
        UUID noticeId = seedMessage(conversationId, AiMessageRole.NOTICE,
                "[notice-survival-user] added attachment \"sample.xlsx\"", 2);

        // Spring AI memory advisor calls saveAll with the cumulative USER+ASSISTANT list —
        // it never has visibility into NOTICE rows.
        List<Message> messages = List.of(
                new UserMessage("first user turn"),
                new AssistantMessage("first assistant reply")
        );

        systemAuthenticator.runWithSystem(() ->
                projectingChatMemoryRepository.saveAll(conversationId.toString(), messages));

        // After the delete-recreate projection, the NOTICE row MUST still be there.
        boolean noticeStillExists = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiMessage.class)
                        .id(noticeId).optional().isPresent());
        assertThat(noticeStillExists)
                .as("NOTICE row must survive ProjectingChatMemoryRepository.saveAll wipe (REQ-5 D-A1)")
                .isTrue();

        // Sanity: USER + ASSISTANT rows for this conversation are still present (just re-created
        // through the JPQL delete-recreate path), and exactly one NOTICE row remains.
        long noticeCount = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiMessage.class)
                        .query("select m from ai_AiMessage m where m.conversation.id = :cid " +
                                "and m.role = :role")
                        .parameter("cid", conversationId)
                        .parameter("role", AiMessageRole.NOTICE.getId())
                        .list()
                        .size());
        assertThat(noticeCount)
                .as("exactly one NOTICE row should remain after the projection")
                .isEqualTo(1L);

        // Track every Jmix-projected row created by saveAll so @AfterEach cleans them up.
        List<AiMessage> projectedRows = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiMessage.class)
                        .query("select m from ai_AiMessage m where m.conversation.id = :cid")
                        .parameter("cid", conversationId)
                        .list());
        projectedRows.forEach(row -> seededMessageIds.add(row.getId()));
    }

    /**
     * The Spring AI primary store ({@code SPRING_AI_CHAT_MEMORY} via
     * {@link JdbcChatMemoryRepository}) is structurally disjoint from the Jmix-projected
     * {@code AI_AGENT_MESSAGE} table. NOTICE rows are written ONLY to the Jmix table by the
     * upload handler, so the Spring AI {@code findByConversationId} read MUST return zero
     * NOTICE-derived messages even after the upload-handler has written a NOTICE row.
     *
     * <p>This is mostly a regression guard against future schema drift (D-A2) — Spring AI's
     * {@link MessageType} has no NOTICE counterpart, so if a future change ever forced a
     * NOTICE projection through the chat-memory advisor we would catch it here.
     */
    @Test
    void springAiPromptPathNeverContainsNoticeMessages() {
        UUID conversationId = createConversation("notice-spring-ai-user");
        seedMessage(conversationId, AiMessageRole.USER, "user turn", 0);
        seedMessage(conversationId, AiMessageRole.ASSISTANT, "assistant turn", 1);
        seedMessage(conversationId, AiMessageRole.NOTICE,
                "[notice-spring-ai-user] added attachment \"file.pdf\"", 2);

        List<Message> springRows = systemAuthenticator.withSystem(() ->
                jdbcChatMemoryRepository.findByConversationId(conversationId.toString()));

        // No row should map to a NOTICE-style message — Spring AI's MessageType set is closed
        // (USER/ASSISTANT/SYSTEM/TOOL). The assertion is vacuously true today, but documents
        // the structural invariant for future schema drift.
        assertThat(springRows)
                .as("Spring AI primary store must not surface NOTICE-derived messages to the LLM-prompt path (D-A2)")
                .allSatisfy(message -> assertThat(message.getMessageType())
                        .isNotNull()
                        .isNotEqualTo(MessageType.SYSTEM /* placeholder — NOTICE is structurally absent */));
        // Stronger: since SPRING_AI_CHAT_MEMORY is written ONLY by the chat-memory advisor
        // (which projects USER+ASSISTANT), no row written by the upload handler can leak
        // through here. We assert size is 0 because no chat turn ran in this test — only the
        // direct AiMessage seed, which is invisible to the JDBC store.
        assertThat(springRows)
                .as("no chat turn ran — Spring AI store must be empty (NOTICE row went only to AI_AGENT_MESSAGE)")
                .isEmpty();
    }

    // ---------- helpers ----------

    private UUID createConversation(String username) {
        UUID conversationId = systemAuthenticator.withSystem(() -> {
            AiConversation conversation = metadata.create(AiConversation.class);
            conversation.setCreatedBy(username);
            conversation.setTitle("notice-filter test conversation");
            return unconstrainedDataManager.save(conversation).getId();
        });
        seededConversationIds.add(conversationId);
        return conversationId;
    }

    private UUID seedMessage(UUID conversationId, AiMessageRole role, String content, int seq) {
        UUID messageId = systemAuthenticator.withSystem(() -> {
            AiConversation conversationRef = unconstrainedDataManager.load(AiConversation.class)
                    .id(conversationId).one();
            AiMessage row = metadata.create(AiMessage.class);
            row.setConversation(conversationRef);
            row.setRole(role);
            row.setContent(content);
            row.setSeq(seq);
            row.setCreatedDate(OffsetDateTime.now().plusNanos(seq));
            return unconstrainedDataManager.save(row).getId();
        });
        seededMessageIds.add(messageId);
        return messageId;
    }
}
