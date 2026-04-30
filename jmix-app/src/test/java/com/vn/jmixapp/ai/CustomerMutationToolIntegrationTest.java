package com.vn.jmixapp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vn.agent.entity.AiAuditEvent;
import com.vn.agent.entity.AiToolCallOutcome;
import com.vn.agent.orchestration.RunContext;
import com.vn.agent.security.AiAgentMutationRole;
import com.vn.agent.tools.link.BuiltInLinkTools;
import com.vn.agent.tools.mutation.AiMutationIntent;
import com.vn.agent.tools.mutation.BuiltInMutationTools;
import com.vn.jmixapp.entity.Customer;
import com.vn.jmixapp.entity.Order;
import io.jmix.core.DataManager;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.security.SystemAuthenticator;
import io.jmix.securitydata.entity.RoleAssignmentEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "ai-agent.tools.mutation.enabled=true")
@ActiveProfiles("test")
class CustomerMutationToolIntegrationTest {

    @Autowired
    private BuiltInMutationTools builtInMutationTools;

    @Autowired
    private BuiltInLinkTools builtInLinkTools;

    @Autowired
    private DataManager dataManager;

    @Autowired
    private UnconstrainedDataManager unconstrainedDataManager;

    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private String idempotencyKey;
    private UUID runId;
    private UUID customerId;
    private UUID orderId;
    private UUID insertedMutationRoleAssignmentId;

    @AfterEach
    void cleanUp() {
        if (orderId != null) {
            systemAuthenticator.runWithSystem(() ->
                    dataManager.load(Order.class)
                            .id(orderId)
                            .optional()
                            .ifPresent(dataManager::remove));
        }
        if (customerId != null) {
            systemAuthenticator.runWithSystem(() ->
                    dataManager.load(Customer.class)
                            .id(customerId)
                            .optional()
                            .ifPresent(dataManager::remove));
        }
        if (idempotencyKey != null) {
            systemAuthenticator.runWithSystem(() -> {
                unconstrainedDataManager.load(AiMutationIntent.class)
                        .query("select e from aiMutation_AiMutationIntent e " +
                                "where e.toolName = :toolName and e.idempotencyKey = :key and e.userUsername = :user")
                        .parameter("toolName", "create_record")
                        .parameter("key", idempotencyKey)
                        .parameter("user", "admin")
                        .list()
                        .forEach(unconstrainedDataManager::remove);
                unconstrainedDataManager.load(AiAuditEvent.class)
                        .query("select a from ai_AiAuditEvent a where a.eventName = :eventName " +
                                "and a.argumentsJson like :key")
                        .parameter("eventName", "create_record")
                        .parameter("key", "%" + idempotencyKey + "%")
                        .list()
                        .forEach(unconstrainedDataManager::remove);
            });
        }
        if (runId != null) {
            RunContext.clear();
        }
        if (insertedMutationRoleAssignmentId != null) {
            systemAuthenticator.runWithSystem(() ->
                    unconstrainedDataManager.load(RoleAssignmentEntity.class)
                            .id(insertedMutationRoleAssignmentId)
                            .optional()
                            .ifPresent(unconstrainedDataManager::remove));
        }
    }

    @Test
    void createRecord_canCreateCustomerWithExactListedEntityName() throws Exception {
        grantMutationRoleToAdmin();
        idempotencyKey = UUID.randomUUID().toString();
        runId = UUID.randomUUID();
        RunContext.set(runId);
        RunContext.setConversationId(UUID.randomUUID());
        RunContext.setRootAuditId(UUID.randomUUID());

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("name", "Tool Customer " + System.currentTimeMillis());
        attributes.put("email", "tool.customer@example.com");
        attributes.put("phone", "090123456");

        String json = systemAuthenticator.withUser("admin", () ->
                builtInMutationTools.createRecord("Customer", attributes, idempotencyKey));

        JsonNode result = objectMapper.readTree(json);
        assertThat(result.path("outcome").asText())
                .as("raw result: %s", json)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        customerId = UUID.fromString(result.path("entityId").asText());

        Customer customer = systemAuthenticator.withSystem(() ->
                dataManager.load(Customer.class).id(customerId).one());
        assertThat(customer.getName()).isEqualTo(attributes.get("name"));
        assertThat(customer.getEmail()).isEqualTo(attributes.get("email"));
        assertThat(customer.getPhone()).isEqualTo(attributes.get("phone"));
    }

