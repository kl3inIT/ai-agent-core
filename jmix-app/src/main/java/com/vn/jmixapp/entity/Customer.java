package com.vn.jmixapp.entity;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@JmixEntity
@Entity
@Table(name = "CUSTOMER", indexes = {
        @Index(name = "IDX_CUSTOMER__ON_EMAIL", columnList = "EMAIL")
})
public class Customer {

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

    @Email
    @Column(name = "EMAIL")
    private String email;

    @Column(name = "PHONE", length = 32)
    private String phone;

    @OneToMany(mappedBy = "recommendedForCustomer")
    private List<Product> recommendedProducts;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public List<Product> getRecommendedProducts() { return recommendedProducts; }
    public void setRecommendedProducts(List<Product> recommendedProducts) { this.recommendedProducts = recommendedProducts; }
}
