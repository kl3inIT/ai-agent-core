package com.vn.agent.audit;

import com.vn.agent.AITestConfiguration;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.orchestration.StreamingEvent;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.mutation.MutationToolTestUsersConfiguration;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.lang.NonNull;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(classes = AITestConfiguration.class,
        properties = {
                "ai-agent.tools.mutation.enabled=true",
                "jmix.ai-agent.audit.hash-sensitive-fields=true",
                "jmix.ai-agent.audit.sensitive-fields=secret"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class MutationToolCallbackBoundaryDecoratorSanitizerTest {

    private static final String USERNAME = "mutation-user";
    private static final String RAW_SECRET = "raw-secret-value";
    private static final String RAW_JSON_INPUT = "{\"entityName\":\"mutationTest_MutationTestFixture\","
            + "\"attributes\":{\"name\":\"Alice\",\"secret\":\"raw-secret-value\"},"
            + "\"idempotencyKey\":\"00000000-0000-0000-0000-000000000001\"}";
    private static final String RAW_OBJECT_SECRET_INPUT = "{\"entityName\":\"mutationTest_MutationTestFixture\","
            + "\"attributes\":{\"name\":\"Alice\",\"secret\":{\"nested\":\"raw-secret-value\"}},"
            + "\"idempotencyKey\":\"00000000-0000-0000-0000-000000000001\"}";
    private static final String RAW_ARRAY_SECRET_INPUT = "{\"entityName\":\"mutationTest_MutationTestFixture\","
            + "\"attributes\":{\"name\":\"Alice\",\"secret\":[\"raw-secret-value\"]},"
            + "\"idempotencyKey\":\"00000000-0000-0000-0000-000000000001\"}";
    private static final String INVALID_INPUT = "secret=raw-secret-value";
    private static final String UNPARSEABLE_PLACEHOLDER =
            "{\"sanitized\":true,\"reason\":\"unparseable_mutation_arguments\"}";

    @Autowired
    private AuditWriter auditWriter;

    @Autowired
    private CurrentAuthentication currentAuthentication;

    @Autowired
    private StreamingSinkHolder streamingSinkHolder;

    @Autowired
    private MutationArgumentSanitizer mutationArgumentSanitizer;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private UUID isolatedRunId;
    private UUID isolatedConversationId;
    private UUID isolatedRootAuditId;
    private List<StreamingEvent> events;
    private Disposable subscription;

    @BeforeEach
    void setUpStreamingSink() {
        isolatedRunId = UUID.randomUUID();
        isolatedConversationId = UUID.randomUUID();
        isolatedRootAuditId = UUID.randomUUID();
        events = new CopyOnWriteArrayList<>();
        Sinks.Many<StreamingEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        subscription = sink.asFlux().subscribe(events::add);
        streamingSinkHolder.register(isolatedRunId, sink);
    }

    @AfterEach
    void cleanRowsAndRunContext() {
        RunContext.clear();
        streamingSinkHolder.unregister(isolatedRunId);
        if (subscription != null) {
            subscription.dispose();
        }
        if (isolatedRunId != null) {
            systemAuthenticator.runWithSystem(() -> {
                List<AiAuditEvent> children = unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.runId = :rid and a.parent is not null")
                        .parameter("rid", isolatedRunId)
                        .list();
                for (AiAuditEvent row : children) {
                    unconstrainedDataManager.remove(row);
                }
                List<AiAuditEvent> roots = unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.runId = :rid and a.parent is null")
                        .parameter("rid", isolatedRunId)
                        .list();
                for (AiAuditEvent row : roots) {
                    unconstrainedDataManager.remove(row);
                }
            });
        }
    }

    @Test
    void jsonMutationInput_sanitizesStreamingArgsAndFallbackAuditArgs() {
        AtomicReference<String> delegateInput = new AtomicReference<>();
        ToolCallback wrapper = wrapThrowingCreateRecordDelegate(delegateInput);

        Throwable thrown = invokeAsMutationUser(wrapper, RAW_JSON_INPUT);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        assertThat(delegateInput).hasValue(RAW_JSON_INPUT);
        String expectedHash = AuditFieldHasher.sha256Hex(RAW_SECRET);

        StreamingEvent.ToolCall toolCall = onlyToolCall();
        assertThat(toolCall.argsJson()).contains(expectedHash);
        assertThat(toolCall.argsJson()).doesNotContain(RAW_SECRET);

        AiAuditEvent row = onlyAuditRow();
        assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.ERROR);
        assertThat(row.getArgumentsJson()).contains(expectedHash);
        assertThat(row.getArgumentsJson()).doesNotContain(RAW_SECRET);
    }

    @Test
    void invalidMutationInput_usesFailClosedPlaceholderForStreamingAndFallbackAudit() {
        AtomicReference<String> delegateInput = new AtomicReference<>();
        ToolCallback wrapper = wrapThrowingCreateRecordDelegate(delegateInput);

        Throwable thrown = invokeAsMutationUser(wrapper, INVALID_INPUT);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        assertThat(delegateInput).hasValue(INVALID_INPUT);

        StreamingEvent.ToolCall toolCall = onlyToolCall();
        assertThat(toolCall.argsJson()).isEqualTo(UNPARSEABLE_PLACEHOLDER);
        assertThat(toolCall.argsJson()).contains("unparseable_mutation_arguments");
        assertThat(toolCall.argsJson()).doesNotContain(RAW_SECRET);

        AiAuditEvent row = onlyAuditRow();
        assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.ERROR);
        assertThat(row.getArgumentsJson()).isEqualTo(UNPARSEABLE_PLACEHOLDER);
        assertThat(row.getArgumentsJson()).contains("unparseable_mutation_arguments");
        assertThat(row.getArgumentsJson()).doesNotContain(RAW_SECRET);
    }

    @Test
    void objectSensitiveField_hashesWholeJsonValueForStreamingAndFallbackAudit() {
        assertSensitiveJsonValueIsHashed(
                RAW_OBJECT_SECRET_INPUT,
                AuditFieldHasher.sha256Hex("{\"nested\":\"raw-secret-value\"}"));
    }

    @Test
    void arraySensitiveField_hashesWholeJsonValueForStreamingAndFallbackAudit() {
        assertSensitiveJsonValueIsHashed(
                RAW_ARRAY_SECRET_INPUT,
                AuditFieldHasher.sha256Hex("[\"raw-secret-value\"]"));
    }

    @Test
    void toolContextInstallsRunContextSoSelfAuditIsNestedUnderChatRoot() {
        UUID rootAuditId = systemAuthenticator.withSystem(() ->
                auditWriter.writeChatStart(isolatedRunId, USERNAME, null, "prompt-hash"));
        ToolCallback wrapper = wrapSelfAuditingCreateRecordDelegate();

        String result = systemAuthenticator.withUser(USERNAME, () ->
                wrapper.call("{}", new ToolContext(Map.of(
                        RunContext.TOOL_CONTEXT_RUN_ID_KEY, isolatedRunId,
                        RunContext.TOOL_CONTEXT_CONVERSATION_ID_KEY, isolatedConversationId))));

        assertThat(result).contains("\"outcome\":\"SUCCESS\"");
        assertThat(RunContext.get()).isNull();
        AiAuditEvent row = onlyAuditRow();
        assertThat(row.getRunId()).isEqualTo(isolatedRunId);
        assertThat(row.getResultSummary()).contains("\"outcome\":\"SUCCESS\"");
        assertThat(parentIdOfOnlyAuditRow()).isEqualTo(rootAuditId);
    }

    private MutationToolCallbackBoundaryDecorator wrapThrowingCreateRecordDelegate(
            AtomicReference<String> delegateInput) {
        ToolCallback delegate = new ToolCallback() {
            @Override
            @NonNull
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("create_record")
                        .description("Throws before the mutation tool body")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            @NonNull
            public String call(@NonNull String toolInput) {
                delegateInput.set(toolInput);
                throw new IllegalArgumentException("binding failed before method body");
            }

            @Override
            @NonNull
            public String call(@NonNull String toolInput, ToolContext toolContext) {
                delegateInput.set(toolInput);
                throw new IllegalArgumentException("binding failed before method body");
            }
        };
        return new MutationToolCallbackBoundaryDecorator(delegate, streamingSinkHolder, auditWriter,
                currentAuthentication, mutationArgumentSanitizer);
    }

    private MutationToolCallbackBoundaryDecorator wrapSelfAuditingCreateRecordDelegate() {
        ToolCallback delegate = new ToolCallback() {
            @Override
            @NonNull
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("create_record")
                        .description("Self-audits like BuiltInMutationTools")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            @NonNull
            public String call(@NonNull String toolInput) {
                return call(toolInput, new ToolContext(Map.of()));
            }

            @Override
            @NonNull
            public String call(@NonNull String toolInput, ToolContext toolContext) {
                String summary = "{\"outcome\":\"SUCCESS\"}";
                auditWriter.writeToolCall(
                        RunContext.getRootAuditId(),
                        RunContext.get(),
                        USERNAME,
                        RunContext.getConversationId(),
                        "create_record",
                        toolInput,
                        summary,
                        1L,
                        AiToolCallOutcome.SUCCESS,
                        null,
                        null);
                return summary;
            }
        };
        return new MutationToolCallbackBoundaryDecorator(delegate, streamingSinkHolder, auditWriter,
                currentAuthentication, mutationArgumentSanitizer);
    }

    private Throwable invokeAsMutationUser(ToolCallback wrapper, String input) {
        return catchThrowable(() -> systemAuthenticator.withUser(USERNAME, () -> {
            RunContext.set(isolatedRunId);
            RunContext.setConversationId(isolatedConversationId);
            RunContext.setRootAuditId(isolatedRootAuditId);
            try {
                wrapper.call(input);
                return null;
            } finally {
                RunContext.clear();
            }
        }));
    }

    private void assertSensitiveJsonValueIsHashed(String rawInput, String expectedHash) {
        AtomicReference<String> delegateInput = new AtomicReference<>();
        ToolCallback wrapper = wrapThrowingCreateRecordDelegate(delegateInput);

        Throwable thrown = invokeAsMutationUser(wrapper, rawInput);

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        assertThat(delegateInput).hasValue(rawInput);

        StreamingEvent.ToolCall toolCall = onlyToolCall();
        assertThat(toolCall.argsJson()).contains(expectedHash);
        assertThat(toolCall.argsJson()).doesNotContain(RAW_SECRET);

        AiAuditEvent row = onlyAuditRow();
        assertThat(row.getOutcome()).isEqualTo(AiToolCallOutcome.ERROR);
        assertThat(row.getArgumentsJson()).contains(expectedHash);
        assertThat(row.getArgumentsJson()).doesNotContain(RAW_SECRET);
    }

    private StreamingEvent.ToolCall onlyToolCall() {
        List<StreamingEvent.ToolCall> toolCalls = events.stream()
                .filter(StreamingEvent.ToolCall.class::isInstance)
                .map(StreamingEvent.ToolCall.class::cast)
                .toList();
        assertThat(toolCalls).hasSize(1);
        return toolCalls.get(0);
    }

    private AiAuditEvent onlyAuditRow() {
        List<AiAuditEvent> rows = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.runId = :rid and a.eventName = :n")
                        .parameter("rid", isolatedRunId)
                        .parameter("n", "create_record")
                        .list());
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private UUID parentIdOfOnlyAuditRow() {
        return systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.loadValue(
                                "select a.parent.id from ai_AiAuditEvent a where a.runId = :rid and a.eventName = :n",
                                UUID.class)
                        .store("agentstore")
                        .parameter("rid", isolatedRunId)
                        .parameter("n", "create_record")
                        .one());
    }
}
