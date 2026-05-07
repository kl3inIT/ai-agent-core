package com.vn.agent.security;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiTaskFile;
import com.vn.agent.entity.AiUiSettings;
import com.vn.agent.exposure.AiExposureRule;
import com.vn.agent.tools.mutation.AiMutationIntent;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;
import io.jmix.securityflowui.role.annotation.MenuPolicy;
import io.jmix.securityflowui.role.annotation.ViewPolicy;

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
    @EntityPolicy(entityClass = AiAuditEvent.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiParameters.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiKnowledgeDocument.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiExposureRule.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiMutationIntent.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiTaskFile.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = AiUiSettings.class, actions = {
            EntityPolicyAction.READ,
            EntityPolicyAction.UPDATE})
    void adminAccess();

    @MenuPolicy(menuIds = {
            "aiAgent.chat",
            "aiAgent.conversations",
            "aiAgent.configuration",
            "aiAgent.uiSettings",
            "aiAgent.knowledge.list",
            "aiAgent.audit.list"})
    @ViewPolicy(viewIds = {
            "AiAgent_Chat",
            "AiAgent_ChatDialog",
            "AiAgent_Configuration",
            "AiAgent_BaselineContext",
            "AiAgent_Conversation.list", "AiAgent_Conversation.detail",
            "AiAgent_Parameters.list", "AiAgent_Parameters.detail",
            "AiAgent_KnowledgeBase.list", "AiAgent_KnowledgeDocument.detail",
            "AiAgent_AiAuditEvent.list", "AiAgent_AiAuditEvent.detailDialog",
            "AiAgent_AiExposureRule.list", "AiAgent_AiExposureRule.detail",
            "AiAgent_AiUiSettings.detail"})
    void adminViews();
}
