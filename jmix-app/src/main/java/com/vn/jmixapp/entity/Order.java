package com.vn.jmixapp.entity;

import io.jmix.core.DeletePolicy;
import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.entity.annotation.OnDelete;
import io.jmix.core.metamodel.annotation.Composition;
import io.jmix.core.metamodel.annotation.DependsOnProperties;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import io.jmix.core.metamodel.annotation.JmixProperty;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JmixEntity
@Entity
@Table(name = "CUSTOMER_ORDER", indexes = {
        @Index(name = "IDX_CUSTOMER_ORDER__ON_NUMBER", columnList = "NUMBER_", unique = true),
        @Index(name = "IDX_CUSTOMER_ORDER__ON_CUSTOMER", columnList = "CUSTOMER_ID")
})
public class Order {

    @Id
    @Column(name = "ID")
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @NotNull
    @Column(name = "NUMBER_", nullable = false, length = 32)
    private String number;

    @NotNull
    @Column(name = "ORDER_DATE", nullable = false)
    private LocalDate orderDate;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(DeletePolicy.DENY)
    @JoinColumn(name = "CUSTOMER_ID", nullable = false)
    private Customer customer;

    @Column(name = "STATUS", length = 32)
    private String status;

    @Composition
    @OnDelete(DeletePolicy.CASCADE)
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLine> lines;

    @InstanceName
    @DependsOnProperties({"number", "orderDate"})
    public String getDisplayName() {
        return String.format("%s (%s)", number, orderDate);
    }

    @JmixProperty
    @DependsOnProperties("lines")
    public BigDecimal getTotalAmount() {
        if (lines == null) return BigDecimal.ZERO;
        return lines.stream()
                .map(OrderLine::getLineAmount)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public OrderStatus getStatus() { return status == null ? null : OrderStatus.fromId(status); }
    public void setStatus(OrderStatus status) { this.status = status == null ? null : status.getId(); }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public LocalDate getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDate orderDate) { this.orderDate = orderDate; }
    public Customer getCustomer() { return customer; }
    public void setCustomer(Customer customer) { this.customer = customer; }
    public List<OrderLine> getLines() { return lines; }
    public void setLines(List<OrderLine> lines) { this.lines = lines; }
}
