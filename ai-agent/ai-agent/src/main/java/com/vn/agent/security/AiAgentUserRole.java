package com.vn.agent.security;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;

/**
 * Resource role granting end users narrow CRUD on their own conversations and messages.
 * <p>End users MUST additionally be assigned {@link AiAgentUserRowLevelRole} so the
 * ownership predicate is applied; this role alone would expose all rows.</p>
 *
 * <p>No policies on {@code AiToolCallAudit}, {@code AiParameters}, or
 * {@code AiKnowledgeDocument} — users have zero access to those entities (D-07).
 * No DELETE — users cannot delete their own conversations in v1.
 * No attribute-level, view, or menu policies in Phase 2 (deferred per D-07).</p>
 */
@ResourceRole(name = "AI Agent User", code = AiAgentUserRole.CODE)
public interface AiAgentUserRole {

    String CODE = "ai-agent-user";

    @EntityPolicy(entityClass = AiConversation.class,
            actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE, EntityPolicyAction.UPDATE})
    @EntityPolicy(entityClass = AiMessage.class,
            actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE})
    void userAccess();
}
