package com.vn.agent.tools.mutation;

import com.vn.agent.metadata.LlmExposurePolicy;
import com.vn.agent.security.AiAgentMutationRole;
import com.vn.agent.tools.ToolUserError;
import io.jmix.core.AccessManager;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.accesscontext.EntityAttributeContext;
import io.jmix.core.metamodel.model.MetaClass;
import io.jmix.core.metamodel.model.MetaProperty;
import io.jmix.core.security.CurrentAuthentication;
import io.jmix.security.role.RoleGrantedAuthorityUtils;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Encapsulates the marker-role gate, Jmix entity CRUD checks, per-attribute write checks,
 * relationship-target LLM exposure checks, and entity read checks for {@code BuiltInMutationTools}
 * (Plan 11-07A).
 *
 * <p><b>Marker-role contract (SEC-07, exact authority equality):</b>
 * {@link #enforceMutationRole(String)} computes the exact required Jmix resource-role
 * authority via
 * {@link RoleGrantedAuthorityUtils#createResourceRoleGrantedAuthority(String)} and compares
 * with {@link String#equals}. A fake authority string that merely contains
 * {@link AiAgentMutationRole#CODE} (e.g. {@code "ROLE_my-ai-agent-mutation-helper"}) is
 * rejected — substring/contains checks are explicitly forbidden by the must-haves contract.
 *
 * <p><b>Failure-mode contract:</b>
 * <ul>
 *     <li>Marker-role denial → {@link ToolUserError} with stable code {@code access_denied}.
 *         Surfaces BEFORE any idempotency reservation or host save.</li>
 *     <li>Entity-level CRUD denial → {@link ToolUserError} with stable code
 *         {@code access_denied}. Per-operation: create / update / read / delete.</li>
 *     <li>Per-attribute denial → {@link ToolUserError} with stable code {@code access_denied};
 *         message is metamodel-only ({@code "attribute is not modifiable"}), never echoes
 *         the attribute value (P-22).</li>
 *     <li>Relationship-target LLM exposure denial → {@link ToolUserError} with stable code
 *         {@code access_denied}; deliberate small information leak per CONTEXT.md "Specifics".</li>
 * </ul>
 */
@Component
public class MutationAuthorizationService {

    private final AccessManager accessManager;
    private final LlmExposurePolicy llmExposurePolicy;
    private final CurrentAuthentication currentAuthentication;
    private final RoleGrantedAuthorityUtils roleGrantedAuthorityUtils;

    public MutationAuthorizationService(AccessManager accessManager,
                                        LlmExposurePolicy llmExposurePolicy,
                                        CurrentAuthentication currentAuthentication,
                                        RoleGrantedAuthorityUtils roleGrantedAuthorityUtils) {
        this.accessManager = accessManager;
        this.llmExposurePolicy = llmExposurePolicy;
        this.currentAuthentication = currentAuthentication;
        this.roleGrantedAuthorityUtils = roleGrantedAuthorityUtils;
    }

    /**
     * Enforce the {@link AiAgentMutationRole#CODE} marker-role authority (exact equality).
     * Throws {@link ToolUserError} with stable code {@code access_denied} BEFORE any
     * idempotency reservation runs. Substring/contains checks are forbidden — see
     * {@link RoleGrantedAuthorityUtils#createResourceRoleGrantedAuthority(String)} which
     * emits {@code defaultRolePrefix + roleCode} (default {@code "ROLE_ai-agent-mutation"}).
     *
     * @param roleCode the role code to require (typically {@link AiAgentMutationRole#CODE})
     */
    public void enforceMutationRole(String roleCode) {
        String requiredAuthority = roleGrantedAuthorityUtils
                .createResourceRoleGrantedAuthority(roleCode)
                .getAuthority();
        var authorities = currentAuthentication.getUser().getAuthorities();
        if (authorities == null) {
            throw accessDenied();
        }
        for (GrantedAuthority granted : authorities) {
            if (requiredAuthority.equals(granted.getAuthority())) {
                return;
            }
        }
        throw accessDenied();
    }

    /** Enforce {@link CrudEntityContext#isCreatePermitted} on {@code metaClass}. */
    public void enforceCreatePermission(MetaClass metaClass) {
        CrudEntityContext ctx = new CrudEntityContext(metaClass);
        accessManager.applyRegisteredConstraints(ctx);
        if (!ctx.isCreatePermitted()) {
            throw accessDenied();
        }
    }

    /** Enforce {@link CrudEntityContext#isUpdatePermitted} on {@code metaClass}. */
    public void enforceUpdatePermission(MetaClass metaClass) {
        CrudEntityContext ctx = new CrudEntityContext(metaClass);
        accessManager.applyRegisteredConstraints(ctx);
        if (!ctx.isUpdatePermitted()) {
            throw accessDenied();
        }
    }

    /** Enforce {@link CrudEntityContext#isReadPermitted} on {@code metaClass}. */
    public void enforceReadPermission(MetaClass metaClass) {
        CrudEntityContext ctx = new CrudEntityContext(metaClass);
        accessManager.applyRegisteredConstraints(ctx);
        if (!ctx.isReadPermitted()) {
            throw accessDenied();
        }
    }

    /**
     * Enforce per-attribute {@link EntityAttributeContext#canModify} for every attribute
     * the LLM is writing. Constructs a fresh context per attribute (the API is stateful —
     * {@link AccessManager#applyRegisteredConstraints} mutates the context).
     *
     * <p>P-22: the thrown {@link ToolUserError} message is metamodel-only and does NOT
     * echo the attribute value the LLM supplied.
     */
    public void enforceAttributeWriteAccess(MetaClass metaClass, Set<String> attributeNames) {
        for (String attributeName : attributeNames) {
            EntityAttributeContext attr = new EntityAttributeContext(metaClass, attributeName);
            accessManager.applyRegisteredConstraints(attr);
            if (!attr.canModify()) {
                throw new ToolUserError("access_denied",
                        "attribute is not modifiable",
                        List.of("call describe_entity and check agent.permissions modifiable attributes"));
            }
        }
    }

    /**
     * Enforce per-attribute write access on the inverse side of a relationship (used by
     * {@code add_related_record} / {@code remove_related_record} when wiring/clearing the
     * child-side to-one inverse). No-op when {@link MetaProperty#getInverse()} returns null
     * (purely unidirectional; tool already failed-closed earlier in metadata resolution).
     */
    public void enforceInverseAttributeWriteAccess(MetaClass childMetaClass,
                                                   MetaProperty relationshipProperty) {
        MetaProperty inverse = relationshipProperty.getInverse();
        if (inverse != null) {
            enforceAttributeWriteAccess(childMetaClass, Set.of(inverse.getName()));
        }
    }

    /**
     * Enforce {@link LlmExposurePolicy} read (and optionally modify) on a relationship target
     * entity. Used for to-one assignment in create/update (read-only target check) and for
     * add/remove related write operations (read+modify check).
     *
     * @param targetMetaClass the relationship target entity's MetaClass
     * @param requiresModify true to additionally require {@link LlmExposurePolicy#canModify};
     *                       false (create/update to-one read-only assignment) checks read only
     */
    public void enforceLlmRelationshipTargetExposure(MetaClass targetMetaClass, boolean requiresModify) {
        if (!llmExposurePolicy.canReadEntity(targetMetaClass)
                || (requiresModify && !llmExposurePolicy.canModify(targetMetaClass))) {
            throw new ToolUserError("access_denied",
                    "relationship target is not available to mutation tools",
                    List.of("do not retry; choose an entity visible in agent.permissions"));
        }
    }

    private static ToolUserError accessDenied() {
        return new ToolUserError("access_denied",
                "operation not permitted",
                List.of("do not retry; surface to user"));
    }
}
