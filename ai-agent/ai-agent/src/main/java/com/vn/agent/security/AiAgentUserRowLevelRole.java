package com.vn.agent.security;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiExtractionDraft;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiTaskFile;
import io.jmix.security.role.annotation.JpqlRowLevelPolicy;
import io.jmix.security.role.annotation.RowLevelRole;

/**
 * Row-level predicate restricting non-admin users to their own conversations and messages.
 * <p>End users MUST be assigned BOTH {@link AiAgentUserRole} (resource role: entity CRUD policies)
 * AND this role (row-level policy: ownership predicate). Admins with {@link AiAgentAdminRole}
 * do NOT need the row-level role — absence of a narrowing policy means admins see all rows.</p>
 *
 * <p>{@code :current_user_username} is a Jmix framework-bound session parameter pulled from
 * {@code CurrentAuthentication.getUser().getUsername()} — no reflection or bean lookup required.
 * {@code {E}} is the JPQL entity alias.</p>
 */
@RowLevelRole(name = "AI Agent User Row-Level", code = AiAgentUserRowLevelRole.CODE)
public interface AiAgentUserRowLevelRole {

    String CODE = "ai-agent-user-rl";

    @JpqlRowLevelPolicy(
            entityClass = AiConversation.class,
            where = "{E}.createdBy = :current_user_username")
    void conversation();

    @JpqlRowLevelPolicy(
            entityClass = AiMessage.class,
            where = "{E}.conversation.createdBy = :current_user_username")
    void message();

    // Phase 13 D-04: AiTaskFile carries its own userUsername column (D-03 schema)
    // so the predicate filters directly — do NOT chain via {E}.conversation.createdBy.
    @JpqlRowLevelPolicy(
            entityClass = AiTaskFile.class,
            where = "{E}.userUsername = :current_user_username")
    void taskFile();

    @JpqlRowLevelPolicy(
            entityClass = AiExtractionDraft.class,
            where = "{E}.userUsername = :current_user_username")
    void extractionDraft();
}
