package com.vn.agent.testsupport.data;

import java.util.function.Consumer;

/**
 * SPI for provisioning an entity instance with sensible defaults. Implementations are
 * consumed by {@link EntityTestData#createWithDefaults(TestDataProvider)} and friends so
 * each test class can express domain fixtures (e.g. {@code AnAdminUser}, {@code APaidPlan})
 * declaratively instead of repeating field-set boilerplate.
 *
 * @param <Entity> the type of the entity being provisioned
 */
public interface TestDataProvider<Entity> extends Consumer<Entity> {

    Class<Entity> getEntityClass();
}
