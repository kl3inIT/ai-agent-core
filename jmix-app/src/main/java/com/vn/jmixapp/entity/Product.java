package com.vn.jmixapp.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

@JmixEntity
@Entity
@Table(name = "PRODUCT", indexes = {
        @Index(name = "IDX_PRODUCT__ON_SKU", columnList = "SKU", unique = true),
        @Index(name = "IDX_PRODUCT__ON_RECOMMENDED_FOR_CUSTOMER", columnList = "RECOMMENDED_FOR_CUSTOMER_ID")
})
public class Product {

    @Id
    @Column(name = "ID")
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @NotNull
    @Column(name = "NAME", nullable = false)
    private String name;

    @NotNull
    @Column(name = "SKU", nullable = false, length = 64)
    private String sku;

    @NotNull
    @PositiveOrZero
    @Column(name = "PRICE", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "RECOMMENDED_FOR_CUSTOMER_ID")
    private Customer recommendedForCustomer;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Customer getRecommendedForCustomer() { return recommendedForCustomer; }
    public void setRecommendedForCustomer(Customer recommendedForCustomer) { this.recommendedForCustomer = recommendedForCustomer; }
}
