package com.vn.agent.tools.mutation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.AITestConfiguration;
import com.vn.agent.audit.AuditFieldHasher;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.spi.AuditKind;
import com.vn.agent.test_support.StubChatModelConfiguration;
import com.vn.agent.test_support.StubVectorStoreConfiguration;
import com.vn.agent.tools.mutation.fixture.MutationTestFixture;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan 11-10 Task 3 — TEST-10 audit argumentsJson carries the full call shape.
 *
 * <p>Pins the {@link com.vn.agent.tools.mutation.DiffSerializer} contract by inspecting the
 * persisted {@link AiAuditEvent#getArgumentsJson()} for both a {@code create_record} success
 * and an {@code add_related_record} success. The argumentsJson MUST contain:
 * <ul>
 *   <li>{@code entityName} — the tool's first argument.</li>
 *   <li>{@code id} — present on update / related-write rows; omitted (null/missing) on create.</li>
 *   <li>{@code idempotencyKey} — the supplied key.</li>
 *   <li>For create/update: nested {@code attributes} map. Sensitive values
 *       ({@code jmix.ai-agent.audit.sensitive-fields=secret}) MUST be SHA-256 hashed
 *       (matches {@link AuditFieldHasher#sha256Hex}).</li>
 *   <li>For related writes: {@code relationship} and {@code relatedId}.</li>
 * </ul>
 *
 * <p>The success path is exercised because the must-haves contract requires PROOF that the
 * full argument envelope reaches the audit row on a real save (not just on the failure path).
 * Fixture rows created here are removed in {@code @AfterEach} so concurrent test runs do not
 * collide.
 */
@SpringBootTest(classes = {AITestConfiguration.class, MutationFixturePersistenceTestConfiguration.class},
        properties = {
                "ai-agent.tools.mutation.enabled=true",
                "jmix.ai-agent.audit.hash-sensitive-fields=true",
                "jmix.ai-agent.audit.sensitive-fields=secret"
        })
@ImportAutoConfiguration({
        com.vn.autoconfigure.agent.AIAutoConfiguration.class,
        com.vn.autoconfigure.agent.SpiDefaultsAutoConfiguration.class
})
@Import({StubChatModelConfiguration.class, StubVectorStoreConfiguration.class,
        MutationToolTestUsersConfiguration.class})
class BuiltInMutationToolsAuditArgumentsTest {

    private static final String FIXTURE_ENTITY = "mutationTest_MutationTestFixture";

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private MutationToolTestContext mutationToolTestContext;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private final java.util.List<UUID> seededFixtureIds = new java.util.ArrayList<>();
    private final java.util.List<UUID> seededIntentIds = new java.util.ArrayList<>();
    private final java.util.List<UUID> seededLinkedChildIds = new java.util.ArrayList<>();
    private final java.util.List<UUID> seededLinkedParentIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanRows() {
        systemAuthenticator.runWithSystem(() -> {
            // Order: child rows must be removed before parent rows (FK).
            for (UUID id : seededLinkedChildIds) {
                unconstrainedDataManager.load(
                                com.vn.agent.tools.mutation.fixture.MutationLinkedChildFixture.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
            for (UUID id : seededLinkedParentIds) {
                unconstrainedDataManager.load(
                                com.vn.agent.tools.mutation.fixture.MutationLinkedParentFixture.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
            for (UUID id : seededFixtureIds) {
                unconstrainedDataManager.load(MutationTestFixture.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
            for (UUID id : seededIntentIds) {
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .id(id).optional().ifPresent(unconstrainedDataManager::remove);
            }
        });
        seededFixtureIds.clear();
        seededIntentIds.clear();
        seededLinkedChildIds.clear();
        seededLinkedParentIds.clear();
    }

    @Test
    void createRecord_argumentsJson_includesFullEnvelopeWithHashedSensitiveValues() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String secretValue = "top-secret-attribute-value";
        String secretHash = AuditFieldHasher.sha256Hex(secretValue);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Alice");
        attributes.put("secret", secretValue);
        attributes.put("priority", 7);

        String json = mutationToolTestContext.withMutationRun("mutation-user", () ->
                builtInMutationTools.createRecord(FIXTURE_ENTITY, attributes, idempotencyKey));

        // Outcome and entityId for cleanup.
        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(json);
        assertThat(parsed.path("outcome").asText())
                .as("create_record must succeed for the audit assertion to be meaningful; raw=%s", json)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        seededFixtureIds.add(UUID.fromString(parsed.path("entityId").asText()));
        recordIntentForCleanup("create_record", idempotencyKey, "mutation-user");

        // Locate the SUCCESS audit row for create_record.
        AiAuditEvent row = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.eventName = :n and a.outcome = :o "
                                + "and a.argumentsJson like :arg "
                                + "order by a.startedAt desc")
                        .parameter("n", "create_record")
                        .parameter("o", AiToolCallOutcome.SUCCESS)
                        .parameter("arg", "%" + idempotencyKey + "%")
                        .list()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no SUCCESS create_record audit row found")));
        assertThat(row.getKind()).isEqualTo(AuditKind.TOOL);

        JsonNode args = mapper.readTree(row.getArgumentsJson());
        assertThat(args.path("entityName").asText()).isEqualTo(FIXTURE_ENTITY);
        assertThat(args.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
        // Create row carries no top-level id (the new entity id is in resultSummary diff).
        assertThat(args.path("id").isMissingNode() || args.path("id").isNull())
                .as("create_record argumentsJson must omit top-level id; raw=%s", row.getArgumentsJson())
                .isTrue();

        JsonNode attrs = args.path("attributes");
        assertThat(attrs.isObject()).isTrue();
        assertThat(attrs.path("name").asText()).isEqualTo("Alice");
        assertThat(attrs.path("priority").asInt()).isEqualTo(7);
        assertThat(attrs.path("secret").asText())
                .as("sensitive 'secret' value must be SHA-256 hashed in argumentsJson (AUD-07)")
                .isEqualTo(secretHash);
        assertThat(attrs.path("secret").asText())
                .as("argumentsJson must NOT contain the raw secret value")
                .isNotEqualTo(secretValue);
    }

    @Test
    void updateRecord_argumentsJson_includesEntityIdAndHashedSensitiveAttributes() throws Exception {
        UUID fixtureId = systemAuthenticator.withSystem(() -> {
            MutationTestFixture fixture = unconstrainedDataManager.create(MutationTestFixture.class);
            fixture.setName("Before update");
            fixture.setSecret("before-secret");
            fixture.setPriority(1);
            return unconstrainedDataManager.save(fixture).getId();
        });
        seededFixtureIds.add(fixtureId);

        String idempotencyKey = UUID.randomUUID().toString();
        String secretValue = "updated-secret-value";
        String secretHash = AuditFieldHasher.sha256Hex(secretValue);

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "After update");
        attributes.put("secret", secretValue);
        attributes.put("priority", 9);

        String json = mutationToolTestContext.withMutationRun("mutation-user", () ->
                builtInMutationTools.updateRecord(
                        FIXTURE_ENTITY,
                        fixtureId.toString(),
                        attributes,
                        idempotencyKey));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(json);
        assertThat(parsed.path("outcome").asText())
                .as("update_record must succeed for the audit assertion to be meaningful; raw=%s", json)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        assertThat(parsed.path("entityId").asText()).isEqualTo(fixtureId.toString());
        recordIntentForCleanup("update_record", idempotencyKey, "mutation-user");

        AiAuditEvent row = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.eventName = :n and a.outcome = :o "
                                + "and a.argumentsJson like :arg "
                                + "order by a.startedAt desc")
                        .parameter("n", "update_record")
                        .parameter("o", AiToolCallOutcome.SUCCESS)
                        .parameter("arg", "%" + idempotencyKey + "%")
                        .list()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no SUCCESS update_record audit row found")));

        JsonNode args = mapper.readTree(row.getArgumentsJson());
        assertThat(args.path("entityName").asText()).isEqualTo(FIXTURE_ENTITY);
        assertThat(args.path("id").asText()).isEqualTo(fixtureId.toString());
        assertThat(args.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);

        JsonNode attrs = args.path("attributes");
        assertThat(attrs.isObject()).isTrue();
        assertThat(attrs.path("name").asText()).isEqualTo("After update");
        assertThat(attrs.path("priority").asInt()).isEqualTo(9);
        assertThat(attrs.path("secret").asText()).isEqualTo(secretHash);
        assertThat(attrs.path("secret").asText())
                .as("update_record argumentsJson must NOT contain the raw secret value")
                .isNotEqualTo(secretValue);
    }

    @Test
    void addRelatedRecord_argumentsJson_includesFullCallShape() throws Exception {
        // Seed a parent and a child via UnconstrainedDataManager so the related write succeeds.
        UUID parentId = systemAuthenticator.withSystem(() -> {
            com.vn.agent.tools.mutation.fixture.MutationLinkedParentFixture p =
                    unconstrainedDataManager.create(
                            com.vn.agent.tools.mutation.fixture.MutationLinkedParentFixture.class);
            p.setName("parent-link-test");
            return unconstrainedDataManager.save(p).getId();
        });
        UUID childId = systemAuthenticator.withSystem(() -> {
            com.vn.agent.tools.mutation.fixture.MutationLinkedChildFixture c =
                    unconstrainedDataManager.create(
                            com.vn.agent.tools.mutation.fixture.MutationLinkedChildFixture.class);
            c.setLabel("child-link-test");
            return unconstrainedDataManager.save(c).getId();
        });
        // Track seeded rows for cleanup.
        seededLinkedFixtureIds(parentId, childId);

        String idempotencyKey = UUID.randomUUID().toString();
        String relationship = "linkedChildren";

        String json = mutationToolTestContext.withMutationRun("mutation-user", () ->
                builtInMutationTools.addRelatedRecord(
                        "mutationTest_MutationLinkedParentFixture",
                        parentId.toString(),
                        relationship,
                        childId.toString(),
                        idempotencyKey));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode parsed = mapper.readTree(json);
        assertThat(parsed.path("outcome").asText())
                .as("add_related_record must succeed; raw=%s", json)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        recordIntentForCleanup("add_related_record", idempotencyKey, "mutation-user");

        AiAuditEvent row = systemAuthenticator.withSystem(() ->
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.eventName = :n and a.outcome = :o "
                                + "and a.argumentsJson like :arg "
                                + "order by a.startedAt desc")
                        .parameter("n", "add_related_record")
                        .parameter("o", AiToolCallOutcome.SUCCESS)
                        .parameter("arg", "%" + idempotencyKey + "%")
                        .list()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "no SUCCESS add_related_record audit row found")));

        JsonNode args = mapper.readTree(row.getArgumentsJson());
        assertThat(args.path("entityName").asText())
                .isEqualTo("mutationTest_MutationLinkedParentFixture");
        assertThat(args.path("id").asText()).isEqualTo(parentId.toString());
        assertThat(args.path("relationship").asText()).isEqualTo(relationship);
        assertThat(args.path("relatedId").asText()).isEqualTo(childId.toString());
        assertThat(args.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
    }

    private void seededLinkedFixtureIds(UUID parentId, UUID childId) {
        seededLinkedParentIds.add(parentId);
        seededLinkedChildIds.add(childId);
    }

    private void recordIntentForCleanup(String toolName, String idempotencyKey, String username) {
        systemAuthenticator.runWithSystem(() ->
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :t and e.idempotencyKey = :k and e.userUsername = :u")
                        .parameter("t", toolName)
                        .parameter("k", idempotencyKey)
                        .parameter("u", username)
                        .optional()
                        .ifPresent(intent -> seededIntentIds.add(intent.getId())));
    }
}
