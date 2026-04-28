package com.vn.agent.rag;

import io.jmix.security.model.ResourceRole;
import io.jmix.security.role.ResourceRoleRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Shared fail-closed validation for role codes mirrored into knowledge-document
 * chunk metadata.
 */
final class KnowledgeDocumentRoleValidator {

    private KnowledgeDocumentRoleValidator() {
    }

    static List<String> validateRoleCodes(Collection<String> allowedRoles,
                                          ResourceRoleRepository roleRepository) {
        Objects.requireNonNull(roleRepository, "roleRepository must not be null");
        if (allowedRoles == null) {
            return List.of();
        }

        List<String> roles = new ArrayList<>(allowedRoles.size());
        for (String code : allowedRoles) {
            if (code == null || code.isBlank()) {
                throw new UnknownRoleCodeException(code);
            }
            ResourceRole role = resolveRole(code, roleRepository);
            if (role == null) {
                throw new UnknownRoleCodeException(code);
            }
            roles.add(code);
        }
        return List.copyOf(roles);
    }

    private static ResourceRole resolveRole(String code, ResourceRoleRepository roleRepository) {
        try {
            return roleRepository.findRoleByCode(code);
        } catch (RuntimeException e) {
            // Jmix may consult the runtime role store after design-time role lookup misses.
            // If that store is unavailable or not present in a lightweight test/host schema,
            // keep the public contract fail-closed: the caller supplied an unusable role code.
            throw new UnknownRoleCodeException(code, e);
        }
    }
}
