package com.vn.agent.spi;

/**
 * Host extension point for injecting <b>app-specific</b> per-request context into the
 * tool-execution ToolContext. Fires once per {@code ChatService.ask}/{@code stream} call,
 * AFTER the add-on has populated baseline keys.
 *
 * <p><b>Baseline is built-in — do NOT re-derive it.</b> When this method is called, the
 * bag already contains the following reserved keys, populated by the add-on from Jmix's
 * current authentication and conversation state:
 * <ul>
 *   <li>{@code agent.userId} — UUID of the current Jmix user</li>
 *   <li>{@code agent.username} — login name</li>
 *   <li>{@code agent.roles} — {@code Set<String>} of role codes</li>
 *   <li>{@code agent.locale} — current user {@link java.util.Locale}</li>
 *   <li>{@code agent.conversationId} — UUID of the active {@code AiConversation}</li>
 * </ul>
 *
 * <p>Implementations MUST NOT overwrite keys under the reserved {@code agent.*} namespace.
 * Contributors exist ONLY for genuinely app-specific context the add-on cannot know:
 * tenant IDs from host multi-tenancy, feature-flag snapshots, correlation IDs from
 * upstream systems, domain-specific session state, etc.</p>
 *
 * <p>Contract: host-owned keys MUST be namespaced (e.g. {@code "crm.accountId"},
 * {@code "billing.tier"}). Bare keys and the {@code agent.*} prefix are reserved.</p>
 */
public interface ContextContributor {
    /**
     * @param bag mutable key-to-value map attached to ToolContext, pre-populated with
     *            reserved {@code agent.*} baseline keys. Add app-specific entries under
     *            a host-owned namespace; do not touch {@code agent.*}.
     */
    void contribute(java.util.Map<String, Object> bag);
}
