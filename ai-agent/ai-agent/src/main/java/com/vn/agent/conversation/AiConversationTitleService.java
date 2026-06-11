package com.vn.agent.conversation;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.AiUiSettingsResolver;
import io.jmix.core.UnconstrainedDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "jmix.ai-agent.conversation-title", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AiConversationTitleService {

    static final String EVENT_NAME = "conversation_title";
    private static final Logger log = LoggerFactory.getLogger(AiConversationTitleService.class);
    private static final int TITLE_MAX_LENGTH = 80;
    private static final String PROMPT_PATH =
            "com/vn/agent/prompts/ai-conversation-title-system-prompt.st";

    private final UnconstrainedDataManager dataManager;
    private final ChatClient.Builder chatClientBuilder;
    private final AiAgentTitleProperties properties;
    private final AuditWriter auditWriter;
    private final AiUiSettingsResolver aiUiSettingsResolver;

    public AiConversationTitleService(UnconstrainedDataManager dataManager,
                                      ChatClient.Builder chatClientBuilder,
                                      AiAgentTitleProperties properties,
                                      AuditWriter auditWriter,
                                      AiUiSettingsResolver aiUiSettingsResolver) {
        this.dataManager = dataManager;
        this.chatClientBuilder = chatClientBuilder;
        this.properties = properties;
        this.auditWriter = auditWriter;
        this.aiUiSettingsResolver = aiUiSettingsResolver;
    }

    /**
     * The existing chat runtime publishes this event only after the Spring AI call/advisor chain
     * has returned. That path is non-transactional at this service boundary, so
     * fallbackExecution=true is deliberate: if a future publisher emits inside a transaction the
     * listener waits for AFTER_COMMIT; today it still runs after the durable chat call has returned.
     */
    @Async("aiAgentTitleExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onConversationTitleEligible(ConversationTitleEligibleEvent event) {
        long startNanos = System.nanoTime();
        try {
            Optional<AiConversation> loadedConversation = loadConversation(event.conversationId());
            if (loadedConversation.isEmpty()) {
                return;
            }

            AiConversation conversation = loadedConversation.get();
            List<AiMessage> visibleMessages = visibleMessages(loadVisibleMessages(event.conversationId()));
            if (!isAssistantCountEligible(visibleMessages)) {
                return;
            }

            Optional<String> defaultTitle = defaultTitle(visibleMessages);
            if (defaultTitle.isEmpty()) {
                return;
            }
            // A conversation is eligible when its current title is either unset (upload-first
            // path created the row before the user typed) or already matches the truncated
            // first-user-message default. Anything else means the user manually edited and
            // we must not clobber.
            String existingTitle = conversation.getTitle();
            boolean existingIsBlank = existingTitle == null || existingTitle.isBlank();
            if (!existingIsBlank && !sameTitle(existingTitle, defaultTitle.get())) {
                return;
            }
            // Seed unseeded conversations with the defaultTitle BEFORE the LLM call so a blank
            // / sentinel / forbidden model reply still leaves a usable title on the row instead
            // of a permanently empty entry in the conversations grid.
            if (existingIsBlank) {
                conversation.setTitle(defaultTitle.get());
                saveConversation(conversation);
            }

            Locale locale = event.localeHint() == null ? Locale.getDefault() : event.localeHint();
            String systemPrompt = renderSystemPrompt(locale);
            String userPrompt = renderUserPrompt(event.conversationId(), locale, visibleMessages);
            String rawTitle = callTitleModel(systemPrompt, userPrompt);
            String title = sanitizeTitle(rawTitle, defaultTitle.get());

            Optional<AiConversation> reloadedConversation = loadConversation(event.conversationId());
            if (reloadedConversation.isEmpty()) {
                return;
            }
            String currentTitle = reloadedConversation.get().getTitle();
            boolean currentIsBlankOrDefault = currentTitle == null || currentTitle.isBlank()
                    || sameTitle(currentTitle, defaultTitle.get());
            if (!currentIsBlankOrDefault) {
                return;
            }

            AiConversation conversationToSave = reloadedConversation.get();
            conversationToSave.setTitle(title);
            saveConversation(conversationToSave);
            audit(event, title, elapsedMillis(startNanos), AiToolCallOutcome.SUCCESS, null, null);
        } catch (IllegalArgumentException rejectedTitle) {
            // Phase 13.1 UAT-fix-02 — auto-title generation is best-effort. When the
            // model returns blank / sentinel / forbidden tokens, we DO NOT escalate to
            // an ERROR audit row — the conversation just keeps its default title.
            // Recording these as ERROR pollutes the audit log with red rows and is
            // confusing to operators who see a "Lỗi" (error) status next to a
            // conversation that completed perfectly fine with its default title.
            log.debug("Auto-title generation rejected output conversationId={} reason={}",
                    event.conversationId(), rejectedTitle.getMessage());
            audit(event, null, elapsedMillis(startNanos), AiToolCallOutcome.SUCCESS,
                    "rejected_title:" + rejectedTitle.getMessage(), null);
        } catch (RuntimeException failure) {
            log.warn("Auto-title generation failed conversationId={}",
                    event.conversationId(), failure);
            audit(event, null, elapsedMillis(startNanos), AiToolCallOutcome.ERROR,
                    "title_generation_failed", failure.getClass().getSimpleName());
        }
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

    void saveConversation(AiConversation conversation) {
        dataManager.save(conversation);
    }

    String callTitleModel(String systemPrompt, String userPrompt) {
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .temperature(0.0)
                .maxTokens(32)
                .toolCallbacks(List.of())
                .toolNames()
                .internalToolExecutionEnabled(false);
        String modelId = properties.resolvedModelId();
        if (modelId != null) {
            optionsBuilder.model(modelId);
        }
        OpenAiChatOptions titleOptions = optionsBuilder.build();

        ChatClient titleClient = chatClientBuilder.clone()
                .defaultSystem(systemPrompt)
                .defaultOptions(titleOptions)
                .defaultAdvisors(List.of())
                .defaultToolCallbacks(List.of())
                .build();

        return titleClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .advisors(List.of())
                .toolCallbacks(List.of())
                .options(titleOptions)
                .call()
                .content();
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

    private boolean isAssistantCountEligible(List<AiMessage> visibleMessages) {
        long assistantMessages = visibleMessages.stream()
                .filter(message -> message.getRole() == AiMessageRole.ASSISTANT)
                .count();
        // Phase 16 CFG-01 — read via resolver so admin edits on AiUiSettings take
        // effect without a restart. Null column falls through to property default.
        return assistantMessages == aiUiSettingsResolver.resolveTitleMinAssistantMessagesTrigger();
    }

    private Optional<String> defaultTitle(List<AiMessage> visibleMessages) {
        return visibleMessages.stream()
                .filter(message -> message.getRole() == AiMessageRole.USER)
                .map(AiMessage::getContent)
                .filter(content -> content != null && !content.isBlank())
                .findFirst()
                .map(this::truncateTitle);
    }

    private String renderSystemPrompt(Locale locale) {
        String language = locale == null ? "en" : locale.getLanguage();
        String localeInstruction = "vi".equalsIgnoreCase(language)
                ? "Return Vietnamese when the conversation content is Vietnamese; otherwise English."
                : "Return English unless the conversation content is clearly Vietnamese.";
        try {
            String template = new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
            return template.replace("{localeInstruction}", localeInstruction);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to load title prompt template", failure);
        }
    }

    private String renderUserPrompt(UUID conversationId, Locale locale, List<AiMessage> visibleMessages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("conversationId=").append(conversationId).append('\n');
        prompt.append("locale=").append(locale == null ? Locale.getDefault().toLanguageTag() : locale.toLanguageTag())
                .append('\n');
        prompt.append("messages:\n");
        visibleMessages.stream()
                // Phase 16 CFG-01 — resolver fall-through; null column → property default.
                .limit(aiUiSettingsResolver.resolveTitleMaxContextMessages())
                .forEach(message -> prompt
                        .append(message.getRole() == AiMessageRole.USER ? "USER: " : "ASSISTANT: ")
                        .append(cleanPromptText(message.getContent()))
                        .append('\n'));
        return prompt.toString();
    }

    private String sanitizeTitle(String rawTitle, String defaultTitle) {
        String title = rawTitle == null ? "" : rawTitle;
        title = title.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        title = title.replaceAll("\\s+", " ").strip();
        title = stripBoundaryQuotes(title).strip();
        while (title.endsWith(".")) {
            title = title.substring(0, title.length() - 1).strip();
        }
        title = truncateTitle(title);
        if (title.isBlank()) {
            throw new IllegalArgumentException("blank title");
        }
        if (sameTitle(title, defaultTitle)) {
            throw new IllegalArgumentException("default title");
        }
        if ("NEW_CONVERSATION".equalsIgnoreCase(title)) {
            throw new IllegalArgumentException("default sentinel");
        }
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        for (String forbiddenToken : forbiddenTitleTokens()) {
            if (lowerTitle.contains(forbiddenToken)) {
                throw new IllegalArgumentException("internal title token");
            }
        }
        return title;
    }

    private String stripBoundaryQuotes(String value) {
        String stripped = value;
        while (stripped.length() >= 2
                && isQuote(stripped.charAt(0))
                && isQuote(stripped.charAt(stripped.length() - 1))) {
            stripped = stripped.substring(1, stripped.length() - 1).strip();
        }
        return stripped;
    }

    private boolean isQuote(char value) {
        return value == '"' || value == '\'' || value == '`'
                || value == '\u201c' || value == '\u201d'
                || value == '\u2018' || value == '\u2019';
    }

    private List<String> forbiddenTitleTokens() {
        return List.of("find_records", "list_entities", "describe_entity", "link_record",
                "create_record", "update_record", "openrouter", "openai", "chatgpt",
                "gpt-", "claude", "ai_agent", "ai_");
    }

    private String cleanPromptText(String content) {
        if (content == null) {
            return "";
        }
        return content.replaceAll("\\s+", " ").strip();
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

    private void audit(ConversationTitleEligibleEvent event,
                       String title,
                       long latencyMs,
                       AiToolCallOutcome outcome,
                       String denialReason,
                       String errorClass) {
        try {
            auditWriter.writeToolCall(null, event.runId() == null ? UUID.randomUUID() : event.runId(),
                    event.userUsername(), event.conversationId(), EVENT_NAME, auditArguments(event),
                    title, latencyMs, outcome, denialReason, errorClass);
        } catch (RuntimeException auditFailure) {
            log.warn("Auto-title audit failed conversationId={}", event.conversationId(), auditFailure);
        }
    }

    private String auditArguments(ConversationTitleEligibleEvent event) {
        String resolvedModelId = properties.resolvedModelId();
        String model = resolvedModelId == null ? "default" : resolvedModelId;
        String locale = event.localeHint() == null ? Locale.getDefault().toLanguageTag()
                : event.localeHint().toLanguageTag();
        return "{\"model\":\"" + jsonEscape(model)
                + "\",\"maxContextMessages\":" + aiUiSettingsResolver.resolveTitleMaxContextMessages()
                + ",\"locale\":\"" + jsonEscape(locale) + "\"}";
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
