package com.vn.jmixapp.service;

import com.vn.jmixapp.entity.Order;
import com.vn.jmixapp.entity.OrderLine;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class OrderService {

    private final DataManager dataManager;

    public OrderService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotal(UUID orderId) {
        Order order = dataManager.load(Order.class)
                .id(orderId)
                .fetchPlan(fp -> fp.addFetchPlan("_base").add("lines", lp -> lp.addFetchPlan("_base").add("product", "_base")))
                .one();
        if (order.getLines() == null) return BigDecimal.ZERO;
        return order.getLines().stream()
                .map(OrderLine::getLineAmount)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
