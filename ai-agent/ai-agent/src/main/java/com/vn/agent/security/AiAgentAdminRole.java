package com.vn.agent.security;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.entity.AiToolCallAudit;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;

/**
 * Resource role granting administrators full CRUD on all AI Agent entities.
 * <p>Explicit {@code entityClass} per entity (NOT {@code entityName="*"}) for safer
 * scope and IDE navigability. Admins do NOT need {@link AiAgentUserRowLevelRole} —
 * absence of a narrowing row-level policy means admins see all rows.</p>
 */
@ResourceRole(name = "AI Agent Admin", code = AiAgentAdminRole.CODE)
public interface AiAgentAdminRole {

    String CODE = "ai-agent-admin";

    @EntityPolicy(entityClass = AiConversation.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiMessage.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiToolCallAudit.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiParameters.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiKnowledgeDocument.class, actions = EntityPolicyAction.ALL)
    void adminAccess();
}
