package com.vn.agent.exposure;

import com.vn.agent.metadata.CurrentUserSchemaAccess;
import io.jmix.core.AccessManager;
import io.jmix.core.accesscontext.CrudEntityContext;
import io.jmix.core.metamodel.model.MetaClass;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link LlmExposurePolicy} — Phase 10 LLM visibility boundary
 * (delegate-and-narrow over {@link CurrentUserSchemaAccess}).
 *
 * <p>Asserts the per-method behavior contract from PLAN.md task 2 &lt;behavior&gt;:
 * <ul>
 *     <li>{@code getReadableSchema()} loads denylist ONCE then removes denied entries
 *         (Pitfall #1: NOT a per-entity loop)</li>
 *     <li>{@code canReadEntity()} = {@code delegate.canReadEntity AND NOT denied}</li>
 *     <li>{@code canReadAttribute()} = pure pass-through to delegate</li>
 *     <li>{@code canModify()} = {@code AccessManager.isUpdatePermitted AND NOT denied}</li>
 *     <li>{@code getDenylistedEntityNames()} = repository.findEnabledExcludedEntityNames()</li>
 * </ul>
 */
class LlmExposurePolicyTest {

    private static MetaClass entityMock(String name) {
        MetaClass mc = mock(MetaClass.class);
        lenient().when(mc.getName()).thenReturn(name);
        return mc;
    }

    @Test
    void getReadableSchemaRemovesDenylistedEntities() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        MetaClass mcVisible = entityMock("entityVisible");
        MetaClass mcHidden = entityMock("entityHidden");
        Map<MetaClass, Set<String>> base = new LinkedHashMap<>();
        base.put(mcVisible, Set.of("name"));
        base.put(mcHidden, Set.of("name"));
        when(delegate.getReadableSchema()).thenReturn(base);
        when(ruleRepository.findEnabledExcludedEntityNames())
                .thenReturn(new LinkedHashSet<>(Set.of("entityHidden")));

        LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
        Map<MetaClass, Set<String>> out = policy.getReadableSchema();

        assertThat(out.keySet()).extracting(MetaClass::getName)
                .containsExactly("entityVisible")
                .doesNotContain("entityHidden");
    }

    @Test
    void getReadableSchemaWithEmptyDenylistReturnsBaseUnchanged() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        MetaClass mcA = entityMock("entityA");
        Map<MetaClass, Set<String>> base = new LinkedHashMap<>();
        base.put(mcA, Set.of("name"));
        when(delegate.getReadableSchema()).thenReturn(base);
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(Set.of());

        LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
        Map<MetaClass, Set<String>> out = policy.getReadableSchema();

        assertThat(out.keySet()).extracting(MetaClass::getName).containsExactly("entityA");
    }

    @Test
    void getReadableSchemaCallsRepositoryOnceNotPerEntity() {
        // Pitfall #1: denylist must be loaded once at top, not per-entity.
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        Map<MetaClass, Set<String>> base = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            base.put(entityMock("entity" + i), Set.of("name"));
        }
        when(delegate.getReadableSchema()).thenReturn(base);
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(Set.of("entity3"));

        LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
        policy.getReadableSchema();

        verify(ruleRepository, times(1)).findEnabledExcludedEntityNames();
    }

    @Test
    void canReadEntityIsFalseWhenDenied() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        MetaClass mc = entityMock("entityHidden");
        when(delegate.canReadEntity(mc)).thenReturn(true);
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(Set.of("entityHidden"));

        LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
        assertThat(policy.canReadEntity(mc)).isFalse();
    }

    @Test
    void canReadEntityIsFalseWhenDelegateDenies() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        MetaClass mc = entityMock("entityA");
        when(delegate.canReadEntity(mc)).thenReturn(false);
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(Set.of());

        LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
        assertThat(policy.canReadEntity(mc)).isFalse();
    }

    @Test
    void canReadEntityIsTrueWhenDelegateAllowsAndNotDenied() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        MetaClass mc = entityMock("entityA");
        when(delegate.canReadEntity(mc)).thenReturn(true);
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(Set.of("otherEntity"));

        LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
        assertThat(policy.canReadEntity(mc)).isTrue();
    }

    @Test
    void canReadAttributeIsPureDelegate() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        MetaClass mc = entityMock("entityA");
        when(delegate.canReadAttribute(mc, "field")).thenReturn(true);

        LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
        assertThat(policy.canReadAttribute(mc, "field")).isTrue();

        // attribute-level rules deferred — repository must NOT be consulted on this path
        verify(ruleRepository, never()).findEnabledExcludedEntityNames();
    }

    @Test
    void canModifyIsFalseWhenDenied() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        MetaClass mc = entityMock("entityHidden");
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(Set.of("entityHidden"));

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> when(mockCtx.isUpdatePermitted()).thenReturn(true))) {

            LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
            assertThat(policy.canModify(mc)).isFalse();
        }
    }

    @Test
    void canModifyIsFalseWhenAccessManagerDenies() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        MetaClass mc = entityMock("entityA");
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(Set.of());

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> when(mockCtx.isUpdatePermitted()).thenReturn(false))) {

            LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
            assertThat(policy.canModify(mc)).isFalse();
        }
    }

    @Test
    void canModifyIsTrueWhenAccessManagerAllowsAndNotDenied() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        MetaClass mc = entityMock("entityA");
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(Set.of("otherEntity"));

        try (MockedConstruction<CrudEntityContext> crud = Mockito.mockConstruction(
                CrudEntityContext.class,
                (mockCtx, ctx) -> when(mockCtx.isUpdatePermitted()).thenReturn(true))) {

            LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
            assertThat(policy.canModify(mc)).isTrue();
            verify(accessManager).applyRegisteredConstraints(any(CrudEntityContext.class));
        }
    }

    @Test
    void getDenylistedEntityNamesReturnsRepositoryResult() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        Set<String> denied = new LinkedHashSet<>(Set.of("entityA", "entityB"));
        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(denied);

        LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
        assertThat(policy.getDenylistedEntityNames()).containsExactlyInAnyOrder("entityA", "entityB");
    }

    @Test
    void getDenylistedEntityNamesReturnsEmptyWhenNoRules() {
        CurrentUserSchemaAccess delegate = mock(CurrentUserSchemaAccess.class);
        LlmExposureRuleRepository ruleRepository = mock(LlmExposureRuleRepository.class);
        AccessManager accessManager = mock(AccessManager.class);

        when(ruleRepository.findEnabledExcludedEntityNames()).thenReturn(Set.of());

        LlmExposurePolicy policy = new LlmExposurePolicy(delegate, ruleRepository, accessManager);
        assertThat(policy.getDenylistedEntityNames()).isEmpty();
    }
}
