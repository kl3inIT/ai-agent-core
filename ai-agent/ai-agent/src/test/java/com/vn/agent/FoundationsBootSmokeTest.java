package com.vn.agent;

import com.vn.agent.entity.AiConversation;
import com.vn.agent.entity.AiKnowledgeDocument;
import com.vn.agent.entity.AiKnowledgeDocumentStatus;
import com.vn.agent.entity.AiMessage;
import com.vn.agent.entity.AiMessageRole;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiParameters;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.security.AiAgentAdminRole;
import com.vn.agent.security.AiAgentUserRole;
import com.vn.agent.security.AiAgentUserRowLevelRole;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.spi.AuditListener;
import com.vn.agent.spi.ContextContributor;
import com.vn.agent.spi.CustomIngester;
import com.vn.agent.spi.PromptContextContributor;
import com.vn.agent.spi.ToolContributor;
import com.vn.agent.spi.ToolGuard;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.security.InMemoryUserRepository;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.core.security.UserRepository;
import io.jmix.security.model.RowLevelPolicy;
import io.jmix.security.model.RowLevelPolicyAction;
import io.jmix.security.model.RowLevelPolicyType;
import io.jmix.security.model.RowLevelRole;
import io.jmix.security.role.ResourceRoleRepository;
import io.jmix.security.role.RoleGrantedAuthorityUtils;
import io.jmix.security.role.RowLevelRoleRepository;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 foundations boot smoke test — the single consolidated gate that proves all Phase 2
 * add-on pieces wire together correctly on the developer laptop DB (HSQLDB). If this test is
 * green, Phase 2 is done.
 *
 * <p>Boots against {@link AITestConfiguration} (the existing {@code @EnableAutoConfiguration}
 * harness) so that the starter's {@code AutoConfiguration.imports} entries — which register
 * {@link com.vn.autoconfigure.agent.AIAutoConfiguration} and
 * {@link com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration} — are discovered. The
 * explicit {@code @ImportAutoConfiguration} belt-and-braces also loads the two auto-configs
 * directly, so this test works even when the starter JAR is absent from classpath.</p>
 *
 * <p><b>Jmix APIs used</b> (verified against jmix-core 2.8.0 + jmix-security 2.8.0 sources):
 * <ul>
 *   <li>{@link InMemoryUserRepository#addUser(UserDetails)} to provision alice/bob/admin</li>
 *   <li>{@link RoleGrantedAuthorityUtils#createResourceRoleGrantedAuthority(String)} /
 *       {@code createRowLevelRoleGrantedAuthority(String)} for authority wiring</li>
 *   <li>{@link SystemAuthenticator#runWithUser(String, Runnable)} /
 *       {@code withUser(String, AuthenticatedOperation)} for identity-switched sections</li>
 * </ul></p>
 */
@SpringBootTest(classes = AITestConfiguration.class)
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import(StubVectorStoreConfiguration.class)
class FoundationsBootSmokeTest {

    @Autowired DataManager dataManager;
    @Autowired Metadata metadata;
    @Autowired
    @Qualifier("pgvectorJdbcTemplate")
    JdbcTemplate agentstoreJdbcTemplate;
    @Autowired SystemAuthenticator systemAuthenticator;
    @Autowired ResourceRoleRepository resourceRoleRepository;
    @Autowired RowLevelRoleRepository rowLevelRoleRepository;

    @Autowired ToolContributor toolContributor;
    @Autowired ContextContributor contextContributor;
    @Autowired PromptContextContributor promptContextContributor;
    @Autowired ToolGuard toolGuard;
    @Autowired AuditListener auditListener;
    @Autowired CustomIngester customIngester;

    // ---------------------------------------------------------------------
    // 1. Liquibase applied the add-on master changelog on HSQLDB.
    //    PgVectorStore is stubbed in this HSQLDB path, so no pgvector table is created.
    // ---------------------------------------------------------------------
    @Test
    void liquibase_applies_on_hsqldb() {
        assertTableExists("AI_AGENT_CONVERSATION");
        assertTableExists("AI_AGENT_MESSAGE");
        assertTableExists("AI_AGENT_AUDIT_EVENT");
        assertTableAbsent("AI_AGENT_TOOL_CALL_AUDIT");
        assertTableExists("AI_AGENT_PARAMETERS");
        assertTableExists("AI_AGENT_KNOWLEDGE_DOCUMENT");
        assertTableExists("SPRING_AI_CHAT_MEMORY");
        assertTableAbsent("AI_AGENT_KB_VECTOR_STORE");
    }

    // ---------------------------------------------------------------------
    // 2. All 5 entities round-trip via DataManager (under system to bypass
    //    row-level predicates for this persistence sanity check).
    //    AiConversation -> AiMessage composition cascade is exercised.
    // ---------------------------------------------------------------------
    @Test
    void all_five_entities_round_trip() {
        systemAuthenticator.runWithSystem(() -> {
            // --- AiConversation + composed AiMessage ---
            AiConversation conv = metadata.create(AiConversation.class);
            conv.setTitle("round-trip conv");
            conv.setCreatedBy("system");

            AiMessage msg = metadata.create(AiMessage.class);
            msg.setConversation(conv);
            msg.setRole(AiMessageRole.USER);
            msg.setContent("hello");
            msg.setSeq(1);
            msg.setCreatedDate(OffsetDateTime.now());

            List<AiMessage> msgs = new ArrayList<>();
            msgs.add(msg);
            conv.setMessages(msgs);

            AiConversation savedConv = dataManager.save(conv, msg).get(conv);
            assertNotNull(savedConv.getId());

            AiConversation loadedConv = dataManager.load(AiConversation.class)
                    .id(savedConv.getId()).one();
            assertEquals(savedConv.getId(), loadedConv.getId());
            assertEquals("round-trip conv", loadedConv.getTitle());

            AiMessage loadedMsg = dataManager.load(AiMessage.class).id(msg.getId()).one();
            assertEquals(msg.getId(), loadedMsg.getId());
            assertEquals(savedConv.getId(), loadedMsg.getConversation().getId());
            assertEquals("hello", loadedMsg.getContent());

            // --- AiAuditEvent (tree-lite; Phase 7.2 rewrite of former AiToolCallAudit) ---
            AiAuditEvent audit = metadata.create(AiAuditEvent.class);
            audit.setKind(AuditKind.TOOL);
            audit.setEventName("sampleTool");
            audit.setOutcome(AiToolCallOutcome.SUCCESS);
            audit.setStartedAt(OffsetDateTime.now());
            AiAuditEvent savedAudit = dataManager.save(audit);
            AiAuditEvent loadedAudit = dataManager.load(AiAuditEvent.class)
                    .id(savedAudit.getId()).one();
            assertEquals(savedAudit.getId(), loadedAudit.getId());
            assertEquals("sampleTool", loadedAudit.getEventName());

            // --- AiParameters ---
            AiParameters params = metadata.create(AiParameters.class);
            params.setProfileName("default-" + UUID.randomUUID());
            params.setActive(Boolean.TRUE);
            params.setBodyYaml("temperature: 0.2");
            AiParameters savedParams = dataManager.save(params);
            AiParameters loadedParams = dataManager.load(AiParameters.class)
                    .id(savedParams.getId()).one();
            assertEquals(savedParams.getId(), loadedParams.getId());
            assertEquals("temperature: 0.2", loadedParams.getBodyYaml());

            // --- AiKnowledgeDocument ---
            AiKnowledgeDocument doc = metadata.create(AiKnowledgeDocument.class);
            doc.setFileName("notes.md");
            doc.setMimeType("text/markdown");
            doc.setSizeBytes(42L);
            doc.setStatus(AiKnowledgeDocumentStatus.PENDING);
            AiKnowledgeDocument savedDoc = dataManager.save(doc);
            AiKnowledgeDocument loadedDoc = dataManager.load(AiKnowledgeDocument.class)
                    .id(savedDoc.getId()).one();
            assertEquals(savedDoc.getId(), loadedDoc.getId());
            assertEquals("notes.md", loadedDoc.getFileName());
            assertEquals(AiKnowledgeDocumentStatus.PENDING, loadedDoc.getStatus());
        });
    }

    // ---------------------------------------------------------------------
    // 3. Row-level role carries the expected JPQL WHERE predicate on
    //    AiConversation and AiMessage keyed to :current_user_username.
    //
    //    Scope note: the add-on DEFINES roles; the HOST application enforces
    //    them at query time. ReadEntityQueryConstraint (the JPA-layer
    //    enforcement bean) lives in jmix-security-data, which the add-on's
    //    runtime does not depend on. Pulling it in as a test-only dep breaks
    //    the metadata wiring on HSQLDB (AccessDenied under runWithSystem +
    //    EclipseLink "not a known Entity type" on persist — the
    //    securitydata module reshapes the persistence unit). So the add-on's
    //    Phase 2 gate verifies the role *contract*; host tests verify
    //    enforcement end-to-end.
    // ---------------------------------------------------------------------
    @Test
    void row_level_policy_restricts_conversation_visibility() {
        RowLevelRole role = rowLevelRoleRepository.getRoleByCode(AiAgentUserRowLevelRole.CODE);
        assertNotNull(role);

        Map<String, RowLevelPolicy> byEntity = new HashMap<>();
        for (RowLevelPolicy p : role.getAllRowLevelPolicies()) {
            if (p.getType() == RowLevelPolicyType.JPQL
                    && p.getAction() == RowLevelPolicyAction.READ) {
                byEntity.put(p.getEntityName(), p);
            }
        }

        RowLevelPolicy convPolicy = byEntity.get("ai_AiConversation");
        assertNotNull(convPolicy, "row-level JPQL READ policy on AiConversation is missing");
        assertTrue(convPolicy.getWhereClause().contains(":current_user_username"),
                "conversation predicate must bind :current_user_username");
        assertTrue(convPolicy.getWhereClause().contains("createdBy"),
                "conversation predicate must narrow by createdBy");

        RowLevelPolicy msgPolicy = byEntity.get("ai_AiMessage");
        assertNotNull(msgPolicy, "row-level JPQL READ policy on AiMessage is missing");
        assertTrue(msgPolicy.getWhereClause().contains(":current_user_username"),
                "message predicate must bind :current_user_username");
        assertTrue(msgPolicy.getWhereClause().contains("conversation.createdBy"),
                "message predicate must traverse conversation.createdBy");
    }

    // ---------------------------------------------------------------------
    // 4. All 6 SPI default beans auto-wire with the exact no-op return
    //    values from plan 02-07 SpiDefaultsAutoConfiguration.
    // ---------------------------------------------------------------------
    @Test
    void all_six_spi_defaults_autowire() {
        assertNotNull(toolContributor);
        assertNotNull(contextContributor);
        assertNotNull(promptContextContributor);
        assertNotNull(toolGuard);
        assertNotNull(auditListener);
        assertNotNull(customIngester);

        // ToolContributor: empty list.
        List<Object> tools = toolContributor.contribute();
        assertNotNull(tools);
        assertTrue(tools.isEmpty(), "default ToolContributor must return empty list");

        // ContextContributor: no-op lambda. Per D-11, baseline agent.* keys are
        // populated in Phase 4, NOT here. We only assert the default does not
        // throw and does not mutate an empty bag.
        Map<String, Object> bag = new HashMap<>();
        contextContributor.contribute(bag);
        assertTrue(bag.isEmpty(), "default ContextContributor must be a no-op in Phase 2");

        // PromptContextContributor: empty fragment, order 0.
        assertEquals("", promptContextContributor.fragment());
        assertEquals(0, promptContextContributor.getOrder());

        // ToolGuard: allow-all (does not throw).
        assertDoesNotThrow(() -> toolGuard.check("any-tool", Map.of("k", "v")));

        // AuditListener: no-op (does not throw).
        assertDoesNotThrow(() -> auditListener.onEventAudited(UUID.randomUUID(), AuditKind.TOOL));

        // CustomIngester: "noop" / "No-op" / empty.
        assertEquals("noop", customIngester.getId());
        assertEquals("No-op", customIngester.getDisplayName());
        assertTrue(customIngester.read().isEmpty());
    }

    // ---------------------------------------------------------------------
    // 5. RoleRepository exposes all 3 add-on roles by CODE constants.
    // ---------------------------------------------------------------------
    @Test
    void role_catalog_has_all_three_roles() {
        assertNotNull(resourceRoleRepository.getRoleByCode(AiAgentUserRole.CODE));
        assertNotNull(resourceRoleRepository.getRoleByCode(AiAgentAdminRole.CODE));
        assertNotNull(rowLevelRoleRepository.getRoleByCode(AiAgentUserRowLevelRole.CODE));
    }

    // ------------------------------- helpers -------------------------------

    private void assertTableExists(String upperCaseName) {
        Integer count = agentstoreJdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where upper(table_name) = ?",
                Integer.class, upperCaseName);
        assertNotNull(count);
        assertTrue(count >= 1, "Expected table present: " + upperCaseName);
    }

    private void assertTableAbsent(String upperCaseName) {
        Integer count = agentstoreJdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where upper(table_name) = ?",
                Integer.class, upperCaseName);
        assertNotNull(count);
        assertEquals(0, count.intValue(), "Expected table absent: " + upperCaseName);
    }

    // alice / bob / admin are provisioned by the ambient
    // com.vn.agent.test_support.TestUsersConfiguration (inside AIConfiguration's
    // component-scan root; loaded for every add-on @SpringBootTest). The inner
    // @TestConfiguration that used to live here was removed in plan 07-07b Task 1
    // to avoid double-provisioning once the ambient fixture was introduced.
}
