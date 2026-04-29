package com.vn.agent.tools.mutation.fixture;

import io.jmix.core.entity.annotation.JmixGeneratedValue;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Test-only host entity for Phase 11 mutation tools.
 *
 * <p>Owned by Plan 11-07B (creates the fixture) and reused read-only by Plans 11-10 (TEST-10..13)
 * and 11-11. Lives in the project's <b>main</b> store (NOT {@code agentstore}) because
 * {@code BuiltInMutationTools} writes target host entities while {@code AiMutationIntent}
 * lives in agentstore — we exercise the cross-store boundary the production code crosses.
 *
 * <p>Shape (locked by 11-07B must-haves contract):
 * <ul>
 *     <li>{@code name} — plain String, used to verify scalar create/update success.</li>
 *     <li>{@code secret} — plain String, configured as a sensitive field in some tests
 *         to verify {@link com.vn.agent.tools.mutation.DiffSerializer} hashing.</li>
 *     <li>{@code priority} — Integer, used to verify
 *         {@link com.vn.agent.filter.FilterLiteralValueConverter} type coercion
 *         ({@code parameter_conversion_error} on a non-numeric string).</li>
 * </ul>
 */
@JmixEntity
@Entity(name = "mutationTest_MutationTestFixture")
@Table(name = "MUTATION_TEST_FIXTURE")
public class MutationTestFixture {

    @Id
    @Column(name = "ID", nullable = false)
    @JmixGeneratedValue
    private UUID id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @Column(name = "NAME_", length = 255)
    private String name;

    @Column(name = "SECRET", length = 255)
    private String secret;

    @Column(name = "PRIORITY")
    private Integer priority;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }
}
