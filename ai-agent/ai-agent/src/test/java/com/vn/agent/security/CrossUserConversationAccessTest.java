package com.vn.agent.security;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.ChatService;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.orchestration.ChatResponseDto;
import com.vn.agent.orchestration.ConversationNotFoundException;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.metamodel.annotation.Store;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Plan 08-01 Task 2 — TEST-04 cross-user opacity assertions (CONTEXT D-03).
 *
 * <p>Two assertions covering the conversation-ownership boundary:
 * <ol>
 *   <li>{@code userB_replayingUserAConversation_throwsSameTypeAndCodeAsRandomUuid} —
 *       R-01e tightening: assert exception <em>type</em> equality (and message-string
 *       equality only as a stable opacity proxy, NOT as the primary contract). The
 *       opaque-error contract is owned by the exception type and its
 *       {@link ConversationNotFoundException#DEFAULT_MESSAGE} constant — i18n changes or
 *       log-format tweaks must not break this test.</li>
 *   <li>{@code userB_listingConversations_doesNotIncludeUserA_agentStoreScoped} —
 *       R-01c reinforcement: verifies {@link AiConversation} carries the {@link Store}
 *       annotation so {@code dataManager.load(Class)} can infer the {@code agentstore}
 *       data store; then asserts DataManager-level row-level opacity (bob does not see
 *       alice's row).</li>
 * </ol>
 *
 * <p>Mirrors {@code com.vn.agent.orchestration.OwnershipOpacityTest} but tightens R-01e
 * (type+code over message-string equality) and adds R-01c (explicit @Store proof).
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class})
class CrossUserConversationAccessTest {

    @Autowired ChatService chatService;
    @Autowired DataManager dataManager;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void userB_replayingUserAConversation_throwsSameTypeAndCodeAsRandomUuid() {
        // Create alice's conversation under alice's auth context. ChatService.ask scopes
        // ownership by the explicit userId argument; the security context here exercises
        // the surrounding pipeline (DataManager load constraints, audit user resolution).
        ChatResponseDto aliceResp = systemAuthenticator.withUser("alice",
                () -> chatService.ask("alice", null, "hello"));
        UUID aliceConvId = aliceResp.conversationId();
        assertThat(aliceConvId).as("Alice's conversation must be created").isNotNull();

        // Switch to bob and probe both branches.
        systemAuthenticator.withUser("bob", () -> {
            Throwable eForeign = catchThrowable(
                    () -> chatService.ask("bob", aliceConvId, "probe"));
            Throwable eRandom = catchThrowable(
                    () -> chatService.ask("bob", UUID.randomUUID(), "probe"));

            // R-01e: assert exception TYPE first (stable contract — i18n-immune, log-format-immune).
            assertThat(eForeign)
                    .as("Cross-user replay must throw ConversationNotFoundException")
                    .isInstanceOf(ConversationNotFoundException.class);
            assertThat(eRandom)
                    .as("Random-UUID replay must throw ConversationNotFoundException")
                    .isInstanceOf(ConversationNotFoundException.class);

            // Strict type equality (not just isInstanceOf — guards against subclass divergence).
            assertThat(eForeign.getClass())
                    .as("Both branches must throw the SAME exception class — no subclass leak")
                    .isEqualTo(eRandom.getClass());

            // Message-string equality is a STABILITY proxy: ConversationNotFoundException pins
            // its message to DEFAULT_MESSAGE for both branches (see ConversationNotFoundException
            // Javadoc + OwnershipOpacityTest analog). If a future error-code accessor is added
            // to ConversationNotFoundException, prefer that over message comparison.
            assertThat(eForeign.getMessage())
                    .as("Opacity: foreign-id and random-id messages must be byte-identical")
                    .isEqualTo(eRandom.getMessage());
            assertThat(eForeign.getMessage())
                    .as("Opacity: message must equal the canonical DEFAULT_MESSAGE constant")
                    .isEqualTo(ConversationNotFoundException.DEFAULT_MESSAGE);
            return null;
        });
    }

    @Test
    void userB_listingConversations_doesNotIncludeUserA_agentStoreScoped() {
        // Seed alice's conversation.
        ChatResponseDto aliceResp = systemAuthenticator.withUser("alice",
                () -> chatService.ask("alice", null, "hello"));
        UUID aliceConvId = aliceResp.conversationId();
        assertThat(aliceConvId).isNotNull();

        // R-01c: AiConversation must carry @Store("agentstore"). dataManager.load(Class)
        // infers the data store from this annotation; a future @Store removal would
        // silently re-route reads to the main store and break this test loudly.
        assertThat(AiConversation.class.isAnnotationPresent(Store.class))
                .as("AiConversation must carry @Store annotation; load(Class) inference depends on it")
                .isTrue();
        assertThat(AiConversation.class.getAnnotation(Store.class).name())
                .as("AiConversation must be in the 'agentstore' data store")
                .isEqualTo("agentstore");

        systemAuthenticator.withUser("bob", () -> {
            // DataManager-level opacity: row-level constraints on AiConversation (Phase 2's
            // AiAgentUserRowLevelRole) must hide alice's conversation from bob's view.
            List<AiConversation> bobsView = dataManager.load(AiConversation.class).all().list();
            assertThat(bobsView)
                    .as("DataManager-level opacity: bob must not observe alice's conversation rows")
                    .noneMatch(c -> aliceConvId.equals(c.getId()));
            return null;
        });
    }
}
