package com.vn.jmixapp.ai;

import com.vn.agent.spi.ToolContributor;
import com.vn.jmixapp.entity.Customer;
import com.vn.jmixapp.entity.Order;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FetchPlans;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sample host-side {@link ToolContributor} impl (Phase 3 D-15 / SPI-01).
 *
 * <p>Demonstrates the real extension pattern a jmix-app would use: a host bean contributes
 * additional {@code @Tool} methods that compose with the add-on's six built-in tools via
 * {@code AgentToolCallbacks.forCurrentUser()}. Joins two domain entities (Order + Customer)
 * through {@link DataManager} to exercise the per-request assembly pipeline meaningfully.</p>
 *
 * <p><b>Security posture:</b> host-side {@code @Tool} methods are <em>trusted code</em>. The
 * add-on's TOOL-04 / D-16 read-only bytecode enforcement scopes only to
 * {@code BuiltInDataTools}; host contributor tools are not scanned. This method is still
 * read-only and parameterized: the JPQL string is fully synthesized by this class (no LLM
 * input flows into the string; {@code customerId} is carried as a named parameter). Phase 6's
 * {@code ToolGuard} will add a per-call veto layer on top.</p>
 */
@Component
public class OrderSummaryToolContributor implements ToolContributor {

    private final DataManager dataManager;
    private final FetchPlans fetchPlans;

    // CLAUDE.md: constructor injection only — no field injection.
    public OrderSummaryToolContributor(DataManager dataManager, FetchPlans fetchPlans) {
        this.dataManager = dataManager;
        this.fetchPlans = fetchPlans;
    }

    @Override
    public List<Object> contribute() {
        return List.of(this);
    }

    @Tool(name = "summarize_customer_orders",
            description = "Summarize a customer's orders: per-order number, date, status, and total amount. "
                    + "Orders are loaded via the customer.id filter — read-only.")
    public Map<String, Object> summarizeCustomerOrders(
            @ToolParam(description = "Customer UUID from find_records('jmixapp_Customer', ...)")
            String customerId) {
        UUID cid = UUID.fromString(customerId);

        Customer customer = dataManager.load(Customer.class)
                .id(cid)
                .fetchPlan(FetchPlan.INSTANCE_NAME)
                .optional().orElse(null);
        if (customer == null) {
            return Map.of("error", "not_found", "reason", "no customer with id " + customerId);
        }

        // Fetch plan: Order + _instance_name + lines (_instance_name) so totalAmount computes
        // without N+1.
        FetchPlan fp = fetchPlans.builder(Order.class)
                .addFetchPlan(FetchPlan.INSTANCE_NAME)
                .add("lines", fpb -> fpb.addFetchPlan(FetchPlan.INSTANCE_NAME))
                .build();
        List<Order> orders = dataManager.load(Order.class)
                .query("select o from jmixapp_Order o where o.customer.id = :cid")
                .parameter("cid", cid)
                .fetchPlan(fp)
                .list();

        List<Map<String, Object>> items = orders.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", o.getNumber());
            m.put("orderDate", o.getOrderDate());
            m.put("status", o.getStatus() == null ? null : o.getStatus().name());
            m.put("totalAmount", o.getTotalAmount());
            return m;
        }).collect(Collectors.toList());

        BigDecimal grand = orders.stream()
                .map(Order::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customerId", customerId);
        out.put("customerName", customer.toString()); // uses @InstanceName
        out.put("orderCount", orders.size());
        out.put("grandTotal", grand);
        out.put("orders", items);
        return out;
    }
}
