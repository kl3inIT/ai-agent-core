package com.vn.agent.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 14 Plan 01 — structural security assertions for extraction drafts.
 *
 * <p>The intended runtime row-level test cannot start while the shared
 * {@code @SpringBootTest} context fails before test bodies execute. This source
 * test pins the exact Jmix annotations and ownership predicate until that
 * pre-existing boot blocker is fixed.
 */
class AiExtractionDraftSecurityTest {

    private static final Path USER_ROLE_SOURCE = Path.of(
            "src/main/java/com/vn/agent/security/AiAgentUserRole.java");
    private static final Path ROW_LEVEL_ROLE_SOURCE = Path.of(
            "src/main/java/com/vn/agent/security/AiAgentUserRowLevelRole.java");
    private static final Path INTERNAL_ENTITY_NAMES_SOURCE = Path.of(
            "src/main/java/com/vn/agent/tools/AiAgentToolsProperties.java");

    @Test
    void userRoleGrantsDraftCrudAndRowLevelRoleScopesByOwner() throws Exception {
        String userRole = Files.readString(USER_ROLE_SOURCE);
        String rowLevelRole = Files.readString(ROW_LEVEL_ROLE_SOURCE);

        assertThat(userRole).contains(
                "import com.vn.agent.entity.AiExtractionDraft;",
                "@EntityPolicy(entityClass = AiExtractionDraft.class,",
                "EntityPolicyAction.READ",
                "EntityPolicyAction.CREATE",
                "EntityPolicyAction.UPDATE",
                "EntityPolicyAction.DELETE");
        assertThat(rowLevelRole).contains(
                "import com.vn.agent.entity.AiExtractionDraft;",
                "@JpqlRowLevelPolicy(",
                "entityClass = AiExtractionDraft.class",
                "where = \"{E}.userUsername = :current_user_username\"",
                "void extractionDraft();");
    }

    @Test
    void draftEntityIsHiddenFromLlmVisibleInternalEntities() throws Exception {
        String internalEntityNames = Files.readString(INTERNAL_ENTITY_NAMES_SOURCE);

        assertThat(internalEntityNames)
                .contains("\"ai_AiExtractionDraft\"")
                .containsOnlyOnce("\"ai_AiExtractionDraft\"");
    }
}
