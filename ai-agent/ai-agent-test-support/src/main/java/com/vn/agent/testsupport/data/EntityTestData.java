package com.vn.agent.testsupport.data;

import io.jmix.core.DataManager;
import io.jmix.core.Id;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.core.validation.EntityValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Save/create helper that wraps {@link DataManager} with a pre-save Jakarta-Validator check.
 * Mirrors {@code com.insurance.common.test_support.EntityTestData} from jmix-insurance.
 *
 * <p>ai-agent specific extension: exposes <em>both</em> the constrained {@link DataManager}
 * and the {@link UnconstrainedDataManager}. Default mode (the Spring-managed bean) uses the
 * constrained manager, matching insurance semantics. Tests that need to bypass entity
 * policies — most {@code Ai*} agentstore writes under {@code jmix-security-data} — call
 * {@link #unconstrained()} to get a sibling helper backed by the unconstrained manager. See
 * {@code feedback_jmix_unconstrained_for_system_writes} for the architectural rationale.
 */
@Component
public class EntityTestData {

    private final DataManager dataManager;
    private final UnconstrainedDataManager unconstrainedDataManager;
    private final Validator validator;
    private final boolean useUnconstrained;

    @Autowired
    public EntityTestData(DataManager dataManager,
                          UnconstrainedDataManager unconstrainedDataManager,
                          Validator validator) {
        this(dataManager, unconstrainedDataManager, validator, false);
    }

    private EntityTestData(DataManager dataManager,
                           UnconstrainedDataManager unconstrainedDataManager,
                           Validator validator,
                           boolean useUnconstrained) {
        this.dataManager = dataManager;
        this.unconstrainedDataManager = unconstrainedDataManager;
        this.validator = validator;
        this.useUnconstrained = useUnconstrained;
    }

    /**
     * @return a sibling helper that routes create / save / load through
     *         {@link UnconstrainedDataManager}. Use for {@code Ai*} agentstore writes that must
     *         bypass entity policies (audit, seed, ingestion).
     */
    public EntityTestData unconstrained() {
        return new EntityTestData(dataManager, unconstrainedDataManager, validator, true);
    }

    public <Entity> Entity createWithDefaults(TestDataProvider<Entity> testDataProvider) {
        return createWithDefaults(testDataProvider.getEntityClass(), testDataProvider);
    }

    public <Entity> Entity createWithDefaults(TestDataProvider<Entity> testDataProvider,
                                              Consumer<Entity> entityConsumer) {
        Entity entity = createWithDefaults(testDataProvider.getEntityClass(), testDataProvider);
        entityConsumer.accept(entity);
        return entity;
    }

    public <Entity> Entity createWithDefaults(Class<Entity> entityType,
                                              Consumer<Entity> entityConsumer) {
        Entity entity = create(entityType);
        entityConsumer.accept(entity);
        return entity;
    }

    public <Entity> Entity create(Class<Entity> entityType) {
        return useUnconstrained
                ? unconstrainedDataManager.create(entityType)
                : dataManager.create(entityType);
    }

    public <Entity> Entity save(Entity entity) {
        ensureIsValid(entity);
        return useUnconstrained
                ? unconstrainedDataManager.save(entity)
                : dataManager.save(entity);
    }

    public <Entity> Entity saveWithDefaults(TestDataProvider<Entity> testDataProvider) {
        return save(createWithDefaults(testDataProvider));
    }

    public <Entity> Entity saveWithDefaults(TestDataProvider<Entity> testDataProvider,
                                            Consumer<Entity> entityConsumer) {
        return save(createWithDefaults(testDataProvider, entityConsumer));
    }

    public <Entity> Entity saveWithDefaults(Class<Entity> entityType,
                                            Consumer<Entity> entityConsumer) {
        Entity entity = create(entityType);
        entityConsumer.accept(entity);
        return save(entity);
    }

    public <Entity> List<Entity> loadAll(Class<Entity> entityType) {
        return useUnconstrained
                ? unconstrainedDataManager.load(entityType).all().list()
                : dataManager.load(entityType).all().list();
    }

    public <Entity> Entity reload(Id<Entity> entityId) {
        return useUnconstrained
                ? unconstrainedDataManager.load(entityId).one()
                : dataManager.load(entityId).one();
    }

    private <T> void ensureIsValid(T entity) {
        Set<ConstraintViolation<T>> violations = validate(entity);
        if (!violations.isEmpty()) {
            throw new EntityValidationException("Entity validation failed: ", violations);
        }
    }

    private <T> Set<ConstraintViolation<T>> validate(T entity) {
        return validator.validate(entity, Default.class);
    }
}
