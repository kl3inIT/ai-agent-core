package com.vn.agent.tools;

import com.vn.agent.spi.ToolContributor;
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

    public AgentToolCallbacks(BuiltInDataTools builtIns, List<ToolContributor> contributors) {
        this.builtIns = builtIns;
        this.contributors = contributors;
    }

    /**
     * Build a fresh {@link ToolCallback} array per request. Do NOT cache the returned array —
     * ToolContributor output and effective schema can change across invocations.
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
        return all.toArray(ToolCallback[]::new);
    }

    /** Spring AI 1.1.4 replacement for the plan's {@code ToolCallbacks.from(bean)}. */
    private static ToolCallback[] fromBean(Object bean) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(bean)
                .build()
                .getToolCallbacks();
    }
}
