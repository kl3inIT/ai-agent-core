package com.vn.agent.security;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;
import io.jmix.securityflowui.role.annotation.MenuPolicy;
import io.jmix.securityflowui.role.annotation.ViewPolicy;

/**
 * Resource role granting end users narrow CRUD on their own conversations and messages.
 * <p>End users MUST additionally be assigned {@link AiAgentUserRowLevelRole} so the
 * ownership predicate is applied; this role alone would expose all rows.</p>
 *
 * <p>No policies on {@code AiAuditEvent}, {@code AiParameters}, or
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

    /**
     * View + menu access for the two user-facing Phase 7 views — Chat and Conversation
     * list (plus the conversation detail route reached from that list). Admin-only views
     * (Parameters, KnowledgeBase, ToolCallAudit) are intentionally absent so non-admins
     * are denied per UI-10 / D-10.
     *
     * <p>Added in plan 07-07b Task 1 as a Rule-2 deviation: {@link AiAgentAdminRole}
     * declared view-level policies for admin views but this user role was not granted
     * symmetric view access to Chat / Conversation, leaving non-admins unable to reach
     * any Phase 7 UI. AdminViewAccessTest ({@code allowsChatForUser}) surfaced the gap.
     */
    @MenuPolicy(menuIds = {
            "aiAgent.chat",
            "aiAgent.conversations"})
    @ViewPolicy(viewIds = {
            "AiAgent_Chat",
            "AiAgent_ChatDialog",
            "AiAgent_Conversation.list",
            "AiAgent_Conversation.detail"})
    void userViews();
}
