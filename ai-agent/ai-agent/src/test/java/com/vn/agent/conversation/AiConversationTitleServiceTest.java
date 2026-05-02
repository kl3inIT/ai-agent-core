package com.vn.agent.conversation;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.entity.AiToolCallOutcome;
import io.jmix.core.UnconstrainedDataManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AiConversationTitleServiceTest {

    @Test
    void listenerUsesBoundedAsyncExecutorAndAfterCommitBoundary() throws Exception {
        var method = AiConversationTitleService.class.getDeclaredMethod(
                "onConversationTitleEligible", ConversationTitleEligibleEvent.class);

        Async async = method.getAnnotation(Async.class);
        TransactionalEventListener eventListener = method.getAnnotation(TransactionalEventListener.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("aiAgentTitleExecutor");
        assertThat(eventListener).isNotNull();
        assertThat(eventListener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void successSanitizesPersistsAndAuditsGeneratedTitle() {
        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        AiConversation firstLoad = conversation(conversationId, "How many orders were created today?");
        AiConversation reloadBeforeSave = conversation(conversationId, "How many orders were created today?");
        TestTitleService service = serviceBuilder()
                .withLoadedConversations(firstLoad, reloadBeforeSave)
                .withMessages(
                        message(AiMessageRole.USER, "How many orders were created today?", 1),
                        message(AiMessageRole.ASSISTANT, "There were 42 orders today.", 2))
                .withModelTitle("\"Daily order count.\"")
                .build();

        service.onConversationTitleEligible(new ConversationTitleEligibleEvent(
                conversationId, "alice", runId, Locale.ENGLISH));

        assertThat(reloadBeforeSave.getTitle()).isEqualTo("Daily order count");
        assertThat(service.savedConversations).containsExactly(reloadBeforeSave);
        verify(service.auditWriter).writeToolCall(isNull(), eq(runId), eq("alice"), eq(conversationId),
                eq("conversation_title"), any(), eq("Daily order count"), anyLong(),
                eq(AiToolCallOutcome.SUCCESS), isNull(), isNull());
    }

    @Test
    void sanitizerRejectionDoesNotPersistAndAuditsError() {
        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        AiConversation conversation = conversation(conversationId, "Show recent customers");
        TestTitleService service = serviceBuilder()
                .withLoadedConversations(conversation)
                .withMessages(
                        message(AiMessageRole.USER, "Show recent customers", 1),
                        message(AiMessageRole.ASSISTANT, "Here are the customers.", 2))
                .withModelTitle("find_records")
                .build();

        service.onConversationTitleEligible(new ConversationTitleEligibleEvent(
                conversationId, "alice", runId, Locale.ENGLISH));

        assertThat(service.savedConversations).isEmpty();
        verify(service.auditWriter).writeToolCall(isNull(), eq(runId), eq("alice"), eq(conversationId),
                eq("conversation_title"), any(), isNull(), anyLong(),
                eq(AiToolCallOutcome.ERROR), eq("rejected_title"), eq("IllegalArgumentException"));
    }

    @Test
    void reloadBeforeSavePreventsManualTitleClobber() {
        UUID conversationId = UUID.randomUUID();
        AiConversation firstLoad = conversation(conversationId, "Summarize this account");
        AiConversation reloadBeforeSave = conversation(conversationId, "Manual account title");
        TestTitleService service = serviceBuilder()
                .withLoadedConversations(firstLoad, reloadBeforeSave)
                .withMessages(
                        message(AiMessageRole.USER, "Summarize this account", 1),
                        message(AiMessageRole.ASSISTANT, "The account is active.", 2))
                .withModelTitle("Active account summary")
                .build();

        service.onConversationTitleEligible(new ConversationTitleEligibleEvent(
                conversationId, "alice", UUID.randomUUID(), Locale.ENGLISH));

        assertThat(reloadBeforeSave.getTitle()).isEqualTo("Manual account title");
        assertThat(service.savedConversations).isEmpty();
        verify(service.auditWriter, never()).writeToolCall(any(), any(), any(), any(), any(), any(), any(),
                anyLong(), eq(AiToolCallOutcome.SUCCESS), any(), any());
    }

    @Test
    void modelFailureIsFailSilentAndAudited() {
        UUID conversationId = UUID.randomUUID();
        AiConversation conversation = conversation(conversationId, "Summarize revenue");
        TestTitleService service = serviceBuilder()
                .withLoadedConversations(conversation)
                .withMessages(
                        message(AiMessageRole.USER, "Summarize revenue", 1),
                        message(AiMessageRole.ASSISTANT, "Revenue is up.", 2))
                .withModelFailure(new IllegalStateException("provider unavailable"))
                .build();

        assertThatCode(() -> service.onConversationTitleEligible(new ConversationTitleEligibleEvent(
                conversationId, "alice", UUID.randomUUID(), Locale.ENGLISH)))
                .doesNotThrowAnyException();

        assertThat(service.savedConversations).isEmpty();
        verify(service.auditWriter).writeToolCall(isNull(), any(UUID.class), eq("alice"), eq(conversationId),
                eq("conversation_title"), any(), isNull(), anyLong(),
                eq(AiToolCallOutcome.ERROR), eq("title_generation_failed"), eq("IllegalStateException"));
    }

    @Test
    void localeHintIsRenderedIntoTitlePrompt() {
        UUID conversationId = UUID.randomUUID();
        AiConversation conversation = conversation(conversationId, "Tóm tắt khách hàng này");
        TestTitleService service = serviceBuilder()
                .withLoadedConversations(conversation, conversation(conversationId, "Tóm tắt khách hàng này"))
                .withMessages(
                        message(AiMessageRole.USER, "Tóm tắt khách hàng này", 1),
                        message(AiMessageRole.ASSISTANT, "Khách hàng có trạng thái tốt.", 2))
                .withModelTitle("Tóm tắt khách hàng")
                .build();

        service.onConversationTitleEligible(new ConversationTitleEligibleEvent(
                conversationId, "alice", UUID.randomUUID(), Locale.forLanguageTag("vi")));

        assertThat(service.capturedSystemPrompt).contains("Vietnamese");
        assertThat(service.capturedUserPrompt).contains("locale=vi");
    }

    @Test
    void skipsWhenAssistantMessageCountIsNotEligible() {
        UUID conversationId = UUID.randomUUID();
        AiConversation conversation = conversation(conversationId, "First question");
        TestTitleService service = serviceBuilder()
                .withLoadedConversations(conversation)
                .withMessages(
                        message(AiMessageRole.USER, "First question", 1),
                        message(AiMessageRole.ASSISTANT, "First answer", 2),
                        message(AiMessageRole.ASSISTANT, "Second answer", 3))
                .withModelTitle("First answer title")
                .build();

        service.onConversationTitleEligible(new ConversationTitleEligibleEvent(
                conversationId, "alice", UUID.randomUUID(), Locale.ENGLISH));

        assertThat(service.modelCallCount).isZero();
        assertThat(service.savedConversations).isEmpty();
    }

    private static TestTitleServiceBuilder serviceBuilder() {
        return new TestTitleServiceBuilder();
    }

    private static AiConversation conversation(UUID conversationId, String title) {
        AiConversation conversation = mock(AiConversation.class);
        AtomicReference<String> titleReference = new AtomicReference<>(title);
        org.mockito.Mockito.when(conversation.getId()).thenReturn(conversationId);
        org.mockito.Mockito.when(conversation.getTitle()).thenAnswer(invocation -> titleReference.get());
        doAnswer(invocation -> {
            titleReference.set(invocation.getArgument(0));
            return null;
        }).when(conversation).setTitle(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(conversation.getCreatedBy()).thenReturn("alice");
        org.mockito.Mockito.when(conversation.getCreatedDate()).thenReturn(OffsetDateTime.now());
        return conversation;
    }

    private static AiMessage message(AiMessageRole role, String content, int sequence) {
        AiMessage message = mock(AiMessage.class);
        org.mockito.Mockito.when(message.getRole()).thenReturn(role);
        org.mockito.Mockito.when(message.getContent()).thenReturn(content);
        org.mockito.Mockito.when(message.getSeq()).thenReturn(sequence);
        org.mockito.Mockito.when(message.getCreatedDate()).thenReturn(OffsetDateTime.now().plusSeconds(sequence));
        return message;
    }

    private static final class TestTitleServiceBuilder {
        private final List<AiConversation> loadedConversations = new ArrayList<>();
        private final List<AiMessage> messages = new ArrayList<>();
        private String modelTitle = "Generated title";
        private RuntimeException modelFailure;

        TestTitleServiceBuilder withLoadedConversations(AiConversation... conversations) {
            loadedConversations.addAll(List.of(conversations));
            return this;
        }

        TestTitleServiceBuilder withMessages(AiMessage... messages) {
            this.messages.addAll(List.of(messages));
            return this;
        }

        TestTitleServiceBuilder withModelTitle(String modelTitle) {
            this.modelTitle = modelTitle;
            return this;
        }

        TestTitleServiceBuilder withModelFailure(RuntimeException modelFailure) {
            this.modelFailure = modelFailure;
            return this;
        }

        TestTitleService build() {
            return new TestTitleService(mock(UnconstrainedDataManager.class),
                    mock(ChatClient.Builder.class),
                    new AiAgentTitleProperties(true, "title-model", 6, 1,
                            new AiAgentTitleProperties.Executor(1, 2, 32, 60)),
                    mock(AuditWriter.class),
                    loadedConversations,
                    messages,
                    modelTitle,
                    modelFailure);
        }
    }

    private static final class TestTitleService extends AiConversationTitleService {
        private final AuditWriter auditWriter;
        private final List<AiConversation> loadedConversations;
        private final List<AiMessage> messages;
        private final String modelTitle;
        private final RuntimeException modelFailure;
        private final List<AiConversation> savedConversations = new ArrayList<>();
        private String capturedSystemPrompt;
        private String capturedUserPrompt;
        private int modelCallCount;

        private TestTitleService(UnconstrainedDataManager dataManager,
                                 ChatClient.Builder chatClientBuilder,
                                 AiAgentTitleProperties properties,
                                 AuditWriter auditWriter,
                                 List<AiConversation> loadedConversations,
                                 List<AiMessage> messages,
                                 String modelTitle,
                                 RuntimeException modelFailure) {
            super(dataManager, chatClientBuilder, properties, auditWriter);
            this.auditWriter = auditWriter;
            this.loadedConversations = new ArrayList<>(loadedConversations);
            this.messages = List.copyOf(messages);
            this.modelTitle = modelTitle;
            this.modelFailure = modelFailure;
        }

        @Override
        Optional<AiConversation> loadConversation(UUID conversationId) {
            if (loadedConversations.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(loadedConversations.remove(0));
        }

        @Override
        List<AiMessage> loadVisibleMessages(UUID conversationId) {
            return messages;
        }

        @Override
        void saveConversation(AiConversation conversation) {
            savedConversations.add(conversation);
        }

        @Override
        String callTitleModel(String systemPrompt, String userPrompt) {
            modelCallCount++;
            capturedSystemPrompt = systemPrompt;
            capturedUserPrompt = userPrompt;
            if (modelFailure != null) {
                throw modelFailure;
            }
            return modelTitle;
        }
    }
}
