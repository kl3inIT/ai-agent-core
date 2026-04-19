package com.vn.jmixapp.ai;

import com.vn.agent.tools.AgentToolCallbacks;
import com.vn.agent.tools.BuiltInDataTools;
import com.vn.jmixapp.entity.Customer;
import com.vn.jmixapp.entity.Order;
import com.vn.jmixapp.test_support.AuthenticatedAsAdmin;
import io.jmix.core.DataManager;
import io.jmix.core.Metadata;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 Plan 05 / success-criterion-#3 integration test.
 *
 * <p>Three assertions exercise the full Phase 3 stack end-to-end in a real Jmix
 * {@code @SpringBootTest} context:</p>
 * <ol>
 *   <li><b>perRequestAssemblyIncludesBuiltInsAndHostContributor</b> — asserts
 *       {@link AgentToolCallbacks#forCurrentUser()} composes the six built-in @Tool methods
 *       with the host's {@link OrderSummaryToolContributor} (SPI-01).</li>
 *   <li><b>findRecordsOrderRoundTrip</b> — seeds a Customer + Order under
 *       {@link SystemAuthenticator} and asserts
 *       {@code BuiltInDataTools.findRecords("jmixapp_Order", null, null)} returns the seeded
 *       row's number via the real {@link DataManager} path (success criterion #3 DataManager
 *       round-trip).</li>
 *   <li><b>describeEntityAdminPathSurfacesStructuredJson</b> — asserts admin-user
 *       {@code describeEntity} returns structured JSON that names the entity and at least one
 *       expected attribute (smoke check that describe_entity plumbing works end-to-end).</li>
 * </ol>
 *
 * <p><b>Restricted-user denied-attribute absence</b> — success criterion #3's second half — is
 * covered authoritatively at unit level by Plan 04's {@code EffectiveSchemaComputerTest}
 * (see 03-CONTEXT.md `## Deferred Ideas`). This class intentionally does NOT add a
 * restricted-user {@code @SpringBootTest} variant.</p>
 *
 * <p><b>ChatService-routed mock-ChatModel variant</b> is deferred to Phase 4 alongside
 * {@code ChatClientFactory} + advisor chain wiring (CHAT-02/CHAT-03). Here we call the
 * {@link BuiltInDataTools} @Tool method directly — that is the authoritative Phase 3
 * coverage of the DataManager path.</p>
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class ChatServiceToolIntegrationTest {

    @Autowired AgentToolCallbacks agentToolCallbacks;
    @Autowired BuiltInDataTools builtInDataTools;
    @Autowired DataManager dataManager;
    @Autowired Metadata metadata;
    @Autowired SystemAuthenticator systemAuthenticator;

    @Test
    void perRequestAssemblyIncludesBuiltInsAndHostContributor() {
        ToolCallback[] cbs = agentToolCallbacks.forCurrentUser();
        assertThat(cbs.length).isGreaterThanOrEqualTo(7);
        Set<String> names = Arrays.stream(cbs)
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertThat(names).contains(
                "list_entities",
                "describe_entity",
                "find_records",
                "get_record",
                "count_records",
                "get_related_records",
                "summarize_customer_orders"
        );
    }

    @Test
    void findRecordsOrderRoundTrip() {
        // Seed one Customer + one Order under system auth so row-level policies don't
        // interfere with the admin-path read in the assertion below. Use a time-stamped
        // order number to avoid cross-run collisions (the HSQLDB file persists between
        // test-class boots — see 03-05-PLAN.md threat T-03-26).
        String orderNumber = "ORD-" + System.currentTimeMillis();
        systemAuthenticator.runWithSystem(() -> {
            Customer c = metadata.create(Customer.class);
            // Customer entity has `name`, `email`, `phone` (verified Customer.java). The
            // plan's setFirstName/setLastName/setEmail referred to an earlier schema;
            // adapted to the real fields (Rule 1 deviation recorded in 03-05-SUMMARY.md).
            c.setName("Test Buyer " + System.currentTimeMillis());
            c.setEmail("test.buyer@example.com");
            dataManager.save(c);

            Order o = metadata.create(Order.class);
            o.setNumber(orderNumber);
            o.setOrderDate(LocalDate.now());
            o.setCustomer(c);
            dataManager.save(o);
        });

        // Call the @Tool method directly via the @Component injection path. The test
        // validates the DataManager round-trip, not the LLM-hosted invocation; the
        // ChatService-routed variant is deferred to Phase 4 (see class-level Javadoc).
        String json = builtInDataTools.findRecords("jmixapp_Order", null, null);
        assertThat(json).contains(orderNumber);
        assertThat(json).contains("\"truncated\":false");
    }

    @Test
    void describeEntityAdminPathSurfacesStructuredJson() {
        // Admin context (from AuthenticatedAsAdmin) should permit Order at the entity level
        // and surface its attributes. This is a smoke assertion that describe_entity plumbing
        // works end-to-end in @SpringBootTest. Denied-attribute absence (success criterion
        // #3 second half) is covered authoritatively by EffectiveSchemaComputerTest (Plan 04
        // Task 2) at unit level — see 03-CONTEXT.md `## Deferred Ideas`. Do NOT add a
        // restricted-user integration variant here.
        String json = builtInDataTools.describeEntity("jmixapp_Order");
        assertThat(json).contains("jmixapp_Order");
        assertThat(json).containsAnyOf("number", "orderDate", "customer", "status");
    }
}
