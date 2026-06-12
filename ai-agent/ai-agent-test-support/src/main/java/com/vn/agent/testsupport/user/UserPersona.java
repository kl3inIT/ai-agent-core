package com.vn.agent.testsupport.user;

import org.springframework.security.core.GrantedAuthority;

import java.util.List;

/**
 * Declarative test persona — username + resource roles + row-level roles + ad-hoc extra
 * authorities. Consumed by {@link TestUsersConfiguration} to seed an
 * {@link io.jmix.core.security.InMemoryUserRepository}.
 *
 * <p>Lists default to empty when null is passed so call sites can write
 * {@code new UserPersona("alice", List.of(UserRole.CODE), null, null)} for the common case.
 *
 * @param name              the username (also the password — {@code {noop}password})
 * @param resourceRoles     resource role codes wrapped via
 *                          {@link io.jmix.security.role.RoleGrantedAuthorityUtils#createResourceRoleGrantedAuthority(String)}
 * @param rowLevelRoles     row-level role codes wrapped via
 *                          {@link io.jmix.security.role.RoleGrantedAuthorityUtils#createRowLevelRoleGrantedAuthority(String)}
 * @param extraAuthorities  additional authorities appended verbatim (used by the mutation
 *                          fake-marker test persona)
 */
public record UserPersona(String name,
                          List<String> resourceRoles,
                          List<String> rowLevelRoles,
                          List<GrantedAuthority> extraAuthorities) {

    public UserPersona {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("UserPersona name must be non-blank");
        }
        resourceRoles = resourceRoles != null ? List.copyOf(resourceRoles) : List.of();
        rowLevelRoles = rowLevelRoles != null ? List.copyOf(rowLevelRoles) : List.of();
        extraAuthorities = extraAuthorities != null ? List.copyOf(extraAuthorities) : List.of();
    }

    public static UserPersona of(String name, String... resourceRoles) {
        return new UserPersona(name, List.of(resourceRoles), List.of(), List.of());
    }

    public static UserPersona withRowLevel(String name, List<String> resourceRoles, List<String> rowLevelRoles) {
        return new UserPersona(name, resourceRoles, rowLevelRoles, List.of());
    }
}
