package com.vn.agent.tools.mutation;

import com.vn.agent.security.AiAgentAdminRole;
import com.vn.agent.security.AiAgentMutationRole;
import com.vn.agent.tools.mutation.fixture.MutationTestFixtureTestRole;
import io.jmix.core.security.InMemoryUserRepository;
import io.jmix.core.security.UserRepository;
import io.jmix.security.role.RoleGrantedAuthorityUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan 11-10 Task 0 — test-only persona seeder for the four mutation-tool test users.
 * Imported explicitly by the TEST-10..13 classes (the ambient
 * {@link com.vn.agent.test_support.TestUsersConfiguration} only seeds {@code alice/bob/admin}).
 *
 * <h2>Users</h2>
 * <ul>
 *   <li>{@code mutation-admin} — {@link AiAgentAdminRole} + {@link AiAgentMutationRole};
 *       full Jmix CRUD plus the exact mutation marker authority.</li>
 *   <li>{@code mutation-user} — {@link MutationTestFixtureTestRole} + {@link AiAgentMutationRole};
 *       narrow fixture CRUD + the exact mutation marker authority.</li>
 *   <li>{@code mutation-no-marker} — {@link MutationTestFixtureTestRole} only; ordinary fixture
 *       CRUD but deliberately MISSING the marker so the marker-role gate denies before
 *       reservation/save.</li>
 *   <li>{@code mutation-fake-marker} — {@link MutationTestFixtureTestRole} plus a manually
 *       constructed authority string that contains {@link AiAgentMutationRole#CODE} but is NOT
 *       equal to the Jmix-created authority. Proves that
 *       {@link MutationAuthorizationService#enforceMutationRole(String)} compares with EXACT
 *       equality and does not accept a substring/contains match.</li>
 * </ul>
 *
 * <p>Mutation-marker authorities for the first two users are built via
 * {@link RoleGrantedAuthorityUtils#createResourceRoleGrantedAuthority(String)} so they match
 * the production Jmix wiring byte-for-byte. The fake marker is intentionally a hand-rolled
 * {@link SimpleGrantedAuthority} that wraps {@code AiAgentMutationRole.CODE} plus a suffix.
 */
@Configuration
public class MutationToolTestUsersConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "mutationToolUserInitializer")
    public MutationToolUserInitializer mutationToolUserInitializer(UserRepository userRepository,
                                                                   RoleGrantedAuthorityUtils authorityUtils) {
        return new MutationToolUserInitializer(userRepository, authorityUtils);
    }

    /**
     * Deferred initializer — runs after {@link UserRepository} +
     * {@link RoleGrantedAuthorityUtils} are wired.
     */
    public static class MutationToolUserInitializer {

        private final UserRepository userRepository;
        private final RoleGrantedAuthorityUtils authorityUtils;

        public MutationToolUserInitializer(UserRepository userRepository,
                                           RoleGrantedAuthorityUtils authorityUtils) {
            this.userRepository = userRepository;
            this.authorityUtils = authorityUtils;
        }

        @PostConstruct
        void init() {
            if (!(userRepository instanceof InMemoryUserRepository repo)) {
                return;
            }
            // mutation-admin must hold the fixture CRUD role so Jmix policies grant
            // create/read/update on the test fixture host entities. AiAgentAdminRole alone
            // does NOT grant policies on the fixture entities (it covers only the AI internal
            // entities). Without the fixture role, LlmExposurePolicy.canReadEntity / canCreate
            // returns false and the resolver returns unknown_entity before any mutation runs.
            repo.addUser(buildUser("mutation-admin",
                    List.of(AiAgentAdminRole.CODE,
                            MutationTestFixtureTestRole.CODE,
                            AiAgentMutationRole.CODE),
                    List.of()));
            repo.addUser(buildUser("mutation-user",
                    List.of(MutationTestFixtureTestRole.CODE, AiAgentMutationRole.CODE),
                    List.of()));
            repo.addUser(buildUser("mutation-no-marker",
                    List.of(MutationTestFixtureTestRole.CODE),
                    List.of()));
            // Fake marker: the authority STRING contains the role code but is not equal to
            // the Jmix-emitted authority (which is "ROLE_" prefixed via
            // RoleGrantedAuthorityUtils.createResourceRoleGrantedAuthority). Substring match
            // must be rejected by enforceMutationRole.
            repo.addUser(buildUser("mutation-fake-marker",
                    List.of(MutationTestFixtureTestRole.CODE),
                    List.of(new SimpleGrantedAuthority(AiAgentMutationRole.CODE + "-not-the-real-thing"))));
        }

        private UserDetails buildUser(String username,
                                      List<String> resourceRoleCodes,
                                      List<GrantedAuthority> additional) {
            List<GrantedAuthority> auths = new ArrayList<>();
            for (String code : resourceRoleCodes) {
                auths.add(authorityUtils.createResourceRoleGrantedAuthority(code));
            }
            auths.addAll(additional);
            return User.builder()
                    .username(username)
                    .password("{noop}password")
                    .authorities(auths)
                    .build();
        }
    }
}
