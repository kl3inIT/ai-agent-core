package com.vn.agent.guard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.audit.AuditWriter;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.spi.ToolGuard;
import com.vn.agent.spi.ToolVetoedException;
import io.jmix.core.security.CurrentAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Decorates Spring AI's {@link ToolCallingManager} with two guard responsibilities:
 * <ol>
 *   <li><b>Iteration cap</b> (GUARD-03, D-16): every time the LLM asks the framework to run a
 *       round of tools, {@link IterationCounter#increment()} is bumped; once the new value
 *       exceeds the configured maximum (default 6), an {@link IterationCapExceededException} is
 *       thrown. The request-level audit row uses sentinel tool name {@code "__chat__"} (D-11).</li>
 *   <li><b>Pre-tool veto</b> (GUARD-04, D-11): before delegating, every assistant-emitted tool
 *       call is offered to {@link ToolGuard#check(String, Map)}. A {@link ToolVetoedException}
 *       produces a BLOCKED audit row with the REAL tool name + denial reason, then the exception
 *       is re-thrown so the chat-level advisor records the error and surfaces an i18n denial to
 *       the caller.</li>
 * </ol>
 *
 * <p><b>Relationship to Phase 4's {@code ToolCallbackAuditDecorator}:</b> this decorator does
 * <em>not</em> replace the callback-level PRE/POST audit rows. Those run inside each individual
 * tool callback and survive delegate-side transaction rollback via {@code REQUIRES_NEW} on
 * {@link AuditWriter}. This manager runs <em>above</em> the callback layer and is responsible
 * only for iteration accounting and request-level denial auditing.</p>
 *
 * <p><b>Lifecycle:</b> {@link IterationCounter#start()} and {@link IterationCounter#reset()}
 * are called by the chat orchestrator (Plan 04's {@code DefaultChatServiceImpl}) — this decorator
 * never calls start/reset itself. The increment happens per invocation; a single turn with zero
 * tool rounds never enters this class's {@code executeToolCalls}.</p>
 *
 * <p>Not a Spring {@code @Component} — wired as a {@code @Primary} bean by
 * {@code AiAgentGuardAutoConfiguration} that takes the framework's default ToolCallingManager as
 * its delegate.</p>
 */
public class GuardedToolCallingManager implements ToolCallingManager {

    /**
     * Sentinel tool name written into the audit row when a <em>request-level</em> (not tool-level)
     * denial occurs — currently only the iteration-cap breach (D-11). Real tool-level denials
     * (ToolGuard veto) write the REAL tool name.
     */
    public static final String CHAT_SENTINEL_TOOL_NAME = "__chat__";

    private static final Logger log = LoggerFactory.getLogger(GuardedToolCallingManager.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() { };
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCallingManager delegate;
    private final AiAgentGuardProperties props;
    private final ToolGuard toolGuard;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;

    public GuardedToolCallingManager(ToolCallingManager delegate,
                                     AiAgentGuardProperties props,
                                     ToolGuard toolGuard,
                                     AuditWriter auditWriter,
                                     CurrentAuthentication currentAuthentication) {
        this.delegate = delegate;
        this.props = props;
        this.toolGuard = toolGuard;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        // Pure delegation — definition resolution happens before any LLM call and is not a
        // policy decision point.
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        int next = IterationCounter.increment();
        int cap = props.resolvedMaxIterations();
        if (next > cap) {
            final String username = resolveUsername();
            safeAudit(() -> auditWriter.writeToolCall(
                    RunContext.getRootAuditId(),
                    RunContext.get(), username, /* conversationId */ null,
                    CHAT_SENTINEL_TOOL_NAME, /* argumentsJson */ null, /* resultSummary */ null,
                    0L, AiToolCallOutcome.BLOCKED, "iteration-cap-exceeded",
                    /* errorClass */ null));
            throw new IterationCapExceededException(cap);
        }

        // Offer every assistant-emitted tool call to ToolGuard before the delegate executes them.
        // The assistant message lives on the chatResponse's first generation output.
        List<AssistantMessage.ToolCall> toolCalls = extractToolCalls(chatResponse);
        for (AssistantMessage.ToolCall call : toolCalls) {
            String toolName = call.name();
            String argumentsJson = call.arguments();
            Map<String, Object> argumentsMap = parseArguments(argumentsJson);
            try {
                toolGuard.check(toolName, argumentsMap);
            } catch (ToolVetoedException veto) {
                final String username = resolveUsername();
                final String denialReason = "tool-vetoed:"
                        + (veto.getMessage() == null ? "" : veto.getMessage());
                safeAudit(() -> auditWriter.writeToolCall(
                        RunContext.getRootAuditId(),
                        RunContext.get(), username, /* conversationId */ null,
                        toolName, argumentsJson, /* resultSummary */ null, 0L,
                        AiToolCallOutcome.BLOCKED, denialReason,
                        /* errorClass */ null));
                throw veto;
            }
        }
        return delegate.executeToolCalls(prompt, chatResponse);
    }

    private static List<AssistantMessage.ToolCall> extractToolCalls(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return Collections.emptyList();
        }
        List<AssistantMessage.ToolCall> calls = chatResponse.getResult().getOutput().getToolCalls();
        return calls == null ? Collections.emptyList() : calls;
    }

    private static Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(argumentsJson, MAP_TYPE);
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception e) {
            // Malformed JSON from the LLM — pass an empty map so ToolGuard can still inspect the
            // tool name. Runtime tool execution will fail later against the real schema; guards
            // that need the raw text can inspect argumentsJson by reading the tool name alone.
            log.debug("Failed to parse tool-call arguments JSON; falling back to empty map", e);
            return Collections.emptyMap();
        }
    }

    private String resolveUsername() {
        try {
            UserDetails user = currentAuthentication.getUser();
            return user != null ? user.getUsername() : "anonymous";
        } catch (RuntimeException anon) {
            return "anonymous";
        }
    }

    private void safeAudit(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            // Best-effort: the chat-level PRE row from AuditAdvisor is the durable floor
            // (REQUIRES_NEW). Never let an audit failure mask the original guard decision.
            log.warn("Guard audit write failed", t);
        }
    }
}
