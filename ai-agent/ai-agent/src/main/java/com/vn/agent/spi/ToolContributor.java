package com.vn.agent.spi;

import java.util.List;

/**
 * Host extension point for contributing additional {@code @Tool}-annotated beans to the agent.
 * <p>Spring AI 2.x tool-callback resolution consumes every ToolContributor bean in the
 * application context; returned beans' {@code @Tool} methods are exposed via
 * {@code ToolCallbacks.from(bean)}.</p>
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * @Component
 * class CrmTools implements ToolContributor {
 *     @Override public List<Object> contribute() { return List.of(this); }
 *
 *     @Tool(description = "Look up a CRM contact by email")
 *     public Contact lookup(@ToolParam String email) { ... }
 * }
 * }</pre>
 */
public interface ToolContributor {
    /** @return beans whose {@code @Tool} methods should be exposed; empty list = no contribution. */
    List<Object> contribute();
}
