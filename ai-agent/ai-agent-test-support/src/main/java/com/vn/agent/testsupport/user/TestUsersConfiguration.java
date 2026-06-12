package com.vn.agent.testsupport.user;

import io.jmix.core.security.InMemoryUserRepository;
import io.jmix.core.security.UserRepository;
import io.jmix.security.role.RoleGrantedAuthorityUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reusable test-only Spring configuration that
 * <ol>
 *   <li>provisions an {@link InMemoryUserRepository} when the host context has not declared
 *       a {@link UserRepository} (Jmix 2.8's {@code core.CoreSecurityConfiguration} doesn't
 *       auto-register one — every {@code @SpringBootTest} otherwise fails autowiring); and</li>
 *   <li>seeds the set of personas supplied via a sibling {@code @Bean List<UserPersona>} —
 *       or, if none is supplied, an empty seed (no personas).</li>
 * </ol>
 *
 * <p>Typical usage from a test slice:
 * <pre>{@code
 * @Configuration
 * @Import(TestUsersConfiguration.class)
 * class MySliceConfig {
 *     @Bean
 *     List<UserPersona> personas() {
 *         return List.of(
 *             UserPersona.withRowLevel("alice",
 *                 List.of(AiAgentUserRole.CODE),
 *                 List.of(AiAgentUserRowLevelRole.CODE)),
 *             UserPersona.of("admin", AiAgentAdminRole.CODE)
 *         );
 *     }
 * }
 * }</pre>
 *
 * <p>Mirrors {@code com.vn.agent.test_support.TestUsersConfiguration} +
 * {@code MutationToolTestUsersConfiguration} consolidated into a single parameterised
 * configuration. Both duplicates remain in place for now — see Step 5a — and will be migrated
 * in a follow-up pass.
 */
@Configuration("aiAgentTestSupport_TestUsersConfiguration")
public class TestUsersConfiguration {

    @Bean(name = "core_UserRepository")
    @ConditionalOnMissingBean(UserRepository.class)
    public UserRepository userRepository() {
        return new InMemoryUserRepository();
    }

    @Bean("aiAgentTestSupport_TestUserInitializer")
    @ConditionalOnMissingBean(TestUserInitializer.class)
    public TestUserInitializer testUserInitializer(UserRepository userRepository,
                                                   RoleGrantedAuthorityUtils authorityUtils,
                                                   ObjectProvider<List<UserPersona>> personaListProvider) {
        List<UserPersona> personas = personaListProvider.getIfAvailable(Collections::emptyList);
        return new TestUserInitializer(userRepository, authorityUtils, personas);
    }

    /**
     * Deferred initializer — runs after {@link UserRepository} +
     * {@link RoleGrantedAuthorityUtils} are wired, then adds every {@link UserPersona} to the
     * in-memory repository. Hosts with a non-in-memory repository are left untouched (let the
     * host control its own seeding).
     */
    public static class TestUserInitializer {

        private final UserRepository userRepository;
        private final RoleGrantedAuthorityUtils authorityUtils;
        private final List<UserPersona> personas;

        public TestUserInitializer(UserRepository userRepository,
                                   RoleGrantedAuthorityUtils authorityUtils,
                                   List<UserPersona> personas) {
            this.userRepository = userRepository;
            this.authorityUtils = authorityUtils;
            this.personas = personas;
        }

        @PostConstruct
        void init() {
            if (!(userRepository instanceof InMemoryUserRepository repo)) {
                return;
            }
            for (UserPersona persona : personas) {
                repo.addUser(buildUser(persona));
            }
        }

        private UserDetails buildUser(UserPersona persona) {
            List<GrantedAuthority> authorities = new ArrayList<>();
            for (String code : persona.resourceRoles()) {
                authorities.add(authorityUtils.createResourceRoleGrantedAuthority(code));
            }
            for (String code : persona.rowLevelRoles()) {
                authorities.add(authorityUtils.createRowLevelRoleGrantedAuthority(code));
            }
            authorities.addAll(persona.extraAuthorities());
            return User.builder()
                    .username(persona.name())
                    .password("{noop}password")
                    .authorities(authorities)
                    .build();
        }
    }
}