    @Test
    void createRecord_canCreateOrderWithExistingCustomerReference() throws Exception {
        grantMutationRoleToAdmin();
        idempotencyKey = UUID.randomUUID().toString();
        runId = UUID.randomUUID();
        RunContext.set(runId);
        RunContext.setConversationId(UUID.randomUUID());
        RunContext.setRootAuditId(UUID.randomUUID());

        Customer customer = systemAuthenticator.withSystem(() -> {
            Customer entity = dataManager.create(Customer.class);
            entity.setName("Order Customer " + System.currentTimeMillis());
            entity.setEmail("order.customer@example.com");
            return dataManager.save(entity);
        });
        customerId = customer.getId();

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("number", "ORD-" + System.currentTimeMillis());
        attributes.put("orderDate", "2026-04-29");
        attributes.put("customer", customerId.toString());
        attributes.put("status", "NEW");

        String json = systemAuthenticator.withUser("admin", () ->
                builtInMutationTools.createRecord("jmixapp_Order", attributes, idempotencyKey));

        JsonNode result = objectMapper.readTree(json);
        assertThat(result.path("outcome").asText())
                .as("raw result: %s", json)
                .isEqualTo(AiToolCallOutcome.SUCCESS.getId());
        orderId = UUID.fromString(result.path("entityId").asText());

        Order order = systemAuthenticator.withSystem(() ->
                dataManager.load(Order.class)
                        .id(orderId)
                        .fetchPlan(fetchPlan -> fetchPlan.addFetchPlan("_base").add("customer", "_base"))
                        .one());
        assertThat(order.getNumber()).isEqualTo(attributes.get("number"));
        assertThat(order.getOrderDate().toString()).isEqualTo(attributes.get("orderDate"));
        assertThat(order.getCustomer().getId()).isEqualTo(customerId);
    }

    @Test
    void linkTools_canResolveOrderPrimaryViewsFromEntityName() throws Exception {
        String listJson = systemAuthenticator.withUser("admin", () ->
                builtInLinkTools.generateEntityListLink("jmixapp_Order"));
        JsonNode list = objectMapper.readTree(listJson);
        assertThat(list.path("url").asText())
                .as("raw result: %s", listJson)
                .isEqualTo("/orders");

        UUID id = UUID.randomUUID();
        String detailJson = systemAuthenticator.withUser("admin", () ->
                builtInLinkTools.generateEntityDetailLink("jmixapp_Order", id.toString()));
        JsonNode detail = objectMapper.readTree(detailJson);
        assertThat(detail.path("url").asText())
                .as("raw result: %s", detailJson)
                .isEqualTo("/orders/" + id);
    }

    private void grantMutationRoleToAdmin() {
        systemAuthenticator.runWithSystem(() -> {
            boolean exists = unconstrainedDataManager.load(RoleAssignmentEntity.class)
                    .query("select e from sec_RoleAssignmentEntity e " +
                            "where e.username = :username and e.roleCode = :roleCode and e.roleType = :roleType")
                    .parameter("username", "admin")
                    .parameter("roleCode", AiAgentMutationRole.CODE)
                    .parameter("roleType", "resource")
                    .optional()
                    .isPresent();
            if (!exists) {
                RoleAssignmentEntity assignment = dataManager.create(RoleAssignmentEntity.class);
                assignment.setUsername("admin");
                assignment.setRoleCode(AiAgentMutationRole.CODE);
                assignment.setRoleType("resource");
                RoleAssignmentEntity saved = unconstrainedDataManager.save(assignment);
                insertedMutationRoleAssignmentId = saved.getId();
            }
        });
    }
}
