package com.vn.agent.test_support;

import com.vn.agent.security.AiAgentAdminRole;
import com.vn.agent.security.AiAgentUserRole;
import com.vn.agent.security.AiAgentUserRowLevelRole;
import io.jmix.core.security.InMemoryUserRepository;
import io.jmix.core.security.UserRepository;
import io.jmix.security.role.RoleGrantedAuthorityUtils;
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
 * Ambient test-only {@code core_UserRepository} provisioning three canonical users:
 * <ul>
 *   <li>{@code alice} — {@link AiAgentUserRole} + {@link AiAgentUserRowLevelRole}</li>
 *   <li>{@code bob}   — {@link AiAgentUserRole} + {@link AiAgentUserRowLevelRole}</li>
 *   <li>{@code admin} — {@link AiAgentAdminRole} (no row-level role)</li>
 * </ul>
 *
 * <p>Lives in {@code com.vn.agent.test_support} so AIConfiguration's
 * {@link org.springframework.context.annotation.ComponentScan} picks it up during
 * <em>every</em> add-on test that loads {@link com.vn.agent.AITestConfiguration}. Jmix
 * 2.8's {@code core.CoreSecurityConfiguration} does not auto-register a {@link UserRepository};
 * without this bean any {@code @SpringBootTest} fails with a {@code NoSuchBeanDefinitionException}
 * for {@code UserRepository} (surfaces via {@code SystemAuthenticator} / {@code SecurityConfiguration}
 * autowiring). {@code @ConditionalOnMissingBean} lets individual tests override with a richer
 * fixture if they need to.
 *
 * <p>Added by plan 07-07b Task 1 as a Rule-1 deviation. 07-01 pulled the full Jmix security +
 * Flow-UI filter chain into the add-on; the corresponding test-classpath fixtures were not
 * introduced at the same time, leaving every subsequent {@code @SpringBootTest} unable to
 * bootstrap. Test-side fix; production is untouched.
 */
@Configuration
public class TestUsersConfiguration {

    @Bean(name = "core_UserRepository")
    @ConditionalOnMissingBean(UserRepository.class)
    public UserRepository userRepository() {
        return new InMemoryUserRepository();
    }

    @Bean
    @ConditionalOnMissingBean(TestUserInitializer.class)
    public TestUserInitializer testUserInitializer(UserRepository userRepository,
                                                   RoleGrantedAuthorityUtils authorityUtils) {
        return new TestUserInitializer(userRepository, authorityUtils);
    }

    /**
     * Deferred-initialization helper — runs after {@link UserRepository} + {@link RoleGrantedAuthorityUtils}
     * are fully wired, then seeds the three canonical users.
     */
    public static class TestUserInitializer {
        private final UserRepository userRepository;
        private final RoleGrantedAuthorityUtils authorityUtils;

        public TestUserInitializer(UserRepository userRepository, RoleGrantedAuthorityUtils authorityUtils) {
            this.userRepository = userRepository;
            this.authorityUtils = authorityUtils;
        }

        @PostConstruct
        void init() {
            if (!(userRepository instanceof InMemoryUserRepository repo)) {
                // Host-supplied repo — do not touch it. Tests that need the alice/bob/admin
                // fixture against a custom repo should wire their own initializer.
                return;
            }
            repo.addUser(buildUser("alice", AiAgentUserRole.CODE, AiAgentUserRowLevelRole.CODE));
            repo.addUser(buildUser("bob", AiAgentUserRole.CODE, AiAgentUserRowLevelRole.CODE));
            repo.addUser(buildAdmin("admin", AiAgentAdminRole.CODE));
        }

        private UserDetails buildUser(String username, String resourceRoleCode, String rowLevelRoleCode) {
            List<GrantedAuthority> auths = new ArrayList<>();
            auths.add(authorityUtils.createResourceRoleGrantedAuthority(resourceRoleCode));
            auths.add(authorityUtils.createRowLevelRoleGrantedAuthority(rowLevelRoleCode));
            return User.builder()
                    .username(username)
                    .password("{noop}password")
                    .authorities(auths)
                    .build();
        }

        private UserDetails buildAdmin(String username, String resourceRoleCode) {
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
