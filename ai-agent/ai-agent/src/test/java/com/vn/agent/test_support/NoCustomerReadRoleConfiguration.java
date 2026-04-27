package com.vn.agent.test_support;

import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiMessage;
import io.jmix.core.security.InMemoryUserRepository;
import io.jmix.core.security.UserRepository;
import io.jmix.security.model.EntityAttributePolicyAction;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.RoleGrantedAuthorityUtils;
import io.jmix.security.role.annotation.EntityAttributePolicy;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan 08-01 Task 1 — test-only persona supporting CONTEXT D-04.
 *
 * <p>Declares a restricted {@link NoCustomerReadRole} that grants narrow CRUD on
 * {@link AiConversation} + {@link AiMessage} (so the chat-tool call path is reachable)
 * but deliberately omits any policy on the denial-target entity. By Jmix deny-by-default,
 * any entity NOT listed here is unreadable.
 *
 * <p>Registers user {@code carol} with this single resource role. {@code carol} is the
 * subject of the negative-case integration suite (TEST-04, 08-CONTEXT.md D-03):
 * <ul>
 *   <li>{@code FilteredSchemaAndExecutionDenialTest} — schema filter excludes denied entity
 *       at both entity AND attribute granularity (R-01f)</li>
 *   <li>{@code FilteredSchemaAndExecutionDenialTest#carol_findRecords...} — BuiltInDataTools
 *       returns the {@code unknown_entity} JSON envelope on denied lookups (Phase 10 Fix R4
 *       full uniformity: denied entities indistinguishable from non-existent ones, R-01a).</li>
 * </ul>
 *
 * <p><b>Plan 08-01 deviation note (Rule 3 — blocking adaptation):</b> CONTEXT.md and the
 * plan name this configuration "NoCustomerReadRoleConfiguration" because the demo schema's
 * {@code Customer} entity (in {@code com.vn.jmixapp}) was the original denial target. That
 * entity is in the {@code jmix-app} module and is NOT on the {@code ai-agent} module's
 * test classpath, so a {@code Customer}-shaped assertion would always pass trivially.
 * The negative-case tests therefore use {@code ai_AiAuditEvent} (carol has no policy on it
 * → denied) as the actual denial target. The class name is preserved for plan-trace
 * fidelity. See 08-01-SUMMARY.md.
 *
 * <p>Critically, this class does NOT redeclare {@code core_UserRepository}: that bean is
 * owned by {@link TestUsersConfiguration} (which {@code AIConfiguration}'s component scan
 * always picks up). This config only adds {@code carol} via a deferred initializer.
 */
@Configuration
public class NoCustomerReadRoleConfiguration {

    /**
     * Restricted resource role: read+create on AiConversation + AiMessage only. No policy on
     * AiAuditEvent / AiKnowledgeDocument / AiParameters → those entities are denied by
     * Jmix deny-by-default. This is the mechanism we exercise in the negative-case tests.
     */
    @ResourceRole(name = "No Customer Read", code = NoCustomerReadRole.CODE)
    public interface NoCustomerReadRole {

        String CODE = "no-customer-read";

        @EntityPolicy(entityClass = AiConversation.class,
                actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE})
        @EntityPolicy(entityClass = AiMessage.class,
                actions = {EntityPolicyAction.READ, EntityPolicyAction.CREATE})
        void access();
    }

    /**
     * WR-008: attribute-denial fixture role. Grants entity-level READ on
     * {@link AiAuditEvent} (so it appears in {@code CurrentUserSchemaAccess.getReadableSchema()})
     * but grants VIEW only on a curated subset that EXCLUDES the two protected
     * attributes {@code userUsername} and {@code argumentsJson}.
     *
     * <p>The Jmix attribute-policy model is positive-grant only
     * ({@link EntityAttributePolicyAction#VIEW} / {@link EntityAttributePolicyAction#MODIFY});
     * there is no explicit DENY action. To keep an attribute out of
     * {@code CurrentUserSchemaAccess.collectReadableAttributeNames(...)} we simply omit it
     * from the granted list — the {@code EntityAttributeContext.canView()} check returns
     * false in that case.</p>
     *
     * <p>The original
     * {@code FilteredSchemaAndExecutionDenialTest#carol_filteredSchema_excludesDenied_andDeniedAttributes}
     * flow checked attribute denial only on the entity-denial path, where the MetaClass was
     * always absent — making the attribute assertion vacuous. Persona {@code dave}
     * (registered below) drives a non-vacuous attribute-denial assertion.</p>
     */
    @ResourceRole(name = "Audit Read No Sensitive Attrs", code = AuditReadNoSensitiveAttrsRole.CODE)
    public interface AuditReadNoSensitiveAttrsRole {

        String CODE = "audit-read-no-sensitive-attrs";

        @EntityPolicy(entityClass = AiAuditEvent.class,
                actions = {EntityPolicyAction.READ})
        // Grant VIEW only on a curated subset — userUsername + argumentsJson are
        // intentionally absent so canView() returns false for them under this role.
        // (Jmix attribute policies are positive-grant only; absence == deny.)
        @EntityAttributePolicy(entityClass = AiAuditEvent.class,
                attributes = {
                        "id", "version", "parent", "children", "conversation",
                        "runId", "kind", "eventName", "resultSummary", "queryText",
                        "topK", "hitCount", "topScore", "filtersJson", "retrievalHitsJson",
                        "outcome", "denialReason", "errorClass", "promptHash",
                        "latencyMs", "startedAt", "finishedAt", "displayName"
                },
                action = EntityAttributePolicyAction.VIEW)
        void access();
    }

    @Bean
    @ConditionalOnMissingBean(name = "noCustomerReadUserInitializer")
    public NoCustomerReadUserInitializer noCustomerReadUserInitializer(
            UserRepository userRepository,
            RoleGrantedAuthorityUtils authorityUtils) {
        return new NoCustomerReadUserInitializer(userRepository, authorityUtils);
    }

    /**
     * Deferred initializer — runs after {@link UserRepository} + {@link RoleGrantedAuthorityUtils}
     * are wired and seeds {@code carol} into the in-memory repo (mirrors
     * {@link TestUsersConfiguration.TestUserInitializer} pattern).
     */
    public static class NoCustomerReadUserInitializer {

        private final UserRepository userRepository;
        private final RoleGrantedAuthorityUtils authorityUtils;

        public NoCustomerReadUserInitializer(UserRepository userRepository,
                                             RoleGrantedAuthorityUtils authorityUtils) {
            this.userRepository = userRepository;
            this.authorityUtils = authorityUtils;
        }

        @PostConstruct
        void init() {
            if (!(userRepository instanceof InMemoryUserRepository repo)) {
                // Host-supplied repo — defensive early-return (matches TestUsersConfiguration).
                return;
            }
            repo.addUser(buildUser("carol", NoCustomerReadRole.CODE));
            // WR-008: register dave with the attribute-denial role.
            repo.addUser(buildUser("dave", AuditReadNoSensitiveAttrsRole.CODE));
        }

        private UserDetails buildUser(String username, String resourceRoleCode) {
            List<GrantedAuthority> auths = new ArrayList<>();
            auths.add(authorityUtils.createResourceRoleGrantedAuthority(resourceRoleCode));
            return User.builder()
                    .username(username)
                    .password("{noop}password")
                    .authorities(auths)
                    .build();
        }
    }
}
