package com.vn.jmixapp.security;

import com.vn.jmixapp.entity.Customer;
import com.vn.jmixapp.entity.Order;
import com.vn.jmixapp.entity.OrderLine;
import com.vn.jmixapp.entity.Product;
import io.jmix.security.model.EntityAttributePolicyAction;
import io.jmix.security.model.EntityPolicyAction;
import io.jmix.security.role.annotation.EntityAttributePolicy;
import io.jmix.security.role.annotation.EntityPolicy;
import io.jmix.security.role.annotation.ResourceRole;
import io.jmix.securityflowui.role.annotation.MenuPolicy;
import io.jmix.securityflowui.role.annotation.ViewPolicy;

@ResourceRole(name = "Sample Data Access", code = SampleDataRole.CODE)
public interface SampleDataRole {

    String CODE = "sample-data-access";

    @EntityPolicy(entityClass = Customer.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = Product.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = Order.class, actions = EntityPolicyAction.ALL)
    @EntityPolicy(entityClass = OrderLine.class, actions = EntityPolicyAction.ALL)
    @EntityAttributePolicy(entityClass = Customer.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityAttributePolicy(entityClass = Product.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityAttributePolicy(entityClass = Order.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @EntityAttributePolicy(entityClass = OrderLine.class, attributes = "*", action = EntityAttributePolicyAction.MODIFY)
    @ViewPolicy(viewIds = {
            "Customer.list", "Customer.detail",
            "Product.list", "Product.detail",
            "Order.list", "Order.detail",
            "OrderLine.detail"
    })
    @MenuPolicy(menuIds = {"Customer.list", "Product.list", "Order.list"})
    void sampleData();
}
