package com.vn.agent.tools.mutation;

import io.jmix.core.DataManager;
import io.jmix.core.EntitySet;
import io.jmix.core.SaveContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single transactional save boundary for {@code BuiltInMutationTools} (Plan 11-07A).
 *
 * <p><b>Why a separate @Component:</b> Spring's @Transactional is implemented via a proxy.
 * If {@code BuiltInMutationTools} declared a private/self-invoked @Transactional save method,
 * the proxy would be bypassed and the @Transactional annotation would be silently ignored
 * (Spring self-invocation pitfall). Putting the save method on a different bean makes the
 * proxy boundary explicit — every {@code mutationSaveExecutor.save(...)} call from the tool
 * crosses the proxy and runs inside a real transaction.
 *
 * <p>Uses regular {@link DataManager} (NOT {@code UnconstrainedDataManager}) so user-level
 * row policies, lifecycle events, and entity listeners run on the host save (MUT-03).
 *
 * <p>v1.1 ships {@link #save(Object)} and {@link #saveAll(Object...)} only. There is NO
 * {@code remove} method: {@code remove_related_record} unlinks via FK clearing only and
 * never deletes a child row in v1.1 (D-07).
 */
@Component
public class MutationSaveExecutor {

    private final DataManager dataManager;

    public MutationSaveExecutor(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    /** Single-entity save (create_record, update_record). */
    @Transactional
    public Object save(Object entity) {
        return dataManager.save(entity);
    }

    /**
     * EntitySet save for parent + child writes (add_related_record / remove_related_record
     * non-deleting unlink). Returns Jmix's {@link EntitySet} carrying both saved instances.
     */
    @Transactional
    public EntitySet saveAll(Object... entities) {
        return dataManager.save(entities);
    }

    /**
     * Bulk save for the {@code bulk_save_records} tool (Phase 13 D-02). SINGLE
     * {@code @Transactional} span — any RuntimeException from
     * {@link DataManager#save(SaveContext)} rolls back ALL rows in the SaveContext
     * (rollback-all invariant).
     *
     * <p>Uses regular {@link DataManager} (NOT {@code UnconstrainedDataManager}) so user row
     * policies, lifecycle events, listeners, and Bean Validation run on every per-row save
     * (Phase 11 MUT-03 / SEC-07).
     *
     * <p>This method MUST be called from {@code BuiltInMutationTools.bulkSaveRecords} via the
     * Spring proxy — NEVER add {@code @Transactional} to {@code bulkSaveRecords} itself
     * (self-invocation pitfall; see class JavaDoc).
     */
    @Transactional
    public EntitySet bulkSave(SaveContext saveContext) {
        return dataManager.save(saveContext);
    }
}
