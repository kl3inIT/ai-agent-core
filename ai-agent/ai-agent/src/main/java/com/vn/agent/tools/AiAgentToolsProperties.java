package com.vn.agent.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * Configuration surface for the AI tool layer, bound to {@code jmix.ai-agent.tools.*}.
 * Picked up by the {@code @ConfigurationPropertiesScan} on {@code AIConfiguration}.
 *
 * <p>{@link #hiddenEntities} is a pure schema-visibility filter: it removes the named
 * entities from the LLM tool/schema surface so the model never sees the add-on's own
 * governance tables or the Jmix security tables. It is NOT a security boundary —
 * Jmix {@code AccessManager} / constrained {@code DataManager} remain authoritative for
 * real entity-, attribute-, and row-level access control.</p>
 *
 * <p>When {@link #hiddenEntities} is null or empty the {@link #DEFAULT_HIDDEN_ENTITIES}
 * set is used; a host that sets the key supplies the complete list (no merge with the
 * default).</p>
 */
@ConfigurationProperties("jmix.ai-agent.tools")
public record AiAgentToolsProperties(List<String> hiddenEntities) {

    /**
     * Built-in schema-visibility denylist: the add-on's own internal {@code Ai*} entities
     * plus the Jmix security entities. These are implementation/governance tables, never
     * business entities for the LLM tool surface.
     */
    public static final Set<String> DEFAULT_HIDDEN_ENTITIES = Set.of(
            // AI add-on internal entities
            "ai_AiAuditEvent",
            "ai_AiConversation",
            "ai_AiMessage",
            "ai_AiKnowledgeDocument",
            "ai_AiParameters",
            "ai_AiUiSettings",
            "ai_AiExtractionDraft",
            "aiMutation_AiMutationIntent",
            // Jmix security entities
            "sec_User",
            "sec_Role",
            "sec_RoleAssignment",
            "sec_ResourcePolicy",
            "sec_ResourceRole",
            "sec_RowLevelPolicy",
            "sec_RowLevelRole",
            "sec_UserSubstitution",
            "sec_SessionLogEntry"
    );

    /**
     * The effective set of entity names hidden from the LLM schema surface. Returns the
     * configured {@link #hiddenEntities} when present, otherwise {@link #DEFAULT_HIDDEN_ENTITIES}.
     * Never null.
     */
    public Set<String> resolvedHiddenEntities() {
        return hiddenEntities == null || hiddenEntities.isEmpty()
                ? DEFAULT_HIDDEN_ENTITIES
                : Set.copyOf(hiddenEntities);
    }

    /**
     * Whether the given entity name is hidden from the LLM schema surface. Replacement for
     * the former {@code AiInternalEntityNames.contains}.
     */
    public boolean contains(String entityName) {
        return resolvedHiddenEntities().contains(entityName);
    }
}
