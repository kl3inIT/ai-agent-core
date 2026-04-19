package com.vn.agent.spi;

import java.util.Map;

/**
 * Host extension point that can veto a tool invocation before it runs.
 * <p>Multiple guards compose by short-circuit AND: any guard throwing {@link ToolVetoedException}
 * blocks the call and produces an {@code AiToolCallAudit} row with
 * {@code outcome = BLOCKED} and the thrown message captured as {@code denialReason}.</p>
 *
 * <p><b>Example:</b>
 * <pre>{@code
 * @Component
 * class BusinessHoursGuard implements ToolGuard {
 *     @Override
 *     public void check(String toolName, Map<String, Object> arguments) {
 *         if ("issueRefund".equals(toolName) && LocalTime.now().isAfter(LocalTime.of(18, 0))) {
 *             throw new ToolVetoedException("Refunds disabled outside business hours");
 *         }
 *     }
 * }
 * }</pre>
 */
public interface ToolGuard {
    /**
     * @param toolName  the {@code @Tool} name being invoked
     * @param arguments the resolved tool arguments as a map
     * @throws ToolVetoedException when the invocation must be blocked
     */
    void check(String toolName, Map<String, Object> arguments) throws ToolVetoedException;
}
