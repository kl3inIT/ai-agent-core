package com.vn.agent.tools;

import com.vn.agent.audit.AuditWriter;
import com.vn.agent.audit.ToolCallbackAuditDecorator;
import com.vn.agent.orchestration.StreamingSinkHolder;
import com.vn.agent.spi.ToolContributor;
import io.jmix.core.security.CurrentAuthentication;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-request tool-callback assembly (D-10). Phase 4's {@code ChatClientFactory} will call
 * {@link #forCurrentUser()} inside {@code ChatClient.prompt().toolCallbacks(...)} — NEVER the
 * builder-level defaults variant — because the effective schema the built-in tools see is
 * user-specific and {@link ToolContributor}s may gate on role.
 *
 * <p>Implementation note: the plan's {@code <interfaces>} block referenced a
 * {@code ToolCallbacks.from(Object)} helper. In Spring AI 1.1.4 that class does not exist; the
 * equivalent public API is
 * {@link MethodToolCallbackProvider}{@code .builder().toolObjects(bean).build().getToolCallbacks()}
 * which performs the same {@code @Tool}-method reflection pass. Behavior is identical; the
 * contract (fresh array per call, no caching) is preserved.</p>
 */
@Component
public class AgentToolCallbacks {

    private final BuiltInDataTools builtIns;
    private final List<ToolContributor> contributors;
    private final AuditWriter auditWriter;
    private final CurrentAuthentication currentAuthentication;
    private final StreamingSinkHolder streamingSinkHolder;

    public AgentToolCallbacks(BuiltInDataTools builtIns,
                              List<ToolContributor> contributors,
                              AuditWriter auditWriter,
                              CurrentAuthentication currentAuthentication,
                              StreamingSinkHolder streamingSinkHolder) {
        this.builtIns = builtIns;
        this.contributors = contributors;
        this.auditWriter = auditWriter;
        this.currentAuthentication = currentAuthentication;
        this.streamingSinkHolder = streamingSinkHolder;
    }

    /**
     * Build a fresh {@link ToolCallback} array per request (AUD-04). Every callback is wrapped in
     * a {@link ToolCallbackAuditDecorator} so each tool invocation produces PRE/POST audit rows
     * via the REQUIRES_NEW {@code AuditWriter} boundary — rows survive even when a tool rolls
     * back its own transaction. Do NOT cache the returned array — ToolContributor output and
     * effective schema can change across invocations.
     */
    public ToolCallback[] forCurrentUser() {
        List<ToolCallback> all = new ArrayList<>();
        Collections.addAll(all, fromBean(builtIns));
        for (ToolContributor tc : contributors) {
            List<Object> beans = tc.contribute();
            if (beans == null) {
                continue;
            }
            for (Object bean : beans) {
                Collections.addAll(all, fromBean(bean));
            }
        }
        ToolCallback[] out = new ToolCallback[all.size()];
        for (int i = 0; i < all.size(); i++) {
            out[i] = new ToolCallbackAuditDecorator(all.get(i), auditWriter, currentAuthentication, streamingSinkHolder);
        }
        return out;
    }

    /**
     * Per-request assembly used by {@code DefaultChatServiceImpl} (Plan 04-04 Task 3). The
     * {@code userId} and {@code conversationId} parameters are accepted for future per-user tool
     * filtering (Phase 5+); today the implementation delegates to {@link #forCurrentUser()}.
     */
    public ToolCallback[] callbacksFor(String userId, java.util.UUID conversationId) {
        return forCurrentUser();
    }

    /** Spring AI 1.1.4 replacement for the plan's {@code ToolCallbacks.from(bean)}. */
    private static ToolCallback[] fromBean(Object bean) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(bean)
                .build()
                .getToolCallbacks();
    }
}
